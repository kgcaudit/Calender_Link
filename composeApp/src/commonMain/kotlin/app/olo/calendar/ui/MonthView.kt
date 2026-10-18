package app.olo.calendar.ui

import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import app.olo.calendar.design.OloMotion
import app.olo.calendar.design.OloTone
import app.olo.calendar.design.OloHue
import app.olo.calendar.design.argb
import androidx.compose.ui.draw.drawBehind
import app.olo.calendar.model.YearMonth
import app.olo.calendar.model.weekStartOf
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.gestures.animateTo
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.FloatingActionButton
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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.olo.calendar.design.OloLayout
import app.olo.calendar.design.calendarColor
import app.olo.calendar.design.saturdayColor
import app.olo.calendar.design.softFill
import app.olo.calendar.model.CalendarInfo
import app.olo.calendar.model.EventDraft
import app.olo.calendar.model.KoreanHolidays
import app.olo.calendar.model.KoreanLunar
import app.olo.calendar.model.Occurrence
import app.olo.calendar.model.StoredEvent
import app.olo.calendar.model.ViewMode
import app.olo.calendar.model.WeekSegment
import app.olo.calendar.model.isoWeekNumber
import app.olo.calendar.model.koShort
import app.olo.calendar.model.layoutWeek
import app.olo.calendar.model.monthGrid
import app.olo.calendar.model.on
import app.olo.calendar.model.sortedForDay
import app.olo.calendar.model.weekHeader
import kotlin.math.abs
import kotlinx.coroutines.launch
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.plus
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

internal val NumberRow = 22.dp
internal val LaneHeight = 16.dp
internal val WideLayout = OloLayout.WideBreakpoint
private val LineLane = 5.dp

// Break titles between phrases rather than mid-word where the platform supports it.
internal val TitleStyle = TextStyle(lineBreak = LineBreak.Heading)
internal val SegmentText = TextStyle(fontSize = 10.sp, lineHeight = 13.sp)

/** Phone month-view stages (docs/04): full month, lines + day, one week + day. */
enum class MonthStage { FULL, LINES, WEEK }

/** Holiday names for a day: the built-in Korean list plus any synced holiday calendar, without repeats. */
internal fun holidayNames(day: LocalDate, occurrences: List<Occurrence>, holidayCalendars: Set<String>): List<String> =
    (KoreanHolidays.on(day).map { it.name } + occurrences.on(day).filter { it.calendarId in holidayCalendars }.map { it.title })
        .map { it.trim() }.filter { it.isNotEmpty() }.distinct()

/**
 * Whether a day is a holiday, for the red numbers of every cell. The days the
 * holiday calendars cover are worked out once per list (a year view asks for
 * thousands of days), not by scanning every event for each cell.
 */
internal fun isHoliday(day: LocalDate, occurrences: List<Occurrence>, holidayCalendars: Set<String>): Boolean =
    KoreanHolidays.on(day).isNotEmpty() || day in holidayDays(occurrences, holidayCalendars)

private var holidayDaysCache: Triple<List<Occurrence>, Set<String>, Set<LocalDate>>? = null

private fun holidayDays(occurrences: List<Occurrence>, holidayCalendars: Set<String>): Set<LocalDate> {
    holidayDaysCache?.let { (o, c, days) -> if (o === occurrences && c == holidayCalendars) return days }
    val days = buildSet {
        occurrences.filter { it.calendarId in holidayCalendars && it.title.isNotBlank() }.forEach { o ->
            var d = o.firstDay
            var guard = 0
            while (d <= o.lastDay && guard++ < 400) { add(d); d = d.plus(1, DateTimeUnit.DAY) }
        }
    }
    holidayDaysCache = Triple(occurrences, holidayCalendars, days)
    return days
}

/**
 * What the month grid draws as bars. A holiday calendar the person turned on
 * (Samsung's 공휴일, Google's 대한민국의 휴일) shows its names as rose bars, as
 * Samsung Calendar does — so ticking it makes 크리스마스 appear in the cell, not
 * only a red number (docs/06 §7). Off, OLO's own list still reddens the day.
 */
internal fun gridEvents(occurrences: List<Occurrence>, holidayCalendars: Set<String>): List<Occurrence> =
    occurrences.map { if (it.calendarId in holidayCalendars) it.copy(colorArgb = OloHue.AUDIO.argb) else it }
        .distinctBy { if (it.calendarId in holidayCalendars) "h|${it.firstDay}|${it.title.trim()}" else it.eventId + "@" + it.begin }

/**
 * Month view. Wide: grid beside the selected day. Phone: three stages driven by a
 * vertical drag on the grid or the day list (AnchoredDraggable); the list hands
 * its leftover scroll to the stage, so pulling down at its top opens the month.
 */
@Composable
internal fun MonthBody(
    state: CalendarUiState,
    controller: CalendarController,
    holidayCalendars: Set<String>,
    wide: Boolean,
    stage: AnchoredDraggableState<MonthStage>,
    onSelect: (LocalDate) -> Unit,
    dayItems: List<Occurrence>,
    dayHolidays: List<String>,
    openDetail: (Occurrence) -> Unit,
) {
    val showLunar = state.settings.showLunar
    if (wide) {
        Row(Modifier.fillMaxSize()) {
            MonthPages(state, controller, Modifier.weight(0.6f).fillMaxHeight(), vertical = state.settings.verticalPagingWide) { m ->
                MonthGrid(m, state, holidayCalendars, onSelect, null, OloLayout.GridMarginWide, Modifier.fillMaxSize())
            }
            Box(Modifier.fillMaxHeight().width(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
            Box(Modifier.weight(0.4f).fillMaxHeight()) {
                DaySidePane(
                    day = state.selected,
                    today = state.today,
                    items = dayItems,
                    holidays = dayHolidays,
                    calendars = state.calendarsById,
                    zone = controller.zone,
                    onOpen = openDetail,
                    showLunar = showLunar,
                )
            }
        }
        return
    }
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val weeks = monthGrid(state.month, state.settings.weekStart).size / 7
        val header = 24.dp
        val bandLine = OloTone.bandLine
        val full = maxHeight - header - 4.dp - OloLayout.QuickBarSpace
        val lines = (OloLayout.LinesRow + OloLayout.CellGap) * weeks
        val week = OloLayout.LinesRow + OloLayout.CellGap
        val anchors = with(density) {
            DraggableAnchors {
                MonthStage.FULL at full.toPx()
                MonthStage.LINES at minOf(lines, full).toPx()
                MonthStage.WEEK at week.toPx()
            }
        }
        // A 5-week month next to a 6-week one moves the stage anchors; glide to
        // the new height instead of jumping (docs/05 §5).
        val correction = remember { Animatable(0f) }
        LaunchedEffect(anchors) {
            val before = stage.offset
            stage.updateAnchors(anchors, stage.targetValue)
            val after = stage.offset
            if (!before.isNaN() && !after.isNaN() && abs(before - after) > 1f && !stage.isAnimationRunning) {
                correction.snapTo(before - after)
                correction.animateTo(0f, tween(OloMotion.Medium, easing = OloMotion.Emphasized))
            }
        }
        // Remember the stage the user leaves the month in.
        LaunchedEffect(stage) {
            snapshotFlow { stage.settledValue }.collect { v -> controller.updateSettings { it.copy(monthStage = v.ordinal + 1) } }
        }
        val gridHeight = with(density) { ((if (stage.offset.isNaN()) anchors.positionOf(stage.currentValue) else stage.offset) + correction.value).toDp() }
        // Only the selected week is left: page by weeks instead of months.
        val weekMode = stage.currentValue == MonthStage.WEEK && !stage.offset.isNaN() &&
            abs(stage.offset - anchors.positionOf(MonthStage.WEEK)) < 1f

        val connection = remember(stage) {
            object : NestedScrollConnection {
                override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                    // Finger moving up: fold the month before the list scrolls.
                    val min = stage.anchors.minPosition()
                    return if (available.y < 0 && stage.offset > min) Offset(0f, stage.dispatchRawDelta(available.y)) else Offset.Zero
                }
                override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset =
                    // List already at its top and the finger keeps pulling down: open the month.
                    if (available.y > 0 && source == NestedScrollSource.UserInput) Offset(0f, stage.dispatchRawDelta(available.y)) else Offset.Zero
                override suspend fun onPreFling(available: Velocity): Velocity {
                    val off = stage.offset
                    val positions = MonthStage.entries.map { stage.anchors.positionOf(it) }
                    if (positions.any { abs(it - off) < 1f }) return Velocity.Zero
                    stage.animateTo(nearestStage(stage, available.y))
                    return available
                }
            }
        }

        Column(Modifier.fillMaxSize().anchoredDraggable(stage, Orientation.Vertical)) {
            // The weekday row joins the top bar's band; a hairline closes the zone.
            WeekdayHeader(
                state, OloLayout.GridMarginCompact,
                Modifier.background(OloTone.band).height(header).drawBehind {
                    drawLine(bandLine, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1.dp.toPx())
                },
            )
            Spacer(Modifier.height(4.dp))
            val pick = { d: LocalDate ->
                if (d == state.selected && stage.currentValue == MonthStage.FULL) scope.launch { stage.animateTo(MonthStage.LINES) }
                onSelect(d)
            }
            Box(Modifier.height(gridHeight).clipToBounds()) {
                if (weekMode) {
                    WeekPages(state, controller, holidayCalendars, pick)
                } else {
                    MonthPages(state, controller, Modifier.fillMaxSize()) { m ->
                        MonthGrid(m, state, holidayCalendars, pick, gridHeight, OloLayout.GridMarginCompact, Modifier.fillMaxWidth(), showHeader = false)
                    }
                }
            }
            DayPanel(
                day = state.selected,
                today = state.today,
                items = dayItems,
                holidays = dayHolidays,
                calendars = state.calendarsById,
                zone = controller.zone,
                showLunar = showLunar,
                onOpen = openDetail,
                onHandle = {
                    scope.launch { stage.animateTo(if (stage.currentValue == MonthStage.FULL) MonthStage.LINES else MonthStage.FULL) }
                },
                // At the full month only the handle peeks out above the quick-add bar.
                contentAlpha = {
                    val f = anchors.positionOf(MonthStage.FULL)
                    val l = anchors.positionOf(MonthStage.LINES)
                    val off = if (stage.offset.isNaN()) f else stage.offset
                    if (f - l < 1f) 1f else ((f - off) / (f - l) * 3f).coerceIn(0f, 1f)
                },
                modifier = Modifier.weight(1f).nestedScroll(connection),
            )
        }
    }
}

private fun nearestStage(stage: AnchoredDraggableState<MonthStage>, velocity: Float): MonthStage {
    val off = stage.offset
    val sorted = MonthStage.entries.sortedBy { stage.anchors.positionOf(it) }
    return when {
        velocity < -600f -> sorted.lastOrNull { stage.anchors.positionOf(it) < off } ?: sorted.first()
        velocity > 600f -> sorted.firstOrNull { stage.anchors.positionOf(it) > off } ?: sorted.last()
        else -> sorted.minBy { abs(stage.anchors.positionOf(it) - off) }
    }
}

/** Always with the year (docs/05 §4): "2026년 10월". */
internal fun viewTitle(s: CalendarUiState): String = when (s.view) {
    ViewMode.YEAR -> "${s.selected.year}년"
    ViewMode.AGENDA -> "일정"
    // One face, one size, fixed width: "2027년 01월" (no width jump between 9월 and 10월).
    else -> "${s.month.year}년 ${s.month.month.toString().padStart(2, '0')}월"
}

/** Month pages; a settled swipe shows that month (the day number is kept). */
@Composable
internal fun MonthPages(
    state: CalendarUiState,
    controller: CalendarController,
    modifier: Modifier,
    vertical: Boolean = false,
    content: @Composable (YearMonth) -> Unit,
) {
    DatePager(
        target = state.month.index,
        onSettle = { controller.showMonth(YearMonth.ofIndex(it)) },
        modifier = modifier,
        vertical = vertical,
    ) { i -> content(YearMonth.ofIndex(i)) }
}

/** Stage 3: the selected week as one row of lines, paged a week at a time. */
@Composable
private fun WeekPages(state: CalendarUiState, controller: CalendarController, holidayCalendars: Set<String>, onSelect: (LocalDate) -> Unit) {
    val start = weekStartOf(state.selected, state.settings.weekStart)
    val offset = state.selected.toEpochDays() - start.toEpochDays()
    DatePager(
        target = start.toEpochDays().floorDiv(7),
        onSettle = { w -> controller.select(LocalDate.fromEpochDays(w * 7 + (start.toEpochDays().mod(7)) + offset)) },
        modifier = Modifier.fillMaxSize(),
        key = state.settings.weekStart,
    ) { w ->
        val first = LocalDate.fromEpochDays(w * 7 + (start.toEpochDays().mod(7)))
        val week = List(7) { first.plus(it, DateTimeUnit.DAY) }
        val month = YearMonth.of(week.firstOrNull { it == state.selected } ?: week[offset])
        Row(Modifier.fillMaxWidth().height(OloLayout.LinesRow).padding(horizontal = OloLayout.GridMarginCompact)) {
            WeekRow(
                week = week, month = month, state = state,
                events = remember(state.occurrences, holidayCalendars) { gridEvents(state.occurrences, holidayCalendars) },
                holidayCalendars = holidayCalendars, onSelect = onSelect, textAlpha = 0f,
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
        }
    }
}

@Composable
private fun WeekdayHeader(state: CalendarUiState, margin: Dp, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(horizontal = margin), verticalAlignment = Alignment.CenterVertically) {
        if (state.settings.showWeekNumbers) Spacer(Modifier.width(WeekNumberColumn))
        weekHeader(state.settings.weekStart).forEach { d ->
            Text(
                d.koShort(),
                style = MaterialTheme.typography.labelSmall,
                color = when (d) {
                    DayOfWeek.SUNDAY -> MaterialTheme.colorScheme.error
                    DayOfWeek.SATURDAY -> saturdayColor()
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

private val WeekNumberColumn = 18.dp

/**
 * The month grid. [gridHeight] null: rows share the height (wide pane / full stage).
 * Otherwise rows follow the stage: down to the "lines" height all rows shrink
 * together; below it the selected week keeps its row and the others fold away.
 * Rows under 64dp show colour lines instead of titles.
 */
@Composable
internal fun MonthGrid(
    month: YearMonth,
    state: CalendarUiState,
    holidayCalendars: Set<String>,
    onSelect: (LocalDate) -> Unit,
    gridHeight: Dp?,
    margin: Dp,
    modifier: Modifier,
    showHeader: Boolean = true,
) {
    val days = monthGrid(month, state.settings.weekStart)
    val weeks = days.chunked(7)
    val visibleEvents = remember(state.occurrences, holidayCalendars) { gridEvents(state.occurrences, holidayCalendars) }
    val selectedHere = state.selected in days && YearMonth.of(state.selected) == month
    // A neighbour page keeps the same row open as the selected one: the week holding the same weekday-and-number, else the first.
    val selectedWeek = weeks.indexOfFirst { state.selected in it }.coerceAtLeast(0)

    // The selection outline is drawn once over the grid and glides between days (docs/05 §6 #4).
    var gridCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var target by remember { mutableStateOf<Rect?>(null) }
    val outline = remember { Animatable(Rect.Zero, Rect.VectorConverter) }
    var outlineDay by remember { mutableStateOf<LocalDate?>(null) }
    LaunchedEffect(target, state.selected) {
        val r = target ?: return@LaunchedEffect
        if (outlineDay != null && outlineDay != state.selected && outline.value != Rect.Zero) {
            outline.animateTo(r, tween(OloMotion.Short, easing = OloMotion.Emphasized))
        } else {
            outline.snapTo(r)
        }
        outlineDay = state.selected
    }
    val primary = MaterialTheme.colorScheme.primary
    val density = LocalDensity.current
    Box(
        modifier.onGloballyPositioned { gridCoords = it }.drawWithContent {
            drawContent()
            if (selectedHere && outline.value != Rect.Zero) {
                val w = with(density) { 1.5.dp.toPx() }
                val r = outline.value.deflate(w / 2)
                drawRoundRect(primary, r.topLeft, r.size, CornerRadius(with(density) { 10.dp.toPx() }), style = Stroke(w))
            }
        },
    ) {
    Column(Modifier.fillMaxWidth().then(if (gridHeight == null) Modifier.fillMaxHeight() else Modifier)) {
        if (showHeader) WeekdayHeader(state, margin, Modifier.padding(vertical = 4.dp))
        val gap = OloLayout.CellGap
        weeks.forEachIndexed { i, week ->
            val rowH: Dp? = gridHeight?.let { h ->
                val linesTotal = (OloLayout.LinesRow + gap) * weeks.size
                if (h >= linesTotal) h / weeks.size - gap
                else if (i == selectedWeek) OloLayout.LinesRow
                else ((h - OloLayout.LinesRow - gap) / (weeks.size - 1) - gap).coerceAtLeast(0.dp)
            }
            val rowMod = if (rowH == null) Modifier.weight(1f).padding(bottom = gap) else Modifier.requiredHeight(rowH).clipToBounds()
            if (rowH != null && rowH <= 0.dp) return@forEachIndexed
            Row(rowMod.padding(horizontal = margin)) {
                if (state.settings.showWeekNumbers) {
                    // ISO week number, counted from the week's Monday.
                    val monday = week.first { it.dayOfWeek == DayOfWeek.MONDAY }
                    Text(
                        "${isoWeekNumber(monday)}",
                        fontSize = 9.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.width(WeekNumberColumn).padding(top = 5.dp),
                    )
                }
                WeekRow(
                    week = week,
                    month = month,
                    state = state,
                    events = visibleEvents,
                    holidayCalendars = holidayCalendars,
                    onSelect = onSelect,
                    // Lines below 52dp+, titles from 76dp; in between the two cross-fade (docs/05 §6 #7).
                    textAlpha = rowH?.let { ((it - OloLayout.LinesRow) / (TextRowFrom - OloLayout.LinesRow)).coerceIn(0f, 1f) } ?: 1f,
                    onSelectedPlaced = { c -> gridCoords?.let { g -> if (g.isAttached && c.isAttached) target = Rect(g.localPositionOf(c, Offset.Zero), c.size.let { androidx.compose.ui.geometry.Size(it.width.toFloat(), it.height.toFloat()) }) } },
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
            }
            if (rowH != null) Spacer(Modifier.height(gap))
        }
    }
    }
}

private val TextRowFrom = 76.dp

@Composable
private fun WeekRow(
    week: List<LocalDate>,
    month: YearMonth,
    state: CalendarUiState,
    events: List<Occurrence>,
    holidayCalendars: Set<String>,
    onSelect: (LocalDate) -> Unit,
    textAlpha: Float,
    modifier: Modifier,
    onSelectedPlaced: ((LayoutCoordinates) -> Unit)? = null,
) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val gap = OloLayout.CellGap
        val colW = (maxWidth + gap) / 7
        val textLanes = ((maxHeight - NumberRow - 2.dp) / LaneHeight).toInt().coerceAtLeast(0)
        val lineLanes = ((maxHeight - NumberRow - 2.dp) / LineLane).toInt().coerceAtLeast(0)
        val measurer = rememberTextMeasurer()
        val density = LocalDensity.current
        // Room for a title on one line: a bar loses its insets and padding, a timed item its tile.
        val barRoom = with(density) { (colW - gap - 12.dp).toPx() }
        val timedRoom = with(density) { (colW - gap - 14.dp).toPx() }
        val showText = textAlpha > 0f
        val showLines = textAlpha < 1f
        val textLayout = if (!showText) null else remember(week, events, colW, textLanes) {
            layoutWeek(week, events, textLanes) { o ->
                val w = measurer.measure(o.title.ifBlank { "(제목 없음)" }, SegmentText, maxLines = 1, softWrap = false).size.width
                w > if (o.allDay) barRoom else timedRoom
            }
        }
        val lineLayout = if (!showLines) null else remember(week, events, lineLanes) { layoutWeek(week, events, lineLanes) { false } }
        val layout = if (textAlpha >= 0.5f) textLayout!! else lineLayout!!

        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(gap)) {
            week.forEachIndexed { i, day ->
                val selected = day == state.selected && day.monthNumber == month.month
                DayCell(
                    day = day,
                    inMonth = day.monthNumber == month.month,
                    isToday = day == state.today,
                    // The outline itself is drawn by the grid; the strip of stage 3 draws its own.
                    isSelected = selected && onSelectedPlaced == null,
                    isHoliday = isHoliday(day, state.occurrences, holidayCalendars),
                    hidden = layout.hiddenPerDay[i],
                    lunarOn = state.settings.showLunar,
                    onClick = { onSelect(day) },
                    modifier = Modifier.weight(1f).then(
                        if (selected && onSelectedPlaced != null) Modifier.onGloballyPositioned(onSelectedPlaced) else Modifier,
                    ),
                )
            }
        }
        val outside = { seg: WeekSegment -> week[seg.startCol].monthNumber != month.month && week[seg.endCol].monthNumber != month.month }
        if (lineLayout != null) Box(Modifier.fillMaxSize().graphicsLayer { alpha = 1f - textAlpha }) {
            lineLayout.segments.forEach { seg -> LineSegment(seg, colW, gap, outside(seg)) }
        }
        if (textLayout != null) Box(Modifier.fillMaxSize().graphicsLayer { alpha = textAlpha }) {
            textLayout.segments.forEach { seg -> Segment(seg, colW, gap, faded = outside(seg)) }
        }
    }
}

/** Stage 2/3: an event as a 3dp colour line under the date, as Samsung's compact month draws it. */
@Composable
private fun LineSegment(seg: WeekSegment, colW: Dp, gap: Dp, faded: Boolean) {
    val hue = calendarColor(seg.occurrence.colorArgb)
    val left = colW * seg.startCol + (if (seg.continuesBefore) 0.dp else 2.dp)
    val right = colW * (seg.endCol + 1) - gap - (if (seg.continuesAfter) 0.dp else 2.dp)
    Box(
        Modifier.offset(x = left, y = NumberRow + LineLane * seg.lane).width(right - left).height(3.dp)
            .background(hue.copy(alpha = if (faded) 0.3f else 0.75f), RoundedCornerShape(2.dp)),
    )
}

@Composable
private fun DayCell(
    day: LocalDate,
    inMonth: Boolean,
    isToday: Boolean,
    isSelected: Boolean,
    isHoliday: Boolean,
    hidden: Int,
    lunarOn: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val alpha = if (inMonth) 1f else 0.38f
    val numberColor = when {
        day.dayOfWeek == DayOfWeek.SUNDAY || isHoliday -> cs.error
        day.dayOfWeek == DayOfWeek.SATURDAY -> saturdayColor()
        else -> cs.onSurface
    }
    val lunar = KoreanLunar.fromSolar(day)
    // Lunar date on Sundays (as Samsung shows it) and on the 1st and 15th of each lunar month.
    val showLunar = lunarOn && lunar != null && (day.dayOfWeek == DayOfWeek.SUNDAY || lunar.day == 1 || lunar.day == 15)
    Column(
        modifier
            .fillMaxHeight()
            // White cells on the ivory ground; today's cell takes the soft clay fill.
            .background(if (isToday && inMonth) OloTone.container else cs.surfaceContainerLowest, MaterialTheme.shapes.small)
            .then(if (isSelected) Modifier.border(1.5.dp, cs.primary, MaterialTheme.shapes.small) else Modifier)
            .clickable(onClick = onClick),
    ) {
        Row(
            Modifier.fillMaxWidth().height(NumberRow).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (isToday) {
                // Today is a clay tile with a white number: the brand marks "now".
                Box(Modifier.background(cs.primary, MaterialTheme.shapes.extraSmall).padding(horizontal = 4.dp)) {
                    Text("${day.dayOfMonth}", color = cs.onPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            } else {
                Text("${day.dayOfMonth}", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = numberColor.copy(alpha = alpha))
            }
            Spacer(Modifier.weight(1f))
            when {
                // The overflow count wins the corner over the lunar date: it is what hides events.
                hidden > 0 -> Text("+$hidden", fontSize = 9.sp, color = cs.onSurfaceVariant.copy(alpha = alpha))
                showLunar -> Text(lunar!!.label(), fontSize = 9.sp, color = cs.onSurfaceVariant.copy(alpha = alpha * 0.9f))
            }
        }
    }
}

/** One event piece in a week row: a soft bar for all-day and multi-day, a tile and text for timed. */
@Composable
internal fun Segment(seg: WeekSegment, colW: Dp, gap: Dp, faded: Boolean, top: Dp = NumberRow, onClick: (() -> Unit)? = null) {
    val o = seg.occurrence
    val hue = calendarColor(o.colorArgb)
    val alpha = if (faded) 0.38f else 1f
    val inset = 2.dp
    val left = colW * seg.startCol + (if (seg.continuesBefore) 0.dp else inset)
    val right = colW * (seg.endCol + 1) - gap - (if (seg.continuesAfter) 0.dp else inset)
    val y = top + LaneHeight * seg.lane
    val h = LaneHeight * seg.height - 2.dp
    val title = o.title.ifBlank { "(제목 없음)" }
    val textColor = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha)
    val r = 6.dp
    val shape = RoundedCornerShape(
        topStart = if (seg.continuesBefore) 0.dp else r, bottomStart = if (seg.continuesBefore) 0.dp else r,
        topEnd = if (seg.continuesAfter) 0.dp else r, bottomEnd = if (seg.continuesAfter) 0.dp else r,
    )
    // Placed after the offset, so the touch lands where the bar is drawn.
    val tap = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier
    if (o.allDay || seg.spansDays) {
        Box(
            Modifier.offset(x = left, y = y).width(right - left).height(h).then(tap)
                .background(softFill(hue).copy(alpha = alpha), shape)
                .padding(horizontal = 4.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(
                title, fontSize = 10.sp, lineHeight = 13.sp, color = textColor, style = TitleStyle,
                maxLines = seg.height, overflow = TextOverflow.Ellipsis,
                textAlign = if (seg.spansDays) TextAlign.Center else TextAlign.Start,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    } else {
        Row(Modifier.offset(x = left, y = y).width(right - left).height(h).then(tap).padding(start = 1.dp)) {
            Box(Modifier.padding(top = 4.dp).size(6.dp).background(hue.copy(alpha = alpha), RoundedCornerShape(2.dp)))
            Spacer(Modifier.width(3.dp))
            Text(title, fontSize = 10.sp, lineHeight = 13.sp, color = textColor, style = TitleStyle, maxLines = seg.height, overflow = TextOverflow.Ellipsis)
        }
    }
}


/** Phone: the selected day under the month, with its date header and events. */
@Composable
private fun DayPanel(
    day: LocalDate,
    today: LocalDate,
    items: List<Occurrence>,
    holidays: List<String>,
    calendars: Map<String, CalendarInfo>,
    zone: TimeZone,
    showLunar: Boolean,
    onOpen: (Occurrence) -> Unit,
    onHandle: () -> Unit,
    contentAlpha: () -> Float = { 1f },
    modifier: Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.large.copy(bottomStart = CornerSize(0.dp), bottomEnd = CornerSize(0.dp)),
        shadowElevation = 3.dp,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.fillMaxSize()) {
            Box(
                Modifier.fillMaxWidth().height(22.dp).clickable(onClick = onHandle).semantics { contentDescription = "달력 펼치기·접기" },
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.size(width = 36.dp, height = 4.dp).background(MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(2.dp)))
            }
            DaySwap(day, items, holidays, Modifier.weight(1f).graphicsLayer { alpha = contentAlpha() }) { d, its, hs ->
                Column(Modifier.fillMaxSize()) {
                    DayHeader(d, hs, showLunar, Modifier.padding(horizontal = OloLayout.ContentMargin), today = today, count = its.size)
                    if (its.isEmpty()) {
                        Text(
                            "일정이 없습니다. 아래 입력줄에 적어 바로 추가할 수 있습니다.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(OloLayout.ContentMargin),
                        )
                    }
                    DayItems(its, calendars, zone, onOpen, endPadding = OloLayout.ContentMargin)
                }
            }
        }
    }
}

/**
 * The day's head (docs/06 §4 ①): a solid date tile — clay for today, the soft
 * fill otherwise — beside the weekday, with lunar date, holidays and count as
 * clay accent text.
 */
@Composable
internal fun DayHeader(
    day: LocalDate,
    holidays: List<String>,
    showLunar: Boolean,
    modifier: Modifier = Modifier,
    today: LocalDate? = null,
    count: Int = -1,
) {
    val cs = MaterialTheme.colorScheme
    val red = holidays.isNotEmpty() || day.dayOfWeek == DayOfWeek.SUNDAY
    val isToday = day == today
    val dayColor = when { red -> cs.error; day.dayOfWeek == DayOfWeek.SATURDAY -> saturdayColor(); else -> cs.onSurface }
    Row(modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(
            Modifier.size(52.dp).background(if (isToday) OloTone.solid else OloTone.container, RoundedCornerShape(16.dp)),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            val on = if (isToday) OloTone.onSolid(OloTone.solid) else dayColor
            Text(day.dayOfWeek.koShort(), fontSize = 11.sp, color = on.copy(alpha = 0.85f), lineHeight = 12.sp)
            Text("${day.dayOfMonth}", fontSize = 21.sp, fontWeight = FontWeight.Bold, color = on, lineHeight = 22.sp)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                (if (isToday) "오늘 · " else "") + "${day.monthNumber}월 ${day.dayOfMonth}일 ${day.dayOfWeek.koShort()}요일",
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = if (red && !isToday) cs.error else cs.onSurface,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            val meta = listOfNotNull(
                KoreanLunar.fromSolar(day)?.takeIf { showLunar }?.longLabel(),
                holidays.takeIf { it.isNotEmpty() }?.joinToString(" · "),
                count.takeIf { it > 0 }?.let { "일정 $it" },
            )
            if (meta.isNotEmpty()) {
                Text(meta.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = OloTone.accent, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/**
 * The day's events as Explorer lists folders (docs/06 §4): a solid tile in the
 * calendar's hue carrying the start time (or a calendar mark for all-day), the
 * title large, the rest small, dividers starting after the tile.
 */
@Composable
internal fun DayItems(
    items: List<Occurrence>,
    calendars: Map<String, CalendarInfo>,
    zone: TimeZone,
    onOpen: (Occurrence) -> Unit,
    endPadding: Dp = 72.dp,
) {
    val use24 = LocalUse24Hour.current
    val cs = MaterialTheme.colorScheme
    LazyColumn(contentPadding = PaddingValues(bottom = 96.dp)) {
        items(items, key = { it.eventId + "@" + it.begin.toEpochMilliseconds() }) { o ->
            val hue = calendarColor(o.colorArgb)
            Column {
                Row(
                    Modifier.fillMaxWidth().clickable { onOpen(o) }
                        .padding(start = 16.dp, end = endPadding, top = 10.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(
                        Modifier.size(46.dp).background(hue, RoundedCornerShape(14.dp)),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        if (o.allDay) {
                            Icon(app.olo.calendar.design.OloIcons.Month, contentDescription = "종일", tint = OloTone.glyph(hue), modifier = Modifier.size(22.dp))
                        } else {
                            val t = clock(o.begin.toLocalDateTime(zone).time, use24)
                            val (ampm, hm) = t.split(' ').let { if (it.size == 2) it[0] to it[1] else "" to t }
                            if (ampm.isNotEmpty()) Text(ampm, fontSize = 9.sp, lineHeight = 10.sp, color = OloTone.glyph(hue))
                            Text(hm, fontSize = 14.sp, lineHeight = 15.sp, fontWeight = FontWeight.Bold, color = OloTone.onSolid(hue))
                        }
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(o.title.ifBlank { "(제목 없음)" }, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        val sub = listOfNotNull(
                            if (o.allDay) "종일" else "~ " + clock(o.end.toLocalDateTime(zone).time, use24),
                            o.location.takeIf { it.isNotBlank() },
                            calendars[o.calendarId]?.name,
                        ).joinToString(" · ")
                        Text(sub, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                HorizontalDivider(Modifier.padding(start = 76.dp, end = 16.dp), color = cs.outlineVariant.copy(alpha = 0.5f))
            }
        }
    }
}

/** The right-hand pane on wide screens: the selected day, always open. */
@Composable
private fun DaySidePane(
    day: LocalDate,
    today: LocalDate,
    items: List<Occurrence>,
    holidays: List<String>,
    calendars: Map<String, CalendarInfo>,
    zone: TimeZone,
    onOpen: (Occurrence) -> Unit,
    showLunar: Boolean = true,
) {
    val cs = MaterialTheme.colorScheme
    DaySwap(day, items, holidays, Modifier.fillMaxSize()) { day, items, holidays ->
    Column(Modifier.fillMaxSize().padding(top = 8.dp)) {
        DayHeader(day, holidays, showLunar, Modifier.padding(horizontal = 16.dp), today = today, count = items.size)
        if (items.isEmpty()) {
            Text("일정이 없습니다. + 를 눌러 추가하세요.", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, modifier = Modifier.padding(16.dp))
        }
        DayItems(items, calendars, zone, onOpen, endPadding = 16.dp)
    }
    }
}

/**
 * The selected day's content, sliding a little in the direction of travel and
 * fading as the day changes (docs/05 §6 #5); the outgoing day keeps its own events.
 */
@Composable
private fun DaySwap(
    day: LocalDate,
    items: List<Occurrence>,
    holidays: List<String>,
    modifier: Modifier,
    content: @Composable (LocalDate, List<Occurrence>, List<String>) -> Unit,
) {
    AnimatedContent(
        targetState = Triple(day, items, holidays),
        contentKey = { it.first },
        transitionSpec = {
            val dir = if (targetState.first > initialState.first) 1 else -1
            (slideInHorizontally(tween(OloMotion.Short, easing = OloMotion.EmphasizedDecelerate)) { w -> dir * w / 8 } +
                fadeIn(tween(OloMotion.Short))) togetherWith
                (slideOutHorizontally(tween(OloMotion.Quick, easing = OloMotion.EmphasizedAccelerate)) { w -> -dir * w / 8 } +
                    fadeOut(tween(OloMotion.Quick)))
        },
        modifier = modifier,
        label = "day",
    ) { (d, its, hs) -> content(d, its, hs) }
}
