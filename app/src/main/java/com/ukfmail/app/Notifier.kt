package com.ukfmail.app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.ukfmail.app.ais.Exam
import com.ukfmail.app.ais.Grade
import com.ukfmail.app.mail.MailHeader

object Notifier {
    const val CHANNEL_MAIL = "new_mail"
    const val CHANNEL_ACCOUNT = "account"
    const val CHANNEL_STUDY = "study"
    private const val GROUP = "ukf_inbox"
    private const val SUMMARY_ID = 1
    private const val LOGIN_ID = 2

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_MAIL, "Nové e-maily", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Upozornenie na každý nový e-mail v doručenej pošte"
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_STUDY, "Štúdium (AiS2)", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Nové známky, nové termíny skúšok a pripomienka deň pred skúškou"
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ACCOUNT, "Účet", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Problémy s prihlásením"
            }
        )
    }

    fun idFor(uid: String) = 1000 + (uid.hashCode() and 0x3fffffff)

    fun showNew(context: Context, mails: List<MailHeader>, unreadTotal: Int) {
        if (mails.isEmpty() || !allowed(context)) return
        val nm = NotificationManagerCompat.from(context)
        val color = ContextCompat.getColor(context, R.color.accent)

        // Oldest first, so the newest ends up on top of the shade.
        for (m in mails.reversed()) {
            val open = PendingIntent.getActivity(
                context, idFor(m.uid),
                Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_UID, m.uid)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val read = PendingIntent.getBroadcast(
                context, idFor(m.uid),
                Intent(context, NotificationActionReceiver::class.java)
                    .setAction(NotificationActionReceiver.ACTION_MARK_READ)
                    .putExtra(MainActivity.EXTRA_UID, m.uid),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val n = NotificationCompat.Builder(context, CHANNEL_MAIL)
                .setSmallIcon(R.drawable.ic_stat_mail)
                .setColor(color)
                .setContentTitle(m.fromName)
                .setContentText(m.subject)
                .setStyle(NotificationCompat.BigTextStyle().bigText(m.subject))
                .setSubText(m.fromEmail.takeIf { it != m.fromName })
                .setWhen(m.date.takeIf { it > 0 } ?: System.currentTimeMillis())
                .setShowWhen(true)
                .setCategory(NotificationCompat.CATEGORY_EMAIL)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setGroup(GROUP)
                .setAutoCancel(true)
                .setContentIntent(open)
                .addAction(0, "Označiť ako prečítané", read)
                .build()
            nm.notify(idFor(m.uid), n)
        }

        val inbox = NotificationCompat.InboxStyle()
        mails.take(5).forEach { inbox.addLine("${it.fromName}  ${it.subject}") }
        inbox.setSummaryText("$unreadTotal neprečítaných")
        val summary = NotificationCompat.Builder(context, CHANNEL_MAIL)
            .setSmallIcon(R.drawable.ic_stat_mail)
            .setColor(color)
            .setContentTitle("Nové e-maily")
            .setContentText("$unreadTotal neprečítaných")
            .setStyle(inbox)
            .setNumber(unreadTotal)
            .setGroup(GROUP)
            .setGroupSummary(true)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            .setAutoCancel(true)
            .setContentIntent(openApp(context))
            .build()
        nm.notify(SUMMARY_ID, summary)
    }

    fun showLoginProblem(context: Context) {
        if (!allowed(context)) return
        val n = NotificationCompat.Builder(context, CHANNEL_ACCOUNT)
            .setSmallIcon(R.drawable.ic_stat_mail)
            .setColor(ContextCompat.getColor(context, R.color.accent))
            .setContentTitle("Neviem sa prihlásiť do študentskej pošty")
            .setContentText("Otvor appku a prihlás sa znova.")
            .setAutoCancel(true)
            .setContentIntent(openApp(context))
            .build()
        NotificationManagerCompat.from(context).notify(LOGIN_ID, n)
    }

    fun showGrade(context: Context, g: Grade) {
        val cool = com.ukfmail.app.ui.coolGrade(g.grade).takeIf { Store(context).coolGrades }
        val text = listOfNotNull(cool ?: g.gradeText, g.examiner).joinToString(" · ").ifBlank { "${g.credits} kreditov" }
        study(context, "grade:${g.id}", "Nová známka ${g.grade} · ${g.name}", text, MainActivity.TAB_GRADES)
    }

    fun showNewTerms(context: Context, exams: List<Exam>) {
        if (exams.isEmpty()) return
        val subjects = exams.map { it.name }.distinct()
        val title = if (exams.size == 1) "Nový termín skúšky" else "Nové termíny skúšok (${exams.size})"
        val text = if (exams.size == 1) exams[0].let { "${it.name}, ${it.dateText} ${it.time.orEmpty()}".trim() }
        else subjects.take(4).joinToString(", ") + if (subjects.size > 4) "…" else ""
        study(context, "terms", title, text, MainActivity.TAB_EXAMS)
    }

    fun showExamReminder(context: Context, e: Exam) {
        val where = listOfNotNull(e.time?.let { "o $it" }, e.dateText.takeIf { it.isNotBlank() }).joinToString(", ")
        study(context, "exam:${e.id}", "Zajtra skúška: ${e.name}", "Si prihlásený $where. Veľa šťastia!", MainActivity.TAB_EXAMS)
    }

    private fun study(context: Context, key: String, title: String, text: String, tab: String) {
        if (!allowed(context)) return
        val id = 500_000 + (key.hashCode() and 0xfffff)
        val open = PendingIntent.getActivity(
            context, id,
            Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_TAB, tab)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(context, CHANNEL_STUDY)
            .setSmallIcon(R.drawable.ic_stat_school)
            .setColor(ContextCompat.getColor(context, R.color.accent))
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        NotificationManagerCompat.from(context).notify(id, n)
    }

    fun cancel(context: Context, uids: Collection<String>) {
        val nm = NotificationManagerCompat.from(context)
        uids.forEach { nm.cancel(idFor(it)) }
    }

    fun cancelSummary(context: Context) = NotificationManagerCompat.from(context).cancel(SUMMARY_ID)

    fun cancelAll(context: Context) = NotificationManagerCompat.from(context).cancelAll()

    private fun openApp(context: Context) = PendingIntent.getActivity(
        context, 0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun allowed(context: Context) =
        android.os.Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
}

class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_MARK_READ) return
        val uid = intent.getStringExtra(MainActivity.EXTRA_UID) ?: return
        Notifier.cancel(context, listOf(uid))
        MailCheck.markRead(context, uid)
    }

    companion object {
        const val ACTION_MARK_READ = "com.ukfmail.app.MARK_READ"
    }
}
