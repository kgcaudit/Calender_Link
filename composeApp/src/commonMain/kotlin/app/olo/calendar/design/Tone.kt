package app.olo.calendar.design

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

/**
 * One hue, several voices (docs/06 §4), as OLO Explorer uses clay:
 * - [solid]: the filled tile — today, the day's date tile, the + button.
 * - [band]: a tinted strip that marks a zone — top bar and weekday header, month headers.
 * - [container]: a soft fill under something chosen or current — today's cell, other date tiles.
 * - [glyph]: the pale mark printed on a solid tile.
 * - [accent]: clay as text — the year, the lunar line, counts.
 * Proposed to OLO-Design with the motion and layout tokens.
 */
object OloTone {
    val solid: Color @Composable get() = MaterialTheme.colorScheme.primary
    val accent: Color @Composable get() = MaterialTheme.colorScheme.primary
    val container: Color @Composable get() = MaterialTheme.colorScheme.primaryContainer.let {
        if (MaterialTheme.colorScheme.background.luminanceIsDark()) lerp(MaterialTheme.colorScheme.background, MaterialTheme.colorScheme.primary, 0.22f) else it
    }
    val band: Color @Composable get() = lerp(MaterialTheme.colorScheme.background, MaterialTheme.colorScheme.primary, if (dark) 0.12f else 0.075f)
    val bandLine: Color @Composable get() = lerp(MaterialTheme.colorScheme.background, MaterialTheme.colorScheme.primary, if (dark) 0.22f else 0.15f)

    /** The pale mark on a solid tile of [hue]. */
    @Composable
    fun glyph(hue: Color): Color = lerp(hue, Color.White, if (dark) 0.15f else 0.72f)

    /** Text and marks on a solid tile of [hue]. */
    @Composable
    fun onSolid(hue: Color): Color = if (dark) Color(0xFF1A1714) else Color.White

    private val dark: Boolean @Composable get() = MaterialTheme.colorScheme.background.luminanceIsDark()
}

/** Whether a background is a dark one (dark theme), for tokens outside this package. */
fun Color.luminanceIsDarkPublic(): Boolean = luminanceIsDark()
