package app.olo.calendar

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.glance.GlanceModifier
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.unit.ColorProvider as FixedColor
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.olo.calendar.model.AppSettings
import app.olo.calendar.model.Occurrence
import app.olo.calendar.model.WidgetBackground
import app.olo.calendar.model.isShown
import app.olo.calendar.model.loadSettings
import app.olo.calendar.model.shownOccurrences
import app.olo.calendar.store.BridgeEngine
import app.olo.calendar.store.bridgeStatusLine
import app.olo.calendar.ui.OpenRequest
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import java.util.concurrent.TimeUnit

/** The clock moved (time zone, set by hand): the widgets' days are different now. */
class WidgetClockReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = Widgets.refresh(context)
}

object Widgets {
    const val TICK = "olo.widget.tick"
    private const val NOW = "olo.widget.now"

    /**
     * Bumped on every redraw request. A widget's session can outlive one update
     * and recompose with what it read before (docs/13 §1-5: today's events missing
     * while the app showed them); each widget reads its events again when this changes.
     */
    val VERSION = intPreferencesKey("dataVersion")

    private val kinds: List<() -> GlanceAppWidget> = listOf({ AgendaWidget() }, { MonthWidget() }, { NextWidget() })

    /** Redraws every OLO widget soon (off the caller's thread). */
    fun refresh(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            NOW, ExistingWorkPolicy.REPLACE, OneTimeWorkRequestBuilder<WidgetWorker>().build(),
        )
    }

    /** Whether any OLO widget is on a home screen. */
    suspend fun any(context: Context): Boolean = runCatching {
        val m = GlanceAppWidgetManager(context)
        kinds.any { m.getGlanceIds(it()::class.java).isNotEmpty() }
    }.getOrDefault(false)

    /** Redraws every OLO widget now (from a worker), each reading its events afresh. */
    suspend fun updateAll(context: Context) {
        val m = GlanceAppWidgetManager(context)
        kinds.forEach { make ->
            val widget = make()
            m.getGlanceIds(widget::class.java).forEach { id ->
                runCatching {
                    updateAppWidgetState(context, PreferencesGlanceStateDefinition, id) { p ->
                        p.toMutablePreferences().apply { this[VERSION] = (p[VERSION] ?: 0) + 1 }
                    }
                    widget.update(context, id)
                }
            }
        }
    }

    /** A kind of widget left the home screen: stop its schedules if no OLO widget remains. */
    fun lastOneRemoved(context: Context) {
        if (anyPlaced(context)) return
        WorkManager.getInstance(context).cancelUniqueWork(TICK)
        context.getSharedPreferences("olo_widgets", Context.MODE_PRIVATE).edit().remove("nextTick").apply()
        if (!AndroidPreferences(context).loadSettings().bridgeOn) BridgeScheduler.cancel(context)
    }

    /** Without suspending: whether any OLO widget is placed. */
    fun anyPlaced(context: Context): Boolean {
        val m = android.appwidget.AppWidgetManager.getInstance(context)
        return receivers.any { m.getAppWidgetIds(android.content.ComponentName(context, it)).isNotEmpty() }
    }

    /** Receiver classes, for the platform's widget lists. */
    val receivers = listOf(AgendaWidgetReceiver::class.java, MonthWidgetReceiver::class.java, NextWidgetReceiver::class.java)

    /**
     * The next redraw nobody announces: midnight (a new day heads the list) or
     * when today's next event starts or ends, at most every 5 minutes.
     */
    fun scheduleTick(context: Context, atMillis: Long) {
        // Several widgets ask: the earliest still ahead wins, so none waits past its moment.
        val sp = context.getSharedPreferences("olo_widgets", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val pending = sp.getLong("nextTick", 0L)
        if (pending > now && pending <= atMillis) return
        sp.edit().putLong("nextTick", atMillis).apply()
        val delay = (atMillis - now).coerceAtLeast(TimeUnit.MINUTES.toMillis(5))
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

/**
 * The widget's data, read again whenever [Widgets.VERSION] moves (and when [key]
 * changes, e.g. the month turned to). [first] is what provideGlance read.
 */
@Composable
internal fun <T> rememberWidgetData(first: T, key: Any? = null, load: suspend () -> T): T {
    val version = currentState<Preferences>()[Widgets.VERSION] ?: 0
    var data by remember { mutableStateOf(first) }
    var seen by remember { mutableStateOf(version to key) }
    LaunchedEffect(version, key) {
        if (seen != (version to key)) {
            data = load()
            seen = version to key
        }
    }
    return data
}

/** What every OLO widget reads: the app's settings and the events it would show for [from]–[to) (days). */
internal class WidgetSource(
    val settings: AppSettings,
    val shown: List<Occurrence>,
    val holidayCalendars: Set<String>,
    val status: Pair<String, Boolean>?,
)

internal suspend fun readWidgetSource(context: Context, from: LocalDate, to: LocalDate, zone: TimeZone, now: Instant): WidgetSource {
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

// OLO's tokens (design/Theme.kt), light and dark.
internal val Bg = ColorProvider(day = Color(0xFFFBF9F5), night = Color(0xFF1C1A16))
internal val OnBg = ColorProvider(day = Color(0xFF1D1A16), night = Color(0xFFE8E3DA))
internal val Muted = ColorProvider(day = Color(0xFF574D45), night = Color(0xFFCFC5B9))
internal val Faint = ColorProvider(day = Color(0xFFB4A99E), night = Color(0xFF6A6058))
internal val Clay = ColorProvider(day = Color(0xFFB95B3B), night = Color(0xFFE8A183))
internal val OnClay = ColorProvider(day = Color.White, night = Color(0xFF4A1E0C))
internal val Holiday = ColorProvider(day = Color(0xFFA50E2E), night = Color(0xFFFFB0BE))
internal val Saturday = ColorProvider(day = Color(0xFF3D6FB0), night = Color(0xFF9DBEEB))
internal val Ok = ColorProvider(day = Color(0xFF2F6B4F), night = Color(0xFF8FD1AE))
internal val Divider = ColorProvider(day = Color(0xFFE7E1D6), night = Color(0xFF2E2A25))
/** The plain card behind a timed event (docs/13 §2-2). */
internal val Plain = ColorProvider(day = Color(0xFFF1ECE5), night = Color(0xFF29251F))
internal val Pill = ColorProvider(day = Color(0xFFF1ECE5), night = Color(0xFF29251F))

/** The widget's card, as see-through as the person chose (설정 > 위젯 > 배경). */
internal fun widgetBg(b: WidgetBackground) =
    if (b == WidgetBackground.SOLID) Bg else ColorProvider(day = Color(0xFFFBF9F5).copy(alpha = b.alpha), night = Color(0xFF1C1A16).copy(alpha = b.alpha))

/** A calendar colour filled softly behind an all-day event, stronger at night so it still reads. */
internal fun softFill(argb: Int, faded: Boolean = false): FixedColor {
    val c = Color(argb)
    val k = if (faded) 0.45f else 1f
    return ColorProvider(day = c.copy(alpha = 0.28f * k), night = c.copy(alpha = 0.45f * k))
}

internal fun solid(argb: Int, faded: Boolean = false) = FixedColor(Color(argb).copy(alpha = if (faded) 0.45f else 1f))

/** The widget's own shape: the home screen's widget corner on Android 12+ (docs/13 §2-5). */
internal fun GlanceModifier.widgetShape(): GlanceModifier =
    if (Build.VERSION.SDK_INT >= 31) cornerRadius(android.R.dimen.system_app_widget_background_radius) else cornerRadius(20.dp)

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

/** The round + that opens a new event on [day]. */
@Composable
internal fun AddButton(context: Context, day: LocalDate, size: androidx.compose.ui.unit.Dp = 30.dp) {
    Box(
        GlanceModifier.size(size).background(Clay).cornerRadius(size / 2).clickable(open(context, OpenRequest.NewEvent(day))),
        contentAlignment = Alignment.Center,
    ) {
        Text("+", style = TextStyle(color = OnClay, fontSize = if (size >= 36.dp) 22.sp else 18.sp, fontWeight = androidx.glance.text.FontWeight.Bold))
    }
}

/** "● 연동 3분 전 · 문제 없음" under a hairline; tapping it opens the bridge sheet. */
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
            Text(text, style = TextStyle(color = if (alert) Holiday else Muted, fontSize = 13.sp), maxLines = 1)
        }
    }
}

/** "OLO를 열어 캘린더 권한을 허용해 주세요" — tapping opens the app. */
@Composable
internal fun NoPermission(context: Context, today: LocalDate) {
    Text(
        "OLO를 열어 캘린더 권한을 허용해 주세요",
        style = TextStyle(color = Muted, fontSize = 13.sp),
        modifier = GlanceModifier.padding(top = 8.dp).clickable(open(context, OpenRequest.Day(today))),
    )
}
