package app.olo.calendar.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.olo.calendar.design.OloTone
import app.olo.calendar.design.calendarColor
import app.olo.calendar.model.BridgePlan
import app.olo.calendar.model.CalendarInfo
import app.olo.calendar.store.BridgeReport
import kotlinx.datetime.Clock

/** "방금", "3분 전", "2시간 전", "어제". */
internal fun agoKo(atMillis: Long): String {
    val m = (Clock.System.now().toEpochMilliseconds() - atMillis) / 60_000
    return when {
        m < 1 -> "방금"
        m < 60 -> "${m}분 전"
        m < 24 * 60 -> "${m / 60}시간 전"
        else -> "${m / (24 * 60)}일 전"
    }
}

/**
 * "구글 메인 계정과 연결" (docs/07): Samsung Calendar stays as it is, and the
 * bridge keeps a copy of every event in the Google main calendar, both ways.
 */
@Composable
fun BridgeSheet(
    samsung: CalendarInfo?,
    state: CalendarUiState,
    controller: CalendarController,
    onEnableSync: (String) -> Unit,
    onChanged: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    /** Whether background passes may run freely, and the way to allow it (docs/07 §18). */
    batteryUnrestricted: Boolean = true,
    onBattery: () -> Unit = {},
) {
    if (samsung == null) { onDismiss(); return }
    val connected = state.settings.bridgeSamsungId == samsung.id && state.settings.bridgeOn
    OloSheet(onDismiss = { if (state.bridgeProgress == null) onDismiss() }) {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("구글 메인 계정과 연결", style = MaterialTheme.typography.headlineSmall)
            BridgeDiagram(samsung, if (connected) state.bridgeGoogle else null)
            Text(
                "${samsung.name}은 그대로 두고, 같은 일정을 구글 메인 계정에 복제해 계속 맞춥니다. " +
                    "갤럭시 탭·북은 ${samsung.name}로, 아이폰은 구글 계정으로 같은 일정을 봅니다.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            state.bridgeProgress?.let { (d, t) ->
                Text("맞추는 중… $d / $t", style = MaterialTheme.typography.bodyLarge)
                LinearProgressIndicator(progress = { if (t == 0) 0f else d.toFloat() / t }, modifier = Modifier.fillMaxWidth())
            }
            if (connected) Connected(state, controller, onEnableSync, onChanged, onDismiss, batteryUnrestricted, onBattery)
            else Connect(samsung, state, controller, onEnableSync, onChanged, onDismiss, batteryUnrestricted, onBattery)
        }
    }
}

@Composable
private fun BridgeDiagram(samsung: CalendarInfo, google: CalendarInfo?) {
    Surface(color = OloTone.container, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            End(calendarColor(samsung.colorArgb), "S", samsung.name, "갤럭시 폰·탭·북", Modifier.weight(1f))
            Text("⇄", fontSize = 22.sp, color = OloTone.accent, modifier = Modifier.padding(horizontal = 8.dp))
            End(calendarColor(google?.colorArgb ?: 0xFF3D74B8.toInt()), "G", google?.accountName ?: "구글 메인 계정", "아이폰·아이패드·맥", Modifier.weight(1f))
        }
    }
}

@Composable
private fun End(hue: androidx.compose.ui.graphics.Color, letter: String, title: String, sub: String, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(36.dp).background(hue, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
            Text(letter, fontWeight = FontWeight.Bold, color = OloTone.onSolid(hue))
        }
        Text(title, style = MaterialTheme.typography.labelLarge, maxLines = 1, modifier = Modifier.padding(top = 6.dp))
        Text(sub, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Connect(
    samsung: CalendarInfo,
    state: CalendarUiState,
    controller: CalendarController,
    onEnableSync: (String) -> Unit,
    onChanged: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    batteryUnrestricted: Boolean,
    onBattery: () -> Unit,
) {
    val targets = state.googleTargets
    var target by remember { mutableStateOf(targets.firstOrNull { it.isPrimary } ?: targets.firstOrNull()) }
    var plan by remember { mutableStateOf<BridgePlan?>(null) }
    LaunchedEffect(target?.id) {
        plan = null
        target?.let { t -> plan = runCatching { controller.previewBridge(samsung.id, t.id) }.getOrNull() }
    }
    if (targets.isEmpty()) {
        Text("휴대폰에 Google 계정이 없습니다. 설정 › 계정에서 구글 메인 계정을 먼저 추가해 주세요.", color = MaterialTheme.colorScheme.error)
        OloTextButton("닫기", onDismiss)
        return
    }
    Text("구글 메인 계정", style = MaterialTheme.typography.labelLarge)
    targets.forEach { c ->
        Row(Modifier.fillMaxWidth().clickable { target = c }, verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = target?.id == c.id, onClick = { target = c })
            Column(Modifier.weight(1f)) {
                Text(c.accountName, style = MaterialTheme.typography.bodyLarge)
                Text(if (c.name == c.accountName) "기본 캘린더" else c.name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    val t = target
    if (t != null && (t.accountName in state.sync.accountsWithSyncOff || t.accountName in state.sync.accountsNeedingAccess)) {
        Surface(color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f), shape = MaterialTheme.shapes.small) {
            Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("이 계정의 캘린더 동기화가 꺼져 있어 아이폰까지 가지 않습니다.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                OloTextButton("켜기", { onEnableSync(t.accountName) })
            }
        }
    }
    if (t != null) RecoverCard(samsung.id, t.id, state, controller)
    val p = plan
    if (p == null) {
        Text("두 캘린더를 비교하는 중…", style = MaterialTheme.typography.bodyMedium)
    } else {
        Text("처음 연결하면", style = MaterialTheme.typography.labelLarge)
        PlanLine("${samsung.name} → 구글", p.toGoogle)
        PlanLine("구글 → ${samsung.name}", p.toSamsung)
        PlanLine("이미 같은 일정(짝만 지음)", p.paired)
        listOf(
            "원본은 지우지 않습니다. 이후 어느 쪽에서 고치거나 지워도 다른 쪽이 따라갑니다.",
            "양쪽에서 같은 일정을 동시에 고치면 ${samsung.name} 쪽을 남깁니다.",
            "한 번에 많은 일정이 지워지면 멈추고 확인을 받습니다.",
            "참석자와 삼성 전용 정보(스티커 등)는 복제하지 않습니다.",
            "OLO에서는 짝지어진 일정을 한 번만 보여 줍니다.",
        ).forEach { Text("· $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
        OloTextButton("취소", onDismiss)
        OloButton("연결 시작", enabled = t != null && p != null && state.bridgeProgress == null, onClick = {
            val to = target ?: return@OloButton
            // Background passes start only after the first one has recorded its pairs.
            // Connected: ask once to run in the background, or the bridge sleeps when OLO is closed.
            controller.startBridge(samsung.id, to.id) { onChanged(true); if (!batteryUnrestricted) onBattery() }
        })
    }
}

@Composable
private fun PlanLine(label: String, n: Int) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(6.dp).background(OloTone.accent, CircleShape))
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text("${n}개", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
    }
}

/**
 * The bridge runs while OLO is closed only if the phone lets it (docs/07 §18).
 * Shown when the background passes fell behind (over 30 minutes when OLO
 * opened) or the last pass failed; one tap goes to the setting that fixes it.
 */
@Composable
private fun BackgroundCard(state: CalendarUiState, batteryUnrestricted: Boolean, onBattery: () -> Unit, controller: CalendarController) {
    val stale = state.bridgeStaleFor
    val error = state.bridgeError
    if (stale == null && error == null && batteryUnrestricted) return
    val cs = MaterialTheme.colorScheme
    Surface(color = cs.errorContainer.copy(alpha = 0.55f), shape = MaterialTheme.shapes.small) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            when {
                error != null -> {
                    Text("마지막 맞춤이 실패했습니다", style = MaterialTheme.typography.titleSmall)
                    Text(error, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                    OloTextButton("다시 맞추기", { controller.runBridge() }, enabled = state.bridgeProgress == null)
                }
                else -> {
                    val hours = (stale ?: 0L) / 3_600_000L
                    Text(
                        if (stale != null) "앱을 닫아 둔 동안 자동 맞춤이 ${if (hours >= 1) "${hours}시간" else "${stale / 60_000L}분"} 멈춰 있었습니다"
                        else "앱을 닫으면 자동 맞춤이 멈출 수 있습니다",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        if (batteryUnrestricted) "배터리는 제한 없음입니다. 설정 → 배터리 → 백그라운드 사용 제한에서 '잠자는 앱'에 OLO가 있으면 빼 주세요."
                        else "Samsung 절전 관리가 백그라운드 작업을 막고 있습니다. 절전 예외로 두면 닫아 둔 동안에도 맞춥니다.",
                        style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OloButton(if (batteryUnrestricted) "앱 설정 열기" else "절전 예외로 두기", onBattery)
                        if (stale != null) OloTextButton("확인", { controller.dismissBridgeStale() })
                    }
                }
            }
        }
    }
}

@Composable
private fun Connected(
    state: CalendarUiState,
    controller: CalendarController,
    onEnableSync: (String) -> Unit,
    onChanged: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    batteryUnrestricted: Boolean,
    onBattery: () -> Unit,
) {
    val google = state.bridgeGoogle
    val r: BridgeReport? = state.bridgeReport
    BackgroundCard(state, batteryUnrestricted, onBattery, controller)
    var dupes by remember { mutableStateOf(emptyList<String>() to emptyList<String>()) }
    LaunchedEffect(r?.atMillis, state.bridgeProgress == null) {
        if (state.bridgeProgress == null) dupes = runCatching { controller.bridgeDuplicates() }.getOrDefault(emptyList<String>() to emptyList())
    }
    val dupeCount = dupes.first.size + dupes.second.size
    if (dupeCount > 0) {
        Surface(color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f), shape = MaterialTheme.shapes.small) {
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("똑같은 일정이 두 벌 있습니다", style = MaterialTheme.typography.titleSmall)
                Text(
                    "${state.bridgeSamsung?.name ?: "Samsung Calendar"} ${dupes.first.size}개 · ${google?.accountName ?: "구글"} ${dupes.second.size}개: " +
                        (dupes.first + dupes.second).distinct().take(8).joinToString(", ") +
                        ". 제목·시작·끝·반복이 같은 일정끼리 묶어, 알림·메모가 가장 많이 남은 한 벌만 두고 지웁니다.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row { OloButton("중복 ${dupeCount}개 정리", { controller.removeBridgeDuplicates { dupes = emptyList<String>() to emptyList() } }, enabled = state.bridgeProgress == null) }
            }
        }
    }
    Text(
        r?.let { "마지막 맞춤 ${agoKo(it.atMillis)} · 짝 ${it.linked}개" } ?: "첫 맞춤을 기다리는 중",
        style = MaterialTheme.typography.titleSmall,
    )
    r?.let {
        val parts = buildList {
            if (it.toGoogle > 0) add("구글로 ${it.toGoogle}")
            if (it.toSamsung > 0) add("삼성으로 ${it.toSamsung}")
            if (it.deleted > 0) add("삭제 ${it.deleted}")
            if (it.conflicts > 0) add("동시 수정 ${it.conflicts}건은 삼성 쪽을 남김")
            if (it.failed > 0) add("실패 ${it.failed}")
        }
        if (parts.isNotEmpty()) Text("지난번: " + parts.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    // A few deletions carry over by themselves (0.10.2); for a day they can be undone here.
    var autoDeleted by remember { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(r?.atMillis, state.bridgeProgress == null) {
        if (state.bridgeProgress == null) autoDeleted = controller.bridgeAutoDeleted()
    }
    if (autoDeleted.isNotEmpty()) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.small) {
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("함께 지운 일정 ${autoDeleted.size}개", style = MaterialTheme.typography.titleSmall)
                Text(
                    autoDeleted.take(6).joinToString(", ") + if (autoDeleted.size > 6) " 외 ${autoDeleted.size - 6}개" else "",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text("한쪽에서 지운 일정을 다른 쪽에서도 지웠습니다. 24시간 동안 되돌릴 수 있습니다.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OloTextButton("되돌리기", { controller.undoBridgeAutoDeleted(); autoDeleted = emptyList() }, enabled = state.bridgeProgress == null)
                    OloTextButton("확인", { controller.keepBridgeAutoDeleted(); autoDeleted = emptyList() })
                }
            }
        }
    }
    // Many vanishing at once (more than AUTO_DELETE_LIMIT) waits here for a person (0.8.3).
    var pending by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    LaunchedEffect(r?.atMillis, state.bridgeProgress == null) {
        if (state.bridgeProgress == null) pending = runCatching { controller.bridgePending() }.getOrDefault(emptyList())
    }
    if (pending.isNotEmpty()) {
        Surface(color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f), shape = MaterialTheme.shapes.small) {
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("한쪽에서만 사라진 일정 ${pending.size}개", style = MaterialTheme.typography.titleSmall)
                Text(
                    "다른 쪽에는 아직 남아 있습니다: " + pending.take(8).joinToString(", ") { it.first } + if (pending.size > 8) " 외 ${pending.size - 8}개" else "",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text("직접 지운 것이 아니라면 '되살리기'를 누르세요. 남아 있는 쪽에서 다시 복제합니다.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OloButton("되살리기", { controller.keepBridgePending(); pending = emptyList() }, enabled = state.bridgeProgress == null)
                    OloTextButton("양쪽에서 지우기", { controller.runBridge(allowMassDelete = true); pending = emptyList() })
                }
            }
        }
    }
    val s = state.bridgeSamsung
    if (s != null && google != null) RecoverCard(s.id, google.id, state, controller)
    LogCard(controller)
    if (google != null && (google.accountName in state.sync.accountsWithSyncOff || google.accountName in state.sync.accountsNeedingAccess)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${google.accountName} 동기화가 꺼져 있습니다.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
            OloTextButton("켜기", { onEnableSync(google.accountName) })
        }
    }
    google?.let {
        Text(
            "· 삼성 캘린더 앱에서 ${it.accountName} 캘린더 표시를 끄면 같은 일정이 두 번 보이지 않습니다. 아이폰에서 만든 일정도 ${state.bridgeSamsung?.name ?: "Samsung Calendar"}로 들어오므로 빠지는 일정은 없습니다.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
        OloTextButton("연결 해제", { controller.stopBridge(); onChanged(false); onDismiss() })
        Spacer(Modifier.weight(1f))
        OloOutlinedButton("지금 맞추기", { controller.runBridge() }, enabled = state.bridgeProgress == null)
        OloButton("닫기", onDismiss)
    }
}

/**
 * Events a wrong deletion took that the phone still holds (deleted rows the sync
 * adapter has not purged yet): brought back into Samsung Calendar.
 */
@Composable
private fun RecoverCard(samsungId: String, googleId: String, state: CalendarUiState, controller: CalendarController) {
    var found by remember { mutableStateOf<List<String>>(emptyList()) }
    var restored by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(samsungId, googleId, state.bridgeProgress == null) {
        if (state.bridgeProgress == null) found = runCatching { controller.bridgeRecoverable(samsungId, googleId) }.getOrDefault(emptyList())
    }
    restored?.let { Text("일정 ${it}개를 되살렸습니다.", style = MaterialTheme.typography.bodyMedium, color = OloTone.accent) }
    if (found.isEmpty()) return
    Surface(color = OloTone.container, shape = MaterialTheme.shapes.small) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("되살릴 수 있는 지워진 일정 ${found.size}개", style = MaterialTheme.typography.titleSmall)
            Text(
                found.take(10).joinToString(", ") + if (found.size > 10) " 외 ${found.size - 10}개" else "",
                style = MaterialTheme.typography.bodySmall,
            )
            Text("휴대폰에 아직 남아 있는 삭제 기록에서 찾았습니다. Samsung Calendar로 되살리고, 연결되어 있으면 구글에도 복제합니다.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row { OloButton("${found.size}개 되살리기", { controller.restoreDeleted(samsungId, googleId) { n -> restored = n; found = emptyList() } }, enabled = state.bridgeProgress == null) }
        }
    }
}

/** The bridge's record: what each pass did, deletions by title. */
@Composable
private fun LogCard(controller: CalendarController) {
    var open by remember { mutableStateOf(false) }
    val lines = controller.bridgeLog
    if (lines.isEmpty()) return
    Text(
        if (open) "최근 기록 접기" else "최근 기록 보기 (${lines.size})",
        style = MaterialTheme.typography.labelLarge, color = OloTone.accent,
        modifier = Modifier.clickable { open = !open }.padding(vertical = 6.dp),
    )
    if (open) {
        lines.take(20).forEach { line ->
            val at = line.substringBefore('|').toLongOrNull()
            Text(
                (at?.let { agoKo(it) + " · " } ?: "") + line.substringAfter('|'),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
