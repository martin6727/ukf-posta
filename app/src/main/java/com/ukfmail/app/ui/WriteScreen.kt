package com.ukfmail.app.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ukfmail.app.MailViewModel
import com.ukfmail.app.Screen

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun WriteScreen(vm: MailViewModel, screen: Screen.Write, snackbar: SnackbarHostState) {
    val start = screen.draft
    var draft by remember(screen) { mutableStateOf(start) }
    var from by remember { mutableStateOf(vm.ownAddress) }
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
            Field("Od", from, { from = it }, KeyboardType.Email, placeholder = "meno.priezvisko@student.ukf.sk")
            Field("Komu", draft.to, { draft = draft.copy(to = it) }, KeyboardType.Email,
                trailing = {
                    IconButton(onClick = { showCc = !showCc }) {
                        Icon(if (showCc) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, "Kópia")
                    }
                })
            if (showCc) Field("Kópia", draft.cc, { draft = draft.copy(cc = it) }, KeyboardType.Email)
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
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp).heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 15.sp, modifier = Modifier.width(72.dp))
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
            modifier = Modifier.weight(1f).padding(vertical = 14.dp),
            decorationBox = { inner ->
                if (value.isEmpty() && placeholder != null) {
                    Text(placeholder, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f), fontSize = 16.sp)
                }
                inner()
            },
        )
        if (trailing != null) trailing() else Spacer(Modifier.width(12.dp))
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}
