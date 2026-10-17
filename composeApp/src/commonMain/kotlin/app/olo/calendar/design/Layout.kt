package app.olo.calendar.design

import androidx.compose.ui.unit.dp

/**
 * Calendar layout tokens (docs/04-화면구성-개선안.md). Proposed to OLO-Design with
 * the calendar hues; kept here until the shared universe carries them.
 */
object OloLayout {
    /** From this width: rail + two panes instead of drawer + three-stage month. */
    val WideBreakpoint = 600.dp
    /** Side margin of the month grid: tighter than Material's 16dp to keep cells wide (Samsung ≈ 11dp). */
    val GridMarginCompact = 12.dp
    val GridMarginWide = 24.dp
    /** Lists and sheets. */
    val ContentMargin = 16.dp
    val CellGap = 3.dp
    val TopBarHeight = 56.dp
    val TouchTarget = 48.dp
    val IconSize = 24.dp
    /** Wide enough for the drawer tree's three indent levels (0.9.6: was 280). */
    val DrawerWidth = 320.dp
    val RailWidth = 96.dp
    /** Week row height in the "lines" stage of the month view. */
    val LinesRow = 52.dp
    /** Room kept free at the bottom for the floating quick-add bar. */
    val QuickBarSpace = 84.dp
}
