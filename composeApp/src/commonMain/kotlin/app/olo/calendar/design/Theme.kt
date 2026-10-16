package app.olo.calendar.design

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * OLO family tokens, copied verbatim from kgcaudit/OLO-Design (tokens/color.md,
 * tokens/type-shape.md), whose seed is OLO Explorer's shipping Theme.kt.
 * Do not change values here: a change goes to OLO-Design first, then flows out.
 * No Material-You dynamic colour, and every scheme role is set so Material never
 * fills a gap from its purple baseline.
 */

private val Clay = Color(0xFFB95B3B)
private val ClayLight = Color(0xFFE8A183)
private val Stone = Color(0xFF6E5C50)
private val StoneLight = Color(0xFFD6C3B4)

internal val LightColors = lightColorScheme(
    primary = Clay,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFF6E0D6),
    onPrimaryContainer = Color(0xFF4A1E0C),
    inversePrimary = ClayLight,

    secondary = Stone,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFEFE6DE),
    onSecondaryContainer = Color(0xFF2A211B),
    tertiary = Color(0xFF3E7F80),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFCDE5E4),
    onTertiaryContainer = Color(0xFF0A2C2C),
    background = Color(0xFFF7F4EF),
    onBackground = Color(0xFF1D1A16),
    surface = Color(0xFFF7F4EF),
    onSurface = Color(0xFF1D1A16),
    surfaceVariant = Color(0xFFECE5DC),
    onSurfaceVariant = Color(0xFF574D45),
    surfaceTint = Clay,
    surfaceBright = Color(0xFFFBF9F5),
    surfaceDim = Color(0xFFDFD8CC),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFCFAF6),
    surfaceContainer = Color(0xFFF3EFE8),
    surfaceContainerHigh = Color(0xFFEDE8DF),
    surfaceContainerHighest = Color(0xFFE7E1D6),

    inverseSurface = Color(0xFF35302A),
    inverseOnSurface = Color(0xFFF5F1EA),

    outline = Color(0xFF8B7F74),
    outlineVariant = Color(0xFFD6CCC1),
    scrim = Color(0xFF000000),
    error = Color(0xFFA50E2E),
    onError = Color.White,
    errorContainer = Color(0xFFF8DCDA),
    onErrorContainer = Color(0xFF3B100B),
)

internal val DarkColors = darkColorScheme(
    primary = ClayLight,
    onPrimary = Color(0xFF4A1E0C),
    primaryContainer = Color(0xFF8A3E22),
    onPrimaryContainer = Color(0xFFFBE0D4),
    inversePrimary = Clay,

    secondary = StoneLight,
    onSecondary = Color(0xFF2A211B),
    secondaryContainer = Color(0xFF52443A),
    onSecondaryContainer = Color(0xFFEFE0D4),

    tertiary = Color(0xFF86CFCF),
    onTertiary = Color(0xFF08292A),
    tertiaryContainer = Color(0xFF2A5455),
    onTertiaryContainer = Color(0xFFCDE5E4),

    background = Color(0xFF181613),
    onBackground = Color(0xFFE8E3DA),
    surface = Color(0xFF181613),
    onSurface = Color(0xFFE8E3DA),
    surfaceVariant = Color(0xFF49423A),
    onSurfaceVariant = Color(0xFFCFC5B9),
    surfaceTint = ClayLight,

    surfaceBright = Color(0xFF3D362E),
    surfaceDim = Color(0xFF141210),
    surfaceContainerLowest = Color(0xFF0F0E0B),
    surfaceContainerLow = Color(0xFF1C1A16),
    surfaceContainer = Color(0xFF211E1A),
    surfaceContainerHigh = Color(0xFF2B2723),
    surfaceContainerHighest = Color(0xFF363029),

    inverseSurface = Color(0xFFE8E3DA),
    inverseOnSurface = Color(0xFF181613),

    outline = Color(0xFF978C80),
    outlineVariant = Color(0xFF49423A),
    scrim = Color(0xFF000000),

    error = Color(0xFFFFB0BE),
    onError = Color(0xFF5E001C),
    errorContainer = Color(0xFF7E2A24),
    onErrorContainer = Color(0xFFFFDAD3),
)

data class TileColors(
    val folder: Color,
    val archive: Color,
    val image: Color,
    val video: Color,
    val audio: Color,
    val document: Color,
    val code: Color,
    val app: Color,
    val other: Color,
)

val MaterialTheme.tiles: TileColors
    @Composable
    get() = if (colorScheme.background.luminanceIsDark()) DarkTiles else LightTiles

internal val LightTiles = TileColors(
    folder = Clay,
    archive = Color(0xFF8A6A3B),
    image = Color(0xFF2E8B6B),
    video = Color(0xFF6A5A9E),
    audio = Color(0xFFB04A6A),
    document = Color(0xFF55606B),
    code = Color(0xFF3E7F80),
    app = Color(0xFF4C7A3E),
    other = Color(0xFF7A7168),
)

internal val DarkTiles = TileColors(
    folder = Color(0xFFD1734F),
    archive = Color(0xFFB08A54),
    image = Color(0xFF3FA383),
    video = Color(0xFF8A7AC0),
    audio = Color(0xFFC96B88),
    document = Color(0xFF6E7A86),
    code = Color(0xFF55A0A1),
    app = Color(0xFF69985A),
    other = Color(0xFF938A80),
)

data class StatusColors(
    val running: Color,
    val waiting: Color,
    val paused: Color,
    val done: Color,
    val failed: Color,
        val progressTrack: Color,
)

val MaterialTheme.status: StatusColors
    @Composable
    get() = if (colorScheme.background.luminanceIsDark()) DarkStatus else LightStatus

internal val LightStatus = StatusColors(
    running = Color(0xFF1273BE),
    waiting = Color(0xFF7A6F65),
    paused = Color(0xFF9C7A0C),
    done = Color(0xFF1B7F4B),
    failed = Color(0xFF9E0C2B),
    progressTrack = Color(0xFFDCD3C6),
)

internal val DarkStatus = StatusColors(
    running = Color(0xFF7FC0F5),
    waiting = Color(0xFFA79C90),
    paused = Color(0xFFE0BE52),
    done = Color(0xFF6FD79B),
    failed = Color(0xFFFFB0BE),
    progressTrack = Color(0xFF39434D),
)

internal fun Color.luminanceIsDark(): Boolean = (red * 0.299f + green * 0.587f + blue * 0.114f) < 0.5f

/** Applies the OLO palette, corners and type. Status-bar icons are set per platform. */
@Composable
fun OloTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        shapes = OloShapes,
        typography = OloTypography,
        content = content,
    )
}

/** Tight corners: 6 / 10 / 14 / 18 / 20 dp. No pills anywhere in the family. */
internal val OloShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(20.dp),
)

/** Material's type scale with only the four largest roles pulled down. */
internal val OloTypography = Typography().let { base ->
    base.copy(
        headlineLarge = base.headlineLarge.copy(fontSize = 28.sp, lineHeight = 34.sp),
        headlineMedium = base.headlineMedium.copy(fontSize = 24.sp, lineHeight = 30.sp),
        headlineSmall = base.headlineSmall.copy(fontSize = 20.sp, lineHeight = 26.sp),
        titleLarge = base.titleLarge.copy(fontSize = 19.sp, lineHeight = 25.sp),
    )
}
