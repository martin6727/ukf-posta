package com.ukfmail.app.ais

import java.time.LocalDate
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IcsTest {
    private val ics = """
        BEGIN:VCALENDAR
        VERSION:2.0
        BEGIN:VEVENT
        DTSTART;TZID=Europe/Bratislava:20260921T080000
        DTEND;TZID=Europe/Bratislava:20260921T093000
        RRULE:FREQ=WEEKLY;UNTIL=20261221T230000Z
        EXDATE;TZID=Europe/Bratislava:20261116T080000
        SUMMARY:Matematika 1 (P)
        LOCATION:A-312
        DESCRIPTION:Vyučujúci: doc. RNDr. Ján Novák\, PhD.\nprednáška
        END:VEVENT
        BEGIN:VEVENT
        DTSTART:20260923T090000Z
        DTEND:20260923T103000Z
        RRULE:FREQ=WEEKLY;INTERVAL=2;COUNT=6
        SUMMARY:Programovanie - cvičenie v lab
         oratóriu
        LOCATION:B-101
        END:VEVENT
        BEGIN:VEVENT
        DTSTART:20261113T130000
        DTEND:20261113T140000
        SUMMARY:Konzultácia
        END:VEVENT
        END:VCALENDAR
    """.trimIndent().replace("\n", "\r\n")

    private val events = Ics.parse(ics)

    @Test fun parsesEventsAndFolding() {
        assertEquals(3, events.size)
        assertEquals("Programovanie - cvičenie v laboratóriu", events[1].summary)
        assertEquals("A-312", events[0].location)
    }

    @Test fun weeklyWithException() {
        val week = Ics.lessons(events, LocalDate.of(2026, 11, 9), LocalDate.of(2026, 11, 15))
        val math = week.filter { it.title == "Matematika 1" }
        assertEquals(1, math.size)
        assertEquals(LocalDateTime.of(2026, 11, 9, 8, 0), math[0].start)
        assertEquals(LocalDateTime.of(2026, 11, 9, 9, 30), math[0].end)
        assertEquals("Prednáška", math[0].kind)
        assertEquals("doc. RNDr. Ján Novák, PhD.", math[0].teacher)
        // The cancelled lesson on 16 November is skipped.
        val next = Ics.lessons(events, LocalDate.of(2026, 11, 16), LocalDate.of(2026, 11, 22))
        assertTrue(next.none { it.title == "Matematika 1" })
    }

    @Test fun utcTimesAreLocalAndIntervalCounts() {
        // 23 Sep 09:00 UTC is 11:00 in Bratislava (summer time); every second week, six times.
        val all = Ics.lessons(events, LocalDate.of(2026, 9, 1), LocalDate.of(2027, 2, 1)).filter { it.title.startsWith("Programovanie") }
        assertEquals(6, all.size)
        assertEquals(LocalDateTime.of(2026, 9, 23, 11, 0), all[0].start)
        assertEquals(LocalDate.of(2026, 10, 7), all[1].start.toLocalDate())
        // After the switch to winter time 09:00 UTC is 10:00.
        assertEquals(10, all.last().start.hour)
    }

    @Test fun aisSummaryCleanup() {
        val e = Ics.parse(
            "BEGIN:VEVENT\nDTSTART:20261110T073000\nDTEND:20261110T090000\n" +
                "SUMMARY:KAI/bAB10/22 Algoritmy a údajové štruktúry C (Rozvrhov\n á akcia)\n" +
                """LOCATION:A00120\, poschodie 0\, Hlavná 4\, Nitra""" + "\nEND:VEVENT"
        )
        val l = Ics.lessons(e, LocalDate.of(2026, 11, 10), LocalDate.of(2026, 11, 10)).single()
        assertEquals("Algoritmy a údajové štruktúry", l.title)
        assertEquals("Cvičenie", l.kind)
        assertEquals("A00120 · poschodie 0", l.room)
    }

    @Test fun singleEvent() {
        val l = Ics.lessons(events, LocalDate.of(2026, 11, 13), LocalDate.of(2026, 11, 13))
        assertEquals(listOf("Konzultácia"), l.map { it.title })
    }
}
