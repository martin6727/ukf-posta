package com.ukfmail.app.events

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.ukfmail.app.Store
import com.ukfmail.app.mail.BodyParser
import com.ukfmail.app.mail.MailHeader
import com.ukfmail.app.mail.MailService
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Events found in mail, kept in one JSON file together with which mails were already read. */
class MailEventStore(context: Context) {
    private companion object {
        /** Bump when [MailEvents] gets better, so already read mail is read again. */
        const val VERSION = 2
    }

    private val file = File(context.filesDir, "mail-events.json")

    @Volatile private var cache: Pair<Set<String>, List<StudyEvent>>? = null

    @Synchronized
    private fun load(): Pair<Set<String>, List<StudyEvent>> {
        cache?.let { return it }
        // A newer reader reads all mail again.
        val o = runCatching { JSONObject(file.readText()) }.getOrNull()?.takeIf { it.optInt("v") == VERSION }
        val scanned = o?.optJSONArray("scanned")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }.orEmpty()
        val events = o?.optJSONArray("events")?.let { a -> (0 until a.length()).mapNotNull { runCatching { fromJson(a.getJSONObject(it)) }.getOrNull() } }.orEmpty()
        return (scanned to events).also { cache = it }
    }

    fun events(): List<StudyEvent> = load().second

    fun scanned(): Set<String> = load().first

    @Synchronized
    fun add(found: Map<String, List<StudyEvent>>, alsoScanned: Collection<String>) {
        val (scanned, events) = load()
        val next = (scanned + found.keys + alsoScanned) to (events.filter { it.mailUid !in found.keys } + found.values.flatten())
        val o = JSONObject()
            .put("v", VERSION)
            .put("scanned", JSONArray(next.first.toList()))
            .put("events", JSONArray(next.second.map(::toJson)))
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(o.toString())
        tmp.renameTo(file)
        cache = next
    }

    fun clear() {
        file.delete()
        cache = null
    }

    private fun toJson(e: StudyEvent) = JSONObject()
        .put("kind", e.kind.name).put("date", e.date.toString())
        .put("from", e.from?.toString()).put("to", e.to?.toString())
        .put("title", e.title).put("hint", e.subjectHint).put("teacher", e.teacher)
        .put("snippet", e.snippet).put("uid", e.mailUid).put("subject", e.mailSubject)

    private fun fromJson(o: JSONObject) = StudyEvent(
        kind = EventKind.valueOf(o.getString("kind")),
        date = LocalDate.parse(o.getString("date")),
        from = o.optString("from").takeIf { it.isNotBlank() && it != "null" }?.let(LocalTime::parse),
        to = o.optString("to").takeIf { it.isNotBlank() && it != "null" }?.let(LocalTime::parse),
        title = o.getString("title"),
        subjectHint = o.optString("hint").takeIf { it.isNotBlank() && it != "null" },
        teacher = o.optString("teacher").takeIf { it.isNotBlank() && it != "null" },
        snippet = o.optString("snippet"),
        mailUid = o.getString("uid"),
        mailSubject = o.optString("subject"),
    )
}

/** Reads new mail from UKF staff, Moodle and the rector's office and keeps what it says about days. */
object EventScan {
    const val WORK = "mail-event-scan"
    /** How far back the first scan goes. */
    private const val DAYS_BACK = 200L

    fun enqueue(context: Context) {
        val req = OneTimeWorkRequestBuilder<EventScanWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK, ExistingWorkPolicy.KEEP, req)
    }

    /** Staff and university systems write from @ukf.sk; students from @student.ukf.sk. */
    fun relevant(h: MailHeader) = h.fromEmail.lowercase().let { it.endsWith("@ukf.sk") && !it.endsWith("@student.ukf.sk") }

    fun scan(context: Context, mail: MailService, limit: Int = 80) {
        val store = MailEventStore(context)
        val done = store.scanned()
        val (headers, _) = mail.list("INBOX", count = 150)
        val since = System.currentTimeMillis() - DAYS_BACK * 86_400_000L
        val fresh = headers.filter { it.uid !in done }
        val skip = fresh.filter { !relevant(it) || it.date in 1 until since }.map { it.uid }
        val todo = fresh.filter { relevant(it) && (it.date == 0L || it.date >= since) }.take(limit)
        val found = LinkedHashMap<String, List<StudyEvent>>()
        for (h in todo) {
            val body = runCatching { mail.read("INBOX", h.uid, markSeen = false) }.getOrNull() ?: continue
            val text = body.plainText.ifBlank { BodyParser.htmlToText(body.html) }.let(BodyParser::decodeEntities)
            val sent = if (h.date > 0) LocalDateTime.ofInstant(Instant.ofEpochMilli(h.date), ZoneId.systemDefault()) else LocalDateTime.now()
            found[h.uid] = runCatching {
                MailEvents.extract(MailInput(h.uid, h.fromName, h.fromEmail, h.subject, text, sent))
            }.getOrDefault(emptyList())
        }
        store.add(found, skip)
    }
}

class EventScanWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val store = Store(applicationContext)
        val c = store.credentials ?: return@withContext Result.success()
        val mail = MailService(c.user, c.password, protocol = store.protocol)
        try {
            mail.connect()
            EventScan.scan(applicationContext, mail)
            Result.success()
        } catch (e: Exception) {
            if (runAttemptCount < 2) Result.retry() else Result.success()
        } finally {
            mail.close()
        }
    }
}
