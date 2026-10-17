package app.olo.calendar.ui

import app.olo.calendar.model.AppSettings
import app.olo.calendar.model.CalendarInfo
import app.olo.calendar.model.SyncKind
import app.olo.calendar.design.OloHue
import app.olo.calendar.design.argb
import app.olo.calendar.model.MemoryPreferences
import app.olo.calendar.model.Preferences
import app.olo.calendar.model.ViewMode
import app.olo.calendar.model.loadSettings
import app.olo.calendar.model.saveSettings
import app.olo.calendar.model.weekStartOf
import app.olo.calendar.model.isShown
import kotlinx.datetime.DatePeriod
import app.olo.calendar.model.EventDraft
import app.olo.calendar.model.MovePlan
import app.olo.calendar.model.MoveResult
import app.olo.calendar.model.Occurrence
import app.olo.calendar.model.StoredEvent
import app.olo.calendar.model.SyncStatus
import app.olo.calendar.model.YearMonth
import app.olo.calendar.model.monthGrid
import app.olo.calendar.store.CalendarStore
import app.olo.calendar.store.BridgeConfig
import app.olo.calendar.store.BridgeEngine
import app.olo.calendar.store.BridgeReport
import app.olo.calendar.model.BridgePlan
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
    /** Calendars as OLO shows them: a colour chosen in OLO replaces the stored one. */
    val calendars: List<CalendarInfo> = emptyList(),
    val rawCalendars: List<CalendarInfo> = emptyList(),
    /** Every occurrence in the loaded range, hidden calendars included. */
    val allOccurrences: List<Occurrence> = emptyList(),
    /** What the views draw: [allOccurrences] of the calendars OLO shows. */
    val occurrences: List<Occurrence> = emptyList(),
    val sync: SyncStatus = SyncStatus(),
    val eventCounts: Map<String, Int> = emptyMap(),
    val loading: Boolean = true,
    val error: String? = null,
    val view: ViewMode = ViewMode.MONTH,
    /** Agenda tab: false = 다가오는 일정 (from today), true = 지난 일정 (back from today). */
    val agendaPast: Boolean = false,
    val settings: AppSettings = AppSettings(),
    /** Google-side ids of bridged pairs: shown once, from Samsung (docs/07). */
    val bridgeLinkedGoogle: Set<String> = emptySet(),
    val bridgeReport: BridgeReport? = null,
    /** done / total while a bridge pass runs; null otherwise. */
    val bridgeProgress: Pair<Int, Int>? = null,
) {
    val bridgeSamsung: CalendarInfo? get() = settings.bridgeSamsungId?.let { calendarsById[it] }
    val bridgeGoogle: CalendarInfo? get() = settings.bridgeGoogleId?.let { calendarsById[it] }

    val calendarsById: Map<String, CalendarInfo> get() = calendars.associateBy { it.id }

    /**
     * Phone-only or Samsung-cloud calendars that hold events: those never reach the
     * iPhone. Exchange and iCloud travel by their own servers, so they are not listed.
     */
    val stranded: List<Pair<CalendarInfo, Int>>
        get() = calendars.filter { it.writable && !it.isHoliday && (it.kind == SyncKind.DEVICE || it.kind == SyncKind.SAMSUNG) && it.id != settings.bridgeSamsungId }
            .mapNotNull { c -> (eventCounts[c.id] ?: 0).takeIf { it > 0 }?.let { c to it } }

    val googleTargets: List<CalendarInfo>
        get() = calendars.filter { it.kind.reachesGoogle && it.writable && !it.isHoliday }
            .sortedWith(compareBy({ !it.isPrimary }, { it.name }))

    /** What stops events from reaching the other phone, each with its own fix (docs/05 §3.4). */
    val syncActions: List<SyncAction>
        get() = buildList {
            if (!sync.masterSyncOn) add(SyncAction.MasterSyncOff)
            sync.accountsWithSyncOff.forEach { add(SyncAction.AccountSyncOff(it)) }
            stranded.forEach { (c, n) -> add(SyncAction.Stranded(c, n)) }
        }

    val needsSyncAttention: Boolean get() = syncActions.isNotEmpty()

    fun isShown(c: CalendarInfo): Boolean = settings.isShown(c)

    /** Where a new event goes: the chosen default, else a writable primary Google calendar. */
    val defaultCalendar: CalendarInfo?
        get() = calendars.filter { it.writable && !it.isHoliday }.let { w ->
            w.firstOrNull { it.id == settings.defaultCalendarId }
                ?: w.firstOrNull { it.kind.reachesGoogle && it.isPrimary }
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
    private val prefs: Preferences = MemoryPreferences(),
) {
    private val todayNow: LocalDate get() = Clock.System.now().toLocalDateTime(zone).date

    private val _state = MutableStateFlow(
        todayNow.let {
            val settings = prefs.loadSettings()
            CalendarUiState(month = YearMonth.of(it), selected = it, today = it, view = settings.lastView, settings = settings)
        },
    )
    val state: StateFlow<CalendarUiState> = _state.asStateFlow()

    val bridge = BridgeEngine(store, prefs)

    init {
        _state.update { it.copy(bridgeLinkedGoogle = bridge.linkedGoogleIds(), bridgeReport = bridge.lastReport) }
        reload()
        scope.launch {
            // A burst of writes (a sync pulling many events) reloads once.
            store.changes().debounce(300).collect { reload() }
        }
        scope.launch {
            // While OLO is open the bridge follows every change within seconds; the
            // platform's background work covers the rest. Its own writes settle in one pass.
            store.changes().debounce(3_000).collect { if (_state.value.settings.bridgeOn) runBridge() }
        }
        if (_state.value.settings.bridgeOn) runBridge()
    }

    private fun bridgeConfig(): BridgeConfig? = _state.value.settings.let { st ->
        if (st.bridgeSamsungId != null && st.bridgeGoogleId != null) BridgeConfig(st.bridgeSamsungId, st.bridgeGoogleId) else null
    }

    /** What connecting these two calendars would do, without writing anything. */
    suspend fun previewBridge(samsungId: String, googleId: String): BridgePlan = bridge.preview(BridgeConfig(samsungId, googleId))

    /** Joins Samsung Calendar to the Google main calendar and runs the first pass. */
    fun startBridge(samsungId: String, googleId: String, done: (Result<BridgeReport>) -> Unit = {}) {
        if (_state.value.settings.bridgeSamsungId != samsungId || _state.value.settings.bridgeGoogleId != googleId) bridge.forget()
        updateSettings { it.copy(bridgeSamsungId = samsungId, bridgeGoogleId = googleId) }
        runBridge(done = done)
    }

    /** One pass now. [allowMassDelete] releases deletions held by the safety stop. */
    fun runBridge(allowMassDelete: Boolean = false, done: (Result<BridgeReport>) -> Unit = {}) {
        val config = bridgeConfig() ?: return
        scope.launch {
            val r = runCatching {
                bridge.run(config, allowMassDelete) { d, t -> if (t > 0) _state.update { it.copy(bridgeProgress = d to t) } }
            }
            _state.update {
                it.copy(
                    bridgeProgress = null,
                    bridgeLinkedGoogle = bridge.linkedGoogleIds(),
                    bridgeReport = r.getOrNull() ?: it.bridgeReport,
                    occurrences = shownOf(it.allOccurrences, it.calendars, it.settings, bridge.linkedGoogleIds()),
                )
            }
            if (r.getOrNull()?.let { it.toGoogle + it.toSamsung + it.deleted > 0 } == true) store.requestSync()
            done(r)
        }
    }

    /** Stops the bridge; both calendars keep every event they hold. */
    fun stopBridge() {
        bridge.forget()
        updateSettings { it.copy(bridgeSamsungId = null, bridgeGoogleId = null) }
        _state.update { it.copy(bridgeLinkedGoogle = emptySet(), bridgeReport = null, occurrences = shownOf(it.allOccurrences, it.calendars, it.settings, emptySet())) }
    }

    /** A month page settled: keep the day number (31 → the month's last day), or today in today's month. */
    fun showMonth(month: YearMonth) {
        val s0 = _state.value
        if (s0.month == month) return
        _state.update { s ->
            val sel = when {
                YearMonth.of(s.selected) == month -> s.selected
                YearMonth.of(s.today) == month -> s.today
                else -> LocalDate(month.year, month.month, minOf(s.selected.dayOfMonth, month.length))
            }
            s.copy(month = month, selected = sel)
        }
        reload()
    }

    fun select(day: LocalDate) {
        val before = _state.value
        if (before.selected == day) return
        _state.update { it.copy(selected = day, month = YearMonth.of(day)) }
        val moved = when (before.view) {
            ViewMode.MONTH -> YearMonth.of(day) != before.month
            // The agenda's range hangs from today, not from the selected day.
            ViewMode.AGENDA -> false
            ViewMode.YEAR -> day.year != before.selected.year
            ViewMode.WEEK -> weekStartOf(day, before.settings.weekStart) != weekStartOf(before.selected, before.settings.weekStart)
            ViewMode.DAY -> true
        }
        if (moved || range(before).let { (a, b) -> day < a || day >= b }) reload()
    }

    /** Year view scrolled to another year. */
    fun showYear(year: Int) {
        val s = _state.value
        if (s.selected.year == year) return
        val m = s.selected.monthNumber
        select(LocalDate(year, m, minOf(s.selected.dayOfMonth, YearMonth(year, m).length)))
    }

    // --- which calendars OLO shows (docs/05 §3.3) ---

    fun setShown(ids: Collection<String>, shown: Boolean) =
        updateSettings { st -> st.copy(visibility = st.visibility + ids.associateWith { shown }) }

    /** "이것만 보기": this calendar on, every other one off. */
    fun showOnly(id: String) =
        updateSettings { st -> st.copy(visibility = _state.value.calendars.associate { it.id to (it.id == id) }) }

    /** Back to OLO's defaults (everything but holiday copies). */
    fun showDefault() = updateSettings { it.copy(visibility = emptyMap()) }

    /** A colour for a calendar inside OLO; null goes back to the calendar's own. */
    fun setHue(id: String, hue: OloHue?) =
        updateSettings { st -> st.copy(hues = if (hue == null) st.hues - id else st.hues + (id to hue.name)) }

    fun setFolded(key: String, folded: Boolean) = updateSettings { it.copy(folded = it.folded + (key to folded)) }

    fun goToday() {
        val t = todayNow
        _state.update { it.copy(selected = t, month = YearMonth.of(t), today = t) }
        reload()
    }

    fun reload() {
        scope.launch {
            val s = _state.value
            val (fromDay, toDay) = range(s)
            val from = fromDay.atStartOfDayIn(zone)
            val to = toDay.atStartOfDayIn(zone)
            runCatching {
                Loaded(store.calendars(), store.occurrences(from, to), store.syncStatus(), store.eventCounts())
            }.onSuccess { l ->
                _state.update {
                    it.copy(
                        rawCalendars = l.calendars, calendars = recolored(l.calendars, it.settings),
                        allOccurrences = l.occurrences, sync = l.sync, eventCounts = l.counts,
                        occurrences = shownOf(l.occurrences, l.calendars, it.settings, it.bridgeLinkedGoogle),
                        loading = false, error = null, today = todayNow,
                    )
                }
            }.onFailure { e ->
                _state.update { it.copy(loading = false, error = e.message ?: "캘린더를 읽지 못했습니다.") }
            }
        }
    }

    /**
     * Days [from, to) the current view needs, with the neighbouring page on each
     * side, so a page already holds its events while it slides in (docs/05 §5).
     */
    private fun range(s: CalendarUiState): Pair<LocalDate, LocalDate> = when (s.view) {
        ViewMode.YEAR -> LocalDate(s.selected.year - 1, 1, 1) to LocalDate(s.selected.year + 2, 1, 1)
        ViewMode.MONTH -> monthGrid(s.month.plusMonths(-1), s.settings.weekStart).first() to
            monthGrid(s.month.plusMonths(1), s.settings.weekStart).last().plus(1, DateTimeUnit.DAY)
        ViewMode.WEEK -> weekStartOf(s.selected, s.settings.weekStart).let { it.plus(-7, DateTimeUnit.DAY) to it.plus(14, DateTimeUnit.DAY) }
        ViewMode.DAY -> s.selected.plus(-1, DateTimeUnit.DAY) to s.selected.plus(2, DateTimeUnit.DAY)
        ViewMode.AGENDA ->
            if (s.agendaPast) s.today.plus(DatePeriod(years = -1)) to s.today
            else s.today to s.today.plus(DatePeriod(years = 1))
    }

    fun setAgendaPast(past: Boolean) {
        if (_state.value.agendaPast == past) return
        _state.update { it.copy(agendaPast = past) }
        reload()
    }

    fun setView(view: ViewMode) {
        updateSettings { it.copy(lastView = view) }
        _state.update { it.copy(view = view) }
        reload()
    }

    /** Previous / next page of the current view: a year, a month, a week or a day. */
    fun step(dir: Int) {
        val s = _state.value
        val day = when (s.view) {
            ViewMode.YEAR -> s.selected.plus(DatePeriod(years = dir))
            ViewMode.MONTH, ViewMode.AGENDA -> s.month.plusMonths(dir).firstDay.let { if (YearMonth.of(s.today) == YearMonth.of(it)) s.today else it }
            ViewMode.WEEK -> s.selected.plus(7 * dir, DateTimeUnit.DAY)
            ViewMode.DAY -> s.selected.plus(dir, DateTimeUnit.DAY)
        }
        _state.update { it.copy(selected = day, month = YearMonth.of(day)) }
        reload()
    }

    /** Opens a day in another view (a year-view tap goes to that month). */
    fun open(day: LocalDate, view: ViewMode) {
        _state.update { it.copy(selected = day, month = YearMonth.of(day), view = view) }
        updateSettings { it.copy(lastView = view) }
        reload()
    }

    fun updateSettings(change: (AppSettings) -> AppSettings) {
        val before = _state.value.settings
        val next = change(before)
        if (next == before) return
        prefs.saveSettings(next)
        _state.update {
            if (next.visibility == before.visibility && next.hues == before.hues) it.copy(settings = next)
            else it.copy(
                settings = next,
                calendars = recolored(it.rawCalendars, next),
                occurrences = shownOf(it.allOccurrences, it.calendars, next, it.bridgeLinkedGoogle),
            )
        }
        if (next.weekStart != before.weekStart || next.hidePastInAgenda != before.hidePastInAgenda) reload()
    }

    suspend fun load(id: String): StoredEvent? = store.event(id)

    /** Events from a year back to two years ahead whose text contains [query]. */
    suspend fun search(query: String): List<Occurrence> {
        val t = todayNow
        val from = t.plus(DatePeriod(years = -1)).atStartOfDayIn(zone)
        val to = t.plus(DatePeriod(years = 2)).atStartOfDayIn(zone)
        val s = _state.value
        val hidden = s.calendars.filter { !s.settings.isShown(it) }.map { it.id }.toSet()
        return store.search(query, from, to).filter { it.calendarId !in hidden }
    }

    /**
     * Quick-add line: reads date/time words out of [text] (QuickAdd) and saves at
     * once to the default calendar. [done] gets the saved draft and event id.
     */
    fun quickAdd(text: String, done: (Result<Pair<EventDraft, String>>) -> Unit) {
        val s = _state.value
        val entry = app.olo.calendar.model.QuickAdd.parse(text, s.selected, todayNow)
        val base = newDraft(entry.date) ?: return done(Result.failure(IllegalStateException("저장할 캘린더가 없습니다.")))
        val draft = if (entry.start == null) {
            base.copy(title = entry.title, allDay = true, reminderMinutes = emptyList())
        } else {
            base.copy(title = entry.title, startTime = entry.start, endTime = entry.end ?: entry.start)
        }
        save(draft) { r -> done(r.map { draft to it }) }
        if (YearMonth.of(entry.date) != s.month) select(entry.date)
    }

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

    /** Turns an account's calendar sync on (or back off, for undo) and reloads; [done] gets whether the OS took it. */
    fun setAccountSync(account: String, on: Boolean, done: (Boolean) -> Unit = {}) {
        scope.launch {
            val ok = runCatching { store.setAccountSync(account, on) }.getOrDefault(false)
            done(ok)
            reload()
        }
    }

    fun setMasterSync(on: Boolean, done: (Boolean) -> Unit = {}) {
        scope.launch {
            val ok = runCatching { store.setMasterSync(on) }.getOrDefault(false)
            done(ok)
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

    /** A new event on [day]; at [start] for an hour when given (a tap in the time grid). */
    fun newDraft(day: LocalDate = _state.value.selected, start: kotlinx.datetime.LocalTime? = null): EventDraft? {
        val cal = _state.value.defaultCalendar ?: return null
        val base = EventDraft(calendarId = cal.id, startDate = day, endDate = day, timeZoneId = zone.id, reminderMinutes = listOf(10))
        if (start == null) return base
        val endHour = start.hour + 1
        return if (endHour >= 24) base.copy(startTime = start, endTime = kotlinx.datetime.LocalTime(23, 59))
        else base.copy(startTime = start, endTime = kotlinx.datetime.LocalTime(endHour, start.minute))
    }
}

/** One reason events might not reach the other phone. */
sealed interface SyncAction {
    data object MasterSyncOff : SyncAction
    data class AccountSyncOff(val account: String) : SyncAction
    data class Stranded(val calendar: CalendarInfo, val count: Int) : SyncAction
}

private fun AppSettings.hueOf(id: String): OloHue? = hues[id]?.let { n -> OloHue.entries.firstOrNull { it.name == n } }

private fun recolored(calendars: List<CalendarInfo>, settings: AppSettings): List<CalendarInfo> =
    if (settings.hues.isEmpty()) calendars
    else calendars.map { c -> settings.hueOf(c.id)?.let { c.copy(colorArgb = it.argb) } ?: c }

/** The occurrences OLO draws: hidden calendars out, OLO-chosen colours in. */
private fun shownOf(all: List<Occurrence>, calendars: List<CalendarInfo>, settings: AppSettings, bridged: Set<String> = emptySet()): List<Occurrence> {
    val hidden = calendars.filter { !settings.isShown(it) }.map { it.id }.toSet()
    // A bridged pair is one event: show the Samsung copy, unless Samsung Calendar is hidden.
    val samsungShown = settings.bridgeSamsungId?.let { id -> id !in hidden } == true
    val twins = if (samsungShown) bridged else emptySet()
    val shown = if (hidden.isEmpty() && twins.isEmpty()) all else all.filter { it.calendarId !in hidden && it.seriesId !in twins }
    if (settings.hues.isEmpty()) return shown
    return shown.map { o -> settings.hueOf(o.calendarId)?.let { o.copy(colorArgb = it.argb) } ?: o }
}

private class Loaded(
    val calendars: List<CalendarInfo>,
    val occurrences: List<Occurrence>,
    val sync: SyncStatus,
    val counts: Map<String, Int>,
)
