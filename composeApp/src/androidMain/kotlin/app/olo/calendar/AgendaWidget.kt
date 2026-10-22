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
import androidx.glance.appwidget.lazy.LazyColumn
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
import androidx.work.ExistingWorkPolicy
import app.olo.calendar.model.AgendaDay
import app.olo.calendar.model.KoreanLunar
import app.olo.calendar.model.Occurrence
import app.olo.calendar.model.WIDGET_DAYS
import app.olo.calendar.model.WidgetBackground
import app.olo.calendar.model.koShort
import app.olo.calendar.model.rangeKo
import app.olo.calendar.model.widgetAgenda
import app.olo.calendar.ui.OpenRequest
import app.olo.calendar.ui.clock
import kotlinx.datetime.Clock
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime

/**
 * W1, the agenda widget (docs/09 §3, docs/13 §3): today and the next two weeks,
 * grouped by day, all-day events as filled cards and timed ones with a colour
 * strip and their time; the bridge's state underneath on 4×3 and up. Reads the
 * phone's calendar store itself and shows what the app shows.
 */
class AgendaWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(setOf(SMALL, WIDE, TALL))
    override val stateDefinition: GlanceStateDefinition<*> = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val first = loadAgenda(context)
        Widgets.scheduleTick(context, first.nextTickMillis)
        provideContent {
            val d = rememberWidgetData(first) { loadAgenda(context).also { Widgets.scheduleTick(context, it.nextTickMillis) } }
            AgendaContent(context, d)
        }
    }

    companion object {
        /** 3×2. */
        val SMALL = DpSize(180.dp, 110.dp)
        /** 4×2 (the default) and wider: the end time and place too. */
        val WIDE = DpSize(250.dp, 110.dp)
        /** 4×3 and taller: the bridge line under the list. */
        val TALL = DpSize(250.dp, 200.dp)
    }
}

class AgendaWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = AgendaWidget()

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        // Calendar changes reach the widget through the same content trigger the bridge uses.
        BridgeScheduler.armOnChange(context, ExistingWorkPolicy.KEEP)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        Widgets.lastOneRemoved(context)
    }
}

private class AgendaData(
    val granted: Boolean,
    val today: LocalDate,
    val lunar: String?,
    val days: List<AgendaDay>,
    val status: Pair<String, Boolean>?,
    val use24: Boolean,
    val zone: TimeZone,
    val nowMillis: Long,
    val nextTickMillis: Long,
    val background: WidgetBackground = WidgetBackground.SOLID,
)

private suspend fun loadAgenda(context: Context): AgendaData {
    val zone = TimeZone.currentSystemDefault()
    val now = Clock.System.now()
    val today = now.toLocalDateTime(zone).date
    val midnight = nextMidnight(today, zone)
    val use24 = android.text.format.DateFormat.is24HourFormat(context)
    if (!calendarGranted(context)) return AgendaData(false, today, null, emptyList(), null, use24, zone, now.toEpochMilliseconds(), midnight)
    val src = readWidgetSource(context, today, today.plus(WIDGET_DAYS, DateTimeUnit.DAY), zone, now)
    val days = widgetAgenda(src.shown, src.holidayCalendars, today, now)
    val lunar = if (src.settings.showLunar) KoreanLunar.fromSolar(today)?.longLabel()?.replace("음력 ", "음 ") else null
    // Redraw when one of today's events starts ("진행 중") or ends (it leaves the list).
    val nowMs = now.toEpochMilliseconds()
    val marks = days.firstOrNull()?.items.orEmpty().filter { !it.allDay }
        .flatMap { listOf(it.begin.toEpochMilliseconds(), it.end.toEpochMilliseconds()) }.filter { it > nowMs }
    return AgendaData(true, today, lunar, days, src.status, use24, zone, nowMs, minOf(midnight, marks.minOrNull() ?: midnight), src.settings.widgetBackground)
}

/** The list flattened: a day heading, then its cards. */
private sealed interface Line {
    data class Day(val date: LocalDate, val holidays: List<String>) : Line
    data class Card(val o: Occurrence, val date: LocalDate) : Line
    data object Empty : Line
}

private fun linesOf(days: List<AgendaDay>): List<Line> = days.flatMapIndexed { i, day ->
    buildList {
        add(Line.Day(day.date, day.holidays))
        day.items.forEach { add(Line.Card(it, day.date)) }
        // Only today says it is empty; another day is listed only for what it has (a holiday, say).
        if (day.items.isEmpty() && i == 0) add(Line.Empty)
    }
}

@Composable
private fun AgendaContent(context: Context, d: AgendaData) {
    val size = LocalSize.current
    val wide = size.width >= AgendaWidget.WIDE.width
    val tall = size.height >= AgendaWidget.TALL.height
    Column(GlanceModifier.fillMaxSize().background(widgetBg(d.background)).widgetShape().padding(horizontal = 14.dp, vertical = 12.dp)) {
        Row(GlanceModifier.fillMaxWidth().padding(bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(GlanceModifier.defaultWeight().clickable(open(context, OpenRequest.Day(d.today)))) {
                Text(
                    "${d.today.monthNumber}월 ${d.today.dayOfMonth}일 ${d.today.dayOfWeek.koShort()}요일",
                    style = TextStyle(color = OnBg, fontSize = 19.sp, fontWeight = FontWeight.Bold), maxLines = 1,
                )
                if (d.lunar != null) Text(d.lunar, style = TextStyle(color = Muted, fontSize = 13.sp), maxLines = 1)
            }
            AddButton(context, d.today, 40.dp)
        }
        if (!d.granted) { NoPermission(context, d.today); return@Column }
        val lines = linesOf(d.days)
        // Every size scrolls (docs/13 §3 W1): a small widget is a window on the same list.
        LazyColumn(GlanceModifier.defaultWeight().fillMaxWidth()) {
            items(lines.size) { i -> LineView(context, lines[i], d, wide) }
        }
        if (tall) d.status?.let { (text, alert) -> StatusLine(context, text, alert) }
    }
}

@Composable
private fun LineView(context: Context, l: Line, d: AgendaData, wide: Boolean) {
    when (l) {
        is Line.Day -> {
            val name = "${l.date.dayOfWeek.koShort()}요일"
            val label = when (l.date) {
                d.today -> "오늘 · $name"
                d.today.plus(1, DateTimeUnit.DAY) -> "내일 · $name"
                else -> "${l.date.monthNumber}월 ${l.date.dayOfMonth}일 $name"
            }
            val color = when {
                l.date == d.today -> Clay
                l.holidays.isNotEmpty() || l.date.dayOfWeek == DayOfWeek.SUNDAY -> Holiday
                l.date.dayOfWeek == DayOfWeek.SATURDAY -> Saturday
                else -> Muted
            }
            Row(
                GlanceModifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp).clickable(open(context, OpenRequest.Day(l.date))),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(label, style = TextStyle(color = color, fontSize = 14.sp, fontWeight = FontWeight.Bold), maxLines = 1)
                if (l.holidays.isNotEmpty()) {
                    Text("  " + l.holidays.joinToString(", "), style = TextStyle(color = Holiday, fontSize = 13.sp), maxLines = 1)
                }
            }
        }
        is Line.Card -> EventCard(context, l.o, l.date, d, wide)
        Line.Empty -> Box(GlanceModifier.fillMaxWidth().padding(vertical = 2.dp)) {
            Box(GlanceModifier.fillMaxWidth().height(42.dp).background(Pill).cornerRadius(10.dp).padding(horizontal = 12.dp), contentAlignment = Alignment.CenterStart) {
                Text("남은 일정 없음", style = TextStyle(color = Muted, fontSize = 14.sp), maxLines = 1)
            }
        }
    }
}

/** An all-day event as a filled card; a timed one as a plain card with its colour strip and time. */
@Composable
private fun EventCard(context: Context, o: Occurrence, date: LocalDate, d: AgendaData, wide: Boolean) {
    val live = !o.allDay && o.begin.toEpochMilliseconds() <= d.nowMillis && o.end.toEpochMilliseconds() > d.nowMillis
    val right = when {
        o.allDay -> "종일"
        live -> "진행 중"
        o.firstDay < date -> "계속"
        wide -> rangeKo(o, d.zone, d.use24)
        else -> clock(o.begin.toLocalDateTime(d.zone).time, d.use24)
    }
    val title = o.title.ifBlank { "(제목 없음)" } + if (wide && o.location.isNotBlank()) " · ${o.location}" else ""
    Box(GlanceModifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Row(
            // 42dp tall with 3dp between: close to the 48dp touch target (docs/13 §7).
            GlanceModifier.fillMaxWidth().height(42.dp)
                .background(if (o.allDay) softFill(o.colorArgb) else Plain).cornerRadius(10.dp)
                .clickable(open(context, OpenRequest.Event(o.eventId, o.begin.toEpochMilliseconds()))),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!o.allDay) Box(GlanceModifier.width(4.dp).fillMaxHeight().background(solid(o.colorArgb))) {}
            Spacer(GlanceModifier.width(if (o.allDay) 12.dp else 10.dp))
            Text(title, style = TextStyle(color = OnBg, fontSize = 15.sp, fontWeight = FontWeight.Medium), maxLines = 1, modifier = GlanceModifier.defaultWeight())
            Text(
                right,
                style = TextStyle(color = if (live) Clay else Muted, fontSize = 13.sp, fontWeight = if (live) FontWeight.Bold else FontWeight.Normal),
                maxLines = 1, modifier = GlanceModifier.padding(start = 8.dp, end = 12.dp),
            )
        }
    }
}
