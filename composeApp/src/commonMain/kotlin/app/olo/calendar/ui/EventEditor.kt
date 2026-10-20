package app.olo.calendar.ui

import app.olo.calendar.model.EditScope
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.olo.calendar.design.calendarColor
import app.olo.calendar.model.AllDay
import app.olo.calendar.model.CalendarInfo
import app.olo.calendar.model.EventDraft
import app.olo.calendar.model.Frequency
import app.olo.calendar.model.Recurrence
import app.olo.calendar.model.RecurrenceEnd
import app.olo.calendar.model.StoredEvent
import app.olo.calendar.model.at
import app.olo.calendar.model.koDate
import app.olo.calendar.model.koLabel
import app.olo.calendar.model.koShort
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

private val ReminderChoices = listOf(0, 10, 30, 60, 1440)

private enum class PickTarget { START_DATE, START_TIME, END_DATE, END_TIME, UNTIL }

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EventEditorSheet(
    initial: EventDraft,
    stored: StoredEvent?,
    calendars: List<CalendarInfo>,
    /** For one occurrence of a repeating event: how far the edit reaches (docs/10). */
    scope: EditScope? = null,
    onSave: (EventDraft) -> Unit,
    onDismiss: () -> Unit,
) {
    var d by remember { mutableStateOf(initial) }
    var pick by remember { mutableStateOf<PickTarget?>(null) }
    val zone = remember(d.timeZoneId) { runCatching { TimeZone.of(d.timeZoneId) }.getOrDefault(TimeZone.currentSystemDefault()) }
    val keepRule = stored?.let { it.rawRule != null && !it.recurrenceEditable } == true
    val seriesEdit = stored != null && !stored.isException && (stored.rawRule != null)

    val startAt = if (d.allDay) null else d.startDate.at(d.startTime, zone)
    val endAt = if (d.allDay) null else d.endDate.at(d.endTime, zone)
    val valid = if (d.allDay) d.endDate >= d.startDate else (startAt != null && endAt != null && endAt >= startAt)

    OloSheet(onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState()).imePadding(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(if (initial.id == null) "새 일정" else "일정 편집", style = MaterialTheme.typography.headlineSmall)
            val reachNote = when {
                scope == EditScope.THIS -> "반복 중 이 일정만 바뀝니다."
                scope == EditScope.FOLLOWING -> "이 일정부터 이후 일정이 바뀝니다. 지난 일정은 그대로입니다."
                seriesEdit -> "반복 일정 전체가 바뀝니다."
                else -> null
            }
            if (reachNote != null) {
                Text(reachNote, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            OutlinedTextField(
                value = d.title, onValueChange = { d = d.copy(title = it) },
                label = { Text("제목") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.small,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("종일", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Switch(checked = d.allDay, onCheckedChange = { d = d.copy(allDay = it) })
            }
            DateTimeRow("시작", d.startDate, d.startTime, d.allDay, { pick = PickTarget.START_DATE }, { pick = PickTarget.START_TIME })
            DateTimeRow("종료", d.endDate, d.endTime, d.allDay, { pick = PickTarget.END_DATE }, { pick = PickTarget.END_TIME })
            if (!valid) Text("종료 시간이 시작보다 빠릅니다.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)

            if (scope == EditScope.THIS) {
                // A changed occurrence cannot repeat on its own.
            } else if (keepRule) {
                Text(
                    "반복 · 다른 앱에서 만든 규칙(그대로 유지)",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                RecurrenceEditor(d.recurrence, d.startDate, { pick = PickTarget.UNTIL }) { d = d.copy(recurrence = it) }
            }

            Text("알림", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ReminderChoices.forEach { m ->
                    val on = m in d.reminderMinutes
                    FilterChip(
                        selected = on,
                        onClick = { d = d.copy(reminderMinutes = if (on) d.reminderMinutes - m else (d.reminderMinutes + m).take(5)) },
                        label = { Text(reminderLabel(m)) },
                        shape = MaterialTheme.shapes.extraSmall,
                    )
                }
            }

            OutlinedTextField(
                value = d.location, onValueChange = { d = d.copy(location = it) },
                label = { Text("장소") }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.small,
            )
            OutlinedTextField(
                value = d.description, onValueChange = { d = d.copy(description = it) },
                label = { Text("메모") }, minLines = 2, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.small,
            )

            // One occurrence stays in its series' calendar.
            if (scope != EditScope.THIS) Text("저장할 캘린더", style = MaterialTheme.typography.labelLarge)
            if (scope != EditScope.THIS) calendars.forEach { c ->
                Row(
                    Modifier.fillMaxWidth().clickable { d = d.copy(calendarId = c.id) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = d.calendarId == c.id, onClick = { d = d.copy(calendarId = c.id) })
                    Tile(calendarColor(c.colorArgb), 14.dp)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(c.name, style = MaterialTheme.typography.bodyLarge)
                        Text("${c.accountName} · ${c.kind.koLabel()}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            val target = calendars.firstOrNull { it.id == d.calendarId }
            if (target != null && !target.kind.reachesGoogle) {
                Text(
                    "이 기기에만 저장되어 아이폰 등 다른 기기에는 보이지 않습니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (initial.id != null && initial.calendarId != d.calendarId) {
                Text(
                    "다른 캘린더로 옮기면 새 캘린더에 일정을 만들고 원래 일정은 지웁니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                OloTextButton("취소", onDismiss)
                OloButton("저장", { onSave(d.copy(title = d.title.trim(), keepExistingRule = keepRule && scope != EditScope.THIS)) }, enabled = valid)
            }
            Spacer(Modifier.height(8.dp))
        }
    }

    pick?.let { target ->
        when (target) {
            PickTarget.START_DATE -> DateDialog(d.startDate, { pick = null }) { nd ->
                val span = d.startDate.daysUntilSafe(d.endDate)
                d = d.copy(startDate = nd, endDate = nd.plusDays(span))
                pick = null
            }
            PickTarget.END_DATE -> DateDialog(d.endDate, { pick = null }) { d = d.copy(endDate = it); pick = null }
            PickTarget.START_TIME -> TimeDialog(d.startTime, { pick = null }) { nt ->
                // Keep the duration when the start moves.
                val s0 = d.startDate.at(d.startTime, zone)
                val e0 = d.endDate.at(d.endTime, zone)
                val s1 = d.startDate.at(nt, zone)
                val e1 = (s1 + (e0 - s0)).toLocalDateTime(zone)
                d = d.copy(startTime = nt, endDate = e1.date, endTime = e1.time)
                pick = null
            }
            PickTarget.END_TIME -> TimeDialog(d.endTime, { pick = null }) { d = d.copy(endTime = it); pick = null }
            PickTarget.UNTIL -> {
                val cur = (d.recurrence?.end as? RecurrenceEnd.Until)?.lastDay ?: d.startDate.plusDays(30)
                DateDialog(cur, { pick = null }) { nd ->
                    d.recurrence?.let { r -> d = d.copy(recurrence = r.copy(end = RecurrenceEnd.Until(nd))) }
                    pick = null
                }
            }
        }
    }
}

@Composable
private fun DateTimeRow(
    label: String,
    date: LocalDate,
    time: LocalTime,
    allDay: Boolean,
    onDate: () -> Unit,
    onTime: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.width(48.dp))
        OloOutlinedButton("${date.koDate()} (${date.dayOfWeek.koShort()})", onDate)
        if (!allDay) {
            Spacer(Modifier.width(8.dp))
            OloOutlinedButton(clock(time, LocalUse24Hour.current), onTime)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RecurrenceEditor(
    value: Recurrence?,
    start: LocalDate,
    onPickUntil: () -> Unit,
    onChange: (Recurrence?) -> Unit,
) {
    Text("반복", style = MaterialTheme.typography.labelLarge)
    val choices = listOf<Pair<String, Frequency?>>("안 함" to null, "매일" to Frequency.DAILY, "매주" to Frequency.WEEKLY, "매월" to Frequency.MONTHLY, "매년" to Frequency.YEARLY)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        choices.forEach { (label, f) ->
            FilterChip(
                selected = value?.frequency == f,
                onClick = { onChange(f?.let { Recurrence(it, end = value?.end ?: RecurrenceEnd.Never) }) },
                label = { Text(label) },
                shape = MaterialTheme.shapes.extraSmall,
            )
        }
    }
    if (value == null) return
    if (value.frequency == Frequency.WEEKLY) {
        val days = value.days.ifEmpty { setOf(start.dayOfWeek) }
        val order = listOf(DayOfWeek.SUNDAY, DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY, DayOfWeek.SATURDAY)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            order.forEach { day ->
                val on = day in days
                FilterChip(
                    selected = on,
                    onClick = {
                        val next = if (on) days - day else days + day
                        if (next.isNotEmpty()) onChange(value.copy(days = next))
                    },
                    label = { Text(day.koShort()) },
                    shape = MaterialTheme.shapes.extraSmall,
                )
            }
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("간격", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(48.dp))
        NumberField(value.interval, 1..99, Modifier.width(80.dp)) { onChange(value.copy(interval = it)) }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(value.end == RecurrenceEnd.Never, { onChange(value.copy(end = RecurrenceEnd.Never)) }, { Text("계속") }, shape = MaterialTheme.shapes.extraSmall)
        FilterChip(value.end is RecurrenceEnd.Count, { onChange(value.copy(end = RecurrenceEnd.Count(10))) }, { Text("횟수") }, shape = MaterialTheme.shapes.extraSmall)
        FilterChip(value.end is RecurrenceEnd.Until, onPickUntil, { Text("날짜까지") }, shape = MaterialTheme.shapes.extraSmall)
    }
    when (val e = value.end) {
        is RecurrenceEnd.Count -> NumberField(e.times, 1..999, Modifier.width(140.dp), label = "반복 횟수") { onChange(value.copy(end = RecurrenceEnd.Count(it))) }
        is RecurrenceEnd.Until -> Text("${e.lastDay.koDate()}까지", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.clickable(onClick = onPickUntil))
        RecurrenceEnd.Never -> Unit
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateDialog(initial: LocalDate, onDismiss: () -> Unit, onPick: (LocalDate) -> Unit) {
    // Material's date picker speaks UTC-midnight millis, the same convention as
    // Android all-day events, so AllDay converts both ways.
    val state = rememberDatePickerState(initialSelectedDateMillis = AllDay.startMillis(initial))
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = { OloTextButton("확인", { state.selectedDateMillis?.let { onPick(AllDay.firstDay(it)) } ?: onDismiss() }) },
        dismissButton = { OloTextButton("취소", onDismiss) },
        shape = MaterialTheme.shapes.extraLarge,
    ) { DatePicker(state = state) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeDialog(initial: LocalTime, onDismiss: () -> Unit, onPick: (LocalTime) -> Unit) {
    val state = rememberTimePickerState(initialHour = initial.hour, initialMinute = initial.minute, is24Hour = LocalUse24Hour.current)
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { OloTextButton("확인", { onPick(LocalTime(state.hour, state.minute)) }) },
        dismissButton = { OloTextButton("취소", onDismiss) },
        text = { TimePicker(state = state) },
        shape = MaterialTheme.shapes.extraLarge,
    )
}

private fun LocalDate.plusDays(n: Int): LocalDate = kotlinx.datetime.LocalDate.fromEpochDays(toEpochDays() + n)
private fun LocalDate.daysUntilSafe(other: LocalDate): Int = maxOf(0, other.toEpochDays() - toEpochDays())

/**
 * A whole-number field that may be emptied while typing ("1" → "" → "3"): the
 * text is kept as typed, and only a number inside [range] is passed on.
 */
@Composable
private fun NumberField(value: Int, range: IntRange, modifier: Modifier, label: String? = null, onValue: (Int) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = { t ->
            if (t.length <= range.last.toString().length && t.all { it.isDigit() }) {
                text = t
                t.toIntOrNull()?.takeIf { it in range }?.let { if (it != value) onValue(it) }
            }
        },
        label = label?.let { { Text(it) } },
        singleLine = true,
        isError = text.toIntOrNull()?.let { it !in range } ?: true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
    )
}
