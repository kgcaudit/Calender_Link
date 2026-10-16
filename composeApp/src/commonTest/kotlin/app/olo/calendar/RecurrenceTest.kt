package app.olo.calendar

import app.olo.calendar.model.Frequency
import app.olo.calendar.model.Recurrence
import app.olo.calendar.model.RecurrenceEnd
import app.olo.calendar.model.androidDuration
import app.olo.calendar.model.describeKo
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RecurrenceTest {
    private val seoul = TimeZone.of("Asia/Seoul")
    private val start = LocalDate(2026, 10, 16) // a Friday

    @Test
    fun weeklyOnDaysWritesDaysInWeekOrder() {
        val r = Recurrence(Frequency.WEEKLY, days = setOf(DayOfWeek.WEDNESDAY, DayOfWeek.MONDAY))
        assertEquals("FREQ=WEEKLY;BYDAY=MO,WE", r.toRRule(false, seoul))
    }

    @Test
    fun intervalAndCount() {
        assertEquals("FREQ=DAILY;INTERVAL=2;COUNT=10", Recurrence(Frequency.DAILY, 2, end = RecurrenceEnd.Count(10)).toRRule(false, seoul))
    }

    @Test
    fun untilIsADateForAllDayEvents() {
        val r = Recurrence(Frequency.MONTHLY, end = RecurrenceEnd.Until(LocalDate(2026, 12, 31)))
        assertEquals("FREQ=MONTHLY;UNTIL=20261231", r.toRRule(true, seoul))
    }

    @Test
    fun untilIsEndOfLocalDayInUtcForTimedEvents() {
        val r = Recurrence(Frequency.WEEKLY, end = RecurrenceEnd.Until(LocalDate(2026, 12, 31)))
        // 23:59:59 in Seoul (UTC+9) is 14:59:59Z the same day.
        assertEquals("FREQ=WEEKLY;UNTIL=20261231T145959Z", r.toRRule(false, seoul))
    }

    @Test
    fun parsesWhatItWrites() {
        val cases = listOf(
            Recurrence(Frequency.DAILY),
            Recurrence(Frequency.WEEKLY, 2, setOf(DayOfWeek.MONDAY, DayOfWeek.FRIDAY), RecurrenceEnd.Count(8)),
            Recurrence(Frequency.MONTHLY, end = RecurrenceEnd.Until(LocalDate(2027, 3, 1))),
            Recurrence(Frequency.YEARLY),
        )
        for (r in cases) {
            for (allDay in listOf(true, false)) {
                assertEquals(r, Recurrence.parse(r.toRRule(allDay, seoul), start, seoul), "round trip of $r allDay=$allDay")
            }
        }
    }

    @Test
    fun acceptsGoogleStyleRestatements() {
        assertEquals(
            Recurrence(Frequency.MONTHLY),
            Recurrence.parse("RRULE:FREQ=MONTHLY;BYMONTHDAY=16;WKST=SU", start, seoul),
        )
        assertEquals(
            Recurrence(Frequency.YEARLY),
            Recurrence.parse("FREQ=YEARLY;BYMONTH=10;BYMONTHDAY=16", start, seoul),
        )
    }

    @Test
    fun refusesRulesOloWouldFlatten() {
        assertNull(Recurrence.parse("FREQ=MONTHLY;BYDAY=2TU", start, seoul))          // ordinal weekday
        assertNull(Recurrence.parse("FREQ=MONTHLY;BYMONTHDAY=1,15", start, seoul))   // two days
        assertNull(Recurrence.parse("FREQ=HOURLY", start, seoul))
        assertNull(Recurrence.parse("FREQ=WEEKLY;BYSETPOS=1", start, seoul))
        assertNull(Recurrence.parse("RRULE:FREQ=DAILY\nRRULE:FREQ=WEEKLY", start, seoul))
        assertNull(Recurrence.parse("FREQ=DAILY;COUNT=3;UNTIL=20261231", start, seoul))
    }

    @Test
    fun emptyMeansNoRepeat() {
        assertNull(Recurrence.parse(null, start, seoul))
        assertNull(Recurrence.parse("", start, seoul))
    }

    @Test
    fun koreanDescriptions() {
        assertEquals("매주 금요일", Recurrence(Frequency.WEEKLY).describeKo(start))
        assertEquals("2주마다 월·수요일, 10회", Recurrence(Frequency.WEEKLY, 2, setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY), RecurrenceEnd.Count(10)).describeKo(start))
        assertEquals("매월 16일, 2026. 12. 31.까지", Recurrence(Frequency.MONTHLY, end = RecurrenceEnd.Until(LocalDate(2026, 12, 31))).describeKo(start))
        assertEquals("매년 10월 16일", Recurrence(Frequency.YEARLY).describeKo(start))
    }

    @Test
    fun androidDurations() {
        assertEquals("P1D", androidDuration(true, start, start, 0))
        assertEquals("P3D", androidDuration(true, start, LocalDate(2026, 10, 18), 0))
        assertEquals("P3600S", androidDuration(false, start, start, 3600))
    }
}
