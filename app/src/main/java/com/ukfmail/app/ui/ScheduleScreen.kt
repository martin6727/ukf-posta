package com.ukfmail.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Coffee
import androidx.compose.material.icons.outlined.EventBusy
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ukfmail.app.StudyViewModel
import com.ukfmail.app.ais.Lesson
import com.ukfmail.app.ais.Timetable
import com.ukfmail.app.events.DayPlan
import com.ukfmail.app.events.EventKind
import com.ukfmail.app.events.Holidays
import com.ukfmail.app.events.PlannedLesson
import com.ukfmail.app.events.Plans
import com.ukfmail.app.events.StudyEvent
import java.time.LocalTime
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.material.icons.outlined.AssignmentLate
import androidx.compose.material.icons.outlined.Celebration
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.MailOutline
import androidx.compose.material.icons.outlined.Quiz
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlinx.coroutines.delay

private val sk = Locale.forLanguageTag("sk")
private val HM = DateTimeFormatter.ofPattern("H:mm")

@Composable
fun ScheduleScreen(vm: StudyViewModel, onOpenMail: (String) -> Unit = {}) {
    LaunchedEffect(Unit) { vm.start() }
    // Ticks every half minute so "now" and "next" stay right while the screen is open.
    val now by produceState(LocalDateTime.now()) { while (true) { delay(30_000); value = LocalDateTime.now() } }
    ScheduleContent(
        timetable = vm.data.timetable,
        updatedAt = vm.data.updatedAt,
        loading = vm.loading,
        error = vm.error,
        needsLogin = vm.needsLogin,
        suggestedLogin = vm.suggestedLogin,
        onSignIn = vm::signIn,
        onRefresh = vm::refresh,
        now = now,
        events = vm.mailEvents,
        onOpenMail = onOpenMail,
    )
}

/** A row of the day: a lesson or something from mail, in time order. */
private sealed interface DayItem {
    val at: LocalTime
    data class Class(val p: PlannedLesson) : DayItem { override val at: LocalTime get() = p.lesson.start.toLocalTime() }
    data class Note(val e: StudyEvent) : DayItem { override val at: LocalTime get() = e.from ?: LocalTime.MIN }
}

@Composable
fun ScheduleContent(
    timetable: Timetable,
    updatedAt: Long,
    loading: Boolean,
    error: String?,
    needsLogin: Boolean,
    suggestedLogin: String,
    onSignIn: (String, String) -> Unit,
    onRefresh: () -> Unit,
    now: LocalDateTime,
    events: List<StudyEvent> = emptyList(),
    onOpenMail: (String) -> Unit = {},
) {
    val today = now.toLocalDate()
    // On a weekend the next week is more useful than the one ending.
    val baseMonday = today.with(DayOfWeek.MONDAY).let { if (today.dayOfWeek.value >= 6) it.plusWeeks(1) else it }
    var weekOffset by rememberSaveable { mutableIntStateOf(0) }
    val monday = baseMonday.plusWeeks(weekOffset.toLong())
    val week = remember(timetable, monday) { timetable.lessons(monday, monday.plusDays(6)) }
    val weekendUsed = week.any { it.start.dayOfWeek.value >= 6 }
    val days = (0 until if (weekendUsed) 7 else 5).map { monday.plusDays(it.toLong()) }
    var picked by rememberSaveable { mutableStateOf<String?>(null) }
    val selected = picked?.let { LocalDate.parse(it) }?.takeIf { it in days }
        ?: days.firstOrNull { it == today } ?: days.first()
    val lessons = week.filter { it.start.toLocalDate() == selected }
    val plan = remember(lessons, events, selected) { Plans.day(selected, lessons, events) }
    // Days with something from mail (or a public holiday) get a pink dot in the strip.
    val marked = remember(events, days) {
        days.filter { d -> Holidays.on(d).isNotEmpty() || events.any { it.date == d && it.kind != EventKind.CANCELLED } }.toSet() +
            days.filter { d -> Plans.day(d, week.filter { it.start.toLocalDate() == d }, events).lessons.any { it.cancelled } }
    }
    val active = plan.lessons.filter { !it.cancelled }.map { it.lesson }

    StudyFrame(
        refreshing = loading && !timetable.isEmpty,
        onRefresh = onRefresh,
        header = {
            StudyTitle(weekLabel(monday, baseMonday, today), "Rozvrh", updatedAt, loading)
            Spacer(Modifier.height(6.dp))
            Text(daySummary(selected, today, active, now, plan), fontSize = 14.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))
            WeekStrip(days, selected, today, week, marked, onPick = { picked = it.toString() },
                onPrev = { weekOffset--; picked = null }, onNext = { weekOffset++; picked = null })
        },
    ) {
        if (studyStatus(!timetable.isEmpty, loading, error, needsLogin, suggestedLogin, onSignIn, onRefresh)) return@StudyFrame
        item(key = "gap-top") { Spacer(Modifier.height(10.dp)) }
        plan.freeDays.forEach { f -> item(key = "free-${f.title}-${f.mailUid}") { FreeDayBanner(f, onOpenMail) } }
        val items = (plan.lessons.map { DayItem.Class(it) } + plan.notes.map { DayItem.Note(it) }).sortedBy { it.at }
        when {
            items.isEmpty() && timetable.isEmpty && plan.freeDays.isEmpty() -> item(key = "none") {
                EmptyState(Icons.Outlined.EventBusy, "Rozvrh zatiaľ nie je", "AiS2 pre tento semester rozvrh neukazuje. Skús neskôr alebo potiahni nadol.")
            }
            items.isEmpty() && plan.freeDays.isEmpty() -> item(key = "free") {
                EmptyState(Icons.Outlined.WbSunny, "Voľný deň", if (selected == today) "Dnes nemáš žiadnu hodinu." else "V tento deň nemáš žiadnu hodinu.")
            }
            else -> {
                var lastEnd: LocalDateTime? = null
                items.forEachIndexed { i, it ->
                    when (it) {
                        is DayItem.Class -> {
                            val l = it.p.lesson
                            val prev = lastEnd
                            if (prev != null && !it.p.cancelled) {
                                val gap = Duration.between(prev, l.start).toMinutes()
                                if (gap >= 20) item(key = "break-$i") { BreakRow(gap) }
                            }
                            if (!it.p.cancelled) lastEnd = l.end
                            item(key = "l-$i-${l.start}") { LessonCard(l, now, it.p.off, onOpenMail) }
                        }
                        is DayItem.Note -> item(key = "n-$i-${it.e.mailUid}") { NoteCard(it.e, onOpenMail) }
                    }
                }
            }
        }
    }
}

/** "Rektorské voľno" and the like, at the top of the day. */
@Composable
private fun FreeDayBanner(e: StudyEvent, onOpenMail: (String) -> Unit) {
    val shape = RoundedCornerShape(24.dp)
    Row(
        Modifier.padding(horizontal = 16.dp, vertical = 5.dp).fillMaxWidth().clip(shape)
            .background(Brush.linearGradient(listOf(Emerald, Sky)))
            .then(if (e.mailUid.isNotBlank()) Modifier.clickable { onOpenMail(e.mailUid) } else Modifier)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(Color.White.copy(alpha = 0.22f)), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.Celebration, null, tint = Color.White)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(e.title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            val span = when {
                e.from != null && e.to != null -> "od ${e.from.format(HM)} do ${e.to.format(HM)}"
                e.to != null -> "do ${e.to.format(HM)}"
                e.from != null -> "od ${e.from.format(HM)}"
                else -> "celý deň"
            }
            Text(
                if (e.mailUid.isBlank()) "Štátny sviatok · $span" else "$span · ťukni pre e-mail",
                color = Color.White.copy(alpha = 0.9f), fontSize = 13.sp,
            )
        }
    }
}

/** Excursion, test, deadline or an unmatched cancellation from mail. */
@Composable
private fun NoteCard(e: StudyEvent, onOpenMail: (String) -> Unit) {
    val (icon, color) = when (e.kind) {
        EventKind.TEST -> Icons.Outlined.Quiz to Rose
        EventKind.DEADLINE -> Icons.Outlined.AssignmentLate to Amber
        EventKind.CANCELLED -> Icons.Outlined.EventBusy to Rose
        else -> Icons.Outlined.Event to Pink
    }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(Modifier.padding(horizontal = 16.dp, vertical = 5.dp).fillMaxWidth().height(IntrinsicSize.Min)) {
        Column(Modifier.width(52.dp).padding(top = 14.dp)) {
            Text(e.from?.format(HM) ?: "—", fontWeight = FontWeight.Bold, fontSize = 15.sp)
            e.to?.let { Text(it.format(HM), fontSize = 13.sp, color = muted) }
        }
        Row(
            Modifier.weight(1f).clip(RoundedCornerShape(22.dp))
                .background(color.copy(alpha = 0.10f).compositeOver(MaterialTheme.colorScheme.surfaceContainer))
                .clickable { onOpenMail(e.mailUid) }
                .padding(horizontal = 14.dp, vertical = 13.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(icon, null, Modifier.size(16.dp), tint = color)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        when (e.kind) {
                            EventKind.TEST -> "Test / skúška"
                            EventKind.DEADLINE -> if (e.from != null) "Odovzdať do ${e.from.format(HM)}" else "Odovzdať"
                            EventKind.CANCELLED -> "Odpadá"
                            else -> e.title
                        },
                        color = color, fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp,
                    )
                }
                Spacer(Modifier.height(5.dp))
                val head = when (e.kind) {
                    EventKind.EVENT -> e.title
                    EventKind.CANCELLED -> e.teacher?.let { "Výučba – $it" } ?: "Výučba"
                    else -> e.title
                }
                Text(head, fontWeight = FontWeight.Bold, fontSize = 16.sp, lineHeight = 21.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val course = e.subjectHint?.takeIf { e.kind == EventKind.TEST || e.kind == EventKind.DEADLINE }?.takeIf { it.length < 80 }
                course?.let { Text(it, fontSize = 13.sp, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                if (e.snippet.isNotBlank() && e.kind != EventKind.DEADLINE && e.kind != EventKind.TEST || course == null) {
                    Spacer(Modifier.height(4.dp))
                    MailQuote(e, muted)
                }
            }
        }
    }
}

/** "✉ Nováková: dňa 27. októbra nebudeme mať výučbu." */
@Composable
private fun MailQuote(e: StudyEvent, muted: Color) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(Icons.Outlined.MailOutline, null, Modifier.padding(top = 2.dp).size(14.dp), tint = muted)
        Spacer(Modifier.width(6.dp))
        Text(
            listOfNotNull(e.teacher, e.snippet.trim()).joinToString(": "),
            fontSize = 12.5.sp, lineHeight = 17.sp, color = muted, maxLines = 3, overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun WeekStrip(
    days: List<LocalDate>,
    selected: LocalDate,
    today: LocalDate,
    week: List<Lesson>,
    marked: Set<LocalDate>,
    onPick: (LocalDate) -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        GlassButton(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, "Predchádzajúci týždeň", onPrev, size = 36.dp)
        Row(Modifier.weight(1f).padding(horizontal = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            days.forEach { d ->
                val isSel = d == selected
                val count = week.count { it.start.toLocalDate() == d }
                val shape = RoundedCornerShape(18.dp)
                Column(
                    Modifier
                        .weight(1f)
                        .clip(shape)
                        .then(
                            if (isSel) Modifier.background(Brush.linearGradient(listOf(Indigo, Violet, Pink)))
                            else Modifier.background(MaterialTheme.colorScheme.surface.copy(alpha = 0.7f))
                        )
                        .then(if (d == today && !isSel) Modifier.border(1.5.dp, Indigo.copy(alpha = 0.6f), shape) else Modifier)
                        .clickable { onPick(d) }
                        .padding(vertical = 9.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    val fg = if (isSel) Color.White else MaterialTheme.colorScheme.onSurface
                    Text(d.dayOfWeek.getDisplayName(TextStyle.SHORT, sk).take(2).replaceFirstChar { it.uppercase() },
                        fontSize = 12.sp, color = fg.copy(alpha = 0.8f), fontWeight = FontWeight.Medium)
                    Text(d.dayOfMonth.toString(), fontSize = 18.sp, color = fg, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(3.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        repeat(count.coerceAtMost(4)) {
                            Box(Modifier.size(4.dp).clip(CircleShape).background(if (isSel) Color.White else Indigo))
                        }
                        if (d in marked) Box(Modifier.size(4.dp).clip(CircleShape).background(if (isSel) Color.White else Pink))
                        if (count == 0 && d !in marked) Spacer(Modifier.size(4.dp))
                    }
                }
            }
        }
        GlassButton(Icons.AutoMirrored.Outlined.KeyboardArrowRight, "Ďalší týždeň", onNext, size = 36.dp)
    }
    Spacer(Modifier.height(4.dp))
}

@Composable
private fun LessonCard(l: Lesson, now: LocalDateTime, off: StudyEvent? = null, onOpenMail: (String) -> Unit = {}) {
    val cancelled = off != null
    val ongoing = !cancelled && !now.isBefore(l.start) && now.isBefore(l.end)
    val past = cancelled || !now.isBefore(l.end)
    val startsIn = Duration.between(now, l.start).toMinutes()
    val soon = !ongoing && !past && !cancelled && l.start.toLocalDate() == now.toLocalDate() && startsIn in 0..120
    val dark = isSystemInDarkTheme()
    val surface = MaterialTheme.colorScheme.surfaceContainer
    val bg = if (ongoing) Indigo.copy(alpha = if (dark) 0.2f else 0.09f).compositeOver(surface) else surface
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    Row(
        Modifier.padding(horizontal = 16.dp, vertical = 5.dp).fillMaxWidth().height(IntrinsicSize.Min),
    ) {
        Column(Modifier.width(52.dp).padding(top = 14.dp)) {
            Text(l.start.format(HM), fontWeight = FontWeight.Bold, fontSize = 15.sp,
                color = if (past) muted else MaterialTheme.colorScheme.onSurface)
            Text(l.end.format(HM), fontSize = 13.sp, color = muted)
        }
        Row(
            Modifier.weight(1f).clip(RoundedCornerShape(22.dp)).background(bg)
                .then(if (off != null && off.mailUid.isNotBlank()) Modifier.clickable { onOpenMail(off.mailUid) } else Modifier)
        ) {
            Box(Modifier.width(5.dp).fillMaxHeight().background(if (past) SolidMuted else avatarBrush(l.title)))
            Column(Modifier.padding(horizontal = 14.dp, vertical = 13.dp)) {
                if (off != null) {
                    Tag(if (off.kind == EventKind.FREE_DAY) off.title else "Odpadá", if (off.kind == EventKind.FREE_DAY) Emerald else Rose, filled = true)
                    Spacer(Modifier.height(8.dp))
                }
                if (ongoing || soon) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (ongoing) Tag("Práve prebieha · ešte ${untilText(Duration.between(now, l.end).toMinutes())}", Indigo, filled = true)
                        else Tag("O ${untilText(startsIn)}", Indigo)
                    }
                    Spacer(Modifier.height(8.dp))
                }
                Text(
                    l.title, fontWeight = FontWeight.Bold, fontSize = 16.sp, lineHeight = 21.sp, maxLines = 2,
                    overflow = TextOverflow.Ellipsis, color = if (past) muted else MaterialTheme.colorScheme.onSurface,
                    textDecoration = if (cancelled) TextDecoration.LineThrough else null,
                )
                Spacer(Modifier.height(6.dp))
                if (l.kind != null || l.code != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        l.kind?.let { Tag(it, kindColor(it)); Spacer(Modifier.width(8.dp)) }
                        l.code?.let { Text(it, fontSize = 12.5.sp, color = muted, maxLines = 1) }
                    }
                    Spacer(Modifier.height(6.dp))
                }
                l.room?.let { InfoLine(Icons.Outlined.Place, it) }
                l.teacher?.let {
                    Spacer(Modifier.height(4.dp))
                    InfoLine(Icons.Outlined.Person, it)
                }
                if (off != null && off.kind == EventKind.CANCELLED) {
                    Spacer(Modifier.height(8.dp))
                    MailQuote(off, muted)
                }
                if (ongoing) {
                    Spacer(Modifier.height(10.dp))
                    val total = Duration.between(l.start, l.end).toMinutes().coerceAtLeast(1)
                    val done = Duration.between(l.start, now).toMinutes()
                    LinearProgressIndicator(
                        progress = { (done.toFloat() / total).coerceIn(0f, 1f) },
                        color = Indigo, trackColor = Indigo.copy(alpha = 0.15f),
                        modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(50)),
                        drawStopIndicator = {},
                    )
                }
            }
        }
    }
}

private val SolidMuted = Brush.linearGradient(listOf(Color(0xFF9A9CAD), Color(0xFF9A9CAD)))

@Composable
private fun BreakRow(minutes: Long) {
    Row(Modifier.padding(start = 76.dp, end = 24.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.Coffee, null, Modifier.size(15.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(6.dp))
        Text("Prestávka ${untilText(minutes)}", fontSize = 12.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(10.dp))
        GradientRule(Modifier.weight(1f))
    }
}

private fun kindColor(kind: String): Color = when {
    kind.startsWith("Predn", true) -> Indigo
    kind.startsWith("Cvi", true) -> Emerald
    kind.startsWith("Semin", true) -> Pink
    kind.startsWith("Lab", true) -> Sky
    else -> Violet
}

fun untilText(minutes: Long): String = when {
    minutes < 60 -> "$minutes min"
    minutes % 60 == 0L -> "${minutes / 60} h"
    else -> "${minutes / 60} h ${minutes % 60} min"
}

private fun weekLabel(monday: LocalDate, baseMonday: LocalDate, today: LocalDate): String {
    val friday = monday.plusDays(4)
    val range = if (monday.month == friday.month) "${monday.dayOfMonth}. – ${friday.dayOfMonth}. ${monthGen(friday)}"
    else "${monday.dayOfMonth}. ${monthGen(monday)} – ${friday.dayOfMonth}. ${monthGen(friday)}"
    val thisWeek = today.with(DayOfWeek.MONDAY)
    val name = when (monday) {
        thisWeek -> "Tento týždeň"
        thisWeek.plusWeeks(1) -> "Budúci týždeň"
        thisWeek.minusWeeks(1) -> "Minulý týždeň"
        else -> null
    }
    return listOfNotNull(name, range).joinToString(" · ")
}

/** Month in the genitive, as in "10. novembra". */
private fun monthGen(d: LocalDate): String = DateTimeFormatter.ofPattern("d. MMMM", sk).format(d).substringAfter(' ')

private fun daySummary(day: LocalDate, today: LocalDate, lessons: List<Lesson>, now: LocalDateTime, plan: DayPlan): String {
    val prefix = when (day) {
        today -> "Dnes"
        today.plusDays(1) -> "Zajtra"
        else -> day.dayOfWeek.getDisplayName(TextStyle.FULL, sk).replaceFirstChar { it.uppercase() }
    }
    plan.freeDays.firstOrNull()?.let { if (lessons.isEmpty()) return "$prefix: ${it.title}" }
    val off = plan.lessons.count { it.cancelled }
    if (lessons.isEmpty()) return if (off > 0) "$prefix voľno · všetky hodiny odpadajú" else "$prefix voľno"
    val n = lessons.size
    val word = when (n) { 1 -> "hodina"; in 2..4 -> "hodiny"; else -> "hodín" }
    if (day == today) {
        val next = lessons.firstOrNull { it.end.isAfter(now) } ?: return "$prefix $n $word · na dnes hotovo"
        if (!next.start.isAfter(now)) return "$prefix $n $word · práve máš ${next.title}"
        return "$prefix $n $word · ďalšia o ${next.start.format(HM)}"
    }
    return "$prefix $n $word · ${lessons.first().start.format(HM)} – ${lessons.last().end.format(HM)}"
}
