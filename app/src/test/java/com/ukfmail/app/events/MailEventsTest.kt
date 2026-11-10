package com.ukfmail.app.events

import com.ukfmail.app.ais.Lesson
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Made-up mails written the way UKF teachers, the rector's office and Moodle write them. */
class MailEventsTest {
    private val ais = "neodpovedat_ais@ukf.sk"

    private fun teacher(subject: String, text: String, sent: String = "2026-10-26T12:00", name: String = "doc. PaedDr. Jana Nováková, PhD.") =
        MailEvents.extract(MailInput("u1", ais, ais, subject, "$text\nSprávu z AiS2 odoslal: $name", LocalDateTime.parse(sent)))

    @Test fun cancelledWithMonthName() {
        val e = teacher("Zrušenie výučby", "Dobrý deň,\ndňa 27. októbra nebudeme mať výučbu.\nS pozdravom.").single()
        assertEquals(EventKind.CANCELLED, e.kind)
        assertEquals(LocalDate.of(2026, 10, 27), e.date)
        assertEquals("Nováková", e.teacher)
    }

    @Test fun cancelledNumericWithTimeRange() {
        val e = teacher("Psychológia", "Milí študenti,\ndňa 26.02.2026 (tento štvrtok) sa výučba psychológie v čase od 9,15 do 10,45 v miestnosti D1 nebude konať.", "2026-02-24T09:00").single()
        assertEquals(LocalDate.of(2026, 2, 26), e.date)
        assertEquals(LocalTime.of(9, 15), e.from)
        assertEquals(LocalTime.of(10, 45), e.to)
    }

    @Test fun tomorrowAndThisWeekAndWeekday() {
        assertEquals(LocalDate.of(2026, 4, 28), teacher("Výučba", "Milí študenti,\nz dôvodu mojej neprítomnosti sa zajtrajšia výučba ruší.", "2026-04-27T17:00").single().date)
        assertEquals(5, teacher("oznam", "Milí študenti,\nz dôvodu práceneschopnosti sa výučba tento týždeň ruší.", "2025-11-10T10:00").size)
        assertEquals(LocalDate.of(2025, 10, 22), teacher("IKT", "Milí študenti,\nv stredu nebudeme mať hodinu, nakoľko mám poradu.", "2025-10-20T10:00").single().date)
    }

    @Test fun twoDaysFromSubject() {
        val e = teacher("Výučba 17. a 18. 2. 2026", "Milí študenti,\nz dôvodu mojej neprítomnosti sa nebude realizovať výučba so mnou.", "2026-02-11T12:00")
        assertEquals(listOf(LocalDate.of(2026, 2, 17), LocalDate.of(2026, 2, 18)), e.map { it.date })
    }

    @Test fun timeBeforeTheCancellingWord() {
        val e = teacher("vyučba 1.10.2025", "Dobrý deň.\nUpozorňujem, že dňa 1.10.2025 budeme mať spolu prednášku o 8,15 z Úvodu do štúdia a potom cvičenie o 9,15 nebude.", "2025-09-30T14:00").single()
        assertEquals(LocalTime.of(9, 15), e.from)
    }

    @Test fun lectureThatStillHappensIsNotCancelled() {
        val e = teacher("Pedagogika", "Vážení študenti,\nprednáška sa v pondelok 22. 9. riadne uskutoční v učebni D3.\nRektorské voľno pre študentov je iba do 13:00, takže na túto prednášku sa voľno nevzťahuje.", "2025-09-19T11:00")
        assertTrue(e.none { it.kind == EventKind.CANCELLED })
        val free = e.single { it.kind == EventKind.FREE_DAY }
        assertEquals(LocalDate.of(2025, 9, 22), free.date)
        assertEquals(LocalTime.of(13, 0), free.to)
        assertNull(free.from)
    }

    @Test fun excursionWithTypoYear() {
        val e = teacher("zmena termínu exkurzie", "Dobrý deň,\nna základe dnešného telefonátu nám bol zmenený termín exkurzie vo firme X s.r.o. Nitra na 7.5.2025 o 9:30 hod..\nDruhá exkurzia sa uskutoční v Mochovciach dňa 21.5.2025.", "2026-04-27T14:00")
        assertEquals(listOf(LocalDate.of(2026, 5, 7), LocalDate.of(2026, 5, 21)), e.map { it.date })
        assertEquals(LocalTime.of(9, 30), e[0].from)
        assertTrue(e.all { it.kind == EventKind.EVENT && it.title == "Exkurzia" })
    }

    @Test fun rectorDayOff() {
        val m = MailInput("u2", "Oznam UKF", "oznam_ukf@ukf.sk", "Rektorské voľno dňa 30. októbra 2026",
            "Milé študentky a milí študenti,\nrektor udeľuje študentom dňa 30. októbra 2026 rektorské voľno.", LocalDateTime.parse("2026-10-19T15:00"))
        val e = MailEvents.extract(m).single()
        assertEquals(EventKind.FREE_DAY, e.kind)
        assertEquals("Rektorské voľno", e.title)
        assertEquals(LocalDate.of(2026, 10, 30), e.date)
    }

    @Test fun moodleDeadlineAndTest() {
        val due = MailInput("u3", "Neodpovedajte na tento mail cez UKF Moodle", "noreply-edu@ukf.sk", "Due on utorok, 12 mája 2026, 00:00: test",
            "Hi Jana,\nThe assignment test in course Teória výchovy is due soon.\nDue: utorok, 12 mája 2026, 00:00\nGo to activity", LocalDateTime.parse("2026-05-11T12:00"))
        val d = MailEvents.extract(due).single()
        assertEquals(EventKind.DEADLINE, d.kind)
        assertEquals(LocalDate.of(2026, 5, 12), d.date)
        assertEquals("Teória výchovy", d.subjectHint)
        val test = MailInput("u4", "UKF Moodle", "noreply-moodle@ukf.sk", "Otvorí sa o streda, 10 decembra 2025, 18:00: test 2",
            "Zdravím,\nTest test 2 v predmete Ochrana práce bude čoskoro otvorený.\nOtvára: streda, 10 decembra 2025, 18:00\nKončí: streda, 10 decembra 2025, 18:45", LocalDateTime.parse("2025-12-09T14:00"))
        val t = MailEvents.extract(test).single()
        assertEquals(EventKind.TEST, t.kind)
        assertEquals(LocalTime.of(18, 0), t.from)
        assertEquals(LocalTime.of(18, 45), t.to)
        assertEquals("test 2", t.title)
        assertTrue(MailEvents.extract(due.copy(subject = "Overdue: test")).isEmpty())
    }

    @Test fun studentsAndQuotesAreIgnored() {
        val student = MailInput("u5", "Spolužiak", "x@student.ukf.sk", "Prednáška", "zajtra nebude prednáška", LocalDateTime.parse("2026-10-01T10:00"))
        assertTrue(MailEvents.extract(student).isEmpty())
        val reply = MailInput("u6", "Jana Nováková", "jnovakova@ukf.sk", "Re: ospravedlnenie",
            "ok\n---\nDňa 2026-11-02 20:55 Jana napísal(a):\nnebudem sa môcť zúčastniť vyučovania dňa 3.11.2026.", LocalDateTime.parse("2026-11-03T10:00"))
        assertTrue(MailEvents.extract(reply).isEmpty())
    }

    // --- matching lessons ---

    private fun lesson(title: String, h: Int, m: Int, teacher: String) =
        Lesson(title, null, null, teacher, LocalDateTime.of(2026, 10, 27, h, m), LocalDateTime.of(2026, 10, 27, h + 1, m + 30 - 30))

    private val day = listOf(
        lesson("Seminár - webové technológie", 7, 30, "Nováková"),
        lesson("CAD systémy", 9, 15, "Nováková"),
        lesson("Informačné a komunikačné technológie vo vzdelávaní", 13, 0, "Kráľ, Biely"),
        lesson("Materiály a technológie II.", 15, 0, "Malá"),
    )

    private fun cancel(hint: String, teacher: String? = "Nováková", from: LocalTime? = null) =
        StudyEvent(EventKind.CANCELLED, LocalDate.of(2026, 10, 27), from, null, "Odpadá výučba", hint, teacher, hint, "m", "s")

    @Test fun namedSubjectsAndAcronym() {
        val hit = Plans.matchLessons(cancel("dňa 27. októbra nebudeme mať predmet Webové technológie a ani IKT."), day).map { it.title }
        assertEquals(listOf("Seminár - webové technológie", "Informačné a komunikačné technológie vo vzdelávaní"), hit)
    }

    @Test fun noSubjectMeansTheTeachersLessons() {
        val hit = Plans.matchLessons(cancel("dňa 27. októbra nebudeme mať výučbu."), day).map { it.title }
        assertEquals(listOf("Seminár - webové technológie", "CAD systémy"), hit)
    }

    @Test fun oneSharedWordPrefersTheSender() {
        val hit = Plans.matchLessons(cancel("prednáška z materiálov nebude", teacher = "Malá"), day).map { it.title }
        assertEquals(listOf("Materiály a technológie II."), hit)
    }

    @Test fun timeNarrowsIt() {
        val hit = Plans.matchLessons(cancel("cvičenie o 9,15 nebude", from = LocalTime.of(9, 15)), day).map { it.title }
        assertEquals(listOf("CAD systémy"), hit)
    }

    @Test fun freeDayUntilOneCoversMorningOnly() {
        val free = StudyEvent(EventKind.FREE_DAY, LocalDate.of(2026, 10, 27), null, LocalTime.of(13, 0), "Rektorské voľno", snippet = "", mailUid = "m", mailSubject = "")
        val plan = Plans.day(LocalDate.of(2026, 10, 27), day, listOf(free))
        assertEquals(listOf(true, true, false, false), plan.lessons.map { it.cancelled })
    }

    @Test fun holidays() {
        assertEquals(LocalDate.of(2026, 4, 5), Holidays.easterSunday(2026))
        assertEquals("Veľkonočný pondelok", Holidays.on(LocalDate.of(2026, 4, 6)).single().title)
        assertTrue(Holidays.on(LocalDate.of(2026, 11, 17)).isEmpty())
    }
}
