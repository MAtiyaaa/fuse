package io.github.matiyaaa.fuse.ui.designsystem.input

import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.abs
import kotlin.math.sign
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Whether a held pointer dragging a list scrolls it ([dragScroll]). On Android a finger arrives as a
 * finger and lists scroll under it by themselves, so this stays off there. On Linux and Windows a
 * touch screen's finger reaches the app as the mouse: Compose Desktop is only told about mouse
 * events, with nothing saying a finger made them, so a drag with the mouse button held scrolls too.
 */
val LocalPointerDragScroll = staticCompositionLocalOf { false }

/**
 * Dragging this list or row with the pointer held scrolls it under the pointer, and letting go flings
 * it on at the pointer's speed, as a finger does on a phone. This is for computers' touch screens,
 * whose fingers arrive as the mouse ([LocalPointerDragScroll]); a real finger (Android) is left to
 * the list's own scrolling.
 *
 * The drag starts only once the pointer has moved a finger's slop along [orientation] (more along it
 * than across it), so a tap or a still press is a click or a long press as before, and from then on
 * the item under it no longer takes the click. Anything inside that takes the drag first (a slider,
 * a handle reordering a list, a row scrolling across inside a column scrolling down) keeps it.
 * [interactionSource] hears the drag start and stop, as a list's own does for a finger. A pager
 * passes its own [flingBehavior] (`PagerDefaults.flingBehavior`), so letting go settles on a page.
 */
fun Modifier.dragScroll(
    state: ScrollableState,
    orientation: Orientation = Orientation.Vertical,
    enabled: Boolean = true,
    interactionSource: MutableInteractionSource? = null,
    flingBehavior: FlingBehavior? = null,
): Modifier = composed {
    if (!enabled || !LocalPointerDragScroll.current) return@composed this
    val fling = flingBehavior ?: ScrollableDefaults.flingBehavior()
    val scope = rememberCoroutineScope()
    val moving = remember { arrayOfNulls<Job>(1) }
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val vertical = orientation == Orientation.Vertical
    // The pointer's movement as scrolling: down the screen (or across, the way the row reads) goes back.
    fun along(d: Offset) = if (vertical) d.y else d.x
    fun across(d: Offset) = if (vertical) d.x else d.y
    fun toScroll(d: Float) = if (!vertical && rtl) d else -d
    pointerInput(state, orientation, rtl, fling, interactionSource) {
        val slop = viewConfiguration.touchSlop
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            if (down.type == PointerType.Touch) return@awaitEachGesture
            // A press on a list still flinging stops it there, and only that: nothing under it is clicked.
            moving[0]?.takeIf { it.isActive }?.let { job ->
                job.cancel()
                down.consume()
                return@awaitEachGesture
            }
            // The press's own pass here, after what is inside had it. A row taking the press (every
            // clickable does) still scrolls with it; what takes the movement (a slider, a handle) keeps
            // the drag, below. Only the primary button drags.
            awaitPointerEvent()
            if (!currentEvent.buttons.isPrimaryPressed) return@awaitEachGesture
            var total = Offset.Zero
            while (true) {
                val e = awaitPointerEvent()
                val ch = e.changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
                // Let go before moving far enough (a click), or something inside took the move.
                if (!ch.pressed || ch.isConsumed) return@awaitEachGesture
                total += ch.positionChange()
                if (abs(along(total)) > slop && abs(along(total)) >= abs(across(total))) {
                    ch.consume()
                    break
                }
                // Moving the other way first: a row inside, or the column around, scrolls instead.
                if (abs(across(total)) > slop) return@awaitEachGesture
            }
            val start = DragInteraction.Start()
            interactionSource?.tryEmit(start)
            val deltas = Channel<Float>(Channel.UNLIMITED)
            val released = CompletableDeferred<Float>()
            val tracker = VelocityTracker()
            currentEvent.changes.first { it.id == down.id }.let { tracker.addPosition(it.uptimeMillis, it.position) }
            // Past the slop, the list moves by what is beyond it, so it doesn't jump by the slop at once.
            deltas.trySend(toScroll(along(total) - sign(along(total)) * slop))
            moving[0] = scope.launch {
                try {
                    state.scroll(MutatePriority.UserInput) {
                        for (d in deltas) scrollBy(d)
                        // Even at no speed: a pager settles on the nearer page.
                        val velocity = released.await()
                        with(fling) { performFling(velocity) }
                    }
                } finally {
                    interactionSource?.tryEmit(DragInteraction.Stop(start))
                }
            }
            var velocity = 0f
            try {
                while (true) {
                    val e = awaitPointerEvent()
                    val ch = e.changes.firstOrNull { it.id == down.id } ?: break
                    if (!ch.pressed) {
                        // A release that arrives already used (the system took the pointer) stops where it is.
                        if (!ch.isConsumed) {
                            tracker.addPosition(ch.uptimeMillis, ch.position)
                            val v = tracker.calculateVelocity()
                            velocity = toScroll(if (vertical) v.y else v.x)
                        }
                        ch.consume()
                        break
                    }
                    tracker.addPosition(ch.uptimeMillis, ch.position)
                    val d = along(ch.positionChange())
                    if (d != 0f) deltas.trySend(toScroll(d))
                    ch.consume()
                }
            } finally {
                deltas.close()
                released.complete(velocity)
            }
        }
    }
}

/**
 * [verticalScroll] that a held pointer also drags ([dragScroll]). The drag is heard outside the
 * scrolling content, so where the pointer is never moves with what it scrolls.
 */
fun Modifier.dragVerticalScroll(state: ScrollState): Modifier = dragScroll(state).verticalScroll(state)

/** [horizontalScroll] that a held pointer also drags ([dragScroll]). */
fun Modifier.dragHorizontalScroll(state: ScrollState): Modifier = dragScroll(state, Orientation.Horizontal).horizontalScroll(state)
