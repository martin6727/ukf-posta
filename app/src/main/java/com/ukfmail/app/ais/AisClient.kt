package com.ukfmail.app.ais

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import org.json.JSONObject

/** AiS2 refused the login or password. */
class AisLoginException(message: String) : IOException(message)

/** AiS2 answered with an error; [message] is AiS2's own text when it sent one. */
class AisException(val status: Int, message: String) : IOException(message)

/**
 * Talks to the REST API behind AiS2's own "Študent" web app (ais2.ukf.sk), the way that app does:
 * 1. POST /ais/login.do with the form fields login + password, which sets the JSESSIONID cookie.
 * 2. POST /ais/rest/apps/get-access-token with a fixed header; the token comes back in the AISAuth header.
 * 3. Every REST call sends the cookie, the AISAuth header and lng=SK.
 */
class AisClient(private val login: String, private val password: String) {
    private val cookies = LinkedHashMap<String, String>()
    private var token: String? = null

    private class Resp(val code: Int, val headers: Map<String, List<String>>, val text: String) {
        fun header(name: String) = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull()
    }

    @Synchronized
    fun signIn() {
        cookies.clear()
        token = null
        call("GET", "/ais/start.do")
        val form = "login=${enc(login)}&password=${enc(password)}"
        call("POST", "/ais/login.do", form.toByteArray(), "application/x-www-form-urlencoded")
        val r = call("POST", "/ais/rest/apps/get-access-token", ByteArray(0), extra = mapOf(TOKEN_HEADER to TOKEN_VALUE))
        token = r.header("AISAuth")?.takeIf { it.isNotBlank() }
        if (token == null) throw AisLoginException("AiS2 neprijal prihlasovacie meno alebo heslo.")
    }

    fun get(path: String): String = rest("GET", path, null)

    fun post(path: String, body: String? = null): String = rest("POST", path, body)

    fun delete(path: String): String = rest("DELETE", path, null)

    /** Signs in when needed; a session that died on the server gets one fresh sign-in and a retry. */
    @Synchronized
    private fun rest(method: String, path: String, json: String?, retried: Boolean = false): String {
        if (token == null) signIn()
        val sep = if ('?' in path) '&' else '?'
        val r = call(
            method, "$path${sep}lng=SK",
            body = json?.toByteArray() ?: if (method == "POST") ByteArray(0) else null,
            contentType = if (json != null) "application/json" else null,
            extra = mapOf("AISAuth" to token.orEmpty(), "Accept" to "application/json, text/calendar, */*"),
        )
        r.header("aisAuth")?.takeIf { it.isNotBlank() }?.let { token = it }
        if ((r.code == 401 || r.code == 403) && !retried) {
            token = null
            return rest(method, path, json, retried = true)
        }
        if (r.code == 401) throw AisLoginException("Prihlásenie do AiS2 vypršalo.")
        if (r.code !in 200..299) throw AisException(r.code, serverMessage(r.text) ?: "AiS2 vrátil chybu ${r.code}.")
        return r.text
    }

    private fun call(
        method: String,
        path: String,
        body: ByteArray? = null,
        contentType: String? = null,
        extra: Map<String, String> = emptyMap(),
    ): Resp {
        val c = URL(BASE + path).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.instanceFollowRedirects = false
            c.connectTimeout = 20_000
            c.readTimeout = 40_000
            c.useCaches = false
            c.setRequestProperty("User-Agent", USER_AGENT)
            if (cookies.isNotEmpty()) c.setRequestProperty("Cookie", cookies.entries.joinToString("; ") { "${it.key}=${it.value}" })
            extra.forEach { (k, v) -> c.setRequestProperty(k, v) }
            if (contentType != null) c.setRequestProperty("Content-Type", contentType)
            if (body != null) {
                c.doOutput = true
                c.setFixedLengthStreamingMode(body.size)
                c.outputStream.use { it.write(body) }
            }
            val code = c.responseCode
            val headers = c.headerFields.filterKeys { it != null }
            headers.entries.filter { it.key.equals("Set-Cookie", ignoreCase = true) }.flatMap { it.value }.forEach { line ->
                val pair = line.substringBefore(';')
                val i = pair.indexOf('=')
                if (i > 0) cookies[pair.substring(0, i).trim()] = pair.substring(i + 1).trim()
            }
            val stream = if (code >= 400) c.errorStream else c.inputStream
            val text = stream?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
            return Resp(code, headers, text)
        } catch (e: IOException) {
            throw if (e is AisLoginException || e is AisException) e else IOException("AiS2 je nedostupný (${e.message ?: e.javaClass.simpleName})", e)
        } finally {
            c.disconnect()
        }
    }

    private fun serverMessage(text: String): String? =
        runCatching { JSONObject(text).optString("message").takeIf { it.isNotBlank() && it != "null" } }.getOrNull()

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    companion object {
        const val BASE = "https://ais2.ukf.sk"
        // A constant the AiS2 web app sends when it asks for a token.
        private const val TOKEN_HEADER = "1lWgbIBjRKNgrgH"
        private const val TOKEN_VALUE = "2llVM1Fl3M"
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android) AppleWebKit/537.36 (KHTML, like Gecko) Mobile UKFPosta"
    }
}
