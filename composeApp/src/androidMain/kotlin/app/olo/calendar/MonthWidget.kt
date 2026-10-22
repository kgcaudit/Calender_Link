package app.olo.calendar

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
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
import androidx.glance.currentState
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
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import app.olo.calendar.model.KoreanLunar
import app.olo.calendar.model.Occurrence
import app.olo.calendar.model.WeekSegment
import app.olo.calendar.model.WidgetBackground
import app.olo.calendar.model.YearMonth
import app.olo.calendar.model.koShort
import app.olo.calendar.model.layoutWeek
import app.olo.calendar.model.loadSettings
import app.olo.calendar.model.monthGrid
import app.olo.calendar.model.weekHeader
import app.olo.calendar.ui.OpenRequest
import app.olo.calendar.ui.gridEvents
import app.olo.calendar.ui.isHoliday
import kotlinx.datetime.Clock
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime

/**
 * W2, the month widget (docs/09 §3, docs/13 §3): the app's month grid with the
 * events' titles as bars in each week (one bar across the days of a long event,
 * "+N" for what does not fit), ‹ › to turn months without opening OLO. The
 * number of bar rows follows the height: 2 on 4×4, 3 on 4×5.
 */
class MonthWidget : GlanceAppWidget() {
    // Exact: the bars are laid out by the real column width, not a size bucket's.
    override val sizeMode = SizeMode.Exact
    override val stateDefinition: GlanceStateDefinition<*> = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val first = loadMonth(context, offsetOf(getAppWidgetState(context, PreferencesGlanceStateDefinition, id)))
        Widgets.scheduleTick(context, first.nextTickMillis)
        provideContent {
            val offset = offsetOf(currentState())
            val d = rememberWidgetData(first, key = offset) { loadMonth(context, offset) }
            Styled(d.background) { MonthContent(context, d) }
        }
    }

    companion object {
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

/** ‹ / › / the today chip: turn the page by [STEP] months, 0 back to this month. */
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
    val weeks: List<List<LocalDate>>,
    val weekStart: DayOfWeek,
    /** What the bars draw: the shown events, holiday calendars' names in rose (the app's [gridEvents]). */
    val events: List<Occurrence>,
    val red: Set<LocalDate>,
    val lunar: Map<LocalDate, String>,
    val status: Pair<String, Boolean>?,
    val nextTickMillis: Long,
    val background: WidgetBackground = WidgetBackground.SOLID,
)

private suspend fun loadMonth(context: Context, offset: Int): MonthData {
    val zone = TimeZone.currentSystemDefault()
    val now = Clock.System.now()
    val today = now.toLocalDateTime(zone).date
    val month = YearMonth.of(today).plusMonths(offset)
    val midnight = nextMidnight(today, zone)
    val settings = AndroidPreferences(context).loadSettings()
    val grid = monthGrid(month, settings.weekStart)
    val weeks = grid.chunked(7)
    if (!calendarGranted(context)) {
        return MonthData(offset, false, month, today, weeks, settings.weekStart, emptyList(), emptySet(), emptyMap(), null, midnight)
    }
    val src = readWidgetSource(context, grid.first(), grid.last().plus(1, DateTimeUnit.DAY), zone, now)
    val events = gridEvents(src.shown, src.holidayCalendars)
    val red = grid.filter { it.dayOfWeek == DayOfWeek.SUNDAY || isHoliday(it, src.shown, src.holidayCalendars) }.toSet()
    // The app's rule: Sundays and the lunar 1st and 15th.
    val lunar = if (!src.settings.showLunar) emptyMap() else grid.mapNotNull { d ->
        KoreanLunar.fromSolar(d)?.takeIf { d.dayOfWeek == DayOfWeek.SUNDAY || it.day == 1 || it.day == 15 }?.let { d to it.label() }
    }.toMap()
    return MonthData(offset, true, month, today, weeks, src.settings.weekStart, events, red, lunar, src.status, midnight, src.settings.widgetBackground)
}

// Sizes (docs/13 §7): text 12sp and up (Material's small label/body), touch targets near 48dp.
private val PAD_H = 8.dp
private val HEADER_H = 44.dp
private val WEEKDAY_H = 20.dp
private val NUMBER_H = 20.dp
private val LANE_H = 18.dp
private val STATUS_H = 28.dp

@Composable
private fun MonthContent(context: Context, d: MonthData) {
    val size = LocalSize.current
    val colW: Dp = (size.width - PAD_H * 2) / 7
    // 4×5 and taller (≥ 430dp: a 4×4 is about 375dp on a Galaxy home screen) also has the bridge line.
    val tall = size.height >= 430.dp
    val withStatus = d.status != null && tall
    val gridH = size.height - 16.dp - HEADER_H - WEEKDAY_H - (if (withStatus) STATUS_H else 0.dp)
    val weekH = gridH / d.weeks.size.coerceAtLeast(1)
    // As many bar rows as the week has room for: 2 on 4×4, 3 on 4×5 (docs/13 §3 W2).
    val lanes = ((weekH - NUMBER_H) / LANE_H).toInt().coerceIn(1, if (tall) 3 else 2)
    Column(GlanceModifier.fillMaxSize().background(widgetBg()).widgetShape().padding(horizontal = PAD_H, vertical = 8.dp)) {
        MonthHeader(context, d)
        Row(GlanceModifier.fillMaxWidth().height(WEEKDAY_H)) {
            weekHeader(d.weekStart).forEach { w ->
                Text(
                    w.koShort(),
                    style = TextStyle(
                        color = when (w) { DayOfWeek.SUNDAY -> Holiday; DayOfWeek.SATURDAY -> Saturday; else -> Muted },
                        fontSize = 12.sp, textAlign = TextAlign.Center,
                    ),
                    modifier = GlanceModifier.width(colW),
                )
            }
        }
        Box(GlanceModifier.fillMaxWidth().height(1.dp).background(Divider)) {}
        if (!d.granted) { NoPermission(context, d.today); return@Column }
        Column(GlanceModifier.fillMaxWidth().defaultWeight()) {
            d.weeks.forEach { week -> WeekView(context, d, week, colW, weekH, lanes) }
        }
        if (withStatus) d.status?.let { (text, alert) -> StatusLine(context, text, alert) }
    }
}

@Composable
private fun MonthHeader(context: Context, d: MonthData) {
    Row(GlanceModifier.fillMaxWidth().height(HEADER_H), verticalAlignment = Alignment.CenterVertically) {
        Arrow("‹", -1)
        Text(
            "${d.month.month}월",
            style = TextStyle(color = OnBg, fontSize = 19.sp, fontWeight = FontWeight.Bold),
            modifier = GlanceModifier.clickable(open(context, OpenRequest.Day(if (d.offset == 0) d.today else d.month.firstDay))),
        )
        Arrow("›", 1)
        Text("${d.month.year}", style = TextStyle(color = Muted, fontSize = 13.sp), modifier = GlanceModifier.padding(start = 2.dp))
        Spacer(GlanceModifier.defaultWeight())
        // Today's number: back to this month, or (already here) open OLO on today.
        Box(
            GlanceModifier.height(32.dp).width(40.dp).background(Divider).cornerRadius(8.dp).clickable(
                if (d.offset != 0) actionRunCallback<MonthStep>(actionParametersOf(MonthStep.STEP to 0)) else open(context, OpenRequest.Day(d.today)),
            ),
            contentAlignment = Alignment.Center,
        ) {
            Text("${d.today.dayOfMonth}", style = TextStyle(color = OnBg, fontSize = 14.sp, fontWeight = FontWeight.Bold))
        }
        Spacer(GlanceModifier.width(8.dp))
        AddButton(context, d.today, 36.dp)
    }
}

@Composable
private fun Arrow(glyph: String, step: Int) {
    Box(
        GlanceModifier.width(40.dp).height(HEADER_H).clickable(actionRunCallback<MonthStep>(actionParametersOf(MonthStep.STEP to step))),
        contentAlignment = Alignment.Center,
    ) {
        Text(glyph, style = TextStyle(color = Clay, fontSize = 24.sp, fontWeight = FontWeight.Bold))
    }
}

/**
 * One week: the day numbers, [lanes] rows of bars, then "+N" where some did not
 * fit. Every part of a day's column opens that day in OLO (docs/13 §7) — the
 * number, its bars, the "+N" and the blank below — as Samsung's month widget does.
 */
@Composable
private fun WeekView(context: Context, d: MonthData, week: List<LocalDate>, colW: Dp, weekH: Dp, lanes: Int) {
    val layout = layoutWeek(week, d.events, lanes) { false }
    Column(GlanceModifier.fillMaxWidth().height(weekH)) {
        // "+N" sits beside the day number, as in the app's month view, so bars keep the room.
        Row(GlanceModifier.fillMaxWidth().height(NUMBER_H)) {
            week.forEachIndexed { i, day -> DayNumber(context, d, day, colW, layout.hiddenPerDay[i]) }
        }
        for (lane in 0 until lanes) {
            LaneRow(context, d, week, layout.segments.filter { it.lane == lane }.sortedBy { it.startCol }, colW)
        }
        // The blank under a day still belongs to it.
        Row(GlanceModifier.fillMaxWidth().defaultWeight()) {
            week.forEach { day -> Box(GlanceModifier.width(colW).fillMaxHeight().clickable(open(context, OpenRequest.Day(day)))) {} }
        }
        Box(GlanceModifier.fillMaxWidth().height(1.dp).background(Divider)) {}
    }
}

@Composable
private fun DayNumber(context: Context, d: MonthData, day: LocalDate, colW: Dp, hidden: Int) {
    val inMonth = day.monthNumber == d.month.month
    val isToday = day == d.today
    val color = when {
        isToday -> OnClay
        !inMonth -> Faint
        day in d.red -> Holiday
        day.dayOfWeek == DayOfWeek.SATURDAY -> Saturday
        else -> OnBg
    }
    Row(
        GlanceModifier.width(colW).fillMaxHeight().padding(start = 3.dp).clickable(open(context, OpenRequest.Day(day))),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Today: the app's Clay tile behind the number.
        Box(
            GlanceModifier.height(20.dp).padding(horizontal = 4.dp).then(if (isToday) GlanceModifier.background(Clay).cornerRadius(5.dp) else GlanceModifier),
            contentAlignment = Alignment.Center,
        ) {
            Text("${day.dayOfMonth}", style = TextStyle(color = color, fontSize = 14.sp, fontWeight = FontWeight.Bold), maxLines = 1)
        }
        if (colW >= 48.dp && (hidden == 0 || colW >= 64.dp)) d.lunar[day]?.let { Text(it, style = TextStyle(color = Faint, fontSize = 10.sp), maxLines = 1) }
        if (hidden > 0) {
            Spacer(GlanceModifier.defaultWeight())
            Text("+$hidden", style = TextStyle(color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold), maxLines = 1, modifier = GlanceModifier.padding(end = 3.dp))
        }
    }
}

/** One row of bars: each event across its days (long events as one bar); a free day is a blank that opens it. */
@Composable
private fun LaneRow(context: Context, d: MonthData, week: List<LocalDate>, segments: List<WeekSegment>, colW: Dp) {
    Row(GlanceModifier.fillMaxWidth().height(LANE_H)) {
        var col = 0
        segments.forEach { s ->
            while (col < s.startCol) { FreeDay(context, week[col], colW); col++ }
            Bar(context, d, s, colW * (s.endCol - s.startCol + 1), week[s.startCol])
            col = s.endCol + 1
        }
        while (col < 7) { FreeDay(context, week[col], colW); col++ }
    }
}

@Composable
private fun FreeDay(context: Context, day: LocalDate, colW: Dp) {
    Box(GlanceModifier.width(colW).height(LANE_H).clickable(open(context, OpenRequest.Day(day)))) {}
}

@Composable
private fun Bar(context: Context, d: MonthData, s: WeekSegment, width: Dp, day: LocalDate) {
    val o = s.occurrence
    val faded = o.lastDay < d.month.firstDay || o.firstDay > d.month.plusMonths(1).firstDay.plus(-1, DateTimeUnit.DAY)
    val filled = o.allDay || s.spansDays
    Box(
        GlanceModifier.width(width).height(LANE_H).padding(horizontal = 1.dp, vertical = 1.dp)
            // A bar is part of its day: it opens the day (its events listed), as tapping the number does.
            .clickable(open(context, OpenRequest.Day(day))),
    ) {
        Row(
            GlanceModifier.fillMaxSize().then(if (filled) GlanceModifier.background(softFill(o.colorArgb, faded)).cornerRadius(4.dp) else GlanceModifier),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // A timed event: a thin strip in its colour, no fill (docs/13 §2-2).
            if (!filled) Box(GlanceModifier.width(2.dp).fillMaxHeight().background(solid(o.colorArgb, faded))) {}
            Text(
                o.title.ifBlank { "(제목 없음)" },
                style = TextStyle(color = if (faded) Faint else OnBg, fontSize = 12.sp),
                maxLines = 1, modifier = GlanceModifier.padding(start = 3.dp),
            )
        }
    }
}
