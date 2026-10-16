package app.olo.calendar.ui

import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import app.olo.calendar.design.OloMotion
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlin.math.abs
import kotlin.math.sign

/*
 * Paging for the month, week and day views (docs/05 §5). The page follows the
 * finger and settles on a spring; neighbours are composed ahead and the
 * controller has already loaded their events, so a page slides in filled.
 * A page index is a plain number (months since year 0, days since the epoch,
 * …) so any date maps to a page without a lookup.
 */

private const val PAGE_COUNT = Int.MAX_VALUE / 2

/** Pages around [center]: index p shows `center + (p - PAGE_COUNT / 2)`. */
internal class DatePages(val center: Int) {
    val initial: Int get() = PAGE_COUNT / 2
    fun valueOf(page: Int): Int = center + (page - PAGE_COUNT / 2)
    fun pageOf(value: Int): Int = PAGE_COUNT / 2 + (value - center)
}

/**
 * A pager over whole numbers (a month, week or day index). [target] is where
 * the app wants to be (today button, a picked month): the pager glides there,
 * from the next-door page when the jump is long, so it never spins through
 * dozens of pages. [onSettle] reports where the user's own swipe came to rest.
 */
@Composable
internal fun DatePager(
    target: Int,
    onSettle: (Int) -> Unit,
    modifier: Modifier = Modifier,
    vertical: Boolean = false,
    key: Any? = null,
    content: @Composable (Int) -> Unit,
) {
    val pages = androidx.compose.runtime.remember(key) { DatePages(target) }
    val state = rememberPagerState(initialPage = pages.initial) { PAGE_COUNT }
    val settle = rememberUpdatedState(onSettle)
    LaunchedEffect(state, pages) {
        snapshotFlow { state.settledPage }.distinctUntilChanged().drop(1).collect { settle.value(pages.valueOf(it)) }
    }
    LaunchedEffect(target, pages) { glide(state, pages.pageOf(target)) }
    val fling = PagerDefaults.flingBehavior(state = state, snapAnimationSpec = OloMotion.gesture())
    if (vertical) {
        VerticalPager(
            state = state, modifier = modifier, beyondViewportPageCount = 1, flingBehavior = fling,
            snapPosition = SnapPosition.Start, key = { pages.valueOf(it) },
        ) { p -> content(pages.valueOf(p)) }
    } else {
        HorizontalPager(
            state = state, modifier = modifier, beyondViewportPageCount = 1, flingBehavior = fling,
            snapPosition = SnapPosition.Start, key = { pages.valueOf(it) },
        ) { p -> content(pages.valueOf(p)) }
    }
}

private suspend fun glide(state: PagerState, to: Int) {
    val from = state.currentPage
    if (from == to || state.isScrollInProgress) return
    val d = to - from
    if (abs(d) > 1) state.scrollToPage(to - d.sign)
    state.animateScrollToPage(to, animationSpec = tween(OloMotion.Long, easing = OloMotion.Emphasized))
}
