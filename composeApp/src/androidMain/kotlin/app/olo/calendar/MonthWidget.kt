package app.olo.calendar

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalSize
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.state.GlanceStateDefinition
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import app.olo.calendar.model.MonthCell
import app.olo.calendar.model.Occurrence
import app.olo.calendar.model.YearMonth
import app.olo.calendar.model.koShort
import app.olo.calendar.model.loadSettings
import app.olo.calendar.model.monthGrid
import app.olo.calendar.model.monthWidgetWeeks
import app.olo.calendar.model.on
import app.olo.calendar.model.sortedForDay
import app.olo.calendar.model.weekHeader
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
 * W2, the month widget (docs/09 §3): the app's month grid with a dot per event,
 * ‹ › to turn months without opening OLO, and on 5×5 today's events underneath
 * (what Samsung's month widget leaves out). The month turned to is kept per
 * widget and goes back to this month the next day.
 */
class MonthWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(setOf(COMPACT, FULL, TALL))
    override val stateDefinition: GlanceStateDefinition<*> = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val first = loadMonthData(context, offsetOf(getAppWidgetState(context, PreferencesGlanceStateDefinition, id)))
        Widgets.scheduleTick(context, first.nextTickMillis)
        provideContent {
            val offset = offsetOf(currentState())
            var data by remember { mutableStateOf(first) }
            // A turned page reloads only its month; the session keeps the rest.
            LaunchedEffect(offset) { if (offset != data.offset) data = loadMonthData(context, offset) }
            MonthContent(context, data)
        }
    }

    companion object {
        /** 4×3: numbers and dots. */
        val COMPACT = DpSize(250.dp, 160.dp)
        /** 4×4: roomier rows. */
        val FULL = DpSize(250.dp, 250.dp)
        /** 5×5: lunar dates and today's events under the grid. */
        val TALL = DpSize(300.dp, 330.dp)

        internal val OFFSET = intPreferencesKey("monthOffset")
        /** The day the page was turned on (epoch day): a later day starts from this month again. */
        internal val TURNED_ON = intPreferencesKey("turnedOn")
    }
}

class MonthWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = MonthWidget()

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        BridgeScheduler.armOnChange(context, androidx.work.ExistingWorkPolicy.KEEP)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        Widgets.lastOneRemoved(context)
    }
}

/** ‹ / › / the title: turn the page by [STEP] months, 0 back to this month. */
class MonthStep : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val step = parameters[STEP] ?: 0
        val today = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date.toEpochDays()
        updateAppWidgetState(context, PreferencesGlanceStateDefinition, glanceId) { p ->
            p.toMutablePreferences().apply {
                val current = if (p[MonthWidget.TURNED_ON] == today) p[MonthWidget.OFFSET] ?: 0 else 0
                this[MonthWidget.OFFSET] = if (step == 0) 0 else current + step
                this[MonthWidget.TURNED_ON] = today
            }
        }
        MonthWidget().update(context, glanceId)
    }

    companion object {
        val STEP = ActionParameters.Key<Int>("step")
    }
}

private fun offsetOf(p: Preferences): Int {
    val today = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date.toEpochDays()
    return if (p[MonthWidget.TURNED_ON] == today) p[MonthWidget.OFFSET] ?: 0 else 0
}

private class MonthData(
    val offset: Int,
    val granted: Boolean,
    val month: YearMonth,
    val today: LocalDate,
    val weekStart: DayOfWeek,
    val weeks: List<List<MonthCell>>,
    val todayEvents: List<Occurrence>,
    val status: Pair<String, Boolean>?,
    val use24: Boolean,
    val zone: TimeZone,
    val nextTickMillis: Long,
)

private suspend fun loadMonthData(context: Context, offset: Int): MonthData {
    val zone = TimeZone.currentSystemDefault()
    val now = Clock.System.now()
    val today = now.toLocalDateTime(zone).date
    val month = YearMonth.of(today).plusMonths(offset)
    val use24 = android.text.format.DateFormat.is24HourFormat(context)
    val midnight = nextMidnight(today, zone)
    if (!calendarGranted(context)) {
        return MonthData(offset, false, month, today, DayOfWeek.SUNDAY, emptyList(), emptyList(), null, use24, zone, midnight)
    }
    val grid = monthGrid(month, AndroidPreferences(context).loadSettings().weekStart)
    // The grid's days, and today (it may lie on another page) for the line underneath.
    val from = minOf(grid.first(), today)
    val to = maxOf(grid.last(), today).plus(1, DateTimeUnit.DAY)
    val src = readWidgetSource(context, from, to, zone, now)
    val weeks = monthWidgetWeeks(month, src.settings.weekStart, src.shown, src.holidayCalendars, today, src.settings.showLunar)
    val todayEvents = src.shown.on(today).filter { it.calendarId !in src.holidayCalendars && (it.allDay || it.end > now) }.sortedForDay()
    val nextEnd = todayEvents.filter { !it.allDay }.minOfOrNull { it.end.toEpochMilliseconds() }
    return MonthData(offset, true, month, today, src.settings.weekStart, weeks, todayEvents, src.status, use24, zone, minOf(midnight, nextEnd ?: midnight))
}

private val TodayFill = ColorProvider(day = Color(0xFFB95B3B), night = Color(0xFFE8A183))
private val Faint = ColorProvider(day = Color(0xFFB4A99E), night = Color(0xFF6A6058))
private val Saturday = ColorProvider(day = Color(0xFF3D6FB0), night = Color(0xFF9DBEEB))

@Composable
private fun MonthContent(context: Context, d: MonthData) {
    val size = LocalSize.current
    val tall = size.height >= MonthWidget.TALL.height
    val roomy = size.height >= MonthWidget.FULL.height
    Column(GlanceModifier.fillMaxSize().background(Bg).cornerRadius(20.dp).padding(horizontal = 10.dp, vertical = 8.dp)) {
        MonthHeader(context, d)
        if (!d.granted) {
            Text(
                "OLO를 열어 캘린더 권한을 허용해 주세요",
                style = TextStyle(color = Muted, fontSize = 13.sp),
                modifier = GlanceModifier.padding(top = 8.dp).clickable(open(context, OpenRequest.Day(d.today))),
            )
            return@Column
        }
        Row(GlanceModifier.fillMaxWidth().padding(vertical = 2.dp)) {
            weekHeader(d.weekStart).forEach { w ->
                Text(
                    w.koShort(),
                    style = TextStyle(
                        color = when (w) { DayOfWeek.SUNDAY -> Holiday; DayOfWeek.SATURDAY -> Saturday; else -> Muted },
                        fontSize = 11.sp, textAlign = TextAlign.Center,
                    ),
                    modifier = GlanceModifier.defaultWeight(),
                )
            }
        }
        Column(GlanceModifier.fillMaxWidth().defaultWeight()) {
            d.weeks.forEach { week ->
                Row(GlanceModifier.fillMaxWidth().defaultWeight()) {
                    week.forEach { c -> DayCell(context, c, roomy, tall) }
                }
            }
        }
        if (tall) TodayLine(context, d)
        if (tall) d.status?.let { (text, alert) -> StatusLine(context, text, alert) }
    }
}

@Composable
private fun MonthHeader(context: Context, d: MonthData) {
    Row(GlanceModifier.fillMaxWidth().padding(bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Arrow(context, "‹", -1)
        // The title opens the app on this month; turned away, it first comes back to today.
        Text(
            "${d.month.year}년 ${d.month.month}월",
            style = TextStyle(color = OnBg, fontSize = 15.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center),
            modifier = GlanceModifier.defaultWeight().clickable(
                if (d.offset != 0) actionRunCallback<MonthStep>(actionParametersOf(MonthStep.STEP to 0))
                else open(context, OpenRequest.Day(d.today)),
            ),
        )
        Arrow(context, "›", 1)
        Spacer(GlanceModifier.width(6.dp))
        Box(
            GlanceModifier.size(28.dp).background(Clay).cornerRadius(14.dp).clickable(open(context, OpenRequest.NewEvent(d.today))),
            contentAlignment = Alignment.Center,
        ) {
            Text("+", style = TextStyle(color = OnClay, fontSize = 17.sp, fontWeight = FontWeight.Bold))
        }
    }
}

@Composable
private fun Arrow(context: Context, glyph: String, step: Int) {
    Box(
        GlanceModifier.size(32.dp).clickable(actionRunCallback<MonthStep>(actionParametersOf(MonthStep.STEP to step))),
        contentAlignment = Alignment.Center,
    ) {
        Text(glyph, style = TextStyle(color = Clay, fontSize = 20.sp, fontWeight = FontWeight.Bold))
    }
}

@Composable
private fun androidx.glance.layout.RowScope.DayCell(context: Context, c: MonthCell, roomy: Boolean, tall: Boolean) {
    val number = when {
        c.isToday -> OnClay
        !c.inMonth -> Faint
        c.red -> Holiday
        c.date.dayOfWeek == DayOfWeek.SATURDAY -> Saturday
        else -> OnBg
    }
    Column(
        GlanceModifier.defaultWeight().fillMaxSize().clickable(open(context, OpenRequest.Day(c.date))),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val circle = if (roomy) 22.dp else 18.dp
        Box(
            GlanceModifier.size(circle).then(if (c.isToday) GlanceModifier.background(TodayFill).cornerRadius(circle / 2) else GlanceModifier),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                c.date.dayOfMonth.toString(),
                style = TextStyle(color = number, fontSize = if (roomy) 13.sp else 11.sp, fontWeight = if (c.isToday) FontWeight.Bold else FontWeight.Normal),
            )
        }
        if (tall && c.lunar != null) Text(c.lunar, style = TextStyle(color = Faint, fontSize = 8.sp))
        if (c.dots.isNotEmpty()) {
            Row(GlanceModifier.padding(top = 1.dp), verticalAlignment = Alignment.CenterVertically) {
                c.dots.forEach { argb ->
                    Box(GlanceModifier.padding(horizontal = 1.dp).size(4.dp).background(ColorProvider(Color(argb).copy(alpha = if (c.inMonth) 1f else 0.4f))).cornerRadius(2.dp)) {}
                }
            }
        }
    }
}

/** 5×5: today's events in a line or two (Samsung's month widget has no such line). */
@Composable
private fun TodayLine(context: Context, d: MonthData) {
    val items = d.todayEvents
    Column(GlanceModifier.fillMaxWidth().padding(top = 4.dp)) {
        Box(GlanceModifier.fillMaxWidth().height(1.dp).background(Divider)) {}
        if (items.isEmpty()) {
            Text(
                "오늘 남은 일정 없음",
                style = TextStyle(color = Muted, fontSize = 12.sp),
                modifier = GlanceModifier.padding(top = 4.dp).clickable(open(context, OpenRequest.NewEvent(d.today))),
            )
        }
        items.take(2).forEachIndexed { i, o ->
            val time = if (o.allDay) "종일" else clock(o.begin.toLocalDateTime(d.zone).time, d.use24)
            val more = if (i == 1 && items.size > 2) " 외 ${items.size - 2}개" else ""
            Row(
                GlanceModifier.fillMaxWidth().padding(top = 4.dp).clickable(open(context, OpenRequest.Event(o.eventId, o.begin.toEpochMilliseconds()))),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(GlanceModifier.width(4.dp).height(14.dp).background(ColorProvider(Color(o.colorArgb))).cornerRadius(2.dp)) {}
                Spacer(GlanceModifier.width(6.dp))
                Text(
                    (if (i == 0) "오늘 " else "") + "$time  " + o.title.ifBlank { "(제목 없음)" } + more,
                    style = TextStyle(color = OnBg, fontSize = 12.sp), maxLines = 1,
                )
            }
        }
    }
}
