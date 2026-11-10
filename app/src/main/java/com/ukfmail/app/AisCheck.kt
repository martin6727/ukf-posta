package com.ukfmail.app

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.ukfmail.app.ais.AisClient
import com.ukfmail.app.ais.AisLoginException
import com.ukfmail.app.ais.AisRepo
import com.ukfmail.app.ais.Exam
import com.ukfmail.app.ais.Grade
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Hourly look at AiS2: new grades, new exam terms, and a reminder the day before a signed-up exam. */
object AisCheck {
    private const val PERIODIC = "ais-check"

    fun schedule(context: Context) {
        val store = Store(context)
        val wm = WorkManager.getInstance(context)
        if (store.credentials == null || !store.notificationsEnabled || !store.aisNotify) {
            wm.cancelUniqueWork(PERIODIC)
            return
        }
        val req = PeriodicWorkRequestBuilder<AisCheckWorker>(60, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        wm.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, req)
    }

    fun gradeKey(g: Grade) = "${g.id}:${g.grade}"

    /** Terms that could still be signed up for, and which weren't seen before. */
    fun newTerms(exams: List<Exam>, known: Set<String>) = exams.filter { it.upcoming && !it.registered && it.id !in known }

    /** Signed-up exams starting within the next 24 hours. */
    fun dueReminders(exams: List<Exam>, reminded: Set<String>, now: LocalDateTime) = exams.filter {
        val s = it.start
        it.registered && it.id !in reminded && s != null && s.isAfter(now) && !s.isAfter(now.plusHours(24))
    }
}

class AisCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val ctx = applicationContext
        val store = Store(ctx)
        val creds = store.aisCredentials ?: return@withContext Result.success()
        if (!store.aisNotify || !store.notificationsEnabled) return@withContext Result.success()
        try {
            // The timetable doesn't change from hour to hour; the app fetches it when opened.
            val data = AisRepo(ctx).fetch(AisClient(creds.user, creds.password), store.aisSheet, withTimetable = false)
            val announce = store.aisBaseline

            val graded = data.grades.filter { it.grade != null }
            val freshGrades = graded.filter { AisCheck.gradeKey(it) !in store.knownGrades }
            if (announce) freshGrades.forEach { Notifier.showGrade(ctx, it) }
            store.knownGrades = store.knownGrades + graded.map { AisCheck.gradeKey(it) }

            val terms = AisCheck.newTerms(data.exams, store.knownTerms)
            if (announce) Notifier.showNewTerms(ctx, terms)
            store.knownTerms = store.knownTerms + data.exams.map { it.id }

            val due = AisCheck.dueReminders(data.exams, store.remindedExams, LocalDateTime.now())
            due.forEach { Notifier.showExamReminder(ctx, it) }
            store.remindedExams = store.remindedExams + due.map { it.id }

            store.aisBaseline = true
            store.aisError = null
            Result.success()
        } catch (e: AisLoginException) {
            store.aisError = e.message
            Result.success()
        } catch (e: Exception) {
            store.aisError = e.message
            if (runAttemptCount < 2) Result.retry() else Result.success()
        }
    }
}
