package io.github.pretendgineering.famlogic

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/** Where a pull-up sheet rests: nearly full height, half way, peeking (a person or two), or tucked away (just its handle and buttons). */
enum class SheetStop { Full, Half, Peek, Tucked }

/**
 * A sheet that sits over the map and pulls up: drag its handle (or tap it), or
 * keep scrolling the list inside it; it rests peeking, half way or full. Scrolling
 * the list only scrolls the list once the sheet is all the way up, and pulling
 * down at the top of the list brings the sheet down, as in a maps app.
 *
 * [peekExtra] is how much of the list shows while peeking, under the [header];
 * [footer] stays pinned at the bottom. [onCovered] reports how many pixels of the
 * map it covers, so the map can keep people clear of it.
 */
@Composable
fun PullUpSheet(
    peekExtra: Dp,
    onCovered: (Int) -> Unit = {},
    header: @Composable ColumnScope.() -> Unit,
    footer: @Composable ColumnScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val density = LocalDensity.current
        val scope = rememberCoroutineScope()
        val h = constraints.maxHeight.toFloat()
        var headerH by remember { mutableIntStateOf(0) }
        var footerH by remember { mutableIntStateOf(0) }
        val extra = with(density) { peekExtra.toPx() }
        val full = h * 0.1f
        // Tucked away: only the handle, the buttons and the SOS bar; the map gets the rest.
        val tucked = (h - headerH - footerH).coerceIn(h * 0.5f, h - with(density) { 56.dp.toPx() })
        val peek = (tucked - extra).coerceIn(h * 0.5f, tucked)
        val half = (h * 0.5f).coerceIn(full, peek)
        val anchors = listOf(full, half, peek, tucked)
        var offset by remember { mutableFloatStateOf(Float.NaN) }
        var stop by remember { mutableIntStateOf(SheetStop.Peek.ordinal) }
        // Keep resting at the same stop when the sizes change (rotation, header measured).
        if (offset.isNaN() || (headerH > 0 && abs(offset - anchors[stop]) > 1f && !moving.value)) offset = anchors[stop]
        androidx.compose.runtime.SideEffect { onCovered((h - offset).roundToInt()) }

        fun settle(velocity: Float) {
            val target = when {
                velocity < -900f -> anchors.filter { it < offset - 1f }.maxOrNull() ?: full
                velocity > 900f -> anchors.filter { it > offset + 1f }.minOrNull() ?: tucked
                else -> anchors.minBy { abs(it - offset) }
            }
            stop = anchors.indexOf(target)
            moving.value = true
            scope.launch {
                animate(offset, target, velocity, spring(dampingRatio = .9f, stiffness = 400f)) { v, _ -> offset = v }
                moving.value = false
            }
        }
        fun dragBy(dy: Float): Float {
            val new = (offset + dy).coerceIn(full, tucked)
            val used = new - offset
            offset = new
            moving.value = true
            return used
        }
        val nested = remember(full, tucked) {
            object : NestedScrollConnection {
                // Pushing up: raise the sheet first, then scroll the list.
                override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset =
                    if (available.y < 0 && offset > full + .5f) Offset(0f, dragBy(available.y)) else Offset.Zero

                // Pulling down at the top of the list: lower the sheet.
                override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset =
                    if (available.y > 0 && source == NestedScrollSource.UserInput) Offset(0f, dragBy(available.y)) else Offset.Zero

                override suspend fun onPreFling(available: Velocity): Velocity {
                    if (offset > full + .5f && anchors.none { abs(it - offset) < 1f }) {
                        settle(available.y)
                        return available
                    }
                    return Velocity.Zero
                }

                override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                    if (anchors.none { abs(it - offset) < 1f }) settle(available.y)
                    return Velocity.Zero
                }
            }
        }
        val drag = rememberDraggableState { dy -> dragBy(dy) }

        Surface(
            modifier = Modifier.offset { IntOffset(0, offset.roundToInt()) }
                .fillMaxWidth()
                .height(with(density) { (h - offset).coerceAtLeast(0f).toDp() })
                .nestedScroll(nested),
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
            shadowElevation = 12.dp,
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column {
                Column(
                    Modifier.fillMaxWidth()
                        .onSizeChanged { headerH = it.height }
                        .draggable(drag, Orientation.Vertical, onDragStopped = { v -> settle(v) }),
                ) {
                    // The handle: drag it, or tap to step up (and back down from the top).
                    Box(
                        Modifier.fillMaxWidth().clickable(onClickLabel = "Show more or less") {
                            // Tap: one step up each time, and from the top all the way back down.
                            val next = if (stop == SheetStop.Full.ordinal) SheetStop.Tucked.ordinal else stop - 1
                            stop = next
                            moving.value = true
                            scope.launch {
                                animate(offset, anchors[next], 0f, spring(dampingRatio = .9f, stiffness = 400f)) { v, _ -> offset = v }
                                moving.value = false
                            }
                        }.padding(vertical = 10.dp).semantics { contentDescription = "Drag up for everyone" },
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(Modifier.width(40.dp).height(5.dp).clip(RoundedCornerShape(3.dp)).background(MaterialTheme.colorScheme.outline.copy(alpha = .5f)))
                    }
                    header()
                }
                Box(Modifier.weight(1f).fillMaxWidth()) { Column { content() } }
                Column(Modifier.fillMaxWidth().onSizeChanged { footerH = it.height }) { footer() }
            }
        }
    }
}

/** Set while the sheet is being dragged or is settling (so a resize doesn't snap it mid-move). */
private val moving = androidx.compose.runtime.mutableStateOf(false)
