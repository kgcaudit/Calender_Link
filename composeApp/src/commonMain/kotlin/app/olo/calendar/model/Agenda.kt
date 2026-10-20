package app.olo.calendar.model

import app.olo.calendar.design.OloHue
import app.olo.calendar.design.argb
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime

/**
 * The occurrences OLO draws (app and widget alike): hidden calendars out, a
 * bridged pair shown once (the Samsung copy, unless Samsung Calendar is hidden),
 * OLO-chosen colours in.
 */
fun shownOccurrences(
    all: List<Occurrence>,
    calendars: List<CalendarInfo>,
    settings: AppSettings,
    bridgedGoogle: Set<String> = emptySet(),
): List<Occurrence> {
    val hidden = calendars.filter { !settings.isShown(it) }.map { it.id }.toSet()
    val samsungShown = settings.bridgeSamsungId?.let { id -> id !in hidden } == true
    val twins = if (samsungShown) bridgedGoogle else emptySet()
    val shown = if (hidden.isEmpty() && twins.isEmpty()) all else all.filter { it.calendarId !in hidden && it.seriesId !in twins }
    if (settings.hues.isEmpty()) return shown
    return shown.map { o -> settings.hueOf(o.calendarId)?.let { o.copy(colorArgb = it.argb) } ?: o }
}

/** The hue OLO paints a calendar in, when the person chose one (docs/06 §4). */
fun AppSettings.hueOf(id: String): OloHue? = hues[id]?.let { n -> OloHue.entries.firstOrNull { it.name == n } }

/** Holiday names for a day: the built-in Korean list plus any shown holiday calendar, without repeats. */
fun holidayNamesOn(day: LocalDate, occurrences: List<Occurrence>, holidayCalendars: Set<String>): List<String> =
    (KoreanHolidays.on(day).map { it.name } + occurrences.on(day).filter { it.calendarId in holidayCalendars }.map { it.title })
        .map { it.trim() }.filter { it.isNotEmpty() }.distinct()

/** One day of the widget's list (docs/09 §3 W1). */
data class AgendaDay(val date: LocalDate, val holidays: List<String>, val items: List<Occurrence>)

/**
 * The widget's list: [days] days from [today], each with its holidays and events.
 * - today is always there (it heads the list), other days only when something falls on them;
 * - an event of several days is listed once, on the first day of it the list shows;
 * - today's timed events that have ended ([now]) drop out;
 * - holiday-calendar events read as holidays, not as events.
 */
fun widgetAgenda(
    shown: List<Occurrence>,
    holidayCalendars: Set<String>,
    today: LocalDate,
    now: Instant,
    days: Int = WIDGET_DAYS,
): List<AgendaDay> = (0 until days).mapNotNull { i ->
    val date = today.plus(i, DateTimeUnit.DAY)
    val items = shown.filter { o ->
        o.calendarId !in holidayCalendars &&
            maxOf(o.firstDay, today) == date && o.lastDay >= date &&
            !(date == today && !o.allDay && o.end <= now)
    }.sortedForDay()
    val holidays = holidayNamesOn(date, shown, holidayCalendars)
    if (i == 0 || items.isNotEmpty() || holidays.isNotEmpty()) AgendaDay(date, holidays, items) else null
}

/**
 * The small widget's event (docs/09 §3 W3): the timed event going on now or next
 * (within [days]), else today's first all-day event; holiday entries never.
 */
fun nextUp(
    shown: List<Occurrence>,
    holidayCalendars: Set<String>,
    today: LocalDate,
    now: Instant,
    days: Int = WIDGET_DAYS,
): Occurrence? {
    val until = today.plus(days, DateTimeUnit.DAY)
    val events = shown.filter { it.calendarId !in holidayCalendars && it.firstDay < until }
    return events.filter { !it.allDay && it.end > now }.minByOrNull { it.begin }
        ?: events.filter { it.allDay && today in it.firstDay..it.lastDay }.sortedForDay().firstOrNull()
}

/** "진행 중", "25분 후", "3시간 후", "내일", "10월 23일 (금)", "오늘 종일": when [o] is, from [now]. */
fun untilKo(o: Occurrence, now: Instant, today: LocalDate, zone: kotlinx.datetime.TimeZone): String {
    if (o.allDay) return if (today in o.firstDay..o.lastDay) "오늘 종일" else dayLabelKo(o.firstDay, today)
    if (o.begin <= now) return "진행 중"
    val minutes = (o.begin - now).inWholeMinutes
    val day = o.begin.toLocalDateTime(zone).date
    return when {
        minutes < 60 -> "${minutes.coerceAtLeast(1)}분 후"
        day == today -> "${minutes / 60}시간 후"
        else -> dayLabelKo(day, today)
    }
}

/** "내일" or "10월 23일 (금)". */
fun dayLabelKo(day: LocalDate, today: LocalDate): String =
    if (day == today.plus(1, DateTimeUnit.DAY)) "내일" else "${day.monthNumber}월 ${day.dayOfMonth}일 (${day.dayOfWeek.koShort()})"

/** How far ahead the widget lists (docs/09 §8 D). */
const val WIDGET_DAYS = 14
