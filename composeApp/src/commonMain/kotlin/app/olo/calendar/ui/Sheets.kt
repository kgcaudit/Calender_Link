package app.olo.calendar.ui

import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Switch
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.RadioButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.draw.alpha
import androidx.compose.material3.AlertDialog
import androidx.compose.ui.text.font.FontWeight
import app.olo.calendar.design.OloTone
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.olo.calendar.design.calendarColor
import app.olo.calendar.design.status
import app.olo.calendar.model.AppSettings
import app.olo.calendar.model.CalendarInfo
import kotlinx.datetime.DayOfWeek
import app.olo.calendar.model.MovePlan
import app.olo.calendar.model.MoveResult
import app.olo.calendar.model.Occurrence
import app.olo.calendar.model.StoredEvent
import app.olo.calendar.model.SyncKind
import app.olo.calendar.model.describeKo
import app.olo.calendar.model.koDate
import app.olo.calendar.model.koLabel
import app.olo.calendar.model.koShort
import androidx.compose.material3.Icon
import app.olo.calendar.design.OloIcons
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import app.olo.calendar.model.syncAccounts
import app.olo.calendar.model.syncKey

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
            "${occurrence.firstDay.koDate()} (${occurrence.firstDay.dayOfWeek.koShort()}) ${timeLabel(occurrence, controller.zone, LocalUse24Hour.current)}"
        } else {
            "${occurrence.firstDay.koDate()} – ${occurrence.lastDay.koDate()}" + if (occurrence.allDay) "" else " · ${timeLabel(occurrence, controller.zone, LocalUse24Hour.current)}"
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
    nav: NavActions,
    onOpenSync: () -> Unit,
    onDismiss: () -> Unit,
) {
    OloSheet(onDismiss) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("캘린더", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            OloTextButton("동기화 상태", onOpenSync)
        }
        Text(
            "표시 여부는 OLO에만 적용됩니다. 삼성·Google 캘린더 앱의 설정은 바뀌지 않습니다. 길게 누르면 '이것만 보기'.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Box {
          Column(Modifier.verticalScroll(rememberScrollState())) {
            CalendarTree(nav, horizontal = 0.dp)
          }
          NoteBar(nav, Modifier.align(Alignment.BottomCenter))
        }
    }
}

/** Why events might not be reaching the other phone, each with its fix. */
@Composable
fun SyncSheet(
    state: CalendarUiState,
    onSyncNow: () -> Unit,
    onOpenSyncSettings: (() -> Unit)?,
    onMove: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val st = MaterialTheme.status
    val sync = state.sync
    OloSheet(onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("동기화 상태", style = MaterialTheme.typography.headlineSmall)
            var anyIssue = false
            if (!sync.masterSyncOn) {
                anyIssue = true
                Issue(st.failed, "휴대폰 자동 동기화가 꺼져 있습니다", "어떤 계정도 Google과 동기화하지 않습니다.") {
                    onOpenSyncSettings?.let { OloOutlinedButton("동기화 설정 열기", it) }
                }
            }
            if (sync.accountsWithSyncOff.isNotEmpty()) {
                anyIssue = true
                Issue(
                    st.failed,
                    "Google 계정의 캘린더 동기화가 꺼져 있습니다",
                    sync.accountsWithSyncOff.joinToString("\n") +
                        "\n설정 › 계정 및 백업 › 계정 관리 › (계정) › 계정 동기화 › 캘린더를 켜 주세요.",
                ) {
                    onOpenSyncSettings?.let { OloOutlinedButton("계정 동기화 설정 열기", it) }
                }
            }
            state.stranded.forEach { (cal, n) ->
                anyIssue = true
                Issue(
                    st.failed,
                    "${cal.name}: 일정 ${n}개가 Google 밖에 있습니다",
                    "${cal.kind.koLabel()} 캘린더라 아이폰 등 다른 기기에 나타나지 않습니다. Google 캘린더로 옮기면 함께 보입니다.",
                ) {
                    if (state.googleTargets.isNotEmpty()) OloButton("Google로 옮기기", { onMove(cal.id) })
                    else Text("옮길 Google 캘린더가 없습니다. 먼저 Google 계정의 캘린더 동기화를 켜 주세요.", style = MaterialTheme.typography.bodySmall)
                }
            }
            if (sync.pendingUploads > 0) {
                Issue(st.waiting, "Google로 올라가기를 기다리는 변경 ${sync.pendingUploads}건", "보통 몇 분 안에 올라갑니다.") {
                    OloTextButton("지금 동기화", onSyncNow)
                }
            } else if (!anyIssue) {
                Issue(st.done, "Google과 동기화된 상태입니다", "이 휴대폰의 일정은 Google 캘린더를 거쳐 다른 기기로 갑니다.") {
                    OloTextButton("지금 동기화", onSyncNow)
                }
            }
        }
    }
}

@Composable
private fun Issue(dot: androidx.compose.ui.graphics.Color, title: String, body: String, action: @Composable () -> Unit) {
    Row {
        Tile(dot, 10.dp, Modifier.padding(top = 6.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            action()
        }
    }
}

private sealed interface MoveStep {
    data object Loading : MoveStep
    data class Ready(val plan: MovePlan) : MoveStep
    data class Running(val done: Int, val total: Int) : MoveStep
    data class Done(val result: MoveResult) : MoveStep
    data class Failed(val message: String) : MoveStep
}

/** Moves every event of a Samsung / phone-only calendar into a Google calendar, then deletes the originals. */
@Composable
fun MoveSheet(
    from: CalendarInfo?,
    targets: List<CalendarInfo>,
    accountsWithSyncOff: List<String>,
    controller: CalendarController,
    onDismiss: () -> Unit,
) {
    if (from == null) { LaunchedEffect(Unit) { onDismiss() }; return }
    var step by remember { mutableStateOf<MoveStep>(MoveStep.Loading) }
    var target by remember { mutableStateOf(targets.firstOrNull()) }
    LaunchedEffect(from.id) {
        step = runCatching { MoveStep.Ready(controller.planMove(from.id)) }.getOrElse { MoveStep.Failed(it.message ?: "읽지 못했습니다") }
    }
    OloSheet(onDismiss = { if (step !is MoveStep.Running) onDismiss() }) {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Google로 옮기기", style = MaterialTheme.typography.headlineSmall)
            Text("${from.name} (${from.kind.koLabel()}) → Google 캘린더", style = MaterialTheme.typography.bodyMedium)
            when (val s = step) {
                MoveStep.Loading -> Text("일정을 세는 중…", style = MaterialTheme.typography.bodyMedium)
                is MoveStep.Ready -> {
                    val p = s.plan
                    Text(
                        "일정 ${p.events}개를 옮깁니다" +
                            (if (p.recurring > 0) " (반복 일정 ${p.recurring}개 포함)" else "") + ".",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text("옮길 캘린더", style = MaterialTheme.typography.labelLarge)
                    targets.forEach { c ->
                        Row(Modifier.fillMaxWidth().clickable { target = c }, verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = target?.id == c.id, onClick = { target = c })
                            Tile(calendarColor(c.colorArgb), 14.dp)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(c.name, style = MaterialTheme.typography.bodyLarge)
                                Text(c.accountName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    val notes = buildList {
                        add("옮긴 뒤 ${from.name}의 원본은 삭제됩니다. 모두 옮겨진 경우에만 지웁니다.")
                        add("반복 규칙과 알림은 그대로 옮겨집니다.")
                        if (p.changedOccurrences > 0) add("반복 중 따로 바꾼 일정 ${p.changedOccurrences}개는 각각 별도 일정으로 옮겨집니다.")
                        if (p.withGuests > 0) add("참석자가 있는 일정 ${p.withGuests}개는 참석자 없이 옮깁니다. 초대 메일이 다시 나가지 않게 하기 위해서입니다.")
                        add("삼성 전용 정보(스티커, 음력 반복 등)는 옮겨지지 않습니다.")
                        if (target != null && target!!.accountName in accountsWithSyncOff) {
                            add("이 Google 계정은 캘린더 동기화가 꺼져 있습니다. 옮긴 일정은 동기화를 켜야 Google로 올라갑니다.")
                        }
                    }
                    notes.forEach { Text("· $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                        OloTextButton("취소", onDismiss)
                        OloButton("${p.events}개 옮기기", enabled = target != null && p.events > 0, onClick = {
                            val to = target ?: return@OloButton
                            step = MoveStep.Running(0, p.events)
                            controller.move(
                                from.id, to.id,
                                onProgress = { d, t -> step = MoveStep.Running(d, t) },
                                done = { r -> step = r.fold({ MoveStep.Done(it) }, { MoveStep.Failed(it.message ?: "옮기지 못했습니다") }) },
                            )
                        })
                    }
                }
                is MoveStep.Running -> {
                    Text("옮기는 중… ${s.done} / ${s.total}", style = MaterialTheme.typography.bodyLarge)
                    LinearProgressIndicator(
                        progress = { if (s.total == 0) 0f else s.done.toFloat() / s.total },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                is MoveStep.Done -> {
                    val r = s.result
                    Text(
                        "${r.moved}개를 옮겼습니다." + if (r.deleted > 0) " 원본 ${r.deleted}개를 삭제했습니다." else "",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    if (r.failed > 0) Text("옮기지 못한 일정 ${r.failed}개", color = MaterialTheme.colorScheme.error)
                    r.message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                    OloButton("닫기", onDismiss)
                }
                is MoveStep.Failed -> {
                    Text(s.message, color = MaterialTheme.colorScheme.error)
                    OloButton("닫기", onDismiss)
                }
            }
        }
    }
}

/** OLO's own settings; event data and notifications stay with the phone's calendar. */
@Composable
fun SettingsSheet(
    state: CalendarUiState,
    onChange: ((AppSettings) -> AppSettings) -> Unit,
    onDismiss: () -> Unit,
    /** Lets OLO see an account ("확인 필요"), then turns its sync on. */
    /** Allows an account OLO cannot see yet; the callback says whether the person did. */
    onAllow: (String, (Boolean) -> Unit) -> Unit = { _, done -> done(false) },
) {
    val st = state.settings
    var picking by remember { mutableStateOf(false) }
    var choosingSync by remember { mutableStateOf(false) }
    // Android's settings guidelines: groups under a short heading with a divider,
    // one line per setting, a summary only where it states a value or a limit.
    OloSheet(onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Text("설정", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(bottom = 4.dp))

            SettingGroup("보기", first = true)
            SettingRow("한 주 시작") {
                SegmentedChoice(
                    listOf(DayOfWeek.SUNDAY to "일", DayOfWeek.MONDAY to "월"), st.weekStart,
                ) { d -> onChange { it.copy(weekStart = d) } }
            }
            SettingSwitch("음력", "일요일 · 음력 1일·15일", st.showLunar) { v -> onChange { it.copy(showLunar = v) } }
            SettingSwitch("주 번호", "월 보기 왼쪽 · ISO", st.showWeekNumbers) { v -> onChange { it.copy(showWeekNumbers = v) } }
            SettingSwitch("달을 위아래로 넘기기", "폴드를 펼쳤을 때만", st.verticalPagingWide) { v -> onChange { it.copy(verticalPagingWide = v) } }

            SettingGroup("새 일정")
            val chosen = state.calendars.firstOrNull { it.id == st.defaultCalendarId && it.writable && !it.isHoliday }
            SettingRow(
                "기본 캘린더",
                summary = chosen?.name ?: state.autoCalendar?.let { "자동 · ${it.name}" } ?: "자동",
                onClick = { picking = true },
            ) {
                Icon(OloIcons.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
            }

            SettingGroup("동기화")
            val accounts = syncAccounts(state.calendars)
            val included = accounts.count { it.syncKey !in st.syncExcluded && it.accountName !in state.sync.accountsWithSyncOff }
            SettingRow("지금 동기화 대상", summary = "${accounts.size}개 계정 중 ${included}개", onClick = { choosingSync = true }) {
                Icon(OloIcons.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
            }

            SettingGroup("정보")
            SettingRow("시간 형식", summary = "휴대폰 설정(12/24시간)을 따름")
            SettingRow("알림", summary = "휴대폰·Google 캘린더 알림을 그대로 씀")
            SettingRow("개인정보", summary = "일정은 휴대폰 캘린더에만 저장, 서버 전송 없음")
        }
    }

    if (choosingSync) {
        AlertDialog(
            onDismissRequest = { choosingSync = false },
            shape = MaterialTheme.shapes.extraLarge,
            title = { Text("지금 동기화 대상") },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) { SyncTargets(state, onChange) } },
            confirmButton = { OloTextButton("닫기", onClick = { choosingSync = false }) },
        )
    }

    if (picking) {
        AlertDialog(
            onDismissRequest = { picking = false },
            shape = MaterialTheme.shapes.extraLarge,
            title = { Text("기본 캘린더") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    DefaultCalendarPicker(state, onChange, onAllow, onPicked = { picking = false })
                }
            },
            confirmButton = { OloTextButton("닫기", onClick = { picking = false }) },
        )
    }
}

/**
 * Which accounts "지금 동기화" asks (docs/07 §17). Unticked = left out by OLO
 * only. An account whose sync is off on the phone is never asked; its box is
 * greyed and says so.
 */
@Composable
private fun SyncTargets(state: CalendarUiState, onChange: ((AppSettings) -> AppSettings) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val st = state.settings
    Text("체크를 끄면 OLO의 지금 동기화만 건너뜁니다. 휴대폰 자동 동기화는 그대로입니다.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
    Spacer(Modifier.height(8.dp))
    syncAccounts(state.calendars).forEach { c ->
        val off = c.accountName in state.sync.accountsWithSyncOff && c.accountName !in state.sync.accountsNeedingAccess
        val on = !off && c.syncKey !in st.syncExcluded
        val isMain = state.bridgeGoogle?.let { it.accountName == c.accountName && it.accountType == c.accountType } == true
        val toggle = { v: Boolean -> onChange { it.copy(syncExcluded = if (v) it.syncExcluded - c.syncKey else it.syncExcluded + c.syncKey) } }
        Row(
            Modifier.fillMaxWidth().then(if (!off) Modifier.clickable { toggle(!on) } else Modifier).alpha(if (off) 0.55f else 1f).padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = on, onCheckedChange = if (off) null else toggle, enabled = !off)
            Column(Modifier.weight(1f)) {
                Text(c.accountName, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val note = when {
                    off -> "휴대폰에서 동기화 꺼짐 · 자동으로 제외"
                    isMain && !on -> "구글 메인 계정 · 아이폰 반영이 늦어질 수 있음"
                    isMain -> "${c.kind.koLabel()} · 구글 메인 계정"
                    else -> c.kind.koLabel()
                }
                Text(note, style = MaterialTheme.typography.labelSmall, color = if (isMain && !on) cs.error else cs.onSurfaceVariant)
            }
        }
    }
}

/** A group heading (accent, small) with the divider Android's settings guidelines pair with it. */
@Composable
private fun SettingGroup(label: String, first: Boolean = false) {
    if (!first) HorizontalDivider(Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
    Text(
        label, style = MaterialTheme.typography.labelLarge, color = OloTone.accent, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
    )
}

/** One setting: title, an optional one-line summary, a trailing control; 56dp (72 with a summary). */
@Composable
private fun SettingRow(title: String, summary: String? = null, onClick: (() -> Unit)? = null, trailing: @Composable () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = if (summary == null) 56.dp else 64.dp)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (summary != null) Text(summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        trailing()
    }
}

/** Two or three short choices in one compact segmented control, on the setting's own line. */
@Composable
private fun <T> SegmentedChoice(options: List<Pair<T, String>>, selected: T, onPick: (T) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(Modifier.border(1.dp, cs.outline, MaterialTheme.shapes.small).clip(MaterialTheme.shapes.small)) {
        options.forEachIndexed { i, (v, label) ->
            val on = v == selected
            if (i > 0) Box(Modifier.width(1.dp).height(36.dp).background(cs.outline))
            Box(
                Modifier.size(width = 52.dp, height = 36.dp).background(if (on) OloTone.container else Color.Transparent)
                    .clickable { onPick(v) },
                contentAlignment = Alignment.Center,
            ) {
                Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal, color = if (on) OloTone.accent else cs.onSurfaceVariant)
            }
        }
    }
}

/**
 * Where new events go (docs/07 §14), grouped as in the drawer: "연동 캘린더"
 * (Samsung Calendar and the Google main calendar) first, then the rest.
 * "자동" names what it picks right now; a phone-only calendar says its events
 * stay on this phone; an account OLO may not see yet asks to be allowed first.
 */
@Composable
private fun DefaultCalendarPicker(state: CalendarUiState, onChange: ((AppSettings) -> AppSettings) -> Unit, onAllow: (String, (Boolean) -> Unit) -> Unit, onPicked: () -> Unit = {}) {
    val cs = MaterialTheme.colorScheme
    val st = state.settings
    val writable = state.calendars.filter { it.writable && !it.isHoliday }
    val auto = state.autoCalendar
    val chosen = writable.firstOrNull { it.id == st.defaultCalendarId }
    val linkedIds = listOfNotNull(
        state.bridgeSamsung?.id ?: writable.firstOrNull { it.kind == SyncKind.SAMSUNG }?.id,
        state.googleMainGuess?.id,
    )
    val linked = linkedIds.mapNotNull { id -> writable.firstOrNull { it.id == id } }
    val general = writable.filter { it.id !in linkedIds }
    var ask by remember { mutableStateOf<CalendarInfo?>(null) }

    Text("새로 만든 일정이 저장될 곳 · 편집 화면에서 일정마다 바꿀 수 있음", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
    val pickAuto = { onChange { it.copy(defaultCalendarId = null) }; onPicked() }
    Row(Modifier.fillMaxWidth().clickable(onClick = pickAuto), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = chosen == null, onClick = pickAuto)
        Column(Modifier.weight(1f)) {
            Text("자동", style = MaterialTheme.typography.bodyMedium)
            Text(
                auto?.let { "지금은 ${it.name}" + if (it.name != it.accountName && it.kind != SyncKind.DEVICE) " · ${it.accountName}" else "" } ?: "저장할 캘린더가 없습니다",
                style = MaterialTheme.typography.bodySmall, color = OloTone.accent,
            )
        }
    }
    @Composable
    fun group(label: String, list: List<CalendarInfo>) {
        if (list.isEmpty()) return
        Text(label, style = MaterialTheme.typography.labelMedium, color = OloTone.accent, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 12.dp, top = 4.dp))
        list.forEach { c ->
            val blocked = c.accountName in state.sync.accountsNeedingAccess
            val pick = { if (blocked) ask = c else { onChange { it.copy(defaultCalendarId = c.id) }; onPicked() } }
            Row(
                Modifier.fillMaxWidth().clickable(onClick = pick).alpha(if (blocked) 0.55f else 1f),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = chosen?.id == c.id, onClick = pick)
                Tile(calendarColor(c.colorArgb), 12.dp)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(c.name, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        listOfNotNull(c.accountName.takeIf { it != c.name && c.kind != SyncKind.DEVICE }, c.kind.koLabel()).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                    )
                    when {
                        blocked -> Text("확인 필요 · 고르면 먼저 허용합니다", style = MaterialTheme.typography.labelSmall, color = cs.error)
                        c.kind == SyncKind.DEVICE -> Text("다른 기기에 안 보임", style = MaterialTheme.typography.labelSmall, color = cs.error)
                    }
                }
            }
        }
    }
    group("연동 캘린더", linked)
    group("일반 캘린더", general)

    ask?.let { c ->
        AlertDialog(
            onDismissRequest = { ask = null },
            shape = MaterialTheme.shapes.extraLarge,
            title = { Text("계정 허용이 필요합니다") },
            text = { Text("${c.accountName}은 OLO가 아직 동기화 상태를 확인하지 못한 계정입니다. 계정 선택 화면에서 한 번 허용하면 기본 캘린더로 쓰고 동기화도 켭니다.") },
            confirmButton = {
                OloTextButton("허용하고 고르기", onClick = {
                    // The default moves only once the account is allowed; cancelled, it stays as it was.
                    onAllow(c.accountName) { ok -> if (ok) onChange { it.copy(defaultCalendarId = c.id) } }
                    ask = null
                    onPicked()
                })
            },
            dismissButton = { OloTextButton("취소", onClick = { ask = null }) },
        )
    }
}

@Composable
private fun SettingSwitch(title: String, summary: String?, value: Boolean, onChange: (Boolean) -> Unit) =
    SettingRow(title, summary, onClick = { onChange(!value) }) { Switch(checked = value, onCheckedChange = onChange) }
