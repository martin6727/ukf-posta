package com.ukfmail.app.mail

import java.util.Properties
import javax.mail.Session
import javax.mail.internet.MimeMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BodyParserTest {
    private val h = MailHeader("1", "INBOX", "A", "a@ukf.sk", "S", 0, false, false, true)

    private fun msg(raw: String) = MimeMessage(Session.getInstance(Properties()), raw.replace("\n", "\r\n").byteInputStream())

    @Test
    fun picksHtmlEmbedsCidAndListsAttachments() {
        val raw = """
            From: Jana <j@ukf.sk>
            To: student@student.ukf.sk
            Subject: =?UTF-8?B?UHLDrWxvaGE=?=
            MIME-Version: 1.0
            Content-Type: multipart/mixed; boundary="X"

            --X
            Content-Type: multipart/related; boundary="R"

            --R
            Content-Type: multipart/alternative; boundary="A"

            --A
            Content-Type: text/plain; charset=UTF-8

            Ahoj čitateľ
            --A
            Content-Type: text/html; charset=UTF-8

            <p>Ahoj <img src="cid:logo1"> <img src="https://tracker.example/x.png"></p>
            --A--
            --R
            Content-Type: image/png
            Content-ID: <logo1>
            Content-Transfer-Encoding: base64

            iVBORw0KGgo=
            --R--
            --X
            Content-Type: application/pdf; name="rozvrh.pdf"
            Content-Disposition: attachment; filename="rozvrh.pdf"
            Content-Transfer-Encoding: base64

            JVBERi0xLjQK
            --X--
        """.trimIndent()
        val b = BodyParser.parse(msg(raw), h)
        assertTrue(b.html.contains("data:image/png;base64,iVBORw0KGgo="))
        assertFalse(b.html.contains("cid:logo1"))
        assertTrue(b.hasRemoteContent)
        assertEquals("Ahoj čitateľ", b.plainText.trim())
        assertEquals(listOf("rozvrh.pdf"), b.attachments.map { it.name })
        assertEquals(listOf("student@student.ukf.sk"), b.to)
    }

    @Test
    fun plainTextIsEscapedLinkedAndQuoted() {
        val html = BodyParser.plainToHtml("Pozri https://ukf.sk/a?b=1 <tag>\n> citácia\nkoniec")
        assertTrue(html.contains("<a href=\"https://ukf.sk/a?b=1\">"))
        assertTrue(html.contains("&lt;tag&gt;"))
        assertTrue(html.contains("<blockquote>citácia<br></blockquote>"))
    }
}
