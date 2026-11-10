package com.ukfmail.app

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ukfmail.app.ais.AisClient
import com.ukfmail.app.ais.AisLoginException
import com.ukfmail.app.ais.AisRepo
import com.ukfmail.app.ais.Exam
import com.ukfmail.app.ais.ExamDetail
import com.ukfmail.app.ais.StudyData
import com.ukfmail.app.ais.StudySheet
import com.ukfmail.app.events.EventScan
import com.ukfmail.app.events.MailEventStore
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** State behind the Rozvrh, Skúšky and Známky tabs. */
class StudyViewModel(app: Application) : AndroidViewModel(app) {
    private val store = Store(app)
    private val repo = AisRepo(app)
    private val lock = Mutex()
    private var client: AisClient? = null

    var data by mutableStateOf(repo.cached(store.aisSheet) ?: StudyData()); private set
    var loading by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    /** AiS2 refused the login; the tabs then ask for AiS2 credentials. */
    var needsLogin by mutableStateOf(false); private set
    var toast by mutableStateOf<String?>(null)
    /** Exam whose sign-up or sign-off is running. */
    var busyExam by mutableStateOf<String?>(null); private set

    private val eventStore = MailEventStore(app)
    /** What mail says about days: days off, cancelled lessons, excursions, tests. */
    var mailEvents by mutableStateOf(eventStore.events()); private set

    init {
        // Reload whenever a mail scan finishes.
        viewModelScope.launch {
            WorkManager.getInstance(app).getWorkInfosForUniqueWorkFlow(EventScan.WORK).collect { infos ->
                if (infos.any { it.state.isFinished }) mailEvents = withContext(Dispatchers.IO) { eventStore.events() }
            }
        }
    }

    val suggestedLogin: String get() = store.aisLogin ?: store.credentials?.user.orEmpty()

    private var started = false

    /** Loads once, the first time a study tab is opened. */
    fun start() {
        if (started) return
        started = true
        refresh()
    }

    private suspend fun <T> ais(block: (AisClient) -> T): T = withContext(Dispatchers.IO) {
        lock.withLock {
            val c = client ?: run {
                val creds = store.aisCredentials ?: throw AisLoginException("Nie si prihlásený.")
                AisClient(creds.user, creds.password).also { client = it }
            }
            try {
                block(c)
            } catch (e: AisLoginException) {
                client = null
                throw e
            }
        }
    }

    fun refresh() {
        if (loading) return
        loading = true
        error = null
        viewModelScope.launch {
            try {
                val fresh = ais { repo.fetch(it, store.aisSheet) }
                data = fresh
                needsLogin = false
                store.aisError = null
                if (fresh.sheets.isEmpty()) error = "AiS2 neukazuje žiadne štúdium."
            } catch (e: AisLoginException) {
                needsLogin = true
                error = e.message
            } catch (e: Exception) {
                error = e.message ?: "AiS2 sa nepodarilo načítať."
            } finally {
                loading = false
            }
        }
    }

    fun selectSheet(sheet: StudySheet) {
        if (sheet.id == data.sheet?.id) return
        store.aisSheet = sheet.id
        data = data.copy(sheet = sheet, grades = emptyList(), exams = emptyList(), averages = null, progress = null)
        refresh()
    }

    /** Saves AiS2 credentials that differ from the mail ones, then tries again. */
    fun signIn(login: String, password: String) {
        val mail = store.credentials
        store.aisLogin = login.trim().takeIf { it != mail?.user }
        store.aisPassword = password.takeIf { it != mail?.password }
        client = null
        refresh()
    }

    suspend fun detail(exam: Exam): Result<ExamDetail> =
        runCatching { ais { repo.examDetail(it, exam) } }

    fun register(exam: Exam, showName: Boolean, done: () -> Unit = {}) = change(exam, "Si prihlásený na termín.", done) {
        repo.register(it, exam, showName)
    }

    fun unregister(exam: Exam, done: () -> Unit = {}) = change(exam, "Si odhlásený z termínu.", done) {
        repo.unregister(it, exam)
    }

    private fun change(exam: Exam, ok: String, done: () -> Unit, action: (AisClient) -> List<Exam>) {
        if (busyExam != null) return
        busyExam = exam.id
        viewModelScope.launch {
            try {
                val list = ais(action)
                if (list.isNotEmpty()) data = data.copy(exams = list) else refresh()
                // Terms the user signed up for are not "new" for the background check.
                store.knownTerms = store.knownTerms + exam.id
                toast = ok
                done()
            } catch (e: Exception) {
                toast = e.message ?: "Nepodarilo sa."
            } finally {
                busyExam = null
            }
        }
    }
}
