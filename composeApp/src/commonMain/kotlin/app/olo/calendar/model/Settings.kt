package app.olo.calendar.model

import kotlinx.datetime.DayOfWeek

/** The calendar views, as Samsung Calendar offers them. */
enum class ViewMode(val label: String) { YEAR("년"), MONTH("월"), WEEK("주"), DAY("일"), AGENDA("일정") }

/** OLO's own preferences. Everything about events lives in the phone's calendar store, not here. */
data class AppSettings(
    val weekStart: DayOfWeek = DayOfWeek.SUNDAY,
    val showLunar: Boolean = true,
    val showWeekNumbers: Boolean = false,
    /** Agenda view starts from today instead of listing past days. */
    val hidePastInAgenda: Boolean = false,
    /** Calendar for new events; null = the primary Google calendar. */
    val defaultCalendarId: String? = null,
    val lastView: ViewMode = ViewMode.MONTH,
)

/** Small key-value storage (SharedPreferences / NSUserDefaults). */
interface Preferences {
    fun get(key: String): String?
    fun put(key: String, value: String?)
}

class MemoryPreferences : Preferences {
    private val map = mutableMapOf<String, String>()
    override fun get(key: String) = map[key]
    override fun put(key: String, value: String?) { if (value == null) map.remove(key) else map[key] = value }
}

fun Preferences.loadSettings(): AppSettings {
    val d = AppSettings()
    return AppSettings(
        weekStart = if (get("weekStart") == "MONDAY") DayOfWeek.MONDAY else DayOfWeek.SUNDAY,
        showLunar = get("showLunar")?.toBooleanStrictOrNull() ?: d.showLunar,
        showWeekNumbers = get("showWeekNumbers")?.toBooleanStrictOrNull() ?: d.showWeekNumbers,
        hidePastInAgenda = get("hidePastInAgenda")?.toBooleanStrictOrNull() ?: d.hidePastInAgenda,
        defaultCalendarId = get("defaultCalendarId"),
        lastView = get("lastView")?.let { v -> ViewMode.entries.firstOrNull { it.name == v } } ?: d.lastView,
    )
}

fun Preferences.saveSettings(s: AppSettings) {
    put("weekStart", s.weekStart.name)
    put("showLunar", s.showLunar.toString())
    put("showWeekNumbers", s.showWeekNumbers.toString())
    put("hidePastInAgenda", s.hidePastInAgenda.toString())
    put("defaultCalendarId", s.defaultCalendarId)
    put("lastView", s.lastView.name)
}
