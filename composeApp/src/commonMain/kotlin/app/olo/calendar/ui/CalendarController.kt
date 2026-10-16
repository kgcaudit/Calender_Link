package app.olo.calendar.ui

import app.olo.calendar.model.CalendarInfo
import app.olo.calendar.model.EventDraft
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
    val loading: Boolean = true,
    val error: String? = null,
) {
    val calendarsById: Map<String, CalendarInfo> get() = calendars.associateBy { it.id }

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
                Triple(store.calendars(), store.occurrences(from, to), store.syncStatus())
            }.onSuccess { (cals, occ, sync) ->
                _state.update {
                    it.copy(calendars = cals, occurrences = occ, sync = sync, loading = false, error = null, today = todayNow)
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

    fun dismissError() = _state.update { it.copy(error = null) }

    fun newDraft(day: LocalDate = _state.value.selected): EventDraft? {
        val cal = _state.value.defaultCalendar ?: return null
        return EventDraft(calendarId = cal.id, startDate = day, endDate = day, timeZoneId = zone.id, reminderMinutes = listOf(10))
    }
}
