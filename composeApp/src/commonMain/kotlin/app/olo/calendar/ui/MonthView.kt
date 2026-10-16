package app.olo.calendar.ui

import androidx.compose.animation.core.animateDpAsState
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
            MonthGrid(state, holidayCalendars, onSelect, controller::step, null, OloLayout.GridMarginWide, Modifier.weight(0.6f).fillMaxHeight())
            Box(Modifier.fillMaxHeight().width(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
            Box(Modifier.weight(0.4f).fillMaxHeight()) {
                DaySidePane(
                    day = state.selected,
                    isToday = state.selected == state.today,
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
        val full = maxHeight - header - OloLayout.QuickBarSpace
        val lines = (OloLayout.LinesRow + OloLayout.CellGap) * weeks
        val week = OloLayout.LinesRow + OloLayout.CellGap
        val anchors = with(density) {
            DraggableAnchors {
                MonthStage.FULL at full.toPx()
                MonthStage.LINES at minOf(lines, full).toPx()
                MonthStage.WEEK at week.toPx()
            }
        }
        LaunchedEffect(anchors) { stage.updateAnchors(anchors, stage.targetValue) }
        // Remember the stage the user leaves the month in.
        LaunchedEffect(stage) {
            snapshotFlow { stage.settledValue }.collect { v -> controller.updateSettings { it.copy(monthStage = v.ordinal + 1) } }
        }
        val gridHeight = with(density) { (if (stage.offset.isNaN()) anchors.positionOf(stage.currentValue) else stage.offset).toDp() }

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
            WeekdayHeader(state, OloLayout.GridMarginCompact, Modifier.height(header))
            MonthGrid(
                state, holidayCalendars,
                onSelect = { d ->
                    if (d == state.selected && stage.currentValue == MonthStage.FULL) scope.launch { stage.animateTo(MonthStage.LINES) }
                    onSelect(d)
                },
                onSwipe = { dir ->
                    // One week at a time when only the week is showing.
                    if (stage.currentValue == MonthStage.WEEK) controller.select(state.selected.plus(7 * dir, DateTimeUnit.DAY)) else controller.step(dir)
                },
                gridHeight = gridHeight,
                margin = OloLayout.GridMarginCompact,
                modifier = Modifier.height(gridHeight).clipToBounds(),
                showHeader = false,
            )
            DayPanel(
                day = state.selected,
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

internal fun viewTitle(s: CalendarUiState): String = when {
    s.view == ViewMode.YEAR -> "${s.selected.year}년"
    s.month.year == s.today.year -> "${s.month.month}월"
    else -> s.month.koTitle()
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
    state: CalendarUiState,
    holidayCalendars: Set<String>,
    onSelect: (LocalDate) -> Unit,
    onSwipe: (Int) -> Unit,
    gridHeight: Dp?,
    margin: Dp,
    modifier: Modifier,
    showHeader: Boolean = true,
) {
    val days = monthGrid(state.month, state.settings.weekStart)
    val weeks = days.chunked(7)
    var drag by remember(state.month) { mutableStateOf(0f) }
    val visibleEvents = state.occurrences.filter { it.calendarId !in holidayCalendars }
    val selectedWeek = weeks.indexOfFirst { state.selected in it }.coerceAtLeast(0)
    Column(
        modifier.fillMaxWidth().pointerInput(state.month, state.selected) {
            detectHorizontalDragGestures(
                onDragStart = { drag = 0f },
                onDragEnd = {
                    // Next page comes in from the right, as a page turns (OLO motion rule).
                    if (drag < -80f) onSwipe(1) else if (drag > 80f) onSwipe(-1)
                    drag = 0f
                },
            ) { _, dx -> drag += dx }
        },
    ) {
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
                    state = state,
                    events = visibleEvents,
                    holidayCalendars = holidayCalendars,
                    onSelect = onSelect,
                    lines = rowH != null && rowH < 64.dp,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
            }
            if (rowH != null) Spacer(Modifier.height(gap))
        }
    }
}

@Composable
private fun WeekRow(
    week: List<LocalDate>,
    state: CalendarUiState,
    events: List<Occurrence>,
    holidayCalendars: Set<String>,
    onSelect: (LocalDate) -> Unit,
    lines: Boolean,
    modifier: Modifier,
) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val gap = OloLayout.CellGap
        val colW = (maxWidth + gap) / 7
        val laneH = if (lines) LineLane else LaneHeight
        val lanes = ((maxHeight - NumberRow - 2.dp) / laneH).toInt().coerceAtLeast(0)
        val measurer = rememberTextMeasurer()
        val density = LocalDensity.current
        // Room for a title on one line: a bar loses its insets and padding, a timed item its tile.
        val barRoom = with(density) { (colW - gap - 12.dp).toPx() }
        val timedRoom = with(density) { (colW - gap - 14.dp).toPx() }
        val layout = remember(week, events, colW, lanes, lines) {
            layoutWeek(week, events, lanes) { o ->
                if (lines) return@layoutWeek false
                val w = measurer.measure(o.title.ifBlank { "(제목 없음)" }, SegmentText, maxLines = 1, softWrap = false).size.width
                w > if (o.allDay) barRoom else timedRoom
            }
        }

        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(gap)) {
            week.forEachIndexed { i, day ->
                DayCell(
                    day = day,
                    inMonth = day.monthNumber == state.month.month,
                    isToday = day == state.today,
                    isSelected = day == state.selected,
                    isHoliday = holidayNames(day, state.occurrences, holidayCalendars).isNotEmpty(),
                    hidden = layout.hiddenPerDay[i],
                    lunarOn = state.settings.showLunar,
                    onClick = { onSelect(day) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        layout.segments.forEach { seg ->
            val outside = week[seg.startCol].monthNumber != state.month.month && week[seg.endCol].monthNumber != state.month.month
            if (lines) LineSegment(seg, colW, gap, outside) else Segment(seg, colW, gap, faded = outside)
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
            .background(cs.surfaceContainerLow, MaterialTheme.shapes.small)
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
internal fun Segment(seg: WeekSegment, colW: Dp, gap: Dp, faded: Boolean, top: Dp = NumberRow) {
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
    if (o.allDay || seg.spansDays) {
        Box(
            Modifier.offset(x = left, y = y).width(right - left).height(h)
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
        Row(Modifier.offset(x = left, y = y).width(right - left).height(h).padding(start = 1.dp)) {
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
            Column(Modifier.weight(1f).graphicsLayer { alpha = contentAlpha() }) {
            DayHeader(day, holidays, showLunar, Modifier.padding(horizontal = OloLayout.ContentMargin))
            if (items.isEmpty()) {
                Text(
                    "일정이 없습니다. 아래 입력줄에 적어 바로 추가할 수 있습니다.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(OloLayout.ContentMargin),
                )
            }
            DayItems(items, calendars, zone, onOpen, endPadding = OloLayout.ContentMargin)
            }
        }
    }
}

/** "16 금요일 · 음력 9월 6일 · 공휴일" */
@Composable
internal fun DayHeader(day: LocalDate, holidays: List<String>, showLunar: Boolean, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val red = holidays.isNotEmpty() || day.dayOfWeek == DayOfWeek.SUNDAY
    Row(modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.Bottom) {
        Text("${day.dayOfMonth}", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold, color = if (red) cs.error else cs.onSurface)
        Spacer(Modifier.width(8.dp))
        Text(
            "${day.dayOfWeek.koShort()}요일",
            style = MaterialTheme.typography.titleMedium,
            color = when { red -> cs.error; day.dayOfWeek == DayOfWeek.SATURDAY -> saturdayColor(); else -> cs.onSurface },
            modifier = Modifier.padding(bottom = 3.dp),
        )
        val meta = listOfNotNull(KoreanLunar.fromSolar(day)?.takeIf { showLunar }?.longLabel(), holidays.takeIf { it.isNotEmpty() }?.joinToString(" · "))
        if (meta.isNotEmpty()) {
            Spacer(Modifier.width(10.dp))
            Text(meta.joinToString("  ·  "), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(bottom = 4.dp))
        }
    }
}

@Composable
internal fun DayItems(
    items: List<Occurrence>,
    calendars: Map<String, CalendarInfo>,
    zone: TimeZone,
    onOpen: (Occurrence) -> Unit,
    endPadding: Dp = 72.dp,
) {
    val use24 = LocalUse24Hour.current
    LazyColumn(contentPadding = PaddingValues(bottom = 96.dp)) {
        items(items, key = { it.eventId + "@" + it.begin.toEpochMilliseconds() }) { o ->
            Row(
                Modifier.fillMaxWidth().clickable { onOpen(o) }
                    // In the bottom panel the + button floats beside the rows; keep text clear of it.
                    .padding(start = 16.dp, end = endPadding, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Tile(calendarColor(o.colorArgb), 14.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.width(76.dp)) {
                    if (o.allDay) {
                        Text("종일", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        Text(clock(o.begin.toLocalDateTime(zone).time, use24), style = MaterialTheme.typography.bodyMedium)
                        Text(clock(o.end.toLocalDateTime(zone).time, use24), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Column(Modifier.weight(1f)) {
                    Text(o.title.ifBlank { "(제목 없음)" }, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    val sub = listOfNotNull(o.location.takeIf { it.isNotBlank() }, calendars[o.calendarId]?.name).joinToString(" · ")
                    if (sub.isNotEmpty()) {
                        Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

/** The right-hand pane on wide screens: the selected day, always open. */
@Composable
private fun DaySidePane(
    day: LocalDate,
    isToday: Boolean,
    items: List<Occurrence>,
    holidays: List<String>,
    calendars: Map<String, CalendarInfo>,
    zone: TimeZone,
    onOpen: (Occurrence) -> Unit,
    showLunar: Boolean = true,
) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize().padding(top = 8.dp)) {
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.then(
                    if (isToday) Modifier.background(cs.primary, MaterialTheme.shapes.small)
                    else Modifier.border(1.5.dp, cs.onSurface, MaterialTheme.shapes.small),
                ).padding(horizontal = 10.dp, vertical = 2.dp),
            ) {
                Text("${day.dayOfMonth}", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold, color = if (isToday) cs.onPrimary else cs.onSurface)
            }
            Spacer(Modifier.width(12.dp))
            val red = holidays.isNotEmpty() || day.dayOfWeek == DayOfWeek.SUNDAY
            Text(
                "${day.dayOfWeek.koShort()}요일",
                style = MaterialTheme.typography.titleLarge,
                color = when {
                    red -> cs.error
                    day.dayOfWeek == DayOfWeek.SATURDAY -> saturdayColor()
                    else -> cs.onSurface
                },
            )
        }
        val meta = listOfNotNull(KoreanLunar.fromSolar(day)?.takeIf { showLunar }?.longLabel(), holidays.takeIf { it.isNotEmpty() }?.joinToString(" · "))
        Row(Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp)) {
            meta.forEachIndexed { i, m ->
                if (i > 0) Text("  |  ", style = MaterialTheme.typography.bodyMedium, color = cs.outlineVariant)
                Text(m, style = MaterialTheme.typography.bodyMedium, color = if (i == 1) cs.error else cs.onSurfaceVariant)
            }
        }
        if (items.isEmpty()) {
            Text("일정이 없습니다. + 를 눌러 추가하세요.", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, modifier = Modifier.padding(16.dp))
        }
        DayItems(items, calendars, zone, onOpen, endPadding = 16.dp)
    }
}
