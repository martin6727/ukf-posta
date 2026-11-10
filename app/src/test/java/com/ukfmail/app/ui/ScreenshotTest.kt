package com.ukfmail.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.NightMode
import com.ukfmail.app.MailFilter
import com.ukfmail.app.mail.MailHeader
import org.junit.Rule
import org.junit.Test

/** Renders the main screens with sample mail. Run: ./gradlew recordPaparazziDebug */
class ScreenshotTest {
    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_6, showSystemUi = false)

    private val now = System.currentTimeMillis()
    private val h = 3_600_000L
    private val mails = listOf(
        MailHeader("1", "INBOX", "IT centrum UKF", "it@ukf.sk", "Plánovaná odstávka webmailu v sobotu od 6:00", now - h, false, false, false),
        MailHeader("2", "INBOX", "Erasmus+ UKF", "erasmus@ukf.sk", "Výberové konanie na mobility 2027 je otvorené", now - 3 * h, false, true, true),
        MailHeader("3", "INBOX", "Mgr. Jana Kováčová, PhD.", "jkovacova@ukf.sk", "Zmena miestnosti – Programovanie v Kotline", now - 26 * h, false, false, false),
        MailHeader("4", "INBOX", "AIS2", "ais@ukf.sk", "Hodnotenie predmetu Databázové systémy", now - 50 * h, true, false, false),
        MailHeader("5", "INBOX", "doc. Peter Novák", "pnovak@ukf.sk", "Re: Konzultácia k bakalárskej práci", now - 80 * h, true, true, true),
        MailHeader("6", "INBOX", "Knižnica UKF", "kniznica@ukf.sk", "Upomienka: vráťte výpožičky do piatku", now - 120 * h, true, false, false),
        MailHeader("7", "INBOX", "Študijné oddelenie FPV", "studijne@ukf.sk", "Zápis na zimný semester 2026/2027", now - 400 * h, true, false, true),
    )

    @Composable
    private fun Inbox() {
        Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize()) {
                InboxList(
                    listState = rememberLazyListState(), folderName = "Doručená pošta", hello = "Dobrý deň, Jana",
                    userName = "1234567", messages = mails, unread = 3, filter = MailFilter.ALL, onFilter = {},
                    query = "", onQuery = {}, loading = false, error = null, loadingMore = false, canMarkRead = true,
                    onMenu = {}, onAvatar = {}, onOpen = {}, onStar = {}, onToggleRead = {}, onDelete = {}, onRetry = {},
                    bottomSpace = TabBarSpace,
                )
                ComposeFab(true, {}, Modifier.align(Alignment.BottomEnd).padding(end = 20.dp, bottom = 20.dp + TabBarSpace))
                TabBar(Tab.MAIL, {}, mailBadge = 3, modifier = Modifier.align(Alignment.BottomCenter))
            }
        }
    }

    private fun night() = paparazzi.unsafeUpdateConfig(DeviceConfig.PIXEL_6.copy(nightMode = NightMode.NIGHT))

    @Test fun inboxLight() = paparazzi.snapshot { AppTheme { Inbox() } }
    @Test fun inboxDark() { night(); paparazzi.snapshot { AppTheme { Inbox() } } }
    @Test fun loginLight() = paparazzi.snapshot { AppTheme { LoginScreen(null) { _, _ -> } } }
    @Test fun loginDark() { night(); paparazzi.snapshot { AppTheme { LoginScreen(null) { _, _ -> } } } }
    @Test fun drawerBanner() = paparazzi.snapshot { AppTheme { Surface { DrawerBanner("meno.priezvisko@student.ukf.sk") } } }
}
