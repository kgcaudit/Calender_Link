package app.olo.calendar.ui

import app.olo.calendar.design.OloMotion
import app.olo.calendar.design.OloTone
import androidx.compose.animation.togetherWith
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.scaleOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import androidx.compose.animation.core.tween
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.animateTo
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.olo.calendar.design.OloIcons
import app.olo.calendar.design.OloLayout
import app.olo.calendar.design.calendarColor
import app.olo.calendar.model.EventDraft
import app.olo.calendar.model.Occurrence
import app.olo.calendar.model.StoredEvent
import app.olo.calendar.model.ViewMode
import app.olo.calendar.model.YearMonth
import app.olo.calendar.model.koShort
import app.olo.calendar.model.on
import app.olo.calendar.model.sortedForDay
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.toLocalDateTime

private sealed interface Sheet {
    data object None : Sheet
    data class Detail(val occurrence: Occurrence) : Sheet
    data class Editor(val draft: EventDraft, val stored: StoredEvent?) : Sheet
    data object Calendars : Sheet
    data object Sync : Sheet
    data class Move(val fromCalendarId: String) : Sheet
    data class Bridge(val samsungCalendarId: String) : Sheet
    data object Settings : Sheet
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun CalendarScreen(
    controller: CalendarController,
    settings: SystemSettings? = null,
    startStage: MonthStage? = null,
    startDrawerOpen: Boolean = false,
    startSearch: String? = null,
    startBridgeFor: String? = null,
    startSettings: Boolean = false,
) {
    val state by controller.state.collectAsState()
    var sheet by remember { mutableStateOf<Sheet>(startBridgeFor?.let { Sheet.Bridge(it) } ?: if (startSettings) Sheet.Settings else Sheet.None) }
    var searchOpen by remember { mutableStateOf(startSearch != null) }
    var pickerOpen by remember { mutableStateOf(false) }
    // "…동기화를 켰습니다 · 되돌리기" at the foot of the drawer / calendar sheet.
    var syncNote by remember { mutableStateOf<SyncNote?>(null) }
    LaunchedEffect(syncNote) { if (syncNote != null) { delay(5000); syncNote = null } }
    val holidayCalendars = state.calendars.filter { it.isHoliday }.map { it.id }.toSet()
    val stage = remember {
        AnchoredDraggableState(startStage ?: MonthStage.entries[(state.settings.monthStage - 1).coerceIn(0, 2)])
    }
    val drawer = rememberDrawerState(if (startDrawerOpen) DrawerValue.Open else DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    val dayItems = state.occurrences.on(state.selected).filter { it.calendarId !in holidayCalendars }.sortedForDay()
    val dayHolidays = holidayNames(state.selected, state.occurrences, holidayCalendars)
    val openEditor = { d: LocalDate, t: LocalTime? -> controller.newDraft(d, t)?.let { sheet = Sheet.Editor(it, null) }; Unit }

    BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
        val wide = maxWidth >= WideLayout

        // Back, one step at a time (docs/04): drawer / search → month view → stage 1 →
        // the month with today → leave the app (left to the system, so its
        // back-to-home preview still plays).
        val todayMonth = YearMonth.of(state.today)
        val canStepBack = drawer.isOpen || searchOpen || state.view != ViewMode.MONTH ||
            (!wide && stage.currentValue != MonthStage.FULL) || state.month != todayMonth
        BackHandler(enabled = canStepBack) {
            when {
                drawer.isOpen -> scope.launch { drawer.close() }
                searchOpen -> searchOpen = false
                state.view != ViewMode.MONTH -> controller.setView(ViewMode.MONTH)
                !wide && stage.currentValue != MonthStage.FULL -> scope.launch { stage.animateTo(MonthStage.FULL) }
                else -> controller.goToday()
            }
        }

        val closeDrawer = { scope.launch { drawer.close() }; Unit }
        val nav = calendarNav(
            state, controller, settings,
            note = syncNote,
            onNote = { syncNote = it },
            onMove = { id -> sheet = moveOrBridge(state, id); closeDrawer() },
            onView = { v -> controller.setView(v); closeDrawer() },
            onCalendars = { sheet = Sheet.Calendars; closeDrawer() },
            onSyncNow = controller::syncNow,
            onSettings = { sheet = Sheet.Settings; closeDrawer() },
        )
        val content: @Composable () -> Unit = {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                Column(Modifier.fillMaxSize()) {
                    TopBar(
                        title = viewTitle(state),
                        titleOrder = if (state.view == ViewMode.YEAR) state.selected.year * 12 else state.month.index,
                        today = state.today,
                        badge = state.needsSyncAttention && !wide,
                        onMenu = if (wide) null else ({ scope.launch { drawer.open() } }),
                        onArrows = if (wide) controller::step else null,
                        onTitle = { pickerOpen = true },
                        onSearch = { searchOpen = true },
                        onToday = controller::goToday,
                    )
                    state.error?.let { ErrorLine(it, controller::dismissError) }
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        // One view replacing another: shared axis Z, a slight zoom with a fade (docs/05 §6 #2).
                        AnimatedContent(
                            targetState = state.view,
                            transitionSpec = {
                                val deeper = targetState.ordinal > initialState.ordinal
                                (fadeIn(tween(OloMotion.Long, delayMillis = 60, easing = OloMotion.EmphasizedDecelerate)) +
                                    scaleIn(tween(OloMotion.Long, easing = OloMotion.EmphasizedDecelerate), initialScale = if (deeper) 0.92f else 1.08f)) togetherWith
                                    (fadeOut(tween(OloMotion.Quick)) + scaleOut(tween(OloMotion.Long, easing = OloMotion.EmphasizedAccelerate), targetScale = if (deeper) 1.06f else 0.94f))
                            },
                            modifier = Modifier.fillMaxSize(),
                            label = "view",
                        ) { view ->
                        when (view) {
                            ViewMode.MONTH -> MonthBody(
                                state, controller, holidayCalendars, wide, stage, controller::select,
                                dayItems, dayHolidays, openDetail = { sheet = Sheet.Detail(it) },
                            )
                            ViewMode.YEAR -> YearView(state, holidayCalendars, onOpen = { d -> controller.open(d, ViewMode.MONTH) }, onYear = controller::showYear)
                            ViewMode.WEEK -> TimeGridView(
                                state, controller, holidayCalendars, single = false,
                                onOpen = { sheet = Sheet.Detail(it) }, onNewAt = openEditor,
                                onPickDay = { d -> controller.open(d, ViewMode.DAY) },
                            )
                            ViewMode.DAY -> TimeGridView(
                                state, controller, holidayCalendars, single = true,
                                onOpen = { sheet = Sheet.Detail(it) }, onNewAt = openEditor,
                                onPickDay = {},
                            )
                            ViewMode.AGENDA -> AgendaView(state, controller, holidayCalendars, onOpen = { sheet = Sheet.Detail(it) })
                        }
                        }
                        // The quick-add line floats at the bottom; in the wide month it sits in the day pane.
                        val inPane = wide && state.view == ViewMode.MONTH
                        Box(
                            Modifier.align(Alignment.BottomEnd).fillMaxWidth(if (inPane) 0.4f else 1f).imePadding(),
                            contentAlignment = Alignment.BottomCenter,
                        ) {
                            QuickAddBar(
                                day = state.selected,
                                controller = controller,
                                onOpenEditor = { openEditor(state.selected, null) },
                                onEditSaved = { id -> scope.launch { controller.load(id)?.let { sheet = Sheet.Editor(it.draft, it) } } },
                            )
                        }
                    }
                }
                AnimatedVisibility(
                    visible = searchOpen,
                    enter = fadeIn(tween(OloMotion.Medium)) + slideInVertically(tween(OloMotion.Medium, easing = OloMotion.EmphasizedDecelerate)) { -it / 16 },
                    exit = fadeOut(tween(OloMotion.Quick)) + slideOutVertically(tween(OloMotion.Quick)) { -it / 16 },
                ) {
                    SearchScreen(
                        controller = controller,
                        initial = startSearch.orEmpty(),
                        onClose = { searchOpen = false },
                        onPick = { o ->
                            searchOpen = false
                            controller.open(o.firstDay, if (state.view == ViewMode.YEAR) ViewMode.MONTH else state.view)
                            sheet = Sheet.Detail(o)
                        },
                    )
                }
            }
        }
        if (wide) {
            Row(Modifier.fillMaxSize()) {
                NavRail(nav)
                Box(Modifier.weight(1f)) { content() }
            }
        } else {
            ModalNavigationDrawer(drawerState = drawer, drawerContent = { DrawerContent(nav) }) { content() }
        }
    }

    if (pickerOpen) {
        MonthPicker(
            current = state.month,
            today = state.today,
            onPick = { ym ->
                pickerOpen = false
                val day = if (YearMonth.of(state.today) == ym) state.today else ym.firstDay
                controller.open(day, if (state.view == ViewMode.YEAR) ViewMode.MONTH else state.view)
            },
            onDismiss = { pickerOpen = false },
        )
    }

    when (val s = sheet) {
        Sheet.None -> Unit
        is Sheet.Detail -> EventDetailSheet(
            occurrence = s.occurrence,
            controller = controller,
            calendars = state.calendarsById,
            onEdit = { stored -> sheet = Sheet.Editor(stored.draft, stored) },
            onDismiss = { sheet = Sheet.None },
        )
        is Sheet.Editor -> EventEditorSheet(
            initial = s.draft,
            stored = s.stored,
            calendars = state.calendars.filter { it.writable && !it.isHoliday },
            onSave = { d -> controller.save(d); sheet = Sheet.None },
            onDismiss = { sheet = Sheet.None },
        )
        Sheet.Calendars -> CalendarsSheet(
            nav = calendarNav(
                state, controller, settings, note = syncNote, onNote = { syncNote = it },
                onMove = { id -> sheet = moveOrBridge(state, id) }, onView = controller::setView,
                onCalendars = {}, onSyncNow = controller::syncNow, onSettings = { sheet = Sheet.Settings },
            ),
            onOpenSync = { sheet = Sheet.Sync },
            onDismiss = { sheet = Sheet.None },
        )
        Sheet.Sync -> SyncSheet(
            state = state,
            onSyncNow = controller::syncNow,
            onOpenSyncSettings = settings?.let { s2 -> { s2.openSyncSettings() } },
            onMove = { sheet = moveOrBridge(state, it) },
            onDismiss = { sheet = Sheet.None },
        )
        Sheet.Settings -> SettingsSheet(
            state = state,
            onChange = controller::updateSettings,
            onDismiss = { sheet = Sheet.None },
            onAllow = { account -> enableSync(account, state, controller, settings) { syncNote = it } },
        )
        is Sheet.Bridge -> BridgeSheet(
            samsung = state.calendarsById[s.samsungCalendarId],
            state = state,
            controller = controller,
            onEnableSync = { account -> enableSync(account, state, controller, settings) { syncNote = it } },
            onChanged = { on -> settings?.bridgeChanged(on) },
            onDismiss = { sheet = Sheet.None },
        )
        is Sheet.Move -> MoveSheet(
            from = state.calendarsById[s.fromCalendarId],
            targets = state.googleTargets,
            accountsWithSyncOff = state.sync.accountsWithSyncOff,
            controller = controller,
            onDismiss = { sheet = Sheet.None },
        )
    }
}

/** ☰ · 10월 ▾ · · · 검색 · 오늘. Every target is 48dp. */
@Composable
private fun TopBar(
    title: String,
    titleOrder: Int,
    today: LocalDate,
    badge: Boolean,
    onMenu: (() -> Unit)?,
    onArrows: ((Int) -> Unit)?,
    onTitle: () -> Unit,
    onSearch: () -> Unit,
    onToday: () -> Unit,
) {
    // The top bar is a clay-tinted band, as Explorer marks its path bar (docs/06 §4 ②).
    Row(
        Modifier.fillMaxWidth().background(OloTone.band).height(OloLayout.TopBarHeight).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onMenu != null) {
            Box {
                BarIcon(OloIcons.Menu, if (badge) "메뉴, 동기화 확인 필요" else "메뉴", onMenu)
                if (badge) Box(Modifier.align(Alignment.TopEnd).offset(x = (-11).dp, y = 11.dp).size(8.dp).background(MaterialTheme.colorScheme.error, CircleShape))
            }
        } else {
            Spacer(Modifier.width(12.dp))
        }
        Row(
            Modifier.height(OloLayout.TouchTarget).clickable(onClick = onTitle).padding(horizontal = 6.dp)
                .semantics { contentDescription = "$title, 년·월 고르기" },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The title flips like a card about its vertical axis, turning the way the
            // pages slide: later months turn to the left, earlier ones to the right.
            FlipTitle(title, titleOrder)
            Icon(OloIcons.ChevronDown, contentDescription = null, modifier = Modifier.size(18.dp).padding(start = 2.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (onArrows != null) {
            BarIcon(OloIcons.Back, "이전") { onArrows(-1) }
            Box(Modifier.size(OloLayout.TouchTarget).clickable { onArrows(1) }.semantics { contentDescription = "다음" }, contentAlignment = Alignment.Center) {
                Icon(OloIcons.Back, contentDescription = null, modifier = Modifier.size(OloLayout.IconSize).graphicsFlip())
            }
        }
        Spacer(Modifier.weight(1f))
        BarIcon(OloIcons.Search, "검색", onSearch)
        // Today: a small calendar square carrying today's date, as Samsung and Google show it.
        Box(
            Modifier.size(OloLayout.TouchTarget).clickable(onClick = onToday).semantics { contentDescription = "오늘로 이동" },
            contentAlignment = Alignment.Center,
        ) {
            // A solid clay tile: the same mark today wears in the grid.
            Box(Modifier.size(28.dp).background(OloTone.solid, RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
                Text("${today.dayOfMonth}", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = OloTone.onSolid(OloTone.solid))
            }
        }
    }
}

/**
 * "2027년 01월" in one face, size and colour. A change of title is a horizontal
 * card flip: the old face turns edge-on, the new one turns in from the other
 * side, in the direction the month pages moved.
 */
@Composable
private fun FlipTitle(title: String, order: Int) {
    var face by remember { mutableStateOf(title) }
    var lastOrder by remember { mutableStateOf(order) }
    val turn = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(title) {
        if (title == face) return@LaunchedEffect
        val dir = if (order >= lastOrder) -1f else 1f
        lastOrder = order
        turn.animateTo(90f * dir, tween(OloMotion.Quick, easing = OloMotion.EmphasizedAccelerate))
        face = title
        turn.snapTo(-90f * dir)
        turn.animateTo(0f, tween(OloMotion.Short, easing = OloMotion.EmphasizedDecelerate))
    }
    val density = androidx.compose.ui.platform.LocalDensity.current.density
    Text(
        face,
        // Tabular figures: 01–12 keep the same width, so the title never shifts.
        style = MaterialTheme.typography.headlineSmall.copy(fontFeatureSettings = "tnum"),
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        modifier = Modifier.graphicsLayer {
            rotationY = turn.value
            cameraDistance = 16f * density
        },
    )
}

private fun Modifier.graphicsFlip(): Modifier = this.graphicsLayer { scaleX = -1f }

@Composable
private fun BarIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Box(Modifier.size(OloLayout.TouchTarget).clickable(onClick = onClick).semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(OloLayout.IconSize))
    }
}

@Composable
internal fun ErrorLine(text: String, onClick: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).clickable(onClick = onClick),
    ) {
        Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(12.dp))
    }
}

/**
 * Samsung's "10월 16일에 추가" line. Type "내일 3시 회의" and press + / Enter: date and
 * time words are read out (QuickAdd) and the event is saved straight away.
 * Empty + opens the full editor. A note confirms what was saved, with an edit link.
 */
@Composable
private fun QuickAddBar(day: LocalDate, controller: CalendarController, onOpenEditor: () -> Unit, onEditSaved: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    var note by remember { mutableStateOf<Pair<String, String>?>(null) } // message, event id
    val focus = LocalFocusManager.current
    val use24 = LocalUse24Hour.current
    LaunchedEffect(note) { if (note != null) { delay(4500); note = null } }
    var lastNote by remember { mutableStateOf(note) }
    if (note != null) lastNote = note
    fun submit() {
        val t = text.trim()
        if (t.isEmpty()) { onOpenEditor(); return }
        controller.quickAdd(t) { r ->
            r.onSuccess { (d, id) ->
                val time = if (d.allDay) "종일" else clock(d.startTime, use24)
                note = "‘${d.title}’ ${d.startDate.monthNumber}월 ${d.startDate.dayOfMonth}일 $time 추가됨" to id
                text = ""
                focus.clearFocus()
            }
        }
    }
    Column(
        Modifier.padding(horizontal = 16.dp, vertical = 12.dp).widthIn(max = 460.dp).fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        AnimatedVisibility(
            visible = note != null,
            enter = slideInVertically(tween(OloMotion.Medium, easing = OloMotion.EmphasizedDecelerate)) { it } + fadeIn(tween(OloMotion.Medium)),
            exit = slideOutVertically(tween(OloMotion.Quick)) { it } + fadeOut(tween(OloMotion.Quick)),
        ) {
          lastNote?.let { (msg, id) ->
            Surface(color = MaterialTheme.colorScheme.inverseSurface, contentColor = MaterialTheme.colorScheme.inverseOnSurface, shape = MaterialTheme.shapes.small, shadowElevation = 4.dp) {
                Row(Modifier.padding(start = 14.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(msg, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    Text(
                        "편집", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.inversePrimary,
                        modifier = Modifier.clickable { note = null; onEditSaved(id) }.padding(horizontal = 12.dp, vertical = 12.dp),
                    )
                }
            }
        }
        }
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLowest,
            shadowElevation = 6.dp,
            modifier = Modifier.fillMaxWidth().height(56.dp),
        ) {
            Row(Modifier.padding(start = 22.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (text.isEmpty()) {
                        Text("${day.monthNumber}월 ${day.dayOfMonth}일에 추가", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    BasicTextField(
                        value = text, onValueChange = { text = it }, singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { submit() }),
                        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "빠른 일정 추가. 예: 내일 오후 3시 회의" },
                    )
                }
                // The brand's primary action: clay, as the FAB was.
                Box(
                    Modifier.size(44.dp).background(MaterialTheme.colorScheme.primary, CircleShape).clickable { submit() }
                        .semantics { contentDescription = if (text.isBlank()) "새 일정 만들기" else "추가" },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(OloIcons.Plus, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(22.dp))
                }
            }
        }
    }
}

/**
 * Search over title, location and notes across every year the calendars hold
 * (and up to five years ahead). Results run by date; the list opens at today,
 * so what is coming is in view and the past is a scroll up. The same event held
 * by two calendars is one row with both calendars' dots.
 */
@Composable
private fun SearchScreen(controller: CalendarController, initial: String, onClose: () -> Unit, onPick: (Occurrence) -> Unit) {
    var query by remember { mutableStateOf(initial) }
    var hits by remember { mutableStateOf<List<SearchHit>>(emptyList()) }
    var searched by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val use24 = LocalUse24Hour.current
    val list = rememberLazyListState()
    val today = controller.todayNow
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    LaunchedEffect(query) {
        if (query.isBlank()) { hits = emptyList(); searched = false; busy = false; return@LaunchedEffect }
        delay(250)
        busy = true
        hits = searchHits(runCatching { controller.search(query) }.getOrDefault(emptyList()))
        busy = false
        searched = true
    }
    val byDay = remember(hits) { hits.groupBy { it.first.firstDay }.toSortedMap(compareBy { it.toEpochDays() }) }
    // Rows before today's first result: a summary line, then a header and the events of each earlier day.
    val todayIndex = remember(byDay, today) {
        var i = 1
        for ((d, l) in byDay) { if (d >= today) return@remember i; i += 1 + l.size }
        maxOf(i - 1, 0)
    }
    LaunchedEffect(byDay) { if (byDay.isNotEmpty()) list.scrollToItem(todayIndex) }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().height(OloLayout.TopBarHeight).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                BarIcon(OloIcons.Back, "검색 닫기", onClose)
                Box(Modifier.weight(1f).padding(end = 12.dp), contentAlignment = Alignment.CenterStart) {
                    if (query.isEmpty()) Text("일정 제목, 장소, 메모 · 전체 기간", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    BasicTextField(
                        value = query, onValueChange = { query = it }, singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    )
                }
            }
            if (searched && hits.isEmpty()) {
                Text("‘$query’ 일정이 없습니다.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(24.dp))
            }
            // Each row's day, for the scroller's bubble (the summary row borrows the first day).
            val rowDays = remember(byDay) {
                buildList {
                    if (byDay.isNotEmpty()) add(byDay.firstKey())
                    byDay.forEach { (d, l) -> repeat(1 + l.size) { add(d) } }
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(state = list, contentPadding = PaddingValues(start = OloLayout.ContentMargin, end = OloLayout.ContentMargin + 12.dp, top = 8.dp, bottom = 8.dp), modifier = Modifier.fillMaxSize()) {
                if (byDay.isNotEmpty()) item(key = "summary") {
                    val years = byDay.firstKey().year to byDay.lastKey().year
                    val past = hits.count { it.first.firstDay < today }
                    Text(
                        listOf(
                            "${hits.size}건",
                            if (years.first == years.second) "${years.first}년" else "${years.first}–${years.second}년",
                            "지난 일정 $past",
                            "다가올 일정 ${hits.size - past}",
                        ).joinToString(" · ") + if (busy) " · 찾는 중" else "",
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                byDay.forEach { (d, dayHits) ->
                    item(key = "h" + d.toEpochDays()) {
                        val isToday = d == today
                        Text(
                            "${d.year}. ${d.monthNumber}. ${d.dayOfMonth}. (${d.dayOfWeek.koShort()})" + if (isToday) "  오늘" else "",
                            style = MaterialTheme.typography.labelLarge,
                            color = if (d < today) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f) else if (isToday) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = if (isToday) FontWeight.SemiBold else null,
                            modifier = Modifier.padding(top = 14.dp, bottom = 4.dp),
                        )
                    }
                    items(dayHits, key = { it.first.eventId + "@" + it.first.begin.toEpochMilliseconds() }) { (o, colors) ->
                        Row(Modifier.fillMaxWidth().clickable { onPick(o) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(width = 12.dp + 6.dp * (colors.size - 1), height = 12.dp)) {
                                colors.forEachIndexed { i, c -> Box(Modifier.offset(x = 6.dp * i)) { Tile(calendarColor(c), 12.dp) } }
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(o.title.ifBlank { "(제목 없음)" }, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    listOf(timeLabel(o, controller.zone, use24), o.location).filter { it.isNotBlank() }.joinToString("  ·  "),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
            FastScroller(list, label = { i -> rowDays.getOrNull(i)?.let { "${it.year}년 ${it.monthNumber}월" } })
            }
        }
    }
}

/** One search row: the event, and the colours of every calendar that holds the same event. */
private typealias SearchHit = Pair<Occurrence, List<Int>>

/** The same title at the same time in several calendars (copies, unlinked twins) is one row. */
internal fun searchHits(found: List<Occurrence>): List<Pair<Occurrence, List<Int>>> =
    found.groupBy { listOf(it.title.trim(), it.allDay, it.begin, it.end) }.values
        .map { same -> same.first() to same.map { it.colorArgb }.distinct() }
        .sortedBy { it.first.begin }

/** Tap on the title: jump to any month. */
@Composable
private fun MonthPicker(current: YearMonth, today: LocalDate, onPick: (YearMonth) -> Unit, onDismiss: () -> Unit) {
    var year by remember { mutableStateOf(current.year) }
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = MaterialTheme.shapes.extraLarge,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BarIcon(OloIcons.Back, "이전 해") { year-- }
                Text("${year}년", style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
                Box(Modifier.size(OloLayout.TouchTarget).clickable { year++ }.semantics { contentDescription = "다음 해" }, contentAlignment = Alignment.Center) {
                    Icon(OloIcons.Back, contentDescription = null, modifier = Modifier.size(OloLayout.IconSize).graphicsFlip())
                }
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                (1..12).chunked(4).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { m ->
                            val ym = YearMonth(year, m)
                            val isCurrent = ym == current
                            val isToday = ym == YearMonth.of(today)
                            Box(
                                Modifier.weight(1f).height(44.dp)
                                    .background(if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.shapes.small)
                                    .then(if (isToday && !isCurrent) Modifier.border(1.5.dp, MaterialTheme.colorScheme.primary, MaterialTheme.shapes.small) else Modifier)
                                    .clickable { onPick(ym) },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text("${m}월", color = if (isCurrent) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Medium)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { OloTextButton("이번 달", { onPick(YearMonth.of(today)) }) },
        dismissButton = { OloTextButton("닫기", onDismiss) },
    )
}


internal class SyncNote(val text: String, val undo: (() -> Unit)?)

/**
 * The tree's actions. Sync is fixed in place (docs/06 §3): OLO turns the
 * account's calendar sync on itself and offers undo; only when the phone
 * refuses does it open the account settings.
 */
private fun calendarNav(
    state: CalendarUiState,
    controller: CalendarController,
    settings: SystemSettings?,
    note: SyncNote?,
    onNote: (SyncNote?) -> Unit,
    onMove: (String) -> Unit,
    onView: (ViewMode) -> Unit,
    onCalendars: () -> Unit,
    onSyncNow: () -> Unit,
    onSettings: () -> Unit,
) = NavActions(
    state = state,
    onView = onView,
    onShown = controller::setShown,
    onShowOnly = controller::showOnly,
    onShowDefault = controller::showDefault,
    onFold = controller::setFolded,
    onEnableSync = { account -> enableSync(account, state, controller, settings, onNote) },
    onEnableMaster = {
        controller.setMasterSync(true) { ok ->
            if (ok) onNote(SyncNote("휴대폰 자동 동기화를 켰습니다") { controller.setMasterSync(false); onNote(null) })
            else settings?.openSyncSettings()
        }
    },
    onAccountSettings = { account -> settings?.openAccountSync(account) },
    onSyncExclude = { key, excluded ->
        controller.setSyncExcluded(key, excluded)
        val name = key.substringAfter('/')
        onNote(SyncNote(if (excluded) "$name 지금 동기화에서 제외" else "$name 지금 동기화에 다시 포함") { controller.setSyncExcluded(key, !excluded); onNote(null) })
    },
    onDisableSync = { account ->
        controller.setAccountSync(account, false) { ok ->
            if (ok) onNote(SyncNote("$account 캘린더 동기화를 껐습니다") { controller.setAccountSync(account, true); onNote(null) })
            else { onNote(SyncNote("휴대폰이 허용하지 않아 이 계정의 동기화 설정을 엽니다", null)); settings?.openAccountSync(account) }
        }
    },
    onMove = onMove,
    onHue = controller::setHue,
    onCalendars = onCalendars,
    onSyncNow = onSyncNow,
    onSettings = onSettings,
    note = note?.text,
    onUndo = note?.undo,
)

/** A Samsung-account calendar is joined to Google (the bridge); a phone-only one is moved. */
private fun moveOrBridge(state: CalendarUiState, calendarId: String): Sheet =
    if (state.calendarsById[calendarId]?.kind == app.olo.calendar.model.SyncKind.SAMSUNG) Sheet.Bridge(calendarId) else Sheet.Move(calendarId)

/**
 * Turns a Google account's calendar sync on. An account OLO may not see yet
 * goes through the system account chooser first (Android 8+); if the phone
 * still refuses, that account's own sync page opens.
 */
private fun enableSync(account: String, state: CalendarUiState, controller: CalendarController, settings: SystemSettings?, onNote: (SyncNote?) -> Unit) {
    val turnOn = {
        controller.setAccountSync(account, true) { ok ->
            if (ok) {
                onNote(SyncNote("$account 캘린더 동기화를 켰습니다") { controller.setAccountSync(account, false); onNote(null) })
            } else {
                onNote(SyncNote("휴대폰이 허용하지 않아 이 계정의 동기화 설정을 엽니다", null))
                settings?.openAccountSync(account)
            }
        }
    }
    if (account in state.sync.accountsNeedingAccess && settings != null) {
        settings.allowAccount(account) { allowed ->
            if (allowed) turnOn() else onNote(SyncNote("계정을 허용하지 않아 동기화를 켜지 못했습니다", null))
        }
    } else {
        turnOn()
    }
}
