package app.olo.calendar.design

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring

/**
 * Motion tokens (docs/05 §6), after Material 3: durations grow with the
 * distance travelled; gestures settle on springs so they keep the finger's
 * speed instead of jumping. Proposed to OLO-Design with the layout tokens.
 */
object OloMotion {
    /** Colour, alpha, a small badge. */
    const val Quick = 150
    /** Selection moving, a group folding, a panel's content changing. */
    const val Short = 200
    /** Sheets, search, the quick-add note. */
    const val Medium = 250
    /** One view replacing another. */
    const val Long = 320

    val Emphasized = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    val EmphasizedAccelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
    val Standard = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    /** Page snaps and stage settles: no bounce past the target, quick to rest. */
    fun <T> gesture() = spring<T>(dampingRatio = 0.9f, stiffness = Spring.StiffnessMediumLow)
}
