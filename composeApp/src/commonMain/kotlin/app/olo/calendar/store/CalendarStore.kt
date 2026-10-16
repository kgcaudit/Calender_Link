package app.olo.calendar.store

import app.olo.calendar.model.CalendarInfo
import app.olo.calendar.model.EventDraft
import app.olo.calendar.model.Occurrence
import app.olo.calendar.model.StoredEvent
import app.olo.calendar.model.SyncStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.Instant

/**
 * The phone's own calendar database: Android's Calendar Provider (shared with
 * Samsung Calendar) or iOS EventKit (shared with Apple Calendar). The OS syncs
 * it with Google; OLO keeps no event data of its own.
 */
interface CalendarStore {
    suspend fun calendars(): List<CalendarInfo>

    /** Occurrences overlapping [from, to) in visible calendars, recurrences expanded. */
    suspend fun occurrences(from: Instant, to: Instant): List<Occurrence>

    suspend fun event(id: String): StoredEvent?

    /**
     * Inserts ([EventDraft.id] == null) or updates. On update only the fields
     * that differ from what is stored are written, so data other apps keep on
     * the same row (Samsung stickers, Google-only fields) survives.
     * Returns the event id.
     */
    suspend fun save(draft: EventDraft): String

    suspend fun delete(id: String)

    suspend fun setVisible(calendarId: String, visible: Boolean)

    suspend fun syncStatus(): SyncStatus

    /** Asks the OS to sync now. The OS decides when it actually runs. */
    fun requestSync()

    /** Emits whenever the database changes, from this app or any other. */
    fun changes(): Flow<Unit>
}
