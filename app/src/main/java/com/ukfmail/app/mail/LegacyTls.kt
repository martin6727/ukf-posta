package com.ukfmail.app.mail

import java.net.InetAddress
import java.net.Socket
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.jsse.provider.BouncyCastleJsseProvider

/**
 * TLS for SMTP only. studentmail.ukf.sk:465 (checked 2026-11-13):
 *  - offers nothing newer than TLS 1.0 with TLS_RSA_WITH_AES_256_CBC_SHA, and current Android
 *    has no TLS 1.0 left at all, so the system TLS fails the handshake. BouncyCastle's JSSE
 *    still speaks it;
 *  - sends server cert → GEANT TLS RSA 1 → self-signed "HARICA TLS RSA Root CA 2021". Android
 *    only trusts that root from about version 14; older phones have the HARICA RootCA 2015 instead.
 *    POP3 on 995 also sends the 2021 root cross-signed by the 2015 one; SMTP doesn't, so it is
 *    bundled (res: certs/, with GEANT in case a chain arrives without it).
 *
 * The certificate is still checked against the platform's own root store. Bundled certificates are
 * only appended where the chain stops, each after its signature on the previous one checks out.
 * Tested on an Android 8.0 emulator (TlsOnDeviceTest).
 * JavaMail checks the host name (mail.smtps.ssl.checkserveridentity).
 * Newer protocol versions are listed first, so they win if the server ever gets them.
 */
object LegacyTls {
    private val PROTOCOLS = arrayOf("TLSv1.3", "TLSv1.2", "TLSv1.1", "TLSv1")
    // _DHE_: the server's finite-field DH group is too small (BC rejects it with insufficient_security),
    // and it has no ECDHE, so plain RSA key exchange (TLS_RSA_WITH_AES_*_CBC_SHA) is what's left.
    private val WEAK = Regex("NULL|anon|EXPORT|RC4|_DES_|3DES|MD5|_DHE_", RegexOption.IGNORE_CASE)

    /**
     * BouncyCastle reads this like the JDK does. Android has no such property, so BC falls back
     * to its own defaults, which drop TLS 1.0, and the server's hello is rejected with
     * protocol_version. Same list as the JDK's, minus TLSv1/TLSv1.1. Conscrypt doesn't read it.
     */
    private const val DISABLED =
        "SSLv3, DTLSv1.0, RC4, DES, MD5withRSA, DH keySize < 1024, EC keySize < 224, 3DES_EDE_CBC, anon, NULL, ECDH"

    val socketFactory: SSLSocketFactory by lazy {
        java.security.Security.setProperty("jdk.tls.disabledAlgorithms", DISABLED)
        val jsse = BouncyCastleJsseProvider(BouncyCastleProvider())
        val ctx = SSLContext.getInstance("TLS", jsse)
        ctx.init(null, arrayOf(ChainCompleting(platformTrustManager(), bundledIntermediates())), SecureRandom())
        Factory(ctx.socketFactory)
    }

    private fun platformTrustManager(): X509TrustManager {
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(null as KeyStore?)
        return tmf.trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    /**
     * GEANT TLS RSA 1 (signed by HARICA TLS RSA Root CA 2021), and that root cross-signed by
     * "Hellenic Academic and Research Institutions RootCA 2015" (valid to 2029-08-31). Older Android,
     * 8 to about 13, has only the 2015 root, so it needs the cross-signed one to get there.
     */
    private val BUNDLED = listOf("/certs/geant_tls_rsa_1.pem", "/certs/harica_tls_rsa_2021_by_2015.pem")

    internal fun bundledIntermediates(): List<X509Certificate> = BUNDLED.flatMap { path ->
        LegacyTls::class.java.getResourceAsStream(path)?.use {
            CertificateFactory.getInstance("X.509").generateCertificates(it).filterIsInstance<X509Certificate>()
        }.orEmpty()
    }

    /**
     * Platform trust check. If the server left out intermediates, append the bundled ones one by one,
     * each only after its signature on the previous certificate checks out, and ask the platform again.
     */
    internal class ChainCompleting(
        private val platform: X509TrustManager,
        private val intermediates: List<X509Certificate>,
    ) : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
            platform.checkClientTrusted(chain, authType)

        /**
         * BouncyCastle hands over its own certificate objects. Older Android (8 at least) can't check
         * their signatures against its own root keys and reports "trust anchor not found", so re-read
         * them with the platform's certificate factory first.
         */
        private fun platformCopy(chain: Array<X509Certificate>): Array<X509Certificate> {
            val cf = CertificateFactory.getInstance("X.509")
            return chain.map { cf.generateCertificate(java.io.ByteArrayInputStream(it.encoded)) as X509Certificate }.toTypedArray()
        }

        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
            val sent = platformCopy(chain)
            val first = try {
                platform.checkServerTrusted(sent, authType); return
            } catch (e: CertificateException) { e }
            // The server ends its chain with the self-signed HARICA 2021 root. A phone that doesn't trust
            // that root can't use it, and links added after it confuse path building, so leave it out.
            var current = sent.dropLastWhile { it.subjectX500Principal == it.issuerX500Principal }
                .toTypedArray().ifEmpty { sent }
            repeat(intermediates.size) {
                val last = current.last()
                val issuer = intermediates.firstOrNull { c ->
                    c.subjectX500Principal == last.issuerX500Principal && current.none { it == c } &&
                        runCatching { last.verify(c.publicKey) }.isSuccess
                } ?: throw first
                current = current + issuer
                try {
                    platform.checkServerTrusted(current, authType); return
                } catch (_: CertificateException) {
                    // not there yet, try the next link
                }
            }
            throw first
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = platform.acceptedIssuers
    }

    private class Factory(private val base: SSLSocketFactory) : SSLSocketFactory() {
        private fun prep(s: Socket): Socket = s.apply {
            if (this is SSLSocket) {
                enabledProtocols = PROTOCOLS.filter { it in supportedProtocols }.toTypedArray()
                enabledCipherSuites = supportedCipherSuites.filterNot { WEAK.containsMatchIn(it) }.toTypedArray()
            }
        }

        override fun getDefaultCipherSuites(): Array<String> = base.defaultCipherSuites
        override fun getSupportedCipherSuites(): Array<String> = base.supportedCipherSuites
        override fun createSocket(): Socket = prep(base.createSocket())
        override fun createSocket(s: Socket, host: String, port: Int, autoClose: Boolean): Socket =
            prep(base.createSocket(s, host, port, autoClose))
        override fun createSocket(host: String, port: Int): Socket = prep(base.createSocket(host, port))
        override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
            prep(base.createSocket(host, port, localHost, localPort))
        override fun createSocket(host: InetAddress, port: Int): Socket = prep(base.createSocket(host, port))
        override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket =
            prep(base.createSocket(address, port, localAddress, localPort))
    }
}
