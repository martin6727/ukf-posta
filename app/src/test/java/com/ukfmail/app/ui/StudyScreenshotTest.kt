package com.ukfmail.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.NightMode
import com.ukfmail.app.ais.AisParse
import com.ukfmail.app.ais.Averages
import com.ukfmail.app.ais.Ics
import com.ukfmail.app.ais.Progress
import com.ukfmail.app.ais.StudySheet
import com.ukfmail.app.ais.Timetable
import java.time.LocalDateTime
import org.junit.Rule
import org.junit.Test

/** The study tabs with sample AiS2 data, on Tuesday 10 November 2026 at 9:10. */
class StudyScreenshotTest {
    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_6, showSystemUi = false)

    private val now = LocalDateTime.of(2026, 11, 10, 9, 10)
    private val updated = System.currentTimeMillis() - 4 * 60_000

    private fun ev(day: Int, from: String, to: String, summary: String, room: String, teacher: String) = """
        BEGIN:VEVENT
        DTSTART;TZID=Europe/Bratislava:202611${"%02d".format(day)}T${from}00
        DTEND;TZID=Europe/Bratislava:202611${"%02d".format(day)}T${to}00
        RRULE:FREQ=WEEKLY;COUNT=12
        SUMMARY:$summary
        LOCATION:$room
        DESCRIPTION:Vyučujúci: $teacher
        END:VEVENT
    """.trimIndent()

    private val timetable = Timetable(
        Ics.parse(
            "BEGIN:VCALENDAR\n" + listOf(
                ev(2, "0900", "1030", "Databázové systémy (P)", "A-312", "doc. RNDr. Peter Novák, PhD."),
                ev(10, "0800", "0930", "Programovanie v Kotline (P)", "B-101", "Mgr. Jana Kováčová, PhD."),
                ev(10, "0940", "1110", "Programovanie v Kotline (C)", "PC lab 2", "Mgr. Jana Kováčová, PhD."),
                ev(10, "1300", "1430", "Diskrétna matematika (S)", "A-208", "RNDr. Ivan Malý"),
                ev(11, "1000", "1130", "Počítačové siete (C)", "C-04", "Ing. Lukáš Horváth"),
                ev(12, "0800", "0930", "Anglický jazyk (S)", "D-15", "Mgr. Eva Bieliková"),
            ).joinToString("\n") + "\nEND:VCALENDAR"
        )
    )

    private val exams = AisParse.exams(
        """[
        {"id":"1","idPredmet":1,"idPredmetZl":11,"predmetNazov":"Databázové systémy","predmetSkratka":"KI/DBS","datumSk":"13.11.2026","cas":"9:00","bude":true,"datumPrihlasenia":"1.11.2026"},
        {"id":"2","idPredmet":2,"idPredmetZl":12,"predmetNazov":"Programovanie v Kotline","predmetSkratka":"KI/PK","datumSk":"8.12.2026","cas":"8:00","bude":true,"datumPrihlasenia":"2.11.2026","faza":"Riadny termín"},
        {"id":"3","idPredmet":3,"idPredmetZl":13,"predmetNazov":"Diskrétna matematika","predmetSkratka":"KM/DM","datumSk":"15.12.2026","cas":"10:00","bude":true,"mozeSaPrihlasit":true,"datumPrihlasDoSk":"13.12.2026"},
        {"id":"4","idPredmet":4,"idPredmetZl":14,"predmetNazov":"Počítačové siete","predmetSkratka":"KI/PS","datumSk":"17.12.2026","cas":"9:00","bude":true,"terminNaplneny":true}
        ]"""
    )

    private val grades = AisParse.grades(
        """{"znamky":[
        {"id":1,"predmetSkratka":"KI/ALG","predmetNazov":"Algoritmy a údajové štruktúry","kredit":6,"kodSemester":"Z","hodnotenieKod":"A","hodnoteniePopis":"výborne","hodnotiaci":"prof. Ing. Marek Kráľ, PhD.","kodSposobUkoncenia":"S","kodCastSP":"A"},
        {"id":2,"predmetSkratka":"KI/WEB","predmetNazov":"Webové technológie","kredit":5,"kodSemester":"Z","hodnotenieKod":"B","hodnoteniePopis":"veľmi dobre","hodnotiaci":"Mgr. Tomáš Biely","kodSposobUkoncenia":"H","kodCastSP":"B"},
        {"id":3,"predmetSkratka":"KM/STAT","predmetNazov":"Štatistika","kredit":4,"kodSemester":"Z","hodnotenieKod":"FX","hodnoteniePopis":"nedostatočne","kodSposobUkoncenia":"S","kodCastSP":"A"},
        {"id":4,"predmetSkratka":"KI/DBS","predmetNazov":"Databázové systémy","kredit":6,"kodSemester":"Z","kodSposobUkoncenia":"S","kodCastSP":"A"},
        {"id":5,"predmetSkratka":"KI/PK","predmetNazov":"Programovanie v Kotline","kredit":5,"kodSemester":"Z","kodSposobUkoncenia":"S","kodCastSP":"B"},
        {"id":6,"predmetSkratka":"KJ/AJ","predmetNazov":"Anglický jazyk","kredit":2,"kodSemester":"Z","kodSposobUkoncenia":"H","kodCastSP":"C"}
        ]}"""
    )

    @Composable
    private fun Frame(tab: Tab, content: @Composable () -> Unit) {
        Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize()) {
                content()
                TabBar(tab, {}, mailBadge = 3, modifier = Modifier.align(Alignment.BottomCenter))
            }
        }
    }

    @Composable
    private fun Schedule() = Frame(Tab.SCHEDULE) {
        ScheduleContent(timetable, updated, false, null, false, "", { _, _ -> }, {}, now)
    }

    @Composable
    private fun Exams() = Frame(Tab.EXAMS) {
        ExamsContent(exams, "2026/2027", updated, false, null, false, "", { _, _ -> }, {}, {}, today = now.toLocalDate())
    }

    @Composable
    private fun Grades() = Frame(Tab.GRADES) {
        GradesContent(
            grades, Averages("1.38", null, null), Progress(6, 2, 28, 11),
            listOf(StudySheet("1", "2026/2027", "AI", "Aplikovaná informatika")), StudySheet("1", "2026/2027", "AI", "Aplikovaná informatika"),
            {}, updated, false, null, false, "", { _, _ -> }, {}, today = now.toLocalDate(),
        )
    }

    @Composable
    private fun Login() = Frame(Tab.GRADES) {
        GradesContent(emptyList(), null, null, emptyList(), null, {}, 0, false, "AiS2 neprijal prihlasovacie meno alebo heslo.", true, "1234567", { _, _ -> }, {})
    }

    private fun night() = paparazzi.unsafeUpdateConfig(DeviceConfig.PIXEL_6.copy(nightMode = NightMode.NIGHT))

    private val mailEvents = listOf(
        com.ukfmail.app.events.StudyEvent(com.ukfmail.app.events.EventKind.CANCELLED, java.time.LocalDate.of(2026, 11, 10), null, null, "Odpadá výučba",
            "dňa 10. novembra nebudeme mať predmet Diskrétna matematika.", "Malý", "Dobrý deň, dňa 10. novembra nebudeme mať predmet Diskrétna matematika.", "m1", "Zrušená hodina"),
        com.ukfmail.app.events.StudyEvent(com.ukfmail.app.events.EventKind.EVENT, java.time.LocalDate.of(2026, 11, 10), java.time.LocalTime.of(15, 30), null, "Exkurzia",
            null, "Horváth", "Dňa 10.11.2026 o 15:30 sa uskutoční exkurzia vo firme Muehlbauer, Novozámocká 233.", "m2", "Exkurzia"),
        com.ukfmail.app.events.StudyEvent(com.ukfmail.app.events.EventKind.DEADLINE, java.time.LocalDate.of(2026, 11, 10), java.time.LocalTime.of(23, 59), null, "Seminárna práca",
            "Počítačové siete", null, "", "m3", "Due"),
    )

    @Test fun scheduleWithMail() = paparazzi.snapshot { AppTheme { Frame(Tab.SCHEDULE) { ScheduleContent(timetable, updated, false, null, false, "", { _, _ -> }, {}, now, mailEvents) } } }
    @Test fun scheduleRectorDayOff() { night(); paparazzi.snapshot { AppTheme { Frame(Tab.SCHEDULE) { ScheduleContent(timetable, updated, false, null, false, "", { _, _ -> }, {}, now,
        listOf(com.ukfmail.app.events.StudyEvent(com.ukfmail.app.events.EventKind.FREE_DAY, java.time.LocalDate.of(2026, 11, 10), null, java.time.LocalTime.of(12, 0), "Rektorské voľno", snippet = "", mailUid = "m4", mailSubject = ""))) } } } }

    @Test fun scheduleLight() = paparazzi.snapshot { AppTheme { Schedule() } }
    @Test fun scheduleDark() { night(); paparazzi.snapshot { AppTheme { Schedule() } } }
    @Test fun examsLight() = paparazzi.snapshot { AppTheme { Exams() } }
    @Test fun examsDark() { night(); paparazzi.snapshot { AppTheme { Exams() } } }
    @Test fun gradesLight() = paparazzi.snapshot { AppTheme { Grades() } }
    @Test fun gradesDark() { night(); paparazzi.snapshot { AppTheme { Grades() } } }
    @Test fun tabBarMidSwipe() = paparazzi.snapshot {
        AppTheme { Surface { androidx.compose.foundation.layout.Column { listOf(0f, 0.3f, 0.5f, 0.8f, 2f).forEach { TabBar(position = it, onTab = {}, mailBadge = 2) } } } }
    }
    @Test fun gradesCool() = paparazzi.snapshot {
        AppTheme { androidx.compose.runtime.CompositionLocalProvider(LocalCoolGrades provides true) { Grades() } }
    }
    @Test fun appearanceSettings() = paparazzi.snapshot {
        AppTheme(ThemeMode.LIGHT) { Surface { androidx.compose.foundation.layout.Column(androidx.compose.foundation.layout.PaddingValues(24.dp).let { Modifier.padding(it) }) { AppearanceSection(ThemeMode.LIGHT, {}, true, {}) } } }
    }
    @Test fun forcedDarkOnLightPhone() = paparazzi.snapshot { AppTheme(ThemeMode.DARK) { Exams() } }

    @Test fun aisLogin() = paparazzi.snapshot { AppTheme { Login() } }
}
