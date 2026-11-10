package com.ukfmail.app.ais

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/** One enrolment sheet (zápisný list): one academic year of one study. */
data class StudySheet(val id: String, val year: String, val name: String, val program: String)

data class Grade(
    val id: String,
    val code: String,
    val name: String,
    val credits: Int,
    /** Z (winter), L (summer) or null. */
    val semester: String?,
    /** A..FX, or null while not graded. */
    val grade: String?,
    val gradeText: String?,
    val examiner: String?,
    /** Skúška, hodnotenie… */
    val completion: String?,
    /** Part of the study plan: A compulsory, B compulsory elective, C elective. */
    val part: String?,
    val scope: String?,
)

data class Exam(
    val id: String,
    val subjectId: String?,
    val subjectSheetId: String?,
    val code: String,
    val name: String,
    val date: LocalDate?,
    val dateText: String,
    val time: String?,
    val registerUntil: String?,
    val registeredOn: String?,
    val phase: String?,
    val termGrade: String?,
    val subjectGrade: String?,
    val full: Boolean,
    val upcoming: Boolean,
    val serverAllows: Boolean,
    val unregisteredBy: String?,
) {
    val registered get() = !registeredOn.isNullOrBlank()

    val start: LocalDateTime?
        get() = date?.atTime(time?.let { parseTime(it) } ?: LocalTime.of(8, 0))

    /** Same rule the AiS2 web app uses for its "Prihlásiť" button. */
    val canRegister get() = !registered && !full && upcoming && !(termGrade == null && subjectGrade != null && !serverAllows)
}

data class ExamDetail(
    val rooms: List<String>,
    val examiners: List<String>,
    val capacity: String?,
    val note: String?,
    val phase: String?,
    val registerFrom: String?,
    val registerTo: String?,
    val unregisterFrom: String?,
    val unregisterTo: String?,
    val students: List<String>,
)

data class Averages(val all: String?, val graded: String?, val gradedRecognised: String?)

data class Progress(val enrolled: Int, val passed: Int, val creditsEnrolled: Int, val creditsPassed: Int)

data class Lesson(
    val title: String,
    val kind: String?,
    val room: String?,
    val teacher: String?,
    val start: LocalDateTime,
    val end: LocalDateTime,
    val code: String? = null,
)

/** Reads AiS2 JSON. Field names come from the AiS2 web app; anything missing is tolerated. */
object AisParse {

    fun sheets(text: String): List<StudySheet> {
        val root = JSONTokener(text).nextValue()
        val studies = when (root) {
            is JSONObject -> root.optJSONArray("data") ?: JSONArray()
            is JSONArray -> root
            else -> JSONArray()
        }
        val out = ArrayList<StudySheet>()
        for (st in studies.objects()) {
            val program = st.s("name") ?: st.s("studijnyProgram") ?: ""
            for (z in (st.optJSONArray("zapisneListy") ?: JSONArray()).objects()) {
                val id = z.s("id") ?: continue
                out += StudySheet(id, z.s("akRok") ?: z.s("akademickyRok") ?: "", z.s("name") ?: program, program)
            }
        }
        return out
    }

    fun grades(text: String): List<Grade> {
        val root = JSONTokener(text).nextValue()
        val list = when (root) {
            is JSONObject -> root.optJSONArray("znamky") ?: JSONArray()
            is JSONArray -> root
            else -> JSONArray()
        }
        return list.objects().map { g ->
            Grade(
                id = g.s("id") ?: (g.s("predmetSkratka") ?: ""),
                code = g.s("predmetSkratka") ?: g.s("skratka") ?: "",
                name = g.s("predmetNazov") ?: g.s("nazov") ?: "Predmet",
                credits = g.s("kredit")?.toDoubleOrNull()?.toInt() ?: 0,
                semester = g.s("kodSemester"),
                grade = g.s("hodnotenieKod"),
                gradeText = g.s("hodnoteniePopis"),
                examiner = g.s("hodnotiaci"),
                completion = g.s("popisSposobUkoncenia") ?: completionName(g.s("kodSposobUkoncenia")),
                // kodCastSP is the plan part (SC study, SS state exams); kodTypVyucby is A/B/C.
                part = if (g.s("kodCastSP") == "SS") "SS" else g.s("kodTypVyucby") ?: g.s("kodCastSP"),
                scope = g.s("rozsah"),
            )
        }
    }

    fun exams(text: String): List<Exam> {
        val root = JSONTokener(text).nextValue()
        val list = when (root) {
            is JSONArray -> root
            is JSONObject -> root.optJSONArray("skusky") ?: root.optJSONArray("data") ?: JSONArray()
            else -> JSONArray()
        }
        val exams = list.objects().map { e ->
            val dateText = e.s("datumSk") ?: e.s("datum") ?: ""
            Exam(
                id = e.s("id") ?: "",
                subjectId = e.s("idPredmet"),
                subjectSheetId = e.s("idPredmetZl"),
                code = e.s("predmetSkratka") ?: "",
                name = e.s("predmetNazov") ?: "Skúška",
                date = parseDate(dateText),
                dateText = dateText,
                time = e.s("cas"),
                registerUntil = e.s("datumPrihlasDoSk") ?: e.s("datumPrihlasDo"),
                registeredOn = e.s("datumPrihlasenia"),
                phase = e.s("faza"),
                termGrade = e.s("hodnotenieTermin"),
                subjectGrade = e.s("hodnoteniePredmet"),
                full = e.b("terminNaplneny"),
                upcoming = e.b("bude"),
                serverAllows = e.b("mozeSaPrihlasit"),
                unregisteredBy = e.s("odhlasil"),
            )
        }
        // Sign-up needs the subject's enrolment id, which only some rows of a subject carry.
        val sheetIds = exams.filter { it.subjectSheetId != null }.associate { it.subjectId to it.subjectSheetId }
        return exams.map { if (it.subjectSheetId == null) it.copy(subjectSheetId = sheetIds[it.subjectId]) else it }
            .sortedWith(compareBy({ it.date ?: LocalDate.MAX }, { it.time.orEmpty() }))
    }

    fun examDetail(text: String): ExamDetail {
        val o = JSONObject(text)
        return ExamDetail(
            rooms = o.optJSONArray("miestnosti").labels("kod", "nazov", "popis"),
            examiners = o.optJSONArray("hodnotiaci").labels("plneMeno", "meno", "popis"),
            capacity = o.s("maxPocet"),
            note = o.s("poznamka"),
            phase = o.s("fazaPopis"),
            registerFrom = o.s("datumPrihlasOd"),
            registerTo = o.s("datumPrihlasDo"),
            unregisterFrom = o.s("datumOdhlasOd"),
            unregisterTo = o.s("datumOdhlasDo"),
            students = o.optJSONArray("studenti").labels("plneMeno").filter { it != "unknown" },
        )
    }

    fun averages(text: String): Averages {
        val o = JSONTokener(text).nextValue() as? JSONObject ?: return Averages(null, null, null)
        val p = o.optJSONObject("priemery") ?: o
        return Averages(p.s("priemerVsetkych"), p.s("priemerHodnotenych"), p.s("priemerHodnotenychAjUznanych"))
    }

    fun progress(text: String): Progress {
        val root = JSONTokener(text).nextValue()
        val rows = when (root) {
            is JSONArray -> root
            is JSONObject -> root.optJSONArray("data") ?: JSONArray().put(root)
            else -> JSONArray()
        }
        var enrolled = 0; var passed = 0; var cEnrolled = 0; var cPassed = 0
        for (r in rows.objects()) {
            val recognised = r.s("kodTypVyucby") == "UZNANE"
            enrolled += r.i("pocetZapisane")
            passed += r.i("pocetAbsolvovane")
            cEnrolled += r.i("kredityZapisane")
            // AiS2 counts recognised credits separately from passed ones.
            if (!recognised) cPassed += r.i("kredityAbsolvovane")
        }
        return Progress(enrolled, passed, cEnrolled, cPassed)
    }

    /** Academic year and semester the timetable app opens with, e.g. ("2026/2027", "Z"). */
    fun timetableTerm(text: String): Pair<String?, String?> {
        val o = runCatching { JSONObject(text) }.getOrNull() ?: return null to null
        val year = o.opt("aktualnyAkRok").let { v ->
            when (v) {
                is JSONObject -> v.s("akRok") ?: v.s("kod") ?: v.s("popis")
                is String -> v.takeIf { it.isNotBlank() && it != "null" }
                else -> null
            }
        } ?: o.s("akRok")
        return year to o.s("kodSemester")
    }

    /**
     * Lessons between [from] and [to] from the timetable JSON (/ais/rest/apps/rozvrh/data): every object
     * with a weekday and start and end times, repeated weekly between its datumOd and datumDo.
     */
    fun lessonsFromJson(text: String, from: LocalDate, to: LocalDate): List<Lesson> {
        val out = ArrayList<Lesson>()
        fun add(v: JSONObject, startT: LocalTime, endT: LocalTime, day: Int) {
            val first = parseIsoOrSk(v.s("datumOd")) ?: from
            val last = parseIsoOrSk(v.s("datumDo")) ?: to
            val fixed = v.optJSONArray("datumy")?.labels("datum")?.mapNotNull(::parseIsoOrSk)?.takeIf { it.isNotEmpty() }
            var d = from
            while (!d.isAfter(to)) {
                val hit = if (fixed != null) d in fixed else d.dayOfWeek.value == day && !d.isBefore(first) && !d.isAfter(last)
                if (hit) {
                    val rooms = v.optJSONArray("miestnosti").labels("kod", "nazov").ifEmpty { listOfNotNull(v.s("miestnostNazov")) }
                    val building = v.s("budovaPopis")?.let(::buildingName)
                    out += Lesson(
                        title = (v.s("predmetNazov") ?: v.s("nazov") ?: v.s("predmetSkratka") ?: "Hodina").replace(Regex("""\s+"""), " "),
                        kind = lessonKind(v.s("typ")),
                        room = (rooms + listOfNotNull(building)).joinToString(" · ").ifBlank { null },
                        teacher = v.optJSONArray("vyucujuci").labels("plneMeno", "meno", "priezvisko").joinToString(", ").ifBlank { null },
                        start = d.atTime(startT),
                        end = d.atTime(endT),
                        code = v.s("predmetSkratka"),
                    )
                }
                d = d.plusDays(1)
            }
        }
        fun walk(v: Any?) {
            when (v) {
                is JSONArray -> for (i in 0 until v.length()) walk(v.opt(i))
                is JSONObject -> {
                    val s = v.s("casOd")?.let(::parseTime)
                    val e = v.s("casDo")?.let(::parseTime)
                    val day = dayIndex(v.s("den") ?: v.s("denVTyzdni") ?: v.s("kodDen"))
                    if (s != null && e != null && (day != null || v.optJSONArray("datumy") != null)) add(v, s, e, day ?: 0)
                    else v.keys().forEach { walk(v.opt(it)) }
                }
            }
        }
        walk(runCatching { JSONTokener(text).nextValue() }.getOrNull())
        return out.distinct().sortedBy { it.start }
    }

    /** "Hlavná - blok D (KAI)" and "Hlavná - (blok B) nová budova" -> "Hlavná – blok D" / "Hlavná – blok B". */
    private fun buildingName(s: String): String {
        val street = s.substringBefore(" - ").trim()
        val block = Regex("""blok\s+\w+""", RegexOption.IGNORE_CASE).find(s)?.value
        return if (block != null && street != s) "$street – $block" else s.replace(Regex("""\s*\([^)]*\)\s*$"""), "").replace(" - ", " – ")
    }

    private fun lessonKind(typ: String?) = when (typ?.uppercase()) {
        null -> null
        "RSP" -> "Prednáška"
        "RSC" -> "Cvičenie"
        "RSS" -> "Seminár"
        "RSL" -> "Laboratórium"
        else -> null
    }

    private fun parseIsoOrSk(s: String?): LocalDate? =
        s?.let { runCatching { LocalDate.parse(it.take(10)) }.getOrNull() ?: parseDate(it) }

    private fun dayIndex(v: String?): Int? {
        if (v == null) return null
        v.toIntOrNull()?.let { return it.takeIf { d -> d in 1..7 } }
        val t = v.lowercase().trim()
        return listOf("po", "ut", "st", "št", "pi", "so", "ne").indexOfFirst { t.startsWith(it) }.takeIf { it >= 0 }?.plus(1)
            ?: if (t.startsWith("stv")) 4 else null
    }

    private fun completionName(code: String?) = when (code) {
        null -> null
        "S" -> "Skúška"
        "H" -> "Hodnotenie"
        "Z" -> "Zápočet"
        "KZ" -> "Klasifikovaný zápočet"
        else -> code
    }

    private val DATE = Regex("""(\d{1,2})\.\s*(\d{1,2})\.\s*(\d{4})""")
    private val TIME = Regex("""(\d{1,2}):(\d{2})""")

    fun parseDate(s: String?): LocalDate? {
        val m = DATE.find(s ?: return null) ?: return null
        val (d, mo, y) = m.destructured
        return runCatching { LocalDate.of(y.toInt(), mo.toInt(), d.toInt()) }.getOrNull()
    }

    fun parseTime(s: String): LocalTime? {
        val m = TIME.find(s) ?: return null
        return runCatching { LocalTime.of(m.groupValues[1].toInt(), m.groupValues[2].toInt()) }.getOrNull()
    }
}

internal fun parseTime(s: String) = AisParse.parseTime(s)

/** A value as text, or null for missing, JSON null and blanks. */
internal fun JSONObject.s(key: String): String? {
    if (!has(key) || isNull(key)) return null
    return when (val v = get(key)) {
        is Number -> java.math.BigDecimal(v.toString()).stripTrailingZeros().toPlainString()
        else -> v.toString().trim().takeIf { it.isNotEmpty() && it != "null" }
    }
}

internal fun JSONObject.b(key: String): Boolean = when (val v = opt(key)) {
    is Boolean -> v
    is String -> v.equals("true", true) || v == "A" || v == "1"
    is Number -> v.toInt() != 0
    else -> false
}

internal fun JSONObject.i(key: String): Int = s(key)?.replace(',', '.')?.toDoubleOrNull()?.toInt() ?: 0

internal fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }

/** Plain strings as they are; objects by the first of [keys] they have. */
internal fun JSONArray?.labels(vararg keys: String): List<String> {
    if (this == null) return emptyList()
    return (0 until length()).mapNotNull { i ->
        when (val v = opt(i)) {
            is JSONObject -> keys.firstNotNullOfOrNull { v.s(it) }
            null, JSONObject.NULL -> null
            else -> v.toString().trim().takeIf { it.isNotEmpty() }
        }
    }
}
