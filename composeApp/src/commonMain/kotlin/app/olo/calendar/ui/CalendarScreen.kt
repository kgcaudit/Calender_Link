package app.olo.calendar.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.olo.calendar.design.calendarColor
import app.olo.calendar.design.status
import app.olo.calendar.model.CalendarInfo
import app.olo.calendar.model.EventDraft
import app.olo.calendar.model.Occurrence
import app.olo.calendar.model.StoredEvent
import app.olo.calendar.model.koShort
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
}

@Composable
fun CalendarScreen(controller: CalendarController) {
    val state by controller.state.collectAsState()
    var sheet by remember { mutableStateOf<Sheet>(Sheet.None) }
    val holidayCalendars = state.calendars.filter { it.isHoliday }.map { it.id }.toSet()

    Box(Modifier.fillMaxSize().safeDrawingPadding()) {
        Column(Modifier.fillMaxSize()) {
            TopBar(
                title = state.month.koTitle(),
                onPrev = { controller.showMonth(state.month.plusMonths(-1)) },
                onNext = { controller.showMonth(state.month.plusMonths(1)) },
                onToday = controller::goToday,
                onCalendars = { sheet = Sheet.Calendars },
                syncOk = state.sync.accountsWithSyncOff.isEmpty() && state.sync.masterSyncOn,
                pending = state.sync.pendingUploads,
            )
            if (!state.sync.masterSyncOn || state.sync.accountsWithSyncOff.isNotEmpty()) {
                Banner(
                    text = if (!state.sync.masterSyncOn) "휴대폰의 자동 동기화가 꺼져 있어 다른 기기로 일정이 가지 않습니다."
                    else "Google 계정 캘린더 동기화가 꺼져 있습니다: " + state.sync.accountsWithSyncOff.joinToString(),
                    onClick = { sheet = Sheet.Calendars },
                )
            }
            state.error?.let { Banner(it, onClick = controller::dismissError) }

            MonthGrid(
                state = state,
                holidayCalendars = holidayCalendars,
                onSelect = controller::select,
                onSwipe = { dir -> controller.showMonth(state.month.plusMonths(dir)) },
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            DayList(
                day = state.selected,
                items = state.occurrences.on(state.selected).sortedForDay(),
                calendars = state.calendarsById,
                holidayCalendars = holidayCalendars,
                onOpen = { sheet = Sheet.Detail(it) },
                zone = controller.zone,
                modifier = Modifier.weight(1f),
            )
        }

        // The screen's primary action wears the brand: primary / onPrimary (OLO CONSUMING.md).
        FloatingActionButton(
            onClick = { controller.newDraft()?.let { sheet = Sheet.Editor(it, null) } },
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp).semantics { contentDescription = "새 일정" },
        ) { Text("+", fontSize = 28.sp, fontWeight = FontWeight.Light) }
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
            onSyncNow = controller::syncNow,
            onDismiss = { sheet = Sheet.None },
        )
    }
}

@Composable
private fun TopBar(
    title: String,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onToday: () -> Unit,
    onCalendars: () -> Unit,
    syncOk: Boolean,
    pending: Int,
) {
    Row(
        Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OloTextButton("‹", onPrev)
        Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(horizontal = 4.dp))
        OloTextButton("›", onNext)
        Spacer(Modifier.weight(1f))
        val st = MaterialTheme.status
        val dot = when {
            !syncOk -> st.failed
            pending > 0 -> st.waiting
            else -> st.done
        }
        Box(
            Modifier.size(8.dp).background(dot, MaterialTheme.shapes.extraSmall)
                .semantics { contentDescription = if (!syncOk) "동기화 꺼짐" else if (pending > 0) "업로드 대기 $pending 건" else "동기화됨" },
        )
        OloTextButton("오늘", onToday)
        OloTextButton("캘린더", onCalendars)
    }
}

@Composable
private fun Banner(text: String, onClick: () -> Unit) {
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
) {
    val days = monthGrid(state.month)
    val header = weekHeader()
    var drag by remember(state.month) { mutableStateOf(0f) }
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 6.dp).pointerInput(state.month) {
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
            header.forEach { d ->
                Text(
                    d.koShort(),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (d == DayOfWeek.SUNDAY) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
        days.chunked(7).forEach { week ->
            Row(Modifier.fillMaxWidth().height(64.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                week.forEach { day ->
                    DayCell(
                        day = day,
                        inMonth = day.monthNumber == state.month.month,
                        isToday = day == state.today,
                        isSelected = day == state.selected,
                        items = state.occurrences.on(day).sortedForDay(),
                        holidayCalendars = holidayCalendars,
                        onClick = { onSelect(day) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            Spacer(Modifier.height(2.dp))
        }
    }
}

@Composable
private fun DayCell(
    day: LocalDate,
    inMonth: Boolean,
    isToday: Boolean,
    isSelected: Boolean,
    items: List<Occurrence>,
    holidayCalendars: Set<String>,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val holidays = items.filter { it.calendarId in holidayCalendars }
    val events = items.filter { it.calendarId !in holidayCalendars }
    val red = day.dayOfWeek == DayOfWeek.SUNDAY || holidays.isNotEmpty()
    Column(
        modifier
            .fillMaxSize()
            .background(cs.surfaceContainerLow, MaterialTheme.shapes.small)
            .then(if (isSelected) Modifier.border(1.5.dp, cs.primary, MaterialTheme.shapes.small) else Modifier)
            .clickable(onClick = onClick)
            .padding(horizontal = 2.dp, vertical = 3.dp),
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        val alpha = if (inMonth) 1f else 0.38f
        val room = if (holidays.isEmpty()) 2 else 1
        Row(Modifier.fillMaxWidth().padding(start = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            if (isToday) {
                // Today is a clay tile with a white number: the brand marks "now".
                Box(Modifier.background(cs.primary, MaterialTheme.shapes.extraSmall).padding(horizontal = 4.dp)) {
                    Text("${day.dayOfMonth}", color = cs.onPrimary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                }
            } else {
                Text(
                    "${day.dayOfMonth}",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = (if (red) cs.error else cs.onSurface).copy(alpha = alpha),
                )
            }
            Spacer(Modifier.weight(1f))
            // Overflow count sits beside the date so it is never cut off at the cell's foot.
            if (events.size > room) {
                Text("+${events.size - room}", fontSize = 9.sp, color = cs.onSurfaceVariant.copy(alpha = alpha), modifier = Modifier.padding(end = 2.dp))
            }
        }
        holidays.firstOrNull()?.let {
            Text(it.title, fontSize = 9.sp, color = cs.error.copy(alpha = alpha), maxLines = 1, overflow = TextOverflow.Clip)
        }
        events.take(room).forEach { o ->
            Box(
                Modifier.fillMaxWidth()
                    .background(calendarColor(o.colorArgb).copy(alpha = alpha), MaterialTheme.shapes.extraSmall)
                    .padding(horizontal = 3.dp),
            ) {
                Text(o.title.ifBlank { "(제목 없음)" }, fontSize = 9.sp, lineHeight = 12.sp, color = Color.White, maxLines = 1, overflow = TextOverflow.Clip)
            }
        }
    }
}

@Composable
private fun DayList(
    day: LocalDate,
    items: List<Occurrence>,
    calendars: Map<String, CalendarInfo>,
    holidayCalendars: Set<String>,
    onOpen: (Occurrence) -> Unit,
    zone: TimeZone,
    modifier: Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        Text(
            "${day.monthNumber}월 ${day.dayOfMonth}일 ${day.dayOfWeek.koShort()}요일",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
        )
        if (items.isEmpty()) {
            Text(
                "일정이 없습니다. + 를 눌러 추가하세요.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        }
        LazyColumn(contentPadding = PaddingValues(bottom = 96.dp)) {
            items(items, key = { it.eventId + "@" + it.begin.toEpochMilliseconds() }) { o ->
                val holiday = o.calendarId in holidayCalendars
                Row(
                    Modifier.fillMaxWidth().clickable { onOpen(o) }.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Tile(if (holiday) MaterialTheme.colorScheme.error else calendarColor(o.colorArgb), 14.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        timeLabel(o, zone),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(86.dp),
                    )
                    Column(Modifier.weight(1f)) {
                        Text(o.title.ifBlank { "(제목 없음)" }, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val sub = listOfNotNull(o.location.takeIf { it.isNotBlank() }, calendars[o.calendarId]?.name).joinToString(" · ")
                        if (sub.isNotEmpty()) {
                            Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

internal fun timeLabel(o: Occurrence, zone: TimeZone): String {
    if (o.allDay) return "종일"
    val b = o.begin.toLocalDateTime(zone)
    val e = o.end.toLocalDateTime(zone)
    fun hm(h: Int, m: Int) = "${h.toString().padStart(2, '0')}:${m.toString().padStart(2, '0')}"
    return hm(b.hour, b.minute) + "–" + hm(e.hour, e.minute)
}
