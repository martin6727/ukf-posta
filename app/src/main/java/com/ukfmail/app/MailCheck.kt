package com.ukfmail.app

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.ukfmail.app.mail.BadLoginException
import com.ukfmail.app.mail.MailHeader
import com.ukfmail.app.mail.MailService
import com.ukfmail.app.mail.Protocol
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object MailCheck {
    private const val PERIODIC = "mail-check"
    private const val NOW = "mail-check-now"

    private val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /** Starts (or restarts with a new interval) the background check, or stops it. */
    fun schedule(context: Context) {
        val store = Store(context)
        val wm = WorkManager.getInstance(context)
        if (store.credentials == null || !store.notificationsEnabled) {
            wm.cancelUniqueWork(PERIODIC)
            return
        }
        val req = PeriodicWorkRequestBuilder<MailCheckWorker>(store.intervalMinutes.toLong(), TimeUnit.MINUTES)
            .setConstraints(online)
            .build()
        wm.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, req)
    }

    fun checkNow(context: Context) {
        val req = OneTimeWorkRequestBuilder<MailCheckWorker>().setConstraints(online).build()
        WorkManager.getInstance(context).enqueueUniqueWork(NOW, ExistingWorkPolicy.REPLACE, req)
    }

    fun markRead(context: Context, uid: String) {
        val req = OneTimeWorkRequestBuilder<MarkReadWorker>()
            .setConstraints(online)
            .setInputData(workDataOf("uid" to uid))
            .build()
        WorkManager.getInstance(context).enqueue(req)
    }
}

class MailCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val store = Store(applicationContext)
        val creds = store.credentials ?: return@withContext Result.success()
        val mail = MailService(creds.user, creds.password, protocol = store.protocol)
        try {
            store.protocol = mail.connect()
            var unread: List<MailHeader> = mail.unreadInbox()
            mail.close()
            // POP3 lists every message; the ones opened in the app count as read.
            if (store.protocol == Protocol.POP3) unread = unread.filter { it.uid !in store.popSeen }

            val known = store.knownUnread
            val current = unread.map { it.uid }.toSet()
            val fresh = unread.filter { it.uid !in known }

            val firstRun = !store.baselineDone
            if (store.baselineDone && store.notificationsEnabled) Notifier.showNew(applicationContext, fresh, unread.size)
            // New mail may cancel a lesson or announce a day off: read it for the timetable.
            if (fresh.isNotEmpty() || firstRun) com.ukfmail.app.events.EventScan.enqueue(applicationContext)
            // Mail read elsewhere (in the app or on a computer): its notification goes away.
            Notifier.cancel(applicationContext, known - current)
            if (current.isEmpty()) Notifier.cancelSummary(applicationContext)

            store.knownUnread = current
            store.baselineDone = true
            store.unreadCount = unread.size
            store.lastCheck = System.currentTimeMillis()
            store.lastError = null
            Result.success()
        } catch (e: BadLoginException) {
            store.lastError = "Prihlásenie zlyhalo. Zmenil si heslo? Prihlás sa v appke znova."
            Notifier.showLoginProblem(applicationContext)
            Result.success()
        } catch (e: IOException) {
            mail.close()
            store.lastError = "Nepodarilo sa spojiť so serverom (${e.message})"
            if (runAttemptCount < 3) Result.retry() else Result.success()
        }
    }
}

class MarkReadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val uid = inputData.getString("uid") ?: return@withContext Result.success()
        val store = Store(applicationContext)
        val creds = store.credentials ?: return@withContext Result.success()
        if (store.protocol == Protocol.POP3) {
            store.popSeen = store.popSeen + uid
            store.knownUnread = store.knownUnread - uid
            return@withContext Result.success()
        }
        val mail = MailService(creds.user, creds.password, protocol = store.protocol)
        try {
            mail.setSeen("INBOX", uid, true)
            mail.close()
            store.knownUnread = store.knownUnread - uid
            Result.success()
        } catch (e: BadLoginException) {
            Result.success()
        } catch (e: IOException) {
            mail.close()
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }
}
