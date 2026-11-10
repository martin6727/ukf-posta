package com.ukfmail.app.ais

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/** Minimal iCalendar reader for the AiS2 timetable export: events, weekly/daily repeats and exceptions. */
object Ics {
    val ZONE: ZoneId = ZoneId.of("Europe/Bratislava")

    data class Event(
        val summary: String,
        val location: String?,
        val description: String?,
        val start: LocalDateTime,
        val end: LocalDateTime,
        val rule: Map<String, String>?,
        val exceptions: Set<LocalDateTime>,
        /** Zone the repeat rule counts in: a weekly 9:00 UTC lesson moves by an hour when summer time ends. */
        val ruleZone: ZoneId = ZONE,
    )

    fun parse(text: String): List<Event> {
        // Long lines are folded: a line starting with a space or tab continues the previous one.
        val lines = ArrayList<String>()
        for (raw in text.replace("\r\n", "\n").replace('\r', '\n').split('\n')) {
            if ((raw.startsWith(" ") || raw.startsWith("\t")) && lines.isNotEmpty()) lines[lines.size - 1] += raw.substring(1)
            else if (raw.isNotEmpty()) lines += raw
        }
        val out = ArrayList<Event>()
        var props: MutableList<Triple<String, Map<String, String>, String>>? = null
        for (line in lines) {
            when {
                line.equals("BEGIN:VEVENT", true) -> props = ArrayList()
                line.equals("END:VEVENT", true) -> {
                    props?.let { p -> toEvent(p)?.let { out += it } }
                    props = null
                }
                props != null -> {
                    val colon = colonIndex(line).takeIf { it > 0 } ?: continue
                    val head = line.substring(0, colon).split(';')
                    val params = head.drop(1).mapNotNull {
                        val i = it.indexOf('='); if (i > 0) it.substring(0, i).uppercase() to it.substring(i + 1).trim('"') else null
                    }.toMap()
                    props.add(Triple(head[0].uppercase(), params, line.substring(colon + 1)))
                }
            }
        }
        return out
    }

    /** Every lesson between [from] and [to] (both inclusive). */
    fun lessons(events: List<Event>, from: LocalDate, to: LocalDate): List<Lesson> {
        val out = ArrayList<Lesson>()
        for (e in events) {
            for (start in occurrences(e, from, to)) {
                val (title, kind) = splitKind(e.summary)
                out += Lesson(
                    title = title,
                    kind = kind ?: kindFrom(e.description),
                    // "A00120, poschodie 0, Hlavná 4, Nitra" -> room and floor.
                    room = e.location?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.take(2)?.joinToString(" · ")?.ifBlank { null },
                    teacher = teacherFrom(e.description),
                    start = start,
                    end = start.plusMinutes(ChronoUnit.MINUTES.between(e.start, e.end).coerceAtLeast(0)),
                )
            }
        }
        return out.distinct().sortedBy { it.start }
    }

    private fun occurrences(e: Event, from: LocalDate, to: LocalDate): List<LocalDateTime> {
        val rule = e.rule
        val inRange = { t: LocalDateTime -> !t.toLocalDate().isBefore(from) && !t.toLocalDate().isAfter(to) }
        if (rule == null) return listOf(e.start).filter(inRange)
        val freq = rule["FREQ"]
        val interval = rule["INTERVAL"]?.toIntOrNull()?.coerceAtLeast(1) ?: 1
        val until = rule["UNTIL"]?.let { parseDateTime(it, emptyMap()) }
        val count = rule["COUNT"]?.toIntOrNull()
        val out = ArrayList<LocalDateTime>()
        var produced = 0
        val base = if (e.ruleZone == ZONE) e.start else e.start.atZone(ZONE).withZoneSameInstant(e.ruleZone).toLocalDateTime()
        fun local(t: LocalDateTime) = if (e.ruleZone == ZONE) t else t.atZone(e.ruleZone).withZoneSameInstant(ZONE).toLocalDateTime()
        val days = rule["BYDAY"]?.split(',')?.mapNotNull { dayOf(it.takeLast(2)) }?.takeIf { it.isNotEmpty() }
            ?: listOf(base.dayOfWeek)
        var cursor = base
        // Walk period by period from the first lesson, so COUNT and odd/even weeks stay right.
        var guard = 0
        while (guard++ < 2000) {
            val batch = when (freq) {
                "WEEKLY" -> {
                    val monday = cursor.toLocalDate().with(DayOfWeek.MONDAY)
                    days.sorted().map { monday.plusDays((it.value - 1).toLong()).atTime(base.toLocalTime()) }
                        .filter { !it.isBefore(base) }
                }
                "DAILY" -> listOf(cursor)
                else -> return listOf(e.start).filter(inRange)
            }
            for (raw in batch) {
                val t = local(raw)
                if (until != null && t.isAfter(until)) return out
                if (count != null && produced >= count) return out
                produced++
                if (t !in e.exceptions && inRange(t)) out += t
            }
            if (cursor.toLocalDate().isAfter(to)) return out
            cursor = if (freq == "WEEKLY") cursor.plusWeeks(interval.toLong()) else cursor.plusDays(interval.toLong())
        }
        return out
    }

    private fun toEvent(p: List<Triple<String, Map<String, String>, String>>): Event? {
        fun first(name: String) = p.firstOrNull { it.first == name }
        val start = first("DTSTART")?.let { parseDateTime(it.third, it.second) } ?: return null
        val end = first("DTEND")?.let { parseDateTime(it.third, it.second) } ?: start.plusMinutes(90)
        val rule = first("RRULE")?.third?.split(';')?.mapNotNull {
            val i = it.indexOf('='); if (i > 0) it.substring(0, i).uppercase() to it.substring(i + 1) else null
        }?.toMap()
        val exceptions = p.filter { it.first == "EXDATE" }.flatMap { ex ->
            ex.third.split(',').mapNotNull { parseDateTime(it, ex.second) }
        }.toSet()
        val startProp = first("DTSTART")!!
        val ruleZone = when {
            startProp.third.trim().endsWith("Z") -> ZoneId.of("UTC")
            else -> startProp.second["TZID"]?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZONE
        }
        return Event(
            summary = unescape(first("SUMMARY")?.third.orEmpty()).ifBlank { "Hodina" },
            location = first("LOCATION")?.third?.let(::unescape),
            description = first("DESCRIPTION")?.third?.let(::unescape),
            start = start, end = end, rule = rule, exceptions = exceptions, ruleZone = ruleZone,
        )
    }

    private val BASIC = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")
    private val DATE_ONLY = DateTimeFormatter.ofPattern("yyyyMMdd")

    /** Times in UTC (…Z) or another zone come out as local Slovak time. */
    fun parseDateTime(v: String, params: Map<String, String>): LocalDateTime? = runCatching {
        val s = v.trim()
        when {
            s.endsWith("Z") -> ZonedDateTime.of(LocalDateTime.parse(s.dropLast(1), BASIC), ZoneId.of("UTC"))
                .withZoneSameInstant(ZONE).toLocalDateTime()
            s.length == 8 -> LocalDate.parse(s, DATE_ONLY).atStartOfDay()
            else -> {
                val local = LocalDateTime.parse(s.take(15), BASIC)
                val zone = params["TZID"]?.let { runCatching { ZoneId.of(it) }.getOrNull() }
                if (zone == null || zone == ZONE) local else local.atZone(zone).withZoneSameInstant(ZONE).toLocalDateTime()
            }
        }
    }.getOrNull()

    private fun dayOf(code: String): DayOfWeek? = when (code.uppercase()) {
        "MO" -> DayOfWeek.MONDAY; "TU" -> DayOfWeek.TUESDAY; "WE" -> DayOfWeek.WEDNESDAY
        "TH" -> DayOfWeek.THURSDAY; "FR" -> DayOfWeek.FRIDAY; "SA" -> DayOfWeek.SATURDAY
        "SU" -> DayOfWeek.SUNDAY; else -> null
    }

    /** The first ':' that is not inside a quoted parameter value. */
    private fun colonIndex(line: String): Int {
        var quoted = false
        for ((i, ch) in line.withIndex()) {
            if (ch == '"') quoted = !quoted
            if (ch == ':' && !quoted) return i
        }
        return -1
    }

    private fun unescape(s: String) = s.replace("\\n", "\n").replace("\\N", "\n").replace("\\,", ",").replace("\\;", ";").replace("\\\\", "\\").trim()

    private val KINDS = listOf("prednáška", "cvičenie", "seminár", "laboratórium", "prax", "konzultácia")

    /** "Matematika (prednáška)" or "Matematika - P" -> name and kind. */
    private fun splitKind(raw: String): Pair<String, String?> {
        // AiS2 sends "KAI/bAB10/22 Algoritmy C (Rozvrhová akcia)": code, name, P/C/S letter.
        var summary = raw.replace(Regex("""\s*\(Rozvrhová akcia\)\s*$"""), "").replace(Regex("""\s+"""), " ").trim()
        val firstWord = summary.substringBefore(' ')
        if ('/' in firstWord && ' ' in summary) summary = summary.substringAfter(' ')
        Regex("""^(.*\S)\s+([PCSL])$""").find(summary)?.let { return it.groupValues[1] to kindName(it.groupValues[2]) }
        val paren = Regex("""^(.*?)\s*\(([^()]*)\)\s*$""").find(summary)
        if (paren != null) {
            val k = paren.groupValues[2]
            if (KINDS.any { k.lowercase().startsWith(it.take(5)) } || k.length <= 3) return paren.groupValues[1] to kindName(k)
        }
        return summary to null
    }

    private fun kindName(k: String): String = when (k.trim().uppercase()) {
        "P" -> "Prednáška"; "C", "CV" -> "Cvičenie"; "S", "SE" -> "Seminár"; "L" -> "Laboratórium"
        else -> k.trim().replaceFirstChar { it.uppercase() }
    }

    private fun kindFrom(description: String?): String? {
        val d = description?.lowercase() ?: return null
        return KINDS.firstOrNull { it in d }?.replaceFirstChar { it.uppercase() }
    }

    private fun teacherFrom(description: String?): String? {
        val lines = description?.lines()?.map { it.trim() }?.filter { it.isNotEmpty() } ?: return null
        lines.firstOrNull { it.contains("vyučujúci", true) || it.contains("učiteľ", true) }?.let {
            return it.substringAfter(':').trim().ifBlank { null }
        }
        // Otherwise a line that looks like a name with a title.
        return lines.firstOrNull { Regex("""\b(Mgr|Ing|PhD|doc|prof|PaedDr|RNDr|PhDr|Bc)\.?""").containsMatchIn(it) }
    }
}
