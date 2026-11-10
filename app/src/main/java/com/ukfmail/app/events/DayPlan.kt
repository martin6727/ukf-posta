package com.ukfmail.app.events

import com.ukfmail.app.ais.Lesson
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/** A lesson with what the mail says about it. */
data class PlannedLesson(val lesson: Lesson, val off: StudyEvent? = null) {
    val cancelled get() = off != null
}

/** One day of the timetable after applying days off, cancellations and other events from mail. */
data class DayPlan(
    val lessons: List<PlannedLesson>,
    /** Day off covering the day (or part of it), shown as a banner. */
    val freeDays: List<StudyEvent>,
    /** Excursions, tests, deadlines, and cancellations that matched no lesson. */
    val notes: List<StudyEvent>,
)

object Plans {

    fun day(date: LocalDate, lessons: List<Lesson>, events: List<StudyEvent>): DayPlan {
        val today = events.filter { it.date == date }
            // The same notice often comes twice (oznam and AiS); one is enough.
            .distinctBy { listOf(it.kind, it.title.lowercase(), it.from, it.to, it.subjectHint.takeIf { _ -> it.kind == EventKind.CANCELLED }?.let { h -> MailEvents.fold(h).take(60) }) }
        val free = (today.filter { it.kind == EventKind.FREE_DAY } + Holidays.on(date)).distinctBy { it.title }
        val cancels = today.filter { it.kind == EventKind.CANCELLED }
        val reason = HashMap<Lesson, StudyEvent>()
        val unmatched = ArrayList<StudyEvent>()
        for (c in cancels) {
            val hit = matchLessons(c, lessons)
            if (hit.isEmpty()) unmatched += c else hit.forEach { reason.putIfAbsent(it, c) }
        }
        for (f in free) lessons.filter { covers(f, it) }.forEach { reason.putIfAbsent(it, f) }
        val notes = today.filter { it.kind == EventKind.EVENT || it.kind == EventKind.TEST || it.kind == EventKind.DEADLINE } + unmatched
        return DayPlan(
            lessons = lessons.map { PlannedLesson(it, reason[it]) },
            freeDays = free,
            notes = notes.sortedWith(compareBy({ it.from ?: LocalTime.MIDNIGHT }, { it.title })),
        )
    }

    /** A whole-day free day covers everything; "od 7:30 do 12:30" or "do 13:00" only what overlaps. */
    private fun covers(free: StudyEvent, l: Lesson): Boolean {
        val s = l.start.toLocalTime()
        val e = l.end.toLocalTime()
        val from = free.from ?: LocalTime.MIN
        val to = free.to ?: LocalTime.MAX
        return s.isBefore(to) && e.isAfter(from)
    }

    /**
     * Which of the day's lessons a cancellation means:
     * 1. lessons whose subject the mail names (whole words or an acronym like "IKT"),
     * 2. otherwise the sender's own lessons ("výučba so mnou sa ruší"),
     * narrowed by the time when the mail gives one.
     */
    fun matchLessons(c: StudyEvent, lessons: List<Lesson>): List<Lesson> {
        if (lessons.isEmpty()) return emptyList()
        val hint = c.subjectHint.orEmpty()
        val scored = lessons.map { it to score(it.title, hint) }
        val strong = scored.filter { it.second >= 2 }.map { it.first }
        val weak = scored.filter { it.second == 1 }.map { it.first }
        val byTeacher = lessons.filter { taughtBy(it, c.teacher) }
        var hit = when {
            strong.isNotEmpty() -> strong
            weak.isNotEmpty() -> weak.filter { it in byTeacher }.ifEmpty { if (weak.size == 1) weak else emptyList() }
            else -> byTeacher
        }
        c.from?.let { t ->
            val atTime = hit.ifEmpty { lessons }.filter { abs(ChronoUnit.MINUTES.between(it.start.toLocalTime(), t)) <= 20 }
            if (atTime.isNotEmpty()) hit = atTime
        }
        return hit
    }

    private val STOP = setOf("predmet", "predmetu", "vyucba", "vyucby", "prednaska", "cvicenie", "seminar", "hodina", "zaklady", "uvod")

    /** 2 = sure (two words of the name, or its acronym), 1 = one word of the name, 0 = not named. */
    fun score(title: String, hint: String): Int {
        val h = MailEvents.fold(hint)
        val hintWords = h.split(Regex("""[^a-z0-9]+""")).filter { it.length >= 4 }.map { it.take(6) }.toSet()
        val words = MailEvents.fold(title).split(Regex("""[^a-z0-9]+""")).filter { it.length >= 4 && it !in STOP }
        val stems = words.map { it.take(6) }.distinct()
        val shared = stems.count { it in hintWords }
        // "IKT" for "Informačné a komunikačné technológie vo vzdelávaní".
        val acronym = MailEvents.fold(title).split(Regex("""[^a-z0-9]+""")).filter { it.length >= 3 }.joinToString("") { it.take(1) }
        val tokens = Regex("""\b[A-ZÁČĎÉÍĽĹŇÓÔŔŠŤÚÝŽ]{2,6}\b""").findAll(hint).map { MailEvents.fold(it.value) }
        val acronymHit = tokens.any { it.length >= 2 && acronym.startsWith(it) && it.length >= minOf(3, acronym.length) }
        return when {
            acronymHit || shared >= 2 || (stems.size == 1 && shared == 1) -> 2
            shared == 1 -> 1
            else -> 0
        }
    }

    private fun taughtBy(l: Lesson, teacher: String?): Boolean {
        if (teacher == null) return false
        val t = MailEvents.fold(teacher)
        return l.teacher?.let { MailEvents.fold(it).contains(t) } == true
    }
}

/** Slovak public holidays that are days off. 1 Sep and 17 Nov stopped being days off in 2025. */
object Holidays {
    fun on(date: LocalDate): List<StudyEvent> {
        val name = names(date.year)[date] ?: return emptyList()
        return listOf(StudyEvent(EventKind.FREE_DAY, date, title = name, snippet = "Štátny sviatok", mailUid = "", mailSubject = ""))
    }

    private val cache = HashMap<Int, Map<LocalDate, String>>()

    private fun names(y: Int): Map<LocalDate, String> = cache.getOrPut(y) {
        val easter = easterSunday(y)
        buildMap {
            put(LocalDate.of(y, 1, 1), "Deň vzniku Slovenskej republiky")
            put(LocalDate.of(y, 1, 6), "Zjavenie Pána")
            put(easter.minusDays(2), "Veľký piatok")
            put(easter.plusDays(1), "Veľkonočný pondelok")
            put(LocalDate.of(y, 5, 1), "Sviatok práce")
            put(LocalDate.of(y, 5, 8), "Deň víťazstva nad fašizmom")
            put(LocalDate.of(y, 7, 5), "Sviatok sv. Cyrila a Metoda")
            put(LocalDate.of(y, 8, 29), "Výročie SNP")
            put(LocalDate.of(y, 9, 15), "Sedembolestná Panna Mária")
            put(LocalDate.of(y, 11, 1), "Sviatok všetkých svätých")
            put(LocalDate.of(y, 12, 24), "Štedrý deň")
            put(LocalDate.of(y, 12, 25), "Prvý sviatok vianočný")
            put(LocalDate.of(y, 12, 26), "Druhý sviatok vianočný")
        }
    }

    /** Gregorian Easter (anonymous algorithm). */
    fun easterSunday(y: Int): LocalDate {
        val a = y % 19; val b = y / 100; val c = y % 100; val d = b / 4; val e = b % 4
        val f = (b + 8) / 25; val g = (b - f + 1) / 3; val h = (19 * a + b - d - g + 15) % 30
        val i = c / 4; val k = c % 4; val l = (32 + 2 * e + 2 * i - h - k) % 7
        val m = (a + 11 * h + 22 * l) / 451
        val month = (h + l - 7 * m + 114) / 31
        val day = (h + l - 7 * m + 114) % 31 + 1
        return LocalDate.of(y, month, day)
    }
}
