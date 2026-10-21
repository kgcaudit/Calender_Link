package app.olo.calendar

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import app.olo.calendar.store.BridgeReport
import app.olo.calendar.store.bridgeAlert
import app.olo.calendar.ui.OpenRequest

/**
 * The bridge's phone notification (docs/12 §3): raised by a background pass that
 * needs the person (deletions held, writes failed), once per problem; tapping it
 * opens the bridge sheet. Cleared when a pass finds nothing left.
 */
object BridgeAlerts {
    private const val CHANNEL = "olo.bridge"
    private const val ID = 7001
    private const val KEY_LAST = "bridge.alerted"

    fun afterPass(context: Context, report: BridgeReport, enabled: Boolean) {
        val prefs = AndroidPreferences(context)
        val nm = context.getSystemService(NotificationManager::class.java)
        val alert = bridgeAlert(report)
        if (alert == null) {
            if (prefs.get(KEY_LAST) != null) { nm.cancel(ID); prefs.put(KEY_LAST, null) }
            return
        }
        val (title, text, signature) = alert
        if (!enabled || signature == prefs.get(KEY_LAST)) return
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "연동 알림", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "삼성 캘린더와 Google 메인 캘린더 연동에 확인할 일이 생기면 알립니다"
            })
        }
        val tap = PendingIntent.getActivity(
            context, ID, openIntent(context, OpenRequest.Bridge),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(title)
            .setContentText(text.substringBefore('\n'))
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(tap)
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(ID, n) }.onSuccess { prefs.put(KEY_LAST, signature) }
    }
}
