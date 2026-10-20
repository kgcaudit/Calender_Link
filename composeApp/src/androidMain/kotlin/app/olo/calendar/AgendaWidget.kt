package app.olo.calendar

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
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
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.color.ColorProvider
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
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.olo.calendar.model.AgendaDay
import app.olo.calendar.model.AppSettings
import app.olo.calendar.model.Occurrence
import app.olo.calendar.model.WIDGET_DAYS
import app.olo.calendar.model.isShown
import app.olo.calendar.model.koShort
import app.olo.calendar.model.loadSettings
import app.olo.calendar.model.shownOccurrences
import app.olo.calendar.model.widgetAgenda
import app.olo.calendar.store.BridgeEngine
import app.olo.calendar.store.bridgeStatusLine
import app.olo.calendar.ui.OpenRequest
import app.olo.calendar.ui.clock
import kotlinx.datetime.Clock
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import java.util.concurrent.TimeUnit

/**
 * W1, the agenda widget (docs/09 §3): today and the next two weeks, the bridge's
 * state underneath. Reads the phone's calendar store itself, so it works with OLO
 * closed, and shows what the app shows (same visibility, bridged pairs once).
 */
class AgendaWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(setOf(SMALL, WIDE, TALL))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val data = loadWidgetData(context)
        Widgets.scheduleTick(context, data.nextTickMillis)
        provideContent { Content(context, data) }
    }

    companion object {
        /** 3×2: header and three events. */
        val SMALL = DpSize(180.dp, 110.dp)
        /** 4×2 and wider: places shown too. */
        val WIDE = DpSize(250.dp, 110.dp)
        /** 4×4 and taller: a scrolling list and the bridge line. */
        val TALL = DpSize(250.dp, 220.dp)
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

/** The clock moved (time zone, set by hand): the list's days are different now. */
class WidgetClockReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = Widgets.refresh(context)
}

object Widgets {
    const val TICK = "olo.widget.tick"
    private const val NOW = "olo.widget.now"

    /** Redraws every OLO widget soon (off the caller's thread). */
    fun refresh(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            NOW, ExistingWorkPolicy.REPLACE, OneTimeWorkRequestBuilder<WidgetWorker>().build(),
        )
    }

    /** Whether any OLO widget is on a home screen. */
    suspend fun any(context: Context): Boolean = runCatching {
        val m = GlanceAppWidgetManager(context)
        m.getGlanceIds(AgendaWidget::class.java).isNotEmpty() || m.getGlanceIds(MonthWidget::class.java).isNotEmpty()
    }.getOrDefault(false)

    /** Redraws every OLO widget now (from a worker). */
    suspend fun updateAll(context: Context) {
        AgendaWidget().updateAll(context)
        MonthWidget().updateAll(context)
    }

    /** A kind of widget left the home screen: stop its schedules if no OLO widget remains. */
    fun lastOneRemoved(context: Context) {
        if (anyPlaced(context)) return
        WorkManager.getInstance(context).cancelUniqueWork(TICK)
        if (!AndroidPreferences(context).loadSettings().bridgeOn) BridgeScheduler.cancel(context)
    }

    /** Without suspending: whether any OLO widget is placed. */
    fun anyPlaced(context: Context): Boolean {
        val m = android.appwidget.AppWidgetManager.getInstance(context)
        return receivers.any { m.getAppWidgetIds(android.content.ComponentName(context, it)).isNotEmpty() }
    }

    /** Receiver classes, for the platform's widget lists. */
    val receivers = listOf(AgendaWidgetReceiver::class.java, MonthWidgetReceiver::class.java)

    /**
     * The next redraw nobody announces: midnight (a new day heads the list) or
     * when today's next event ends (it leaves the list), at most every 5 minutes.
     */
    fun scheduleTick(context: Context, atMillis: Long) {
        val delay = (atMillis - System.currentTimeMillis()).coerceAtLeast(TimeUnit.MINUTES.toMillis(5))
        WorkManager.getInstance(context).enqueueUniqueWork(
            TICK, ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<WidgetWorker>().setInitialDelay(delay, TimeUnit.MILLISECONDS).build(),
        )
    }
}

class WidgetWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        Widgets.updateAll(applicationContext)
        return Result.success()
    }
}

private class WidgetData(
    val granted: Boolean,
    val today: LocalDate,
    val days: List<AgendaDay>,
    val status: Pair<String, Boolean>?,
    val use24: Boolean,
    val zone: TimeZone,
    val nextTickMillis: Long,
)

/** What every OLO widget reads: the app's settings and the events it would show for [from]–[to) (days). */
internal class WidgetSource(
    val settings: AppSettings,
    val shown: List<Occurrence>,
    val holidayCalendars: Set<String>,
    val status: Pair<String, Boolean>?,
)

internal suspend fun readWidgetSource(context: Context, from: LocalDate, to: LocalDate, zone: TimeZone, now: kotlinx.datetime.Instant): WidgetSource {
    val prefs = AndroidPreferences(context)
    val settings = prefs.loadSettings()
    val store = AndroidCalendarStore(context)
    val bridge = BridgeEngine(store, prefs)
    val calendars = runCatching { store.calendars() }.getOrDefault(emptyList())
    val all = runCatching { store.occurrences(from.atStartOfDayIn(zone), to.atStartOfDayIn(zone)) }.getOrDefault(emptyList())
    val shown = shownOccurrences(all, calendars, settings, bridge.linkedGoogleIds())
    val holidayCalendars = calendars.filter { it.isHoliday && settings.isShown(it) }.map { it.id }.toSet()
    val googleAccount = calendars.firstOrNull { it.id == settings.bridgeGoogleId }?.accountName
    val syncOff = googleAccount != null && runCatching { store.syncStatus() }.getOrNull()?.let { googleAccount in it.accountsWithSyncOff } == true
    val status = bridgeStatusLine(settings.bridgeOn, bridge.lastReport, syncOff, now.toEpochMilliseconds())
    return WidgetSource(settings, shown, holidayCalendars, status)
}

internal fun calendarGranted(context: Context) =
    context.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

internal fun nextMidnight(today: LocalDate, zone: TimeZone) = today.plus(1, DateTimeUnit.DAY).atStartOfDayIn(zone).toEpochMilliseconds()

private suspend fun loadWidgetData(context: Context): WidgetData {
    val zone = TimeZone.currentSystemDefault()
    val now = Clock.System.now()
    val today = now.toLocalDateTime(zone).date
    val midnight = nextMidnight(today, zone)
    val use24 = android.text.format.DateFormat.is24HourFormat(context)
    if (!calendarGranted(context)) return WidgetData(false, today, emptyList(), null, use24, zone, midnight)
    val src = readWidgetSource(context, today, today.plus(WIDGET_DAYS, DateTimeUnit.DAY), zone, now)
    val days = widgetAgenda(src.shown, src.holidayCalendars, today, now)
    // Today's next ending, so a finished event leaves the list when it ends.
    val nextEnd = days.firstOrNull()?.items?.filter { !it.allDay && it.end > now }?.minOfOrNull { it.end.toEpochMilliseconds() }
    return WidgetData(true, today, days, src.status, use24, zone, minOf(midnight, nextEnd ?: midnight))
}

// OLO's tokens (design/Theme.kt), light and dark.
internal val Bg = ColorProvider(day = Color(0xFFFBF9F5), night = Color(0xFF1C1A16))
internal val OnBg = ColorProvider(day = Color(0xFF1D1A16), night = Color(0xFFE8E3DA))
internal val Muted = ColorProvider(day = Color(0xFF574D45), night = Color(0xFFCFC5B9))
internal val Clay = ColorProvider(day = Color(0xFFB95B3B), night = Color(0xFFE8A183))
internal val OnClay = ColorProvider(day = Color.White, night = Color(0xFF4A1E0C))
internal val Holiday = ColorProvider(day = Color(0xFFA50E2E), night = Color(0xFFFFB0BE))
internal val Ok = ColorProvider(day = Color(0xFF2F6B4F), night = Color(0xFF8FD1AE))
internal val Divider = ColorProvider(day = Color(0xFFE7E1D6), night = Color(0xFF2E2A25))

internal fun open(context: Context, r: OpenRequest) = actionStartActivity(openIntent(context, r))

/** The intent MainActivity reads back into an [OpenRequest] (a distinct data URI per target). */
fun openIntent(context: Context, r: OpenRequest): Intent {
    val i = Intent(context, MainActivity::class.java).setAction(MainActivity.ACTION_OPEN)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    return when (r) {
        is OpenRequest.Day -> i.putExtra(MainActivity.EXTRA_KIND, "day").putExtra(MainActivity.EXTRA_DAY, r.date.toEpochDays().toLong())
            .setData(Uri.parse("olo://day/${r.date}"))
        is OpenRequest.NewEvent -> i.putExtra(MainActivity.EXTRA_KIND, "new").putExtra(MainActivity.EXTRA_DAY, r.date.toEpochDays().toLong())
            .setData(Uri.parse("olo://new/${r.date}"))
        is OpenRequest.Event -> i.putExtra(MainActivity.EXTRA_KIND, "event").putExtra(MainActivity.EXTRA_EVENT, r.eventId)
            .putExtra(MainActivity.EXTRA_BEGIN, r.beginMillis).setData(Uri.parse("olo://event/${r.eventId}/${r.beginMillis}"))
        OpenRequest.Bridge -> i.putExtra(MainActivity.EXTRA_KIND, "bridge").setData(Uri.parse("olo://bridge"))
    }
}

@Composable
private fun Content(context: Context, d: WidgetData) {
    val size = LocalSize.current
    val wide = size.width >= AgendaWidget.WIDE.width
    val tall = size.height >= AgendaWidget.TALL.height
    Column(GlanceModifier.fillMaxSize().background(Bg).cornerRadius(20.dp).padding(horizontal = 14.dp, vertical = 10.dp)) {
        Header(context, d.today)
        if (!d.granted) {
            Text(
                "OLO를 열어 캘린더 권한을 허용해 주세요",
                style = TextStyle(color = Muted, fontSize = 13.sp),
                modifier = GlanceModifier.padding(top = 8.dp).clickable(open(context, OpenRequest.Day(d.today))),
            )
            return@Column
        }
        val rows = rowsOf(d.days)
        if (rows.size <= 1 && d.days.firstOrNull()?.items.isNullOrEmpty() && d.days.firstOrNull()?.holidays.isNullOrEmpty()) {
            Text(
                "앞으로 2주 동안 일정이 없습니다",
                style = TextStyle(color = Muted, fontSize = 13.sp),
                modifier = GlanceModifier.padding(top = 8.dp).defaultWeight().clickable(open(context, OpenRequest.NewEvent(d.today))),
            )
        } else if (tall) {
            LazyColumn(GlanceModifier.defaultWeight().fillMaxWidth()) {
                items(rows.size) { i -> RowView(context, rows[i], d, wide) }
            }
        } else {
            // A small widget does not scroll: as many rows as fit, today first.
            Column(GlanceModifier.defaultWeight().fillMaxWidth()) {
                rows.take(if (size.height >= 150.dp) 6 else 4).forEach { RowView(context, it, d, wide) }
            }
        }
        if (tall) d.status?.let { (text, alert) -> StatusLine(context, text, alert) }
    }
}

/** The list flattened: a day heading, then its holidays and events. */
private sealed interface WRow {
    data class Day(val date: LocalDate, val holidays: List<String>) : WRow
    data class Item(val o: Occurrence, val date: LocalDate) : WRow
    data object Empty : WRow
}

private fun rowsOf(days: List<AgendaDay>): List<WRow> = days.flatMap { day ->
    buildList {
        add(WRow.Day(day.date, day.holidays))
        day.items.forEach { add(WRow.Item(it, day.date)) }
        if (day.items.isEmpty() && day.holidays.isEmpty()) add(WRow.Empty)
    }
}

@Composable
private fun Header(context: Context, today: LocalDate) {
    Row(GlanceModifier.fillMaxWidth().padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            "${today.monthNumber}월 ${today.dayOfMonth}일 (${today.dayOfWeek.koShort()})",
            style = TextStyle(color = OnBg, fontSize = 16.sp, fontWeight = FontWeight.Bold),
            modifier = GlanceModifier.defaultWeight().clickable(open(context, OpenRequest.Day(today))),
        )
        Box(
            GlanceModifier.size(30.dp).background(Clay).cornerRadius(15.dp).clickable(open(context, OpenRequest.NewEvent(today))),
            contentAlignment = Alignment.Center,
        ) {
            Text("+", style = TextStyle(color = OnClay, fontSize = 18.sp, fontWeight = FontWeight.Bold))
        }
    }
}

@Composable
private fun RowView(context: Context, r: WRow, d: WidgetData, wide: Boolean) {
    when (r) {
        is WRow.Day -> {
            val label = when (r.date) {
                d.today -> "오늘"
                d.today.plus(1, DateTimeUnit.DAY) -> "내일 (${r.date.dayOfWeek.koShort()})"
                else -> "${r.date.monthNumber}월 ${r.date.dayOfMonth}일 (${r.date.dayOfWeek.koShort()})"
            }
            val red = r.holidays.isNotEmpty() || r.date.dayOfWeek == kotlinx.datetime.DayOfWeek.SUNDAY
            Row(
                GlanceModifier.fillMaxWidth().padding(top = 6.dp, bottom = 2.dp).clickable(open(context, OpenRequest.Day(r.date))),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(label, style = TextStyle(color = if (red) Holiday else Clay, fontSize = 12.sp, fontWeight = FontWeight.Bold))
                if (r.holidays.isNotEmpty()) {
                    Text("  " + r.holidays.joinToString(", "), style = TextStyle(color = Holiday, fontSize = 12.sp), maxLines = 1)
                }
            }
        }
        is WRow.Item -> {
            val o = r.o
            val time = when {
                o.allDay -> "종일"
                o.firstDay < r.date -> "계속"
                else -> clock(o.begin.toLocalDateTime(d.zone).time, d.use24)
            }
            Row(
                GlanceModifier.fillMaxWidth().padding(vertical = 3.dp).clickable(open(context, OpenRequest.Event(o.eventId, o.begin.toEpochMilliseconds()))),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(GlanceModifier.width(4.dp).height(16.dp).background(ColorProvider(Color(o.colorArgb))).cornerRadius(2.dp)) {}
                Spacer(GlanceModifier.width(8.dp))
                Text(time, style = TextStyle(color = Muted, fontSize = 13.sp), maxLines = 1, modifier = GlanceModifier.width(if (d.use24) 44.dp else 64.dp))
                Text(
                    o.title.ifBlank { "(제목 없음)" } + if (wide && o.location.isNotBlank()) "  · ${o.location}" else "",
                    style = TextStyle(color = OnBg, fontSize = 14.sp), maxLines = 1,
                    modifier = GlanceModifier.defaultWeight(),
                )
            }
        }
        WRow.Empty -> Text("일정 없음", style = TextStyle(color = Muted, fontSize = 13.sp), modifier = GlanceModifier.padding(vertical = 3.dp))
    }
}

@Composable
internal fun StatusLine(context: Context, text: String, alert: Boolean) {
    Column(GlanceModifier.fillMaxWidth()) {
        Box(GlanceModifier.fillMaxWidth().height(1.dp).background(Divider)) {}
        Row(
            GlanceModifier.fillMaxWidth().padding(top = 6.dp).clickable(open(context, OpenRequest.Bridge)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(GlanceModifier.size(7.dp).background(if (alert) Holiday else Ok).cornerRadius(4.dp)) {}
            Spacer(GlanceModifier.width(6.dp))
            Text(text, style = TextStyle(color = if (alert) Holiday else Muted, fontSize = 12.sp), maxLines = 1)
        }
    }
}
