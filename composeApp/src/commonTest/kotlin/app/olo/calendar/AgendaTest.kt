package app.olo.calendar

import app.olo.calendar.model.AppSettings
import app.olo.calendar.model.CalendarInfo
import app.olo.calendar.model.Occurrence
import app.olo.calendar.model.SyncKind
import app.olo.calendar.model.at
import app.olo.calendar.model.shownOccurrences
import app.olo.calendar.model.widgetAgenda
import app.olo.calendar.store.BridgeReport
import app.olo.calendar.store.bridgeStatusLine
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** docs/09 §4: the widget lists what the app shows. */
class AgendaTest {
    private val zone = TimeZone.of("Asia/Seoul")
    private val today = LocalDate(2026, 10, 19)

    private fun timed(id: String, day: LocalDate, h: Int, h2: Int, cal: String = "s", series: String = id) =
        Occurrence(id, cal, id, false, day.at(LocalTime(h, 0), zone), day.at(LocalTime(h2, 0), zone), day, day, "", 0, false, series)

    private fun allDay(id: String, first: LocalDate, last: LocalDate, cal: String = "s") =
        Occurrence(id, cal, id, true, first.at(LocalTime(0, 0), TimeZone.UTC), last.plus(1, DateTimeUnit.DAY).at(LocalTime(0, 0), TimeZone.UTC), first, last, "", 0, false)

    @Test
    fun todayHeadsTheListAndEndedEventsLeaveIt() {
        val now = today.at(LocalTime(14, 0), zone)
        val days = widgetAgenda(listOf(timed("아침", today, 9, 10), timed("저녁", today, 19, 20)), emptySet(), today, now)
        assertEquals(today, days.first().date)
        assertEquals(listOf("저녁"), days.first().items.map { it.title })
        // Nothing else in two weeks: only today, even when it is empty.
        assertEquals(1, widgetAgenda(emptyList(), emptySet(), today, now).size)
    }

    @Test
    fun aLongEventIsListedOnceAndEmptyDaysAreSkipped() {
        val trip = allDay("출장", today.plus(-2, DateTimeUnit.DAY), today.plus(3, DateTimeUnit.DAY))
        val dentist = timed("치과", today.plus(5, DateTimeUnit.DAY), 10, 11)
        val days = widgetAgenda(listOf(trip, dentist), emptySet(), today, today.at(LocalTime(8, 0), zone))
        assertEquals(listOf(today, today.plus(5, DateTimeUnit.DAY)), days.map { it.date })
        assertEquals(listOf("출장"), days[0].items.map { it.title })
        // Past the two weeks: not listed.
        val late = timed("먼 일정", today.plus(20, DateTimeUnit.DAY), 10, 11)
        assertTrue(widgetAgenda(listOf(late), emptySet(), today, today.at(LocalTime(8, 0), zone)).none { it.items.isNotEmpty() })
    }

    @Test
    fun holidayCalendarsReadAsHolidaysAndBridgedTwinsShowOnce() {
        val holiday = allDay("한글날 대체", today, today, cal = "h")
        val days = widgetAgenda(listOf(holiday), setOf("h"), today, today.at(LocalTime(8, 0), zone))
        assertTrue(days[0].items.isEmpty())
        assertEquals(listOf("한글날 대체"), days[0].holidays)

        val cals = listOf(
            CalendarInfo("s", "Samsung Calendar", "x", "com.osp.app.signin", SyncKind.SAMSUNG, 0, true, true, true, false),
            CalendarInfo("g", "sutest", "sutest@gmail.com", "com.google", SyncKind.GOOGLE, 0, true, true, true, false),
        )
        val settings = AppSettings(bridgeSamsungId = "s", bridgeGoogleId = "g")
        val shown = shownOccurrences(listOf(timed("회의", today, 15, 16, "s"), timed("회의", today, 15, 16, "g", series = "g1")), cals, settings, setOf("g1"))
        assertEquals(listOf("s"), shown.map { it.calendarId })
    }

    @Test
    fun bridgeLineSaysWhenItNeedsThePerson() {
        val now = 1_000_000_000_000L
        val ok = BridgeReport(now - 180_000, 0, 0, 0, 0, 0, 0, 10)
        assertNull(bridgeStatusLine(false, ok, false, now))
        assertEquals("연동 3분 전 · 문제 없음" to false, bridgeStatusLine(true, ok, false, now))
        assertEquals(true, bridgeStatusLine(true, ok.copy(failed = 1), false, now)?.second)
        assertEquals(true, bridgeStatusLine(true, ok, true, now)?.second)
    }
}
