package com.ukfmail.app.events

import java.text.Normalizer
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.TemporalAdjusters

enum class EventKind { FREE_DAY, CANCELLED, EVENT, TEST, DEADLINE }

/** Something a mail says about a day: a day off, a cancelled lesson, an excursion, a test, a deadline. */
data class StudyEvent(
    val kind: EventKind,
    val date: LocalDate,
    val from: LocalTime? = null,
    val to: LocalTime? = null,
    /** Short label: "Rektorské voľno", "Exkurzia", "test 2"… */
    val title: String,
    /** Text that names the subject ("nebudeme mať predmet Webové technológie a ani IKT"), matched against lessons. */
    val subjectHint: String? = null,
    /** Surname of the teacher who sent it, e.g. "Nováková". */
    val teacher: String? = null,
    /** The sentence it came from. */
    val snippet: String,
    val mailUid: String,
    val mailSubject: String,
)

data class MailInput(
    val uid: String,
    val fromName: String,
    val fromEmail: String,
    val subject: String,
    val text: String,
    val sent: LocalDateTime,
)

/**
 * Offline reader for UKF mail. The patterns come from real mail: teachers write through AiS2
 * ("Správu z AiS2 odoslal: doc. … Nováková"), e.g. "dňa 27. októbra nebudeme mať výučbu",
 * "dňa 24.9.2025 odpadá prednáška z predmetu …", "zajtrajšia výučba sa ruší", "výučba tento týždeň
 * ruší"; the rector's office sends "Rektorské voľno dňa 30. októbra 2026"; Moodle sends
 * "Due: utorok, 12 mája 2026, 00:00" and "Otvára: … / Končí: …".
 */
object MailEvents {

    fun extract(m: MailInput): List<StudyEvent> {
        val source = sourceOf(m) ?: return emptyList()
        val sentOn = m.sent.toLocalDate()
        val body = ownPart(m.text)
        val out = when (source) {
            Source.MOODLE -> moodle(m, body)
            Source.NOTICE -> freeDays(m, body, sentOn, teacher = null)
            Source.TEACHER -> teacherMail(m, body, sentOn)
        }
        // "Dobrý deň, dňa 27. októbra…" reads better without the greeting.
        val tidy = out.map { it.copy(snippet = GREETING.replace(it.snippet, "").replaceFirstChar { c -> c.uppercase() }) }
        // A mail repeats itself ("pripomínam…"): one event per kind, day and title.
        return tidy.distinctBy { Triple(it.kind, it.date, fold(it.title)) }
    }

    private val GREETING = Regex("""^\s*(Dobrý deň|Dobry den|Milí študenti|Milé študentky|Vážené študentky|Vážení študenti|Ahojte|Zdravím)[^,.!]*[,.!]\s*(milí študenti[,.!]?\s*)?""", RegexOption.IGNORE_CASE)

    private enum class Source { MOODLE, NOTICE, TEACHER }

    private fun sourceOf(m: MailInput): Source? {
        val email = m.fromEmail.lowercase()
        val name = fold(m.fromName)
        return when {
            "moodle" in name || email.startsWith("noreply-edu@") || email.startsWith("noreply-moodle@") -> Source.MOODLE
            email.startsWith("oznam") || name.startsWith("oznam ukf") -> Source.NOTICE
            AIS_SENDER.containsMatchIn(m.text) -> Source.TEACHER
            email.endsWith("@ukf.sk") && !email.startsWith("neodpovedat") && !email.startsWith("noreply") -> Source.TEACHER
            else -> null
        }
    }

    // --- Moodle ---

    private val MOODLE_DUE = Regex("""(?im)^\s*(?:Due|Termín odovzdania|Termín)\s*:\s*(.+)$""")
    private val MOODLE_ASSIGNMENT = Regex("""(?is)assignment\s+(.+?)\s+in course\s+(.+?)\s+is due""")
    private val MOODLE_ASSIGNMENT_SK = Regex("""(?is)(?:úloha|zadanie)\s+(.+?)\s+v (?:predmete|kurze)\s+(.+?)\s+(?:sa|je|má)""")
    private val MOODLE_OPENS = Regex("""(?im)^\s*Otvára\s*:\s*(.+)$""")
    private val MOODLE_CLOSES = Regex("""(?im)^\s*Končí\s*:\s*(.+)$""")
    private val MOODLE_TEST = Regex("""(?is)Test\s+(.+?)\s+v predmete\s+(.+?)\s+bude""")

    private fun moodle(m: MailInput, body: String): List<StudyEvent> {
        if (m.subject.trim().startsWith("Overdue", ignoreCase = true)) return emptyList()
        val out = ArrayList<StudyEvent>()
        MOODLE_OPENS.find(body)?.let { open ->
            val start = dateTime(open.groupValues[1], m.sent.toLocalDate()) ?: return@let
            val end = MOODLE_CLOSES.find(body)?.let { dateTime(it.groupValues[1], m.sent.toLocalDate()) }
            val t = MOODLE_TEST.find(body)
            out += StudyEvent(
                EventKind.TEST, start.first, start.second, end?.second?.takeIf { end.first == start.first },
                title = t?.groupValues?.get(1)?.trim() ?: m.subject.substringAfterLast(": ").trim(),
                subjectHint = t?.groupValues?.get(2)?.trim(), snippet = line(body, open.groups[1]!!.range.first),
                mailUid = m.uid, mailSubject = m.subject,
            )
        }
        MOODLE_DUE.find(body)?.let { due ->
            val at = dateTime(due.groupValues[1], m.sent.toLocalDate()) ?: return@let
            val a = MOODLE_ASSIGNMENT.find(body) ?: MOODLE_ASSIGNMENT_SK.find(body)
            out += StudyEvent(
                EventKind.DEADLINE, at.first, at.second,
                title = a?.groupValues?.get(1)?.trim() ?: m.subject.substringAfter(": ").substringAfter(": ").trim(),
                subjectHint = a?.groupValues?.get(2)?.trim(), snippet = line(body, due.groups[1]!!.range.first),
                mailUid = m.uid, mailSubject = m.subject,
            )
        }
        return out
    }

    // --- days off ---

    private val FREE = Regex("""(rektorsk|dekansk)\w*\s+voln""")

    private fun freeDays(m: MailInput, body: String, sentOn: LocalDate, teacher: String?): List<StudyEvent> {
        val all = fold(m.subject + "\n" + body)
        val kind = FREE.find(all) ?: return emptyList()
        val title = if (kind.groupValues[1].startsWith("rektor")) "Rektorské voľno" else "Dekanské voľno"
        // The subject usually carries the date ("Rektorské voľno dňa 30. októbra 2026").
        val sentences = sentences(body)
        val freeSentence = sentences.firstOrNull { FREE.containsMatchIn(fold(it)) }
        val dates = dates(m.subject, sentOn).ifEmpty { freeSentence?.let { dates(it, sentOn) }.orEmpty() }
            .ifEmpty { dates(body, sentOn).take(1) }
        if (dates.isEmpty()) return emptyList()
        val times = freeSentence?.let { timeRange(it) }
        // "voľno … iba do 13:00" / "od 07:30 hod. do 12:30 hod."
        return dates.map {
            StudyEvent(
                EventKind.FREE_DAY, it, times?.first, times?.second, title, teacher = teacher,
                snippet = freeSentence ?: m.subject, mailUid = m.uid, mailSubject = m.subject,
            )
        }
    }

    // --- teachers ---

    private val CANCEL = Regex(
        """nebude(me)?\s+mat|nebude(\s+sa)?\s+(konat|realizovat|prebiehat)|neuskutocn|\brusi\b|\bzrus|zrusen|odpada|odpadne|""" +
            """(prednaska|vyucba|cvicenie|seminar|hodina)\b[^.]{0,60}\bnebude\b|\bnebude\b[^.]{0,40}(prednaska|vyucba|cvicenie|seminar|hodina)"""
    )
    private val LESSON_WORD = Regex("""vyucb|vyucovan|prednask|cvicen|seminar|hodin|predmet""")
    private val HAPPENS_ANYWAY = Regex("""riadne\s+uskutocni|uskutocni\s+sa\s+riadne|bude\s+riadne""")
    private val EVENT_WORD = Regex("""exkurzi|instruktaz|imatrikulaci|konzultaci|obhajob|zasadnuti|prezentaci|promoci""")
    private val EVENT_VERB = Regex("""uskutocn|realizovat|kona\b|konat|zacina|pozyvam|bude\s+v|stretneme|dostavit|termin""")
    private val TEST_WORD = Regex("""pisomk|\btest|zapoct|skusk|kolokvi|odovzd""")

    private fun teacherMail(m: MailInput, body: String, sentOn: LocalDate): List<StudyEvent> {
        val teacher = teacherSurname(m)
        val out = ArrayList<StudyEvent>()
        out += freeDays(m, body, sentOn, teacher)
        val subjectDates = dates(m.subject, sentOn)
        val subjectFolded = fold(m.subject)
        for (s in sentences(body)) {
            val f = fold(s)
            if (HAPPENS_ANYWAY.containsMatchIn(f)) continue
            val explicit = dates(s, sentOn)
            val relative = relativeDates(f, sentOn)
            val days = explicit.ifEmpty { relative }.ifEmpty { subjectDates }
            val cancel = CANCEL.containsMatchIn(f) &&
                (LESSON_WORD.containsMatchIn(f) || LESSON_WORD.containsMatchIn(subjectFolded) || "so mnou" in f)
            when {
                cancel && days.isNotEmpty() -> {
                    val times = cancelTime(s)
                    days.forEach {
                        out += StudyEvent(
                            EventKind.CANCELLED, it, times?.first, times?.second, title = "Odpadá výučba",
                            subjectHint = s + " " + m.subject, teacher = teacher, snippet = s,
                            mailUid = m.uid, mailSubject = m.subject,
                        )
                    }
                }
                EVENT_WORD.containsMatchIn(f) && EVENT_VERB.containsMatchIn(f) && explicit.isNotEmpty() -> {
                    val word = EVENT_WORD.find(f)!!.value
                    out += explicit.map {
                        StudyEvent(
                            EventKind.EVENT, it, timeRange(s)?.first, null, title = eventTitle(word),
                            subjectHint = s, teacher = teacher, snippet = s, mailUid = m.uid, mailSubject = m.subject,
                        )
                    }
                }
                explicit.isNotEmpty() && (TEST_WORD.containsMatchIn(f) || (TEST_WORD.containsMatchIn(subjectFolded) && timeRange(s) != null)) -> {
                    val word = (TEST_WORD.find(f) ?: TEST_WORD.find(subjectFolded))!!.value.trim()
                    out += explicit.map {
                        StudyEvent(
                            if (word.startsWith("odovzd")) EventKind.DEADLINE else EventKind.TEST, it, timeRange(s)?.first, null,
                            title = testTitle(word), subjectHint = s + " " + m.subject, teacher = teacher, snippet = s,
                            mailUid = m.uid, mailSubject = m.subject,
                        )
                    }
                }
            }
        }
        return out
    }

    private fun eventTitle(word: String) = when {
        word.startsWith("exkurz") -> "Exkurzia"
        word.startsWith("instruk") -> "Inštruktáž"
        word.startsWith("imatrik") -> "Imatrikulácia"
        word.startsWith("konzult") -> "Konzultácia"
        word.startsWith("obhajob") -> "Obhajoba"
        word.startsWith("zasadn") -> "Zasadnutie"
        word.startsWith("prezent") -> "Prezentácia"
        word.startsWith("promoc") -> "Promócie"
        else -> "Udalosť"
    }

    private fun testTitle(word: String) = when {
        word.startsWith("pisomk") -> "Písomka"
        word.startsWith("test") -> "Test"
        word.startsWith("zapoct") -> "Zápočet"
        word.startsWith("skusk") -> "Skúška"
        word.startsWith("kolokv") -> "Kolokvium"
        else -> "Odovzdanie"
    }

    private val AIS_SENDER = Regex("""Správu z AiS2 odoslal:\s*(.+)""")
    private val TITLES = Regex("""\b(Mgr|Bc|Ing|PhDr|PaedDr|RNDr|doc|prof|PhD|MUDr|JUDr|Dr|MSc|MBA|ArtD|CSc|DrSc|h\.\s*c)\.?""", RegexOption.IGNORE_CASE)

    /** "doc. PaedDr. Jana Nováková, PhD." -> "Nováková". */
    fun teacherSurname(m: MailInput): String? {
        val full = AIS_SENDER.find(m.text)?.groupValues?.get(1) ?: m.fromName.takeIf { m.fromEmail.endsWith("@ukf.sk") } ?: return null
        val words = full.replace(TITLES, " ").replace(Regex("""[,."<>]"""), " ").split(Regex("\\s+")).filter { it.length > 1 && it[0].isLetter() }
        return words.lastOrNull()
    }

    // --- text helpers ---

    private val QUOTE = Regex(
        """(?im)^(-{2,}\s*(Original Message|Pôvodná správa|Forwarded message)\s*-{2,}|>|From:\s|Od:\s|Dňa .{0,80}nap[ií]sal|.{0,60}odosielateľ napísal:)"""
    )

    /** The mail's own text: quoted replies are cut off; a forward with nothing of its own keeps the first quoted message. */
    fun ownPart(text: String): String {
        val withoutSignature = text.lines().filterNot { it.trimStart().startsWith("Správu z AiS2 odoslal") }.joinToString("\n")
        val first = QUOTE.find(withoutSignature) ?: return withoutSignature
        val head = withoutSignature.substring(0, first.range.first)
        if (head.trim().length >= 20) return head
        val rest = withoutSignature.substring(first.range.last + 1)
        val next = QUOTE.find(rest)
        return if (next != null) rest.substring(0, next.range.first) else rest
    }

    /** Paragraphs split into sentences; a dot inside a date ("27. októbra", "8.10. sa") does not end one. */
    fun sentences(text: String): List<String> =
        text.split(Regex("""\n\s*\n|\n(?=\s*[-•*]|\s*[A-ZÁČĎÉÍĽĹŇÓÔŔŠŤÚÝŽ])"""))
            .map { it.replace(Regex("""\s+"""), " ").trim() }
            // "Technologies s.r.o. Nitra na 7.5." is one sentence: abbreviations keep their dot.
            .map { ABBREVIATIONS.replace(it) { m -> m.value.replace('.', HIDDEN_DOT) } }
            .flatMap { it.split(Regex("""(?<=[.!?])\s+(?=[A-ZÁČĎÉÍĽĹŇÓÔŔŠŤÚÝŽ])""")) }
            .map { it.replace(HIDDEN_DOT, '.').trim() }
            .filter { it.length > 3 }

    private const val HIDDEN_DOT = '․'
    private val ABBREVIATIONS = Regex(
        """(?i)\b(s\.\s?r\.\s?o\.|a\.\s?s\.|spol\.|č\.|p\.|napr\.|tzv\.|resp\.|t\.\s?j\.|atď\.|hod\.|min\.|ul\.|tel\.|""" +
            """doc\.|prof\.|ing\.|mgr\.|bc\.|phd\.|paeddr\.|rndr\.|phdr\.|judr\.|mudr\.|dr\.)"""
    )

    private fun line(text: String, at: Int): String {
        val start = text.lastIndexOf('\n', at - 1).let { if (it < 0) 0 else it + 1 }
        val end = text.indexOf('\n', at).let { if (it < 0) text.length else it }
        return text.substring(start, end).trim()
    }

    fun fold(s: String): String =
        Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("""\p{M}+"""), "")

    private val MONTHS = listOf(
        "januar", "februar", "marec|marca", "april", "maj", "jun", "jul", "august", "september|septembra",
        "oktober|oktobra", "november|novembra", "december|decembra",
    )
    private val MONTH_NAME = Regex(
        """(?<![\d.])(\d{1,2})\.?\s*(""" + MONTHS.joinToString("|") { "(?:$it)\\w*" } + """)(?:\s+(\d{4}))?"""
    )
    private val TWO_DAYS = Regex("""(?<![\d.,:])(\d{1,2})\.\s*a\s*(\d{1,2})\.\s*(\d{1,2})\.(?:\s*(\d{4}))?""")
    private val NUMERIC = Regex("""(?<![\d.,:])(\d{1,2})\.\s?(\d{1,2})(?:\.(?:\s?(\d{4}))?)?(?![\d,:]|\s*(?:hod|h\b))""")

    /** Dates written in [text]; a missing year is the one closest to when the mail was sent. */
    fun dates(text: String, sentOn: LocalDate): List<LocalDate> {
        var f = fold(text)
        val out = ArrayList<LocalDate>()
        for (m in TWO_DAYS.findAll(f)) {
            val month = m.groupValues[3].toInt()
            val year = m.groupValues[4].toIntOrNull()
            listOf(m.groupValues[1], m.groupValues[2]).forEach { d -> date(d.toInt(), month, year, sentOn)?.let { out += it } }
        }
        f = TWO_DAYS.replace(f, " ")
        for (m in MONTH_NAME.findAll(f)) {
            val month = MONTHS.indexOfFirst { alt -> alt.split('|').any { m.groupValues[2].startsWith(it) } } + 1
            date(m.groupValues[1].toInt(), month, m.groupValues[3].toIntOrNull(), sentOn)?.let { out += it }
        }
        f = MONTH_NAME.replace(f, " ")
        for (m in NUMERIC.findAll(f)) {
            // "9.15" or "11.30" next to "o"/"od"/"do" is a time, not a date.
            val before = f.substring(maxOf(0, m.range.first - 4), m.range.first)
            if (Regex("""\b(o|od|do)\s$""").containsMatchIn(before) && m.groupValues[3].isEmpty()) continue
            date(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toIntOrNull(), sentOn)?.let { out += it }
        }
        return out.distinct()
    }

    private fun date(d: Int, month: Int, year: Int?, sentOn: LocalDate): LocalDate? {
        if (month !in 1..12 || d !in 1..31) return null
        if (year != null) {
            val exact = runCatching { LocalDate.of(year, month, d) }.getOrNull() ?: return null
            // A typo'd year ("7.5.2025" in a mail from May 2026) is fixed to the mail's year.
            if (exact.isBefore(sentOn.minusDays(30))) {
                val fixed = runCatching { LocalDate.of(sentOn.year, month, d) }.getOrNull()
                if (fixed != null && !fixed.isBefore(sentOn.minusDays(7))) return fixed
            }
            return exact
        }
        val same = runCatching { LocalDate.of(sentOn.year, month, d) }.getOrNull() ?: return null
        return when {
            same.isBefore(sentOn.minusDays(60)) -> same.plusYears(1)
            same.isAfter(sentOn.plusDays(300)) -> same.minusYears(1)
            else -> same
        }
    }

    private val WEEKDAYS = listOf("pondel" to DayOfWeek.MONDAY, "utor" to DayOfWeek.TUESDAY, "stred" to DayOfWeek.WEDNESDAY,
        "stvrt" to DayOfWeek.THURSDAY, "piat" to DayOfWeek.FRIDAY, "sobot" to DayOfWeek.SATURDAY, "nedel" to DayOfWeek.SUNDAY)

    /** "zajtra", "dnes", "tento týždeň", "v stredu" -> days, counted from when the mail was sent. */
    fun relativeDates(folded: String, sentOn: LocalDate): List<LocalDate> {
        if (Regex("""\bzajtr""").containsMatchIn(folded)) return listOf(sentOn.plusDays(1))
        if (Regex("""\bdnes|\bdnesn""").containsMatchIn(folded)) return listOf(sentOn)
        if ("tento tyzden" in folded || "tomto tyzdni" in folded) {
            val monday = if (sentOn.dayOfWeek.value >= 6) sentOn.with(TemporalAdjusters.next(DayOfWeek.MONDAY)) else sentOn.with(DayOfWeek.MONDAY)
            return (0L..4L).map { monday.plusDays(it) }
        }
        if ("buduci tyzden" in folded || "buducom tyzdni" in folded) {
            val monday = sentOn.with(TemporalAdjusters.next(DayOfWeek.MONDAY))
            return (0L..4L).map { monday.plusDays(it) }
        }
        Regex("""\b(?:v|vo|na|tento|tuto|toto|najblizs\w*)\s+(pondel|utor|stred|stvrt|piat|sobot|nedel)\w*""").find(folded)?.let { m ->
            val day = WEEKDAYS.first { it.first == m.groupValues[1] }.second
            return listOf(sentOn.with(TemporalAdjusters.nextOrSame(day)))
        }
        return emptyList()
    }

    private val TIME = Regex("""(?<![\d.])([01]?\d|2[0-3])[:,.]([0-5]\d)(?![\d.])""")

    /** "od 9:15 do 10:45" -> both; "o 11,00 hod." -> start only; "do 13:00" -> end only. */
    fun timeRange(s: String): Pair<LocalTime?, LocalTime?>? {
        // Dates must not be read as times.
        var f = fold(s)
        f = TWO_DAYS.replace(f, " ")
        f = MONTH_NAME.replace(f, " ")
        f = Regex("""(?<![\d.,:])\d{1,2}\.\s?\d{1,2}\.(\s?\d{4})?""").replace(f, " ")
        val found = TIME.findAll(f).map { it to LocalTime.of(it.groupValues[1].toInt(), it.groupValues[2].toInt()) }.toList()
        if (found.isEmpty()) return null
        val (firstMatch, first) = found[0]
        val before = f.substring(maxOf(0, firstMatch.range.first - 4), firstMatch.range.first)
        if (found.size == 1 && Regex("""\bdo\s$""").containsMatchIn(before)) return null to first
        return first to found.getOrNull(1)?.second
    }

    /**
     * Time of a cancelled lesson. "prednášku o 8,15 … a potom cvičenie o 9,15 nebude" names two times;
     * the one just before the cancelling word is meant. "v čase od 9:15 do 10:45" gives both ends.
     */
    fun cancelTime(s: String): Pair<LocalTime?, LocalTime?>? {
        val range = timeRange(s) ?: return null
        if (range.second != null && Regex("""\bod\s+\d""").containsMatchIn(fold(s))) return range
        var f = fold(s)
        f = TWO_DAYS.replace(f, " "); f = MONTH_NAME.replace(f, " ")
        f = Regex("""(?<![\d.,:])\d{1,2}\.\s?\d{1,2}\.(\s?\d{4})?""").replace(f, " ")
        val cut = CANCEL.find(f)?.range?.last ?: return range.first to null
        val before = TIME.findAll(f).filter { it.range.first < cut }.lastOrNull()
            ?: return (range.first ?: range.second) to null
        return LocalTime.of(before.groupValues[1].toInt(), before.groupValues[2].toInt()) to null
    }

    /** "streda, 10 decembra 2025, 18:20" -> date and time. */
    private fun dateTime(s: String, sentOn: LocalDate): Pair<LocalDate, LocalTime?>? {
        val d = dates(s, sentOn).firstOrNull() ?: return null
        val t = TIME.findAll(fold(s)).lastOrNull()?.let { LocalTime.of(it.groupValues[1].toInt(), it.groupValues[2].toInt()) }
        return d to t
    }
}
