package app.olo.calendar.model

import kotlinx.datetime.DayOfWeek

/** The calendar views, as Samsung Calendar offers them. */
enum class ViewMode(val label: String) { YEAR("년"), MONTH("월"), WEEK("주"), DAY("일"), AGENDA("일정") }

/** How much of the wallpaper shows through OLO's widgets (docs/09 §6), as Samsung's widgets offer. */
enum class WidgetBackground(val label: String, val alpha: Float) {
    SOLID("불투명", 1f), HALF("반투명", 0.6f), CLEAR("투명", 0f),
}

/** OLO's own preferences. Everything about events lives in the phone's calendar store, not here. */
data class AppSettings(
    val weekStart: DayOfWeek = DayOfWeek.SUNDAY,
    val showLunar: Boolean = true,
    val showWeekNumbers: Boolean = false,
    /** Calendar for new events; null = the primary Google calendar. */
    val defaultCalendarId: String? = null,
    val lastView: ViewMode = ViewMode.MONTH,
    /** Month view stage on a phone: 1 full month, 2 lines + day, 3 one week + day. */
    val monthStage: Int = 1,
    /** OLO's own show/hide per calendar id; ids not listed follow [CalendarInfo.shownByDefault]. */
    val visibility: Map<String, Boolean> = emptyMap(),
    /** Drawer groups the user folded or opened, by key, overriding the default fold. */
    val folded: Map<String, Boolean> = emptyMap(),
    /** Month paging on wide screens: false = left/right (default), true = up/down. */
    val verticalPagingWide: Boolean = false,
    /** Colours chosen inside OLO, calendar id → OloHue name; the store keeps its own colour (docs/06 §4). */
    val hues: Map<String, String> = emptyMap(),
    /** The Galaxy bridge (docs/07): Samsung Calendar ⇄ the Google main calendar. */
    val bridgeSamsungId: String? = null,
    val bridgeGoogleId: String? = null,
    /**
     * Accounts "지금 동기화" leaves alone (docs/07 §17), as [syncAccountKey]s
     * ("type/name": a Samsung account and a Google account can share a name).
     * OLO-only: the phone's own automatic sync of them goes on.
     */
    val syncExcluded: Set<String> = emptySet(),
    val widgetBackground: WidgetBackground = WidgetBackground.SOLID,
    /** A phone notification when a background bridge pass needs the person (docs/12 §3). */
    val bridgeAlerts: Boolean = true,
) {
    val bridgeOn: Boolean get() = bridgeSamsungId != null && bridgeGoogleId != null
}

fun AppSettings.isShown(c: CalendarInfo): Boolean = visibility[c.id] ?: c.shownByDefault()

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
        defaultCalendarId = get("defaultCalendarId"),
        lastView = get("lastView")?.let { v -> ViewMode.entries.firstOrNull { it.name == v } } ?: d.lastView,
        monthStage = get("monthStage")?.toIntOrNull()?.coerceIn(1, 3) ?: d.monthStage,
        visibility = decodeVisibility(get("visibility")),
        folded = decodeVisibility(get("folded")),
        verticalPagingWide = get("verticalPagingWide")?.toBooleanStrictOrNull() ?: d.verticalPagingWide,
        hues = decodeMap(get("hues")),
        bridgeSamsungId = get("bridgeSamsungId"),
        bridgeGoogleId = get("bridgeGoogleId"),
        syncExcluded = get("syncExcluded").orEmpty().split('\n').filter { it.isNotBlank() }.toSet(),
        bridgeAlerts = get("bridgeAlerts")?.toBooleanStrictOrNull() ?: d.bridgeAlerts,
        widgetBackground = get("widgetBackground")?.let { v -> WidgetBackground.entries.firstOrNull { it.name == v } } ?: d.widgetBackground,
    )
}

fun Preferences.saveSettings(s: AppSettings) {
    put("weekStart", s.weekStart.name)
    put("showLunar", s.showLunar.toString())
    put("showWeekNumbers", s.showWeekNumbers.toString())
    put("defaultCalendarId", s.defaultCalendarId)
    put("lastView", s.lastView.name)
    put("monthStage", s.monthStage.toString())
    put("visibility", encodeVisibility(s.visibility))
    put("folded", encodeVisibility(s.folded))
    put("verticalPagingWide", s.verticalPagingWide.toString())
    put("hues", encodeMap(s.hues))
    put("bridgeSamsungId", s.bridgeSamsungId)
    put("bridgeGoogleId", s.bridgeGoogleId)
    put("syncExcluded", s.syncExcluded.joinToString("\n").ifEmpty { null })
    put("widgetBackground", s.widgetBackground.name)
    put("bridgeAlerts", s.bridgeAlerts.toString())
}
