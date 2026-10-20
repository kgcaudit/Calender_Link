package app.olo.calendar.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.olo.calendar.design.OloTheme
import app.olo.calendar.model.MemoryPreferences
import app.olo.calendar.model.Preferences
import app.olo.calendar.store.CalendarStore
import kotlinx.coroutines.flow.StateFlow

/** The platform's calendar-permission flow (Android runtime permission / iOS full access). */
interface PermissionGate {
    val granted: StateFlow<Boolean>
    /** True once the user has refused in a way only system settings can undo. */
    val deniedForGood: StateFlow<Boolean>
    fun request()
    fun openSettings()
}

/** System screens the app can send people to. */
interface SystemSettings {
    /** The phone's account-sync settings, where Google calendar sync is switched on. */
    fun openSyncSettings()

    /**
     * Asks the person, through the system account chooser, to let OLO see a
     * Google account (needed before its sync switch can be read or set).
     */
    fun allowAccount(account: String, done: (Boolean) -> Unit) { done(false) }

    /** That one account's sync page (calendar switch), else the general sync settings. */
    fun openAccountSync(account: String) { openSyncSettings() }

    /** The bridge was switched on or off: the platform (re)schedules its background passes. */
    fun bridgeChanged(on: Boolean) {}

    /** Whether the phone lets OLO's background passes run unhindered (battery optimisation off). */
    val batteryUnrestricted: Boolean get() = true

    /**
     * Asks to exempt OLO from battery optimisation, so the bridge keeps running
     * while closed (docs/07 §18); already exempt, opens the app's own settings page.
     */
    fun askBatteryUnrestricted() {}
}

/** Where to land when OLO is opened from outside (a widget tap, docs/09 §6). */
sealed interface OpenRequest {
    data class Day(val date: kotlinx.datetime.LocalDate) : OpenRequest
    data class Event(val eventId: String, val beginMillis: Long) : OpenRequest
    data class NewEvent(val date: kotlinx.datetime.LocalDate) : OpenRequest
    data object Bridge : OpenRequest
}

@Composable
fun App(
    store: CalendarStore,
    permissions: PermissionGate,
    settings: SystemSettings? = null,
    use24Hour: Boolean = true,
    prefs: Preferences = MemoryPreferences(),
    /** The latest outside request; [onOpened] clears it once handled. */
    openRequest: StateFlow<OpenRequest?>? = null,
    onOpened: () -> Unit = {},
    /** Events or what is shown changed: the platform refreshes its widgets. */
    onChanged: () -> Unit = {},
) {
    OloTheme {
      androidx.compose.runtime.CompositionLocalProvider(LocalUse24Hour provides use24Hour) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            val granted by permissions.granted.collectAsState()
            if (granted) {
                val scope = rememberCoroutineScope()
                val controller = remember(store) { CalendarController(store, scope, prefs = prefs, onChanged = onChanged) }
                // Leaving OLO sends a deletion still waiting for "실행 취소" (docs/10 §3).
                androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_STOP) { controller.flushPendingDelete() }
                val request = openRequest?.collectAsState()?.value
                CalendarScreen(controller, settings, request = request, onRequestHandled = onOpened)
            } else {
                val forGood by permissions.deniedForGood.collectAsState()
                PermissionScreen(forGood, permissions::request, permissions::openSettings)
            }
        }
      }
    }
}

@Composable
private fun PermissionScreen(deniedForGood: Boolean, onRequest: () -> Unit, onSettings: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("캘린더 접근 권한이 필요합니다", style = MaterialTheme.typography.headlineSmall)
        Text(
            "OLO Calendar는 휴대폰 캘린더(삼성 캘린더와 같은 저장소)를 읽고 씁니다. " +
                "일정은 OLO 서버로 보내지 않으며, Google 동기화는 휴대폰이 그대로 처리합니다.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.widthIn(max = 420.dp),
        )
        if (deniedForGood) {
            Text(
                "권한이 꺼져 있습니다. 설정 > 애플리케이션 > OLO Calendar > 권한에서 캘린더를 허용해 주세요.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.widthIn(max = 420.dp),
            )
            OloButton("설정 열기", onSettings)
        } else {
            OloButton("권한 허용", onRequest)
        }
    }
}
