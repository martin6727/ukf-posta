package com.ukfmail.app

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ukfmail.app.mail.Attachment
import com.ukfmail.app.mail.BadLoginException
import com.ukfmail.app.mail.FolderRole
import com.ukfmail.app.mail.MailBody
import com.ukfmail.app.mail.MailFolder
import com.ukfmail.app.mail.MailHeader
import com.ukfmail.app.mail.MailService
import com.ukfmail.app.mail.OutgoingAttachment
import com.ukfmail.app.mail.OutgoingMail
import com.ukfmail.app.mail.Protocol
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

sealed interface Screen {
    data object Inbox : Screen
    data class Read(val header: MailHeader) : Screen
    data class Write(val draft: Draft) : Screen
}

data class DraftAttachment(val uri: Uri, val name: String, val size: Long, val mimeType: String)

data class Draft(
    val to: String = "",
    val cc: String = "",
    val subject: String = "",
    val body: String = "",
    val inReplyTo: String? = null,
    val references: String? = null,
    val attachments: List<DraftAttachment> = emptyList(),
)

enum class MailFilter(val label: String) { ALL("Všetky"), UNREAD("Neprečítané"), FLAGGED("S hviezdičkou"), ATTACHMENTS("S prílohou") }

private val INBOX = MailFolder("INBOX", "Doručená pošta", FolderRole.INBOX, -1)

class MailViewModel(app: Application) : AndroidViewModel(app) {
    private val store = Store(app)
    private val lock = Mutex()
    private var service: MailService? = null

    val stack = mutableStateListOf<Screen>(Screen.Inbox)
    val screen: Screen get() = stack.last()

    var folders by mutableStateOf(listOf(INBOX)); private set
    var folder by mutableStateOf(INBOX); private set
    var messages by mutableStateOf<List<MailHeader>>(emptyList()); private set
    var loading by mutableStateOf(false); private set
    var refreshing by mutableStateOf(false); private set
    var loadingMore by mutableStateOf(false); private set
    var canLoadMore by mutableStateOf(false); private set
    var listError by mutableStateOf<String?>(null); private set
    var query by mutableStateOf("")
    var filter by mutableStateOf(MailFilter.ALL)

    var body by mutableStateOf<MailBody?>(null); private set
    var bodyError by mutableStateOf<String?>(null); private set
    var showRemote by mutableStateOf(false)
    var sending by mutableStateOf(false); private set

    /** One-shot message for the snackbar. */
    var toast by mutableStateOf<String?>(null)
    /** Set when the stored password stopped working. */
    var loginRejected by mutableStateOf(false); private set

    private var oldestIndex = 0
    private var openJob: Job? = null

    val protocol: Protocol? get() = store.protocol
    val ownAddress: String get() = store.emailAddress.orEmpty()

    /** First name for the greeting: from the name in settings, else from meno.priezvisko@student.ukf.sk. */
    val firstName: String
        get() = store.displayName?.trim()?.substringBefore(' ')?.takeIf { it.isNotBlank() }
            ?: ownAddress.substringBefore('@').substringBefore('.').takeIf { it.isNotBlank() && it.first().isLetter() }
                ?.replaceFirstChar { it.uppercase() }
            ?: ""

    init {
        refresh(initial = true)
    }

    // --- connection ---

    private suspend fun <T> mail(block: (MailService) -> T): T = withContext(Dispatchers.IO) {
        lock.withLock {
            val s = service ?: run {
                val c = store.credentials ?: throw BadLoginException()
                MailService(c.user, c.password, protocol = store.protocol).also { service = it }
            }
            try {
                store.protocol = s.connect()
                block(s)
            } catch (e: IOException) {
                // Drop the connection so the next call starts fresh.
                s.close(); service = null
                throw e
            }
        }
    }

    private fun fail(e: Throwable): String {
        if (e is BadLoginException) loginRejected = true
        return when (e) {
            is BadLoginException -> "Heslo už nefunguje. Prihlás sa znova."
            else -> e.message ?: "Niečo sa pokazilo"
        }
    }

    // --- list ---

    fun refresh(initial: Boolean = false) {
        if (refreshing || loading) return
        if (initial || messages.isEmpty()) loading = true else refreshing = true
        listError = null
        viewModelScope.launch {
            try {
                val target = folder
                val (list, start) = mail { it.list(target.fullName) }
                messages = applyLocalSeen(list)
                oldestIndex = start
                canLoadMore = start > 1
                if (initial) loadFolders()
                if (target.role == FolderRole.INBOX) Notifier.cancelSummary(getApplication())
            } catch (e: Exception) {
                listError = fail(e)
            } finally {
                loading = false; refreshing = false
            }
        }
    }

    fun loadMore() {
        if (loadingMore || !canLoadMore) return
        loadingMore = true
        viewModelScope.launch {
            try {
                val target = folder
                val (list, start) = mail { it.list(target.fullName, olderThan = oldestIndex) }
                messages = messages + applyLocalSeen(list).filter { n -> messages.none { it.uid == n.uid } }
                oldestIndex = start
                canLoadMore = start > 1
            } catch (e: Exception) {
                toast = fail(e)
            } finally {
                loadingMore = false
            }
        }
    }

    private suspend fun loadFolders() {
        runCatching { mail { it.folders() } }.onSuccess { list ->
            if (list.isNotEmpty()) folders = list
            folders.firstOrNull { it.fullName == folder.fullName }?.let { folder = it }
        }
        if (store.emailAddress.isNullOrBlank()) {
            runCatching { mail { it.guessOwnAddress() } }.getOrNull()?.let { store.emailAddress = it }
        }
    }

    fun selectFolder(f: MailFolder) {
        if (f.fullName == folder.fullName) return
        folder = f
        messages = emptyList()
        canLoadMore = false
        query = ""
        refresh()
    }

    val visibleMessages: List<MailHeader>
        get() {
            val q = query.trim()
            val base = when (filter) {
                MailFilter.ALL -> messages
                MailFilter.UNREAD -> messages.filter { !it.seen }
                MailFilter.FLAGGED -> messages.filter { it.flagged }
                MailFilter.ATTACHMENTS -> messages.filter { it.hasAttachments }
            }
            if (q.isEmpty()) return base
            return base.filter {
                it.subject.contains(q, true) || it.fromName.contains(q, true) || it.fromEmail.contains(q, true)
            }
        }

    private fun applyLocalSeen(list: List<MailHeader>): List<MailHeader> {
        if (store.protocol != Protocol.POP3) return list
        val seen = store.popSeen
        return list.map { if (it.uid in seen) it.copy(seen = true) else it }
    }

    private fun updateHeader(uid: String, change: (MailHeader) -> MailHeader) {
        messages = messages.map { if (it.uid == uid) change(it) else it }
        body?.let { b -> if (b.header.uid == uid) body = b.copy(header = change(b.header)) }
    }

    // --- reading ---

    /** Recently opened messages, so going back and forth is instant. */
    private val bodyCache = object : LinkedHashMap<String, MailBody>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, MailBody>?) = size > 30
    }

    fun open(h: MailHeader) {
        stack.add(Screen.Read(h))
        val key = h.folder + "/" + h.uid
        val cached = bodyCache[key]
        body = cached; bodyError = null; showRemote = false
        openJob?.cancel()
        openJob = viewModelScope.launch {
            try {
                val b = cached ?: mail { it.read(h.folder, h.uid) }.also { bodyCache[key] = it }
                // A cached copy skips the server read, which is what marks IMAP mail as seen.
                if (cached != null && !h.seen && store.protocol == Protocol.IMAP) {
                    runCatching { mail { it.setSeen(h.folder, h.uid, true) } }
                }
                body = b
                updateHeader(h.uid) { it.copy(seen = true) }
                if (store.protocol == Protocol.POP3) store.popSeen = store.popSeen + h.uid
                Notifier.cancel(getApplication(), listOf(h.uid))
                store.knownUnread = store.knownUnread - h.uid
            } catch (e: Exception) {
                bodyError = fail(e)
            }
        }
    }

    fun retryOpen() {
        val s = screen as? Screen.Read ?: return
        stack.removeAt(stack.lastIndex)
        open(s.header)
    }

    /** Opens a message from a notification (only the uid is known). */
    fun openFromNotification(uid: String) {
        val known = messages.firstOrNull { it.uid == uid }
        val h = known ?: MailHeader(uid, "INBOX", "", "", "", 0, true, false, false)
        if (folder.role != FolderRole.INBOX) selectFolder(folders.firstOrNull { it.role == FolderRole.INBOX } ?: INBOX)
        stack.removeAll { it !is Screen.Inbox }
        open(h)
    }

    fun toggleSeen(h: MailHeader) {
        val target = !h.seen
        updateHeader(h.uid) { it.copy(seen = target) }
        viewModelScope.launch {
            if (store.protocol == Protocol.POP3) {
                store.popSeen = if (target) store.popSeen + h.uid else store.popSeen - h.uid
                return@launch
            }
            runCatching { mail { it.setSeen(h.folder, h.uid, target) } }.onFailure {
                updateHeader(h.uid) { it.copy(seen = !target) }; toast = fail(it)
            }
        }
    }

    fun toggleFlag(h: MailHeader) {
        val target = !h.flagged
        updateHeader(h.uid) { it.copy(flagged = target) }
        viewModelScope.launch {
            runCatching { mail { it.setFlagged(h.folder, h.uid, target) } }.onFailure {
                updateHeader(h.uid) { it.copy(flagged = !target) }; toast = fail(it)
            }
        }
    }

    fun delete(h: MailHeader) {
        val before = messages
        messages = messages.filter { it.uid != h.uid }
        if (screen is Screen.Read) back()
        viewModelScope.launch {
            runCatching { mail { it.delete(h.folder, h.uid) } }
                .onSuccess { toast = if (folder.role == FolderRole.TRASH || protocol == Protocol.POP3) "Správa zmazaná" else "Presunuté do koša" }
                .onFailure { messages = before; toast = fail(it) }
        }
    }

    fun saveAttachment(a: Attachment, onReady: (File) -> Unit) {
        val h = body?.header ?: return
        toast = "Sťahujem ${a.name}…"
        viewModelScope.launch {
            try {
                val dir = File(getApplication<Application>().cacheDir, "attachments/${h.uid.hashCode()}")
                val file = mail { it.saveAttachment(h.folder, h.uid, a.index, dir) }
                toast = null
                onReady(file)
            } catch (e: Exception) {
                toast = fail(e)
            }
        }
    }

    // --- writing ---

    fun compose(draft: Draft = Draft()) {
        stack.add(Screen.Write(draft))
    }

    fun reply(all: Boolean) {
        val b = body ?: return
        val h = b.header
        val to = b.replyTo ?: if (h.fromName != h.fromEmail && h.fromName.isNotBlank()) "${h.fromName} <${h.fromEmail}>" else h.fromEmail
        val me = ownAddress.lowercase()
        val cc = if (all) (b.to + b.cc).filter { me.isBlank() || !it.lowercase().contains(me) }.joinToString(", ") else ""
        compose(
            Draft(
                to = to,
                cc = cc,
                subject = prefixed("Re: ", h.subject),
                body = "\n\n" + quote(b),
                inReplyTo = b.messageId,
                references = listOfNotNull(b.references, b.messageId).joinToString(" ").ifBlank { null },
            )
        )
    }

    fun forward() {
        val b = body ?: return
        val h = b.header
        val fmt = SimpleDateFormat("d. M. yyyy H:mm", Locale.forLanguageTag("sk"))
        val text = buildString {
            append("\n\n---------- Preposlaná správa ----------\n")
            append("Od: ${h.fromName} <${h.fromEmail}>\n")
            append("Dátum: ${fmt.format(Date(h.date))}\n")
            append("Predmet: ${h.subject}\n")
            if (b.to.isNotEmpty()) append("Komu: ${b.to.joinToString(", ")}\n")
            append("\n").append(b.plainText)
        }
        compose(Draft(subject = prefixed("Fwd: ", h.subject), body = text))
        if (b.attachments.isNotEmpty()) toast = "Prílohy sa pri preposlaní neprikladajú automaticky"
    }

    private fun prefixed(prefix: String, subject: String) =
        if (subject.startsWith(prefix.trim(), ignoreCase = true)) subject else prefix + subject

    private fun quote(b: MailBody): String {
        val fmt = SimpleDateFormat("d. M. yyyy 'o' H:mm", Locale.forLanguageTag("sk"))
        val who = b.header.fromName.ifBlank { b.header.fromEmail }
        val head = "Dňa ${fmt.format(Date(b.header.date))} $who napísal(a):\n"
        return head + b.plainText.lines().joinToString("\n") { "> $it" }
    }

    fun attachmentFor(uri: Uri): DraftAttachment {
        val cr = getApplication<Application>().contentResolver
        var name = "priloha"
        var size = 0L
        cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                name = c.getString(0) ?: name
                size = if (c.isNull(1)) 0 else c.getLong(1)
            }
        }
        return DraftAttachment(uri, name, size, cr.getType(uri) ?: "application/octet-stream")
    }

    fun send(draft: Draft, from: String, onSent: () -> Unit) {
        if (sending) return
        val creds = store.credentials ?: return
        val fromAddr = from.trim().ifBlank { "${creds.user}@student.ukf.sk" }
        if (draft.to.isBlank()) {
            toast = "Doplň adresáta"; return
        }
        if (draft.attachments.sumOf { it.size } > 14 * 1024 * 1024) {
            toast = "Prílohy sú väčšie ako 15 MB, server ich neprijme"; return
        }
        sending = true
        viewModelScope.launch {
            try {
                val cr = getApplication<Application>().contentResolver
                val files = withContext(Dispatchers.IO) {
                    draft.attachments.map { a ->
                        val bytes = cr.openInputStream(a.uri)?.use { it.readBytes() } ?: throw IOException("Prílohu ${a.name} sa nedá načítať")
                        OutgoingAttachment(a.name, a.mimeType, bytes)
                    }
                }
                mail {
                    it.send(
                        OutgoingMail(
                            from = fromAddr, fromName = store.displayName,
                            to = draft.to, cc = draft.cc, subject = draft.subject, body = draft.body,
                            inReplyTo = draft.inReplyTo, references = draft.references, attachments = files,
                        )
                    )
                }
                store.emailAddress = fromAddr
                toast = "Odoslané"
                onSent()
                if (folder.role == FolderRole.SENT) refresh()
            } catch (e: Exception) {
                toast = fail(e)
            } finally {
                sending = false
            }
        }
    }

    // --- navigation ---

    fun back(): Boolean {
        if (stack.size <= 1) return false
        stack.removeAt(stack.lastIndex)
        if (screen is Screen.Inbox) {
            body = null
            openJob?.cancel()
        }
        return true
    }

    override fun onCleared() {
        val s = service
        service = null
        Thread { runCatching { s?.close() } }.start()
    }
}
