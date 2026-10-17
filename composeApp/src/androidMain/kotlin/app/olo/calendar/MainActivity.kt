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
        setContent { App(store, this, this, use24Hour = android.text.format.DateFormat.is24HourFormat(this), prefs = prefs) }
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

    override fun openSyncSettings() {
        runCatching { startActivity(Intent(Settings.ACTION_SYNC_SETTINGS)) }
            .onFailure { startActivity(Intent(Settings.ACTION_SETTINGS)) }
    }

    override fun bridgeChanged(on: Boolean) {
        if (on) BridgeScheduler.schedule(this) else BridgeScheduler.cancel(this)
    }

    override fun openSettings() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
    }
}
