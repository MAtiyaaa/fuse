package io.github.matiyaaa.fuse.ui.designsystem.focus

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.unit.IntSize
import io.github.matiyaaa.fuse.ui.designsystem.theme.Durations
import io.github.matiyaaa.fuse.ui.designsystem.theme.Easings
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.FuseMotion
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first

private val followSpec: AnimationSpec<Float> = spring(dampingRatio = 1f, stiffness = 600f)

/**
 * How a list glides to keep up with the selection: a critically damped spring, which restarts
 * smoothly from its current speed when the selection moves again (holding a direction glides
 * instead of stepping). Under Reduced motion a short tween, so the list still moves (the selection
 * must stay in view) without a long slide.
 */
fun FuseMotion.followScroll(): AnimationSpec<Float> =
    if (reduced) tween(ms(Durations.FAST), easing = Easings.Standard) else followSpec

/**
 * Keeps [index] at a steady anchor inside a lazy row/column (anchor 0 = aligned with the content
 * padding, which is how shelves keep the selected tile at a fixed spot while the row slides). Each new selection starts a new
 * animation from wherever the list currently is, so holding a direction glides instead of stepping,
 * and an item that isn't laid out yet is jumped to without animating through hundreds of rows.
 *
 * [anchor] is where the selected item's leading edge should sit, as a fraction of the viewport.
 */
suspend fun LazyListState.follow(index: Int, anchor: Float = 0.12f, animate: Boolean = true, spec: AnimationSpec<Float> = followSpec) {
    // Before the first layout the viewport is empty, so the anchor would come out as 0 and pin the
    // item to the top, hiding the rows above it (a list opened on its fifth row). Wait for it.
    if (layoutInfo.viewportSize == IntSize.Zero) snapshotFlow { layoutInfo.viewportSize }.first { it != IntSize.Zero }
    val info = layoutInfo
    val inner = info.viewportSize.let { if (info.orientation == androidx.compose.foundation.gestures.Orientation.Horizontal) it.width else it.height } -
        info.beforeContentPadding - info.afterContentPadding
    // Item offsets are measured from the end of the leading content padding.
    val target = (inner * anchor).toInt()
    val item = info.visibleItemsInfo.firstOrNull { it.index == index }
    if (item == null) {
        scrollToItem(index, -(inner * anchor).toInt())
        return
    }
    val delta = (item.offset - target).toFloat()
    if (delta == 0f) return
    if (animate) animateScrollBy(delta, spec) else scrollBy(delta)
}

/** Grid version: keeps the selected row near [anchor] of the viewport height. */
suspend fun LazyGridState.follow(index: Int, anchor: Float = 0.2f, animate: Boolean = true, spec: AnimationSpec<Float> = followSpec) {
    // As for lists: measure only once the grid has been laid out.
    if (layoutInfo.viewportSize == IntSize.Zero) snapshotFlow { layoutInfo.viewportSize }.first { it != IntSize.Zero }
    val info = layoutInfo
    val viewport = info.viewportEndOffset - info.viewportStartOffset
    val item = info.visibleItemsInfo.firstOrNull { it.index == index }
    if (item == null) {
        scrollToItem(index, -(viewport * anchor).toInt().coerceAtMost(0))
        return
    }
    val target = (viewport * anchor).toInt()
    val delta = (item.offset.y - target).toFloat()
    // Only scroll when the row is leaving the comfortable middle band, so moving sideways never scrolls.
    val band = viewport * 0.18f
    if (kotlin.math.abs(delta) < band && item.offset.y >= 0 && item.offset.y + item.size.height <= viewport) return
    if (animate) animateScrollBy(delta, spec) else scrollBy(delta)
}

/**
 * Follows [selected] whenever it changes. The newest [selected] lambda is always used, so a caller
 * may pass a plain value captured at composition (a shelf's column) and still be followed.
 */
@Composable
fun FollowSelection(state: LazyListState, selected: () -> Int, anchor: Float = 0.12f, animate: Boolean = true, enabled: () -> Boolean = { true }) {
    val current by rememberUpdatedState(selected)
    val on by rememberUpdatedState(enabled)
    val spec by rememberUpdatedState(Fuse.motion.followScroll())
    LaunchedEffect(state) {
        // Paused while [enabled] is false (an item held by touch), then catches up.
        snapshotFlow { if (on()) current() else null }.collectLatest { if (it != null) state.follow(it, anchor, animate, spec) }
    }
}

@Composable
fun FollowSelection(state: LazyGridState, selected: () -> Int, anchor: Float = 0.2f, animate: Boolean = true, enabled: () -> Boolean = { true }) {
    val current by rememberUpdatedState(selected)
    val on by rememberUpdatedState(enabled)
    val spec by rememberUpdatedState(Fuse.motion.followScroll())
    LaunchedEffect(state) {
        snapshotFlow { if (on()) current() else null }.collectLatest { if (it != null) state.follow(it, anchor, animate, spec) }
    }
}
