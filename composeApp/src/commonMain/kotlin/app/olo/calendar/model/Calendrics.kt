package app.olo.calendar.model

import kotlinx.datetime.DatePeriod
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.daysUntil
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime

data class YearMonth(val year: Int, val month: Int) {
    init { require(month in 1..12) }

    val firstDay: LocalDate get() = LocalDate(year, month, 1)
    fun plusMonths(n: Int): YearMonth = firstDay.plus(DatePeriod(months = n)).let { YearMonth(it.year, it.monthNumber) }
    fun koTitle(): String = "${year}년 ${month}월"
    val length: Int get() = plusMonths(1).firstDay.plus(-1, DateTimeUnit.DAY).dayOfMonth
    /** Months since year 0: a pager page index. */
    val index: Int get() = year * 12 + month - 1

    companion object {
        fun of(d: LocalDate) = YearMonth(d.year, d.monthNumber)
        fun ofIndex(i: Int) = YearMonth(i / 12, i % 12 + 1)
    }
}

/**
 * The days a month view shows, whole weeks from [weekStart] (Sunday in Korea):
 * as many weeks as the month needs (4–6), like Samsung Calendar, so a 5-week
 * month gives each row more room instead of drawing next month's first week.
 */
fun monthGrid(month: YearMonth, weekStart: DayOfWeek = DayOfWeek.SUNDAY): List<LocalDate> {
    val first = month.firstDay
    val back = (first.dayOfWeek.isoDayNumber - weekStart.isoDayNumber + 7) % 7
    val gridStart = first.minus(back, DateTimeUnit.DAY)
    val last = month.plusMonths(1).firstDay.minus(1, DateTimeUnit.DAY)
    val days = gridStart.daysUntil(last) + 1
    val weeks = (days + 6) / 7
    return List(weeks * 7) { gridStart.plus(it, DateTimeUnit.DAY) }
}

/** The first day of the week containing [date]. */
fun weekStartOf(date: LocalDate, weekStart: DayOfWeek = DayOfWeek.SUNDAY): LocalDate {
    val back = (date.dayOfWeek.isoDayNumber - weekStart.isoDayNumber + 7) % 7
    return date.minus(back, DateTimeUnit.DAY)
}

/** ISO-8601 week number (weeks start Monday; week 1 holds the year's first Thursday). */
fun isoWeekNumber(date: LocalDate): Int {
    val thursday = date.plus(4 - date.dayOfWeek.isoDayNumber, DateTimeUnit.DAY)
    val jan1 = LocalDate(thursday.year, 1, 1)
    return jan1.daysUntil(thursday) / 7 + 1
}

/** Days of the week in grid order, for the header row. */
fun weekHeader(weekStart: DayOfWeek = DayOfWeek.SUNDAY): List<DayOfWeek> =
    List(7) { DayOfWeek.entries[(weekStart.ordinal + it) % 7] }

/**
 * All-day events on Android must be stored at UTC midnight with zone "UTC"
 * (CalendarContract.Events). End is exclusive: a one-day event on the 16th
 * runs 16th 00:00Z → 17th 00:00Z.
 */
object AllDay {
    fun startMillis(first: LocalDate): Long = first.atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds()
    fun endMillisExclusive(lastInclusive: LocalDate): Long =
        lastInclusive.plus(1, DateTimeUnit.DAY).atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds()

    fun firstDay(startMillis: Long): LocalDate =
        Instant.fromEpochMilliseconds(startMillis).toLocalDateTime(TimeZone.UTC).date

    /** Inclusive last day; a zero-length or malformed span still covers its first day. */
    fun lastDay(startMillis: Long, endMillisExclusive: Long): LocalDate {
        val first = firstDay(startMillis)
        if (endMillisExclusive <= startMillis) return first
        val endDay = Instant.fromEpochMilliseconds(endMillisExclusive).toLocalDateTime(TimeZone.UTC).date
        val last = endDay.minus(1, DateTimeUnit.DAY)
        return if (last < first) first else last
    }
}

/** Local days a timed span covers. An event ending exactly at midnight does not spill into the next day. */
fun timedDays(begin: Instant, end: Instant, zone: TimeZone): Pair<LocalDate, LocalDate> {
    val first = begin.toLocalDateTime(zone).date
    if (end <= begin) return first to first
    val endLocal = end.toLocalDateTime(zone)
    val last = if (endLocal.time == LocalTime(0, 0)) endLocal.date.minus(1, DateTimeUnit.DAY) else endLocal.date
    return first to (if (last < first) first else last)
}

fun LocalDate.at(time: LocalTime, zone: TimeZone): Instant = LocalDateTime(this, time).toInstant(zone)

/**
 * Sorts a day's list the way people read one: all-day first, then by start,
 * longer first on ties so a block contains what falls inside it.
 */
fun List<Occurrence>.sortedForDay(): List<Occurrence> =
    sortedWith(compareByDescending<Occurrence> { it.allDay }.thenBy { it.begin }.thenByDescending { it.end })

fun List<Occurrence>.on(day: LocalDate): List<Occurrence> = filter { day >= it.firstDay && day <= it.lastDay }

/**
 * Classifies an Android account type (CalendarContract.Calendars.ACCOUNT_TYPE).
 * Only com.google reaches the iPhone. Samsung's "My calendar" and Android's
 * LOCAL are device-only, which is the most common reason an event "does not sync".
 */
fun classifyAndroidAccount(accountType: String?): SyncKind {
    val t = accountType.orEmpty()
    return when {
        t == "com.google" -> SyncKind.GOOGLE
        t.equals("LOCAL", ignoreCase = true) -> SyncKind.DEVICE
        t == "com.osp.app.signin" -> SyncKind.SAMSUNG
        t.contains("exchange", ignoreCase = true) || t.contains("outlook", ignoreCase = true) -> SyncKind.EXCHANGE
        t.contains("ical", ignoreCase = true) || t.contains("subscri", ignoreCase = true) -> SyncKind.SUBSCRIBED
        else -> SyncKind.OTHER
    }
}

/** Google's public holiday calendars, e.g. ko.south_korea#holiday@group.v.calendar.google.com. */
fun isGoogleHolidayCalendarId(ownerOrId: String?): Boolean =
    ownerOrId.orEmpty().endsWith("#holiday@group.v.calendar.google.com")

fun SyncKind.koLabel(): String = when (this) {
    SyncKind.GOOGLE -> "Google 동기화"
    SyncKind.DEVICE -> "이 기기만"
    SyncKind.SAMSUNG -> "삼성 계정"
    SyncKind.ICLOUD -> "iCloud"
    SyncKind.EXCHANGE -> "Exchange"
    SyncKind.SUBSCRIBED -> "구독"
    SyncKind.OTHER -> "기타 계정"
}
