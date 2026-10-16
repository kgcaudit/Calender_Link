package app.olo.calendar.ui

import app.olo.calendar.model.CalendarInfo
import app.olo.calendar.model.EventDraft
import app.olo.calendar.model.MovePlan
import app.olo.calendar.model.MoveResult
import app.olo.calendar.model.Occurrence
import app.olo.calendar.model.StoredEvent
import app.olo.calendar.model.SyncStatus
import app.olo.calendar.model.YearMonth
import app.olo.calendar.model.monthGrid
import app.olo.calendar.store.CalendarStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime

data class CalendarUiState(
    val month: YearMonth,
    val selected: LocalDate,
    val today: LocalDate,
    val calendars: List<CalendarInfo> = emptyList(),
    val occurrences: List<Occurrence> = emptyList(),
    val sync: SyncStatus = SyncStatus(),
    val eventCounts: Map<String, Int> = emptyMap(),
    val loading: Boolean = true,
    val error: String? = null,
) {
    val calendarsById: Map<String, CalendarInfo> get() = calendars.associateBy { it.id }

    /** Writable calendars outside Google that hold events: those events never reach the iPhone. */
    val stranded: List<Pair<CalendarInfo, Int>>
        get() = calendars.filter { it.writable && !it.isHoliday && !it.kind.reachesGoogle }
            .mapNotNull { c -> (eventCounts[c.id] ?: 0).takeIf { it > 0 }?.let { c to it } }

    val googleTargets: List<CalendarInfo>
        get() = calendars.filter { it.kind.reachesGoogle && it.writable && !it.isHoliday }
            .sortedWith(compareBy({ !it.isPrimary }, { it.name }))

    /** Something stops events from reaching the other phone. */
    val needsSyncAttention: Boolean
        get() = !sync.masterSyncOn || sync.accountsWithSyncOff.isNotEmpty() || stranded.isNotEmpty()

    /** Where a new event goes: a writable primary Google calendar if there is one. */
    val defaultCalendar: CalendarInfo?
        get() = calendars.filter { it.writable && !it.isHoliday }.let { w ->
            w.firstOrNull { it.kind.reachesGoogle && it.isPrimary }
                ?: w.firstOrNull { it.kind.reachesGoogle }
                ?: w.firstOrNull()
        }
}

/** Holds what the calendar screens show and reloads it whenever the store changes. */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class CalendarController(
    private val store: CalendarStore,
    private val scope: CoroutineScope,
    val zone: TimeZone = TimeZone.currentSystemDefault(),
) {
    private val todayNow: LocalDate get() = Clock.System.now().toLocalDateTime(zone).date

    private val _state = MutableStateFlow(
        todayNow.let { CalendarUiState(month = YearMonth.of(it), selected = it, today = it) },
    )
    val state: StateFlow<CalendarUiState> = _state.asStateFlow()

    init {
        reload()
        scope.launch {
            // A burst of writes (a sync pulling many events) reloads once.
            store.changes().debounce(300).collect { reload() }
        }
    }

    fun showMonth(month: YearMonth) {
        _state.update { s ->
            val sel = if (YearMonth.of(s.selected) == month) s.selected else month.firstDay
            s.copy(month = month, selected = sel)
        }
        reload()
    }

    fun select(day: LocalDate) {
        val m = YearMonth.of(day)
        val changedMonth = m != _state.value.month
        _state.update { it.copy(selected = day, month = m) }
        if (changedMonth) reload()
    }

    fun goToday() = select(todayNow)

    fun reload() {
        scope.launch {
            val s = _state.value
            val grid = monthGrid(s.month)
            val from = grid.first().atStartOfDayIn(zone)
            val to = grid.last().plus(1, DateTimeUnit.DAY).atStartOfDayIn(zone)
            runCatching {
                Loaded(store.calendars(), store.occurrences(from, to), store.syncStatus(), store.eventCounts())
            }.onSuccess { l ->
                _state.update {
                    it.copy(
                        calendars = l.calendars, occurrences = l.occurrences, sync = l.sync, eventCounts = l.counts,
                        loading = false, error = null, today = todayNow,
                    )
                }
            }.onFailure { e ->
                _state.update { it.copy(loading = false, error = e.message ?: "캘린더를 읽지 못했습니다.") }
            }
        }
    }

    suspend fun load(id: String): StoredEvent? = store.event(id)

    fun save(draft: EventDraft, done: (Result<String>) -> Unit = {}) {
        scope.launch {
            val r = runCatching { store.save(draft) }
            r.onFailure { e -> _state.update { it.copy(error = "저장하지 못했습니다: ${e.message}") } }
            done(r)
            reload()
        }
    }

    fun delete(id: String) {
        scope.launch {
            runCatching { store.delete(id) }
                .onFailure { e -> _state.update { it.copy(error = "삭제하지 못했습니다: ${e.message}") } }
            reload()
        }
    }

    fun setVisible(calendarId: String, visible: Boolean) {
        scope.launch {
            runCatching { store.setVisible(calendarId, visible) }
            reload()
        }
    }

    fun syncNow() {
        store.requestSync()
        reload()
    }

    suspend fun planMove(fromCalendarId: String): MovePlan = store.planMove(fromCalendarId)

    /** Moves a calendar's events into a Google calendar and deletes the originals. */
    fun move(from: String, to: String, onProgress: (Int, Int) -> Unit, done: (Result<MoveResult>) -> Unit) {
        scope.launch {
            val r = runCatching { store.move(from, to, deleteOriginals = true, onProgress = onProgress) }
            done(r)
            store.requestSync()
            reload()
        }
    }

    fun dismissError() = _state.update { it.copy(error = null) }

    fun newDraft(day: LocalDate = _state.value.selected): EventDraft? {
        val cal = _state.value.defaultCalendar ?: return null
        return EventDraft(calendarId = cal.id, startDate = day, endDate = day, timeZoneId = zone.id, reminderMinutes = listOf(10))
    }
}

private class Loaded(
    val calendars: List<CalendarInfo>,
    val occurrences: List<Occurrence>,
    val sync: SyncStatus,
    val counts: Map<String, Int>,
)
