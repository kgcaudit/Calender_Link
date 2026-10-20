package app.olo.calendar

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
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
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import app.olo.calendar.model.KoreanLunar
import app.olo.calendar.model.Occurrence
import app.olo.calendar.model.WIDGET_DAYS
import app.olo.calendar.model.WidgetBackground
import app.olo.calendar.model.koShort
import app.olo.calendar.model.nextUp
import app.olo.calendar.model.untilKo
import app.olo.calendar.ui.OpenRequest
import app.olo.calendar.ui.clock
import kotlinx.datetime.Clock
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import java.util.concurrent.TimeUnit

/**
 * W3, the small widget (docs/09 §3): the event going on now or next, and how
 * soon ("25분 후"). With nothing ahead, today's date and its lunar date.
 */
class NextWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(setOf(LINE, SQUARE))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val data = loadNextData(context)
        Widgets.scheduleTick(context, data.nextTickMillis)
        provideContent { NextContent(context, data) }
    }

    companion object {
        /** 2×1: two lines. */
        val LINE = DpSize(110.dp, 40.dp)
        /** 2×2: the day on top, the place underneath. */
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
    val event: Occurrence?,
    /** "25분 후", "진행 중", "내일", … */
    val until: String,
    val lunar: String?,
    val use24: Boolean,
    val zone: TimeZone,
    val nextTickMillis: Long,
    val background: WidgetBackground,
)

private suspend fun loadNextData(context: Context): NextData {
    val zone = TimeZone.currentSystemDefault()
    val now = Clock.System.now()
    val today = now.toLocalDateTime(zone).date
    val midnight = nextMidnight(today, zone)
    val use24 = android.text.format.DateFormat.is24HourFormat(context)
    val lunar = KoreanLunar.fromSolar(today)?.longLabel()
    if (!calendarGranted(context)) return NextData(false, today, null, "", lunar, use24, zone, midnight, WidgetBackground.SOLID)
    val src = readWidgetSource(context, today, today.plus(WIDGET_DAYS, DateTimeUnit.DAY), zone, now)
    val event = nextUp(src.shown, src.holidayCalendars, today, now)
    val until = event?.let { untilKo(it, now, today, zone) }.orEmpty()
    // Redraw when it starts and when it ends; within the hour, every 15 minutes so "N분 후" stays close.
    val soon = event?.takeIf { !it.allDay }?.let { e ->
        val begin = e.begin.toEpochMilliseconds()
        val nowMs = now.toEpochMilliseconds()
        listOfNotNull(
            begin.takeIf { it > nowMs },
            e.end.toEpochMilliseconds(),
            (nowMs + TimeUnit.MINUTES.toMillis(15)).takeIf { begin - nowMs in 1..TimeUnit.HOURS.toMillis(1) },
        ).minOrNull()
    }
    return NextData(true, today, event, until, lunar, use24, zone, minOf(midnight, soon ?: midnight), src.settings.widgetBackground)
}

@Composable
private fun NextContent(context: Context, d: NextData) {
    val square = LocalSize.current.height >= NextWidget.SQUARE.height
    val e = d.event
    val target = when {
        !d.granted -> OpenRequest.Day(d.today)
        e == null -> OpenRequest.NewEvent(d.today)
        else -> OpenRequest.Event(e.eventId, e.begin.toEpochMilliseconds())
    }
    Row(
        GlanceModifier.fillMaxSize().background(widgetBg(d.background)).cornerRadius(16.dp)
            .padding(horizontal = 12.dp, vertical = 6.dp).clickable(open(context, target)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (e != null) {
            Box(GlanceModifier.width(4.dp).fillMaxHeight().background(ColorProvider(Color(e.colorArgb))).cornerRadius(2.dp)) {}
            Spacer(GlanceModifier.width(8.dp))
        }
        Column(GlanceModifier.defaultWeight()) {
            if (square || e == null) {
                Text(
                    "${d.today.monthNumber}월 ${d.today.dayOfMonth}일 (${d.today.dayOfWeek.koShort()})",
                    style = TextStyle(color = if (e == null) OnBg else Clay, fontSize = if (e == null) 15.sp else 12.sp, fontWeight = FontWeight.Bold),
                    maxLines = 1,
                )
            }
            when {
                !d.granted -> Text("OLO를 열어 권한을 허용해 주세요", style = TextStyle(color = Muted, fontSize = 12.sp), maxLines = 2)
                e == null -> Text(
                    listOfNotNull(d.lunar, "남은 일정 없음").joinToString(" · "),
                    style = TextStyle(color = Muted, fontSize = 12.sp), maxLines = 1,
                )
                else -> {
                    val time = if (e.allDay) "" else clock(e.begin.toLocalDateTime(d.zone).time, d.use24) + " "
                    Text(
                        time + e.title.ifBlank { "(제목 없음)" },
                        style = TextStyle(color = OnBg, fontSize = 14.sp, fontWeight = FontWeight.Bold), maxLines = if (square) 2 else 1,
                    )
                    Text(
                        d.until + if (square && e.location.isNotBlank()) " · ${e.location}" else "",
                        style = TextStyle(color = if (d.until == "진행 중") Clay else Muted, fontSize = 12.sp), maxLines = 1,
                    )
                }
            }
        }
    }
}
