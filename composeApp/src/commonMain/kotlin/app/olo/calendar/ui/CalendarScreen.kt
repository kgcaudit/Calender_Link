package app.olo.calendar.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.olo.calendar.design.calendarColor
import app.olo.calendar.design.saturdayColor
import app.olo.calendar.design.softFill
import app.olo.calendar.model.CalendarInfo
import app.olo.calendar.model.EventDraft
import app.olo.calendar.model.KoreanHolidays
import app.olo.calendar.model.KoreanLunar
import app.olo.calendar.model.Occurrence
import app.olo.calendar.model.StoredEvent
import app.olo.calendar.model.WeekSegment
import app.olo.calendar.model.koShort
import app.olo.calendar.model.layoutWeek
import app.olo.calendar.model.monthGrid
import app.olo.calendar.model.on
import app.olo.calendar.model.sortedForDay
import app.olo.calendar.model.weekHeader
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

private sealed interface Sheet {
    data object None : Sheet
    data class Detail(val occurrence: Occurrence) : Sheet
    data class Editor(val draft: EventDraft, val stored: StoredEvent?) : Sheet
    data object Calendars : Sheet
    data object Sync : Sheet
    data class Move(val fromCalendarId: String) : Sheet
}

private val NumberRow = 22.dp
private val LaneHeight = 16.dp
private val PanelPeek = 78.dp

/** From this width the day list moves beside the month (unfolded Fold, tablets, landscape). */
private val WideLayout = 600.dp

// Break titles between phrases rather than mid-word where the platform supports it.
private val TitleStyle = TextStyle(lineBreak = LineBreak.Heading)
private val SegmentText = TextStyle(fontSize = 10.sp, lineHeight = 13.sp)

/** Holiday names for a day: the built-in Korean list plus any synced holiday calendar, without repeats. */
internal fun holidayNames(day: LocalDate, occurrences: List<Occurrence>, holidayCalendars: Set<String>): List<String> =
    (KoreanHolidays.on(day).map { it.name } + occurrences.on(day).filter { it.calendarId in holidayCalendars }.map { it.title })
        .map { it.trim() }.filter { it.isNotEmpty() }.distinct()

@Composable
fun CalendarScreen(controller: CalendarController, settings: SystemSettings? = null, startExpanded: Boolean = false) {
    val state by controller.state.collectAsState()
    var sheet by remember { mutableStateOf<Sheet>(Sheet.None) }
    var expanded by remember { mutableStateOf(startExpanded) }
    val holidayCalendars = state.calendars.filter { it.isHoliday }.map { it.id }.toSet()

    val onSelect: (LocalDate) -> Unit = { day ->
        // A second tap on the selected day opens its list.
        if (day == state.selected) expanded = true
        controller.select(day)
    }
    val dayItems = state.occurrences.on(state.selected).filter { it.calendarId !in holidayCalendars }.sortedForDay()
    val dayHolidays = holidayNames(state.selected, state.occurrences, holidayCalendars)
    val newEvent = { controller.newDraft()?.let { sheet = Sheet.Editor(it, null) }; Unit }

    BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
        if (maxWidth >= WideLayout) {
            // Unfolded / tablet / landscape: month on the left, the selected day always open on the right.
            Row(Modifier.fillMaxSize()) {
                MonthPane(state, controller, holidayCalendars, onSelect, { sheet = it }, Modifier.weight(0.6f))
                Box(Modifier.fillMaxHeight().width(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
                Box(Modifier.weight(0.4f).fillMaxHeight()) {
                    DaySidePane(
                        day = state.selected,
                        isToday = state.selected == state.today,
                        items = dayItems,
                        holidays = dayHolidays,
                        calendars = state.calendarsById,
                        zone = controller.zone,
                        onOpen = { sheet = Sheet.Detail(it) },
                    )
                    AddButton(newEvent, Modifier.align(Alignment.BottomEnd).padding(20.dp))
                }
            }
        } else {
            val panelHeight by animateDpAsState(if (expanded) maxHeight * 0.5f else PanelPeek)
            Column(Modifier.fillMaxSize()) {
                MonthPane(state, controller, holidayCalendars, onSelect, { sheet = it }, Modifier.weight(1f))
                DayPanel(
                    day = state.selected,
                    items = dayItems,
                    holidays = dayHolidays,
                    calendars = state.calendarsById,
                    zone = controller.zone,
                    expanded = expanded,
                    onToggle = { expanded = it },
                    onOpen = { sheet = Sheet.Detail(it) },
                    modifier = Modifier.height(panelHeight),
                )
            }
            AddButton(newEvent, Modifier.align(Alignment.BottomEnd).padding(end = 20.dp, bottom = panelHeight + 12.dp))
        }
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
            state = state,
            onToggle = controller::setVisible,
            onOpenSync = { sheet = Sheet.Sync },
            onDismiss = { sheet = Sheet.None },
        )
        Sheet.Sync -> SyncSheet(
            state = state,
            onSyncNow = controller::syncNow,
            onOpenSyncSettings = settings?.let { s2 -> { s2.openSyncSettings() } },
            onMove = { sheet = Sheet.Move(it) },
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

@Composable
private fun MonthPane(
    state: CalendarUiState,
    controller: CalendarController,
    holidayCalendars: Set<String>,
    onSelect: (LocalDate) -> Unit,
    openSheet: (Sheet) -> Unit,
    modifier: Modifier,
) {
    Column(modifier.fillMaxHeight()) {
        TopBar(
            title = state.month.koTitle(),
            today = state.today,
            attention = state.needsSyncAttention,
            onPrev = { controller.showMonth(state.month.plusMonths(-1)) },
            onNext = { controller.showMonth(state.month.plusMonths(1)) },
            onToday = controller::goToday,
            onSync = { openSheet(Sheet.Sync) },
            onCalendars = { openSheet(Sheet.Calendars) },
        )
        state.error?.let { ErrorLine(it, controller::dismissError) }
        MonthGrid(
            state = state,
            holidayCalendars = holidayCalendars,
            onSelect = onSelect,
            onSwipe = { dir -> controller.showMonth(state.month.plusMonths(dir)) },
            modifier = Modifier.weight(1f),
        )
    }
}

/** The screen's primary action wears the brand: primary / onPrimary (OLO CONSUMING.md). */
@Composable
private fun AddButton(onClick: () -> Unit, modifier: Modifier) {
    FloatingActionButton(
        onClick = onClick,
        containerColor = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        modifier = modifier.semantics { contentDescription = "새 일정" },
    ) { Text("+", fontSize = 28.sp, fontWeight = FontWeight.Light) }
}

@Composable
private fun TopBar(
    title: String,
    today: LocalDate,
    attention: Boolean,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onToday: () -> Unit,
    onSync: () -> Unit,
    onCalendars: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 4.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OloTextButton("‹", onPrev)
        Text(title, style = MaterialTheme.typography.headlineSmall, maxLines = 1, softWrap = false, modifier = Modifier.padding(horizontal = 2.dp))
        OloTextButton("›", onNext)
        Spacer(Modifier.weight(1f))
        if (attention) {
            Text(
                "동기화 확인 필요",
                maxLines = 1,
                softWrap = false,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .background(MaterialTheme.colorScheme.error.copy(alpha = 0.12f), MaterialTheme.shapes.extraSmall)
                    .clickable(onClick = onSync)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
            Spacer(Modifier.width(8.dp))
        }
        // Today: a small calendar square carrying today's date, as calendar apps do.
        Box(
            Modifier.size(32.dp)
                .border(1.5.dp, MaterialTheme.colorScheme.onSurface, RoundedCornerShape(7.dp))
                .clickable(onClick = onToday)
                .semantics { contentDescription = "오늘로 이동" },
            contentAlignment = Alignment.Center,
        ) {
            Text("${today.dayOfMonth}", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        }
        OloTextButton("캘린더", onCalendars)
    }
}

@Composable
private fun ErrorLine(text: String, onClick: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).clickable(onClick = onClick),
    ) {
        Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(12.dp))
    }
}

@Composable
private fun MonthGrid(
    state: CalendarUiState,
    holidayCalendars: Set<String>,
    onSelect: (LocalDate) -> Unit,
    onSwipe: (Int) -> Unit,
    modifier: Modifier,
) {
    val days = monthGrid(state.month)
    var drag by remember(state.month) { mutableStateOf(0f) }
    val visibleEvents = state.occurrences.filter { it.calendarId !in holidayCalendars }
    Column(
        modifier.fillMaxWidth().padding(horizontal = 4.dp).pointerInput(state.month) {
            detectHorizontalDragGestures(
                onDragStart = { drag = 0f },
                onDragEnd = {
                    // Next month comes in from the right, as a page turns (OLO motion rule).
                    if (drag < -80f) onSwipe(1) else if (drag > 80f) onSwipe(-1)
                    drag = 0f
                },
            ) { _, dx -> drag += dx }
        },
    ) {
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            weekHeader().forEach { d ->
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
        days.chunked(7).forEach { week ->
            WeekRow(
                week = week,
                state = state,
                events = visibleEvents,
                holidayCalendars = holidayCalendars,
                onSelect = onSelect,
                modifier = Modifier.weight(1f).padding(bottom = 3.dp),
            )
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
    modifier: Modifier,
) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val gap = 3.dp
        val colW = (maxWidth + gap) / 7
        val lanes = ((maxHeight - NumberRow - 4.dp) / LaneHeight).toInt().coerceAtLeast(1)
        val measurer = rememberTextMeasurer()
        val density = LocalDensity.current
        // Room for a title on one line: a bar loses its insets and padding, a timed item its tile.
        val barRoom = with(density) { (colW - gap - 12.dp).toPx() }
        val timedRoom = with(density) { (colW - gap - 14.dp).toPx() }
        val layout = remember(week, events, colW, lanes) {
            layoutWeek(week, events, lanes) { o ->
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
                    onClick = { onSelect(day) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        layout.segments.forEach { seg ->
            val outside = week[seg.startCol].monthNumber != state.month.month && week[seg.endCol].monthNumber != state.month.month
            Segment(seg, colW, gap, faded = outside)
        }
    }
}

@Composable
private fun DayCell(
    day: LocalDate,
    inMonth: Boolean,
    isToday: Boolean,
    isSelected: Boolean,
    isHoliday: Boolean,
    hidden: Int,
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
    val showLunar = lunar != null && (day.dayOfWeek == DayOfWeek.SUNDAY || lunar.day == 1 || lunar.day == 15)
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
private fun Segment(seg: WeekSegment, colW: Dp, gap: Dp, faded: Boolean) {
    val o = seg.occurrence
    val hue = calendarColor(o.colorArgb)
    val alpha = if (faded) 0.38f else 1f
    val inset = 2.dp
    val left = colW * seg.startCol + (if (seg.continuesBefore) 0.dp else inset)
    val right = colW * (seg.endCol + 1) - gap - (if (seg.continuesAfter) 0.dp else inset)
    val y = NumberRow + LaneHeight * seg.lane
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

@Composable
private fun DayPanel(
    day: LocalDate,
    items: List<Occurrence>,
    holidays: List<String>,
    calendars: Map<String, CalendarInfo>,
    zone: TimeZone,
    expanded: Boolean,
    onToggle: (Boolean) -> Unit,
    onOpen: (Occurrence) -> Unit,
    modifier: Modifier,
) {
    var drag by remember { mutableStateOf(0f) }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.large.copy(bottomStart = CornerSize(0.dp), bottomEnd = CornerSize(0.dp)),
        shadowElevation = 4.dp,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.fillMaxSize()) {
            Column(
                Modifier.fillMaxWidth()
                    .pointerInput(expanded) {
                        detectVerticalDragGestures(
                            onDragStart = { drag = 0f },
                            onDragEnd = {
                                if (drag < -40f) onToggle(true) else if (drag > 40f) onToggle(false)
                                drag = 0f
                            },
                        ) { _, dy -> drag += dy }
                    }
                    .clickable { onToggle(!expanded) }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Box(
                    Modifier.align(Alignment.CenterHorizontally).size(width = 36.dp, height = 4.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(2.dp)),
                )
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val red = holidays.isNotEmpty() || day.dayOfWeek == DayOfWeek.SUNDAY
                    Text(
                        "${day.monthNumber}월 ${day.dayOfMonth}일 ${day.dayOfWeek.koShort()}요일",
                        style = MaterialTheme.typography.titleMedium,
                        color = when {
                            red -> MaterialTheme.colorScheme.error
                            day.dayOfWeek == DayOfWeek.SATURDAY -> saturdayColor()
                            else -> MaterialTheme.colorScheme.onSurface
                        },
                    )
                    if (holidays.isNotEmpty()) {
                        Text(
                            "  " + holidays.joinToString(" · "),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    KoreanLunar.fromSolar(day)?.let {
                        Text(it.longLabel(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (!expanded) {
                    Text(
                        if (items.isEmpty()) "일정 없음" else "일정 ${items.size}개 · 위로 밀어 목록 보기",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (expanded) {
                if (items.isEmpty()) {
                    Text(
                        "일정이 없습니다. + 를 눌러 추가하세요.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
                DayItems(items, calendars, zone, onOpen)
            }
        }
    }
}

@Composable
private fun DayItems(
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
        val meta = listOfNotNull(KoreanLunar.fromSolar(day)?.longLabel(), holidays.takeIf { it.isNotEmpty() }?.joinToString(" · "))
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
