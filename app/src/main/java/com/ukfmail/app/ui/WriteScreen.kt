package com.ukfmail.app.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.UnfoldMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ukfmail.app.MailViewModel
import com.ukfmail.app.Screen
import com.ukfmail.app.Recipients
import com.ukfmail.app.SentStore
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.ImeAction

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun WriteScreen(vm: MailViewModel, screen: Screen.Write, snackbar: SnackbarHostState) {
    val start = screen.draft
    var draft by remember(screen) { mutableStateOf(start) }
    val senders = vm.senderOptions
    var from by remember { mutableStateOf(senders.firstOrNull().orEmpty()) }
    var showCc by remember { mutableStateOf(start.cc.isNotBlank()) }
    var confirmDiscard by remember { mutableStateOf(false) }
    val changed = draft != start

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        draft = draft.copy(attachments = draft.attachments + uris.map { vm.attachmentFor(it) })
    }

    fun close() {
        if (changed && !vm.sending) confirmDiscard = true else vm.back()
    }
    BackHandler { close() }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(if (start.inReplyTo != null) "Odpoveď" else "Nová správa") },
                navigationIcon = { IconButton(onClick = { close() }) { Icon(Icons.Outlined.Close, "Zavrieť") } },
                actions = {
                    IconButton(onClick = { pick.launch("*/*") }) { Icon(Icons.Outlined.AttachFile, "Priložiť súbor") }
                    GradientButton(
                        onClick = { vm.send(draft, from) { vm.back() } },
                        enabled = !vm.sending,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.padding(end = 10.dp).height(42.dp),
                    ) {
                        if (vm.sending) CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                        else Icon(Icons.AutoMirrored.Filled.Send, null, tint = Color.White, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Odoslať", color = Color.White, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
        containerColor = MaterialTheme.colorScheme.surface,
    ) { pad ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(pad)
                .imePadding()
                .verticalScroll(rememberScrollState())
        ) {
            if (senders.isEmpty()) {
                Field("Od", from, { from = it }, KeyboardType.Email, placeholder = "meno.priezvisko@student.ukf.sk")
            } else {
                FromField(from, senders) { from = it }
            }
            RecipientField("Komu", draft.to, { draft = draft.copy(to = it) }, vm.contacts,
                trailing = {
                    IconButton(onClick = { showCc = !showCc }) {
                        Icon(if (showCc) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, "Kópia")
                    }
                })
            if (showCc) RecipientField("Kópia", draft.cc, { draft = draft.copy(cc = it) }, vm.contacts)
            Field("Predmet", draft.subject, { draft = draft.copy(subject = it) }, KeyboardType.Text, capitalize = true)

            if (draft.attachments.isNotEmpty()) {
                FlowRow(
                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    for (a in draft.attachments) {
                        InputChip(
                            selected = false,
                            onClick = {},
                            label = { Text("${a.name}  ${fileSize(a.size)}", maxLines = 1) },
                            leadingIcon = { Icon(Icons.Outlined.InsertDriveFile, null, Modifier.size(18.dp)) },
                            trailingIcon = {
                                Icon(Icons.Outlined.Close, "Odstrániť", Modifier.size(18.dp).clickable {
                                    draft = draft.copy(attachments = draft.attachments - a)
                                })
                            },
                            shape = RoundedCornerShape(12.dp),
                        )
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }

            BasicTextField(
                value = draft.body,
                onValueChange = { draft = draft.copy(body = it) },
                textStyle = TextStyle(fontSize = 16.sp, lineHeight = 23.sp, color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(Indigo),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth().heightIn(min = 320.dp).padding(16.dp),
                decorationBox = { inner ->
                    if (draft.body.isEmpty()) Text("Napíš správu…", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 16.sp)
                    inner()
                },
            )
        }
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Zahodiť správu?") },
            text = { Text("Rozpísaný text sa neuloží.") },
            confirmButton = { TextButton(onClick = { confirmDiscard = false; vm.back() }) { Text("Zahodiť") } },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Písať ďalej") } },
        )
    }
}

/** "Od": pick one of the addresses from settings. */
@Composable
internal fun FromField(value: String, options: List<String>, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val openSettings = LocalOpenSettings.current
    Box {
        Row(
            Modifier.fillMaxWidth().clickable { open = true }.padding(start = 16.dp, end = 4.dp).heightIn(min = 52.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Od", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 15.sp, maxLines = 1, softWrap = false, modifier = Modifier.widthIn(min = 72.dp).padding(end = 10.dp))
            Text(
                value, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(vertical = 14.dp),
            )
            Icon(
                Icons.Outlined.UnfoldMore, "Vybrať adresu",
                tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(12.dp).size(22.dp),
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, shape = RoundedCornerShape(16.dp)) {
            for (o in options) {
                DropdownMenuItem(
                    text = { Text(o, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    onClick = { onPick(o); open = false },
                    leadingIcon = { Avatar(o, o, 28.dp) },
                    trailingIcon = if (o.equals(value, true)) ({ Icon(Icons.Outlined.Check, null, tint = Indigo) }) else null,
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            DropdownMenuItem(
                text = { Text(if (options.size > 1) "Upraviť adresy" else "Pridať ďalšiu adresu", color = Indigo) },
                onClick = { open = false; openSettings() },
                leadingIcon = { Icon(Icons.Outlined.Add, null, tint = Indigo) },
            )
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

/** Zero-width space kept in front of the typed text, so ⌫ in an empty field can be noticed and remove a bubble. */
private const val ZW = "​"

/**
 * To/Cc like Gmail: finished addresses become bubbles, the rest is typed after them, and
 * people the user has written to before are suggested underneath.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RecipientField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    contacts: List<com.ukfmail.app.Contact>,
    trailing: (@Composable () -> Unit)? = null,
    /** Screenshot tests only: show the suggestions as if the field had focus. */
    startFocused: Boolean = false,
) {
    fun chipsOf(v: String) = Recipients.split(v).filter { Recipients.isComplete(it) }
    fun restOf(v: String) = Recipients.split(v).filterNot { Recipients.isComplete(it) }.joinToString(", ")

    var chips by remember { mutableStateOf(chipsOf(value)) }
    var input by remember { mutableStateOf(restOf(value)) }
    var tfv by remember { mutableStateOf(TextFieldValue(ZW + input, TextRange(ZW.length + input.length))) }
    var focused by remember { mutableStateOf(startFocused) }
    var lastPushed by remember { mutableStateOf(value) }

    fun composed() = (chips + listOfNotNull(input.trim().ifBlank { null })).joinToString(", ")
    fun setInput(s: String) {
        input = s
        tfv = TextFieldValue(ZW + s, TextRange(ZW.length + s.length))
    }
    fun push() {
        lastPushed = composed()
        onChange(lastPushed)
    }

    // The draft was changed from outside, not by typing here: start over from it.
    if (value != lastPushed) {
        lastPushed = value
        chips = chipsOf(value); setInput(restOf(value))
    }

    /** Turns typed text into bubbles where it is a whole address; keeps the rest as typing. */
    fun take(text: String, force: Boolean) {
        val parts = Recipients.split(text)
        val separatorAtEnd = text.trimEnd().let { it.endsWith(",") || it.endsWith(";") } || (force && parts.isNotEmpty())
        val done = if (separatorAtEnd) parts else parts.dropLast(1)
        val rest = if (separatorAtEnd) "" else parts.lastOrNull().orEmpty()
        val good = done.filter { Recipients.isComplete(it) }
        val bad = done.filterNot { Recipients.isComplete(it) }
        chips = chips + good
        setInput((bad + listOfNotNull(rest.ifBlank { null })).joinToString(", "))
        push()
    }

    val suggestions = remember(chips, input, contacts, focused) {
        if (focused) SentStore.suggest(contacts, (chips + input).joinToString(", ")) else emptyList()
    }

    Column {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp).heightIn(min = 52.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 15.sp, maxLines = 1, softWrap = false, modifier = Modifier.widthIn(min = 72.dp).padding(end = 10.dp))
            FlowRow(
                Modifier.weight(1f).padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                for (c in chips) {
                    RecipientChip(c) { chips = chips - c; push() }
                }
                BasicTextField(
                    value = tfv,
                    onValueChange = { nv ->
                        if (!nv.text.startsWith(ZW)) {
                            // ⌫ with nothing typed: take the last bubble back.
                            if (input.isEmpty() && chips.isNotEmpty()) {
                                chips = chips.dropLast(1); setInput(""); push()
                            } else {
                                setInput(nv.text.replace(ZW, "")); push()
                            }
                            return@BasicTextField
                        }
                        val typed = nv.text.removePrefix(ZW).replace(ZW, "")
                        when {
                            typed.any { it == ',' || it == ';' || it == '\n' } -> take(typed.replace('\n', ','), force = false)
                            typed.endsWith(" ") && Recipients.isComplete(typed.trim()) -> take(typed.trim(), force = true)
                            else -> {
                                input = typed
                                tfv = nv.copy(selection = TextRange(maxOf(nv.selection.start, ZW.length), maxOf(nv.selection.end, ZW.length)))
                                push()
                            }
                        }
                    },
                    singleLine = true,
                    textStyle = TextStyle(fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(Indigo),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                    keyboardActions = KeyboardActions(onNext = {
                        if (Recipients.isComplete(input.trim())) take(input, force = true) else defaultKeyboardAction(ImeAction.Next)
                    }),
                    modifier = Modifier
                        .weight(1f)
                        .widthIn(min = 96.dp)
                        .padding(vertical = 6.dp)
                        .onFocusChanged {
                            focused = it.isFocused || startFocused
                            if (!it.isFocused && Recipients.isComplete(input.trim())) take(input, force = true)
                        },
                )
            }
            if (trailing != null) trailing() else Spacer(Modifier.width(12.dp))
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        if (suggestions.isNotEmpty()) {
            Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLow)) {
                for (c in suggestions) {
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable { chips = chips + c.label; setInput(""); push() }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Avatar(c.name ?: c.email, c.email, 34.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            if (!c.name.isNullOrBlank()) {
                                Text(c.name, fontWeight = FontWeight.Medium, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Text(
                                c.email, fontSize = if (c.name.isNullOrBlank()) 15.sp else 13.sp,
                                color = if (c.name.isNullOrBlank()) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}

/** One recipient as a bubble: avatar, name (or address), ✕. */
@Composable
private fun RecipientChip(entry: String, onRemove: () -> Unit) {
    val name = Recipients.display(entry)
    val email = Recipients.email(entry)
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(Indigo.copy(alpha = 0.13f))
            .padding(start = 3.dp, end = 4.dp, top = 3.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(name, email, 24.dp)
        Spacer(Modifier.width(6.dp))
        Text(
            name, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 200.dp),
        )
        Spacer(Modifier.width(2.dp))
        Box(
            Modifier.size(24.dp).clip(CircleShape).clickable(onClick = onRemove),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.Close, "Odstrániť $name", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    type: KeyboardType,
    placeholder: String? = null,
    capitalize: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
) {
    var tfv by remember { mutableStateOf(TextFieldValue(value, TextRange(value.length))) }
    if (tfv.text != value) tfv = tfv.copy(text = value, selection = TextRange(value.length))
    Field(label, tfv, { tfv = it; if (it.text != value) onChange(it.text) }, type, placeholder, capitalize, trailing)
}

@Composable
private fun Field(
    label: String,
    value: TextFieldValue,
    onChange: (TextFieldValue) -> Unit,
    type: KeyboardType,
    placeholder: String? = null,
    capitalize: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
    onFocus: (Boolean) -> Unit = {},
) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp).heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 15.sp, maxLines = 1, softWrap = false, modifier = Modifier.widthIn(min = 72.dp).padding(end = 10.dp))
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = TextStyle(fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(Indigo),
            keyboardOptions = KeyboardOptions(
                keyboardType = type,
                capitalization = if (capitalize) KeyboardCapitalization.Sentences else KeyboardCapitalization.None,
            ),
            modifier = Modifier.weight(1f).padding(vertical = 14.dp).onFocusChanged { onFocus(it.isFocused) },
            decorationBox = { inner ->
                if (value.text.isEmpty() && placeholder != null) {
                    Text(placeholder, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f), fontSize = 16.sp)
                }
                inner()
            },
        )
        if (trailing != null) trailing() else Spacer(Modifier.width(12.dp))
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}
