package com.ukfmail.app.mail

import java.util.Base64
import javax.mail.Message
import javax.mail.Multipart
import javax.mail.Part
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeUtility

/** Turns a MIME message into HTML the app can show, plus the list of attachments. */
object BodyParser {
    private const val MAX_INLINE_IMAGE = 1_500_000

    private class Collected {
        var html: String? = null
        var plain: String? = null
        val inline = mutableMapOf<String, Part>()
        val attachments = mutableListOf<Part>()
    }

    fun parse(m: Message, header: MailHeader): MailBody {
        val c = Collected()
        walk(m, c)

        var html = c.html
        if (html != null) {
            // Embed images the mail references by cid: so they show without network access.
            for ((cid, part) in c.inline) {
                if (part.size > MAX_INLINE_IMAGE) continue
                val bytes = runCatching { part.inputStream.use { it.readBytes() } }.getOrNull() ?: continue
                val type = part.contentType.substringBefore(';').trim().lowercase()
                val data = "data:$type;base64," + Base64.getEncoder().encodeToString(bytes)
                html = html!!.replace("cid:$cid", data, ignoreCase = true)
            }
        }
        val plain = c.plain ?: html?.let { htmlToText(it) } ?: ""
        val shown = html ?: plainToHtml(plain)
        val remote = html != null && Regex("""(src|background)\s*=\s*["']?\s*https?:""", RegexOption.IGNORE_CASE).containsMatchIn(html)

        val atts = c.attachments.mapIndexed { i, p ->
            Attachment(i, fileName(p, i), p.contentType.substringBefore(';').trim().lowercase(), p.size)
        }
        return MailBody(
            header = header,
            to = addresses(m, Message.RecipientType.TO),
            cc = addresses(m, Message.RecipientType.CC),
            replyTo = (m.replyTo?.firstOrNull() as? InternetAddress)?.toUnicodeString(),
            messageId = m.getHeader("Message-ID")?.firstOrNull(),
            references = m.getHeader("References")?.firstOrNull(),
            html = shown,
            plainText = plain,
            hasRemoteContent = remote,
            attachments = atts,
        )
    }

    /** Attachments in the same order as [MailBody.attachments]. */
    fun attachmentParts(m: Message): List<Part> = Collected().also { walk(m, it) }.attachments

    fun fileName(p: Part, index: Int): String {
        val raw = runCatching { p.fileName }.getOrNull()
        val decoded = raw?.let { runCatching { MimeUtility.decodeText(it) }.getOrDefault(it) }
        if (!decoded.isNullOrBlank()) return decoded
        val type = runCatching { p.contentType.substringBefore(';').trim().lowercase() }.getOrDefault("")
        return when (type) {
            "message/rfc822" -> "sprava-${index + 1}.eml"
            "application/pdf" -> "priloha-${index + 1}.pdf"
            "image/jpeg" -> "obrazok-${index + 1}.jpg"
            "image/png" -> "obrazok-${index + 1}.png"
            else -> "priloha-${index + 1}"
        }
    }

    private fun walk(p: Part, c: Collected) {
        val disposition = runCatching { p.disposition?.lowercase() }.getOrNull()
        val hasName = runCatching { !p.fileName.isNullOrBlank() }.getOrDefault(false)
        when {
            p.isMimeType("multipart/alternative") -> {
                val mp = p.content as Multipart
                val parts = (0 until mp.count).map { mp.getBodyPart(it) }
                val html = parts.lastOrNull { it.isMimeType("text/html") }
                val plain = parts.firstOrNull { it.isMimeType("text/plain") }
                val nested = parts.filter { it.isMimeType("multipart/*") }
                if (c.plain == null && plain != null) c.plain = text(plain)
                if (c.html == null && html != null) c.html = text(html)
                nested.forEach { walk(it, c) }
            }
            p.isMimeType("multipart/*") -> {
                val mp = p.content as Multipart
                for (i in 0 until mp.count) walk(mp.getBodyPart(i), c)
            }
            p.isMimeType("message/rfc822") && p !is Message -> c.attachments += p
            disposition == Part.ATTACHMENT || (hasName && !p.isMimeType("text/*") && contentId(p) == null) ->
                c.attachments += p
            p.isMimeType("text/html") && c.html == null -> c.html = text(p)
            p.isMimeType("text/plain") && c.plain == null -> c.plain = text(p)
            p.isMimeType("image/*") && contentId(p) != null -> {
                c.inline[contentId(p)!!] = p
                if (hasName && disposition != Part.INLINE) c.attachments += p
            }
            hasName -> c.attachments += p
        }
    }

    private fun contentId(p: Part): String? =
        p.getHeader("Content-ID")?.firstOrNull()?.trim()?.removePrefix("<")?.removeSuffix(">")

    private fun text(p: Part): String = runCatching {
        when (val content = p.content) {
            is String -> content
            else -> p.inputStream.use { it.readBytes().decodeToString() }
        }
    }.getOrDefault("")

    private fun addresses(m: Message, type: Message.RecipientType): List<String> =
        (runCatching { m.getRecipients(type) }.getOrNull() ?: emptyArray())
            .map { (it as? InternetAddress)?.toUnicodeString() ?: it.toString() }

    fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private val URL = Regex("""(https?://[^\s<>"']+[^\s<>"'.,;:!?)\]])""")
    private val EMAIL = Regex("""([\w.+-]+@[\w-]+\.[\w.-]+)""")

    fun plainToHtml(text: String): String {
        val sb = StringBuilder("<div class=\"ukf-plain\">")
        var inQuote = false
        for (line in text.replace("\r\n", "\n").split('\n')) {
            val quoted = line.startsWith(">")
            if (quoted && !inQuote) sb.append("<blockquote>")
            if (!quoted && inQuote) sb.append("</blockquote>")
            inQuote = quoted
            var l = escape(if (quoted) line.trimStart('>', ' ') else line)
            l = URL.replace(l) { "<a href=\"${it.value}\">${it.value}</a>" }
            if (!l.contains("<a ")) l = EMAIL.replace(l) { "<a href=\"mailto:${it.value}\">${it.value}</a>" }
            sb.append(l).append("<br>")
        }
        if (inQuote) sb.append("</blockquote>")
        return sb.append("</div>").toString()
    }

    private val NAMED = mapOf(
        "nbsp" to " ", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "amp" to "&",
        "ndash" to "–", "mdash" to "—", "hellip" to "…", "bdquo" to "„", "ldquo" to "“", "rdquo" to "”",
        "lsquo" to "‘", "rsquo" to "’", "sbquo" to "‚", "euro" to "€", "copy" to "©", "deg" to "°",
    )
    // Accented letters: &aacute; &scaron; &ocirc; &auml; … (UKF notices are written this way).
    private val MARKS = mapOf("acute" to '́', "caron" to '̌', "circ" to '̂', "uml" to '̈', "grave" to '̀', "ring" to '̊', "tilde" to '̃', "cedil" to '̧')

    /** Turns &aacute;, &#269;, &#x10D; and the common named entities into characters. */
    fun decodeEntities(s: String): String = Regex("&(#x[0-9a-fA-F]+|#\\d+|[a-zA-Z]+);").replace(s) { m ->
        val e = m.groupValues[1]
        when {
            e.startsWith("#x") -> e.drop(2).toIntOrNull(16)?.let { String(Character.toChars(it)) }
            e.startsWith("#") -> e.drop(1).toIntOrNull()?.let { String(Character.toChars(it)) }
            else -> NAMED[e] ?: MARKS.entries.firstOrNull { e.length > it.key.length && e.endsWith(it.key) }?.let { (k, mark) ->
                java.text.Normalizer.normalize(e.dropLast(k.length) + mark, java.text.Normalizer.Form.NFC).takeIf { it.length == 1 }
            }
        } ?: m.value
    }

    fun htmlToText(html: String): String = html
        .replace(Regex("(?is)<(script|style|head)[^>]*>.*?</\\1>"), "")
        .replace(Regex("(?i)<br\\s*/?>"), "\n")
        .replace(Regex("(?i)</(p|div|tr|li|h[1-6])>"), "\n")
        .replace(Regex("<[^>]+>"), "")
        .let(::decodeEntities)
        .replace(Regex("[ \\t]+"), " ")
        .replace(Regex("\n\\s*\n\\s*\n+"), "\n\n")
        .trim()
}
