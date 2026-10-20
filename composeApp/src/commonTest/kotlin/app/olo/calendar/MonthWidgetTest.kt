package app.olo.calendar

import app.olo.calendar.model.Occurrence
import app.olo.calendar.model.YearMonth
import app.olo.calendar.model.at
import app.olo.calendar.model.monthWidgetWeeks
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** docs/09 W2: the month widget's grid follows the app's month view. */
class MonthWidgetTest {
    private val zone = TimeZone.of("Asia/Seoul")
    private val today = LocalDate(2026, 10, 20)

    private fun ev(id: String, day: LocalDate, cal: String = "s", color: Int = 1) =
        Occurrence(id, cal, id, false, day.at(LocalTime(9, 0), zone), day.at(LocalTime(10, 0), zone), day, day, "", color, false)

    @Test
    fun gridIsTheAppsMonthWithDotsAndRedDays() {
        val busy = LocalDate(2026, 10, 14)
        val events = (1..5).map { ev("e$it", busy, color = it) } + ev("휴일", LocalDate(2026, 10, 9), cal = "h")
        val weeks = monthWidgetWeeks(YearMonth(2026, 10), DayOfWeek.SUNDAY, events, setOf("h"), today, showLunar = true)
        assertEquals(5, weeks.size) // October 2026 needs five Sunday-first weeks
        assertTrue(weeks.all { it.size == 7 })
        val cells = weeks.flatten()
        assertEquals(DayOfWeek.SUNDAY, cells.first().date.dayOfWeek)
        val b = cells.first { it.date == busy }
        assertEquals(listOf(1, 2, 3), b.dots)
        assertEquals(2, b.more)
        // A holiday-calendar entry reddens the number and adds no dot.
        val h = cells.first { it.date == LocalDate(2026, 10, 9) }
        assertTrue(h.red && h.dots.isEmpty())
        assertTrue(cells.first { it.date == today }.isToday)
        assertTrue(cells.none { it.inMonth && it.date.monthNumber != 10 })
        // Lunar 1st/15th only, and only when the app shows lunar dates.
        assertTrue(cells.mapNotNull { it.lunar }.all { it.endsWith(".1") || it.endsWith(".15") })
        assertTrue(monthWidgetWeeks(YearMonth(2026, 10), DayOfWeek.SUNDAY, events, setOf("h"), today, showLunar = false).flatten().all { it.lunar == null })
    }
}
