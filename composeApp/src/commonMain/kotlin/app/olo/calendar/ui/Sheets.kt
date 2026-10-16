package app.olo.calendar.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.olo.calendar.design.calendarColor
import app.olo.calendar.design.status
import app.olo.calendar.model.CalendarInfo
import app.olo.calendar.model.Occurrence
import app.olo.calendar.model.StoredEvent
import app.olo.calendar.model.SyncKind
import app.olo.calendar.model.describeKo
import app.olo.calendar.model.koDate
import app.olo.calendar.model.koLabel
import app.olo.calendar.model.koShort

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun OloSheet(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = MaterialTheme.shapes.large.copy(
            bottomStart = androidx.compose.foundation.shape.CornerSize(0.dp),
            bottomEnd = androidx.compose.foundation.shape.CornerSize(0.dp),
        ),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 16.dp)) {
            content()
        }
    }
}

@Composable
fun EventDetailSheet(
    occurrence: Occurrence,
    controller: CalendarController,
    calendars: Map<String, CalendarInfo>,
    onEdit: (StoredEvent) -> Unit,
    onDismiss: () -> Unit,
) {
    var stored by remember { mutableStateOf<StoredEvent?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    LaunchedEffect(occurrence.eventId) { stored = controller.load(occurrence.eventId) }
    val cal = calendars[occurrence.calendarId]

    OloSheet(onDismiss) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Tile(calendarColor(occurrence.colorArgb), 16.dp)
            Spacer(Modifier.width(10.dp))
            Text(occurrence.title.ifBlank { "(제목 없음)" }, style = MaterialTheme.typography.headlineSmall)
        }
        Spacer(Modifier.height(12.dp))
        val when_ = if (occurrence.firstDay == occurrence.lastDay) {
            "${occurrence.firstDay.koDate()} (${occurrence.firstDay.dayOfWeek.koShort()}) ${timeLabel(occurrence, controller.zone)}"
        } else {
            "${occurrence.firstDay.koDate()} – ${occurrence.lastDay.koDate()}" + if (occurrence.allDay) "" else " · ${timeLabel(occurrence, controller.zone)}"
        }
        DetailLine("일시", when_)
        cal?.let { DetailLine("캘린더", "${it.name} · ${it.kind.koLabel()}") }
        stored?.let { s ->
            val d = s.draft
            if (d.location.isNotBlank()) DetailLine("장소", d.location)
            when {
                d.recurrence != null -> DetailLine("반복", d.recurrence.describeKo(d.startDate))
                s.rawRule != null -> DetailLine("반복", "복잡한 반복 규칙 (OLO에서는 그대로 둡니다)")
            }
            if (d.reminderMinutes.isNotEmpty()) DetailLine("알림", d.reminderMinutes.sorted().joinToString { reminderLabel(it) })
            if (d.description.isNotBlank()) DetailLine("메모", d.description)
        }
        Spacer(Modifier.height(16.dp))
        val s = stored
        if (s != null && s.calendarWritable) {
            if (confirmDelete) {
                Text(
                    if (s.rawRule != null || occurrence.recurring) "반복 일정 전체가 삭제됩니다. 삭제할까요?" else "이 일정을 삭제할까요?",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                    OloTextButton("취소", { confirmDelete = false })
                    OloOutlinedButton("삭제", { controller.delete(occurrence.eventId); onDismiss() })
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OloButton("편집", { onEdit(s) })
                    OloTextButton("삭제", { confirmDelete = true }, color = MaterialTheme.colorScheme.error)
                }
            }
        } else if (s != null) {
            Text("읽기 전용 캘린더입니다.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun DetailLine(label: String, value: String) {
    Row(Modifier.padding(vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(64.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

internal fun reminderLabel(min: Int): String = when {
    min == 0 -> "정시"
    min % 1440 == 0 -> "${min / 1440}일 전"
    min % 60 == 0 -> "${min / 60}시간 전"
    else -> "${min}분 전"
}

@Composable
fun CalendarsSheet(
    state: CalendarUiState,
    onToggle: (String, Boolean) -> Unit,
    onSyncNow: () -> Unit,
    onDismiss: () -> Unit,
) {
    OloSheet(onDismiss) {
        Text("캘린더", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        val st = MaterialTheme.status
        val sync = state.sync
        val (dot, msg) = when {
            !sync.masterSyncOn -> st.failed to "휴대폰 자동 동기화가 꺼져 있습니다. 설정 > 계정 및 백업 > 계정 관리에서 켜 주세요."
            sync.accountsWithSyncOff.isNotEmpty() -> st.failed to "캘린더 동기화가 꺼진 Google 계정: ${sync.accountsWithSyncOff.joinToString()}"
            sync.pendingUploads > 0 -> st.waiting to "Google로 올라가기를 기다리는 변경 ${sync.pendingUploads}건"
            else -> st.done to "Google과 동기화된 상태입니다."
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Tile(dot, 10.dp)
            Spacer(Modifier.width(8.dp))
            Text(msg, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        }
        OloTextButton("지금 동기화", onSyncNow)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(vertical = 8.dp))

        val holidaySources = state.calendars.count { it.isHoliday && it.visible }
        if (holidaySources > 1) {
            Text(
                "공휴일 캘린더가 ${holidaySources}개 켜져 있어 공휴일이 겹쳐 보일 수 있습니다. 하나만 켜 두세요.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Column(Modifier.verticalScroll(rememberScrollState())) {
            state.calendars.groupBy { it.accountName }.forEach { (account, cals) ->
                Text(
                    "$account · ${cals.first().kind.koLabel()}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                )
                cals.forEach { c ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onToggle(c.id, !c.visible) }.padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = c.visible, onCheckedChange = { onToggle(c.id, it) })
                        Tile(if (c.isHoliday) MaterialTheme.colorScheme.error else calendarColor(c.colorArgb), 14.dp)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(c.name, style = MaterialTheme.typography.bodyLarge)
                            val notes = buildList {
                                if (!c.writable) add("읽기 전용")
                                if (c.isHoliday) add("공휴일")
                                if (c.kind != SyncKind.GOOGLE && c.writable) add("다른 기기로 동기화되지 않음")
                            }
                            if (notes.isNotEmpty()) {
                                Text(notes.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}
