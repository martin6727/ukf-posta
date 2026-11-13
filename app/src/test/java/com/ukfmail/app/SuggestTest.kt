package com.ukfmail.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SuggestTest {
    private val people = listOf(
        Contact("stefan.novy@student.ukf.sk", "Štefan Nový", count = 1, last = 100),
        Contact("eva.mala@ukf.sk", "Eva Malá", count = 5, last = 50),
        Contact("ivan@example.com", null, count = 2, last = 200),
        Contact("evelina@ukf.sk", "Evelína Kováčová", count = 1, last = 300),
    )

    @Test fun matchesWithoutDiacritics() {
        assertEquals(listOf("stefan.novy@student.ukf.sk"), SentStore.suggest(people, "stef").map { it.email })
        assertEquals(listOf("evelina@ukf.sk"), SentStore.suggest(people, "kovac").map { it.email })
    }

    @Test fun frequentFirstAmongPrefixMatches() {
        assertEquals(listOf("eva.mala@ukf.sk", "evelina@ukf.sk"), SentStore.suggest(people, "ev").map { it.email })
    }

    @Test fun onlyTheLastEntryCountsAndPresentOnesAreSkipped() {
        val got = SentStore.suggest(people, "Eva Malá <eva.mala@ukf.sk>, ev").map { it.email }
        assertEquals(listOf("evelina@ukf.sk"), got)
    }

    @Test fun emptyTokenGivesNothing() {
        assertTrue(SentStore.suggest(people, "ivan@example.com, ").isEmpty())
    }

    @Test fun recipientsSplitKeepsQuotedCommas() {
        assertEquals(
            listOf("\"Novák, Ján\" <jan@x.sk>", "eva@y.sk", "ivan"),
            Recipients.split("\"Novák, Ján\" <jan@x.sk>, eva@y.sk; ivan"),
        )
        assertEquals("Novák, Ján", Recipients.display("\"Novák, Ján\" <jan@x.sk>"))
        assertEquals("jan@x.sk", Recipients.email("\"Novák, Ján\" <jan@x.sk>"))
        assertEquals("eva@y.sk", Recipients.display("eva@y.sk"))
        assertTrue(Recipients.isComplete("Eva <eva@y.sk>"))
        assertTrue(!Recipients.isComplete("ivan"))
        assertTrue(!Recipients.isComplete("ivan@gmail"))
    }

    @Test fun unquotedTitleWithCommaStaysOnePerson() {
        val list = Recipients.split("Mgr. Jana Kováčová, PhD. <jk@ukf.sk>, doc. Peter Novák <pn@ukf.sk>, ev")
        assertEquals(listOf("Mgr. Jana Kováčová, PhD. <jk@ukf.sk>", "doc. Peter Novák <pn@ukf.sk>", "ev"), list)
        assertEquals("Mgr. Jana Kováčová, PhD.", Recipients.display(list[0]))
        // Handed to JavaMail with the name quoted, so it stays one address.
        assertEquals(
            "\"Mgr. Jana Kováčová, PhD.\" <jk@ukf.sk>, doc. Peter Novák <pn@ukf.sk>",
            Recipients.normalizeList("Mgr. Jana Kováčová, PhD. <jk@ukf.sk>; doc. Peter Novák <pn@ukf.sk>,"),
        )
        val parsed = javax.mail.internet.InternetAddress.parse(Recipients.normalizeList("Mgr. Jana Kováčová, PhD. <jk@ukf.sk>"), false)
        assertEquals(1, parsed.size)
        assertEquals("jk@ukf.sk", parsed[0].address)
        assertEquals("\"Ing. Eva Malá, PhD.\" <eva@ukf.sk>", Contact("eva@ukf.sk", "Ing. Eva Malá, PhD.", 1, 0).label)
    }

    @Test fun pickingReplacesTheHalfTypedEntry() {
        val eva = people[1]
        assertEquals("Eva Malá <eva.mala@ukf.sk>, ", SentStore.applySuggestion("ev", eva))
        assertEquals("ivan@example.com, Eva Malá <eva.mala@ukf.sk>, ", SentStore.applySuggestion("ivan@example.com, ma", eva))
        assertEquals("ivan@example.com, ", SentStore.applySuggestion("iv", people[2]))
    }
}
