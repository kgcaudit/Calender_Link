package app.olo.calendar.model

import app.olo.calendar.design.OloHue
import app.olo.calendar.design.argb
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus

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

/** How far ahead the widget lists (docs/09 §8 D). */
const val WIDGET_DAYS = 14
