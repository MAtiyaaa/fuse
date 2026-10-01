package io.github.matiyaaa.fuse.ui.designsystem.focus

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.zIndex
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The pure geometry of reordering by touch, kept apart so it is tested on its own. Rectangles and
 * points share one coordinate space (Fuse uses the window's).
 */
object ReorderMath {
    /** The key whose rectangle holds [point], or null. */
    fun hit(point: Offset, slots: Map<Any, Rect>): Any? = slots.entries.firstOrNull { it.value.contains(point) }?.key

    /**
     * The key the held item should take the place of: the one under [point], counting only the
     * middle of each rectangle ([margin] of its size in from every side). In a gap, near an edge or
     * over the held item itself, nothing changes (null), so the order doesn't flicker.
     */
    fun target(point: Offset, slots: Map<Any, Rect>, held: Any, margin: Float = 0.12f): Any? =
        slots.entries.firstOrNull { (key, r) ->
            key != held && Rect(
                r.left + r.width * margin, r.top + r.height * margin, r.right - r.width * margin, r.bottom - r.height * margin,
            ).contains(point)
        }?.key

    /**
     * Auto-scroll speed in pixels a second for a finger at [pos] along a list running from [start]
     * to [end]: negative near the start, positive near the end, zero elsewhere. It grows with the
     * square of how deep into the [zone] the finger is, up to [max].
     */
    fun autoScrollSpeed(pos: Float, start: Float, end: Float, zone: Float, max: Float): Float {
        if (zone <= 0f || end <= start) return 0f
        val fromStart = pos - start
        val fromEnd = end - pos
        return when {
            fromStart < zone -> -max * square(((zone - fromStart) / zone).coerceIn(0f, 1f))
            fromEnd < zone -> max * square(((zone - fromEnd) / zone).coerceIn(0f, 1f))
            else -> 0f
        }
    }

    private fun square(f: Float) = f * f
}

/**
 * Where a touch reorder stands: which item is held, where it would go, and where the finger is.
 * Screens pass their list through [arrange] to show the new order while the item is held, and for a
 * moment after it is dropped, until their own data has caught up.
 */
@Stable
class DragReorderState internal constructor() {
    /** The key of the held item, or null. */
    var heldKey: Any? by mutableStateOf(null)
        private set

    /** Where the held item would land, as an index in the arranged list. */
    var target by mutableIntStateOf(-1)
        private set

    /** True once the held item has moved past the touch slop. */
    var moved by mutableStateOf(false)
        private set

    internal var finger by mutableStateOf(Offset.Zero)
    internal var grab = Offset.Zero
    internal var container = Rect.Zero
    internal val bounds = HashMap<Any, Rect>()
    internal var displayed: List<Any> = emptyList()
    private var source: List<Any> = emptyList()

    internal var pending: List<Any>? by mutableStateOf(null)
    private var pendingFrom: List<Any>? = null

    internal var settleKey: Any? by mutableStateOf(null)
    internal var settleFrom = Offset.Zero

    /**
     * [items] in the order to show: the held item at its [target] while held, the dropped order
     * until [items] changes, otherwise [items] as they are.
     */
    fun <T> arrange(items: List<T>, key: (T) -> Any): List<T> {
        val keys = items.map(key)
        source = keys
        val held = heldKey
        val order = pending
        val result = when {
            held != null -> {
                val from = keys.indexOf(held)
                if (from < 0 || target !in items.indices || from == target) items else items.toMutableList().apply { add(target, removeAt(from)) }
            }
            order != null && keys == pendingFrom -> items.sortedBy { order.indexOf(key(it)).let { i -> if (i < 0) Int.MAX_VALUE else i } }
            else -> items
        }
        displayed = result.map(key)
        return result
    }

    internal fun lift(key: Any, at: Offset) {
        val r = bounds[key] ?: return
        heldKey = key
        grab = at - r.topLeft
        finger = at
        target = displayed.indexOf(key)
        moved = false
        settleKey = null
    }

    /** Moves the target under the finger; true when it changed. */
    internal fun retarget(visible: Collection<Any>): Boolean {
        val held = heldKey ?: return false
        val slots = bounds.filterKeys { it in visible }
        val over = ReorderMath.target(finger, slots, held) ?: return false
        val to = displayed.indexOf(over)
        if (to < 0 || to == target) return false
        target = to
        return true
    }

    internal fun markMoved() {
        moved = true
    }

    /** Ends the hold; the dropped order shows until the screen's own list changes. */
    internal fun release(keep: Boolean) {
        val held = heldKey ?: return
        bounds[held]?.let { settleFrom = finger - grab - it.topLeft }
        settleKey = held
        if (keep && moved) {
            pendingFrom = source
            pending = displayed
        }
        heldKey = null
        moved = false
    }

    internal fun clearPending() {
        pending = null
        pendingFrom = null
    }
}

/** A reorder state for one list; a dropped order is shown for at most a second and a half. */
@Composable
fun rememberDragReorderState(): DragReorderState {
    val state = remember { DragReorderState() }
    LaunchedEffect(state.pending) {
        if (state.pending != null) {
            delay(1_500)
            state.clearPending()
        }
    }
    return state
}

/**
 * Hold and drag to reorder a lazy list or grid by touch. Put it on the list itself and
 * [reorderItem] on each item. A hold ([longPressMs]) lifts the item under the finger; dragging
 * moves it, the others make room, and the list scrolls by itself near its ends; letting go drops it
 * ([onDrop] with its key and new index). A hold let go without moving calls [onHoldReleased], so
 * whatever a long press did before (an options menu) still works. Until the hold, taps and
 * scrolling are untouched. [visibleKeys] are the keys the list currently shows, [scrollBy] scrolls
 * it, and [endInset] is chrome over the list's end (a hint bar) where auto-scroll starts sooner.
 */
fun Modifier.dragReorder(
    state: DragReorderState,
    visibleKeys: () -> Collection<Any>,
    scrollBy: suspend (Float) -> Float,
    vertical: Boolean = true,
    enabled: Boolean = true,
    longPressMs: Long? = null,
    endInset: Dp = 0.dp,
    onLift: (Any) -> Unit = {},
    onTarget: () -> Unit = {},
    onHoldReleased: (Any) -> Unit = {},
    onDrop: (key: Any, to: Int) -> Unit,
): Modifier = this
    .onGloballyPositioned { state.container = Rect(it.positionInRoot(), it.size.toSize()) }
    .pointerInput(state, enabled) {
        if (!enabled) return@pointerInput
        coroutineScope {
            // Scrolls while a held item is near either end; asleep otherwise.
            launch {
                while (true) {
                    snapshotFlow { state.heldKey }.first { it != null }
                    var last = 0L
                    while (state.heldKey != null) {
                        val now = withFrameNanos { it }
                        val dt = if (last == 0L) 0f else (now - last) / 1_000_000_000f
                        last = now
                        val box = state.container
                        val speed = if (vertical) {
                            ReorderMath.autoScrollSpeed(state.finger.y, box.top, box.bottom - endInset.toPx(), 56.dp.toPx(), 1_400.dp.toPx())
                        } else {
                            ReorderMath.autoScrollSpeed(state.finger.x, box.left, box.right - endInset.toPx(), 56.dp.toPx(), 1_400.dp.toPx())
                        }
                        if (speed != 0f && dt > 0f) {
                            scrollBy(speed * dt)
                            if (state.retarget(visibleKeys())) onTarget()
                        }
                    }
                }
            }
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                val slop = viewConfiguration.touchSlop
                // Wait out the hold without taking anything, so taps and scrolls behave as always.
                val early = withTimeoutOrNull(longPressMs ?: viewConfiguration.longPressTimeoutMillis) {
                    var ended = false
                    while (!ended) {
                        val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id }
                        ended = change == null || !change.pressed || change.isConsumed || (change.position - down.position).getDistance() > slop
                    }
                    true
                }
                if (early != null) return@awaitEachGesture
                val at = state.container.topLeft + down.position
                val key = ReorderMath.hit(at, state.bounds.filterKeys { it in visibleKeys() }) ?: return@awaitEachGesture
                state.lift(key, at)
                if (state.heldKey == null) return@awaitEachGesture
                onLift(key)
                var cancelled = false
                while (true) {
                    val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id }
                    if (change == null) {
                        cancelled = true
                        break
                    }
                    change.consume()
                    if (!change.pressed) break
                    state.finger = state.container.topLeft + change.position
                    if (!state.moved && (state.finger - at).getDistance() > slop) state.markMoved()
                    if (state.moved && state.retarget(visibleKeys())) onTarget()
                }
                val moved = state.moved
                val to = state.target
                state.release(keep = !cancelled)
                when {
                    cancelled -> Unit
                    moved -> onDrop(key, to)
                    else -> onHoldReleased(key)
                }
            }
        }
    }

/**
 * One item of a [dragReorder] list: reports where it is, and while held follows the finger above
 * the others (then settles into its place when dropped). Put it before the item's own scaling, and
 * give the held item no placement animation so it never lags behind the finger.
 */
@Composable
fun Modifier.reorderItem(state: DragReorderState, key: Any, liftScale: Float = 1.06f): Modifier {
    var topLeft by remember { mutableStateOf(Offset.Zero) }
    val held = state.heldKey == key
    val settle = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
    val lift = remember { Animatable(0f) }
    val currentKey by rememberUpdatedState(key)
    LaunchedEffect(held) {
        if (held) {
            settle.snapTo(Offset.Zero)
            lift.animateTo(1f, spring(stiffness = 900f))
        } else if (state.settleKey == currentKey) {
            settle.snapTo(state.settleFrom)
            launch { lift.animateTo(0f, spring(stiffness = 700f)) }
            settle.animateTo(Offset.Zero, spring(dampingRatio = 0.8f, stiffness = 500f))
        } else {
            lift.snapTo(0f)
        }
    }
    return this
        .zIndex(if (held || lift.value > 0.01f) 1f else 0f)
        .onGloballyPositioned {
            val p = it.positionInRoot()
            state.bounds[key] = Rect(p, it.size.toSize())
            if (p != topLeft) topLeft = p
        }
        .graphicsLayer {
            val t = if (held) state.finger - state.grab - topLeft else settle.value
            translationX = t.x
            translationY = t.y
            val s = 1f + (liftScale - 1f) * lift.value
            scaleX = s
            scaleY = s
        }
}
