package app.olo.calendar.ui

import kotlinx.datetime.TimeZone
import app.olo.calendar.design.OloIcons
import app.olo.calendar.design.OloTone
import app.olo.calendar.design.OloMotion
import androidx.compose.animation.core.tween
import androidx.compose.material3.Icon
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.layout.heightIn
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.derivedStateOf
import androidx.compose.foundation.ExperimentalFoundationApi
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.filter
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.olo.calendar.design.calendarColor
import app.olo.calendar.design.saturdayColor
import app.olo.calendar.design.softFill
import app.olo.calendar.model.KoreanLunar
import app.olo.calendar.model.Occurrence
import app.olo.calendar.model.ViewMode
import app.olo.calendar.model.YearMonth
import app.olo.calendar.model.koShort
import app.olo.calendar.model.layoutDay
import app.olo.calendar.model.layoutWeek
import app.olo.calendar.model.monthGrid
import app.olo.calendar.model.on
import app.olo.calendar.model.sortedForDay
import app.olo.calendar.model.weekHeader
import app.olo.calendar.model.weekStartOf
import kotlinx.datetime.Clock
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime

// --- year ---------------------------------------------------------------------

/** Twelve small months; days that hold events are tinted, as in Samsung's year view. */
@Composable
fun YearView(state: CalendarUiState, holidayCalendars: Set<String>, onOpen: (LocalDate) -> Unit, onYear: (Int) -> Unit) {
    // Events per day: the year reads as a heat map in clay (docs/06 §4).
    val busy = remember(state.occurrences, holidayCalendars) {
        buildMap<LocalDate, Int> {
            state.occurrences.filter { it.calendarId !in holidayCalendars }.forEach { o ->
                var d = o.firstDay
                var guard = 0
                while (d <= o.lastDay && guard++ < 400) { put(d, (get(d) ?: 0) + 1); d = d.plus(1, DateTimeUnit.DAY) }
            }
        }
    }
    // Years scroll on, one after another (docs/05 §4); the year at the top becomes the current one.
    val base = remember { state.selected.year - YearSpan }
    val list = rememberLazyListState(initialFirstVisibleItemIndex = YearSpan)
    val report = rememberUpdatedState(onYear)
    LaunchedEffect(list) {
        snapshotFlow { list.isScrollInProgress to list.firstVisibleItemIndex }
            .filter { !it.first }.map { it.second }.distinctUntilChanged()
            .collect { report.value(base + it) }
    }
    LaunchedEffect(state.selected.year) {
        val i = state.selected.year - base
        if (!list.isScrollInProgress && list.firstVisibleItemIndex != i) list.animateScrollToItem(i)
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val cols = if (maxWidth >= WideLayout) 4 else 3
        LazyColumn(state = list, contentPadding = PaddingValues(start = 8.dp, end = 8.dp, bottom = 88.dp), modifier = Modifier.fillMaxSize()) {
            items(YearSpan * 2 + 1, key = { base + it }) { i ->
                val year = base + i
                Column {
                    Text(
                        "${year}년", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
                        color = if (year == state.today.year) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(start = 6.dp, top = 12.dp, bottom = 2.dp),
                    )
                    (1..12).chunked(cols).forEach { row ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            row.forEach { m ->
                                MiniMonth(YearMonth(year, m), state, busy, holidayCalendars, onOpen, Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }
    }
}

private const val YearSpan = 60

@Composable
private fun MiniMonth(
    month: YearMonth,
    state: CalendarUiState,
    busy: Map<LocalDate, Int>,
    holidayCalendars: Set<String>,
    onOpen: (LocalDate) -> Unit,
    modifier: Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val current = YearMonth.of(state.today) == month
    val heat = listOf(
        androidx.compose.ui.graphics.lerp(cs.surfaceContainerLowest, cs.primary, 0.14f),
        androidx.compose.ui.graphics.lerp(cs.surfaceContainerLowest, cs.primary, 0.30f),
        androidx.compose.ui.graphics.lerp(cs.surfaceContainerLowest, cs.primary, 0.62f),
    )
    Column(modifier.clickable { onOpen(if (current) state.today else month.firstDay) }) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text(
                "${month.month}월",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (current) cs.onPrimary else cs.onSurface,
                modifier = Modifier
                    .background(if (current) cs.primary else Color.Transparent, MaterialTheme.shapes.extraSmall)
                    .padding(horizontal = 6.dp, vertical = 1.dp),
            )
        }
        Spacer(Modifier.height(4.dp))
        val weekStart = state.settings.weekStart
        Row(Modifier.fillMaxWidth()) {
            weekHeader(weekStart).forEach { d ->
                Text(
                    d.koShort(), fontSize = 8.sp, textAlign = TextAlign.Center, modifier = Modifier.weight(1f),
                    color = when (d) { DayOfWeek.SUNDAY -> cs.error; DayOfWeek.SATURDAY -> saturdayColor(); else -> cs.onSurfaceVariant },
                )
            }
        }
        monthGrid(month, weekStart).chunked(7).forEach { week ->
            Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
                week.forEach { d ->
                    val inMonth = d.monthNumber == month.month
                    val isToday = d == state.today
                    val n = busy[d] ?: 0
                    val level = when { n <= 0 -> -1; n == 1 -> 0; n <= 3 -> 1; else -> 2 }
                    val red = d.dayOfWeek == DayOfWeek.SUNDAY || holidayNames(d, state.occurrences, holidayCalendars).isNotEmpty()
                    Box(
                        Modifier.weight(1f).height(16.dp).padding(horizontal = 0.5.dp)
                            .background(
                                when {
                                    !inMonth -> Color.Transparent
                                    isToday -> cs.primary
                                    level >= 0 -> heat[level]
                                    else -> Color.Transparent
                                },
                                RoundedCornerShape(3.dp),
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (inMonth) {
                            Text(
                                "${d.dayOfMonth}", fontSize = 9.sp,
                                color = when {
                                    isToday -> cs.onPrimary
                                    level == 2 -> cs.onPrimary
                                    red -> cs.error
                                    d.dayOfWeek == DayOfWeek.SATURDAY -> saturdayColor()
                                    else -> cs.onSurface
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

// --- week / day time grid -------------------------------------------------------

private val HourHeight = 52.dp
private val Gutter = 44.dp

/** Week (seven columns) or day (one column) on a time grid, with an all-day strip on top. */
@Composable
fun TimeGridView(
    state: CalendarUiState,
    controller: CalendarController,
    holidayCalendars: Set<String>,
    single: Boolean,
    onOpen: (Occurrence) -> Unit,
    onNewAt: (LocalDate, LocalTime?) -> Unit,
    onPickDay: (LocalDate) -> Unit,
) {
    // Pages follow the finger (docs/05 §5): a day per page, or a week keeping the weekday.
    if (single) {
        DatePager(
            target = state.selected.toEpochDays(),
            onSettle = { controller.select(LocalDate.fromEpochDays(it)) },
            modifier = Modifier.fillMaxSize(),
        ) { d -> TimeGridPage(state, controller, holidayCalendars, true, LocalDate.fromEpochDays(d), onOpen, onNewAt, onPickDay) }
    } else {
        val start = weekStartOf(state.selected, state.settings.weekStart)
        val offset = state.selected.toEpochDays() - start.toEpochDays()
        val residue = start.toEpochDays() % 7
        DatePager(
            target = start.toEpochDays() / 7,
            onSettle = { w -> controller.select(LocalDate.fromEpochDays(w * 7 + residue + offset)) },
            modifier = Modifier.fillMaxSize(),
            key = state.settings.weekStart,
        ) { w -> TimeGridPage(state, controller, holidayCalendars, false, LocalDate.fromEpochDays(w * 7 + residue), onOpen, onNewAt, onPickDay) }
    }
}

@Composable
private fun TimeGridPage(
    state: CalendarUiState,
    controller: CalendarController,
    holidayCalendars: Set<String>,
    single: Boolean,
    anchor: LocalDate,
    onOpen: (Occurrence) -> Unit,
    onNewAt: (LocalDate, LocalTime?) -> Unit,
    onPickDay: (LocalDate) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val zone = controller.zone
    val use24 = LocalUse24Hour.current
    val days = if (single) listOf(anchor) else List(7) { anchor.plus(it, DateTimeUnit.DAY) }
    val events = state.occurrences.filter { it.calendarId !in holidayCalendars }
    Column(Modifier.fillMaxSize()) {
        // Day headers.
        if (single) {
            val d = anchor
            val holidays = holidayNames(d, state.occurrences, holidayCalendars)
            Row(Modifier.padding(start = 16.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                val red = d.dayOfWeek == DayOfWeek.SUNDAY || holidays.isNotEmpty()
                Text("${d.dayOfMonth}", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold, color = if (red) cs.error else cs.onSurface)
                Spacer(Modifier.width(10.dp))
                Text("${d.dayOfWeek.koShort()}요일", style = MaterialTheme.typography.titleMedium, color = if (red) cs.error else if (d.dayOfWeek == DayOfWeek.SATURDAY) saturdayColor() else cs.onSurface)
            }
            val meta = listOfNotNull(KoreanLunar.fromSolar(d)?.takeIf { state.settings.showLunar }?.longLabel(), holidays.takeIf { it.isNotEmpty() }?.joinToString(" · "))
            if (meta.isNotEmpty()) Text(meta.joinToString("  |  "), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(start = 16.dp, bottom = 4.dp))
        } else {
            Row(Modifier.fillMaxWidth().padding(start = Gutter, end = 4.dp, bottom = 4.dp)) {
                days.forEach { d ->
                    val red = d.dayOfWeek == DayOfWeek.SUNDAY || holidayNames(d, state.occurrences, holidayCalendars).isNotEmpty()
                    Column(Modifier.weight(1f).clickable { onPickDay(d) }, horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(d.dayOfWeek.koShort(), fontSize = 11.sp, color = if (red) cs.error else if (d.dayOfWeek == DayOfWeek.SATURDAY) saturdayColor() else cs.onSurfaceVariant)
                        val isToday = d == state.today
                        Text(
                            "${d.dayOfMonth}", fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                            color = if (isToday) cs.onPrimary else if (red) cs.error else cs.onSurface,
                            modifier = Modifier.background(if (isToday) cs.primary else Color.Transparent, MaterialTheme.shapes.extraSmall).padding(horizontal = 5.dp),
                        )
                    }
                }
            }
        }
        // All-day strip: the same lane layout as the month view, at most three lanes.
        val allDay = events.filter { it.allDay || it.firstDay != it.lastDay }
        if (allDay.any { o -> days.any { d -> d >= o.firstDay && d <= o.lastDay } }) {
            BoxWithConstraints(Modifier.fillMaxWidth().padding(start = Gutter, end = 4.dp)) {
                val padded = if (single) days + List(6) { days[0].plus(it + 1, DateTimeUnit.DAY) } else days
                val colW = maxWidth / (if (single) 1 else 7)
                val layout = layoutWeek(padded, allDay, 3) { false }
                val lanes = (layout.segments.maxOfOrNull { it.lane + 1 } ?: 0)
                Box(Modifier.fillMaxWidth().height(LaneHeight * lanes + 4.dp)) {
                    layout.segments.filter { !single || it.startCol == 0 }.forEach { seg ->
                        val s = if (single) seg.copy(endCol = 0, continuesAfter = seg.occurrence.lastDay > days[0]) else seg
                        Box(Modifier.clickable { onOpen(seg.occurrence) }) { Segment(s, colW, 0.dp, faded = false, top = 0.dp) }
                    }
                }
                val hidden = layout.hiddenPerDay.take(days.size).sum()
                if (hidden > 0) Text("+$hidden", fontSize = 9.sp, color = cs.onSurfaceVariant, modifier = Modifier.align(Alignment.TopEnd))
            }
        }
        HorizontalDivider(color = cs.outlineVariant)
        // Hours.
        val density = LocalDensity.current
        val scroll = rememberScrollState(initial = with(density) { (HourHeight * 8).roundToPx() })
        Row(Modifier.fillMaxSize().verticalScroll(scroll)) {
            Column(Modifier.width(Gutter)) {
                (0 until 24).forEach { h ->
                    Box(Modifier.height(HourHeight).fillMaxWidth()) {
                        if (h > 0) {
                            Text(
                                if (use24) "${h.toString().padStart(2, '0')}" else (if (h < 12) "오전 " else "오후 ") + "${if (h % 12 == 0) 12 else h % 12}",
                                fontSize = 9.sp, color = cs.onSurfaceVariant, textAlign = TextAlign.End,
                                modifier = Modifier.fillMaxWidth().padding(end = 6.dp).offset(y = (-6).dp),
                            )
                        }
                    }
                }
            }
            BoxWithConstraints(Modifier.weight(1f).height(HourHeight * 24).padding(end = 4.dp)) {
                val colW = maxWidth / days.size
                // Hour lines.
                (1 until 24).forEach { h ->
                    Box(Modifier.offset(y = HourHeight * h).fillMaxWidth().height(1.dp).background(cs.outlineVariant.copy(alpha = 0.6f)))
                }
                days.forEachIndexed { i, d ->
                    Box(
                        Modifier.offset(x = colW * i).width(colW).fillMaxHeight()
                            .pointerInput(d) {
                                detectTapGestures { pos ->
                                    val hour = (pos.y / with(density) { HourHeight.toPx() }).toInt().coerceIn(0, 23)
                                    onNewAt(d, LocalTime(hour, 0))
                                }
                            },
                    ) {
                        if (i > 0) Box(Modifier.width(1.dp).fillMaxHeight().background(cs.outlineVariant.copy(alpha = 0.4f)))
                        layoutDay(d, events, zone).forEach { b ->
                            val hue = calendarColor(b.occurrence.colorArgb)
                            val w = colW / b.cols
                            Box(
                                Modifier.offset(x = w * b.col + 1.dp, y = HourHeight * (b.startMin / 60f))
                                    .width(w - 2.dp).height(HourHeight * ((b.endMin - b.startMin) / 60f) - 1.dp)
                                    .background(softFill(hue), MaterialTheme.shapes.extraSmall)
                                    .clickable { onOpen(b.occurrence) },
                            ) {
                                Box(Modifier.width(3.dp).fillMaxHeight().background(hue, RoundedCornerShape(topStart = 6.dp, bottomStart = 6.dp)))
                                Column(Modifier.padding(start = 6.dp, end = 3.dp, top = 2.dp)) {
                                    Text(b.occurrence.title.ifBlank { "(제목 없음)" }, fontSize = if (single) 13.sp else 10.sp, lineHeight = if (single) 16.sp else 12.sp, color = cs.onSurface, overflow = TextOverflow.Ellipsis)
                                    if (single) Text(timeLabel(b.occurrence, zone, use24), fontSize = 11.sp, color = cs.onSurfaceVariant, maxLines = 1)
                                }
                            }
                        }
                        if (d == state.today) {
                            val now = Clock.System.now().toLocalDateTime(zone).time
                            val y = HourHeight * ((now.hour * 60 + now.minute) / 60f)
                            Box(Modifier.offset(y = y).fillMaxWidth().height(2.dp).background(cs.primary))
                        }
                    }
                }
            }
        }
    }
}

// --- agenda -------------------------------------------------------------------

/** Days that hold events, one after another, as Samsung's "일정" view lists them. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AgendaView(state: CalendarUiState, controller: CalendarController, holidayCalendars: Set<String>, onOpen: (Occurrence) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val zone = controller.zone
    val use24 = LocalUse24Hour.current
    val past = state.agendaPast
    val events = state.occurrences.filter { it.calendarId !in holidayCalendars }
    val days = remember(events, past, state.today) {
        events.flatMap { o ->
            buildList { var d = o.firstDay; var g = 0; while (d <= o.lastDay && g++ < 400) { add(d); d = d.plus(1, DateTimeUnit.DAY) } }
        }.distinct().filter { if (past) it < state.today else it >= state.today }
            .let { if (past) it.sortedDescending() else it.sorted() }
    }
    // Month bands and day rows in one list; the fast scroller jumps along it.
    val byMonth = remember(days) { days.groupBy { YearMonth.of(it) } }
    val list = rememberLazyListState()
    Column(Modifier.fillMaxSize()) {
        AgendaTabs(past, controller::setAgendaPast)
        if (days.isEmpty()) {
            Column(Modifier.fillMaxWidth().padding(top = 64.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(64.dp).background(OloTone.container, RoundedCornerShape(20.dp)), contentAlignment = Alignment.Center) {
                    Icon(OloIcons.Agenda, contentDescription = null, tint = OloTone.accent, modifier = Modifier.size(30.dp))
                }
                Text(
                    if (past) "지난 1년 동안의 일정이 없습니다." else "앞으로 1년 동안 일정이 없습니다.",
                    style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 14.dp),
                )
            }
            return@Column
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(state = list, contentPadding = PaddingValues(bottom = 96.dp), modifier = Modifier.fillMaxSize()) {
                byMonth.forEach { (m, ds) ->
                    stickyHeader(key = "m${m.index}") { MonthBand(m) }
                    items(ds, key = { it.toEpochDays() }) { d -> AgendaDay(d, state, events, holidayCalendars, zone, use24, onOpen) }
                }
            }
            // The fast scroller (as in search, 0.9.7) replaces the 12-month rail: it reaches
            // every month, and its bubble names the month under the thumb.
            val rowMonths = remember(byMonth) { byMonth.flatMap { (m, ds) -> List(ds.size + 1) { m } } }
            FastScroller(list, label = { i -> rowMonths.getOrNull(i)?.koTitle() })
        }
    }
}

@Composable
private fun AgendaTabs(past: Boolean, onPast: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().background(OloTone.band).height(44.dp)) {
        listOf(false to "다가오는 일정", true to "지난 일정").forEach { (p, label) ->
            val on = p == past
            val line by androidx.compose.animation.animateColorAsState(if (on) OloTone.solid else Color.Transparent, tween(OloMotion.Short))
            Box(
                Modifier.weight(1f).fillMaxHeight().clickable { onPast(p) }.drawBehind {
                    drawRect(line, topLeft = androidx.compose.ui.geometry.Offset(0f, size.height - 3.dp.toPx()), size = androidx.compose.ui.geometry.Size(size.width, 3.dp.toPx()))
                }.semantics { selected = on; role = Role.Tab },
                contentAlignment = Alignment.Center,
            ) {
                Text(label, style = MaterialTheme.typography.titleSmall, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal, color = if (on) OloTone.accent else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun MonthBand(m: YearMonth) {
    val line = OloTone.bandLine
    Box(
        Modifier.fillMaxWidth().height(34.dp).background(OloTone.band).drawBehind {
            drawLine(line, androidx.compose.ui.geometry.Offset(0f, size.height - 0.5f), androidx.compose.ui.geometry.Offset(size.width, size.height - 0.5f), 1.dp.toPx())
        }.padding(horizontal = 16.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(m.koTitle(), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = OloTone.accent)
    }
}

@Composable
private fun AgendaDay(
    d: LocalDate,
    state: CalendarUiState,
    events: List<Occurrence>,
    holidayCalendars: Set<String>,
    zone: TimeZone,
    use24: Boolean,
    onOpen: (Occurrence) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val holidays = holidayNames(d, state.occurrences, holidayCalendars)
    val red = d.dayOfWeek == DayOfWeek.SUNDAY || holidays.isNotEmpty()
    val isToday = d == state.today
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 28.dp, top = 12.dp, bottom = 6.dp)) {
        // The date tile: solid clay today, the soft fill otherwise.
        Column(
            Modifier.size(46.dp).background(if (isToday) OloTone.solid else OloTone.container, RoundedCornerShape(14.dp)),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            val dayColor = when { isToday -> OloTone.onSolid(OloTone.solid); red -> cs.error; d.dayOfWeek == DayOfWeek.SATURDAY -> saturdayColor(); else -> cs.onSurface }
            Text(d.dayOfWeek.koShort(), fontSize = 10.sp, lineHeight = 11.sp, color = dayColor.copy(alpha = 0.85f))
            Text("${d.dayOfMonth}", fontSize = 18.sp, lineHeight = 19.sp, fontWeight = FontWeight.Bold, color = dayColor)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            val meta = listOfNotNull(KoreanLunar.fromSolar(d)?.takeIf { state.settings.showLunar }?.longLabel(), holidays.takeIf { it.isNotEmpty() }?.joinToString(" · "))
            if (meta.isNotEmpty()) Text(meta.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = if (holidays.isNotEmpty()) cs.error else OloTone.accent)
            events.on(d).sortedForDay().forEach { o ->
                val hue = calendarColor(o.colorArgb)
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable { onOpen(o) }.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.width(4.dp).height(34.dp).background(hue, RoundedCornerShape(2.dp)))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(o.title.ifBlank { "(제목 없음)" }, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        val span = when {
                            o.firstDay != o.lastDay -> "${o.firstDay.monthNumber}월 ${o.firstDay.dayOfMonth}일 – ${o.lastDay.monthNumber}월 ${o.lastDay.dayOfMonth}일"
                            else -> timeLabel(o, zone, use24)
                        }
                        Text(listOf(span, o.location).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}
