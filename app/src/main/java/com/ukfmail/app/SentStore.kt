package com.ukfmail.app

import android.content.Context
import java.io.File
import java.text.Normalizer
import javax.mail.internet.InternetAddress
import org.json.JSONArray
import org.json.JSONObject

/** A mail this app sent, kept on the phone. POP3 has no Sent folder on the server. */
data class SentMail(
    val id: String,
    val date: Long,
    val from: String,
    val fromName: String?,
    val to: String,
    val cc: String,
    val subject: String,
    val body: String,
    /** Names and sizes only; the files themselves are not copied. */
    val attachments: List<Pair<String, Long>>,
)

/** Splitting and reading "Ján <jan@x.sk>, eva@y.sk" style recipient lists, for the To/Cc bubbles. */
object Recipients {
    private val EMAIL = Regex("^[^@\\s,;<>\"]+@[^@\\s,;<>\"]+\\.[^@\\s,;<>\"]{2,}$")

    /** Entries split on , or ; that are not inside quotes or <>. */
    fun split(s: String): List<String> {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var quote = false
        var angle = false
        for (ch in s) {
            when {
                ch == '"' -> { quote = !quote; cur.append(ch) }
                ch == '<' && !quote -> { angle = true; cur.append(ch) }
                ch == '>' && !quote -> { angle = false; cur.append(ch) }
                (ch == ',' || ch == ';') && !quote && !angle -> { out += cur.toString(); cur.clear() }
                else -> cur.append(ch)
            }
        }
        out += cur.toString()
        val parts = out.map { it.trim() }.filter { it.isNotEmpty() }
        // Names with a comma that nobody quoted, as mail headers often show them:
        // "Mgr. Jana Kováčová, PhD. <jk@ukf.sk>" is one person, not "Mgr. Jana Kováčová" and "PhD.".
        val merged = mutableListOf<String>()
        for (p in parts) {
            val prev = merged.lastOrNull()
            if (prev != null && '@' !in prev && '<' !in prev && NAMED.matches(p)) merged[merged.lastIndex] = "$prev, $p"
            else merged += p
        }
        return merged
    }

    private val NAMED = Regex("^[^<@\"]+<[^<>@\\s]+@[^<>\\s]+>$")

    /** "Name <email>", with the name quoted when it has characters that would split it. */
    fun format(name: String?, email: String): String {
        val n = name?.trim()?.trim('"')?.replace("\"", "")?.ifBlank { null } ?: return email.trim()
        return if (n.any { it == ',' || it == ';' || it == '<' || it == '>' || it == '@' }) "\"$n\" <${email.trim()}>" else "$n <${email.trim()}>"
    }

    /** One entry made safe to hand to JavaMail (quotes the name if needed). */
    fun normalize(entry: String): String =
        if ('<' in entry) format(display(entry).takeIf { it != email(entry) }, email(entry)) else entry.trim()

    /** A whole To/Cc field ready for InternetAddress.parse. */
    fun normalizeList(field: String): String = split(field).joinToString(", ") { normalize(it) }

    fun email(entry: String): String =
        entry.substringAfterLast('<', "").substringBefore('>').trim().ifEmpty { entry.trim() }

    /** The name if there is one, else the address. */
    fun display(entry: String): String {
        if ('<' !in entry) return entry.trim()
        return entry.substringBeforeLast('<').trim().trim('"').trim().ifEmpty { email(entry) }
    }

    fun isComplete(entry: String): Boolean = EMAIL.matches(email(entry))
}

/** Someone the user has written to, for suggestions in To/Cc. */
data class Contact(val email: String, val name: String?, val count: Int, val last: Long) {
    val label: String get() = Recipients.format(name, email)
}

/**
 * Sent mail and recipients, as JSON files in the app's private storage.
 * Contacts are kept apart from the mails, so deleting a sent mail keeps its people as suggestions.
 */
class SentStore(context: Context) {
    private val dir = context.applicationContext.filesDir
    private val mailsFile = File(dir, "sent.json")
    private val contactsFile = File(dir, "contacts.json")

    @Synchronized
    fun all(): List<SentMail> = readArray(mailsFile).let { a ->
        (0 until a.length()).mapNotNull { runCatching { a.getJSONObject(it).toMail() }.getOrNull() }
    }.sortedByDescending { it.date }

    fun get(id: String): SentMail? = all().firstOrNull { it.id == id }

    @Synchronized
    fun add(m: SentMail) {
        val list = (listOf(m) + all()).take(MAX_MAILS)
        write(mailsFile, JSONArray().apply { list.forEach { put(it.toJson()) } })
        remember(m.to, m.date)
        remember(m.cc, m.date)
    }

    @Synchronized
    fun remove(id: String) {
        val list = all().filter { it.id != id }
        write(mailsFile, JSONArray().apply { list.forEach { put(it.toJson()) } })
    }

    @Synchronized
    fun contacts(): List<Contact> = readArray(contactsFile).let { a ->
        (0 until a.length()).mapNotNull { i ->
            runCatching {
                val o = a.getJSONObject(i)
                Contact(o.getString("email"), o.optString("name").ifBlank { null }, o.optInt("count", 1), o.optLong("last"))
            }.getOrNull()
        }
    }

    /** Adds or bumps everyone in an address list like "Ján <jan@x.sk>, eva@y.sk". */
    @Synchronized
    fun remember(addresses: String, time: Long) {
        if (addresses.isBlank()) return
        val parsed = runCatching { InternetAddress.parse(addresses.replace(';', ','), false).toList() }.getOrDefault(emptyList())
        if (parsed.isEmpty()) return
        val byEmail = contacts().associateBy { it.email.lowercase() }.toMutableMap()
        for (a in parsed) {
            val email = a.address?.trim()?.lowercase() ?: continue
            if ('@' !in email) continue
            val old = byEmail[email]
            byEmail[email] = Contact(
                email = email,
                name = a.personal?.trim()?.ifBlank { null } ?: old?.name,
                count = (old?.count ?: 0) + 1,
                last = maxOf(time, old?.last ?: 0),
            )
        }
        val keep = byEmail.values.sortedByDescending { it.last }.take(MAX_CONTACTS)
        write(contactsFile, JSONArray().apply {
            keep.forEach { put(JSONObject().put("email", it.email).put("name", it.name ?: "").put("count", it.count).put("last", it.last)) }
        })
    }

    @Synchronized
    fun clear() {
        mailsFile.delete()
        contactsFile.delete()
    }

    private fun readArray(f: File): JSONArray =
        if (!f.exists()) JSONArray() else runCatching { JSONArray(f.readText()) }.getOrDefault(JSONArray())

    private fun write(f: File, a: JSONArray) {
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(a.toString())
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }

    private fun SentMail.toJson() = JSONObject()
        .put("id", id).put("date", date).put("from", from).put("fromName", fromName ?: "")
        .put("to", to).put("cc", cc).put("subject", subject).put("body", body)
        .put("att", JSONArray().apply { attachments.forEach { (n, s) -> put(JSONObject().put("n", n).put("s", s)) } })

    private fun JSONObject.toMail(): SentMail {
        val att = optJSONArray("att") ?: JSONArray()
        return SentMail(
            id = getString("id"), date = getLong("date"), from = optString("from"),
            fromName = optString("fromName").ifBlank { null }, to = optString("to"), cc = optString("cc"),
            subject = optString("subject"), body = optString("body"),
            attachments = (0 until att.length()).map { att.getJSONObject(it).let { o -> o.optString("n") to o.optLong("s") } },
        )
    }

    companion object {
        private const val MAX_MAILS = 500
        private const val MAX_CONTACTS = 300

        /** Lowercase without diacritics, so "stefan" finds "Štefan". */
        fun fold(s: String): String =
            Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").lowercase()

        /**
         * Contacts matching what is being typed after the last comma, best first.
         * Addresses already in the field are left out.
         */
        fun suggest(contacts: List<Contact>, field: String, limit: Int = 5): List<Contact> {
            val token = fold(field.substringAfterLast(',').substringAfterLast(';').trim())
            if (token.isEmpty()) return emptyList()
            val present = fold(field.substringBeforeLast(',', ""))
            return contacts
                .filter { c -> fold(c.email).contains(token) || (c.name != null && fold(c.name).contains(token)) }
                .filter { c -> !present.contains(fold(c.email)) }
                .sortedWith(compareByDescending<Contact> { fold(it.email).startsWith(token) || (it.name != null && fold(it.name).split(' ').any { w -> w.startsWith(token) }) }
                    .thenByDescending { it.count }.thenByDescending { it.last })
                .take(limit)
        }

        /** Replaces the half-typed last entry with the picked contact, ready for the next one. */
        fun applySuggestion(field: String, c: Contact): String {
            val cut = maxOf(field.lastIndexOf(','), field.lastIndexOf(';'))
            val head = if (cut >= 0) field.substring(0, cut + 1).trimEnd() + " " else ""
            return head + c.label + ", "
        }
    }
}
