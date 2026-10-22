package app.olo.calendar

import app.olo.calendar.model.AppSettings
import app.olo.calendar.model.MemoryPreferences
import app.olo.calendar.model.Occurrence
import app.olo.calendar.model.ViewMode
import app.olo.calendar.model.at
import app.olo.calendar.model.isoWeekNumber
import app.olo.calendar.model.layoutDay
import app.olo.calendar.model.loadSettings
import app.olo.calendar.model.saveSettings
import app.olo.calendar.model.weekStartOf
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals

class ViewsLogicTest {
    private val zone = TimeZone.of("Asia/Seoul")
    private val day = LocalDate(2026, 10, 16)

    private fun t(id: String, h: Int, m: Int, h2: Int, m2: Int) =
        Occurrence(id, "c", id, false, day.at(LocalTime(h, m), zone), day.at(LocalTime(h2, m2), zone), day, day, "", 0, false)

    @Test
    fun overlappingEventsShareWidthOthersTakeItAll() {
        val blocks = layoutDay(day, listOf(t("a", 9, 0, 10, 30), t("b", 10, 0, 11, 0), t("c", 13, 0, 14, 0)), zone).associateBy { it.occurrence.eventId }
        assertEquals(0 to 2, blocks["a"]!!.col to blocks["a"]!!.cols)
        assertEquals(1 to 2, blocks["b"]!!.col to blocks["b"]!!.cols)
        assertEquals(0 to 1, blocks["c"]!!.col to blocks["c"]!!.cols)
        assertEquals(540, blocks["a"]!!.startMin)
    }

    @Test
    fun freedColumnIsReused() {
        // a 9-11, b 9-10, c 10-11: c reuses b's column, so the cluster needs two columns.
        val blocks = layoutDay(day, listOf(t("a", 9, 0, 11, 0), t("b", 9, 0, 10, 0), t("c", 10, 0, 11, 0)), zone).associateBy { it.occurrence.eventId }
        assertEquals(2, blocks["c"]!!.cols)
        assertEquals(blocks["b"]!!.col, blocks["c"]!!.col)
    }

    @Test
    fun shortEventsGetAMinimumHeight() {
        val b = layoutDay(day, listOf(t("a", 9, 0, 9, 5)), zone).single()
        assertEquals(20, b.endMin - b.startMin)
    }

    @Test
    fun weekStartsAndNumbers() {
        assertEquals(LocalDate(2026, 10, 11), weekStartOf(day, DayOfWeek.SUNDAY))
        assertEquals(LocalDate(2026, 10, 12), weekStartOf(day, DayOfWeek.MONDAY))
        assertEquals(42, isoWeekNumber(LocalDate(2026, 10, 12)))
        assertEquals(53, isoWeekNumber(LocalDate(2026, 12, 31))) // 2026 has 53 ISO weeks
        assertEquals(1, isoWeekNumber(LocalDate(2027, 1, 4)))
    }

    @Test
    fun settingsRoundTrip() {
        val p = MemoryPreferences()
        assertEquals(AppSettings(), p.loadSettings())
        val s = AppSettings(DayOfWeek.MONDAY, showLunar = false, showWeekNumbers = true, defaultCalendarId = "7", lastView = ViewMode.AGENDA)
        p.saveSettings(s)
        assertEquals(s, p.loadSettings())
    }

    @Test
    fun scoreboardTitleFlipsOnlyTheDigitsThatChange() {
        // Digits line up from the right, so "월" keeps its card.
        val cells = app.olo.calendar.ui.flapCells("2026년 9월", "2026년 10월")
        assertEquals(listOf("2", "0", "2", "6", "년", " ", "", "9", "월"), cells.map { it.first })
        assertEquals(listOf("2", "0", "2", "6", "년", " ", "1", "0", "월"), cells.map { it.second })
        assertEquals(2, cells.count { it.first != it.second })
        assertEquals(3, app.olo.calendar.ui.flapCells("2026년 12월", "2027년 1월").count { it.first != it.second })
    }
}
