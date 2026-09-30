package io.github.matiyaaa.fuse.ui.designsystem.focus

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.collectLatest

private val followSpec: AnimationSpec<Float> = spring(dampingRatio = 1f, stiffness = 600f)

/**
 * Keeps [index] at a steady anchor inside a lazy row/column. Each new selection starts a new
 * animation from wherever the list currently is, so holding a direction glides instead of stepping,
 * and an item that isn't laid out yet is jumped to without animating through hundreds of rows.
 *
 * [anchor] is where the selected item's leading edge should sit, as a fraction of the viewport.
 */
suspend fun LazyListState.follow(index: Int, anchor: Float = 0.12f, animate: Boolean = true) {
    val info = layoutInfo
    val viewport = info.viewportEndOffset - info.viewportStartOffset
    val item = info.visibleItemsInfo.firstOrNull { it.index == index }
    if (item == null) {
        val offset = -(viewport * anchor).toInt()
        scrollToItem(index, offset.coerceAtMost(0))
        return
    }
    val target = (viewport * anchor).toInt()
    val delta = (item.offset - target).toFloat()
    if (delta == 0f) return
    if (animate) animateScrollBy(delta, followSpec) else scrollBy(delta)
}

/** Grid version: keeps the selected row near [anchor] of the viewport height. */
suspend fun LazyGridState.follow(index: Int, anchor: Float = 0.2f, animate: Boolean = true) {
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
    if (animate) animateScrollBy(delta, followSpec) else scrollBy(delta)
}

/** Follows [selected] whenever it changes. */
@Composable
fun FollowSelection(state: LazyListState, selected: () -> Int, anchor: Float = 0.12f, animate: Boolean = true) {
    LaunchedEffect(state) {
        snapshotFlow(selected).collectLatest { state.follow(it, anchor, animate) }
    }
}

@Composable
fun FollowSelection(state: LazyGridState, selected: () -> Int, anchor: Float = 0.2f, animate: Boolean = true) {
    LaunchedEffect(state) {
        snapshotFlow(selected).collectLatest { state.follow(it, anchor, animate) }
    }
}
