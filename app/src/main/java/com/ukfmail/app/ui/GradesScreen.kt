package com.ukfmail.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.School
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ukfmail.app.StudyViewModel
import com.ukfmail.app.ais.AisRepo
import com.ukfmail.app.ais.Averages
import com.ukfmail.app.ais.Grade
import com.ukfmail.app.ais.Progress
import com.ukfmail.app.ais.StudySheet
import java.time.LocalDate

enum class SemesterFilter(val label: String, val code: String?) { ALL("Celý rok", null), WINTER("Zimný", "Z"), SUMMER("Letný", "L") }

private val FAILED = setOf("FX", "F", "N", "X")

fun Grade.passed() = grade != null && grade.uppercase() !in FAILED

@Composable
fun GradesScreen(vm: StudyViewModel) {
    LaunchedEffect(Unit) { vm.start() }
    GradesContent(
        grades = vm.data.grades,
        averages = vm.data.averages,
        progress = vm.data.progress,
        sheets = vm.data.sheets,
        sheet = vm.data.sheet,
        onSheet = vm::selectSheet,
        updatedAt = vm.data.updatedAt,
        loading = vm.loading,
        error = vm.error,
        needsLogin = vm.needsLogin,
        suggestedLogin = vm.suggestedLogin,
        onSignIn = vm::signIn,
        onRefresh = vm::refresh,
        yearGrades = vm.data.yearGrades,
    )
}

@Composable
fun GradesContent(
    grades: List<Grade>,
    averages: Averages?,
    progress: Progress?,
    sheets: List<StudySheet>,
    sheet: StudySheet?,
    onSheet: (StudySheet) -> Unit,
    updatedAt: Long,
    loading: Boolean,
    error: String?,
    needsLogin: Boolean,
    suggestedLogin: String,
    onSignIn: (String, String) -> Unit,
    onRefresh: () -> Unit,
    yearGrades: Map<String, List<Grade>> = emptyMap(),
    today: LocalDate = LocalDate.now(),
) {
    var chosen by rememberSaveable { mutableStateOf<SemesterFilter?>(null) }
    val current = if (AisRepo.semesterOf(today) == "Z") SemesterFilter.WINTER else SemesterFilter.SUMMER
    val filter = chosen ?: if (grades.any { it.semester == current.code }) current else SemesterFilter.ALL
    val shown = remember(grades, filter) { grades.filter { filter.code == null || it.semester == null || it.semester == filter.code } }
    val pending = shown.filter { it.grade == null }
    val graded = shown.filter { it.grade != null }

    StudyFrame(
        refreshing = loading && grades.isNotEmpty(),
        onRefresh = onRefresh,
        header = {
            StudyTitle(listOfNotNull(sheet?.year?.ifBlank { null }, sheet?.program?.ifBlank { null }).joinToString(" · "), "Známky", updatedAt, loading)
            if (grades.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                SummaryCard(shown, filter, averages)
                if (yearGrades.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    StudyTotalCard(sheets, yearGrades)
                }
            }
        },
    ) {
        if (sheets.size > 1 && !needsLogin) item(key = "years") {
            LazyRow(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(sheets, key = { it.id }) { s -> Pill(s.year.ifBlank { s.name }, active = s.id == sheet?.id, onClick = { onSheet(s) }) }
            }
        }
        if (grades.isNotEmpty()) item(key = "semesters") {
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(SemesterFilter.entries) { f -> Pill(f.label, active = f == filter, onClick = { chosen = f }) }
            }
        }
        if (studyStatus(grades.isNotEmpty(), loading, error, needsLogin, suggestedLogin, onSignIn, onRefresh)) return@StudyFrame
        if (shown.isEmpty()) {
            item(key = "empty") { EmptyState(Icons.Outlined.School, "Žiadne predmety", "Na zápisnom liste pre tento semester nie sú predmety.") }
            return@StudyFrame
        }
        if (graded.isNotEmpty()) {
            item(key = "h-graded") { GroupHeader("Hodnotené", graded.size) }
            graded.forEach { g -> item(key = "g-${g.id}") { GradeRow(g) } }
        }
        if (pending.isNotEmpty()) {
            item(key = "h-pending") { GroupHeader("Ešte bez známky", pending.size) }
            pending.forEach { g -> item(key = "p-${g.id}") { GradeRow(g) } }
        }
    }
}

@Composable
private fun SummaryCard(grades: List<Grade>, filter: SemesterFilter, averages: Averages?) {
    // Credits of the semester picked below (or both together for the whole year).
    val creditsAll = grades.sumOf { it.credits }
    val creditsDone = grades.filter { it.passed() }.sumOf { it.credits }
    // AiS2's priemerVsetkych counts subjects without a grade as 4, so only graded ones are used.
    // Its averages are for the whole year, so a single semester is computed from its own grades.
    val average = when {
        grades.none { it.grade != null } -> null
        filter == SemesterFilter.ALL -> averages?.graded?.takeIf { (it.replace(',', '.').toDoubleOrNull() ?: 0.0) > 0 } ?: computedAverage(grades)
        else -> computedAverage(grades)
    }
    val period = when (filter) {
        SemesterFilter.ALL -> "celý rok"
        SemesterFilter.WINTER -> "zimný semester"
        SemesterFilter.SUMMER -> "letný semester"
    }
    val shape = RoundedCornerShape(30.dp)
    Box(
        Modifier.fillMaxWidth().shadow(20.dp, shape, ambientColor = Indigo, spotColor = Violet).clip(shape)
            .background(Brush.linearGradient(listOf(Indigo, Violet, Pink)))
    ) {
        Glow(Modifier.matchParentSize(), strength = 0.7f)
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Priemer · $period", color = Color.White.copy(alpha = 0.85f), fontSize = 13.5.sp, fontWeight = FontWeight.Medium)
                Text(average?.let(::formatAverage) ?: "–", color = Color.White, fontSize = 46.sp, lineHeight = 50.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-1).sp)
                Spacer(Modifier.height(4.dp))
                val done = grades.count { it.passed() }
                Text("$done z ${grades.size} predmetov ukončených", color = Color.White.copy(alpha = 0.9f), fontSize = 13.5.sp)
            }
            CreditRing(creditsDone, creditsAll, 104.dp)
        }
    }
}

/** Credits earned over all years of study, with a line per year. */
@Composable
private fun StudyTotalCard(sheets: List<StudySheet>, yearGrades: Map<String, List<Grade>>) {
    val years = sheets.filter { it.id in yearGrades }.sortedBy { it.year }
    val earned = years.sumOf { s -> yearGrades[s.id].orEmpty().filter { it.passed() }.sumOf { it.credits } }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(13.dp)).background(BrandGradient), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.School, null, tint = Color.White, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Celé štúdium", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Text(
                    if (years.size == 1) "1 ročník" else "${years.size} ročníky".let { if (years.size >= 5) "${years.size} ročníkov" else it },
                    fontSize = 12.5.sp, color = muted,
                )
            }
            Text("$earned", fontWeight = FontWeight.ExtraBold, fontSize = 26.sp)
            Spacer(Modifier.width(4.dp))
            Text("kr.", fontSize = 13.sp, color = muted, modifier = Modifier.padding(top = 6.dp))
        }
        if (years.size > 1) {
            Spacer(Modifier.height(10.dp))
            years.forEach { s ->
                val g = yearGrades[s.id].orEmpty()
                val done = g.filter { it.passed() }.sumOf { it.credits }
                val all = g.sumOf { it.credits }
                Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(s.year.ifBlank { s.name }, fontSize = 13.5.sp, modifier = Modifier.width(92.dp))
                    LinearProgressIndicator(
                        progress = { if (all > 0) done.toFloat() / all else 0f },
                        color = Indigo, trackColor = Indigo.copy(alpha = 0.15f),
                        modifier = Modifier.weight(1f).height(6.dp).clip(RoundedCornerShape(50)),
                        drawStopIndicator = {},
                    )
                    Spacer(Modifier.width(10.dp))
                    Text("$done / $all kr.", fontSize = 13.sp, color = muted)
                }
            }
        }
    }
}

@Composable
private fun CreditRing(done: Int, total: Int, size: Dp) {
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.matchParentSize()) {
            val stroke = 10.dp.toPx()
            val inset = stroke / 2
            val arc = Size(this.size.width - stroke, this.size.height - stroke)
            drawArc(Color.White.copy(alpha = 0.25f), -90f, 360f, false, Offset(inset, inset), arc, style = Stroke(stroke))
            val sweep = if (total > 0) 360f * (done.toFloat() / total).coerceIn(0f, 1f) else 0f
            drawArc(Color.White, -90f, sweep, false, Offset(inset, inset), arc, style = Stroke(stroke, cap = StrokeCap.Round))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("$done/$total", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 19.sp)
            Text("kreditov", color = Color.White.copy(alpha = 0.85f), fontSize = 11.5.sp)
        }
    }
}

@Composable
private fun GroupHeader(title: String, count: Int) {
    Row(Modifier.padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        Spacer(Modifier.width(8.dp))
        Text("$count", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(12.dp))
        GradientRule(Modifier.weight(1f))
    }
}

@Composable
private fun GradeRow(g: Grade) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier.padding(horizontal = 12.dp, vertical = 4.dp).fillMaxWidth().clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GradeBadge(g.grade, 48.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(g.name, fontWeight = FontWeight.Bold, fontSize = 15.5.sp, lineHeight = 20.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(3.dp))
            Text(
                listOfNotNull(g.code.ifBlank { null }, "${g.credits} kr.", g.completion).joinToString(" · "),
                fontSize = 13.sp, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            val sub = listOfNotNull(g.gradeText?.takeIf { g.grade != null }, g.examiner).joinToString(" · ")
            if (sub.isNotBlank()) Text(sub, fontSize = 12.5.sp, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        partLabel(g.part)?.let { (text, color) ->
            Spacer(Modifier.width(8.dp))
            Tag(text, color)
        }
    }
}

private fun partLabel(part: String?): Pair<String, Color>? = when (part?.uppercase()) {
    "A" -> "Povinný" to Indigo
    "B" -> "Pov. voliteľný" to Violet
    "C" -> "Výberový" to Sky
    "SS" -> "Štátnica" to Pink
    else -> null
}

fun gradeColor(grade: String?): Color = when (grade?.uppercase()) {
    "A" -> Emerald
    "B" -> Color(0xFF22C55E)
    "C" -> Color(0xFF84CC16)
    "D" -> Amber
    "E" -> Color(0xFFF97316)
    "FX", "F", "N", "X" -> Rose
    null -> Color(0xFF9A9CAD)
    else -> Indigo
}

/** Round grade badge: the letter on its color, or a dash while not graded. */
@Composable
fun GradeBadge(grade: String?, size: Dp) {
    val c = gradeColor(grade)
    val label = grade?.take(3) ?: "–"
    Box(
        Modifier.size(size).clip(CircleShape)
            .background(if (grade == null) Brush.linearGradient(listOf(c.copy(alpha = 0.18f), c.copy(alpha = 0.18f)))
            else Brush.linearGradient(listOf(c, c.copy(alpha = 0.75f)))),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label, color = if (grade == null) c else Color.White, fontWeight = FontWeight.ExtraBold,
            fontSize = (size.value * if (label.length > 1) 0.32f else 0.42f).sp,
        )
    }
}

private val POINTS = mapOf("A" to 1.0, "B" to 1.5, "C" to 2.0, "D" to 2.5, "E" to 3.0, "FX" to 4.0)

/** Credit-weighted average from the letters, for when AiS2 sends none. */
fun computedAverage(grades: List<Grade>): String? {
    val rated = grades.mapNotNull { g -> POINTS[g.grade?.uppercase()]?.let { it to g.credits.coerceAtLeast(1) } }
    if (rated.isEmpty()) return null
    return (rated.sumOf { it.first * it.second } / rated.sumOf { it.second }).toString()
}

fun formatAverage(v: String): String {
    val n = v.replace(',', '.').toDoubleOrNull() ?: return v
    return String.format(java.util.Locale.forLanguageTag("sk"), "%.2f", n)
}
