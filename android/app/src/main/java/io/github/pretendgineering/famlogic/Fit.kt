package io.github.pretendgineering.famlogic

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Dp

/**
 * No more "just barely too long" lists. When a list overflows the screen by only a
 * little (up to [SQUEEZE_LIMIT] of its height), the space around its rows shrinks
 * evenly until it all fits, so it doesn't scroll. Longer lists scroll as usual, at
 * full size. Rows take their padding through [squeezed].
 */
val LocalSqueeze = compositionLocalOf { 1f }

/** A row's padding, shrunk when its list is being squeezed to fit. */
@Composable
fun squeezed(d: Dp): Dp = d * LocalSqueeze.current

private const val SQUEEZE_LIMIT = 0.15f

class Squeeze {
    var factor by mutableFloatStateOf(1f)
    var ready by mutableStateOf(false)
}

/**
 * Works out the squeeze for a list: [overflow] is how much its content is taller
 * than [viewport] in pixels (0 if it fits, null if it's far too long to judge).
 * It looks at the list at full size and at half the padding, then picks the
 * amount that just fits (row padding scales linearly, so two looks are enough).
 */
@Composable
fun rememberSqueeze(key: Any?, viewport: Int, overflow: () -> Int?): Squeeze {
    val sq = remember(key) { Squeeze() }
    LaunchedEffect(sq, viewport) {
        if (viewport <= 0) return@LaunchedEffect
        sq.ready = false
        sq.factor = 1f
        repeat(2) { withFrameNanos { } }
        val full = overflow()
        if (full != null && full > 0 && full <= viewport * SQUEEZE_LIMIT) {
            sq.factor = .5f
            repeat(2) { withFrameNanos { } }
            val half = overflow() ?: full
            val perUnit = (full - half) / .5f
            val fit = if (perUnit > 0) 1f - full / perUnit else 1f
            // Too tight to look right: scroll instead.
            sq.factor = if (fit >= .2f) (fit - .02f).coerceAtMost(1f) else 1f
        }
        sq.ready = true
    }
    return sq
}

/** A scrolling column that squeezes its rows to fit when it's only just too long. */
@Composable
fun FitScrollColumn(key: Any?, modifier: Modifier = Modifier, state: ScrollState = rememberScrollState(), content: @Composable ColumnScope.() -> Unit) {
    var viewport by remember { mutableIntStateOf(0) }
    val sq = rememberSqueeze(key, viewport) { state.maxValue }
    CompositionLocalProvider(LocalSqueeze provides sq.factor) {
        Column(
            modifier.onSizeChanged { viewport = it.height }
                // Hidden for the frame or two it takes to measure, so nothing jumps.
                .graphicsLayer { alpha = if (sq.ready) 1f else 0f }
                .verticalScroll(state),
            content = content,
        )
    }
}

/** How far a lazy list's content runs past its end (null if not all of it is laid out). */
fun LazyListState.overflowPx(): Int? {
    val info = layoutInfo
    val items = info.visibleItemsInfo
    if (items.isEmpty()) return 0
    if (items.size < info.totalItemsCount) return null
    val first = items.first()
    val last = items.last()
    if (first.index != 0) return null
    val content = (last.offset + last.size) - first.offset + info.beforeContentPadding + info.afterContentPadding
    return (content - (info.viewportEndOffset - info.viewportStartOffset)).coerceAtLeast(0)
}
