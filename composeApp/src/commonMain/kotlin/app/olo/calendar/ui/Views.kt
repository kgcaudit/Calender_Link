package app.olo.calendar.ui

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
fun YearView(state: CalendarUiState, holidayCalendars: Set<String>, onOpen: (LocalDate) -> Unit, onSwipe: (Int) -> Unit) {
    val busy = remember(state.occurrences, holidayCalendars) {
        buildSet {
            state.occurrences.filter { it.calendarId !in holidayCalendars }.forEach { o ->
                var d = o.firstDay
                var guard = 0
                while (d <= o.lastDay && guard++ < 400) { add(d); d = d.plus(1, DateTimeUnit.DAY) }
            }
        }
    }
    var drag by remember { mutableStateOf(0f) }
    BoxWithConstraints(
        Modifier.fillMaxSize().pointerInput(state.selected.year) {
            detectHorizontalDragGestures(onDragStart = { drag = 0f }, onDragEnd = {
                if (drag < -80f) onSwipe(1) else if (drag > 80f) onSwipe(-1)
            }) { _, dx -> drag += dx }
        },
    ) {
        val cols = if (maxWidth >= WideLayout) 4 else 3
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 8.dp)) {
            (1..12).chunked(cols).forEach { row ->
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { m ->
                        MiniMonth(YearMonth(state.selected.year, m), state, busy, holidayCalendars, onOpen, Modifier.weight(1f))
                    }
                }
            }
            Spacer(Modifier.height(88.dp))
        }
    }
}

@Composable
private fun MiniMonth(
    month: YearMonth,
    state: CalendarUiState,
    busy: Set<LocalDate>,
    holidayCalendars: Set<String>,
    onOpen: (LocalDate) -> Unit,
    modifier: Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val current = YearMonth.of(state.today) == month
    val tint = softFill(cs.primary)
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
                    val red = d.dayOfWeek == DayOfWeek.SUNDAY || holidayNames(d, state.occurrences, holidayCalendars).isNotEmpty()
                    Box(
                        Modifier.weight(1f).height(16.dp).padding(horizontal = 0.5.dp)
                            .background(
                                when {
                                    !inMonth -> Color.Transparent
                                    isToday -> cs.primary
                                    d in busy -> tint
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
    onSwipe: (Int) -> Unit,
    onPickDay: (LocalDate) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val zone = controller.zone
    val use24 = LocalUse24Hour.current
    val days = if (single) listOf(state.selected) else List(7) { weekStartOf(state.selected, state.settings.weekStart).plus(it, DateTimeUnit.DAY) }
    val events = state.occurrences.filter { it.calendarId !in holidayCalendars }
    var drag by remember { mutableStateOf(0f) }
    Column(
        Modifier.fillMaxSize().pointerInput(state.selected, single) {
            detectHorizontalDragGestures(onDragStart = { drag = 0f }, onDragEnd = {
                if (drag < -80f) onSwipe(1) else if (drag > 80f) onSwipe(-1)
            }) { _, dx -> drag += dx }
        },
    ) {
        // Day headers.
        if (single) {
            val d = state.selected
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
@Composable
fun AgendaView(state: CalendarUiState, controller: CalendarController, holidayCalendars: Set<String>, onOpen: (Occurrence) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val zone = controller.zone
    val use24 = LocalUse24Hour.current
    val events = state.occurrences.filter { it.calendarId !in holidayCalendars }
    val days = remember(events) {
        events.flatMap { o ->
            buildList { var d = o.firstDay; var g = 0; while (d <= o.lastDay && g++ < 400) { add(d); d = d.plus(1, DateTimeUnit.DAY) } }
        }.distinct().sorted().filter { !state.settings.hidePastInAgenda || it >= state.today }
    }
    val list = rememberLazyListState()
    LaunchedEffect(days, state.selected) {
        val i = days.indexOfFirst { it >= state.selected }
        if (i >= 0) list.scrollToItem(i)
    }
    if (days.isEmpty()) {
        Text("이 기간에는 일정이 없습니다.", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, modifier = Modifier.padding(24.dp))
        return
    }
    LazyColumn(state = list, contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 96.dp)) {
        items(days, key = { it.toEpochDays() }) { d ->
            val holidays = holidayNames(d, state.occurrences, holidayCalendars)
            val red = d.dayOfWeek == DayOfWeek.SUNDAY || holidays.isNotEmpty()
            Column(Modifier.fillMaxWidth().padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${d.monthNumber}월 ${d.dayOfMonth}일 (${d.dayOfWeek.koShort()})",
                        style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                        color = if (red) cs.error else if (d.dayOfWeek == DayOfWeek.SATURDAY) saturdayColor() else cs.onSurface,
                        modifier = Modifier.then(if (d == state.today) Modifier.border(1.5.dp, cs.primary, MaterialTheme.shapes.extraSmall).padding(horizontal = 4.dp) else Modifier),
                    )
                    Spacer(Modifier.width(8.dp))
                    val meta = listOfNotNull(KoreanLunar.fromSolar(d)?.takeIf { state.settings.showLunar }?.longLabel(), holidays.takeIf { it.isNotEmpty() }?.joinToString(" · "))
                    Text(meta.joinToString("  ·  "), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                }
                events.on(d).sortedForDay().forEach { o ->
                    val hue = calendarColor(o.colorArgb)
                    if (o.allDay || o.firstDay != o.lastDay) {
                        Row(
                            Modifier.fillMaxWidth().background(softFill(hue), MaterialTheme.shapes.medium).clickable { onOpen(o) }.padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Tile(hue, 12.dp)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(o.title.ifBlank { "(제목 없음)" }, style = MaterialTheme.typography.bodyLarge)
                                val span = if (o.firstDay == o.lastDay) "하루 종일" else "${o.firstDay.monthNumber}월 ${o.firstDay.dayOfMonth}일 – ${o.lastDay.monthNumber}월 ${o.lastDay.dayOfMonth}일"
                                Text(listOf(span, o.location).filter { it.isNotBlank() }.joinToString("  "), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                            }
                        }
                    } else {
                        Row(Modifier.fillMaxWidth().clickable { onOpen(o) }.padding(horizontal = 6.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(clock(o.begin.toLocalDateTime(zone).time, use24), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 1, softWrap = false, modifier = Modifier.width(88.dp))
                            Box(Modifier.width(3.dp).height(32.dp).background(hue, RoundedCornerShape(2.dp)))
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(o.title.ifBlank { "(제목 없음)" }, style = MaterialTheme.typography.bodyLarge)
                                Text(listOf(timeLabel(o, zone, use24), o.location).filter { it.isNotBlank() }.joinToString("  "), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}
