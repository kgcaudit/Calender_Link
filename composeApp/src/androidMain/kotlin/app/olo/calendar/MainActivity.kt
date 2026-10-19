package app.olo.calendar

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import app.olo.calendar.model.loadSettings
import app.olo.calendar.ui.App
import app.olo.calendar.ui.OpenRequest
import app.olo.calendar.ui.PermissionGate
import app.olo.calendar.ui.SystemSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class MainActivity : ComponentActivity(), PermissionGate, SystemSettings {
    private val perms = arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
    private val _granted = MutableStateFlow(false)
    private val _deniedForGood = MutableStateFlow(false)
    override val granted: StateFlow<Boolean> = _granted
    override val deniedForGood: StateFlow<Boolean> = _deniedForGood
    private var asked = false
    /** A widget tap waiting for the screen (docs/09 §6). */
    private val openRequest = MutableStateFlow<OpenRequest?>(null)

    private val launcher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        refresh()
        // Refused and Android will no longer show the prompt: only Settings can help.
        _deniedForGood.value = !_granted.value && perms.none { shouldShowRequestPermissionRationale(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        refresh()
        val store = AndroidCalendarStore(this)
        val prefs = AndroidPreferences(this)
        if (prefs.loadSettings().bridgeOn) BridgeScheduler.schedule(this)
        openRequest.value = requestOf(intent)
        setContent {
            App(
                store, this, this, use24Hour = android.text.format.DateFormat.is24HourFormat(this), prefs = prefs,
                openRequest = openRequest, onOpened = { openRequest.value = null },
                onChanged = { Widgets.refresh(this) },
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        requestOf(intent)?.let { openRequest.value = it }
    }

    private fun requestOf(i: Intent?): OpenRequest? {
        if (i?.action != ACTION_OPEN) return null
        val day = i.getLongExtra(EXTRA_DAY, Long.MIN_VALUE).takeIf { it != Long.MIN_VALUE }?.let { kotlinx.datetime.LocalDate.fromEpochDays(it.toInt()) }
        return when (i.getStringExtra(EXTRA_KIND)) {
            "day" -> day?.let { OpenRequest.Day(it) }
            "new" -> day?.let { OpenRequest.NewEvent(it) }
            "event" -> i.getStringExtra(EXTRA_EVENT)?.let { OpenRequest.Event(it, i.getLongExtra(EXTRA_BEGIN, 0L)) }
            "bridge" -> OpenRequest.Bridge
            else -> null
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
        if (!_granted.value && !asked) { asked = true; request() }
    }

    private fun refresh() {
        _granted.value = perms.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
        if (_granted.value) _deniedForGood.value = false
    }

    override fun request() = launcher.launch(perms)

    private var accountDone: ((Boolean) -> Unit)? = null
    private var accountAsked: String? = null
    private val accountChooser = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val picked = r.data?.getStringExtra(android.accounts.AccountManager.KEY_ACCOUNT_NAME)
        accountDone?.invoke(r.resultCode == RESULT_OK && picked == accountAsked)
        accountDone = null
    }

    // Picking the account in the system chooser makes it visible to OLO (Android 8+),
    // after which its calendar sync can be read and switched.
    override fun allowAccount(account: String, done: (Boolean) -> Unit) {
        val a = android.accounts.Account(account, "com.google")
        accountAsked = account
        accountDone = done
        val intent = android.accounts.AccountManager.newChooseAccountIntent(
            a, arrayListOf(a), arrayOf("com.google"), null, null, null, null,
        )
        runCatching { accountChooser.launch(intent) }.onFailure { accountDone = null; done(false) }
    }

    override fun openAccountSync(account: String) {
        val i = Intent("android.settings.ACCOUNT_SYNC_SETTINGS").putExtra("account", android.accounts.Account(account, "com.google"))
        runCatching { startActivity(i) }.onFailure { openSyncSettings() }
    }

    override fun openSyncSettings() {
        runCatching { startActivity(Intent(Settings.ACTION_SYNC_SETTINGS)) }
            .onFailure { startActivity(Intent(Settings.ACTION_SETTINGS)) }
    }

    override fun bridgeChanged(on: Boolean) {
        if (on) BridgeScheduler.schedule(this) else BridgeScheduler.cancel(this)
    }

    override val batteryUnrestricted: Boolean
        get() = (getSystemService(POWER_SERVICE) as android.os.PowerManager).isIgnoringBatteryOptimizations(packageName)

    /**
     * The system's one-tap "allow to run in background" prompt; if a phone hides
     * it, the app's own page, where One UI keeps 배터리 → 제한 없음.
     */
    @android.annotation.SuppressLint("BatteryLife")
    override fun askBatteryUnrestricted() {
        // Already exempt: Samsung's 잠자는 앱 list lives on the app's own battery page.
        if (batteryUnrestricted) { openSettings(); return }
        runCatching { startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))) }
            .onFailure { openSettings() }
    }

    override fun openSettings() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
    }

    companion object {
        const val ACTION_OPEN = "app.olo.calendar.OPEN"
        const val EXTRA_KIND = "olo.kind"
        const val EXTRA_DAY = "olo.day"
        const val EXTRA_EVENT = "olo.event"
        const val EXTRA_BEGIN = "olo.begin"
    }
}
