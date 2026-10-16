package app.olo.calendar.model

import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime

/**
 * Where a calendar's events go when they are saved. Only [GOOGLE] reaches the
 * other phone; everything else stays on this device or goes to another service.
 */
enum class SyncKind {
    GOOGLE,
    /** Phone-only: Android LOCAL / Samsung "My calendar" / iOS "On My iPhone". */
    DEVICE,
    SAMSUNG,
    ICLOUD,
    EXCHANGE,
    SUBSCRIBED,
    OTHER,
    ;

    val reachesGoogle: Boolean get() = this == GOOGLE
}

data class CalendarInfo(
    val id: String,
    val name: String,
    val accountName: String,
    val accountType: String,
    val kind: SyncKind,
    /** ARGB as stored by the OS. Shown through [app.olo.calendar.design.oloHueFor]. */
    val colorArgb: Int,
    val writable: Boolean,
    val visible: Boolean,
    val isPrimary: Boolean,
    val isHoliday: Boolean,
)

/**
 * One occurrence on screen. A recurring event yields one per repetition.
 *
 * [firstDay]..[lastDay] are the local days it covers. All-day events are
 * stored as UTC midnights on Android, so their days are taken in UTC, never in
 * the device zone (otherwise they shift by a day west of UTC).
 */
data class Occurrence(
    val eventId: String,
    val calendarId: String,
    val title: String,
    val allDay: Boolean,
    val begin: Instant,
    val end: Instant,
    val firstDay: LocalDate,
    val lastDay: LocalDate,
    val location: String,
    val colorArgb: Int,
    val recurring: Boolean,
)

/** What the editor shows and saves. Times are wall-clock in [timeZoneId]. */
data class EventDraft(
    val id: String? = null,
    val calendarId: String,
    val title: String = "",
    val allDay: Boolean = false,
    val startDate: LocalDate,
    val startTime: LocalTime = LocalTime(9, 0),
    /** Inclusive for all-day events (a one-day event ends on its start date). */
    val endDate: LocalDate = startDate,
    val endTime: LocalTime = LocalTime(10, 0),
    val timeZoneId: String,
    val location: String = "",
    val description: String = "",
    /** Popup reminders in minutes before start. Email reminders are not used (iPhone drops them). */
    val reminderMinutes: List<Int> = emptyList(),
    val recurrence: Recurrence? = null,
    /**
     * Leave the stored repeat rule exactly as it is. Set when the rule is
     * beyond OLO's editor, so saving other fields cannot flatten it.
     */
    val keepExistingRule: Boolean = false,
)

/** An event read back from the store, with what OLO may safely change about it. */
data class StoredEvent(
    val draft: EventDraft,
    /** The raw rule, kept so an unsupported rule can be shown and left untouched. */
    val rawRule: String?,
    /** False when the rule is beyond what OLO's editor (and iPhone) can express. */
    val recurrenceEditable: Boolean,
    val calendarWritable: Boolean,
    /** An exception (a moved/changed single occurrence of a recurring event). */
    val isException: Boolean,
)

data class SyncStatus(
    /** Events changed on this phone that the sync adapter has not uploaded yet. */
    val pendingUploads: Int = 0,
    /** Google accounts whose calendar sync is switched off in system settings. */
    val accountsWithSyncOff: List<String> = emptyList(),
    /** System-wide auto-sync switch. */
    val masterSyncOn: Boolean = true,
)
