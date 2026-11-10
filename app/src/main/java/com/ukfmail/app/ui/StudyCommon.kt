package com.ukfmail.app.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.EventAvailable
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.School
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class Tab(val label: String, val icon: ImageVector) {
    MAIL("Pošta", Icons.Outlined.Inbox),
    SCHEDULE("Rozvrh", Icons.Outlined.CalendarMonth),
    EXAMS("Skúšky", Icons.Outlined.EventAvailable),
    GRADES("Známky", Icons.Outlined.School),
}

/** Opens the settings sheet from anywhere in the app. */
val LocalOpenSettings = staticCompositionLocalOf<() -> Unit> { {} }

/** Room the floating tab bar takes at the bottom, on top of the system navigation bar. */
val TabBarSpace = 84.dp

/** Pull-to-refresh list with the soft glow behind the header, like the inbox. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StudyFrame(
    refreshing: Boolean,
    onRefresh: () -> Unit,
    listState: LazyListState = rememberLazyListState(),
    header: @Composable ColumnScope.() -> Unit,
    content: LazyListScope.() -> Unit,
) {
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val density = LocalDensity.current
    var headerPx by remember { mutableIntStateOf(0) }
    val atTop by remember { derivedStateOf { listState.firstVisibleItemIndex == 0 } }
    val surface = MaterialTheme.colorScheme.surface
    val pull = rememberPullToRefreshState()

    // No Surface above these tabs, so text would default to black in dark mode.
    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = onRefresh,
        state = pull,
        modifier = Modifier.fillMaxSize().background(surface),
        indicator = {
            PullToRefreshDefaults.Indicator(
                state = pull, isRefreshing = refreshing, color = Indigo,
                modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding(),
            )
        },
    ) {
        if (atTop) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(statusTop + with(density) { headerPx.toDp() })
                    // Clip after moving, so glow that spills past the bottom never slides into view as a hard edge.
                    .graphicsLayer { translationY = -listState.firstVisibleItemScrollOffset.toFloat(); clip = true }
            ) {
                Glow(Modifier.matchParentSize())
                Box(Modifier.matchParentSize().background(Brush.verticalGradient(0.5f to Color.Transparent, 1f to surface)))
            }
        }
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(bottom = TabBarSpace + bottom + 16.dp),
            modifier = Modifier.fillMaxSize().padding(top = statusTop),
        ) {
            item(key = "header") {
                Column(Modifier.onSizeChanged { headerPx = it.height }.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 6.dp)) {
                    header()
                }
            }
            content()
        }
    }
    }
}

/** Top row (sync status), small line above, and the big title. */
@Composable
fun StudyTitle(kicker: String, title: String, updatedAt: Long, loading: Boolean) {
    Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(30.dp).clip(RoundedCornerShape(9.dp)).background(BrandGradient), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.School, null, tint = Color.White, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(10.dp))
        Text("AiS2", fontWeight = FontWeight.Bold, fontSize = 15.sp)
        Spacer(Modifier.weight(1f))
        Row(
            Modifier.clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.surface.copy(alpha = 0.6f))
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (loading) CircularProgressIndicator(Modifier.size(13.dp), strokeWidth = 2.dp, color = Indigo)
            else Icon(Icons.Outlined.Sync, null, Modifier.size(15.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(6.dp))
            Text(
                if (loading) "Načítavam…" else syncedLabel(updatedAt),
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(8.dp))
        GlassButton(Icons.Outlined.Settings, "Nastavenia", LocalOpenSettings.current, size = 36.dp)
    }
    Spacer(Modifier.height(20.dp))
    if (kicker.isNotBlank()) {
        Text(kicker, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Medium,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    Text(title, fontSize = 34.sp, lineHeight = 40.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.8).sp)
}

private val sk = Locale.forLanguageTag("sk")

fun syncedLabel(millis: Long, now: Long = System.currentTimeMillis()): String = when {
    millis <= 0 -> "Neaktualizované"
    now - millis < 60_000 -> "Práve aktualizované"
    now - millis < 3_600_000 -> "Pred ${(now - millis) / 60_000} min"
    shortDate(millis, now).contains(':') -> "Dnes " + SimpleDateFormat("H:mm", sk).format(Date(millis))
    else -> shortDate(millis, now)
}

/** Shown in place of a tab's content when AiS2 refused the login, so other credentials can be typed. */
@Composable
fun AisLoginCard(message: String?, suggestedLogin: String, loading: Boolean, onSignIn: (String, String) -> Unit) {
    var login by rememberSaveable { mutableStateOf(suggestedLogin) }
    var pass by rememberSaveable { mutableStateOf("") }
    Column(
        Modifier.padding(16.dp).fillMaxWidth().clip(RoundedCornerShape(26.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer).padding(20.dp)
    ) {
        Box(Modifier.size(48.dp).clip(RoundedCornerShape(16.dp)).background(BrandGradient), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.Lock, null, tint = Color.White)
        }
        Spacer(Modifier.height(14.dp))
        Text("Prihlásenie do AiS2", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(
            (message?.let { "$it " } ?: "") + "Zadaj meno a heslo, ktorými sa prihlasuješ na ais2.ukf.sk.",
            fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(14.dp))
        OutlinedTextField(
            login, { login = it }, label = { Text("Prihlasovacie meno") }, singleLine = true,
            shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            pass, { pass = it }, label = { Text("Heslo") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(16.dp))
        GradientButton(
            onClick = { onSignIn(login, pass) },
            enabled = !loading && login.isNotBlank() && pass.isNotBlank(),
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            if (loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Color.White)
            else Text("Prihlásiť", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
        }
    }
}

/** The common "nothing loaded yet / it failed / AiS2 wants a login" states for a study tab. */
fun LazyListScope.studyStatus(
    hasData: Boolean,
    loading: Boolean,
    error: String?,
    needsLogin: Boolean,
    suggestedLogin: String,
    onSignIn: (String, String) -> Unit,
    onRetry: () -> Unit,
): Boolean {
    when {
        needsLogin -> item(key = "login") { AisLoginCard(error, suggestedLogin, loading, onSignIn) }
        !hasData && loading -> item(key = "loading") {
            Box(Modifier.fillMaxWidth().padding(top = 80.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Indigo) }
        }
        !hasData && error != null -> item(key = "error") {
            EmptyState(Icons.Outlined.WifiOff, "AiS2 sa nenačítal", error, "Skúsiť znova", onRetry)
        }
        else -> return false
    }
    return true
}

/** Small rounded tag, e.g. "Prednáška" or "Plný termín". */
@Composable
fun Tag(text: String, color: Color, filled: Boolean = false) {
    Text(
        text,
        color = if (filled) Color.White else color,
        fontSize = 11.5.sp,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (filled) color else color.copy(alpha = 0.14f))
            .padding(horizontal = 9.dp, vertical = 3.dp),
    )
}

/** Icon and muted text on one line. */
@Composable
fun InfoLine(icon: ImageVector, text: String, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(15.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(5.dp))
        Text(text, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

val Emerald = Color(0xFF10B981)
val Amber = Color(0xFFF59E0B)
