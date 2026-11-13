package com.ukfmail.app

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ukfmail.app.ais.AisRepo
import com.ukfmail.app.events.EventScan
import com.ukfmail.app.events.MailEventStore
import com.ukfmail.app.mail.MailService
import com.ukfmail.app.mail.Protocol
import com.ukfmail.app.ui.AppTheme
import com.ukfmail.app.ui.LocalCoolGrades
import com.ukfmail.app.ui.LocalOpenSettings
import com.ukfmail.app.ui.ThemeMode
import com.ukfmail.app.ui.isDark
import androidx.compose.runtime.CompositionLocalProvider
import com.ukfmail.app.ui.ExamsScreen
import com.ukfmail.app.ui.GradesScreen
import com.ukfmail.app.ui.ScheduleScreen
import com.ukfmail.app.ui.Tab
import com.ukfmail.app.ui.TabBar
import com.ukfmail.app.ui.TabBarSpace
import com.ukfmail.app.ui.InboxScreen
import com.ukfmail.app.ui.LoginScreen
import com.ukfmail.app.ui.ReadScreen
import com.ukfmail.app.ui.SettingsSheet
import com.ukfmail.app.ui.WriteScreen
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    companion object {
        const val EXTRA_UID = "uid"
        const val ACTION_SETTINGS = "com.ukfmail.app.SETTINGS"
        const val EXTRA_TAB = "tab"
        const val TAB_EXAMS = "exams"
        const val TAB_GRADES = "grades"
        const val TAB_SCHEDULE = "schedule"
    }

    private lateinit var store: Store
    private var signedIn by mutableStateOf(false)
    private var showSettings by mutableStateOf(false)
    private var themeMode by mutableStateOf(ThemeMode.SYSTEM)
    private var coolGrades by mutableStateOf(false)
    private var loginMessage by mutableStateOf<String?>(null)
    /** Bumped on sign-in so the mail ViewModel starts fresh. */
    private var session by mutableIntStateOf(0)
    private var pendingIntent: Intent? = null
    private var pendingTick by mutableIntStateOf(0)

    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        store = Store(this)
        signedIn = store.credentials != null
        pendingIntent = intent
        if (intent?.action == ACTION_SETTINGS && signedIn) showSettings = true
        if (signedIn) EventScan.enqueue(this)

        themeMode = runCatching { ThemeMode.valueOf(store.themeMode) }.getOrDefault(ThemeMode.SYSTEM)
        coolGrades = store.coolGrades

        setContent {
            AppTheme(themeMode) {
                // Status and navigation bar icons follow the app's light or dark look, not only the phone's.
                val dark = isDark()
                LaunchedEffect(dark) {
                    val style = if (dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                    else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
                    enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                }
                CompositionLocalProvider(LocalCoolGrades provides coolGrades, LocalOpenSettings provides { showSettings = true }) {
                    if (!signedIn) {
                        LoginScreen(loginMessage) { user, pass -> signIn(user, pass) }
                    } else {
                        MailApp(session)
                    }
                    if (showSettings && signedIn) {
                        SettingsSheet(
                            store = store,
                            onDismiss = { showSettings = false },
                            onSignOut = { signOut(null) },
                            askNotificationPermission = { askPermission() },
                            themeMode = themeMode,
                            onThemeMode = { themeMode = it; store.themeMode = it.name },
                            coolGrades = coolGrades,
                            onCoolGrades = { coolGrades = it; store.coolGrades = it },
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == ACTION_SETTINGS && signedIn) showSettings = true
        else pendingIntent = intent
        pendingTick++
    }

    override fun onResume() {
        super.onResume()
        if (signedIn) Notifier.cancelSummary(this)
    }

    @Composable
    private fun MailApp(sessionKey: Int) {
        val vm: MailViewModel = viewModel(key = "mail-$sessionKey")
        val study: StudyViewModel = viewModel(key = "study-$sessionKey")
        var tab by rememberSaveable { mutableStateOf(Tab.MAIL) }
        val snackbar = remember { SnackbarHostState() }

        LaunchedEffect(vm.toast) {
            vm.toast?.let { snackbar.showSnackbar(it); vm.toast = null }
        }
        LaunchedEffect(vm.loginRejected) {
            if (vm.loginRejected) signOut("Heslo už nefunguje. Prihlás sa znova.")
        }
        LaunchedEffect(study.toast) {
            study.toast?.let { snackbar.showSnackbar(it); study.toast = null }
        }
        // Notification taps and mailto: links.
        LaunchedEffect(pendingTick, sessionKey) {
            val i = pendingIntent ?: return@LaunchedEffect
            pendingIntent = null
            i.getStringExtra(EXTRA_TAB)?.let { t ->
                i.removeExtra(EXTRA_TAB)
                while (vm.stack.size > 1) vm.back()
                tab = when (t) { TAB_EXAMS -> Tab.EXAMS; TAB_GRADES -> Tab.GRADES; TAB_SCHEDULE -> Tab.SCHEDULE; else -> Tab.MAIL }
            }
            i.getStringExtra(EXTRA_UID)?.let { tab = Tab.MAIL }
            i.getStringExtra(EXTRA_UID)?.let { uid -> i.removeExtra(EXTRA_UID); vm.openFromNotification(uid) }
            i.data?.takeIf { it.scheme == "mailto" }?.let { uri ->
                i.data = null
                val q = Uri.parse("mailto:x?" + (uri.encodedQuery ?: ""))
                vm.compose(
                    Draft(
                        to = uri.schemeSpecificPart.substringBefore('?'),
                        subject = q.getQueryParameter("subject").orEmpty(),
                        body = q.getQueryParameter("body").orEmpty(),
                    )
                )
            }
        }

        BackHandler(enabled = vm.stack.size > 1 && vm.screen !is Screen.Write) { vm.back() }
        BackHandler(enabled = vm.stack.size == 1 && tab != Tab.MAIL) { tab = Tab.MAIL }

        AnimatedContent(
            targetState = vm.screen,
            transitionSpec = {
                val forward = targetState !is Screen.Inbox
                if (forward) (slideInHorizontally { it / 6 } + fadeIn()) togetherWith fadeOut()
                else fadeIn() togetherWith (slideOutHorizontally { it / 6 } + fadeOut())
            },
            contentKey = { it::class },
            label = "screens",
        ) { s ->
            when (s) {
                Screen.Inbox -> Tabs(tab, { tab = it }, vm, study, snackbar)
                is Screen.Read -> ReadScreen(vm, s, snackbar) { file, type -> openFile(file, type) }
                is Screen.Write -> WriteScreen(vm, s, snackbar)
            }
        }
    }

    /** The four top-level sections with the floating tab bar over them. */
    @Composable
    private fun Tabs(tab: Tab, onTab: (Tab) -> Unit, vm: MailViewModel, study: StudyViewModel, snackbar: SnackbarHostState) {
        // Swipe sideways between sections; the tab bar's blob follows the finger.
        val pager = rememberPagerState(initialPage = tab.ordinal) { Tab.entries.size }
        val updatedOnTab by rememberUpdatedState(onTab)
        LaunchedEffect(tab) { if (pager.targetPage != tab.ordinal) pager.animateScrollToPage(tab.ordinal) }
        // drop(1): the first value is the start page, which must not override a tab opened from a notification.
        LaunchedEffect(pager) { snapshotFlow { pager.settledPage }.drop(1).collect { updatedOnTab(Tab.entries[it]) } }
        Box(Modifier.fillMaxSize()) {
            HorizontalPager(pager, beyondViewportPageCount = Tab.entries.size - 1, key = { it }) { page ->
                when (Tab.entries[page]) {
                    Tab.MAIL -> InboxScreen(vm, snackbar, store.userName.orEmpty(), bottomSpace = TabBarSpace) { showSettings = true }
                    Tab.SCHEDULE -> ScheduleScreen(study) { uid -> vm.openFromNotification(uid) }
                    Tab.EXAMS -> ExamsScreen(study)
                    Tab.GRADES -> GradesScreen(study)
                }
            }
            if (tab != Tab.MAIL) {
                SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = TabBarSpace))
            }
            TabBar(
                position = pager.currentPage + pager.currentPageOffsetFraction,
                onTab = onTab,
                mailBadge = vm.messages.count { !it.seen },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }

    private suspend fun signIn(user: String, password: String) {
        val protocol = withContext(Dispatchers.IO) {
            val m = MailService(user, password)
            try {
                val p = m.connect()
                // POP3 has no read state on the server: mail already there counts as read.
                if (p == Protocol.POP3) store.popSeen = m.unreadInbox().map { it.uid }.toSet()
                runCatching { m.guessOwnAddress() }.getOrNull()?.let { store.emailAddress = it }
                p
            } finally {
                m.close()
            }
        }
        store.credentials = Credentials(user, password)
        store.protocol = protocol
        store.baselineDone = false
        store.knownUnread = emptySet()
        loginMessage = null
        session++
        signedIn = true
        MailCheck.schedule(this)
        MailCheck.checkNow(this)
        AisCheck.schedule(this)
        EventScan.enqueue(this)
        askPermission()
    }

    private fun signOut(message: String?) {
        showSettings = false
        store.signOut()
        MailCheck.schedule(this)
        Notifier.cancelAll(this)
        File(cacheDir, "attachments").deleteRecursively()
        AisRepo(this).clear()
        MailEventStore(this).clear()
        SentStore(this).clear()
        AisCheck.schedule(this)
        viewModelStore.clear()
        loginMessage = message
        signedIn = false
    }

    private fun askPermission() {
        if (Build.VERSION.SDK_INT >= 33) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun openFile(file: File, mimeType: String) {
        val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
        val type = mimeType.ifBlank { contentResolver.getType(uri) ?: "*/*" }
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, type).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivity(Intent.createChooser(view, file.name))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "Žiadna appka nevie otvoriť ${file.name}", Toast.LENGTH_LONG).show()
        }
    }
}
