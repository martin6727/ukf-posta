package com.ukfmail.app.events

import com.ukfmail.app.mail.BodyParser
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Runs the mail reader over a folder of saved mails (From/Date/Subject header lines, then the text)
 * and prints what it finds. Only runs with MAIL_DUMP_DIR set; the mails themselves stay out of git.
 */
class MailEventsDump {
    @Test fun dump() {
        val dir = System.getenv("MAIL_DUMP_DIR")?.let(::File)
        assumeTrue(dir != null && dir.isDirectory)
        val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        val report = StringBuilder()
        dir!!.listFiles { f -> f.name.endsWith(".txt") }!!.sortedBy { it.name }.forEach { f ->
            val lines = f.readText().lines()
            val from = lines[0].removePrefix("From: ")
            val m = MailInput(
                uid = f.name,
                fromName = from.substringBefore(" <").trim(),
                fromEmail = from.substringAfter("<", "").substringBefore(">"),
                subject = lines[2].removePrefix("Subject: "),
                text = BodyParser.decodeEntities(lines.drop(4).joinToString("\n")),
                sent = LocalDateTime.parse(lines[1].removePrefix("Date: "), fmt),
            )
            val events = MailEvents.extract(m)
            if (events.isNotEmpty()) {
                report.append("== ${f.name} | ${m.subject}\n")
                events.forEach { e ->
                    report.append("   ${e.kind} ${e.date} ${e.from ?: ""}${e.to?.let { "-$it" } ?: ""} \"${e.title}\" teacher=${e.teacher}\n")
                    report.append("      « ${e.snippet.take(140)} »\n")
                }
            }
        }
        // With the real timetable next to the mails: which lessons each day's events hit.
        val timetable = File(dir.parentFile, "rozvrh.json").takeIf { it.exists() }?.readText()
        if (timetable != null) {
            val all = dir.listFiles { f -> f.name.endsWith(".txt") }!!.flatMap { f ->
                val lines = f.readText().lines()
                val from = lines[0].removePrefix("From: ")
                MailEvents.extract(
                    MailInput(f.name, from.substringBefore(" <").trim(), from.substringAfter("<", "").substringBefore(">"),
                        lines[2].removePrefix("Subject: "), BodyParser.decodeEntities(lines.drop(4).joinToString("\n")),
                        LocalDateTime.parse(lines[1].removePrefix("Date: "), fmt))
                )
            }
            report.append("\n######## applied to the timetable\n")
            for (d in all.map { it.date }.distinct().sorted()) {
                val lessons = com.ukfmail.app.ais.AisParse.lessonsFromJson(timetable, d, d)
                if (lessons.isEmpty()) continue
                val plan = Plans.day(d, lessons, all)
                report.append("$d ${d.dayOfWeek}\n")
                plan.freeDays.forEach { report.append("   FREE ${it.title} ${it.from ?: ""}-${it.to ?: ""}\n") }
                plan.lessons.forEach { p -> report.append("   ${if (p.cancelled) "X" else " "} ${p.lesson.start.toLocalTime()} ${p.lesson.title} [${p.lesson.teacher}]${p.off?.let { " <- ${it.mailUid}" } ?: ""}\n") }
                plan.notes.forEach { report.append("   * ${it.kind} ${it.title} (${it.mailUid})\n") }
            }
        }
        File(dir.parentFile, "events-report.txt").writeText(report.toString())
    }
}
