package app.olo.calendar

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.olo.calendar.model.loadSettings
import app.olo.calendar.store.BridgeConfig
import app.olo.calendar.store.BridgeEngine
import java.util.concurrent.TimeUnit

/**
 * The bridge while OLO is closed (docs/07 §3). Two jobs:
 * - a one-shot job Android starts when anything in the calendar store changes
 *   (Samsung Calendar edited on the phone, a Samsung Cloud or Google sync
 *   landing), re-armed after every run;
 * - a 15-minute periodic job as the safety net for anything missed.
 */
object BridgeScheduler {
    private const val ON_CHANGE = "olo.bridge.onChange"
    private const val PERIODIC = "olo.bridge.periodic"

    fun schedule(context: Context) {
        val wm = WorkManager.getInstance(context)
        // KEEP: opening the app must not cancel a background pass halfway through its writes.
        armOnChange(context, ExistingWorkPolicy.KEEP)
        wm.enqueueUniquePeriodicWork(
            PERIODIC, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<BridgeWorker>(15, TimeUnit.MINUTES).build(),
        )
    }

    fun armOnChange(context: Context, policy: ExistingWorkPolicy) {
        val constraints = Constraints.Builder()
            .addContentUriTrigger(CalendarContract.CONTENT_URI, true)
            // Let a burst of writes (a sync of hundreds of events) finish first.
            .setTriggerContentUpdateDelay(5, TimeUnit.SECONDS)
            .setTriggerContentMaxDelay(60, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            ON_CHANGE, policy,
            OneTimeWorkRequestBuilder<BridgeWorker>().setConstraints(constraints).build(),
        )
    }

    /** The bridge was switched off. The change trigger stays while a widget still needs it. */
    fun cancel(context: Context) {
        val wm = WorkManager.getInstance(context)
        wm.cancelUniqueWork(PERIODIC)
        if (!Widgets.anyPlaced(context)) wm.cancelUniqueWork(ON_CHANGE)
    }
}

class BridgeWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val granted = listOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
            .all { ctx.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
        val prefs = AndroidPreferences(ctx)
        val st = prefs.loadSettings()
        if (!granted) return Result.success()
        val widgets = Widgets.any(ctx)
        if (st.bridgeSamsungId != null && st.bridgeGoogleId != null) {
            val store = AndroidCalendarStore(ctx)
            val report = runCatching { BridgeEngine(store, prefs).run(BridgeConfig(st.bridgeSamsungId, st.bridgeGoogleId), trigger = "백그라운드") }
            report.getOrNull()?.let { if (it.toGoogle + it.toSamsung + it.deleted > 0) store.requestSync() }
        } else if (!widgets) {
            return Result.success()
        }
        // The same change redraws the widgets (docs/09 §5), after the bridge has had its say.
        if (widgets) Widgets.updateAll(ctx)
        // A content-triggered job runs once; arm the next one (our own writes settle in one pass).
        // Appended behind this run, which REPLACE would cancel. A failed pass is not retried
        // here: the next change or the periodic job runs it again.
        BridgeScheduler.armOnChange(ctx, ExistingWorkPolicy.APPEND_OR_REPLACE)
        return Result.success()
    }
}
