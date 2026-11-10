package com.ukfmail.app.ais

import android.content.Context
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDate

/** Everything shown on the study tabs, for one enrolment sheet. */
data class StudyData(
    val sheets: List<StudySheet> = emptyList(),
    val sheet: StudySheet? = null,
    val grades: List<Grade> = emptyList(),
    val exams: List<Exam> = emptyList(),
    val averages: Averages? = null,
    val progress: Progress? = null,
    val timetable: Timetable = Timetable(),
    val updatedAt: Long = 0,
    /** Grades of every enrolment sheet (every year of study), by sheet id, for the all-years credit total. */
    val yearGrades: Map<String, List<Grade>> = emptyMap(),
)

/** Lessons from the timetable JSON (names, teachers, lesson type) or, when that has none, from the .ics export. */
data class Timetable(val events: List<Ics.Event> = emptyList(), val json: String? = null) {
    val isEmpty get() = events.isEmpty() && json == null

    fun lessons(from: LocalDate, to: LocalDate): List<Lesson> =
        if (json != null) AisParse.lessonsFromJson(json, from, to) else Ics.lessons(events, from, to)
}

/**
 * Loads AiS2 data and keeps the raw answers in files, so the tabs open instantly and work offline.
 * The raw files also make it possible to check what AiS2 really sends.
 */
class AisRepo(context: Context) {
    private val dir = File(context.filesDir, "ais")

    fun cached(sheetId: String?): StudyData? {
        val studia = read("studia.json") ?: return null
        return runCatching {
            val sheets = AisParse.sheets(studia)
            val sheet = sheets.firstOrNull { it.id == sheetId } ?: sheets.firstOrNull()
            build(sheets, sheet, read("znamky.json"), read("skusky.json"), read("priemery.json"), read("prehlad.json"),
                read("rozvrh.ics"), read("rozvrh.json"), File(dir, "studia.json").lastModified()).withYears(sheets)
        }.getOrNull()
    }

    /** Fetches everything for [sheetId] (or the newest sheet). Parts that fail keep their cached copy. */
    fun fetch(client: AisClient, sheetId: String?, withTimetable: Boolean = true): StudyData {
        val studia = client.get("/ais/rest/portal/student-predmety/studia").also { save("studia.json", it) }
        val sheets = AisParse.sheets(studia)
        val sheet = sheets.firstOrNull { it.id == sheetId } ?: sheets.firstOrNull()
        if (sheet != null) {
            // Another year was shown before: its files must not stand in for this one.
            if (read("sheet.txt") != sheet.id) listOf("znamky.json", "skusky.json", "priemery.json", "prehlad.json").forEach { File(dir, it).delete() }
            save("sheet.txt", sheet.id)
            val p = "/ais/rest/portal/student-predmety"
            part("znamky.json") { client.get("$p/znamky/${sheet.id}") }
            part("skusky.json") { client.get("$p/skusky/${sheet.id}/v2") }
            part("priemery.json") { client.get("$p/studium-priemery/${sheet.id}") }
            part("prehlad.json") { client.get("$p/studium-prehlad/${sheet.id}") }
            read("znamky.json")?.let { save("znamky-${sheet.id}.json", it) }
            // Other years only change when a grade is added late; the background check skips them.
            if (withTimetable) sheets.filter { it.id != sheet.id }.forEach { s -> part("znamky-${s.id}.json") { client.get("$p/znamky/${s.id}") } }
        }
        if (withTimetable) fetchTimetable(client)
        return build(sheets, sheet, read("znamky.json"), read("skusky.json"), read("priemery.json"), read("prehlad.json"),
            read("rozvrh.ics"), read("rozvrh.json"), System.currentTimeMillis()).withYears(sheets)
    }

    private fun fetchTimetable(client: AisClient) {
        val json = runCatching { client.get("/ais/rest/apps/rozvrh/data") }.getOrNull()
        if (json != null) save("rozvrh.json", json)
        val (serverYear, serverSemester) = json?.let { AisParse.timetableTerm(it) } ?: (null to null)
        val today = LocalDate.now()
        val year = serverYear ?: academicYear(today)
        val semester = serverSemester?.takeIf { it == "Z" || it == "L" } ?: semesterOf(today)
        val ics = runCatching {
            client.get("/ais/rest/apps/rozvrh/export-ics/O/-1/${year.replace("/", "_")}/$semester")
        }.getOrNull()
        if (ics != null && ics.contains("BEGIN:VCALENDAR")) save("rozvrh.ics", ics)
    }

    /** Signing up or off returns the updated list of terms. */
    fun register(client: AisClient, exam: Exam, showName: Boolean): List<Exam> {
        val id = exam.subjectSheetId ?: throw AisException(0, "AiS2 neposlal údaje potrebné na prihlásenie.")
        val text = client.post("/ais/rest/portal/student-predmety/termin/${exam.id}/student/$id?zverejnit=$showName")
        return afterChange(text)
    }

    fun unregister(client: AisClient, exam: Exam): List<Exam> {
        val id = exam.subjectSheetId ?: throw AisException(0, "AiS2 neposlal údaje potrebné na odhlásenie.")
        return afterChange(client.delete("/ais/rest/portal/student-predmety/termin/${exam.id}/student/$id"))
    }

    fun examDetail(client: AisClient, exam: Exam): ExamDetail =
        AisParse.examDetail(client.get("/ais/rest/portal/student-predmety/termin-detail/${exam.id}"))

    private fun afterChange(text: String): List<Exam> {
        val list = runCatching { AisParse.exams(text) }.getOrNull()
        if (list != null && text.trimStart().startsWith("[")) save("skusky.json", text)
        return list ?: emptyList()
    }

    fun clear() {
        dir.deleteRecursively()
    }

    private fun build(
        sheets: List<StudySheet>, sheet: StudySheet?, znamky: String?, skusky: String?, priemery: String?,
        prehlad: String?, ics: String?, rozvrh: String?, updatedAt: Long,
    ) = StudyData(
        sheets = sheets,
        sheet = sheet,
        grades = znamky?.let { runCatching { AisParse.grades(it) }.getOrNull() }.orEmpty(),
        exams = skusky?.let { runCatching { AisParse.exams(it) }.getOrNull() }.orEmpty(),
        averages = priemery?.let { runCatching { AisParse.averages(it) }.getOrNull() },
        progress = prehlad?.let { runCatching { AisParse.progress(it) }.getOrNull() },
        timetable = timetable(ics, rozvrh),
        updatedAt = updatedAt,
    )

    private fun StudyData.withYears(sheets: List<StudySheet>): StudyData = copy(
        yearGrades = sheets.associate { s ->
            s.id to if (s.id == sheet?.id) grades
            else read("znamky-${s.id}.json")?.let { runCatching { AisParse.grades(it) }.getOrNull() }.orEmpty()
        },
    )

    private fun timetable(ics: String?, json: String?): Timetable {
        // The JSON is used when it holds lessons for this school year.
        val today = LocalDate.now()
        if (json != null && runCatching { AisParse.lessonsFromJson(json, today.minusDays(200), today.plusDays(200)) }.getOrNull().orEmpty().isNotEmpty()) {
            return Timetable(json = json)
        }
        return Timetable(events = ics?.let { runCatching { Ics.parse(it) }.getOrNull() }.orEmpty())
    }

    private inline fun part(name: String, load: () -> String) {
        runCatching(load).getOrNull()?.let { save(name, it) }
    }

    private fun read(name: String) = File(dir, name).takeIf { it.exists() }?.readText()

    private fun save(name: String, text: String) {
        dir.mkdirs()
        val tmp = File(dir, "$name.tmp")
        tmp.writeText(text)
        tmp.renameTo(File(dir, name))
    }

    companion object {
        fun academicYear(d: LocalDate): String =
            if (d.monthValue >= 9) "${d.year}/${d.year + 1}" else "${d.year - 1}/${d.year}"

        /** Winter semester runs from September to mid-February. */
        fun semesterOf(d: LocalDate): String =
            if (d.monthValue >= 9 || d.monthValue == 1 || (d.monthValue == 2 && d.dayOfMonth < 15)) "Z" else "L"
    }
}
