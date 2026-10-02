package io.github.matiyaaa.fuse.ui.designsystem.focus

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
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
     * over the held item itself, nothing changes (null), so the order doesn't flicker. [skip] is the
     * item just passed: in a grid of mixed sizes it can slide under the finger again, so it only
     * counts once the finger has left it.
     */
    fun target(point: Offset, slots: Map<Any, Rect>, held: Any, margin: Float = 0.12f, skip: Any? = null, lane: Lane = Lane.GRID): Any? {
        // In a single row or column only the distance along it counts: a shelf held by its title, at
        // the far left, still finds the shelf it is over.
        val mx = if (lane == Lane.COLUMN) 0f else margin
        val my = if (lane == Lane.ROW) 0f else margin
        return slots.entries.firstOrNull { (key, r) ->
            key != held && key != skip && Rect(
                r.left + r.width * mx, r.top + r.height * my, r.right - r.width * mx, r.bottom - r.height * my,
            ).contains(point)
        }?.key
    }

    /** How the items of a reorderable list are laid out. */
    enum class Lane { GRID, ROW, COLUMN }

    /**
     * The key whose rectangle is closest to [point], other than [held]: while the list scrolls under
     * a finger resting at its edge, the held item takes the nearest place, so it never scrolls away.
     */
    fun nearest(point: Offset, slots: Map<Any, Rect>, held: Any): Any? =
        slots.entries.filter { it.key != held }.minByOrNull { (_, r) ->
            val dx = (r.left - point.x).coerceAtLeast(0f) + (point.x - r.right).coerceAtLeast(0f)
            val dy = (r.top - point.y).coerceAtLeast(0f) + (point.y - r.bottom).coerceAtLeast(0f)
            dx * dx + dy * dy
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

/** How a touch reorder moves and feels, shared by every list that can be rearranged. */
object ReorderDefaults {
    /** Neighbours sliding out of the way: quick, settling with almost no overshoot. */
    val Placement: FiniteAnimationSpec<IntOffset> = spring(dampingRatio = 0.86f, stiffness = 420f, visibilityThreshold = IntOffset.VisibilityThreshold)

    /** A dropped item gliding into its place. */
    val Settle: FiniteAnimationSpec<Offset> = spring(dampingRatio = 0.78f, stiffness = 520f, visibilityThreshold = Offset.VisibilityThreshold)

    /** How much a held item grows, and how high its shadow lifts it. */
    const val LIFT_SCALE = 1.08f
    val LiftElevation = 20.dp

    /** The hold before an item lifts: a little quicker than a long press, so it feels direct. */
    fun liftMs(longPressMs: Long): Long = (longPressMs * 0.7f).toLong().coerceIn(300L, 450L)
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

    /**
     * An item a menu's "Move" picked: the next touch on it drags at once, without the hold. A touch
     * anywhere else, or a drop, clears it.
     */
    var armedKey: Any? by mutableStateOf(null)
        private set

    fun arm(key: Any?) {
        armedKey = key
    }

    internal var finger by mutableStateOf(Offset.Zero)
    internal var grab = Offset.Zero
    internal var container = Rect.Zero
    internal val bounds = HashMap<Any, Rect>()
    internal val handles = HashMap<Any, Rect>()
    internal var lane = ReorderMath.Lane.GRID
    private var passed: Any? = null
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
        // The order just dropped, until the screen's own list catches up; a new hold starts from it too.
        val base = if (order != null && keys == pendingFrom) {
            items.sortedBy { order.indexOf(key(it)).let { i -> if (i < 0) Int.MAX_VALUE else i } }
        } else {
            items
        }
        val result = if (held != null) {
            val from = base.indexOfFirst { key(it) == held }
            if (from < 0 || target !in base.indices || from == target) base else base.toMutableList().apply { add(target, removeAt(from)) }
        } else {
            base
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
        passed = null
        armedKey = null
    }

    /** Moves the target under the finger; true when it changed. */
    internal fun retarget(visible: Collection<Any>): Boolean {
        val held = heldKey ?: return false
        val slots = bounds.filterKeys { it in visible }
        passed?.let { p -> if (slots[p]?.contains(finger) != true) passed = null }
        val over = ReorderMath.target(finger, slots, held, skip = passed, lane = lane) ?: return false
        val to = displayed.indexOf(over)
        if (to < 0 || to == target) return false
        target = to
        passed = over
        return true
    }

    /** While auto-scrolling: the held item takes the place nearest the finger. */
    internal fun retargetNearest(visible: Collection<Any>): Boolean {
        val held = heldKey ?: return false
        if (retarget(visible)) return true
        // Still over its own place: it stays there.
        if (bounds[held]?.contains(finger) == true) return false
        val over = ReorderMath.nearest(finger, bounds.filterKeys { it in visible }, held) ?: return false
        val to = displayed.indexOf(over)
        if (to < 0 || to == target) return false
        target = to
        passed = over
        return true
    }

    internal fun markMoved() {
        moved = true
    }

    /** Ends the hold; the dropped order shows until the screen's own list changes. */
    internal fun release(keep: Boolean) {
        val held = heldKey ?: return
        bounds[held]?.let {
            val d = finger - grab - it.topLeft
            settleFrom = when (lane) {
                ReorderMath.Lane.COLUMN -> Offset(0f, d.y)
                ReorderMath.Lane.ROW -> Offset(d.x, 0f)
                ReorderMath.Lane.GRID -> d
            }
        }
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
 *
 * A lazy list keeps its first visible item in place by key, so moving that item would scroll the
 * list; [keepScroll] is called as the order changes to pin the scroll by index instead (pass
 * `{ state.requestScrollToItem(state.firstVisibleItemIndex, state.firstVisibleItemScrollOffset) }`).
 *
 * [lane] says whether the items form a grid, a single row or a single column. A touch where [ignore]
 * says so (a point in the window) is left alone, for controls that sit on the items (a resize grip).
 *
 * With [requireHandle] only a hold on an item's [reorderHandle] lifts it, so a list of rows can be
 * rearranged while the rows' own items keep their holds (a row of systems inside a list of shelves).
 * With [instantHandles] a touch on a handle lifts its item at once, without the hold (a list made
 * for rearranging, whose rows show grips). An item that was [DragReorderState.arm]ed lifts at the
 * first touch.
 *
 * The gesture outlives recompositions (restarting it would drop an item mid-drag), so the callbacks
 * are read as they are now, never as they were when the list was first touched: a drop always sees
 * the screen's current list.
 */
@Composable
fun Modifier.dragReorder(
    state: DragReorderState,
    visibleKeys: () -> Collection<Any>,
    scrollBy: suspend (Float) -> Float,
    vertical: Boolean = true,
    enabled: Boolean = true,
    longPressMs: Long? = null,
    endInset: Dp = 0.dp,
    requireHandle: Boolean = false,
    instantHandles: Boolean = false,
    lane: ReorderMath.Lane = ReorderMath.Lane.GRID,
    ignore: (Offset) -> Boolean = { false },
    keepScroll: () -> Unit = {},
    onLift: (Any) -> Unit = {},
    onTarget: () -> Unit = {},
    onHoldReleased: (Any) -> Unit = {},
    onDrop: (key: Any, to: Int) -> Unit,
): Modifier {
    val currentVisibleKeys by rememberUpdatedState(visibleKeys)
    val currentScrollBy by rememberUpdatedState(scrollBy)
    val currentLongPressMs by rememberUpdatedState(longPressMs)
    val currentKeepScroll by rememberUpdatedState(keepScroll)
    val currentIgnore by rememberUpdatedState(ignore)
    val currentOnLift by rememberUpdatedState(onLift)
    val currentOnTarget by rememberUpdatedState(onTarget)
    val currentOnHoldReleased by rememberUpdatedState(onHoldReleased)
    val currentOnDrop by rememberUpdatedState(onDrop)
    return this
        .onGloballyPositioned {
            state.container = Rect(it.positionInRoot(), it.size.toSize())
            state.lane = lane
        }
        .pointerInput(state, enabled) {
            if (!enabled) return@pointerInput
            dragGestures(
                state, vertical, endInset, requireHandle, instantHandles,
                visibleKeys = { currentVisibleKeys() },
                scrollBy = { currentScrollBy(it) },
                longPressMs = { currentLongPressMs },
                ignore = { currentIgnore(it) },
                keepScroll = { currentKeepScroll() },
                onLift = { currentOnLift(it) },
                onTarget = { currentOnTarget() },
                onHoldReleased = { currentOnHoldReleased(it) },
                onDrop = { key, to -> currentOnDrop(key, to) },
            )
        }
}

private suspend fun PointerInputScope.dragGestures(
    state: DragReorderState,
    vertical: Boolean,
    endInset: Dp,
    requireHandle: Boolean,
    instantHandles: Boolean,
    visibleKeys: () -> Collection<Any>,
    scrollBy: suspend (Float) -> Float,
    longPressMs: () -> Long?,
    ignore: (Offset) -> Boolean,
    keepScroll: () -> Unit,
    onLift: (Any) -> Unit,
    onTarget: () -> Unit,
    onHoldReleased: (Any) -> Unit,
    onDrop: (key: Any, to: Int) -> Unit,
) {
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
                    if (speed != 0f && dt > 0f && scrollBy(speed * dt) != 0f && state.retargetNearest(visibleKeys())) {
                        // The list carried the item to a new place: that is a move, even with a still finger.
                        state.markMoved()
                        keepScroll()
                        onTarget()
                    }
                }
            }
        }
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val slop = viewConfiguration.touchSlop
            val at = state.container.topLeft + down.position
            if (ignore(at)) return@awaitEachGesture
            val visible = visibleKeys()
            val handle = if (instantHandles) state.handles.entries.firstOrNull { (k, r) -> k in visible && r.contains(at) }?.key else null
            val armed = state.armedKey?.takeIf { it in visible && state.bounds[it]?.contains(at) == true } ?: handle
            if (armed == null && state.armedKey != null) state.arm(null)
            if (armed == null) {
                // Wait out the hold without taking anything, so taps and scrolls behave as always.
                val early = withTimeoutOrNull(longPressMs() ?: ReorderDefaults.liftMs(viewConfiguration.longPressTimeoutMillis)) {
                    var ended = false
                    while (!ended) {
                        val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id }
                        ended = change == null || !change.pressed || change.isConsumed || (change.position - down.position).getDistance() > slop
                    }
                    true
                }
                if (early != null) return@awaitEachGesture
            }
            val key = armed ?: if (requireHandle) {
                state.handles.entries.firstOrNull { (k, r) -> k in visible && r.contains(at) }?.key
            } else {
                ReorderMath.hit(at, state.bounds.filterKeys { it in visible })
            } ?: return@awaitEachGesture
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
                if (state.moved && state.retarget(visibleKeys())) {
                    keepScroll()
                    onTarget()
                }
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
 * the others, grown by [liftScale] over a soft shadow in [shape]; dropped, it glides into its place.
 * Put it before the item's own scaling, and give the held item no placement animation so it never
 * lags behind the finger.
 *
 * The item's place is read while it is placed, so the held item is drawn from the same frame's
 * position (no jump when it takes a new slot), and moving the finger only redraws its layer.
 */
@Composable
fun Modifier.reorderItem(state: DragReorderState, key: Any, liftScale: Float = ReorderDefaults.LIFT_SCALE, shape: Shape? = null): Modifier {
    val held = state.heldKey == key
    val settle = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
    val lift = remember { Animatable(0f) }
    val currentKey by rememberUpdatedState(key)
    LaunchedEffect(held) {
        if (held) {
            settle.snapTo(Offset.Zero)
            lift.animateTo(1f, spring(dampingRatio = 0.7f, stiffness = 700f))
        } else if (state.settleKey == currentKey) {
            settle.snapTo(state.settleFrom)
            launch { lift.animateTo(0f, spring(dampingRatio = 0.9f, stiffness = 500f)) }
            settle.animateTo(Offset.Zero, ReorderDefaults.Settle)
        } else {
            lift.snapTo(0f)
        }
    }
    val raised by remember { derivedStateOf { state.heldKey == currentKey || lift.value > 0.01f } }
    val elevation = ReorderDefaults.LiftElevation
    return this
        // Catches an ancestor moving without this item being placed again.
        .onGloballyPositioned { state.bounds[key] = Rect(it.positionInRoot(), it.size.toSize()) }
        .layout { measurable, constraints ->
            val p = measurable.measure(constraints)
            layout(p.width, p.height) {
                val origin = coordinates?.positionInRoot() ?: Offset.Zero
                state.bounds[key] = Rect(origin, Size(p.width.toFloat(), p.height.toFloat()))
                p.placeWithLayer(0, 0, zIndex = if (raised) 1f else 0f) {
                    val l = lift.value
                    // In a single row or column the held item slides along it only, as in a phone's lists.
                    val t = if (state.heldKey == key) {
                        val d = state.finger - state.grab - origin
                        when (state.lane) {
                            ReorderMath.Lane.COLUMN -> Offset(0f, d.y)
                            ReorderMath.Lane.ROW -> Offset(d.x, 0f)
                            ReorderMath.Lane.GRID -> d
                        }
                    } else {
                        settle.value
                    }
                    translationX = t.x
                    translationY = t.y
                    val s = 1f + (liftScale - 1f) * l
                    scaleX = s
                    scaleY = s
                    if (shape != null && l > 0f) {
                        this.shape = shape
                        shadowElevation = elevation.toPx() * l
                        ambientShadowColor = Color.Black
                        spotShadowColor = Color.Black
                    } else {
                        shadowElevation = 0f
                    }
                }
            }
        }
}

/** Marks the part of an item a hold lifts it by, for a list with `requireHandle`. */
fun Modifier.reorderHandle(state: DragReorderState, key: Any): Modifier =
    onGloballyPositioned { state.handles[key] = Rect(it.positionInRoot(), it.size.toSize()) }

/**
 * The look of an item carried with the controller, the same as one lifted by touch: grown a little,
 * raised, over a soft shadow in [shape]. [lift] runs from 0 (resting) to 1 (carried).
 */
fun Modifier.carried(lift: () -> Float, shape: Shape, scale: Float = 1.05f): Modifier = graphicsLayer {
    val l = lift()
    val s = 1f + (scale - 1f) * l
    scaleX = s
    scaleY = s
    translationY = -6.dp.toPx() * l
    if (l > 0f) {
        this.shape = shape
        shadowElevation = ReorderDefaults.LiftElevation.toPx() * l
        ambientShadowColor = Color.Black
        spotShadowColor = Color.Black
    } else {
        shadowElevation = 0f
    }
}
