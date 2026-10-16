package app.olo.calendar.model

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import kotlinx.datetime.plus

data class KoreanHoliday(val date: LocalDate, val name: String, val substitute: Boolean = false)

/**
 * South Korean public holidays (관공서의 공휴일에 관한 규정), computed so that
 * the month view does not depend on a holiday calendar being synced to the
 * phone (Samsung keeps its own; Google's is optional and often absent).
 *
 * Substitute holidays (대체공휴일):
 * - 설날·추석 연휴: when a day falls on Sunday or on another holiday (since 2014);
 * - 어린이날: Saturday, Sunday or another holiday (since 2014);
 * - 삼일절·광복절·개천절·한글날: Saturday or Sunday (since 2021-08-04);
 * - 부처님오신날·성탄절: Saturday or Sunday (since 2023-05-04).
 * The substitute is the first following day that is neither a weekend day nor a holiday.
 *
 * Election days and one-off temporary holidays are announced by the government,
 * so they come from [ANNOUNCED] and need adding as they are declared.
 */
object KoreanHolidays {
    private val ANNOUNCED = listOf(
        KoreanHoliday(LocalDate(2022, 3, 9), "대통령선거일"),
        KoreanHoliday(LocalDate(2022, 6, 1), "지방선거일"),
        KoreanHoliday(LocalDate(2023, 10, 2), "임시공휴일"),
        KoreanHoliday(LocalDate(2024, 4, 10), "국회의원선거일"),
        KoreanHoliday(LocalDate(2024, 10, 1), "국군의 날"),
        KoreanHoliday(LocalDate(2025, 1, 27), "임시공휴일"),
        KoreanHoliday(LocalDate(2025, 6, 3), "대통령선거일"),
        KoreanHoliday(LocalDate(2026, 6, 3), "지방선거일"),
    )

    private val cache = mutableMapOf<Int, List<KoreanHoliday>>()

    fun forYear(year: Int): List<KoreanHoliday> = cache.getOrPut(year) { compute(year) }

    fun on(date: LocalDate): List<KoreanHoliday> = forYear(date.year).filter { it.date == date }

    private enum class Rule { NONE, SUNDAY_OR_OVERLAP, WEEKEND_OR_OVERLAP }

    private class Base(val date: LocalDate, val name: String, val rule: Rule)

    private fun compute(year: Int): List<KoreanHoliday> {
        val base = mutableListOf<Base>()
        fun solar(m: Int, d: Int, name: String, rule: Rule) = base.add(Base(LocalDate(year, m, d), name, rule))

        val weekend2021: (LocalDate) -> Rule = { d -> if (d >= LocalDate(2021, 8, 4)) Rule.WEEKEND_OR_OVERLAP else Rule.NONE }
        val weekend2023: (LocalDate) -> Rule = { d -> if (d >= LocalDate(2023, 5, 4)) Rule.WEEKEND_OR_OVERLAP else Rule.NONE }
        val sunday2014 = if (year >= 2014) Rule.SUNDAY_OR_OVERLAP else Rule.NONE

        solar(1, 1, "신정", Rule.NONE)
        solar(3, 1, "삼일절", weekend2021(LocalDate(year, 3, 1)))
        solar(5, 5, "어린이날", if (year >= 2014) Rule.WEEKEND_OR_OVERLAP else Rule.NONE)
        solar(6, 6, "현충일", Rule.NONE)
        solar(8, 15, "광복절", weekend2021(LocalDate(year, 8, 15)))
        solar(10, 3, "개천절", weekend2021(LocalDate(year, 10, 3)))
        solar(10, 9, "한글날", weekend2021(LocalDate(year, 10, 9)))
        solar(12, 25, "성탄절", weekend2023(LocalDate(year, 12, 25)))

        // 설날: the last day of the previous lunar year, 1/1 and 1/2.
        KoreanLunar.toSolar(year, 1, 1)?.let { seollal ->
            base.add(Base(seollal.minus(1, DateTimeUnit.DAY), "설날 연휴", sunday2014))
            base.add(Base(seollal, "설날", sunday2014))
            base.add(Base(seollal.plus(1, DateTimeUnit.DAY), "설날 연휴", sunday2014))
        }
        KoreanLunar.toSolar(year, 4, 8)?.let { base.add(Base(it, "부처님오신날", weekend2023(it))) }
        KoreanLunar.toSolar(year, 8, 15)?.let { chuseok ->
            base.add(Base(chuseok.minus(1, DateTimeUnit.DAY), "추석 연휴", sunday2014))
            base.add(Base(chuseok, "추석", sunday2014))
            base.add(Base(chuseok.plus(1, DateTimeUnit.DAY), "추석 연휴", sunday2014))
        }
        val announced = ANNOUNCED.filter { it.date.year == year }

        val taken = (base.map { it.date } + announced.map { it.date }).toMutableSet()
        val subs = mutableListOf<KoreanHoliday>()
        // Per date, how many extra days off it creates; processed in date order so
        // an earlier substitute is taken before a later one looks for a free day.
        for ((date, here) in base.groupBy { it.date }.toSortedMap(compareBy { it.toEpochDays() })) {
            val dow = date.dayOfWeek
            val total = here.size + announced.count { it.date == date }
            val count = when (dow) {
                DayOfWeek.SUNDAY -> here.count { it.rule != Rule.NONE }
                DayOfWeek.SATURDAY -> here.count { it.rule == Rule.WEEKEND_OR_OVERLAP }
                // A weekday shared by two holidays loses one day off; give it back if a rule allows.
                else -> minOf(here.count { it.rule != Rule.NONE }, total - 1)
            }
            repeat(count) {
                var d = date.plus(1, DateTimeUnit.DAY)
                while (d in taken || d.dayOfWeek == DayOfWeek.SATURDAY || d.dayOfWeek == DayOfWeek.SUNDAY) d = d.plus(1, DateTimeUnit.DAY)
                taken += d
                subs += KoreanHoliday(d, "대체공휴일", substitute = true)
            }
        }
        return (base.map { KoreanHoliday(it.date, it.name) } + announced + subs)
            .filter { it.date.year == year }
            .sortedBy { it.date }
    }
}
