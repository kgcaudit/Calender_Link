package app.olo.calendar

import androidx.compose.ui.window.ComposeUIViewController
import app.olo.calendar.model.CalendarInfo
import app.olo.calendar.model.EventDraft
import app.olo.calendar.model.MovePlan
import app.olo.calendar.model.MoveResult
import app.olo.calendar.model.Occurrence
import app.olo.calendar.model.StoredEvent
import app.olo.calendar.model.SyncStatus
import app.olo.calendar.store.CalendarStore
import app.olo.calendar.ui.App
import app.olo.calendar.ui.PermissionGate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.datetime.Instant

/*
 * iOS entry point. Stage 2 replaces PendingIosStore with an EventKit store
 * (platform.EventKit: EKEventStore.requestFullAccessToEvents, calendarItemExternalIdentifier
 * as the stable key, EKSpan for repeat edits). Until then the screen opens empty.
 * Not built on Linux; compiled by the macOS CI job once it exists.
 */
fun MainViewController() = ComposeUIViewController { App(PendingIosStore, AlwaysGranted) }

private object AlwaysGranted : PermissionGate {
    override val granted: StateFlow<Boolean> = MutableStateFlow(true)
    override val deniedForGood: StateFlow<Boolean> = MutableStateFlow(false)
    override fun request() = Unit
    override fun openSettings() = Unit
}

private object PendingIosStore : CalendarStore {
    override suspend fun calendars(): List<CalendarInfo> = emptyList()
    override suspend fun occurrences(from: Instant, to: Instant): List<Occurrence> = emptyList()
    override suspend fun event(id: String): StoredEvent? = null
    override suspend fun search(query: String, from: Instant, to: Instant): List<Occurrence> = emptyList()
    override suspend fun save(draft: EventDraft): String = error("EventKit store not implemented yet")
    override suspend fun delete(id: String) = Unit
    override suspend fun setVisible(calendarId: String, visible: Boolean) = Unit
    override suspend fun syncStatus(): SyncStatus = SyncStatus()
    override suspend fun setAccountSync(account: String, on: Boolean) = false
    override suspend fun bridgeItems(calendarId: String): List<app.olo.calendar.model.BridgeItem> = emptyList()
    override suspend fun bridgeCopy(sourceId: String, toCalendarId: String): String? = null
    override suspend fun bridgeReplace(sourceId: String, targetId: String): String? = null
    override suspend fun bridgeDelete(id: String) = false
    override suspend fun setMasterSync(on: Boolean) = false
    override fun requestSync() = Unit
    override fun changes(): Flow<Unit> = emptyFlow()
    override suspend fun eventCounts(): Map<String, Int> = emptyMap()
    override suspend fun planMove(fromCalendarId: String): MovePlan = MovePlan(fromCalendarId, 0, 0, 0, 0)
    override suspend fun move(fromCalendarId: String, toCalendarId: String, deleteOriginals: Boolean, onProgress: (Int, Int) -> Unit): MoveResult =
        MoveResult(0, 0, 0)
}
