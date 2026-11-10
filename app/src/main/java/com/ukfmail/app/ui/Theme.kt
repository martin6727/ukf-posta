package com.ukfmail.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Mail
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

val Indigo = Color(0xFF5B5EF4)
val Violet = Color(0xFF8B5CF6)
val BrandGradient = Brush.linearGradient(listOf(Indigo, Violet))

enum class ThemeMode(val label: String) { SYSTEM("Podľa systému"), LIGHT("Svetlý"), DARK("Tmavý") }

/** Whether the app is dark right now (the user's choice in settings, or the phone's). */
val LocalDark = staticCompositionLocalOf { false }

@Composable
fun isDark(): Boolean = LocalDark.current

@Composable
fun AppTheme(mode: ThemeMode = ThemeMode.SYSTEM, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val scheme = if (dark) {
        darkColorScheme(
            primary = Color(0xFF9294FA), onPrimary = Color(0xFF14154A),
            primaryContainer = Color(0xFF2C2D63), onPrimaryContainer = Color(0xFFE2E2FF),
            secondaryContainer = Color(0xFF26273F), onSecondaryContainer = Color(0xFFE3E3FF),
            background = Color(0xFF0E0F14), onBackground = Color(0xFFE8E9EF),
            surface = Color(0xFF0E0F14), onSurface = Color(0xFFE8E9EF),
            surfaceVariant = Color(0xFF1E1F28), onSurfaceVariant = Color(0xFFA2A4B3),
            surfaceContainerLowest = Color(0xFF0B0C10),
            surfaceContainerLow = Color(0xFF15161D),
            surfaceContainer = Color(0xFF191A22),
            surfaceContainerHigh = Color(0xFF1F2029),
            surfaceContainerHighest = Color(0xFF262733),
            outline = Color(0xFF3A3C4A), outlineVariant = Color(0xFF282A35),
        )
    } else {
        lightColorScheme(
            primary = Indigo, onPrimary = Color.White,
            primaryContainer = Color(0xFFE6E6FF), onPrimaryContainer = Color(0xFF1E1F6B),
            secondaryContainer = Color(0xFFEDECFF), onSecondaryContainer = Color(0xFF26246B),
            background = Color(0xFFFFFFFF), onBackground = Color(0xFF1B1D26),
            surface = Color(0xFFFFFFFF), onSurface = Color(0xFF1B1D26),
            surfaceVariant = Color(0xFFF1F1F7), onSurfaceVariant = Color(0xFF636677),
            surfaceContainerLowest = Color.White,
            surfaceContainerLow = Color(0xFFF7F7FB),
            surfaceContainer = Color(0xFFF2F2F8),
            surfaceContainerHigh = Color(0xFFECECF4),
            surfaceContainerHighest = Color(0xFFE6E6EF),
            outline = Color(0xFFC9CAD6), outlineVariant = Color(0xFFE7E8EF),
        )
    }
    val base = Typography()
    val typography = base.copy(
        headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
        headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    )
    CompositionLocalProvider(LocalDark provides dark) {
        MaterialTheme(colorScheme = scheme, typography = typography, content = content)
    }
}

/** Two-tone avatar gradients; each sender always gets the same one. */
private val avatarPairs = listOf(
    0xFF6366F1 to 0xFFA855F7, // indigo → purple
    0xFFEC4899 to 0xFFF97316, // pink → orange
    0xFF06B6D4 to 0xFF3B82F6, // cyan → blue
    0xFF10B981 to 0xFF0EA5E9, // emerald → sky
    0xFFF59E0B to 0xFFEF4444, // amber → red
    0xFF8B5CF6 to 0xFFEC4899, // violet → pink
    0xFF14B8A6 to 0xFF84CC16, // teal → lime
    0xFFF43F5E to 0xFFA855F7, // rose → purple
)

private fun pairFor(key: String) = avatarPairs[Math.floorMod(key.lowercase().hashCode(), avatarPairs.size)]

fun avatarBrush(key: String): Brush {
    val (a, b) = pairFor(key)
    return Brush.linearGradient(listOf(Color(a), Color(b)))
}

/** The same gradient for the HTML message view. */
fun avatarCss(key: String): String {
    val (a, b) = pairFor(key)
    return "linear-gradient(135deg,#%06X,#%06X)".format(a and 0xFFFFFF, b and 0xFFFFFF)
}

private val titles = Regex("""\b(Mgr|Bc|Ing|PhDr|PaedDr|RNDr|doc|prof|PhD|MUDr|JUDr|Dr|MSc|MBA|art|ArtD)\.?(?=\s|,|$)""", RegexOption.IGNORE_CASE)

fun initials(name: String): String {
    val clean = name.replace(Regex("[\"'<>()]"), " ").replace(titles, " ").replace(",", " ").trim()
    val parts = clean.split(Regex("[\\s@._-]+")).filter { it.isNotBlank() && it.first().isLetterOrDigit() }
    if (parts.isEmpty()) return "?"
    val first = parts.first().first()
    val last = if (parts.size > 1 && !clean.contains('@')) parts.last().first() else null
    return (first.toString() + (last?.toString() ?: "")).uppercase()
}

@Composable
fun Avatar(name: String, key: String, size: Dp = 44.dp) {
    Box(
        Modifier.size(size).clip(CircleShape).background(avatarBrush(key)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            initials(name),
            color = Color.White,
            style = TextStyle(fontSize = (size.value * 0.36f).sp, fontWeight = FontWeight.SemiBold),
        )
    }
}

@Composable
fun AppMark(size: Dp) {
    val corner = RoundedCornerShape(size * 0.3f)
    Box(
        Modifier
            .size(size)
            .shadow(size * 0.2f, corner, ambientColor = Indigo, spotColor = Indigo)
            .clip(corner)
            .background(BrandGradient),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Outlined.Mail, null, tint = Color.White, modifier = Modifier.size(size * 0.5f))
    }
}

private val sk = Locale.forLanguageTag("sk")

/** Short date for the list: time today, weekday this week, day and month this year, else full date. */
fun shortDate(millis: Long, now: Long = System.currentTimeMillis()): String {
    if (millis <= 0) return ""
    val c = Calendar.getInstance().apply { timeInMillis = millis }
    val n = Calendar.getInstance().apply { timeInMillis = now }
    val sameYear = c.get(Calendar.YEAR) == n.get(Calendar.YEAR)
    val days = (startOfDay(now) - startOfDay(millis)) / 86_400_000L
    val pattern = when {
        days == 0L -> "H:mm"
        days == 1L -> return "Včera"
        days in 2..6 -> "EEE"
        sameYear -> "d. MMM"
        else -> "d. M. yyyy"
    }
    return SimpleDateFormat(pattern, sk).format(Date(millis)).replaceFirstChar { it.uppercase() }
}

/** Section title for the inbox: Dnes, Včera, Tento týždeň, the month name, or the year. */
fun dayGroup(millis: Long, now: Long = System.currentTimeMillis()): String {
    if (millis <= 0) return "Staršie"
    val days = (startOfDay(now) - startOfDay(millis)) / 86_400_000L
    val c = Calendar.getInstance().apply { timeInMillis = millis }
    val n = Calendar.getInstance().apply { timeInMillis = now }
    return when {
        days <= 0L -> "Dnes"
        days == 1L -> "Včera"
        days in 2..6 -> "Tento týždeň"
        c.get(Calendar.YEAR) == n.get(Calendar.YEAR) ->
            SimpleDateFormat("LLLL", sk).format(Date(millis)).replaceFirstChar { it.uppercase() }
        else -> c.get(Calendar.YEAR).toString()
    }
}

fun greeting(now: Long = System.currentTimeMillis()): String =
    when (Calendar.getInstance().apply { timeInMillis = now }.get(Calendar.HOUR_OF_DAY)) {
        in 4..10 -> "Dobré ráno"
        in 11..17 -> "Dobrý deň"
        else -> "Dobrý večer"
    }

fun longDate(millis: Long): String =
    if (millis <= 0) "" else SimpleDateFormat("EEEE d. MMMM yyyy, H:mm", sk).format(Date(millis))

private fun startOfDay(millis: Long): Long = Calendar.getInstance().apply {
    timeInMillis = millis
    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}.timeInMillis

fun fileSize(bytes: Long): String = when {
    bytes <= 0 -> ""
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} kB"
    else -> String.format(sk, "%.1f MB", bytes / 1048576.0)
}
