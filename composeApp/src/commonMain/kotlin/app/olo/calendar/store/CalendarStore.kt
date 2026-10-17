package app.olo.calendar.store

import app.olo.calendar.model.CalendarInfo
import app.olo.calendar.model.EventDraft
import app.olo.calendar.model.MovePlan
import app.olo.calendar.model.MoveResult
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

    /** Occurrences in [from, to) whose title, location or notes contain [query]. */
    suspend fun search(query: String, from: Instant, to: Instant): List<Occurrence>

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

    /**
     * Turns automatic calendar sync on or off for one Google account, as the
     * phone's account settings would (docs/06 §3). Returns false when the OS
     * refuses; the caller then offers the account settings instead.
     */
    suspend fun setAccountSync(account: String, on: Boolean): Boolean

    /** The phone-wide auto-sync switch. */
    suspend fun setMasterSync(on: Boolean): Boolean

    // --- the Galaxy bridge (docs/07) ---

    /** Every series in a calendar (standalone events and repeating ones with their exceptions folded in). */
    suspend fun bridgeItems(calendarId: String): List<app.olo.calendar.model.BridgeItem>

    /** Copies a series into another calendar, exceptions and reminders included; returns the new id. */
    suspend fun bridgeCopy(sourceId: String, toCalendarId: String): String?

    /**
     * Makes [targetId] match [sourceId]: in place for a plain event, by
     * recreating the series when repeats are involved. Returns the target's id
     * afterwards (new when recreated).
     */
    suspend fun bridgeReplace(sourceId: String, targetId: String): String?

    suspend fun bridgeDelete(id: String): Boolean

    /**
     * Series deleted on this phone whose rows the sync adapter has not purged yet
     * (DELETED = 1): what can still be brought back after a wrong deletion.
     */
    suspend fun bridgeDeleted(calendarId: String): List<app.olo.calendar.model.BridgeItem>

    /** Re-creates a deleted series (see [bridgeDeleted]) in [toCalendarId]; returns the new id. */
    suspend fun bridgeRestore(deletedId: String, toCalendarId: String): String?

    /**
     * Asks the OS to sync calendar accounts now; the OS decides when it runs.
     * Leaves out [exclude] ("type/name" keys) and every account whose calendar
     * sync is off on the phone: a manual request would otherwise override that
     * switch (docs/07 §17).
     */
    fun requestSync(exclude: Set<String> = emptySet())

    /** Whether any account [requestSync] would ask is syncing or waiting to (so "지금 동기화" knows when to report). */
    suspend fun syncActive(exclude: Set<String> = emptySet()): Boolean = false

    /** Emits whenever the database changes, from this app or any other. */
    fun changes(): Flow<Unit>

    /** Number of (non-deleted) events stored in each calendar, by calendar id. */
    suspend fun eventCounts(): Map<String, Int>

    suspend fun planMove(fromCalendarId: String): MovePlan

    /**
     * Copies every event of one calendar into another (normally from a Samsung or
     * phone-only calendar into Google), then deletes the originals when
     * [deleteOriginals] is set and every copy succeeded. Repeat rules, changed
     * occurrences and reminders are kept; guests are not copied, so no
     * invitations go out.
     */
    suspend fun move(
        fromCalendarId: String,
        toCalendarId: String,
        deleteOriginals: Boolean,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): MoveResult
}
