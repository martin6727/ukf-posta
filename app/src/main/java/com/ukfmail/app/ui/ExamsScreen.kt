package com.ukfmail.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.EventNote
import androidx.compose.material.icons.outlined.AccessTime
import androidx.compose.material.icons.outlined.EventAvailable
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.HowToReg
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ukfmail.app.StudyViewModel
import com.ukfmail.app.ais.Exam
import com.ukfmail.app.ais.ExamDetail
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

private val sk = Locale.forLanguageTag("sk")

enum class ExamFilter(val label: String) { MINE("Prihlásené"), OPEN("Dostupné"), PAST("Prebehnuté") }

fun examsFor(filter: ExamFilter, exams: List<Exam>, today: LocalDate): List<Exam> {
    fun isPast(e: Exam) = !e.upcoming && (e.date == null || e.date.isBefore(today))
    return when (filter) {
        ExamFilter.MINE -> exams.filter { it.registered && !isPast(it) }
        ExamFilter.OPEN -> exams.filter { !it.registered && !isPast(it) }
        ExamFilter.PAST -> exams.filter { isPast(it) }.reversed()
    }
}

@Composable
fun ExamsScreen(vm: StudyViewModel) {
    LaunchedEffect(Unit) { vm.start() }
    var open by remember { mutableStateOf<Exam?>(null) }
    ExamsContent(
        exams = vm.data.exams,
        sheetLabel = vm.data.sheet?.year.orEmpty(),
        updatedAt = vm.data.updatedAt,
        loading = vm.loading,
        error = vm.error,
        needsLogin = vm.needsLogin,
        suggestedLogin = vm.suggestedLogin,
        onSignIn = vm::signIn,
        onRefresh = vm::refresh,
        onOpen = { open = it },
    )
    open?.let { e ->
        // Keep showing the latest copy of the term after a sign-up changes it.
        val current = vm.data.exams.firstOrNull { it.id == e.id } ?: e
        ExamSheet(
            exam = current,
            loadDetail = { vm.detail(current) },
            busy = vm.busyExam == current.id,
            onRegister = { showName -> vm.register(current, showName) },
            onUnregister = { vm.unregister(current) },
            onDismiss = { open = null },
        )
    }
}

@Composable
fun ExamsContent(
    exams: List<Exam>,
    sheetLabel: String,
    updatedAt: Long,
    loading: Boolean,
    error: String?,
    needsLogin: Boolean,
    suggestedLogin: String,
    onSignIn: (String, String) -> Unit,
    onRefresh: () -> Unit,
    onOpen: (Exam) -> Unit,
    today: LocalDate = LocalDate.now(),
) {
    val mineCount = examsFor(ExamFilter.MINE, exams, today).size
    var chosen by rememberSaveable { mutableStateOf<ExamFilter?>(null) }
    val filter = chosen ?: if (mineCount > 0) ExamFilter.MINE else ExamFilter.OPEN
    val shown = remember(exams, filter, today) { examsFor(filter, exams, today) }
    val next = examsFor(ExamFilter.MINE, exams, today).firstOrNull()

    StudyFrame(
        refreshing = loading && exams.isNotEmpty(),
        onRefresh = onRefresh,
        header = {
            StudyTitle(sheetLabel.ifBlank { "Termíny" }, "Skúšky", updatedAt, loading)
            Spacer(Modifier.height(6.dp))
            Text(
                next?.let { "Najbližšia ${relativeDay(it.date, today)} · ${it.name}" } ?: "Žiadna prihlásená skúška",
                fontSize = 14.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        },
    ) {
        if (exams.isNotEmpty()) item(key = "filters") {
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(ExamFilter.entries) { f ->
                    val n = examsFor(f, exams, today).size
                    Pill(if (n > 0 && f != ExamFilter.PAST) "${f.label} · $n" else f.label, active = f == filter, onClick = { chosen = f })
                }
            }
        }
        if (studyStatus(exams.isNotEmpty(), loading, error, needsLogin, suggestedLogin, onSignIn, onRefresh)) return@StudyFrame
        if (shown.isEmpty()) {
            item(key = "empty") {
                when (filter) {
                    ExamFilter.MINE -> EmptyState(Icons.Outlined.EventAvailable, "Nie si prihlásený", "Na žiadny budúci termín. Pozri, čo je Dostupné.")
                    ExamFilter.OPEN -> EmptyState(Icons.AutoMirrored.Outlined.EventNote, "Žiadne termíny", "Vyučujúci zatiaľ nevypísali termíny, na ktoré sa dá prihlásiť.")
                    ExamFilter.PAST -> EmptyState(Icons.Outlined.History, "Nič za tebou", "Tu budú skúšky, ktoré už prebehli.")
                }
            }
        } else {
            var lastMonth: String? = null
            shown.forEach { e ->
                val month = e.date?.let { DateTimeFormatter.ofPattern("LLLL yyyy", sk).format(it).replaceFirstChar { c -> c.uppercase() } } ?: "Bez dátumu"
                if (month != lastMonth) {
                    lastMonth = month
                    item(key = "m-$month") { MonthHeader(month) }
                }
                item(key = "e-${e.id}") { ExamCard(e, today) { onOpen(e) } }
            }
        }
    }
}

@Composable
private fun MonthHeader(title: String) {
    Row(Modifier.padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        Spacer(Modifier.width(12.dp))
        GradientRule(Modifier.weight(1f))
    }
}

@Composable
private fun ExamCard(e: Exam, today: LocalDate, onClick: () -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier.padding(horizontal = 12.dp, vertical = 4.dp).fillMaxWidth().clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer).clickable(onClick = onClick).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DateTile(e.date, highlighted = e.registered && e.upcoming)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(e.name, fontWeight = FontWeight.Bold, fontSize = 15.5.sp, lineHeight = 20.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                InfoLine(Icons.Outlined.AccessTime, listOfNotNull(e.time, relativeDay(e.date, today).takeIf { e.upcoming }).joinToString(" · ").ifBlank { e.dateText })
            }
            if (!e.registered && e.upcoming && e.registerUntil != null) {
                Spacer(Modifier.height(2.dp))
                Text("Prihlásiť sa do ${e.registerUntil}", fontSize = 12.5.sp, color = muted, maxLines = 1)
            }
            val tags = examTags(e)
            if (tags.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { tags.forEach { (t, c, f) -> Tag(t, c, f) } }
            }
        }
        (e.termGrade ?: e.subjectGrade)?.let { g ->
            Spacer(Modifier.width(10.dp))
            GradeBadge(g, 42.dp)
        }
    }
}

private fun examTags(e: Exam): List<Triple<String, Color, Boolean>> = buildList {
    if (e.registered) add(Triple("Prihlásený", Emerald, true))
    if (e.full && !e.registered) add(Triple("Plný termín", Rose, false))
    if (e.unregisteredBy != null) add(Triple("Odhlásený", Rose, false))
    e.phase?.let { add(Triple(it, Violet, false)) }
}

@Composable
private fun DateTile(date: LocalDate?, highlighted: Boolean) {
    val fg = if (highlighted) Color.White else MaterialTheme.colorScheme.onSurface
    Column(
        Modifier.size(width = 58.dp, height = 66.dp).clip(RoundedCornerShape(18.dp))
            .then(
                if (highlighted) Modifier.background(Brush.linearGradient(listOf(Indigo, Violet, Pink)))
                else Modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest)
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (date == null) {
            Text("?", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = fg)
        } else {
            Text(date.dayOfWeek.getDisplayName(TextStyle.SHORT, sk).take(2).uppercase(), fontSize = 10.5.sp, color = fg.copy(alpha = 0.75f), fontWeight = FontWeight.SemiBold)
            Text(date.dayOfMonth.toString(), fontSize = 22.sp, lineHeight = 24.sp, fontWeight = FontWeight.ExtraBold, color = fg)
            Text(date.month.getDisplayName(TextStyle.SHORT, sk).take(3).uppercase(), fontSize = 10.5.sp, color = fg.copy(alpha = 0.75f), fontWeight = FontWeight.SemiBold)
        }
    }
}

fun relativeDay(date: LocalDate?, today: LocalDate): String {
    if (date == null) return ""
    val days = ChronoUnit.DAYS.between(today, date)
    return when {
        days == 0L -> "dnes"
        days == 1L -> "zajtra"
        days in 2..4 -> "o $days dni"
        days in 5..30 -> "o $days dní"
        days < 0 && days == -1L -> "včera"
        days < 0 -> "pred ${-days} dňami"
        else -> DateTimeFormatter.ofPattern("d. M.", sk).format(date)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExamSheet(
    exam: Exam,
    loadDetail: suspend () -> Result<ExamDetail>,
    busy: Boolean,
    onRegister: (Boolean) -> Unit,
    onUnregister: () -> Unit,
    onDismiss: () -> Unit,
) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var detail by remember(exam.id) { mutableStateOf<Result<ExamDetail>?>(null) }
    LaunchedEffect(exam.id, exam.registered) { detail = loadDetail() }
    var askRegister by remember { mutableStateOf(false) }
    var askUnregister by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding().padding(bottom = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                DateTile(exam.date, highlighted = true)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(exam.name, fontSize = 20.sp, lineHeight = 25.sp, fontWeight = FontWeight.Bold)
                    Text(exam.code, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(18.dp))
            val whenText = listOfNotNull(
                exam.date?.let { DateTimeFormatter.ofPattern("EEEE d. MMMM yyyy", sk).format(it) } ?: exam.dateText.ifBlank { null },
                exam.time,
            ).joinToString(", ")
            DetailRow(Icons.Outlined.AccessTime, "Kedy", whenText)
            val d = detail?.getOrNull()
            when {
                detail == null -> Row(Modifier.padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Indigo)
                    Spacer(Modifier.width(10.dp))
                    Text("Načítavam podrobnosti…", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                d == null -> Text(
                    detail?.exceptionOrNull()?.message ?: "Podrobnosti sa nenačítali.",
                    fontSize = 13.sp, color = Rose, modifier = Modifier.padding(vertical = 10.dp),
                )
                else -> {
                    if (d.rooms.isNotEmpty()) DetailRow(Icons.Outlined.Place, "Miestnosť", d.rooms.joinToString(", "))
                    if (d.examiners.isNotEmpty()) DetailRow(Icons.Outlined.Person, "Skúšajúci", d.examiners.joinToString(", "))
                    val people = listOfNotNull(
                        d.students.size.takeIf { it > 0 }?.let { "$it prihlásených" },
                        d.capacity?.let { "kapacita $it" },
                    ).joinToString(" · ")
                    if (people.isNotBlank()) DetailRow(Icons.Outlined.Groups, "Obsadenosť", people)
                    range(d.registerFrom, d.registerTo)?.let { DetailRow(Icons.Outlined.HowToReg, "Prihlasovanie", it) }
                    range(d.unregisterFrom, d.unregisterTo)?.let { DetailRow(Icons.AutoMirrored.Outlined.EventNote, "Odhlasovanie", it) }
                    d.note?.let { DetailRow(Icons.Outlined.Info, "Poznámka", it) }
                }
            }
            exam.unregisteredBy?.let { DetailRow(Icons.Outlined.Info, "Odhlásil", it) }
            (exam.termGrade ?: exam.subjectGrade)?.let { g ->
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    GradeBadge(g, 40.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(if (exam.termGrade != null) "Hodnotenie z tohto termínu" else "Predmet už máš hodnotený", fontSize = 14.sp)
                }
            }
            Spacer(Modifier.height(20.dp))
            when {
                exam.registered && exam.upcoming -> OutlinedButton(
                    onClick = { askUnregister = true }, enabled = !busy,
                    shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth().height(52.dp),
                ) { if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Text("Odhlásiť sa z termínu", fontSize = 15.sp) }
                exam.canRegister -> GradientButton(
                    onClick = { askRegister = true }, enabled = !busy, modifier = Modifier.fillMaxWidth().height(52.dp),
                ) {
                    if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Color.White)
                    else Text("Prihlásiť sa na termín", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                }
                exam.upcoming && exam.full -> Text("Termín je plný.", color = Rose, fontSize = 14.sp)
            }
        }
    }

    if (askRegister) {
        var showName by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { askRegister = false },
            title = { Text("Prihlásiť sa na termín?") },
            text = {
                Column {
                    Text("${exam.name}\n${exam.whenLine}", fontSize = 14.sp)
                    Spacer(Modifier.height(12.dp))
                    Row(Modifier.clip(RoundedCornerShape(12.dp)).clickable { showName = !showName }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(showName, { showName = it }, colors = CheckboxDefaults.colors(checkedColor = Indigo))
                        Text("Zobraziť moje meno v zozname prihlásených", fontSize = 14.sp)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { askRegister = false; onRegister(showName) }) { Text("Prihlásiť") } },
            dismissButton = { TextButton(onClick = { askRegister = false }) { Text("Zrušiť") } },
        )
    }
    if (askUnregister) {
        AlertDialog(
            onDismissRequest = { askUnregister = false },
            title = { Text("Odhlásiť sa z termínu?") },
            text = { Text("${exam.name}\n${exam.whenLine}", fontSize = 14.sp) },
            confirmButton = { TextButton(onClick = { askUnregister = false; onUnregister() }) { Text("Odhlásiť", color = Rose) } },
            dismissButton = { TextButton(onClick = { askUnregister = false }) { Text("Nechať") } },
        )
    }
}

private val Exam.whenLine get() = listOfNotNull(dateText.ifBlank { null }, time).joinToString(" o ")

private fun range(from: String?, to: String?): String? = when {
    from != null && to != null -> "$from – $to"
    to != null -> "do $to"
    from != null -> "od $from"
    else -> null
}

@Composable
private fun DetailRow(icon: ImageVector, label: String, value: String) {
    if (value.isBlank()) return
    Row(Modifier.padding(vertical = 7.dp), verticalAlignment = Alignment.Top) {
        Box(
            Modifier.size(36.dp).clip(RoundedCornerShape(12.dp)).background(Indigo.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, Modifier.size(19.dp), tint = Indigo) }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, fontSize = 15.sp, lineHeight = 20.sp)
        }
    }
}
