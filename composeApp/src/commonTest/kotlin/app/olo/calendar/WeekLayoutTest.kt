package app.olo.calendar

import app.olo.calendar.model.AllDay
import app.olo.calendar.model.Occurrence
import app.olo.calendar.model.layoutWeek
import app.olo.calendar.model.mergeExdates
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WeekLayoutTest {
    private val week = List(7) { LocalDate.fromEpochDays(LocalDate(2026, 10, 4).toEpochDays() + it) } // Sun 4 .. Sat 10

    private fun ev(id: String, first: Int, last: Int = first, allDay: Boolean = true, hour: Int = 0) = Occurrence(
        id, "c", id, allDay,
        Instant.fromEpochMilliseconds(AllDay.startMillis(LocalDate(2026, 10, first)) + hour * 3_600_000L),
        Instant.fromEpochMilliseconds(AllDay.endMillisExclusive(LocalDate(2026, 10, last))),
        LocalDate(2026, 10, first), LocalDate(2026, 10, last), "", 0, false,
    )

    @Test
    fun multiDayEventIsOneSegmentInOneLane() {
        val l = layoutWeek(week, listOf(ev("휴양림", 5, 9), ev("엔화", 4, 6)), maxLanes = 3)
        val bar = l.segments.single { it.occurrence.eventId == "휴양림" }
        assertEquals(1, bar.startCol)
        assertEquals(5, bar.endCol)
        // Longer span on top; the shorter one keeps one lane across its own days.
        assertEquals(0, bar.lane)
        assertEquals(1, l.segments.single { it.occurrence.eventId == "엔화" }.lane)
    }

    @Test
    fun segmentsAreClippedToTheWeekAndMarkedAsContinuing() {
        val l = layoutWeek(week, listOf(ev("여행", 2, 6), ev("출장", 9, 12)), maxLanes = 3)
        val a = l.segments.single { it.occurrence.eventId == "여행" }
        assertEquals(0, a.startCol); assertTrue(a.continuesBefore)
        val b = l.segments.single { it.occurrence.eventId == "출장" }
        assertEquals(6, b.endCol); assertTrue(b.continuesAfter)
    }

    @Test
    fun oneDayItemsTakeTwoLinesWhenFree() {
        val l = layoutWeek(week, listOf(ev("컬러링 신규 등록일", 8)), maxLanes = 3)
        assertEquals(2, l.segments.single().height)
    }

    @Test
    fun overflowIsCountedPerDay() {
        val items = listOf(ev("a", 5), ev("b", 5), ev("c", 5, allDay = false, hour = 9), ev("d", 5, allDay = false, hour = 11))
        val l = layoutWeek(week, items, maxLanes = 2)
        assertEquals(2, l.segments.size)
        assertEquals(listOf(0, 2, 0, 0, 0, 0, 0), l.hiddenPerDay)
    }

    @Test
    fun exdatesAreMerged() {
        val t = Instant.parse("2026-10-16T00:00:00Z")
        assertEquals("20261016T000000Z", mergeExdates(null, listOf(t)))
        assertEquals("20261001T000000Z,20261016T000000Z", mergeExdates("20261001T000000Z", listOf(t)))
        assertEquals("20261016T000000Z", mergeExdates("20261016T000000Z", listOf(t))) // no duplicate
        assertEquals("TZID=Asia/Seoul;20261001T090000,20261016T090000", mergeExdates("TZID=Asia/Seoul;20261001T090000", listOf(Instant.parse("2026-10-16T00:00:00Z"))))
        assertEquals(null, mergeExdates("", emptyList()))
    }
}
