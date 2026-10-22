package app.olo.calendar

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.glance.currentState
import app.olo.calendar.model.WidgetBackground

/** 색상 in 위젯 설정: follow the phone, or stay light or dark. */
enum class WidgetTheme(val label: String) { SYSTEM("폰 설정 따름"), LIGHT("밝게"), DARK("어둡게") }

/**
 * One widget's look, set from its own 위젯 설정 (long-press › 설정, docs/13 §8):
 * the card on or off, how opaque (0–100), and light/dark.
 */
data class WidgetStyle(val background: Boolean = true, val opacity: Int = 100, val theme: WidgetTheme = WidgetTheme.SYSTEM) {
    companion object {
        private val SET = booleanPreferencesKey("styleSet")
        private val BG = booleanPreferencesKey("styleBg")
        private val OPACITY = intPreferencesKey("styleOpacity")
        private val THEME = intPreferencesKey("styleTheme")

        /** What the app-wide 위젯 배경 meant, for a widget never set on its own. */
        fun of(b: WidgetBackground) = WidgetStyle(background = b != WidgetBackground.CLEAR, opacity = (b.alpha * 100).toInt().coerceAtLeast(10))

        fun read(p: Preferences, fallback: WidgetBackground): WidgetStyle =
            if (p[SET] != true) of(fallback)
            else WidgetStyle(
                p[BG] ?: true,
                (p[OPACITY] ?: 100).coerceIn(0, 100),
                WidgetTheme.entries.getOrElse(p[THEME] ?: 0) { WidgetTheme.SYSTEM },
            )

        fun write(p: MutablePreferences, s: WidgetStyle) {
            p[SET] = true
            p[BG] = s.background
            p[OPACITY] = s.opacity
            p[THEME] = s.theme.ordinal
        }
    }
}

internal val LocalWidgetStyle = staticCompositionLocalOf { WidgetStyle() }

/** Gives [content] this widget's own style (or the app-wide one it has not overridden). */
@Composable
internal fun Styled(fallback: WidgetBackground, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalWidgetStyle provides WidgetStyle.read(currentState<Preferences>(), fallback), content = content)
}

/** Which of OLO's widgets an app-widget id belongs to, for 위젯 설정's preview. */
internal enum class WidgetKind { AGENDA, MONTH, NEXT }

internal fun kindOf(context: Context, appWidgetId: Int): WidgetKind? =
    when (android.appwidget.AppWidgetManager.getInstance(context).getAppWidgetInfo(appWidgetId)?.provider?.className) {
        AgendaWidgetReceiver::class.java.name -> WidgetKind.AGENDA
        MonthWidgetReceiver::class.java.name -> WidgetKind.MONTH
        NextWidgetReceiver::class.java.name -> WidgetKind.NEXT
        else -> null
    }
