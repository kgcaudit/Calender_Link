package app.olo.calendar.model

import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.daysUntil
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime

enum class Frequency { DAILY, WEEKLY, MONTHLY, YEARLY }

sealed interface RecurrenceEnd {
    data object Never : RecurrenceEnd
    data class Count(val times: Int) : RecurrenceEnd
    /** Last day an occurrence may start on, inclusive. */
    data class Until(val lastDay: LocalDate) : RecurrenceEnd
}

/**
 * The repeat rules OLO edits: deliberately the subset an iPhone's
 * EKRecurrenceRule can express (one rule; daily / weekly on days / monthly on
 * the start's day / yearly; an interval; an end by count or date). Anything
 * richer that arrives from Google or Samsung is shown but left untouched.
 */
data class Recurrence(
    val frequency: Frequency,
    val interval: Int = 1,
    /** Weekly only; empty means "the start's weekday". */
    val days: Set<DayOfWeek> = emptySet(),
    val end: RecurrenceEnd = RecurrenceEnd.Never,
) {
    init {
        require(interval >= 1) { "interval must be >= 1" }
    }

    /**
     * RFC 5545 RRULE value (without the "RRULE:" prefix), as Android's
     * Events.RRULE and Google's recurrence[] expect it.
     *
     * UNTIL follows the start's value type: a DATE for all-day events, and for
     * timed events the end of that local day expressed in UTC.
     */
    fun toRRule(allDay: Boolean, zone: TimeZone): String {
        val parts = mutableListOf("FREQ=${frequency.name}")
        if (interval > 1) parts += "INTERVAL=$interval"
        if (frequency == Frequency.WEEKLY && days.isNotEmpty()) {
            parts += "BYDAY=" + DayOfWeek.entries.filter { it in days }.joinToString(",") { it.code }
        }
        when (val e = end) {
            RecurrenceEnd.Never -> Unit
            is RecurrenceEnd.Count -> parts += "COUNT=${e.times}"
            is RecurrenceEnd.Until -> parts += "UNTIL=" + if (allDay) {
                e.lastDay.basic()
            } else {
                val endOfDay = LocalDateTime(e.lastDay, LocalTime(23, 59, 59)).toInstant(zone)
                endOfDay.toLocalDateTime(TimeZone.UTC).let { "${it.date.basic()}T${pad(it.hour)}${pad(it.minute)}${pad(it.second)}Z" }
            }
        }
        return parts.joinToString(";")
    }

    companion object {
        /**
         * Reads a rule back into the editable subset, or returns null when it
         * holds anything OLO would lose on save (several rules, BYSETPOS, an
         * ordinal weekday like 2TU, hourly frequencies, and so on).
         */
        fun parse(rule: String?, start: LocalDate, zone: TimeZone): Recurrence? {
            if (rule.isNullOrBlank()) return null
            val lines = rule.trim().lines().filter { it.isNotBlank() }
            if (lines.size != 1) return null
            val body = lines[0].removePrefix("RRULE:")
            val map = mutableMapOf<String, String>()
            for (p in body.split(';')) {
                if (p.isBlank()) continue
                val kv = p.split('=', limit = 2)
                if (kv.size != 2) return null
                map[kv[0].uppercase()] = kv[1].uppercase()
            }
            val freq = when (map.remove("FREQ")) {
                "DAILY" -> Frequency.DAILY
                "WEEKLY" -> Frequency.WEEKLY
                "MONTHLY" -> Frequency.MONTHLY
                "YEARLY" -> Frequency.YEARLY
                else -> return null
            }
            val interval = map.remove("INTERVAL")?.toIntOrNull()?.takeIf { it >= 1 } ?: if ("INTERVAL" in map) return null else 1
            map.remove("WKST")

            var days = emptySet<DayOfWeek>()
            map.remove("BYDAY")?.let { v ->
                if (freq != Frequency.WEEKLY) return null
                days = v.split(',').map { code -> DayOfWeek.entries.firstOrNull { it.code == code } ?: return null }.toSet()
            }
            // Redundant restatements of the start date are fine (Google writes them).
            map.remove("BYMONTHDAY")?.let { if (freq !in setOf(Frequency.MONTHLY, Frequency.YEARLY) || it != start.dayOfMonth.toString()) return null }
            map.remove("BYMONTH")?.let { if (freq != Frequency.YEARLY || it != start.monthNumber.toString()) return null }

            val count = map.remove("COUNT")
            val until = map.remove("UNTIL")
            if (map.isNotEmpty()) return null
            val end = when {
                count != null && until != null -> return null
                count != null -> RecurrenceEnd.Count(count.toIntOrNull()?.takeIf { it > 0 } ?: return null)
                until != null -> RecurrenceEnd.Until(parseUntil(until, zone) ?: return null)
                else -> RecurrenceEnd.Never
            }
            return Recurrence(freq, interval, days, end)
        }

        private fun parseUntil(v: String, zone: TimeZone): LocalDate? {
            if (v.length < 8) return null
            val date = runCatching {
                LocalDate(v.substring(0, 4).toInt(), v.substring(4, 6).toInt(), v.substring(6, 8).toInt())
            }.getOrNull() ?: return null
            if (v.length == 8) return date
            // yyyyMMddTHHmmss[Z]: a UTC instant (or floating); report its local day.
            val t = runCatching {
                LocalTime(v.substring(9, 11).toInt(), v.substring(11, 13).toInt(), v.substring(13, 15).toInt())
            }.getOrNull() ?: return null
            val ldt = LocalDateTime(date, t)
            return if (v.endsWith("Z")) ldt.toInstant(TimeZone.UTC).toLocalDateTime(zone).date else date
        }
    }
}

private val DayOfWeek.code: String
    get() = when (this) {
        DayOfWeek.MONDAY -> "MO"
        DayOfWeek.TUESDAY -> "TU"
        DayOfWeek.WEDNESDAY -> "WE"
        DayOfWeek.THURSDAY -> "TH"
        DayOfWeek.FRIDAY -> "FR"
        DayOfWeek.SATURDAY -> "SA"
        DayOfWeek.SUNDAY -> "SU"
    }

private fun pad(n: Int) = n.toString().padStart(2, '0')
private fun LocalDate.basic() = "${year.toString().padStart(4, '0')}${pad(monthNumber)}${pad(dayOfMonth)}"

/** Korean one-line description, e.g. "2주마다 월·수요일, 10회". */
fun Recurrence.describeKo(start: LocalDate): String {
    val base = when (frequency) {
        Frequency.DAILY -> if (interval == 1) "매일" else "${interval}일마다"
        Frequency.WEEKLY -> {
            val ds = (days.ifEmpty { setOf(start.dayOfWeek) })
            val names = DayOfWeek.entries.filter { it in ds }.joinToString("·") { it.koShort() }
            (if (interval == 1) "매주 " else "${interval}주마다 ") + names + "요일"
        }
        Frequency.MONTHLY -> (if (interval == 1) "매월 " else "${interval}개월마다 ") + "${start.dayOfMonth}일"
        Frequency.YEARLY -> (if (interval == 1) "매년 " else "${interval}년마다 ") + "${start.monthNumber}월 ${start.dayOfMonth}일"
    }
    return when (val e = end) {
        RecurrenceEnd.Never -> base
        is RecurrenceEnd.Count -> "$base, ${e.times}회"
        is RecurrenceEnd.Until -> "$base, ${e.lastDay.koDate()}까지"
    }
}

fun DayOfWeek.koShort(): String = when (this) {
    DayOfWeek.MONDAY -> "월"
    DayOfWeek.TUESDAY -> "화"
    DayOfWeek.WEDNESDAY -> "수"
    DayOfWeek.THURSDAY -> "목"
    DayOfWeek.FRIDAY -> "금"
    DayOfWeek.SATURDAY -> "토"
    DayOfWeek.SUNDAY -> "일"
}

/** "2026. 10. 16." — the Korean document date style the user's other tools use. */
fun LocalDate.koDate(): String = "$year. $monthNumber. $dayOfMonth."

/** Android all-day DURATION ("P1D", "P3D") or timed DURATION in seconds ("P3600S"). */
fun androidDuration(allDay: Boolean, startDay: LocalDate, endDayInclusive: LocalDate, seconds: Long): String =
    if (allDay) {
        "P${maxOf(1, startDay.daysUntil(endDayInclusive) + 1)}D"
    } else {
        "P${maxOf(0, seconds)}S"
    }
