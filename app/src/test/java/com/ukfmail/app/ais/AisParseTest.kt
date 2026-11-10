package com.ukfmail.app.ais

import com.ukfmail.app.AisCheck
import java.time.LocalDate
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AisParseTest {

    @Test fun sheetsFromStudies() {
        val json = """{"data":[{"name":"Aplikovaná informatika","zapisneListy":[
            {"id":123,"akRok":"2026/2027","name":"AI 2026/2027"},{"id":99,"akRok":"2025/2026","name":null}]}]}"""
        val s = AisParse.sheets(json)
        assertEquals(listOf("123", "99"), s.map { it.id })
        assertEquals("2026/2027", s[0].year)
        assertEquals("Aplikovaná informatika", s[1].name)
    }

    @Test fun gradesToleratesNulls() {
        val json = """{"znamky":[
            {"id":1,"predmetSkratka":"KI/MAT1","predmetNazov":"Matematika 1","kredit":6,"kodSemester":"Z",
             "hodnotenieKod":"A","hodnoteniePopis":"výborne","hodnotiaci":"doc. RNDr. Novák","kodSposobUkoncenia":"S","kodCastSP":"A"},
            {"id":2,"predmetNazov":"Programovanie","kredit":5.0,"kodSemester":null,"hodnotenieKod":null,"hodnotiaci":null}
        ],"castiStudijnehoPlanu":[]}"""
        val g = AisParse.grades(json)
        assertEquals(2, g.size)
        assertEquals("A", g[0].grade)
        assertEquals("Skúška", g[0].completion)
        assertEquals(6, g[0].credits)
        assertNull(g[1].grade)
        assertNull(g[1].semester)
        assertEquals(5, g[1].credits)
    }

    @Test fun examsSortedAndSheetIdShared() {
        val json = """[
            {"id":"t2","idPredmet":7,"idPredmetZl":null,"predmetNazov":"Fyzika","datumSk":"20.01.2027","cas":"10:00","bude":true,"terminNaplneny":false,"mozeSaPrihlasit":true},
            {"id":"t1","idPredmet":7,"idPredmetZl":555,"predmetNazov":"Fyzika","datumSk":"14.01.2027","cas":"08:00","bude":true,"datumPrihlasenia":"02.01.2027 10:11"},
            {"id":"t0","idPredmet":8,"predmetNazov":"Chémia","datumSk":"10.12.2026","cas":"09:00","bude":false,"hodnotenieTermin":"B"}
        ]"""
        val e = AisParse.exams(json)
        assertEquals(listOf("t0", "t1", "t2"), e.map { it.id })
        assertTrue(e[1].registered)
        assertEquals("555", e[2].subjectSheetId) // borrowed from the other term of the same subject
        assertTrue(e[2].canRegister)
        assertFalse(e[1].canRegister)
        assertEquals(LocalDateTime.of(2027, 1, 20, 10, 0), e[2].start)
    }

    @Test fun fullTermCannotBeTaken() {
        val e = AisParse.exams("""[{"id":"x","datumSk":"1.2.2027","bude":true,"terminNaplneny":true}]""").single()
        assertFalse(e.canRegister)
    }

    @Test fun examDetailMixedShapes() {
        val d = AisParse.examDetail("""{"miestnosti":[{"kod":"A-312"}],"hodnotiaci":["Mgr. Kováč",{"plneMeno":"Ing. Malá"}],
            "maxPocet":20,"poznamka":null,"studenti":[{"plneMeno":"unknown"},{"plneMeno":"Ján"}],"datumPrihlasDo":"12.01.2027"}""")
        assertEquals(listOf("A-312"), d.rooms)
        assertEquals(listOf("Mgr. Kováč", "Ing. Malá"), d.examiners)
        assertEquals("20", d.capacity)
        assertNull(d.note)
        assertEquals(listOf("Ján"), d.students)
    }

    @Test fun progressSkipsRecognisedCredits() {
        val p = AisParse.progress("""[{"kodTypVyucby":"A","pocetZapisane":5,"pocetAbsolvovane":3,"kredityZapisane":30,"kredityAbsolvovane":18},
            {"kodTypVyucby":"UZNANE","pocetZapisane":0,"pocetAbsolvovane":1,"kredityZapisane":0,"kredityAbsolvovane":4}]""")
        assertEquals(30, p.creditsEnrolled)
        assertEquals(18, p.creditsPassed)
        assertEquals(4, p.passed)
    }

    @Test fun averagesNestedOrFlat() {
        assertEquals("1.42", AisParse.averages("""{"priemerVsetkych":1.42}""").all)
        assertEquals("2", AisParse.averages("""{"priemery":{"priemerHodnotenych":2.0}}""").graded)
    }

    @Test fun timetableTerm() {
        assertEquals("2026/2027" to "Z", AisParse.timetableTerm("""{"aktualnyAkRok":"2026/2027","kodSemester":"Z"}"""))
        assertEquals("2026/2027" to null, AisParse.timetableTerm("""{"aktualnyAkRok":{"akRok":"2026/2027"}}"""))
    }

    @Test fun lessonsFromRealTimetableJson() {
        // Shape AiS2 really sends (2026-11-10).
        val json = """{"akRok":"2026/2027","kodSemester":"Z","data":[
            {"typ":"RSC","predmetSkratka":"bAB11","predmetNazov":"Bezpečnosť práce","budovaPopis":"Hlavná - blok D (KAI)",
             "casOd":"11:00","casDo":"12:30","den":3,"datumOd":"2026-09-21","datumDo":"2026-12-19","pravidelnost":"TYZ","vyucujuci":["Horváth","Kováčová"],"miestnosti":["A00070"],"datumy":null},
            {"typ":"RSP","predmetNazov":"CAD systémy","casOd":"09:15","casDo":"10:45","den":2,"datumOd":"2026-09-21","datumDo":"2026-12-19","miestnosti":["B04180"]}]}"""
        val l = AisParse.lessonsFromJson(json, LocalDate.of(2026, 11, 9), LocalDate.of(2026, 11, 15))
        assertEquals(2, l.size)
        assertEquals(LocalDateTime.of(2026, 11, 10, 9, 15), l[0].start)
        assertEquals("Prednáška", l[0].kind)
        assertEquals("Cvičenie", l[1].kind)
        assertEquals("A00070 · Hlavná – blok D", l[1].room)
        assertEquals("Horváth, Kováčová", l[1].teacher)
        assertEquals("bAB11", l[1].code)
        // Nothing after the semester ends.
        assertTrue(AisParse.lessonsFromJson(json, LocalDate.of(2027, 1, 4), LocalDate.of(2027, 1, 10)).isEmpty())
    }

    @Test fun semesterAndYear() {
        assertEquals("2026/2027", AisRepo.academicYear(LocalDate.of(2026, 11, 10)))
        assertEquals("2025/2026", AisRepo.academicYear(LocalDate.of(2026, 3, 1)))
        assertEquals("Z", AisRepo.semesterOf(LocalDate.of(2027, 1, 20)))
        assertEquals("L", AisRepo.semesterOf(LocalDate.of(2027, 3, 1)))
    }

    @Test fun notificationRules() {
        val exams = AisParse.exams("""[
            {"id":"a","datumSk":"11.11.2026","cas":"09:00","bude":true,"datumPrihlasenia":"1.11.2026"},
            {"id":"b","datumSk":"20.11.2026","cas":"09:00","bude":true},
            {"id":"c","datumSk":"21.11.2026","cas":"09:00","bude":true}]""")
        val now = LocalDateTime.of(2026, 11, 10, 12, 0)
        assertEquals(listOf("a"), AisCheck.dueReminders(exams, emptySet(), now).map { it.id })
        assertTrue(AisCheck.dueReminders(exams, setOf("a"), now).isEmpty())
        assertEquals(listOf("c"), AisCheck.newTerms(exams, setOf("a", "b")).map { it.id })
    }
}
