package app.olo.calendar

import app.olo.calendar.model.QuickAdd
import app.olo.calendar.model.QuickEntry
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals

class QuickAddTest {
    private val today = LocalDate(2026, 10, 16) // Friday
    private fun p(s: String, base: LocalDate = today) = QuickAdd.parse(s, base, today)

    @Test
    fun relativeDayAndAfternoonHour() {
        assertEquals(QuickEntry("회의", LocalDate(2026, 10, 17), LocalTime(15, 0), LocalTime(16, 0)), p("내일 오후 3시 회의"))
        assertEquals(QuickEntry("회의", LocalDate(2026, 10, 17), LocalTime(15, 0), LocalTime(16, 0)), p("내일 3시에 회의"))
    }

    @Test
    fun monthDayHalfHour() {
        assertEquals(QuickEntry("병원", LocalDate(2026, 10, 20), LocalTime(14, 30), LocalTime(15, 30)), p("10월 20일 2시 반 병원"))
    }

    @Test
    fun weekdaysThisAndNextWeek() {
        assertEquals(LocalDate(2026, 10, 16), p("금요일 저녁 7시 동창회").date) // today counts
        assertEquals(LocalTime(19, 0), p("금요일 저녁 7시 동창회").start)
        assertEquals("동창회", p("금요일 저녁 7시 동창회").title)
        assertEquals(LocalDate(2026, 10, 19), p("월요일 보고").date)
        assertEquals(LocalDate(2026, 10, 20), p("다음주 화요일 미팅").date)
        assertEquals(LocalDate(2026, 10, 14), p("이번주 수요일 정리").date)
    }

    @Test
    fun rangesAndColonTimes() {
        val e = p("다음주 화요일 10:30~12시 워크숍")
        assertEquals(LocalTime(10, 30), e.start)
        assertEquals(LocalTime(12, 0), e.end)
        assertEquals("워크숍", e.title)
        val f = p("3시부터 5시까지 교육")
        assertEquals(LocalTime(15, 0), f.start)
        assertEquals(LocalTime(17, 0), f.end)
        assertEquals("교육", f.title)
    }

    @Test
    fun passedDatesRollForward() {
        assertEquals(LocalDate(2027, 3, 1), p("3월 1일 등산").date)
        assertEquals(LocalDate(2026, 11, 5), p("5일 납부").date)
        assertEquals(LocalDate(2026, 12, 25), p("12/25 파티").date)
    }

    @Test
    fun noDateUsesSelectedDayAndNoTimeIsAllDay() {
        val base = LocalDate(2026, 10, 22)
        val e = p("심폐소생술 교육", base)
        assertEquals(QuickEntry("심폐소생술 교육", base, null, null), e)
        assertEquals("새 일정", p("내일").title)
    }

    @Test
    fun morningWordsAndNoon() {
        assertEquals(LocalTime(9, 0), p("오전 9시 조회").start)
        assertEquals(LocalTime(12, 0), p("12시 점심").start)
        assertEquals(LocalTime(13, 0), p("점심 1시 약속").start)
        assertEquals(LocalTime(8, 0), p("8시 출발").start)
    }
}
