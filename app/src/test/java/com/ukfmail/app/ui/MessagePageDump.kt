package com.ukfmail.app.ui

import com.ukfmail.app.mail.MailBody
import com.ukfmail.app.mail.MailHeader
import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Writes sample message pages to MESSAGE_PAGE_DIR so they can be looked at in a browser. */
class MessagePageDump {
    @Test
    fun dump() {
        val dir = System.getenv("MESSAGE_PAGE_DIR")
        assumeTrue(dir != null)
        val h = MailHeader("1", "INBOX", "ChatGPT", "noreply@tm.openai.com", "Tvoj dočasný prihlasovací kód ChatGPT", 1793452000000, true, false, false)
        val html = """<!DOCTYPE html><html><head><style>body{margin:0;padding:0!important;width:100%!important;min-width:640px;background:#ffffff} h1{font-size:40px;color:#000} a{color:#10a37f}</style></head>
            <body><table width="640" cellpadding="0" cellspacing="0" style="background:#fff"><tr><td style="padding:40px">
            <img src="https://cdn.openai.com/logo.png" alt="ChatGPT" width="120">
            <p style="font-size:16px;color:#202123">Ak chceš pokračovať, zadaj tento dočasný overovací kód:</p>
            <div style="background:#f2f2f2;border-radius:12px;padding:24px;font-size:36px;letter-spacing:2px">194352</div>
            <p>Ak si sa do aplikácie ChatGPT nepokúšal/-a prihlásiť ty, <a href="https://x">resetuj svoje heslo</a>.</p>
            </td></tr></table></body></html>"""
        val b = MailBody(h, listOf("meno.priezvisko@student.ukf.sk"), emptyList(), null, null, null, html, "", true, emptyList())
        File(dir!!).mkdirs()
        File(dir, "dark.html").writeText(MessageHtml.page(b, true))
        File(dir, "light.html").writeText(MessageHtml.page(b, false))
    }
}
