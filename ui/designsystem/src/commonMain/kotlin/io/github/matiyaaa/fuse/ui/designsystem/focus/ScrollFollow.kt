package io.github.matiyaaa.fuse.ui.designsystem.focus

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.unit.IntSize
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.fuseline.Curves
import io.github.matiyaaa.fuse.ui.fuseline.Durations
import io.github.matiyaaa.fuse.ui.fuseline.Motion
import io.github.matiyaaa.fuse.ui.fuseline.animate
import io.github.matiyaaa.fuse.ui.fuseline.fuselineScrollBy
import io.github.matiyaaa.fuse.ui.fuseline.spring
import io.github.matiyaaa.fuse.ui.fuseline.tween
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** A list following its selection, without a motion profile to hand: the same spring as [FuselineMotion.followScroll]. */
private val followSpec: Motion = spring(dampingRatio = 1f, stiffness = 600f)

/**
 * Keeps [index] at a steady anchor inside a lazy row/column (anchor 0 = aligned with the content
 * padding, which is how shelves keep the selected tile at a fixed spot while the row slides). Each new selection starts a new
 * animation from wherever the list currently is, so holding a direction glides instead of stepping,
 * and an item that isn't laid out yet is jumped to without animating through hundreds of rows.
 *
 * [anchor] is where the selected item's leading edge should sit, as a fraction of the viewport.
 */
suspend fun LazyListState.follow(index: Int, anchor: Float = 0.12f, animate: Boolean = true, spec: Motion = followSpec) {
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
    if (animate) fuselineScrollBy(delta, spec) else scrollBy(delta)
}

/**
 * Scrolls only as far as it takes to show [index] whole, with [marginPx] to spare, the way a menu
 * or a list of settings is expected to scroll: moving inside what is already on screen moves
 * nothing, and the list steps along as the selection reaches its edge. An item that isn't laid out
 * yet is jumped to.
 */
suspend fun LazyListState.keepInView(index: Int, marginPx: Int, animate: Boolean = true, spec: Motion = followSpec) {
    if (layoutInfo.viewportSize == IntSize.Zero) snapshotFlow { layoutInfo.viewportSize }.first { it != IntSize.Zero }
    val info = layoutInfo
    val start = info.viewportStartOffset
    val end = info.viewportEndOffset
    val item = info.visibleItemsInfo.firstOrNull { it.index == index }
    if (item == null) {
        scrollToItem(index, -marginPx)
        return
    }
    val top = item.offset - start
    val bottom = item.offset + item.size - end
    val delta = when {
        top < marginPx -> (top - marginPx).toFloat()
        bottom > -marginPx -> (bottom + marginPx).toFloat().coerceAtMost((top - marginPx).toFloat().coerceAtLeast(0f))
        else -> 0f
    }
    if (delta == 0f) return
    if (animate) fuselineScrollBy(delta, spec) else scrollBy(delta)
}

/** Grid version: keeps the selected row near [anchor] of the viewport height. */
suspend fun LazyGridState.follow(
    index: Int,
    anchor: Float = 0.2f,
    animate: Boolean = true,
    spec: Motion = followSpec,
    /** Pixels at the top that don't count as in view (a fading edge): the row is kept below them. */
    inset: Int = 0,
) {
    // As for lists: measure only once the grid has been laid out.
    if (layoutInfo.viewportSize == IntSize.Zero) snapshotFlow { layoutInfo.viewportSize }.first { it != IntSize.Zero }
    val info = layoutInfo
    val viewport = info.viewportEndOffset - info.viewportStartOffset
    val item = info.visibleItemsInfo.firstOrNull { it.index == index }
    if (item == null) {
        scrollToItem(index, -maxOf((viewport * anchor).toInt(), inset).coerceAtMost(0))
        return
    }
    val target = maxOf((viewport * anchor).toInt(), inset)
    val delta = (item.offset.y - target).toFloat()
    // Only scroll when the row is leaving the comfortable middle band, so moving sideways never scrolls.
    val band = viewport * 0.18f
    if (kotlin.math.abs(delta) < band && item.offset.y >= inset && item.offset.y + item.size.height <= viewport) return
    if (animate) fuselineScrollBy(delta, spec) else scrollBy(delta)
}

/**
 * Follows [selected] whenever it changes. The newest [selected] lambda is always used, so a caller
 * may pass a plain value captured at composition (a shelf's column) and still be followed.
 *
 * A selection can change in the same moment as the items do (a shelf moved down one place, rows
 * arriving): each move is measured only once the list has laid that change out, so the list never
 * follows the place an item used to be. While it waits, the glide already under way carries on, so
 * holding a direction stays one smooth run.
 */
@Composable
fun FollowSelection(state: LazyListState, selected: () -> Int, anchor: Float = 0.12f, animate: Boolean = true, enabled: () -> Boolean = { true }) {
    val current by rememberUpdatedState(selected)
    val on by rememberUpdatedState(enabled)
    val spec by rememberUpdatedState(Fuse.motion.followScroll())
    LaunchedEffect(state) {
        // Paused while [enabled] is false (an item held by touch), then catches up.
        followEach(snapshotFlow { if (on()) current() else null }) { state.follow(it, anchor, animate, spec) }
    }
}

/**
 * [keepInView] for every new [selected] index, measured after the frame that changed it, like
 * [FollowSelection]. While [enabled] is false (the selection was just made by a finger, on the row
 * it touched) nothing scrolls under the finger.
 */
@Composable
fun KeepSelectionInView(state: LazyListState, selected: () -> Int, marginPx: Int, enabled: () -> Boolean = { true }) {
    val current by rememberUpdatedState(selected)
    val on by rememberUpdatedState(enabled)
    val spec by rememberUpdatedState(Fuse.motion.followScroll())
    LaunchedEffect(state) {
        followEach(snapshotFlow { if (on()) current() else null }) { state.keepInView(it, marginPx, spec = spec) }
    }
}

@Composable
fun FollowSelection(
    state: LazyGridState,
    selected: () -> Int,
    anchor: Float = 0.2f,
    animate: Boolean = true,
    enabled: () -> Boolean = { true },
    /** Pixels at the top kept clear of the selected row (under a fading edge). */
    insetPx: Int = 0,
    /** Anything that changes the rows' size (tiles shrinking as a stage folds): the selection is followed again. */
    relayout: Any? = null,
) {
    val current by rememberUpdatedState(selected)
    val on by rememberUpdatedState(enabled)
    val spec by rememberUpdatedState(Fuse.motion.followScroll())
    val inset by rememberUpdatedState(insetPx)
    LaunchedEffect(state) {
        followEach(snapshotFlow { if (on()) current() else null }) { state.follow(it, anchor, animate, spec, inset) }
    }
    LaunchedEffect(state, relayout) {
        if (relayout == null || !on()) return@LaunchedEffect
        withFrameNanos {}
        state.follow(current(), anchor, animate = false, spec = spec, inset = inset)
    }
}

/**
 * Runs [follow] for each new selection after the layout of the frame it changed in: two frames on,
 * that frame's measure pass has run. The previous follow keeps going until then and is only
 * replaced when the new one starts, so following never pauses between steps.
 */
private suspend fun followEach(selections: Flow<Int?>, follow: suspend (Int) -> Unit) = coroutineScope {
    var running: Job? = null
    selections.collect { index ->
        val previous = running
        running = if (index == null) {
            previous?.cancel()
            null
        } else {
            launch {
                withFrameNanos {}
                withFrameNanos {}
                previous?.cancelAndJoin()
                follow(index)
            }
        }
    }
}
