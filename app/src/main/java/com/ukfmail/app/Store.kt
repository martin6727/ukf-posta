package com.ukfmail.app

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.ukfmail.app.mail.Protocol
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class Credentials(val user: String, val password: String)

/** App settings plus the login, whose password is encrypted with a key kept in the Android Keystore. */
class Store(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("ukfmail", Context.MODE_PRIVATE)

    var credentials: Credentials?
        get() {
            val user = prefs.getString(KEY_USER, null) ?: return null
            val blob = prefs.getString(KEY_PASS, null) ?: return null
            val pass = runCatching { decrypt(blob) }.getOrNull() ?: return null
            return Credentials(user, pass)
        }
        set(value) {
            prefs.edit().apply {
                if (value == null) {
                    remove(KEY_USER); remove(KEY_PASS)
                } else {
                    putString(KEY_USER, value.user); putString(KEY_PASS, encrypt(value.password))
                }
            }.apply()
        }

    val userName: String? get() = prefs.getString(KEY_USER, null)

    /** Which protocol worked at sign-in (IMAP preferred, POP3 fallback). */
    var protocol: Protocol?
        get() = prefs.getString("protocol", null)?.let { runCatching { Protocol.valueOf(it) }.getOrNull() }
        set(v) = prefs.edit().putString("protocol", v?.name).apply()

    /** The user's own address for sending, e.g. meno.priezvisko@student.ukf.sk. */
    var emailAddress: String?
        get() = prefs.getString("email", null)
        set(v) = prefs.edit().putString("email", v).apply()

    var displayName: String?
        get() = prefs.getString("displayName", null)
        set(v) = prefs.edit().putString("displayName", v).apply()

    /** POP3 has no read flag on the server, so read state is kept here. */
    var popSeen: Set<String>
        get() = prefs.getStringSet("popSeen", emptySet()) ?: emptySet()
        set(v) = prefs.edit().putStringSet("popSeen", v).apply()

    var notificationsEnabled: Boolean
        get() = prefs.getBoolean("notify", true)
        set(v) = prefs.edit().putBoolean("notify", v).apply()

    /** How often to check, in minutes (Android allows 15 at the least). */
    var intervalMinutes: Int
        get() = prefs.getInt("interval", 15)
        set(v) = prefs.edit().putInt("interval", v).apply()

    /** Unread messages seen at the last check, so each one is announced once. */
    var knownUnread: Set<String>
        get() = prefs.getStringSet("known", emptySet()) ?: emptySet()
        set(v) = prefs.edit().putStringSet("known", v).apply()

    /** False until the first check: mail that was already unread then is not announced. */
    var baselineDone: Boolean
        get() = prefs.getBoolean("baseline", false)
        set(v) = prefs.edit().putBoolean("baseline", v).apply()

    var lastCheck: Long
        get() = prefs.getLong("lastCheck", 0)
        set(v) = prefs.edit().putLong("lastCheck", v).apply()

    var lastError: String?
        get() = prefs.getString("lastError", null)
        set(v) = prefs.edit().putString("lastError", v).apply()

    var unreadCount: Int
        get() = prefs.getInt("unread", 0)
        set(v) = prefs.edit().putInt("unread", v).apply()

    /** AiS2 normally takes the mail login; these hold a different one if the user typed it. */
    var aisLogin: String?
        get() = prefs.getString("aisLogin", null)
        set(v) = prefs.edit().putString("aisLogin", v).apply()

    var aisPassword: String?
        get() = prefs.getString("aisPass", null)?.let { runCatching { decrypt(it) }.getOrNull() }
        set(v) = prefs.edit().putString("aisPass", v?.let { encrypt(it) }).apply()

    val aisCredentials: Credentials?
        get() {
            val mail = credentials ?: return null
            return Credentials(aisLogin ?: mail.user, aisPassword ?: mail.password)
        }

    /** Notifications for new grades, new exam terms and exam reminders. */
    var aisNotify: Boolean
        get() = prefs.getBoolean("aisNotify", true)
        set(v) = prefs.edit().putBoolean("aisNotify", v).apply()

    var aisSheet: String?
        get() = prefs.getString("aisSheet", null)
        set(v) = prefs.edit().putString("aisSheet", v).apply()

    /** "subjectId:grade" pairs already announced. */
    var knownGrades: Set<String>
        get() = prefs.getStringSet("knownGrades", emptySet()) ?: emptySet()
        set(v) = prefs.edit().putStringSet("knownGrades", v).apply()

    var knownTerms: Set<String>
        get() = prefs.getStringSet("knownTerms", emptySet()) ?: emptySet()
        set(v) = prefs.edit().putStringSet("knownTerms", v).apply()

    var remindedExams: Set<String>
        get() = prefs.getStringSet("remindedExams", emptySet()) ?: emptySet()
        set(v) = prefs.edit().putStringSet("remindedExams", v).apply()

    /** False until the first AiS2 check: what is already there then is not announced. */
    var aisBaseline: Boolean
        get() = prefs.getBoolean("aisBaseline", false)
        set(v) = prefs.edit().putBoolean("aisBaseline", v).apply()

    var aisError: String?
        get() = prefs.getString("aisError", null)
        set(v) = prefs.edit().putString("aisError", v).apply()

    fun signOut() {
        prefs.edit().clear().apply()
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return gen.generateKey()
    }

    private fun encrypt(plain: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val out = c.iv + c.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    private fun decrypt(blob: String): String {
        val raw = Base64.decode(blob, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw, 0, 12))
        return String(c.doFinal(raw, 12, raw.size - 12), Charsets.UTF_8)
    }

    private companion object {
        const val ALIAS = "ukfmail-login"
        const val KEY_USER = "user"
        const val KEY_PASS = "pass"
    }
}
