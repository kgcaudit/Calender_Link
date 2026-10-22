package app.olo.calendar

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.state.PreferencesGlanceStateDefinition
import app.olo.calendar.design.OloTheme
import app.olo.calendar.model.loadSettings
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.math.roundToInt

/**
 * 위젯 설정 (docs/13 §8): opened from a placed widget's long-press › 설정, like
 * Samsung's own. A preview over a wallpaper-like backdrop, then 배경 on/off,
 * 불투명도 and 색상; 저장 redraws that one widget.
 */
class WidgetConfigActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val id = intent?.extras?.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        // Backing out leaves the widget as it was (or, before Android 12, not placed).
        setResult(RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
        val kind = kindOf(this, id)
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID || kind == null) { finish(); return }
        val manager = GlanceAppWidgetManager(this)
        val glanceId = runCatching { manager.getGlanceIdBy(id) }.getOrNull()
        val fallback = AndroidPreferences(this).loadSettings().widgetBackground
        val start = glanceId?.let {
            runBlocking { runCatching { getAppWidgetState(this@WidgetConfigActivity, PreferencesGlanceStateDefinition, it) }.getOrNull() }
        }?.let { WidgetStyle.read(it as Preferences, fallback) } ?: WidgetStyle.of(fallback)
        setContent {
            OloTheme {
                val scope = rememberCoroutineScope()
                WidgetSettings(
                    kind, start,
                    onCancel = { finish() },
                    onSave = { s ->
                        scope.launch {
                            if (glanceId != null) {
                                updateAppWidgetState(this@WidgetConfigActivity, glanceId) { WidgetStyle.write(it, s) }
                                widgetOf(kind).update(this@WidgetConfigActivity, glanceId)
                            }
                            setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
                            finish()
                        }
                    },
                )
            }
        }
    }
}

private fun widgetOf(k: WidgetKind): GlanceAppWidget = when (k) {
    WidgetKind.AGENDA -> AgendaWidget()
    WidgetKind.MONTH -> MonthWidget()
    WidgetKind.NEXT -> NextWidget()
}

@Composable
private fun WidgetSettings(kind: WidgetKind, start: WidgetStyle, onCancel: () -> Unit, onSave: (WidgetStyle) -> Unit) {
    var style by remember { mutableStateOf(start) }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
                Text("위젯 설정", fontSize = 26.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 20.dp, bottom = 16.dp))
                Preview(kind, style)
                Spacer(Modifier.height(16.dp))
                Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                    Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                        Row(
                            Modifier.fillMaxWidth().height(56.dp).clickable(role = Role.Switch) { style = style.copy(background = !style.background) },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("배경", fontSize = 17.sp, modifier = Modifier.weight(1f))
                            Switch(style.background, onCheckedChange = { style = style.copy(background = it) })
                        }
                        Line()
                        Text("불투명도", fontSize = 17.sp, modifier = Modifier.padding(top = 14.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Slider(
                                value = style.opacity.toFloat(), onValueChange = { style = style.copy(opacity = (it / 10).roundToInt() * 10) },
                                valueRange = 0f..100f, steps = 9, enabled = style.background, modifier = Modifier.weight(1f),
                            )
                            Text("${style.opacity}%", fontSize = 15.sp, modifier = Modifier.width(52.dp).padding(start = 12.dp))
                        }
                        Line()
                        Text("색상", fontSize = 17.sp, modifier = Modifier.padding(top = 14.dp, bottom = 4.dp))
                        WidgetTheme.entries.forEach { t ->
                            Row(
                                Modifier.fillMaxWidth().height(48.dp).selectable(style.theme == t, role = Role.RadioButton) { style = style.copy(theme = t) },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(style.theme == t, onClick = null)
                                Text(t.label, fontSize = 16.sp, modifier = Modifier.padding(start = 12.dp))
                            }
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f).height(52.dp)) { Text("취소", fontSize = 17.sp) }
                Button(onClick = { onSave(style) }, modifier = Modifier.weight(1f).height(52.dp)) { Text("저장", fontSize = 17.sp) }
            }
        }
    }
}

@Composable
private fun Line() = Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))

/** The widget as it will look, over a stand-in wallpaper so 투명 shows something through. */
@Composable
private fun Preview(kind: WidgetKind, s: WidgetStyle) {
    val dark = when (s.theme) { WidgetTheme.SYSTEM -> isSystemInDarkTheme(); WidgetTheme.LIGHT -> false; WidgetTheme.DARK -> true }
    val bg = if (dark) Color(0xFF1C1A16) else Color(0xFFFBF9F5)
    val on = if (dark) Color(0xFFE8E3DA) else Color(0xFF1D1A16)
    val muted = if (dark) Color(0xFFCFC5B9) else Color(0xFF574D45)
    val clay = if (dark) Color(0xFFE8A183) else Color(0xFFB95B3B)
    val plain = if (dark) Color(0xFF29251F) else Color(0xFFF1ECE5)
    Box(
        Modifier.fillMaxWidth().aspectRatio(1.5f).clip(RoundedCornerShape(24.dp))
            .background(Brush.linearGradient(listOf(Color(0xFFF3D9B8), Color(0xFF9FC7BE), Color(0xFF5E7F96)))),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            // A few soft shapes so see-through is visible.
            drawCircle(Color.White.copy(alpha = 0.35f), radius = size.minDimension * 0.35f, center = center.copy(x = size.width * 0.2f))
            drawCircle(Color(0xFF2F4858).copy(alpha = 0.35f), radius = size.minDimension * 0.3f, center = center.copy(x = size.width * 0.85f, y = size.height * 0.8f))
        }
        val w = if (kind == WidgetKind.NEXT) 0.5f else 0.78f
        Column(
            Modifier.fillMaxWidth(w).clip(RoundedCornerShape(20.dp))
                .background(if (s.background) bg.copy(alpha = s.opacity / 100f) else Color.Transparent)
                .padding(14.dp),
        ) {
            when (kind) {
                WidgetKind.NEXT -> {
                    Text("오늘 · 2시간 후", color = clay, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Text("현정 저녁 모임", color = on, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Text("오후 7:00 – 9:00", color = muted, fontSize = 12.sp)
                }
                WidgetKind.AGENDA -> {
                    Text("10월 22일 목요일", color = on, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Text("오늘 · 목요일", color = clay, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
                    SampleCard("심폐소생술 교육", "종일", Color(0xFF7FB3E0).copy(alpha = if (dark) 0.45f else 0.28f), null, on, muted)
                    SampleCard("현정 저녁 모임", "오후 7:00", plain, Color(0xFF4E9BE0), on, muted)
                }
                WidgetKind.MONTH -> {
                    Text("10월", color = on, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    repeat(3) { r ->
                        Row(Modifier.fillMaxWidth()) {
                            repeat(7) { c ->
                                val n = 12 + r * 7 + c
                                Column(Modifier.weight(1f).height(34.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("$n", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = when (c) { 0 -> Color(0xFFD0384E); 6 -> Color(0xFF3D6FB0); else -> on })
                                    if ((n + c) % 3 == 0) Box(Modifier.fillMaxWidth().padding(horizontal = 1.dp).height(8.dp).clip(RoundedCornerShape(2.dp)).background(Color(0xFF7FB3E0).copy(alpha = 0.5f)))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SampleCard(title: String, right: String, fill: Color, strip: Color?, on: Color, muted: Color) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp).height(30.dp).clip(RoundedCornerShape(8.dp)).background(fill),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (strip != null) Box(Modifier.width(3.dp).fillMaxHeight().background(strip))
        Text(title, color = on, fontSize = 12.sp, modifier = Modifier.weight(1f).padding(start = 8.dp))
        Text(right, color = muted, fontSize = 11.sp, modifier = Modifier.padding(end = 8.dp))
    }
}
