package com.ukfmail.app.mail

import com.sun.mail.imap.IMAPFolder
import com.sun.mail.pop3.POP3Folder
import java.io.File
import java.io.IOException
import java.util.Date
import java.util.Properties
import javax.mail.Address
import javax.mail.AuthenticationFailedException
import javax.mail.FetchProfile
import javax.mail.Flags
import javax.mail.Folder
import javax.mail.Message
import javax.mail.MessagingException
import javax.mail.Part
import javax.mail.Session
import javax.mail.Store
import javax.mail.UIDFolder
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeBodyPart
import javax.mail.internet.MimeMessage
import javax.mail.internet.MimeMultipart
import javax.mail.internet.MimeUtility
import javax.mail.search.FlagTerm
import javax.mail.util.ByteArrayDataSource

/** Where the UKF student mail lives (from the university's own client setup guide). */
data class ServerConfig(
    val imapHost: String = "studentmail.ukf.sk",
    val imapPort: Int = 993,
    val imapSsl: Boolean = true,
    val popHost: String = "studentmail.ukf.sk",
    val popPort: Int = 995,
    val popSsl: Boolean = true,
    val smtpHost: String = "studentmail.ukf.sk",
    val smtpPort: Int = 465,
    val smtpSsl: Boolean = true,
)

enum class Protocol { IMAP, POP3 }

enum class FolderRole { INBOX, SENT, DRAFTS, TRASH, JUNK, OTHER }

data class MailFolder(val fullName: String, val name: String, val role: FolderRole, val unread: Int)

data class MailHeader(
    val uid: String,
    val folder: String,
    val fromName: String,
    val fromEmail: String,
    val subject: String,
    val date: Long,
    val seen: Boolean,
    val flagged: Boolean,
    val hasAttachments: Boolean,
)

data class Attachment(val index: Int, val name: String, val mimeType: String, val size: Int)

data class MailBody(
    val header: MailHeader,
    val to: List<String>,
    val cc: List<String>,
    val replyTo: String?,
    val messageId: String?,
    val references: String?,
    /** Ready-to-show HTML (plain text mail is converted). Inline images are embedded. */
    val html: String,
    val plainText: String,
    val hasRemoteContent: Boolean,
    val attachments: List<Attachment>,
)

data class OutgoingMail(
    val from: String,
    val fromName: String?,
    val to: String,
    val cc: String,
    val subject: String,
    val body: String,
    val inReplyTo: String? = null,
    val references: String? = null,
    val attachments: List<OutgoingAttachment> = emptyList(),
)

class OutgoingAttachment(val name: String, val mimeType: String, val bytes: ByteArray)

class BadLoginException(cause: Throwable? = null) : IOException("Nesprávne meno alebo heslo", cause)

/**
 * One connection to the mailbox. Prefers IMAP (folders, read state shared with the webmail)
 * and falls back to POP3, which UKF documents for students. Not thread-safe: callers serialize.
 */
class MailService(
    private val user: String,
    private val password: String,
    private val config: ServerConfig = ServerConfig(),
    /** Null = try IMAP, then POP3. */
    private var protocol: Protocol? = null,
) {
    private val session: Session = Session.getInstance(sessionProps())
    private var store: Store? = null

    val activeProtocol: Protocol? get() = protocol

    /** Connects (or reuses the connection). Throws [BadLoginException] for a wrong password. */
    fun connect(): Protocol {
        store?.let { if (it.isConnected) return protocol!! }
        val order = protocol?.let { listOf(it) } ?: listOf(Protocol.IMAP, Protocol.POP3)
        var lastError: Exception? = null
        for (p in order) {
            try {
                val s = when (p) {
                    Protocol.IMAP -> session.getStore(if (config.imapSsl) "imaps" else "imap")
                        .apply { connect(config.imapHost, config.imapPort, user, password) }
                    Protocol.POP3 -> session.getStore(if (config.popSsl) "pop3s" else "pop3")
                        .apply { connect(config.popHost, config.popPort, user, password) }
                }
                store = s
                protocol = p
                return p
            } catch (e: AuthenticationFailedException) {
                throw BadLoginException(e)
            } catch (e: MessagingException) {
                lastError = e
            }
        }
        throw IOException(friendly(lastError), lastError)
    }

    fun close() {
        dropPop()
        runCatching { store?.close() }
        store = null
    }

    /**
     * POP3 opens a new connection (TLS + login + UIDL) every time the inbox is opened, which made
     * each message take seconds. The inbox stays open between calls instead; a refresh reopens it.
     */
    private var popFolder: Folder? = null

    private fun dropPop() {
        runCatching { popFolder?.takeIf { it.isOpen }?.close(false) }
        popFolder = null
    }

    val supportsFolders: Boolean get() = protocol == Protocol.IMAP

    fun folders(): List<MailFolder> = io {
        val s = connected()
        if (protocol == Protocol.POP3) return@io listOf(MailFolder("INBOX", "Doručená pošta", FolderRole.INBOX, -1))
        s.defaultFolder.list("*")
            .filter { it.type and Folder.HOLDS_MESSAGES != 0 }
            .map { f ->
                val role = roleOf(f)
                val unread = runCatching { f.unreadMessageCount }.getOrDefault(-1)
                MailFolder(f.fullName, displayName(f.fullName, f.name, role), role, unread)
            }
            .sortedWith(compareBy({ it.role.ordinal }, { it.name.lowercase() }))
    }

    /** Newest [count] messages before sequence number [olderThan], newest first, plus the oldest sequence loaded. */
    fun list(folderName: String, count: Int = 40, olderThan: Int? = null): Pair<List<MailHeader>, Int> = io {
        if (olderThan == null) dropPop() // first page: see new mail
        val f = open(folderName, Folder.READ_ONLY)
        try {
            val total = f.messageCount
            val end = (olderThan ?: (total + 1)) - 1
            if (end < 1) return@io emptyList<MailHeader>() to 0
            val start = maxOf(1, end - count + 1)
            val msgs = f.getMessages(start, end)
            val fp = FetchProfile().apply {
                add(FetchProfile.Item.ENVELOPE)
                add(FetchProfile.Item.FLAGS)
                if (protocol == Protocol.IMAP) add(FetchProfile.Item.CONTENT_INFO)
                add(UIDFolder.FetchProfileItem.UID)
            }
            f.fetch(msgs, fp)
            msgs.map { header(f, it) }.sortedByDescending { it.date } to start
        } finally {
            f.closeQuietly()
        }
    }

    /** Unread inbox mail, for notifications. With POP3 every message counts and the caller tracks what is new. */
    fun unreadInbox(): List<MailHeader> = io {
        dropPop()
        val f = open("INBOX", Folder.READ_ONLY)
        try {
            val msgs = if (protocol == Protocol.IMAP) f.search(FlagTerm(Flags(Flags.Flag.SEEN), false))
            else f.messages.takeLast(60).toTypedArray()
            f.fetch(msgs, FetchProfile().apply {
                add(FetchProfile.Item.ENVELOPE); add(FetchProfile.Item.FLAGS); add(UIDFolder.FetchProfileItem.UID)
            })
            msgs.map { header(f, it) }.sortedByDescending { it.date }
        } finally {
            f.closeQuietly()
        }
    }

    fun read(folderName: String, uid: String, markSeen: Boolean = true): MailBody = io {
        val f = open(folderName, if (protocol == Protocol.IMAP) Folder.READ_WRITE else Folder.READ_ONLY)
        try {
            val m = find(f, uid) ?: throw IOException("Správa už neexistuje")
            if (markSeen && protocol == Protocol.IMAP && !m.isSet(Flags.Flag.SEEN)) m.setFlag(Flags.Flag.SEEN, true)
            val h = header(f, m).copy(seen = true)
            BodyParser.parse(m, h)
        } finally {
            f.closeQuietly()
        }
    }

    /** Saves attachment number [index] (from [MailBody.attachments]) into [dir]. */
    fun saveAttachment(folderName: String, uid: String, index: Int, dir: File): File = io {
        val f = open(folderName, Folder.READ_ONLY)
        try {
            val m = find(f, uid) ?: throw IOException("Správa už neexistuje")
            val part = BodyParser.attachmentParts(m).getOrNull(index) ?: throw IOException("Príloha sa nenašla")
            dir.mkdirs()
            val name = BodyParser.fileName(part, index).replace(Regex("[\\\\/:*?\"<>|]"), "_")
            val out = File(dir, name)
            part.inputStream.use { input -> out.outputStream().use { input.copyTo(it) } }
            out
        } finally {
            f.closeQuietly()
        }
    }

    fun setSeen(folderName: String, uid: String, seen: Boolean) = setFlag(folderName, uid, Flags.Flag.SEEN, seen)

    fun setFlagged(folderName: String, uid: String, flagged: Boolean) = setFlag(folderName, uid, Flags.Flag.FLAGGED, flagged)

    private fun setFlag(folderName: String, uid: String, flag: Flags.Flag, on: Boolean) = io {
        if (protocol != Protocol.IMAP) return@io
        val f = open(folderName, Folder.READ_WRITE)
        try {
            find(f, uid)?.setFlag(flag, on)
        } finally {
            f.closeQuietly()
        }
    }

    /** Moves to Trash; deletes for good when already in Trash (or with POP3). */
    fun delete(folderName: String, uid: String) = io {
        val f = open(folderName, Folder.READ_WRITE)
        var expunge = true
        try {
            val m = find(f, uid) ?: return@io
            if (protocol == Protocol.IMAP) {
                val trash = findFolder(FolderRole.TRASH)
                if (trash != null && trash.fullName != f.fullName) {
                    f.copyMessages(arrayOf(m), trash)
                }
            }
            m.setFlag(Flags.Flag.DELETED, true)
        } catch (e: Exception) {
            expunge = false
            throw e
        } finally {
            runCatching { if (f.isOpen) f.close(expunge) }
            if (f === popFolder) popFolder = null
        }
    }

    fun send(mail: OutgoingMail) = io {
        val msg = MimeMessage(session)
        msg.setFrom(InternetAddress(mail.from, mail.fromName?.takeIf { it.isNotBlank() }, "UTF-8"))
        msg.setRecipients(Message.RecipientType.TO, InternetAddress.parse(mail.to.normalizeList(), false))
        if (mail.cc.isNotBlank()) msg.setRecipients(Message.RecipientType.CC, InternetAddress.parse(mail.cc.normalizeList(), false))
        msg.setSubject(mail.subject, "UTF-8")
        msg.sentDate = Date()
        mail.inReplyTo?.let { msg.setHeader("In-Reply-To", it) }
        mail.references?.let { msg.setHeader("References", it) }
        msg.setHeader("X-Mailer", "UKF Posta (Android)")
        if (mail.attachments.isEmpty()) {
            msg.setText(mail.body, "UTF-8")
        } else {
            val mp = MimeMultipart()
            mp.addBodyPart(MimeBodyPart().apply { setText(mail.body, "UTF-8") })
            for (a in mail.attachments) {
                mp.addBodyPart(MimeBodyPart().apply {
                    dataHandler = javax.activation.DataHandler(ByteArrayDataSource(a.bytes, a.mimeType))
                    fileName = MimeUtility.encodeText(a.name, "UTF-8", null)
                    disposition = Part.ATTACHMENT
                })
            }
            msg.setContent(mp)
        }
        msg.saveChanges()

        val t = session.getTransport(if (config.smtpSsl) "smtps" else "smtp")
        try {
            t.connect(config.smtpHost, config.smtpPort, user, password)
            t.sendMessage(msg, msg.allRecipients)
        } catch (e: AuthenticationFailedException) {
            throw BadLoginException(e)
        } catch (e: MessagingException) {
            throw IOException("Správu sa nepodarilo odoslať: ${friendly(e)}", e)
        } finally {
            runCatching { t.close() }
        }

        // Keep a copy in Sent, like the webmail does. Best effort.
        if (protocol == Protocol.IMAP) runCatching {
            msg.setFlag(Flags.Flag.SEEN, true)
            findFolder(FolderRole.SENT)?.appendMessages(arrayOf(msg))
        }
    }

    /** Guesses the user's own address from mail addressed to them (UKF uses meno.priezvisko@student.ukf.sk). */
    fun guessOwnAddress(): String? = io {
        val f = open("INBOX", Folder.READ_ONLY)
        try {
            val total = f.messageCount
            if (total == 0) return@io null
            val msgs = f.getMessages(maxOf(1, total - 30), total)
            f.fetch(msgs, FetchProfile().apply { add(FetchProfile.Item.ENVELOPE) })
            msgs.flatMap { m -> (m.getRecipients(Message.RecipientType.TO) ?: emptyArray<Address>()).toList() }
                .mapNotNull { (it as? InternetAddress)?.address?.lowercase() }
                .filter { it.endsWith("@student.ukf.sk") }
                .groupingBy { it }.eachCount()
                .maxByOrNull { it.value }?.key
        } finally {
            f.closeQuietly()
        }
    }

    // --- helpers ---

    private fun connected(): Store {
        connect()
        return store!!
    }

    private fun open(name: String, mode: Int): Folder {
        val s = connected()
        if (protocol == Protocol.POP3) {
            popFolder?.let { if (it.isOpen && (mode == Folder.READ_ONLY || it.mode == Folder.READ_WRITE)) return it }
            dropPop()
            return s.getFolder("INBOX").apply { open(mode) }.also { popFolder = it }
        }
        val f = s.getFolder(name)
        f.open(mode)
        return f
    }

    private fun find(f: Folder, uid: String): Message? = when (f) {
        is UIDFolder -> uid.toLongOrNull()?.let { f.getMessageByUID(it) }
        is POP3Folder -> {
            // One UIDL for the whole inbox instead of one round trip per message.
            val all = f.messages
            f.fetch(all, FetchProfile().apply { add(UIDFolder.FetchProfileItem.UID) })
            all.firstOrNull { f.getUID(it) == uid }
        }
        else -> null
    }

    private fun uidOf(f: Folder, m: Message): String = when (f) {
        is UIDFolder -> f.getUID(m).toString()
        is POP3Folder -> f.getUID(m) ?: m.messageNumber.toString()
        else -> m.messageNumber.toString()
    }

    private fun header(f: Folder, m: Message): MailHeader {
        val from = (m.from?.firstOrNull() as? InternetAddress)
        val name = from?.personal?.takeIf { it.isNotBlank() } ?: from?.address ?: "(neznámy odosielateľ)"
        val hasAtt = runCatching {
            val ct = m.contentType?.lowercase().orEmpty()
            ct.startsWith("multipart/mixed") || ct.startsWith("application/")
        }.getOrDefault(false)
        return MailHeader(
            uid = uidOf(f, m),
            folder = f.fullName,
            fromName = name,
            fromEmail = from?.address.orEmpty(),
            subject = m.subject?.takeIf { it.isNotBlank() } ?: "(bez predmetu)",
            date = (m.sentDate ?: m.receivedDate)?.time ?: 0L,
            seen = m.isSet(Flags.Flag.SEEN),
            flagged = m.isSet(Flags.Flag.FLAGGED),
            hasAttachments = hasAtt,
        )
    }

    private fun findFolder(role: FolderRole): Folder? {
        val s = connected()
        return s.defaultFolder.list("*").firstOrNull { roleOf(it) == role }
    }

    private fun roleOf(f: Folder): FolderRole {
        val attrs = (f as? IMAPFolder)?.let { runCatching { it.attributes.map(String::lowercase) }.getOrNull() }.orEmpty()
        val n = f.fullName.substringAfterLast('.').substringAfterLast('/').lowercase()
        return when {
            f.fullName.equals("INBOX", ignoreCase = true) -> FolderRole.INBOX
            "\\sent" in attrs || n in setOf("sent", "sent items", "sent messages", "odoslané", "odoslane") -> FolderRole.SENT
            "\\drafts" in attrs || n in setOf("drafts", "draft", "koncepty") -> FolderRole.DRAFTS
            "\\trash" in attrs || n in setOf("trash", "deleted items", "deleted messages", "kôš", "kos") -> FolderRole.TRASH
            "\\junk" in attrs || n in setOf("junk", "spam", "nevyžiadaná pošta") -> FolderRole.JUNK
            else -> FolderRole.OTHER
        }
    }

    private fun displayName(full: String, name: String, role: FolderRole) = when (role) {
        FolderRole.INBOX -> "Doručená pošta"
        FolderRole.SENT -> "Odoslané"
        FolderRole.DRAFTS -> "Koncepty"
        FolderRole.TRASH -> "Kôš"
        FolderRole.JUNK -> "Spam"
        FolderRole.OTHER -> name.ifBlank { full }
    }

    private fun Folder.closeQuietly() {
        if (this === popFolder) return // kept open on purpose
        runCatching { if (isOpen) close(false) }
    }

    private fun String.normalizeList() = com.ukfmail.app.Recipients.normalizeList(this)

    /**
     * Runs a mail operation. If the server dropped the connection (an idle POP3 inbox times out),
     * it reconnects and tries once more.
     */
    private inline fun <T> io(block: () -> T): T = try {
        block()
    } catch (e: BadLoginException) {
        throw e
    } catch (e: AuthenticationFailedException) {
        throw BadLoginException(e)
    } catch (e: MessagingException) {
        close()
        try {
            block()
        } catch (e2: AuthenticationFailedException) {
            throw BadLoginException(e2)
        } catch (e2: MessagingException) {
            throw IOException(friendly(e2), e2)
        }
    }

    private fun sessionProps() = Properties().apply {
        for (p in listOf("imap", "imaps", "pop3", "pop3s", "smtp", "smtps")) {
            put("mail.$p.connectiontimeout", "15000")
            put("mail.$p.timeout", "30000")
            put("mail.$p.writetimeout", "30000")
        }
        for (p in listOf("imaps", "pop3s", "smtps")) put("mail.$p.ssl.checkserveridentity", "true")
        // studentmail.ukf.sk:465 only speaks TLS 1.0 (checked 2026-11-13; POP3 995 has TLS 1.2),
        // which current Android TLS can't do any more. SMTP alone goes through BouncyCastle; see LegacyTls.
        put("mail.smtps.ssl.socketFactory", LegacyTls.socketFactory)
        // Without this JavaMail quietly retries with the system TLS when ours fails, hiding the real error.
        put("mail.smtps.socketFactory.fallback", "false")
        put("mail.smtps.auth", "true")
        put("mail.smtp.auth", "true")
        put("mail.imaps.partialfetch", "false")
        put("mail.mime.decodefilename", "true")
        put("mail.mime.address.strict", "false")
    }

    companion object {
        fun friendly(e: Throwable?): String {
            var c: Throwable? = e
            while (c != null) {
                when (c) {
                    is java.net.UnknownHostException -> return "Bez internetu alebo server nie je dostupný"
                    is java.net.SocketTimeoutException -> return "Server neodpovedá"
                    is java.net.ConnectException -> return "Nedá sa pripojiť na server"
                    is javax.net.ssl.SSLException -> return "Problém so zabezpečeným spojením (${c.message})"
                    is java.security.cert.CertificateException -> return "Certifikát servera nie je dôveryhodný"
                }
                c = c.cause
            }
            return e?.message ?: "Neznáma chyba"
        }
    }
}
