package com.ukfmail.app

import android.os.Build
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ukfmail.app.mail.LegacyTls
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs on a phone or emulator: can this Android version reach the UKF mail server the way the app does?
 * Only the TLS handshake and the server's greeting; no login.
 */
@RunWith(AndroidJUnit4::class)
class TlsOnDeviceTest {
    private val host = "studentmail.ukf.sk"

    @Test fun smtpThroughLegacyTls() {
        val s = LegacyTls.socketFactory.createSocket(host, 465) as SSLSocket
        s.soTimeout = 20_000
        s.use {
            it.startHandshake() // the certificate chain is checked here (ChainCompleting)
            assertTrue("hostname", HttpsURLConnection.getDefaultHostnameVerifier().verify(host, it.session))
            val greeting = it.inputStream.bufferedReader().readLine()
            Log.i("TlsOnDeviceTest", "API ${Build.VERSION.SDK_INT} SMTP ${it.session.protocol} ${it.session.cipherSuite}: $greeting")
            assertTrue(greeting, greeting.startsWith("220"))
            it.outputStream.write("QUIT\r\n".toByteArray())
        }
    }

    @Test fun pop3ThroughSystemTls() {
        val s = SSLSocketFactory.getDefault().createSocket(host, 995) as SSLSocket
        s.soTimeout = 20_000
        s.use {
            it.startHandshake()
            assertTrue("hostname", HttpsURLConnection.getDefaultHostnameVerifier().verify(host, it.session))
            val greeting = it.inputStream.bufferedReader().readLine()
            Log.i("TlsOnDeviceTest", "API ${Build.VERSION.SDK_INT} POP3 ${it.session.protocol}: $greeting")
            assertTrue(greeting, greeting.startsWith("+OK"))
            it.outputStream.write("QUIT\r\n".toByteArray())
        }
    }
}
