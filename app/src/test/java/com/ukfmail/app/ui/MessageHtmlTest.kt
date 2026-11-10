package com.ukfmail.app.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageHtmlTest {
    @Test
    fun mailCssIsScopedToItsOwnBox() {
        val out = MessageHtml.isolate(
            "<!DOCTYPE html><html><head><title>x</title><style>body{padding:0!important} html, body {width:100%} h1{color:red}</style></head>" +
                "<body style=\"margin:0\"><h1>Ahoj</h1><p>text</p></body></html>"
        )
        assertTrue(out, out.contains(".ukf-mail{padding:0!important}"))
        assertTrue(out, out.contains(".ukf-mail, .ukf-mail {width:100%}"))
        assertTrue(out, out.contains("<h1>Ahoj</h1><p>text</p>"))
        assertFalse(out, out.contains("<body"))
        assertFalse(out, out.contains("<title>"))
    }
}
