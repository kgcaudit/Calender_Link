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
            if (connected) Connected(state, controller, onEnableSync, onChanged, onDismiss)
            else Connect(samsung, state, controller, onEnableSync, onChanged, onDismiss)
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
            controller.startBridge(samsung.id, to.id)
            onChanged(true)
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

@Composable
private fun Connected(
    state: CalendarUiState,
    controller: CalendarController,
    onEnableSync: (String) -> Unit,
    onChanged: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val google = state.bridgeGoogle
    val r: BridgeReport? = state.bridgeReport
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
        if (it.heldDeletes > 0) {
            Surface(color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f), shape = MaterialTheme.shapes.small) {
                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                    Text("한쪽에서 일정 ${it.heldDeletes}개가 한꺼번에 사라져 다른 쪽 삭제를 멈췄습니다.", style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OloTextButton("삭제 진행", { controller.runBridge(allowMassDelete = true) })
                    }
                }
            }
        }
    }
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
