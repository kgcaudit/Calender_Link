package app.olo.calendar

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.state.GlanceStateDefinition
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import app.olo.calendar.model.KoreanLunar
import app.olo.calendar.model.Occurrence
import app.olo.calendar.model.WIDGET_DAYS
import app.olo.calendar.model.WidgetBackground
import app.olo.calendar.model.koShort
import app.olo.calendar.model.rangeKo
import app.olo.calendar.model.upcoming
import app.olo.calendar.model.whenLabelKo
import app.olo.calendar.ui.OpenRequest
import kotlinx.datetime.Clock
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import java.util.concurrent.TimeUnit

/**
 * W3, the small widget (docs/13 §3): what is on now or next, in three lines —
 * when ("오늘 · 7시간 후", "진행 중 · 오후 12:00 종료"), what, and its hours.
 * 2×2 adds the one after. Nothing ahead: today's date and lunar date.
 */
class NextWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(setOf(LINE, SQUARE))
    override val stateDefinition: GlanceStateDefinition<*> = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val first = loadNext(context)
        Widgets.scheduleTick(context, first.nextTickMillis)
        provideContent {
            val d = rememberWidgetData(first) { loadNext(context).also { Widgets.scheduleTick(context, it.nextTickMillis) } }
            NextContent(context, d)
        }
    }

    companion object {
        /** 2×1: three lines. */
        val LINE = DpSize(110.dp, 40.dp)
        /** 2×2: the next one too. */
        val SQUARE = DpSize(110.dp, 110.dp)
    }
}

class NextWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = NextWidget()

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        BridgeScheduler.armOnChange(context, androidx.work.ExistingWorkPolicy.KEEP)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        Widgets.lastOneRemoved(context)
    }
}

private class NextData(
    val granted: Boolean,
    val today: LocalDate,
    val events: List<Occurrence>,
    /** The first line for each of [events]. */
    val labels: List<String>,
    val ranges: List<String>,
    val lunar: String?,
    val nextTickMillis: Long,
    val background: WidgetBackground,
)

private suspend fun loadNext(context: Context): NextData {
    val zone = TimeZone.currentSystemDefault()
    val now = Clock.System.now()
    val today = now.toLocalDateTime(zone).date
    val midnight = nextMidnight(today, zone)
    val use24 = android.text.format.DateFormat.is24HourFormat(context)
    val lunar = KoreanLunar.fromSolar(today)?.longLabel()
    if (!calendarGranted(context)) return NextData(false, today, emptyList(), emptyList(), emptyList(), lunar, midnight, WidgetBackground.SOLID)
    val src = readWidgetSource(context, today, today.plus(WIDGET_DAYS, DateTimeUnit.DAY), zone, now)
    val events = upcoming(src.shown, src.holidayCalendars, today, now, 2)
    // Redraw when it starts and when it ends; within the hour, every 15 minutes so "N분 후" stays close.
    val nowMs = now.toEpochMilliseconds()
    val soon = events.firstOrNull()?.takeIf { !it.allDay }?.let { e ->
        val begin = e.begin.toEpochMilliseconds()
        listOfNotNull(
            begin.takeIf { it > nowMs },
            e.end.toEpochMilliseconds(),
            (nowMs + TimeUnit.MINUTES.toMillis(15)).takeIf { begin - nowMs in 1..TimeUnit.HOURS.toMillis(1) },
        ).minOrNull()
    }
    return NextData(
        true, today, events,
        events.map { whenLabelKo(it, now, today, zone, use24) },
        events.map { e -> rangeKo(e, zone, use24) + if (e.location.isNotBlank()) " · ${e.location.trim()}" else "" },
        lunar, minOf(midnight, soon ?: midnight), src.settings.widgetBackground,
    )
}

@Composable
private fun NextContent(context: Context, d: NextData) {
    val square = LocalSize.current.height >= NextWidget.SQUARE.height
    val e = d.events.firstOrNull()
    val target = when {
        !d.granted -> OpenRequest.Day(d.today)
        e == null -> OpenRequest.NewEvent(d.today)
        else -> OpenRequest.Event(e.eventId, e.begin.toEpochMilliseconds())
    }
    Column(
        GlanceModifier.fillMaxSize().background(widgetBg(d.background)).widgetShape().padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(GlanceModifier.fillMaxWidth().clickable(open(context, target)), verticalAlignment = Alignment.CenterVertically) {
            if (e != null) {
                Box(GlanceModifier.width(5.dp).height(if (square) 60.dp else 52.dp).background(solid(e.colorArgb)).cornerRadius(3.dp)) {}
                Spacer(GlanceModifier.width(10.dp))
            }
            Column(GlanceModifier.defaultWeight()) {
                when {
                    !d.granted -> Text("OLO를 열어 권한을 허용해 주세요", style = TextStyle(color = Muted, fontSize = 12.sp), maxLines = 2)
                    e == null -> {
                        Text(
                            "${d.today.monthNumber}월 ${d.today.dayOfMonth}일 (${d.today.dayOfWeek.koShort()})",
                            style = TextStyle(color = OnBg, fontSize = 18.sp, fontWeight = FontWeight.Bold), maxLines = 1,
                        )
                        Text(listOfNotNull(d.lunar, "남은 일정 없음").joinToString(" · "), style = TextStyle(color = Muted, fontSize = 13.sp), maxLines = 1)
                    }
                    else -> {
                        val label = d.labels.first()
                        Text(
                            label,
                            style = TextStyle(color = if (label.startsWith("진행 중")) Holiday else Clay, fontSize = 13.sp, fontWeight = FontWeight.Bold),
                            maxLines = 1,
                        )
                        Text(e.title.ifBlank { "(제목 없음)" }, style = TextStyle(color = OnBg, fontSize = 18.sp, fontWeight = FontWeight.Bold), maxLines = 1)
                        Text(d.ranges.first(), style = TextStyle(color = Muted, fontSize = 13.sp), maxLines = 1)
                    }
                }
            }
        }
        // 2×2: the one after, small, under a hairline.
        val second = d.events.getOrNull(1)
        if (square && second != null) {
            Box(GlanceModifier.fillMaxWidth().height(1.dp).padding(vertical = 0.dp).background(Divider)) {}
            Row(
                GlanceModifier.fillMaxWidth().padding(top = 8.dp).clickable(open(context, OpenRequest.Event(second.eventId, second.begin.toEpochMilliseconds()))),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(GlanceModifier.width(3.dp).height(30.dp).background(solid(second.colorArgb)).cornerRadius(2.dp)) {}
                Spacer(GlanceModifier.width(8.dp))
                Column(GlanceModifier.defaultWeight()) {
                    Text(second.title.ifBlank { "(제목 없음)" }, style = TextStyle(color = OnBg, fontSize = 14.sp), maxLines = 1)
                    Text(d.labels[1], style = TextStyle(color = Muted, fontSize = 12.sp), maxLines = 1)
                }
            }
        }
    }
}
