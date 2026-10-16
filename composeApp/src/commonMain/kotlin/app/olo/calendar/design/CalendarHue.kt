package app.olo.calendar.design

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The OLO tile hues a calendar can wear. OLO tells kinds apart by hue, not
 * lightness, so a calendar's own colour (picked in Google / Samsung / iPhone)
 * is shown as the nearest family hue. The stored colour is never changed:
 * the other calendar apps keep showing their own.
 *
 * Proposed for OLO-Design as the calendar mapping; values are the shipping
 * `MaterialTheme.tiles`.
 */
enum class OloHue { FOLDER, ARCHIVE, IMAGE, VIDEO, AUDIO, DOCUMENT, CODE, APP, OTHER }

private data class HueRef(val hue: OloHue, val degrees: Float)

// Hue angles of the light tile colours (see LightTiles).
private val References = listOf(
    HueRef(OloHue.FOLDER, hueOf(0xFFB95B3B.toInt())),
    HueRef(OloHue.ARCHIVE, hueOf(0xFF8A6A3B.toInt())),
    HueRef(OloHue.IMAGE, hueOf(0xFF2E8B6B.toInt())),
    HueRef(OloHue.VIDEO, hueOf(0xFF6A5A9E.toInt())),
    HueRef(OloHue.AUDIO, hueOf(0xFFB04A6A.toInt())),
    HueRef(OloHue.CODE, hueOf(0xFF3E7F80.toInt())),
    HueRef(OloHue.APP, hueOf(0xFF4C7A3E.toInt())),
    // Google/Samsung blues have no tile of their own; slate is the cool family member.
    HueRef(OloHue.DOCUMENT, 215f),
)

/** Nearest OLO hue for an ARGB colour; greys and near-greys become [OloHue.OTHER]. */
fun oloHueFor(argb: Int): OloHue {
    val r = ((argb shr 16) and 0xFF) / 255f
    val g = ((argb shr 8) and 0xFF) / 255f
    val b = (argb and 0xFF) / 255f
    val mx = max(r, max(g, b))
    val mn = min(r, min(g, b))
    if (mx - mn < 0.12f) return OloHue.OTHER
    val h = hueOf(argb)
    return References.minBy { circular(it.degrees, h) }.hue
}

fun hueOf(argb: Int): Float {
    val r = ((argb shr 16) and 0xFF) / 255f
    val g = ((argb shr 8) and 0xFF) / 255f
    val b = (argb and 0xFF) / 255f
    val mx = max(r, max(g, b))
    val mn = min(r, min(g, b))
    val d = mx - mn
    if (d == 0f) return 0f
    val h = when (mx) {
        r -> 60f * (((g - b) / d) % 6f)
        g -> 60f * (((b - r) / d) + 2f)
        else -> 60f * (((r - g) / d) + 4f)
    }
    return (h + 360f) % 360f
}

private fun circular(a: Float, b: Float): Float {
    val d = abs(a - b) % 360f
    return if (d > 180f) 360f - d else d
}

@Composable
fun OloHue.color(): Color {
    val t = MaterialTheme.tiles
    return when (this) {
        OloHue.FOLDER -> t.folder
        OloHue.ARCHIVE -> t.archive
        OloHue.IMAGE -> t.image
        OloHue.VIDEO -> t.video
        OloHue.AUDIO -> t.audio
        OloHue.DOCUMENT -> t.document
        OloHue.CODE -> t.code
        OloHue.APP -> t.app
        OloHue.OTHER -> t.other
    }
}

@Composable
fun calendarColor(argb: Int): Color = oloHueFor(argb).color()
