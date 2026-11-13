package com.ukfmail.app.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Mail HTML must not be able to navigate, embed or submit by itself (security review, 2026-11-13). */
class MessageSafetyTest {
    private fun clean(html: String) = MessageHtml.isolate(html).lowercase()

    @Test fun nestedMetaRefreshIsGone() {
        val out = clean("""<html><body><me<meta>ta http-equiv="refresh" content="0;url=https://evil.example/x"><p>Ahoj</p></body></html>""")
        assertFalse(out, out.contains("<meta"))
        assertTrue(out.contains("<p>ahoj</p>"))
    }

    @Test fun deeplyNestedMetaIsGone() {
        val out = clean("<body><me<me<meta>ta>ta http-equiv=refresh content='0;url=x://y'>text</body>")
        assertFalse(out, out.contains("<meta"))
    }

    @Test fun iframesObjectsFormsAndBaseAreGone() {
        val out = clean(
            """<body><iframe src="market://details?id=x" style="display:none"></iframe>
            <iframe srcdoc="&lt;meta http-equiv=refresh content='0;url=x-scheme://a'&gt;"></iframe>
            <object data="x"></object><embed src="y"><base href="https://evil.example/">
            <form action="https://evil.example/"><input name="p"></form><a href="https://ukf.sk">UKF</a></body>"""
        )
        for (tag in listOf("<iframe", "<object", "<embed", "<base", "<form")) assertFalse("$tag left in: $out", out.contains(tag))
        assertTrue(out.contains("""<a href="https://ukf.sk">ukf</a>"""))
    }

    @Test fun ordinaryMarkupStays() {
        val out = clean("""<body><table><tr><td><img src="cid:logo"><b>Rozvrh</b></td></tr></table></body>""")
        assertTrue(out.contains("<table>") && out.contains("<img src=\"cid:logo\">") && out.contains("<b>rozvrh</b>"))
    }

    @Test fun pageHasContentSecurityPolicy() {
        val h = com.ukfmail.app.mail.MailHeader("1", "INBOX", "A", "a@b.sk", "S", 0, true, false, false)
        val b = com.ukfmail.app.mail.MailBody(h, emptyList(), emptyList(), null, null, null, "<p>x</p>", "x", false, emptyList())
        val page = MessageHtml.page(b, dark = false)
        assertTrue(page.contains("Content-Security-Policy") && page.contains("frame-src 'none'"))
    }
}
