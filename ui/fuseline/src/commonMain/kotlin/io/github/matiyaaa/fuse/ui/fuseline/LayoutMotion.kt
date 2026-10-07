package io.github.matiyaaa.fuse.ui.fuseline

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Which coordinates a layout motion follows. */
enum class MotionSpace {
    /** Within its parent: the parent moving or scrolling carries it at once; only its own moves animate. */
    PARENT,

    /** On the screen: any move animates, the parent's too (what an element travelling between screens wants). */
    ROOT,
}

/**
 * Old bounds to new without any animation code in the component: when this element's place (or, with
 * [animateSize], its size) changes, it moves from where it was to where layout now puts it under
 * [motion]. If the destination moves again while it travels (layout changes, the window resizes, its
 * parent moves in [MotionSpace.ROOT]), the motion is retargeted in place, carrying on from where it is
 * and how fast it is going. The first placement takes its place at once. Only placement re-runs per
 * frame for a move; a size change re-measures the content at the moving size.
 */
fun Modifier.motionBounds(
    motion: Motion = Spring(1f, 600f),
    space: MotionSpace = MotionSpace.PARENT,
    animateSize: Boolean = false,
): Modifier = this then MotionBoundsElement(motion, space, animateSize)

private data class MotionBoundsElement(val motion: Motion, val space: MotionSpace, val animateSize: Boolean) : ModifierNodeElement<MotionBoundsNode>() {
    override fun create() = MotionBoundsNode(motion, space, animateSize)
    override fun update(node: MotionBoundsNode) {
        node.motion = motion
        node.space = space
        node.animateSize = animateSize
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "motionBounds"
        properties["motion"] = motion
        properties["space"] = space
    }
}

private class MotionBoundsNode(var motion: Motion, var space: MotionSpace, var animateSize: Boolean) :
    Modifier.Node(), LayoutModifierNode, GlobalPositionAwareModifierNode {
    private val place = FuselineValue(Offset.Zero, OffsetConverter)
    private val size = FuselineValue(Size.Zero, SizeConverter)
    private var placed = false
    private var sized = false

    // Where layout puts it now; a number Compose watches, so the layer is redrawn the moment it changes.
    private var targetX = 0f
    private var targetY = 0f
    private val targetMoved = androidx.compose.runtime.mutableIntStateOf(0)

    override fun onDetach() {
        placed = false
        sized = false
    }

    override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult {
        val full = measurable.measure(constraints)
        var w = full.width
        var h = full.height
        if (animateSize) {
            follow(size, full.width.toFloat(), full.height.toFloat(), sized)
            sized = true
            w = size.component(0).roundToInt().coerceAtLeast(0)
            h = size.component(1).roundToInt().coerceAtLeast(0)
        }
        // The content at the moving size, so it lays itself out as it grows; at rest, the measured one.
        val content = if (w == full.width && h == full.height) full else measurable.measure(Constraints.fixed(w, h))
        return layout(content.width, content.height) {
            // Drawn where it is on its way, as a layer: moving it never lays anything out again.
            content.placeWithLayer(0, 0, layerBlock = shift)
        }
    }

    private val shift: GraphicsLayerScope.() -> Unit = {
        targetMoved.intValue
        if (placed) {
            translationX = place.component(0) - targetX
            translationY = place.component(1) - targetY
        }
    }

    /**
     * Where layout put it, however it got there: its own layout, its parent moving it, a resize. Seen
     * after layout and before drawing, so the frame it moves in is already drawn from where it was.
     */
    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        val t = if (space == MotionSpace.ROOT) coordinates.positionInRoot() else coordinates.positionInParent()
        if (placed && t.x == targetX && t.y == targetY) return
        targetX = t.x
        targetY = t.y
        follow(place, t.x, t.y, placed)
        placed = true
        targetMoved.intValue++
    }

    /** Sends a two-number [value] toward (x, y) in place if that moved, or puts it there the first time; nothing is boxed per frame. */
    private fun <T> follow(value: FuselineValue<T>, x: Float, y: Float, started: Boolean) {
        if (!started) {
            value.jumpTo(value.converter.read(floatArrayOf(x, y)))
            return
        }
        if (value.targetComponent(0) == x && value.targetComponent(1) == y) return
        if (!value.retargetXY(x, y, motion)) {
            val target = value.converter.read(floatArrayOf(x, y))
            coroutineScope.launch { value.animateTo(target, motion) }
        }
    }
}

// ----------------------------------------------------------------------------------------------
// Shared elements

/**
 * Elements shared between states: when an element with a key leaves (its screen closes, the list it
 * was in is replaced) and one with the same key appears within [handoverMillis], the new one travels
 * from the old one's bounds and corner radius to its own. Its own bounds are followed every layout
 * pass while it travels, so a destination that moves (layout settling, a scroll, a resize, another
 * motion) is chased, never missed, and a travel interrupted by the next change starts from where the
 * element is. Drawn through a graphics layer: nothing is laid out again per frame.
 */
@Stable
class SharedMotion(
    val motion: Motion = Spring(1f, 450f),
    private val handoverMillis: Long = 600L,
) {
    internal class Record(val bounds: Rect, val corner: Float, val atNanos: Long, val velocity: Rect)

    internal val left = HashMap<Any, Record>()
    internal val active = HashMap<Any, SharedMotionNode>()

    internal fun leaving(key: Any, node: SharedMotionNode, bounds: Rect, corner: Float, velocity: Rect) {
        if (active[key] === node) active.remove(key)
        if (bounds.isEmpty && bounds.width == 0f) return
        left[key] = Record(bounds, corner, monotonicNanos(), velocity)
    }

    /** What an element with [key] arriving now travels from, if one left recently enough. */
    internal fun arriving(key: Any, node: SharedMotionNode): Record? {
        val previous = active[key]
        active[key] = node
        // Another element with the key still on screen hands over from where it is shown now.
        if (previous != null && previous !== node && previous.isAttached) {
            previous.hidden = true
            return previous.snapshot()
        }
        val r = left.remove(key) ?: return null
        return r.takeIf { monotonicNanos() - it.atNanos <= handoverMillis * NANOS_PER_MS }
    }
}

@Composable
fun rememberSharedMotion(motion: Motion = Spring(1f, 450f)): SharedMotion = remember(motion) { SharedMotion(motion) }

/**
 * Marks this element as [key] in [shared]: arriving where an element with the same key just left, it
 * travels from there (bounds and [corner] radius) to its own place, chasing its own place as it moves.
 */
fun Modifier.sharedMotion(shared: SharedMotion, key: Any, corner: Dp = 0.dp): Modifier =
    this then SharedMotionElement(shared, key, corner)

private data class SharedMotionElement(val shared: SharedMotion, val key: Any, val corner: Dp) : ModifierNodeElement<SharedMotionNode>() {
    override fun create() = SharedMotionNode(shared, key, corner)
    override fun update(node: SharedMotionNode) {
        node.shared = shared
        node.key = key
        node.corner = corner
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "sharedMotion"
        properties["key"] = key
    }
}

internal class SharedMotionNode(var shared: SharedMotion, var key: Any, var corner: Dp) : Modifier.Node(), LayoutModifierNode {
    /** Where it is shown while it travels (screen bounds); unspecified at rest. */
    private val bounds = FuselineValue(Rect.Zero, RectConverter)
    private val radius = FuselineValue(0f)
    private var target: Rect? = null
    private var travelling = false
    private var started = false
    var hidden = false

    override fun onAttach() {
        started = false
        hidden = false
    }

    override fun onDetach() {
        val t = target ?: return
        val shown = if (travelling) bounds.value else t
        shared.leaving(key, this, shown, radius.floatValue, bounds.velocity)
        travelling = false
        target = null
    }

    /** How it looks now, for an element taking over from it while it is still on screen. */
    internal fun snapshot(): SharedMotion.Record? {
        val t = target ?: return null
        return SharedMotion.Record(if (travelling) bounds.value else t, radius.floatValue, monotonicNanos(), bounds.velocity)
    }

    override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult {
        val p = measurable.measure(constraints)
        return layout(p.width, p.height) {
            val c = coordinates
            if (c == null) {
                p.place(0, 0)
                return@layout
            }
            val own = Rect(c.positionInRoot(), Size(p.width.toFloat(), p.height.toFloat()))
            target = own
            val ownCorner = corner.toPx()
            if (!started) {
                started = true
                val from = shared.arriving(key, this@SharedMotionNode)
                if (from != null) {
                    travelling = true
                    bounds.jumpTo(from.bounds)
                    radius.jumpTo(from.corner)
                    coroutineScope.launch {
                        launch { radius.animateTo(ownCorner, shared.motion) }
                        bounds.animateTo(own, shared.motion, from.velocity)
                        travelling = false
                    }
                } else {
                    radius.jumpTo(ownCorner)
                }
            } else if (travelling && bounds.targetValue != own) {
                // The destination moved: chase it from here, at the speed it has.
                if (MotionTrace.enabled) MotionTrace.record(MotionTrace.Kind.MOVING_TARGET, "shared $key", bounds.component(0), bounds.velocityComponent(0), "to ${own.left}, ${own.top}")
                if (!bounds.retarget(own)) coroutineScope.launch { bounds.animateTo(own, shared.motion); travelling = false }
            }
            p.placeWithLayer(0, 0) { showAt(own) }
        }
    }

    private var shapeRadius = -1f
    private var shapeCache: androidx.compose.ui.graphics.Shape = RectangleShape

    /** Draws the element where it is shown: its laid-out bounds mapped onto the travelling ones. */
    private fun GraphicsLayerScope.showAt(own: Rect) {
        if (hidden) {
            alpha = 0f
            return
        }
        if (!travelling || own.width <= 0f || own.height <= 0f) return
        val left = bounds.component(0)
        val top = bounds.component(1)
        val right = bounds.component(2)
        val bottom = bounds.component(3)
        transformOrigin = TransformOrigin(0f, 0f)
        scaleX = (right - left) / own.width
        scaleY = (bottom - top) / own.height
        translationX = left - own.left
        translationY = top - own.top
        val r = radius.floatValue
        if (r > 0f) {
            clip = true
            // The radius as it looks after scaling: undo the scale so corners stay round.
            val shown = r / scaleX.coerceAtLeast(0.01f)
            if (shown != shapeRadius) {
                shapeRadius = shown
                shapeCache = RoundedCornerShape(shown)
            }
            shape = shapeCache
        } else {
            clip = false
            shape = RectangleShape
        }
    }
}
