package app.olo.calendar.model

import kotlinx.datetime.LocalDate

data class LunarDate(val year: Int, val month: Int, val day: Int, val leap: Boolean = false) {
    /** "9.1", or "윤2.1" in a leap month — the style Samsung Calendar uses. */
    fun label(): String = (if (leap) "윤" else "") + "$month.$day"

    /** "음력 9월 6일" / "음력 윤2월 1일" for headers. */
    fun longLabel(): String = "음력 " + (if (leap) "윤" else "") + "${month}월 ${day}일"
}

/**
 * Korean lunar calendar (음력), from a table generated off ICU's dangi calendar.
 *
 * Korean and Chinese lunar dates differ in some months (Korea counts new moons
 * at UTC+9, China at UTC+8): 2026-10-11 is 9.1 in Korea but 9.2 in China. So
 * this never falls back to a "chinese" calendar.
 */
object KoreanLunar {
    private val data = KoreanLunarTable.DATA
    private const val FIRST = KoreanLunarTable.FIRST_YEAR
    private const val LAST = KoreanLunarTable.LAST_YEAR

    private fun newYear(i: Int) = data[i * 3]
    private fun leapMonth(i: Int) = data[i * 3 + 1]
    private fun bits(i: Int) = data[i * 3 + 2]
    private fun monthCount(i: Int) = if (leapMonth(i) == 0) 12 else 13
    private fun length(i: Int, slot: Int) = if (bits(i) and (1 shl slot) != 0) 30 else 29

    fun fromSolar(date: LocalDate): LunarDate? {
        val day = date.toEpochDays()
        val years = LAST - FIRST + 1
        if (day < newYear(0)) return null
        // Last year whose 1/1 is on or before the date.
        var lo = 0
        var hi = years - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (newYear(mid) <= day) lo = mid else hi = mid - 1
        }
        var offset = day - newYear(lo)
        val leap = leapMonth(lo)
        for (slot in 0 until monthCount(lo)) {
            val len = length(lo, slot)
            if (offset < len) {
                val (month, isLeap) = slotToMonth(slot, leap)
                return LunarDate(FIRST + lo, month, offset + 1, isLeap)
            }
            offset -= len
        }
        return null // past the end of the table
    }

    fun toSolar(year: Int, month: Int, day: Int, leap: Boolean = false): LocalDate? {
        val i = year - FIRST
        if (i < 0 || year > LAST || month !in 1..12) return null
        val lm = leapMonth(i)
        if (leap && lm != month) return null
        val slot = monthToSlot(month, leap, lm)
        if (day < 1 || day > length(i, slot)) return null
        var d = newYear(i)
        for (s in 0 until slot) d += length(i, s)
        return LocalDate.fromEpochDays(d + day - 1)
    }

    /** Days in a lunar month (29 or 30), or null outside the table. */
    fun monthLength(year: Int, month: Int, leap: Boolean = false): Int? {
        val i = year - FIRST
        if (i < 0 || year > LAST) return null
        val lm = leapMonth(i)
        if (leap && lm != month) return null
        return length(i, monthToSlot(month, leap, lm))
    }

    private fun slotToMonth(slot: Int, leap: Int): Pair<Int, Boolean> = when {
        leap == 0 || slot < leap -> (slot + 1) to false
        slot == leap -> leap to true
        else -> slot to false
    }

    private fun monthToSlot(month: Int, isLeap: Boolean, leap: Int): Int = when {
        leap == 0 || month < leap -> month - 1
        month == leap && !isLeap -> month - 1
        month == leap && isLeap -> month
        else -> month
    }
}
