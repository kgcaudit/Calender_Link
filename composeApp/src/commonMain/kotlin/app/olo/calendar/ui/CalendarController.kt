package app.olo.calendar.ui

import app.olo.calendar.model.EditScope
import app.olo.calendar.model.OccurrenceRef
import app.olo.calendar.model.ref
import kotlinx.datetime.Instant
import kotlin.time.Duration.Companion.days
import app.olo.calendar.model.hueOf
import app.olo.calendar.model.shownOccurrences
import app.olo.calendar.model.AppSettings
import app.olo.calendar.model.CalendarInfo
import app.olo.calendar.model.SyncKind
import app.olo.calendar.model.syncAccounts
import app.olo.calendar.model.syncKey
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime

/** A deletion waiting out its "실행 취소" moment (docs/10 §3): hidden on screen, not yet sent to the store. */
data class PendingDelete(
    val ref: OccurrenceRef,
    val scope: EditScope,
    val title: String,
    /** A lunar repeat's events (docs/11): hidden by id, as each year is its own event. */
    val ids: Set<String> = emptySet(),
) {
    fun hides(o: Occurrence): Boolean = if (ids.isNotEmpty()) o.eventId in ids else ref.covers(o, scope)
}

data class CalendarUiState(
    /** The deletion that can still be undone; its occurrences are already off the screen. */
    val pendingDelete: PendingDelete? = null,
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
    /** The last "지금 동기화", or the one running now. */
    val syncRun: SyncRun? = null,
    val loading: Boolean = true,
    val error: String? = null,
    val view: ViewMode = ViewMode.MONTH,
    /** Agenda tab: false = 다가오는 일정 (from today), true = 지난 일정 (back from today). */
    val agendaPast: Boolean = false,
    val settings: AppSettings = AppSettings(),
    /** Google-side ids of bridged pairs: shown once, from Samsung (docs/07). */
    val bridgeLinkedGoogle: Set<String> = emptySet(),
    val bridgeReport: BridgeReport? = null,
    /**
     * When OLO opened, the last pass was this old (millis since) — over 30
     * minutes means the background passes did not run while OLO was closed
     * (Samsung's battery management, usually). Null when they kept up.
     */
    val bridgeStaleFor: Long? = null,
    /** Why the last pass failed, if it did (the report then stays the old one). */
    val bridgeError: String? = null,
    /** done / total while a bridge pass runs; null otherwise. */
    val bridgeProgress: Pair<Int, Int>? = null,
    /** Twins found after the last pass (Samsung + Google), shown on the main card. */
    val bridgeDuplicates: Int = 0,
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

    /** Whether anything stops events from reaching the other phone (docs/05 §3.4). */
    private val syncBlocked: Boolean
        get() {
            if (!sync.masterSyncOn) return true
            // Only the account that carries events to the iPhone matters: the Google main
            // account when the bridge is on, else any Google account switched off.
            val main = bridgeGoogle?.accountName
            val accountOff = if (main != null) main in sync.accountsWithSyncOff || main in sync.accountsNeedingAccess else sync.accountsWithSyncOff.isNotEmpty()
            return accountOff || stranded.isNotEmpty()
        }

    /**
     * Accounts left out of "지금 동기화" right now (docs/07 §17), read live so the
     * drawer line follows a change made after the last run.
     */
    val syncExcludedCount: Int get() = syncAccounts(calendars).count { it.syncKey in settings.syncExcluded }

    /** "허용 필요" accounts still asked (excluded ones are left alone on purpose). */
    val syncUnreachable: Int
        get() = syncAccounts(calendars).count { it.accountName in sync.accountsNeedingAccess && it.syncKey !in settings.syncExcluded }

    val needsSyncAttention: Boolean get() = syncBlocked || (bridgeReport?.heldDeletes ?: 0) > 0 || bridgeDuplicates > 0

    fun isShown(c: CalendarInfo): Boolean = settings.isShown(c)

    /** Where a new event goes: the chosen default, else [autoCalendar]. */
    val defaultCalendar: CalendarInfo?
        get() = calendars.firstOrNull { it.id == settings.defaultCalendarId && it.writable && !it.isHoliday } ?: autoCalendar

    /**
     * "자동" (docs/07 §14): with the bridge on, Samsung Calendar — Galaxy devices
     * see the event at once and the bridge carries it to the iPhone. Before
     * linking, the Google main calendar ([googleMainGuess]). Name order only last.
     */
    val autoCalendar: CalendarInfo?
        // Shown calendars first, so a new event never lands somewhere the person cannot see it.
        get() = calendars.filter { it.writable && !it.isHoliday }.sortedBy { !isShown(it) }.let { w ->
            bridgeSamsung?.takeIf { bridgeGoogle != null && it in w }
                ?: googleMainGuess?.takeIf { it in w }
                ?: w.firstOrNull { it.kind.reachesGoogle && it.isPrimary }
                ?: w.firstOrNull { it.kind.reachesGoogle }
                ?: w.firstOrNull()
        }

    /**
     * The Google main calendar before the bridge names one: the linked one if
     * set; else a primary Google calendar, preferring the account Samsung
     * Calendar signs in with, then accounts that sync and OLO can see, then the
     * one holding most events.
     */
    val googleMainGuess: CalendarInfo?
        get() {
            bridgeGoogle?.let { return it }
            val samsungAccount = calendars.firstOrNull { it.kind == SyncKind.SAMSUNG && it.writable }?.accountName
            return calendars.filter { it.kind == SyncKind.GOOGLE && it.isPrimary && it.writable && !it.isHoliday }
                .sortedWith(
                    compareBy<CalendarInfo>(
                        { it.accountName != samsungAccount },
                        { it.accountName in sync.accountsNeedingAccess || it.accountName in sync.accountsWithSyncOff },
                        { -(eventCounts[it.id] ?: 0) },
                        { it.accountName },
                    ),
                ).firstOrNull()
        }
}

/** Holds what the calendar screens show and reloads it whenever the store changes. */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class CalendarController(
    private val store: CalendarStore,
    private val scope: CoroutineScope,
    val zone: TimeZone = TimeZone.currentSystemDefault(),
    private val prefs: Preferences = MemoryPreferences(),
    /** Events, what is shown, or the bridge's state changed: the platform refreshes its widgets. */
    private val onChanged: () -> Unit = {},
) {
    val todayNow: LocalDate get() = Clock.System.now().toLocalDateTime(zone).date

    private val _state = MutableStateFlow(
        todayNow.let {
            val settings = prefs.loadSettings()
            CalendarUiState(month = YearMonth.of(it), selected = it, today = it, view = settings.lastView, settings = settings)
        },
    )
    val state: StateFlow<CalendarUiState> = _state.asStateFlow()

    val bridge = BridgeEngine(store, prefs)
    private val lunar = app.olo.calendar.store.LunarRepeats(store, prefs) { ids -> bridge.noteIntendedDeletes(ids) }

    init {
        val last = bridge.lastReport
        val idle = last?.let { Clock.System.now().toEpochMilliseconds() - it.atMillis }
        _state.update {
            it.copy(
                bridgeLinkedGoogle = bridge.linkedGoogleIds(), bridgeReport = last,
                bridgeStaleFor = idle?.takeIf { ms -> it.settings.bridgeOn && ms > 30 * 60_000L },
            )
        }
        reload()
        // Lunar repeats stay ten years ahead (docs/11 §2).
        scope.launch {
            // Years deleted in another app leave the repeat first, so it extends from what is really there.
            runCatching { lunar.prune { id -> store.event(id) != null } }
            if (runCatching { lunar.topUp(todayNow) }.getOrDefault(0) > 0) { reload(); onChanged() }
        }
        scope.launch {
            // A burst of writes (a sync pulling many events) reloads once.
            store.changes().debounce(300).collect { reload() }
        }
        scope.launch {
            // While OLO is open the bridge follows every change within seconds; the
            // platform's background work covers the rest. Its own writes settle in one pass.
            store.changes().debounce(3_000).collect { if (_state.value.settings.bridgeOn) runBridge(trigger = "변경 감지") }
        }
        if (_state.value.settings.bridgeOn) runBridge(trigger = "앱 열 때")
    }

    private fun bridgeConfig(): BridgeConfig? = _state.value.settings.let { st ->
        if (st.bridgeSamsungId != null && st.bridgeGoogleId != null) BridgeConfig(st.bridgeSamsungId, st.bridgeGoogleId) else null
    }

    /** What connecting these two calendars would do, without writing anything. */
    suspend fun previewBridge(samsungId: String, googleId: String): BridgePlan = bridge.preview(BridgeConfig(samsungId, googleId))

    /** Joins Samsung Calendar to the Google main calendar and runs the first pass. */
    fun startBridge(samsungId: String, googleId: String, done: (Result<BridgeReport>) -> Unit = {}) {
        val changed = _state.value.settings.bridgeSamsungId != samsungId || _state.value.settings.bridgeGoogleId != googleId
        scope.launch {
            // Under the bridge's lock, so a pass still running for the old pair finishes first.
            if (changed) bridge.forget()
            updateSettings { it.copy(bridgeSamsungId = samsungId, bridgeGoogleId = googleId) }
            runBridge(trigger = "연결", done = done)
        }
    }

    /** One pass now. [allowMassDelete] releases deletions held by the safety stop. */
    fun runBridge(allowMassDelete: Boolean = false, retryRefused: Boolean = false, trigger: String = "앱", done: (Result<BridgeReport>) -> Unit = {}) {
        val config = bridgeConfig() ?: return
        scope.launch { done(bridgePass(config, allowMassDelete, retryRefused, trigger)) }
    }

    private suspend fun bridgePass(config: BridgeConfig, allowMassDelete: Boolean, retryRefused: Boolean = false, trigger: String = "앱"): Result<BridgeReport> {
        run {
            val r = runCatching {
                bridge.run(config, allowMassDelete, retryRefused, trigger) { d, t -> if (t > 0) _state.update { it.copy(bridgeProgress = d to t) } }
            }
            // Stopped or re-pointed while this pass ran: its result belongs to a pair no longer shown.
            if (bridgeConfig() != config) {
                _state.update { it.copy(bridgeProgress = null) }
                return r
            }
            _state.update {
                it.copy(
                    bridgeProgress = null,
                    bridgeLinkedGoogle = bridge.linkedGoogleIds(),
                    bridgeReport = r.getOrNull() ?: it.bridgeReport,
                    bridgeError = r.exceptionOrNull()?.let { e -> e.message ?: e::class.simpleName ?: "알 수 없는 오류" },
                    occurrences = shownNow(it.allOccurrences, it.calendars, it.settings, bridge.linkedGoogleIds()),
                )
            }
            val d = runCatching { bridge.findDuplicates(config) }.getOrNull()
            if (d != null) _state.update { it.copy(bridgeDuplicates = d.first + d.second) }
            onChanged()
            if (r.getOrNull()?.let { it.toGoogle + it.toSamsung + it.deleted > 0 } == true) store.requestSync()
            return r
        }
    }

    /** Exact twins in (Samsung, Google), e.g. left by the overlapping passes of 0.8.0–0.8.1. */
    suspend fun bridgeDuplicates(): Pair<List<String>, List<String>> =
        bridgeConfig()?.let { bridge.duplicateTitles(it) } ?: (emptyList<String>() to emptyList())

    fun removeBridgeDuplicates(done: (Result<Int>) -> Unit = {}) {
        val config = bridgeConfig() ?: return
        scope.launch {
            _state.update { it.copy(bridgeProgress = 0 to 0) }
            val r = runCatching { bridge.removeDuplicates(config) }
            _state.update {
                it.copy(
                    bridgeProgress = null,
                    bridgeDuplicates = 0,
                    bridgeLinkedGoogle = bridge.linkedGoogleIds(),
                    bridgeReport = bridge.lastReport ?: it.bridgeReport,
                    occurrences = shownNow(it.allOccurrences, it.calendars, it.settings, bridge.linkedGoogleIds()),
                )
            }
            store.requestSync()
            reload()
            done(r)
        }
    }

    /** What vanished on one side and waits for a yes: (title, side it is still on). */
    suspend fun bridgePending(): List<Pair<String, String>> = bridgeConfig()?.let { bridge.pendingDeletes(it) } ?: emptyList()

    /** Titles of the events a pass deleted by itself in the last day (docs/07 §18). */
    fun bridgeAutoDeleted(): List<app.olo.calendar.store.AutoDeleted> = if (bridgeConfig() == null) emptyList() else bridge.recentAutoDeletedDetail

    /** Brings the recent automatic deletions back into both calendars. */
    fun undoBridgeAutoDeleted() {
        val config = bridgeConfig() ?: return
        scope.launch {
            runCatching { bridge.undoAutoDeleted(config) }
            _state.update { it.copy(bridgeReport = bridge.lastReport, bridgeLinkedGoogle = bridge.linkedGoogleIds()) }
            store.requestSync()
            reload()
        }
    }

    fun keepBridgeAutoDeleted() = bridge.keepAutoDeleted()

    fun dismissBridgeStale() = _state.update { it.copy(bridgeStaleFor = null) }

    /** Keep what vanished on one side: copy it back instead of deleting the partner. */
    fun keepBridgePending() {
        val config = bridgeConfig() ?: return
        scope.launch {
            runCatching { bridge.keepPending(config) }.onSuccess { r -> _state.update { it.copy(bridgeReport = r, bridgeLinkedGoogle = bridge.linkedGoogleIds()) } }
            store.requestSync()
            reload()
        }
    }

    suspend fun bridgeRecoverable(samsungId: String, googleId: String): List<String> =
        bridge.recoverable(BridgeConfig(samsungId, googleId)).map { it.fields.title }

    /** Brings back events a wrong deletion took, into Samsung Calendar. */
    fun restoreDeleted(samsungId: String, googleId: String, done: (Int) -> Unit = {}) {
        scope.launch {
            _state.update { it.copy(bridgeProgress = 0 to 0) }
            val n = runCatching { bridge.restore(BridgeConfig(samsungId, googleId), connected = _state.value.settings.bridgeOn) }.getOrDefault(0)
            _state.update { it.copy(bridgeProgress = null, bridgeLinkedGoogle = bridge.linkedGoogleIds(), bridgeReport = bridge.lastReport ?: it.bridgeReport) }
            store.requestSync()
            reload()
            done(n)
        }
    }

    val bridgeEntries: List<app.olo.calendar.model.LogEntry> get() = bridge.entries
    val bridgeLastQuiet: Long? get() = bridge.lastQuietCheck
    val bridgeLog: List<String> get() = bridge.log

    /** Stops the bridge; both calendars keep every event they hold. */
    fun stopBridge() {
        updateSettings { it.copy(bridgeSamsungId = null, bridgeGoogleId = null) }
        _state.update { it.copy(bridgeLinkedGoogle = emptySet(), bridgeReport = null, occurrences = shownNow(it.allOccurrences, it.calendars, it.settings, emptySet())) }
        scope.launch { bridge.forget() }
    }

    /**
     * A month page settled. Today stays the selected day: another month shows no
     * selection until a day is tapped (no jump to the same day number there).
     * Back in today's month, today is selected again.
     */
    fun showMonth(month: YearMonth) {
        val s0 = _state.value
        if (s0.month == month) return
        _state.update { s -> s.copy(month = month, selected = if (YearMonth.of(s.today) == month) s.today else s.selected) }
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

    /** Leaves an account ("type/name") out of "지금 동기화", or takes it back (docs/07 §17). */
    fun setSyncExcluded(key: String, excluded: Boolean) =
        updateSettings { it.copy(syncExcluded = if (excluded) it.syncExcluded + key else it.syncExcluded - key) }

    fun goToday() {
        val t = todayNow
        _state.update { it.copy(selected = t, month = YearMonth.of(t), today = t) }
        reload()
    }

    fun reload() {
        scope.launch { reloadNow() }
    }

    /** Counts reloads, so one that finishes after a newer one does not write its older range back. */
    private var reloadGeneration = 0

    private suspend fun reloadNow() {
        val generation = ++reloadGeneration
        run {
            val s = _state.value
            val (fromDay, toDay) = range(s)
            val from = fromDay.atStartOfDayIn(zone)
            val to = toDay.atStartOfDayIn(zone)
            runCatching {
                Loaded(store.calendars(), store.occurrences(from, to), store.syncStatus(), store.eventCounts())
            }.onSuccess { l ->
                if (generation != reloadGeneration) return@onSuccess
                _state.update {
                    it.copy(
                        rawCalendars = l.calendars, calendars = recolored(l.calendars, it.settings),
                        allOccurrences = l.occurrences, sync = l.sync, eventCounts = l.counts,
                        occurrences = shownNow(l.occurrences, l.calendars, it.settings, it.bridgeLinkedGoogle),
                        loading = false, error = null, today = todayNow,
                    )
                }
            }.onFailure { e ->
                if (generation != reloadGeneration) return@onFailure
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
            // A month away: the selection stays as it is (see [showMonth]).
            ViewMode.MONTH, ViewMode.AGENDA -> { showMonth(s.month.plusMonths(dir)); return }
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
                occurrences = shownNow(it.allOccurrences, it.calendars, next, it.bridgeLinkedGoogle),
            )
        }
        if (next.weekStart != before.weekStart) reload()
        if (next.visibility != before.visibility || next.hues != before.hues || next.bridgeSamsungId != before.bridgeSamsungId ||
            next.bridgeGoogleId != before.bridgeGoogleId || next.weekStart != before.weekStart || next.showLunar != before.showLunar ||
            next.widgetBackground != before.widgetBackground) onChanged()
    }

    suspend fun load(id: String): StoredEvent? = store.event(id)

    /**
     * Every event whose text contains [query], however long ago; ahead, up to
     * [SEARCH_YEARS_AHEAD] years (a series that never ends would go on forever).
     * Shown and coloured as the calendar views show them: hidden calendars left
     * out, a bridged Google twin folded into its Samsung event.
     */
    suspend fun search(query: String): List<Occurrence> {
        val from = LocalDate(1900, 1, 1).atStartOfDayIn(zone)
        val to = todayNow.plus(DatePeriod(years = SEARCH_YEARS_AHEAD)).atStartOfDayIn(zone)
        val s = _state.value
        return shownNow(store.search(query, from, to), s.calendars, s.settings, s.bridgeLinkedGoogle)
    }

    /**
     * Quick-add line: reads date/time words out of [text] (QuickAdd) and saves at
     * once to the default calendar. [done] gets the saved draft and event id.
     */
    fun quickAdd(text: String, done: (Result<Pair<EventDraft, String>>) -> Unit) {
        val s = _state.value
        val entry = app.olo.calendar.model.QuickAdd.parse(text, s.selected, todayNow)
        val base = newDraft(entry.date) ?: run {
            noCalendar()
            return done(Result.failure(IllegalStateException(NO_CALENDAR)))
        }
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
            // An edit in Samsung Calendar may come back as a new event (the provider kept the old
            // one's length): the bridge pair moves with it, under the bridge's lock so no pass sees
            // the old event gone in between, and Google gets an edit, not a delete and a copy.
            // Every lunar year (docs/11): written as one event a year; an existing event becomes the first of them.
            if (draft.lunarYearly) {
                val r = runCatching {
                    val ids = lunar.create(draft.copy(id = null))
                    draft.id?.let { old -> bridge.noteIntendedDeletes(setOf(old)); store.delete(old) }
                    ids.firstOrNull().orEmpty()
                }
                r.onFailure { e -> _state.update { it.copy(error = "저장하지 못했습니다." + (e.message?.let { m -> " ($m)" } ?: "")) } }
                done(r)
                reload()
                onChanged()
                return@launch
            }
            val oldId = draft.id
            val r = runCatching {
                if (oldId != null && draft.calendarId == _state.value.settings.bridgeSamsungId) bridge.saveBridged({ store.save(draft) }, oldId)
                else store.save(draft)
            }
            r.onFailure { e -> _state.update { it.copy(error = "저장하지 못했습니다." + (e.message?.let { m -> " ($m)" } ?: "")) } }
            done(r)
            reload()
            onChanged()
        }
    }

    /** The lunar repeat [eventId] belongs to (docs/11), if any. */
    fun lunarGroupOf(eventId: String): app.olo.calendar.model.LunarGroup? = lunar.groupOf(eventId)

    /** Saves [draft], opened on [eventId] of a lunar repeat, for [reach] of it. */
    fun saveLunar(draft: EventDraft, eventId: String, reach: EditScope) {
        scope.launch {
            runCatching { lunar.save(draft, eventId, reach) }
                .onFailure { e -> _state.update { it.copy(error = "저장하지 못했습니다." + (e.message?.let { m -> " ($m)" } ?: "")) } }
            reload()
            onChanged()
        }
    }

    /** Saves [draft] for [reach] of a repeating event, from occurrence [at] (docs/10 §2). */
    fun saveOccurrence(draft: EventDraft, at: OccurrenceRef, reach: EditScope) {
        scope.launch {
            runCatching { store.saveOccurrence(draft, at, reach) }
                .onFailure { e -> _state.update { it.copy(error = "저장하지 못했습니다." + (e.message?.let { m -> " ($m)" } ?: "")) } }
            reload()
            onChanged()
        }
    }

    private var pendingJob: Job? = null
    /** Outlives the screen, so a deletion left pending when OLO goes away still happens. */
    private val detached = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Deletes [reach] of [o] after a moment in which "실행 취소" takes it back
     * (docs/10 §3). Nothing reaches the store, Samsung Calendar or the bridge
     * until then, so an undone deletion leaves no trace on the iPhone either.
     */
    fun delete(o: Occurrence, reach: EditScope = EditScope.ALL) {
        flushPendingDelete()
        val ids = lunar.groupOf(o.eventId)?.reach(o.eventId, reach).orEmpty()
        val p = PendingDelete(o.ref(), reach, o.title, ids)
        _state.update { it.copy(pendingDelete = p, occurrences = it.occurrences.filterNot { x -> p.hides(x) }) }
        pendingJob = scope.launch { delay(UNDO_MILLIS); commitDelete(p) }
    }

    fun undoDelete() {
        pendingJob?.cancel()
        pendingJob = null
        if (_state.value.pendingDelete == null) return
        _state.update { it.copy(pendingDelete = null) }
        _state.update { it.copy(occurrences = shownNow(it.allOccurrences, it.calendars, it.settings, it.bridgeLinkedGoogle)) }
    }

    /** Sends a pending deletion now (OLO leaves the screen, or another deletion follows). */
    fun flushPendingDelete() {
        val p = _state.value.pendingDelete ?: return
        pendingJob?.cancel()
        pendingJob = null
        detached.launch { commitDelete(p) }
    }

    private suspend fun commitDelete(p: PendingDelete) {
        if (_state.value.pendingDelete != p) return
        _state.update { it.copy(pendingDelete = null) }
        // Deleted here on purpose: the bridge carries it over without its safety stop (docs/11 §3).
        bridge.noteIntendedDeletes(
            when {
                p.ids.isNotEmpty() -> emptySet() // the lunar store reports its own
                p.scope == EditScope.ALL -> setOf(p.ref.seriesId)
                else -> emptySet()
            },
        )
        runCatching { if (p.ids.isNotEmpty()) lunar.delete(p.ref.eventId, p.scope) else store.deleteOccurrence(p.ref, p.scope) }
            .onFailure { e -> _state.update { it.copy(error = "삭제하지 못했습니다." + (e.message?.let { m -> " ($m)" } ?: "")) } }
        reload()
        onChanged()
    }

    /** [shownOccurrences] less what a pending deletion has taken off the screen. */
    private fun shownNow(all: List<Occurrence>, calendars: List<CalendarInfo>, settings: AppSettings, bridged: Set<String>): List<Occurrence> {
        val shown = shownOccurrences(all, calendars, settings, bridged)
        val p = _state.value.pendingDelete ?: return shown
        return shown.filterNot { p.hides(it) }
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

    /**
     * "지금 동기화" (docs/04): asks every syncing account (Google, Exchange, Samsung)
     * to sync, waits while the phone works on it, then runs a bridge pass so
     * Samsung Calendar and the Google main calendar agree, and reports what came
     * in. Accounts OLO may not see yet are counted, since the phone may ignore them.
     */
    fun syncNow() {
        if (_state.value.syncRun?.running == true) return
        scope.launch {
            val before = _state.value.eventCounts.values.sum()
            _state.update { it.copy(syncRun = SyncRun(running = true)) }
            val excluded = _state.value.settings.syncExcluded
            store.requestSync(excluded)
            delay(1500)
            var waited = 0
            while (waited < 60 && runCatching { store.syncActive(excluded) }.getOrDefault(false)) { delay(1000); waited++ }
            val bridged = bridgeConfig()?.let { bridgePass(it, allowMassDelete = false, trigger = "지금 동기화").getOrNull() }
            reloadNow()
            val s = _state.value
            val after = s.eventCounts.values.sum()
            _state.update {
                it.copy(
                    syncRun = SyncRun(
                        running = false, atMillis = Clock.System.now().toEpochMilliseconds(),
                        added = maxOf(0, after - before), removed = maxOf(0, before - after),
                        matched = bridged?.let { b -> b.toGoogle + b.toSamsung },
                    ),
                )
            }
        }
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

    /** The occurrence of [eventId] that begins at [beginMillis] (a widget tap), or its nearest one that day. */
    suspend fun findOccurrence(eventId: String, beginMillis: Long): Occurrence? {
        val at = Instant.fromEpochMilliseconds(beginMillis)
        val near = runCatching { store.occurrences(at.minus(1.days), at.plus(1.days)) }.getOrDefault(emptyList()).filter { it.eventId == eventId }
        return near.firstOrNull { it.begin == at } ?: near.minByOrNull { (it.begin - at).absoluteValue }
    }

    /** Nowhere to put a new event (every calendar read-only or hidden): say so instead of doing nothing. */
    fun noCalendar() = _state.update { it.copy(error = NO_CALENDAR) }

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

/** A "지금 동기화" run: running, or how the last one ended. */
data class SyncRun(
    val running: Boolean,
    val atMillis: Long? = null,
    val added: Int = 0,
    val removed: Int = 0,
    /** Events the bridge copied between Samsung Calendar and the Google main calendar; null when not linked. */
    val matched: Int? = null,
)

/** One reason events might not reach the other phone. */


private fun recolored(calendars: List<CalendarInfo>, settings: AppSettings): List<CalendarInfo> =
    if (settings.hues.isEmpty()) calendars
    else calendars.map { c -> settings.hueOf(c.id)?.let { c.copy(colorArgb = it.argb) } ?: c }

/** How far ahead search follows a repeating event (docs/04 §검색). */
const val SEARCH_YEARS_AHEAD = 5

private class Loaded(
    val calendars: List<CalendarInfo>,
    val occurrences: List<Occurrence>,
    val sync: SyncStatus,
    val counts: Map<String, Int>,
)

private const val NO_CALENDAR = "새 일정을 저장할 수 있는 캘린더가 없습니다."

/** How long "실행 취소" is offered after a deletion. */
const val UNDO_MILLIS = 5_000L
