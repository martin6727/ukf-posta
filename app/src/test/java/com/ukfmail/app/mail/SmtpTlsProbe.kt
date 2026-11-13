package com.ukfmail.app.mail

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.security.Security
import java.util.Properties
import javax.mail.Session
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Live check against studentmail.ukf.sk:465: TLS handshake, certificate and host name check, and
 * the server's greeting. It never logs in (no AUTH is sent). Opt-in: SMTP_PROBE=1.
 */
class SmtpTlsProbe {
    @Test
    fun handshake() {
        assumeTrue(System.getenv("SMTP_PROBE") == "1")
        // LegacyTls sets jdk.tls.disabledAlgorithms itself (needed on Android too), so nothing to do here.
        Security.setProperty("jdk.tls.disabledAlgorithms", "SSLv3, TLSv1, TLSv1.1") // JDK-like start state
        // Android trusts "HARICA TLS RSA Root CA 2021"; the JDK's cacerts doesn't yet, so add it for this test.
        val ks = java.security.KeyStore.getInstance(java.io.File(System.getProperty("java.home"), "lib/security/cacerts"), "changeit".toCharArray())
        javaClass.getResourceAsStream("/certs/harica_tls_rsa_root_2021.pem")!!.use {
            ks.setCertificateEntry("harica-tls-rsa-2021", java.security.cert.CertificateFactory.getInstance("X.509").generateCertificate(it))
        }
        val store = java.io.File.createTempFile("probe-trust", ".p12").apply { deleteOnExit() }
        store.outputStream().use { ks.store(it, "changeit".toCharArray()) }
        System.setProperty("javax.net.ssl.trustStore", store.path)
        System.setProperty("javax.net.ssl.trustStorePassword", "changeit")
        System.setProperty("javax.net.ssl.trustStoreType", "PKCS12")
        val props = Properties().apply {
            put("mail.smtps.ssl.checkserveridentity", "true")
            put("mail.smtps.ssl.socketFactory", LegacyTls.socketFactory)
            put("mail.smtps.auth", "false") // connect and EHLO only
            put("mail.smtps.connectiontimeout", "15000")
            put("mail.smtps.timeout", "30000")
            put("mail.smtps.writetimeout", "30000")
        }
        val log = ByteArrayOutputStream()
        val session = Session.getInstance(props).apply { debug = true; debugOut = PrintStream(log) }
        val t = session.getTransport("smtps")
        val result = try {
            t.connect("studentmail.ukf.sk", 465, null, null)
            "OK: connected (TLS handshake, certificate and EHLO went through)"
        } catch (e: Exception) {
            var c: Throwable? = e
            buildString { while (c != null) { append(c!!.javaClass.name).append(": ").append(c!!.message).append(" | "); c = c!!.cause } }
        } finally {
            runCatching { t.close() }
        }
        println("RESULT: $result")
        println(log.toString().lines().filter { "socket" in it.lowercase() || "ssl" in it.lowercase() || it.startsWith("DEBUG SMTP: trying") || it.startsWith("2") || it.startsWith("5") }.joinToString("\n"))
    }
}
