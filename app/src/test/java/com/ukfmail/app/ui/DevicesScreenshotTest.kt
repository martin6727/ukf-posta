package com.ukfmail.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.NightMode
import com.ukfmail.app.Contact
import com.ukfmail.app.MailFilter
import com.ukfmail.app.mail.MailHeader
import org.junit.Rule
import org.junit.Test

/**
 * The mail screens on small/old phones, large fonts and a tablet.
 * Run: ./gradlew recordPaparazziDebug --tests '*DevicesScreenshotTest*'
 */
class DevicesScreenshotTest {
    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_6, showSystemUi = false)

    private val devices = listOf(
        "nexus4_small" to DeviceConfig.NEXUS_4,
        "nexus5_font130" to DeviceConfig.NEXUS_5.copy(fontScale = 1.3f),
        "nexus5_font200" to DeviceConfig.NEXUS_5.copy(fontScale = 2.0f),
        "pixel6_dark" to DeviceConfig.PIXEL_6.copy(nightMode = NightMode.NIGHT),
        "tablet_pixelc" to DeviceConfig.PIXEL_C,
    )

    private fun each(content: @Composable () -> Unit) {
        for ((name, config) in devices) {
            paparazzi.unsafeUpdateConfig(config)
            paparazzi.snapshot(name) { AppTheme { Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) { content() } } }
        }
    }

    private val now = System.currentTimeMillis()
    private val h = 3_600_000L
    private val inbox = listOf(
        MailHeader("1", "INBOX", "IT centrum UKF", "it@ukf.sk", "Plánovaná odstávka webmailu v sobotu od 6:00", now - h, false, false, false),
        MailHeader("2", "INBOX", "Erasmus+ UKF", "erasmus@ukf.sk", "Výberové konanie na mobility 2027 je otvorené", now - 3 * h, false, true, true),
        MailHeader("3", "INBOX", "Mgr. Jana Kováčová, PhD.", "jkovacova@ukf.sk", "Zmena miestnosti – Programovanie v Kotline", now - 26 * h, true, false, false),
        MailHeader("4", "INBOX", "doc. Peter Novák", "pnovak@ukf.sk", "Re: Konzultácia k bakalárskej práci", now - 80 * h, true, true, true),
    )
    private val sent = listOf(
        MailHeader("s1", "local:sent", "Pre: Mgr. Jana Kováčová, PhD.", "jkovacova@ukf.sk", "Ospravedlnenie z cvičenia", now - 2 * h, true, false, false),
        MailHeader("s2", "local:sent", "Pre: doc. Peter Novák +2", "pnovak@ukf.sk", "Re: Konzultácia k bakalárskej práci", now - 30 * h, true, false, true),
        MailHeader("s3", "local:sent", "Pre: studijne@ukf.sk", "studijne@ukf.sk", "Potvrdenie o štúdiu", now - 200 * h, true, false, false),
    )
    private val contacts = listOf(
        Contact("eva.mala@ukf.sk", "Eva Malá", 5, now),
        Contact("evelina.kovacova@student.ukf.sk", "Evelína Kováčová", 2, now - h),
        Contact("everest.tim@example.com", null, 1, now - 2 * h),
    )

    @Composable
    private fun MailList(folder: String, list: List<MailHeader>, sent: Boolean = false) = Box(Modifier.fillMaxSize()) {
        InboxList(
            listState = rememberLazyListState(), folderName = folder, hello = "Dobrý deň, Jana",
            userName = "1234567", messages = list, unread = list.count { !it.seen }, filter = MailFilter.ALL, onFilter = {},
            query = "", onQuery = {}, loading = false, error = null, loadingMore = false, canMarkRead = !sent,
            onMenu = {}, onAvatar = {}, onOpen = {}, onStar = {}, onToggleRead = {}, onDelete = {}, onRetry = {},
            bottomSpace = TabBarSpace, sentFolder = sent,
        )
        ComposeFab(true, {}, Modifier.align(Alignment.BottomEnd).padding(end = 20.dp, bottom = 20.dp + TabBarSpace))
        TabBar(Tab.MAIL, {}, mailBadge = 2, modifier = Modifier.align(Alignment.BottomCenter))
    }

    @Test fun inbox() = each { MailList("Doručená pošta", inbox) }

    @Test fun sentFolder() = each { MailList("Odoslané", sent, sent = true) }

    @Test fun writeWithBubblesAndSuggestions() = each {
        Column {
            FromField("jana.kovacova@student.ukf.sk", listOf("jana.kovacova@student.ukf.sk", "1234567@student.ukf.sk")) {}
            RecipientField(
                "Komu", "Mgr. Jana Kováčová, PhD. <jkovacova@ukf.sk>, doc. Peter Novák <pnovak@ukf.sk>, ev",
                {}, contacts, startFocused = true,
            )
            RecipientField("Kópia", "studijne@ukf.sk", {}, contacts)
            Text("Napíš správu…", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
        }
    }

    @Test fun settingsSenders() = each {
        Column(Modifier.padding(24.dp)) {
            SendersSection("jana.kovacova@student.ukf.sk", listOf("1234567@student.ukf.sk", "jana.kovacova@example.com")) {}
        }
    }

    @Test fun login() = each { LoginScreen(null) { _, _ -> } }
}
