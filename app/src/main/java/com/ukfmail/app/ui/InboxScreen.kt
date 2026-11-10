package com.ukfmail.app.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Drafts
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.MarkEmailRead
import androidx.compose.material.icons.outlined.MarkEmailUnread
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.Report
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ukfmail.app.MailFilter
import com.ukfmail.app.MailViewModel
import com.ukfmail.app.mail.FolderRole
import com.ukfmail.app.mail.MailFolder
import com.ukfmail.app.mail.MailHeader
import kotlin.math.abs
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InboxScreen(
    vm: MailViewModel,
    snackbar: SnackbarHostState,
    userName: String,
    bottomSpace: Dp = 0.dp,
    onSettings: () -> Unit,
) {
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val list = rememberLazyListState()

    // Load older mail when the end of the list comes into view.
    val nearEnd by remember {
        derivedStateOf {
            val last = list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= list.layoutInfo.totalItemsCount - 5
        }
    }
    LaunchedEffect(nearEnd, vm.messages.size) { if (nearEnd && vm.query.isBlank()) vm.loadMore() }

    ModalNavigationDrawer(
        drawerState = drawer,
        // Closed, the menu opens from its button only, so a sideways swipe moves to the next tab.
        gesturesEnabled = drawer.isOpen,
        drawerContent = {
            FolderDrawer(vm, userName,
                onPick = { vm.selectFolder(it); scope.launch { drawer.close(); list.scrollToItem(0) } },
                onSettings = { scope.launch { drawer.close() }; onSettings() },
            )
        },
    ) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbar, Modifier.navigationBarsPadding().padding(bottom = bottomSpace)) },
            contentWindowInsets = WindowInsets(0),
            containerColor = MaterialTheme.colorScheme.surface,
        ) { _ ->
            val pullState = rememberPullToRefreshState()
            Box(Modifier.fillMaxSize()) {
                PullToRefreshBox(
                    isRefreshing = vm.refreshing,
                    onRefresh = { vm.refresh() },
                    state = pullState,
                    modifier = Modifier.fillMaxSize(),
                    indicator = {
                        PullToRefreshDefaults.Indicator(
                            state = pullState,
                            isRefreshing = vm.refreshing,
                            color = Indigo,
                            modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding(),
                        )
                    },
                ) {
                    InboxList(
                        listState = list,
                        folderName = vm.folder.name,
                        hello = greeting() + if (vm.firstName.isNotBlank()) ", ${vm.firstName}" else "",
                        userName = userName,
                        messages = vm.visibleMessages,
                        unread = vm.messages.count { !it.seen },
                        filter = vm.filter,
                        onFilter = { vm.filter = it },
                        query = vm.query,
                        onQuery = { vm.query = it },
                        loading = vm.loading,
                        error = vm.listError,
                        loadingMore = vm.loadingMore,
                        canMarkRead = true,
                        onMenu = { scope.launch { drawer.open() } },
                        onAvatar = onSettings,
                        onOpen = { vm.open(it) },
                        onStar = { vm.toggleFlag(it) },
                        onToggleRead = { vm.toggleSeen(it) },
                        onDelete = { vm.delete(it) },
                        onRetry = { vm.refresh() },
                        bottomSpace = bottomSpace,
                    )
                }
                ComposeFab(
                    expanded = list.firstVisibleItemIndex < 3,
                    onClick = { vm.compose() },
                    modifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(end = 20.dp, bottom = 20.dp + bottomSpace),
                )
            }
        }
    }
}

/** Everything below the drawer: header, search, filters and the grouped list. Stateless. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun InboxList(
    listState: LazyListState,
    folderName: String,
    hello: String,
    userName: String,
    messages: List<MailHeader>,
    unread: Int,
    filter: MailFilter,
    onFilter: (MailFilter) -> Unit,
    query: String,
    onQuery: (String) -> Unit,
    loading: Boolean,
    error: String?,
    loadingMore: Boolean,
    canMarkRead: Boolean,
    onMenu: () -> Unit,
    onAvatar: () -> Unit,
    onOpen: (MailHeader) -> Unit,
    onStar: (MailHeader) -> Unit,
    onToggleRead: (MailHeader) -> Unit,
    onDelete: (MailHeader) -> Unit,
    onRetry: () -> Unit,
    bottomSpace: Dp = 0.dp,
) {
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val groups = remember(messages) { messages.groupBy { dayGroup(it.date) } }
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val density = LocalDensity.current
    var headerPx by remember { mutableIntStateOf(0) }
    val atTop by remember { derivedStateOf { listState.firstVisibleItemIndex == 0 } }
    val surface = MaterialTheme.colorScheme.surface

    Box(Modifier.fillMaxSize()) {
    // The glow sits behind the list (and the status bar) and moves up with the header as you scroll.
    if (atTop) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(statusTop + with(density) { headerPx.toDp() })
                // Clip after moving, so glow that spills past the bottom never slides into view as a hard edge.
                .graphicsLayer { translationY = -listState.firstVisibleItemScrollOffset.toFloat(); clip = true }
        ) {
            Glow(Modifier.matchParentSize())
            // Fade into the list instead of ending on a hard edge.
            Box(Modifier.matchParentSize().background(Brush.verticalGradient(0.55f to Color.Transparent, 1f to surface)))
        }
    }
    // Starts below the status bar, so day headers stick under it rather than behind it.
    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(bottom = 110.dp + bottom + bottomSpace),
        modifier = Modifier.fillMaxSize().padding(top = statusTop),
    ) {
        item(key = "header") {
            Column(Modifier.onSizeChanged { headerPx = it.height }) {
                InboxHeader(folderName, hello, userName, unread, onMenu, onAvatar)
                SearchField(query, onQuery)
                FilterRow(filter, unread, onFilter)
            }
        }

        when {
            loading && messages.isEmpty() -> item(key = "loading") {
                Box(Modifier.fillMaxWidth().padding(top = 80.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Indigo)
                }
            }
            error != null && messages.isEmpty() -> item(key = "error") {
                EmptyState(Icons.Outlined.WifiOff, "Pošta sa nenačítala", error, "Skúsiť znova", onRetry)
            }
            messages.isEmpty() -> item(key = "empty") {
                when {
                    query.isNotBlank() -> EmptyState(Icons.Outlined.Search, "Nič sa nenašlo", "Hľadá sa v načítaných správach podľa odosielateľa a predmetu.")
                    filter == MailFilter.UNREAD -> EmptyState(Icons.Outlined.DoneAll, "Všetko prečítané", "Žiadne nové správy. Pekná práca.")
                    else -> EmptyState(Icons.Outlined.Inbox, "Žiadne správy", "Tu zatiaľ nič nie je.")
                }
            }
            else -> {
                groups.forEach { (title, mails) ->
                    stickyHeader(key = "g-$title") { SectionHeader(title, mails.size) }
                    items(mails, key = { it.folder + "/" + it.uid }) { m ->
                        SwipeableRow(
                            m, canMarkRead,
                            onClick = { onOpen(m) }, onStar = { onStar(m) },
                            onToggleRead = { onToggleRead(m) }, onDelete = { onDelete(m) },
                            modifier = Modifier.animateItem(),
                        )
                    }
                }
                if (loadingMore) item(key = "more") {
                    Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp, color = Indigo)
                    }
                }
            }
        }
    }
    }
}

@Composable
private fun InboxHeader(folderName: String, hello: String, userName: String, unread: Int, onMenu: () -> Unit, onAvatar: () -> Unit) {
    Box(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                GlassButton(Icons.Outlined.Menu, "Priečinky", onMenu)
                Spacer(Modifier.weight(1f))
                Box(
                    Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .border(2.dp, Brush.linearGradient(listOf(Indigo, Pink)), CircleShape)
                        .padding(3.dp)
                        .clip(CircleShape)
                        .clickable(onClick = onAvatar),
                    contentAlignment = Alignment.Center,
                ) { Avatar(userName, userName, 38.dp) }
            }
            Spacer(Modifier.height(22.dp))
            Text(hello, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Medium)
            Text(
                folderName,
                fontSize = 34.sp,
                lineHeight = 40.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = (-0.8).sp,
            )
            Spacer(Modifier.height(10.dp))
            if (unread > 0) {
                Row(
                    Modifier
                        .clip(RoundedCornerShape(50))
                        .background(Brush.linearGradient(listOf(Indigo, Violet, Pink)))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Dot(Color.White, 7.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(unreadLabel(unread), color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.DoneAll, null, tint = Color(0xFF10B981), modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Všetko prečítané", fontSize = 13.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

fun unreadLabel(n: Int) = when (n) {
    1 -> "1 neprečítaná"
    in 2..4 -> "$n neprečítané"
    else -> "$n neprečítaných"
}

@Composable
private fun SearchField(query: String, onQuery: (String) -> Unit) {
    Row(
        Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .height(52.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(12.dp))
        Box(Modifier.weight(1f)) {
            if (query.isEmpty()) Text("Hľadať v pošte", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 16.sp)
            BasicTextField(
                value = query,
                onValueChange = onQuery,
                singleLine = true,
                textStyle = TextStyle(fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(Indigo),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (query.isNotEmpty()) {
            Icon(Icons.Outlined.Close, "Zrušiť hľadanie", Modifier.size(22.dp).clip(CircleShape).clickable { onQuery("") })
        }
    }
}

@Composable
private fun FilterRow(filter: MailFilter, unread: Int, onFilter: (MailFilter) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(MailFilter.entries) { f ->
            val label = if (f == MailFilter.UNREAD && unread > 0) "${f.label} · $unread" else f.label
            Pill(label, active = f == filter, onClick = { onFilter(f) })
        }
    }
}

@Composable
private fun SectionHeader(title: String, count: Int) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(start = 24.dp, end = 24.dp, top = 14.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        Spacer(Modifier.width(8.dp))
        Text("$count", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(12.dp))
        GradientRule(Modifier.weight(1f))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeableRow(
    m: MailHeader,
    canMarkRead: Boolean,
    onClick: () -> Unit,
    onStar: () -> Unit,
    onToggleRead: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier,
) {
    // Callbacks are read fresh each time; the swipe state outlives recompositions.
    val toggle by rememberUpdatedState(onToggleRead)
    val delete by rememberUpdatedState(onDelete)
    val state = rememberSwipeToDismissBoxState(positionalThreshold = { it * 0.3f })
    LaunchedEffect(state) {
        snapshotFlow { state.currentValue }.collect { v ->
            when (v) {
                SwipeToDismissBoxValue.StartToEnd -> { toggle(); state.reset() } // slide back in, updated
                SwipeToDismissBoxValue.EndToStart -> delete()
                SwipeToDismissBoxValue.Settled -> Unit
            }
        }
    }
    SwipeToDismissBox(
        state = state,
        enableDismissFromStartToEnd = canMarkRead,
        // Swiping left moves to the next tab; mail is deleted from the open message instead.
        enableDismissFromEndToStart = false,
        modifier = modifier,
        backgroundContent = {
            // Drawn only while the row is actually moved, so no colored edge shows around a resting card.
            val offset = runCatching { state.requireOffset() }.getOrDefault(0f)
            if (abs(offset) > 1f) {
                val toRight = offset > 0
                val progress = (abs(offset) / 220f).coerceIn(0f, 1f)
                Row(
                    Modifier
                        .fillMaxSize()
                        .padding(horizontal = 10.dp, vertical = 3.dp)
                        .graphicsLayer { alpha = progress }
                        .clip(RoundedCornerShape(24.dp))
                        .background(
                            if (toRight) Brush.horizontalGradient(listOf(Indigo, Violet))
                            else Brush.horizontalGradient(listOf(Pink, Rose))
                        )
                        .padding(horizontal = 24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = if (toRight) Arrangement.Start else Arrangement.End,
                ) {
                    val icon = when {
                        !toRight -> Icons.Outlined.Delete
                        m.seen -> Icons.Outlined.MarkEmailUnread
                        else -> Icons.Outlined.MarkEmailRead
                    }
                    Icon(icon, null, tint = Color.White, modifier = Modifier.graphicsLayer {
                        val s = 0.7f + 0.3f * progress
                        scaleX = s; scaleY = s
                    })
                    Spacer(Modifier.width(10.dp))
                    Text(
                        when {
                            !toRight -> "Zmazať"
                            m.seen -> "Neprečítané"
                            else -> "Prečítané"
                        },
                        color = Color.White, fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        },
    ) {
        MessageRow(m, onClick, onStar)
    }
}

@Composable
fun MessageRow(m: MailHeader, onClick: () -> Unit, onStar: () -> Unit) {
    val unread = !m.seen
    val dark = isDark()
    val surface = MaterialTheme.colorScheme.surface
    val bg by animateColorAsState(
        if (unread) Indigo.copy(alpha = if (dark) 0.14f else 0.07f).compositeOver(surface) else surface,
        tween(350),
        label = "row",
    )
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier
            .padding(horizontal = 10.dp, vertical = 3.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            Avatar(m.fromName, m.fromEmail.ifBlank { m.fromName }, 48.dp)
            if (unread) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .size(14.dp)
                        .clip(CircleShape)
                        .background(bg)
                        .padding(2.5.dp)
                        .clip(CircleShape)
                        .background(Brush.linearGradient(listOf(Indigo, Pink)))
                )
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    m.fromName,
                    fontWeight = if (unread) FontWeight.Bold else FontWeight.Medium,
                    fontSize = 15.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    shortDate(m.date),
                    fontSize = 12.5.sp,
                    color = if (unread) MaterialTheme.colorScheme.primary else muted,
                    fontWeight = if (unread) FontWeight.Bold else FontWeight.Normal,
                )
            }
            Spacer(Modifier.height(3.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    m.subject,
                    fontSize = 14.5.sp,
                    fontWeight = if (unread) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (unread) MaterialTheme.colorScheme.onSurface else muted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    lineHeight = 19.sp,
                    modifier = Modifier.weight(1f),
                )
                if (m.hasAttachments) {
                    Box(
                        Modifier.padding(start = 8.dp).size(26.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh),
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Outlined.AttachFile, "Príloha", tint = muted, modifier = Modifier.size(15.dp)) }
                }
                if (m.flagged) {
                    Icon(
                        Icons.Filled.Star, "Označené hviezdičkou", tint = Color(0xFFF5B301),
                        modifier = Modifier.padding(start = 6.dp).size(21.dp).clip(CircleShape).clickable(onClick = onStar),
                    )
                }
            }
        }
    }
}

@Composable
internal fun EmptyState(icon: ImageVector, title: String, text: String, action: String? = null, onAction: (() -> Unit)? = null) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(120.dp), contentAlignment = Alignment.Center) {
            Glow(Modifier.matchParentSize(), strength = 1.6f)
            Box(
                Modifier.size(76.dp).clip(RoundedCornerShape(26.dp)).background(BrandGradient),
                contentAlignment = Alignment.Center,
            ) { Icon(icon, null, tint = Color.White, modifier = Modifier.size(36.dp)) }
        }
        Spacer(Modifier.height(16.dp))
        Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp, textAlign = TextAlign.Center)
        if (action != null && onAction != null) {
            Spacer(Modifier.height(18.dp))
            OutlinedButton(onClick = onAction, shape = RoundedCornerShape(14.dp)) { Text(action) }
        }
    }
}

@Composable
private fun FolderDrawer(vm: MailViewModel, userName: String, onPick: (MailFolder) -> Unit, onSettings: () -> Unit) {
    ModalDrawerSheet(drawerContainerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(horizontal = 12.dp)) {
            DrawerBanner(vm.ownAddress.ifBlank { userName })
            for (f in vm.folders) {
                NavigationDrawerItem(
                    label = { Text(f.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium) },
                    icon = { Icon(iconFor(f.role), null) },
                    badge = {
                        if (f.unread > 0) {
                            Box(
                                Modifier.clip(RoundedCornerShape(50)).background(BrandGradient).padding(horizontal = 9.dp, vertical = 2.dp)
                            ) { Text(f.unread.toString(), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp) }
                        }
                    },
                    selected = f.fullName == vm.folder.fullName,
                    onClick = { onPick(f) },
                    shape = RoundedCornerShape(18.dp),
                    colors = NavigationDrawerItemDefaults.colors(
                        selectedContainerColor = Indigo.copy(alpha = 0.14f),
                        selectedIconColor = Indigo,
                        selectedTextColor = Indigo,
                    ),
                )
            }
            if (vm.folders.none { it.role != FolderRole.INBOX }) {
                Text(
                    "Priečinky nie sú dostupné, server ponúka len doručenú poštu (POP3).",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
            GradientRule(Modifier.padding(vertical = 12.dp))
            NavigationDrawerItem(
                label = { Text("Notifikácie a účet", fontWeight = FontWeight.Medium) },
                icon = { Icon(Icons.Outlined.NotificationsActive, null) },
                selected = false,
                onClick = onSettings,
                shape = RoundedCornerShape(18.dp),
            )
        }
    }
}

@Composable
internal fun DrawerBanner(address: String) {
    Box(
        Modifier
            .padding(top = 16.dp, bottom = 16.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(Brush.linearGradient(listOf(Indigo, Violet, Pink)))
    ) {
        Glow(Modifier.matchParentSize(), strength = 0.6f)
        Column(Modifier.padding(20.dp)) {
            Box(
                Modifier.size(46.dp).clip(RoundedCornerShape(15.dp)).background(Color.White.copy(alpha = 0.22f)),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Outlined.Inbox, null, tint = Color.White) }
            Spacer(Modifier.height(14.dp))
            Text("UKF Pošta", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
            Text(address, color = Color.White.copy(alpha = 0.85f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

private fun iconFor(role: FolderRole): ImageVector = when (role) {
    FolderRole.INBOX -> Icons.Outlined.Inbox
    FolderRole.SENT -> Icons.AutoMirrored.Outlined.Send
    FolderRole.DRAFTS -> Icons.Outlined.Drafts
    FolderRole.TRASH -> Icons.Outlined.Delete
    FolderRole.JUNK -> Icons.Outlined.Report
    FolderRole.OTHER -> Icons.AutoMirrored.Outlined.Label
}
