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
enum class OloHue(val label: String) {
    FOLDER("클레이"), ARCHIVE("호박"), IMAGE("초록"), VIDEO("보라"), AUDIO("장미"), DOCUMENT("슬레이트"), CODE("청록"), APP("올리브"),
    /** Calendar-only: Google's and Samsung's default blues (docs/06 §4). Proposed to OLO-Design. */
    SKY("하늘"),
    OTHER("회색"),
}

/** A colour of each hue as an ARGB, for calendars recoloured inside OLO (the store keeps its own). */
val OloHue.argb: Int
    get() = when (this) {
        OloHue.FOLDER -> 0xFFB95B3B.toInt()
        OloHue.ARCHIVE -> 0xFF8A6A3B.toInt()
        OloHue.IMAGE -> 0xFF2E8B6B.toInt()
        OloHue.VIDEO -> 0xFF6A5A9E.toInt()
        OloHue.AUDIO -> 0xFFB04A6A.toInt()
        OloHue.DOCUMENT -> 0xFF55606B.toInt()
        OloHue.CODE -> 0xFF3E7F80.toInt()
        OloHue.APP -> 0xFF4C7A3E.toInt()
        OloHue.SKY -> 0xFF3D74B8.toInt()
        OloHue.OTHER -> 0xFF8B7F74.toInt()
    }

private val SkyLight = Color(0xFF3D74B8)
private val SkyDark = Color(0xFF8CB4E8)

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
    HueRef(OloHue.SKY, 212f),
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
    val near = References.minBy { circular(it.degrees, h) }.hue
    // A greyed blue stays slate; a clear blue (Google #039BE5, Samsung #7FB3E8) is sky, not grey.
    return if (near == OloHue.SKY && (mx - mn) / mx < 0.3f) OloHue.DOCUMENT else near
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
        OloHue.SKY -> if (MaterialTheme.colorScheme.background.luminanceIsDark()) SkyDark else SkyLight
        OloHue.OTHER -> t.other
    }
}

@Composable
fun calendarColor(argb: Int): Color = oloHueFor(argb).color()

/*
 * Calendar-only tokens, proposed to OLO-Design (not yet in the shared universe):
 * - Saturday is blue, Sunday and holidays use the family's crimson `error`, as
 *   Korean calendars do. The blue sits on the cool side of the wheel, away from
 *   the clay brand, and clears WCAG AA on both grounds (5.4–5.7:1 on the ivory surfaces, 8.4:1+ on
 *   the dark surface).
 * - An all-day bar is its tile hue laid softly over the surface, with ordinary
 *   text on top. A month of solid tiles reads heavy; the hue still tells
 *   calendars apart, which is the family rule.
 */
private val SaturdayLight = Color(0xFF1F63B8)
private val SaturdayDark = Color(0xFF8AB8F0)

@Composable
fun saturdayColor(): Color = if (MaterialTheme.colorScheme.background.luminanceIsDark()) SaturdayDark else SaturdayLight

@Composable
fun softFill(hue: Color): Color {
    val cs = MaterialTheme.colorScheme
    val dark = cs.background.luminanceIsDark()
    return androidx.compose.ui.graphics.lerp(cs.surfaceContainerLowest, hue, if (dark) 0.42f else 0.22f)
}
