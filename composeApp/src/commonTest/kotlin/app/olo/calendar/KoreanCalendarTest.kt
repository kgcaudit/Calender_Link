package app.olo.calendar

import app.olo.calendar.model.KoreanHolidays
import app.olo.calendar.model.KoreanLunar
import app.olo.calendar.model.LunarDate
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class KoreanCalendarTest {
    @Test
    fun lunarLabelsMatchSamsungCalendar() {
        // Sunday labels in Samsung Calendar, October 2026 (user's phone).
        assertEquals("8.17", KoreanLunar.fromSolar(LocalDate(2026, 9, 27))!!.label())
        assertEquals("8.24", KoreanLunar.fromSolar(LocalDate(2026, 10, 4))!!.label())
        assertEquals("9.1", KoreanLunar.fromSolar(LocalDate(2026, 10, 11))!!.label())
        assertEquals("9.8", KoreanLunar.fromSolar(LocalDate(2026, 10, 18))!!.label())
        assertEquals("9.15", KoreanLunar.fromSolar(LocalDate(2026, 10, 25))!!.label())
    }

    @Test
    fun koreanNotChineseLunar() {
        // China starts this month a day later (UTC+8): 2026-10-10 is 8.30 in Korea.
        assertEquals(LunarDate(2026, 8, 30), KoreanLunar.fromSolar(LocalDate(2026, 10, 10)))
    }

    @Test
    fun publishedSeollalAndChuseok() {
        val seollal = mapOf(2024 to LocalDate(2024, 2, 10), 2025 to LocalDate(2025, 1, 29), 2026 to LocalDate(2026, 2, 17), 2027 to LocalDate(2027, 2, 7))
        val chuseok = mapOf(2024 to LocalDate(2024, 9, 17), 2025 to LocalDate(2025, 10, 6), 2026 to LocalDate(2026, 9, 25), 2027 to LocalDate(2027, 9, 15))
        seollal.forEach { (y, d) -> assertEquals(d, KoreanLunar.toSolar(y, 1, 1), "설날 $y") }
        chuseok.forEach { (y, d) -> assertEquals(d, KoreanLunar.toSolar(y, 8, 15), "추석 $y") }
    }

    @Test
    fun leapMonthRoundTrip() {
        // 2023 had a leap 2nd month (윤2월), starting 2023-03-22.
        val d = LocalDate(2023, 3, 22)
        assertEquals(LunarDate(2023, 2, 1, leap = true), KoreanLunar.fromSolar(d))
        assertEquals("윤2.1", KoreanLunar.fromSolar(d)!!.label())
        assertEquals(d, KoreanLunar.toSolar(2023, 2, 1, leap = true))
        assertEquals(LocalDate(2023, 4, 20), KoreanLunar.toSolar(2023, 3, 1))
        assertNull(KoreanLunar.toSolar(2024, 2, 1, leap = true)) // no leap month that year
    }

    @Test
    fun roundTripsEveryDayFor2000To2050() {
        var d = LocalDate(2000, 2, 5)
        val end = LocalDate(2050, 12, 31)
        while (d <= end) {
            val l = KoreanLunar.fromSolar(d)!!
            assertEquals(d, KoreanLunar.toSolar(l.year, l.month, l.day, l.leap), "round trip $d -> $l")
            d = LocalDate.fromEpochDays(d.toEpochDays() + 1)
        }
    }

    private fun holidays(y: Int) = KoreanHolidays.forYear(y).map { it.date to it.name }.toSet()
    private fun subs(y: Int) = KoreanHolidays.forYear(y).filter { it.substitute }.map { it.date }

    @Test
    fun holidays2026() {
        val h = holidays(2026)
        listOf(
            LocalDate(2026, 1, 1) to "신정",
            LocalDate(2026, 2, 16) to "설날 연휴", LocalDate(2026, 2, 17) to "설날", LocalDate(2026, 2, 18) to "설날 연휴",
            LocalDate(2026, 3, 1) to "삼일절", LocalDate(2026, 5, 5) to "어린이날", LocalDate(2026, 5, 24) to "부처님오신날",
            LocalDate(2026, 6, 3) to "지방선거일", LocalDate(2026, 6, 6) to "현충일", LocalDate(2026, 8, 15) to "광복절",
            LocalDate(2026, 9, 24) to "추석 연휴", LocalDate(2026, 9, 25) to "추석", LocalDate(2026, 9, 26) to "추석 연휴",
            LocalDate(2026, 10, 3) to "개천절", LocalDate(2026, 10, 9) to "한글날", LocalDate(2026, 12, 25) to "성탄절",
        ).forEach { assert(it in h) { "missing $it" } }
        // 3/1 Sun, 부처님오신날 5/24 Sun, 8/15 Sat, 10/3 Sat (Samsung shows 10/5 red).
        assertEquals(
            listOf(LocalDate(2026, 3, 2), LocalDate(2026, 5, 25), LocalDate(2026, 8, 17), LocalDate(2026, 10, 5)),
            subs(2026),
        )
    }

    @Test
    fun substitutes2025And2024() {
        // 2025: 3/1 Sat -> 3/3; 어린이날 = 부처님오신날 on 5/5 -> 5/6; 추석 10/5(Sun)-7 -> 10/8.
        assertEquals(listOf(LocalDate(2025, 3, 3), LocalDate(2025, 5, 6), LocalDate(2025, 10, 8)), subs(2025))
        // 2024: 설날 연휴 2/9-11 (11th Sun... 2/11 Sun) -> 2/12; 어린이날 5/5 Sun -> 5/6.
        assertEquals(listOf(LocalDate(2024, 2, 12), LocalDate(2024, 5, 6)), subs(2024))
    }
}
