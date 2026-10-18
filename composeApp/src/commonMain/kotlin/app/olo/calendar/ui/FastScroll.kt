package app.olo.calendar.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.olo.calendar.design.OloMotion
import app.olo.calendar.design.OloTone
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * A scrollbar a finger can hold (docs/04 §검색), for lists longer than a few
 * screens: a clay thumb on the right edge that shows while the list moves and
 * fades a moment after. Drag it, or touch anywhere along the edge, to jump;
 * while held, a bubble names where you are ([label] of the top item, e.g. "2023년 8월").
 */
@Composable
fun FastScroller(list: LazyListState, label: (Int) -> String?, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    // Counts, not the layout info itself (a new object every frame), so this recomposes only when they change.
    val total by remember { derivedStateOf { list.layoutInfo.totalItemsCount } }
    val visible by remember { derivedStateOf { list.layoutInfo.visibleItemsInfo.size.coerceAtLeast(1) } }
    var held by remember { mutableStateOf(false) }
    var awake by remember { mutableStateOf(false) }
    LaunchedEffect(list.isScrollInProgress, held) {
        if (list.isScrollInProgress || held) awake = true
        else { delay(1500); awake = false }
    }
    val shown by animateFloatAsState(if (awake) 1f else 0f, tween(if (awake) OloMotion.Quick else OloMotion.Medium))
    val fraction by remember {
        derivedStateOf {
            val last = (list.layoutInfo.totalItemsCount - list.layoutInfo.visibleItemsInfo.size).coerceAtLeast(1)
            (list.firstVisibleItemIndex.toFloat() / last).coerceIn(0f, 1f)
        }
    }
    // Only worth it when the list runs well past one screen.
    if (total <= visible * 3) return
    // Laid over the whole list so the bubble has room; only the right edge takes touches.
    BoxWithConstraints(modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val thumbH = 44.dp
        val trackPx = with(density) { (maxHeight - thumbH).toPx() }.coerceAtLeast(1f)
        // Read fresh on each move: the visible count changes as the list jumps, and
        // restarting the gesture on that would drop the finger mid-drag.
        val jump by rememberUpdatedState { y: Float ->
            val f = ((y - with(density) { thumbH.toPx() } / 2) / trackPx).coerceIn(0f, 1f)
            val target = (f * (total - visible).coerceAtLeast(0)).roundToInt()
            scope.launch { list.scrollToItem(target) }
            Unit
        }
        // The edge takes touches only while the bar shows; hidden, taps reach the rows under it.
        Box(
            Modifier.align(Alignment.TopEnd).fillMaxHeight().width(if (awake) 36.dp else 12.dp)
                .pointerInput(Unit) {
                    // One loop for touch and drag: down jumps, every move follows, up lets go.
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        down.consume()
                        held = true
                        try {
                            jump(down.position.y)
                            do {
                                val e = awaitPointerEvent()
                                e.changes.forEach { if (it.pressed) { it.consume(); jump(it.position.y) } }
                            } while (e.changes.any { it.pressed })
                        } finally {
                            held = false
                        }
                    }
                }
                .semantics { contentDescription = "목록 빠르게 이동" },
        ) {
            // The thumb: a slim clay bar, wider while held. Its place is read at layout, not in composition.
            Box(
                Modifier.align(Alignment.TopEnd).offset { IntOffset(0, (fraction * trackPx).roundToInt()) }.padding(end = 4.dp)
                    .alpha(shown)
                    .size(width = if (held) 8.dp else 5.dp, height = thumbH)
                    .background(if (held) OloTone.solid else OloTone.solid.copy(alpha = 0.6f), RoundedCornerShape(4.dp)),
            )
        }
        // Where the thumb is: a bubble to its left while held.
        AnimatedVisibility(
            visible = held,
            enter = fadeIn(tween(OloMotion.Quick)),
            exit = fadeOut(tween(OloMotion.Short)),
            modifier = Modifier.align(Alignment.TopEnd),
        ) {
            val text = label(list.firstVisibleItemIndex)
            if (text != null) {
                Box(Modifier.width(maxWidth).height(maxHeight)) {
                    Box(
                        Modifier.align(Alignment.TopEnd)
                            .offset { IntOffset(-with(density) { 40.dp.roundToPx() }, (fraction * trackPx).roundToInt()) }
                            .height(thumbH)
                            .background(OloTone.solid, RoundedCornerShape(8.dp))
                            .padding(horizontal = 14.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = OloTone.onSolid(OloTone.solid))
                    }
                }
            }
        }
    }
}
