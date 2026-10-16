package app.olo.calendar

import app.olo.calendar.design.OloHue
import app.olo.calendar.design.oloHueFor
import app.olo.calendar.model.AllDay
import app.olo.calendar.model.SyncKind
import app.olo.calendar.model.YearMonth
import app.olo.calendar.model.classifyAndroidAccount
import app.olo.calendar.model.isGoogleHolidayCalendarId
import app.olo.calendar.model.monthGrid
import app.olo.calendar.model.timedDays
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CalendricsTest {
    private val seoul = TimeZone.of("Asia/Seoul")
    private val la = TimeZone.of("America/Los_Angeles")

    @Test
    fun monthGridUsesOnlyTheWeeksTheMonthNeeds() {
        val g = monthGrid(YearMonth(2026, 10)) // Oct 1 is a Thursday, Oct 31 a Saturday
        assertEquals(35, g.size)                // 5 weeks, as Samsung Calendar shows it
        assertEquals(LocalDate(2026, 9, 27), g.first())
        assertEquals(DayOfWeek.SUNDAY, g.first().dayOfWeek)
        assertEquals(LocalDate(2026, 10, 31), g.last())
    }

    @Test
    fun monthGridSixAndFourWeeks() {
        assertEquals(42, monthGrid(YearMonth(2026, 8)).size) // Aug 1 Sat .. Aug 31 Mon
        assertEquals(28, monthGrid(YearMonth(2026, 2)).size) // Feb 1 Sun .. Feb 28 Sat
        assertEquals(LocalDate(2026, 2, 1), monthGrid(YearMonth(2026, 2)).first())
    }

    @Test
    fun yearMonthWrapsAcrossYears() {
        assertEquals(YearMonth(2027, 1), YearMonth(2026, 12).plusMonths(1))
        assertEquals(YearMonth(2025, 12), YearMonth(2026, 1).plusMonths(-1))
    }

    @Test
    fun allDayRoundTripsThroughUtcMidnight() {
        val d = LocalDate(2026, 10, 16)
        val start = AllDay.startMillis(d)
        val end = AllDay.endMillisExclusive(d)
        assertEquals(86_400_000L, end - start)
        assertEquals(d, AllDay.firstDay(start))
        assertEquals(d, AllDay.lastDay(start, end))
    }

    @Test
    fun multiDayAllDayKeepsItsLastDay() {
        val a = LocalDate(2026, 10, 3)
        val b = LocalDate(2026, 10, 5)
        assertEquals(b, AllDay.lastDay(AllDay.startMillis(a), AllDay.endMillisExclusive(b)))
    }

    @Test
    fun timedEventEndingAtMidnightStaysOnItsDay() {
        val begin = LocalDateTime(2026, 10, 16, 22, 0).toInstant(seoul)
        val end = LocalDateTime(2026, 10, 17, 0, 0).toInstant(seoul)
        assertEquals(LocalDate(2026, 10, 16) to LocalDate(2026, 10, 16), timedDays(begin, end, seoul))
    }

    @Test
    fun timedEventDaysFollowTheDeviceZone() {
        val begin = LocalDateTime(2026, 10, 16, 9, 0).toInstant(seoul) // 15th, 17:00 in LA
        val end = LocalDateTime(2026, 10, 16, 10, 0).toInstant(seoul)
        assertEquals(LocalDate(2026, 10, 15), timedDays(begin, end, la).first)
    }

    @Test
    fun accountTypes() {
        assertEquals(SyncKind.GOOGLE, classifyAndroidAccount("com.google"))
        assertEquals(SyncKind.DEVICE, classifyAndroidAccount("LOCAL"))
        assertEquals(SyncKind.DEVICE, classifyAndroidAccount("local"))
        assertEquals(SyncKind.SAMSUNG, classifyAndroidAccount("com.osp.app.signin"))
        assertEquals(SyncKind.EXCHANGE, classifyAndroidAccount("com.samsung.android.exchange"))
        assertEquals(SyncKind.OTHER, classifyAndroidAccount(null))
        assertTrue(SyncKind.GOOGLE.reachesGoogle)
        assertFalse(SyncKind.DEVICE.reachesGoogle)
    }

    @Test
    fun holidayCalendarIds() {
        assertTrue(isGoogleHolidayCalendarId("ko.south_korea#holiday@group.v.calendar.google.com"))
        assertFalse(isGoogleHolidayCalendarId("someone@gmail.com"))
    }

    @Test
    fun calendarColoursMapToOloHues() {
        assertEquals(OloHue.FOLDER, oloHueFor(0xFFB95B3B.toInt()))     // clay itself
        assertEquals(OloHue.DOCUMENT, oloHueFor(0xFF4285F4.toInt()))   // Google blue -> slate
        assertEquals(OloHue.IMAGE, oloHueFor(0xFF0B8043.toInt()))      // Google "Basil" green
        assertEquals(OloHue.AUDIO, oloHueFor(0xFFD81B60.toInt()))      // Google "Flamingo/Radicchio"
        assertEquals(OloHue.OTHER, oloHueFor(0xFF616161.toInt()))      // Google "Graphite"
    }
}
