package app.olo.calendar.model

import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate

/** One day of the month widget's grid (docs/09 §3 W2). */
data class MonthCell(
    val date: LocalDate,
    val inMonth: Boolean,
    val isToday: Boolean,
    /** Sunday or a holiday: a red number. */
    val red: Boolean,
    /** Colours of the day's first events (at most [MONTH_DOTS]), all-day ones first. */
    val dots: List<Int>,
    /** Events beyond the dots. */
    val more: Int,
    /** "9.15" on lunar 1st and 15th, when the app shows lunar dates. */
    val lunar: String?,
)

const val MONTH_DOTS = 3

/**
 * The month widget's weeks: the app's month grid ([monthGrid]), each day with its
 * dots. What counts as an event and a holiday is the app's rule ([shownOccurrences],
 * [holidayNamesOn]): holiday-calendar entries redden the number instead of adding a dot.
 */
fun monthWidgetWeeks(
    month: YearMonth,
    weekStart: DayOfWeek,
    shown: List<Occurrence>,
    holidayCalendars: Set<String>,
    today: LocalDate,
    showLunar: Boolean,
): List<List<MonthCell>> = monthGrid(month, weekStart).map { date ->
    val events = shown.on(date).filter { it.calendarId !in holidayCalendars }.sortedForDay()
    val lunar = if (showLunar) KoreanLunar.fromSolar(date)?.takeIf { it.day == 1 || it.day == 15 }?.label() else null
    MonthCell(
        date = date,
        inMonth = date.monthNumber == month.month,
        isToday = date == today,
        red = date.dayOfWeek == DayOfWeek.SUNDAY || holidayNamesOn(date, shown, holidayCalendars).isNotEmpty(),
        dots = events.take(MONTH_DOTS).map { it.colorArgb },
        more = (events.size - MONTH_DOTS).coerceAtLeast(0),
        lunar = lunar,
    )
}.chunked(7)
