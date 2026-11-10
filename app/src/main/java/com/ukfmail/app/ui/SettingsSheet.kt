package com.ukfmail.app.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.BatteryAlert
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.School
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ukfmail.app.AisCheck
import com.ukfmail.app.MailCheck
import com.ukfmail.app.Store
import com.ukfmail.app.mail.Protocol
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(store: Store, onDismiss: () -> Unit, onSignOut: () -> Unit, askNotificationPermission: () -> Unit) {
    val ctx = LocalContext.current
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var enabled by remember { mutableStateOf(store.notificationsEnabled) }
    var aisNotify by remember { mutableStateOf(store.aisNotify) }
    var interval by remember { mutableIntStateOf(store.intervalMinutes) }
    var email by remember { mutableStateOf(store.emailAddress.orEmpty()) }
    var name by remember { mutableStateOf(store.displayName.orEmpty()) }
    var status by remember { mutableStateOf(statusText(store)) }
    val pm = ctx.getSystemService(PowerManager::class.java)
    var batteryOk by remember { mutableStateOf(pm.isIgnoringBatteryOptimizations(ctx.packageName)) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(2_000)
            status = statusText(store)
            batteryOk = pm.isIgnoringBatteryOptimizations(ctx.packageName)
        }
    }

    ModalBottomSheet(
        onDismissRequest = {
            store.emailAddress = email.trim().ifBlank { null }
            store.displayName = name.trim().ifBlank { null }
            onDismiss()
        },
        sheetState = sheet,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp)
                .navigationBarsPadding()
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(28.dp))
                    .background(Brush.linearGradient(listOf(Indigo, Violet, Pink)))
            ) {
                Glow(Modifier.matchParentSize(), strength = 0.6f)
                Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(56.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.25f)).padding(3.dp),
                        contentAlignment = Alignment.Center,
                    ) { Avatar(store.userName.orEmpty(), store.userName.orEmpty(), 50.dp) }
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text(
                            email.ifBlank { store.userName.orEmpty() },
                            color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            "ID ${store.userName.orEmpty()} · " + when (store.protocol) {
                                Protocol.IMAP -> "IMAP"
                                Protocol.POP3 -> "POP3"
                                null -> "nepripojené"
                            },
                            fontSize = 13.sp, color = Color.White.copy(alpha = 0.85f),
                        )
                    }
                }
            }
            Spacer(Modifier.height(24.dp))

            SettingRow(Icons.Outlined.NotificationsActive, "Upozorniť na nový e-mail", "Kontrola doručenej pošty na pozadí") {
                Switch(
                    checked = enabled,
                    onCheckedChange = {
                        enabled = it
                        store.notificationsEnabled = it
                        MailCheck.schedule(ctx)
                        AisCheck.schedule(ctx)
                        if (it) askNotificationPermission()
                    },
                    colors = SwitchDefaults.colors(checkedTrackColor = Indigo),
                )
            }

            if (enabled) {
                Spacer(Modifier.height(18.dp))
                Text("Ako často kontrolovať", fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(15 to "15 min", 30 to "30 min", 60 to "1 hod").forEach { (m, label) ->
                        FilterChip(
                            selected = interval == m,
                            onClick = { interval = m; store.intervalMinutes = m; MailCheck.schedule(ctx) },
                            label = { Text(label) },
                            shape = RoundedCornerShape(12.dp),
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Indigo.copy(alpha = 0.16f), selectedLabelColor = Indigo,
                            ),
                        )
                    }
                }
                Text(
                    "Android nedovolí kontrolovať na pozadí častejšie ako raz za 15 minút.",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
                Spacer(Modifier.height(18.dp))
                SettingRow(Icons.Outlined.School, "Známky a skúšky z AiS2", "Nová známka, nový termín, pripomienka deň pred skúškou") {
                    Switch(
                        checked = aisNotify,
                        onCheckedChange = { aisNotify = it; store.aisNotify = it; AisCheck.schedule(ctx) },
                        colors = SwitchDefaults.colors(checkedTrackColor = Indigo),
                    )
                }
                if (!batteryOk) {
                    Spacer(Modifier.height(16.dp))
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        onClick = { requestBatteryExemption(ctx) },
                    ) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.BatteryAlert, null)
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text("Povoliť beh na pozadí", fontWeight = FontWeight.SemiBold)
                                Text("Bez toho môže telefón notifikácie zdržať. Ťukni a vyber Povoliť.", fontSize = 13.sp)
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(18.dp))
            Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Posledná kontrola", fontWeight = FontWeight.SemiBold)
                        Text(status, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { MailCheck.checkNow(ctx); status = "Kontrolujem…" }) {
                        Icon(Icons.Outlined.Refresh, "Skontrolovať teraz")
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
            Text("Odosielanie", fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = name, onValueChange = { name = it },
                label = { Text("Tvoje meno") },
                placeholder = { Text("Ján Novák") },
                singleLine = true, shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = email, onValueChange = { email = it },
                label = { Text("Tvoja e-mailová adresa") },
                placeholder = { Text("meno.priezvisko@student.ukf.sk") },
                supportingText = { Text("Nájdeš ju v AiS2 alebo vo webmaile.") },
                singleLine = true, shape = RoundedCornerShape(14.dp),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(20.dp))
            OutlinedButton(
                onClick = onSignOut,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(14.dp),
            ) {
                Icon(Icons.AutoMirrored.Outlined.Logout, null)
                Spacer(Modifier.width(8.dp))
                Text("Odhlásiť sa")
            }
        }
    }
}

@Composable
private fun SettingRow(icon: ImageVector, title: String, subtitle: String, trailing: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(Indigo.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, tint = Indigo) }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(subtitle, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        trailing()
    }
}

private fun statusText(store: Store): String {
    store.lastError?.let { return it }
    val t = store.lastCheck
    if (t == 0L) return "Zatiaľ neprebehla"
    val ago = DateUtils.getRelativeTimeSpanString(t, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
    val n = store.unreadCount
    val unread = when (n) {
        0 -> "žiadne neprečítané"
        1 -> "1 neprečítaná"
        in 2..4 -> "$n neprečítané"
        else -> "$n neprečítaných"
    }
    return "$ago · $unread"
}

@SuppressLint("BatteryLife")
private fun requestBatteryExemption(ctx: Context) {
    try {
        ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")))
    } catch (e: Exception) {
        runCatching { ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
    }
}
