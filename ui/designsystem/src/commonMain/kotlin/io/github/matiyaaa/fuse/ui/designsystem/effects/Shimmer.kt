package io.github.matiyaaa.fuse.ui.designsystem.effects

import androidx.compose.animation.core.withInfiniteAnimationFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.platform.InspectorInfo
import io.github.matiyaaa.fuse.ui.designsystem.theme.Durations
import io.github.matiyaaa.fuse.ui.designsystem.theme.Easings
import io.github.matiyaaa.fuse.ui.designsystem.theme.LocalFuseLook
import io.github.matiyaaa.fuse.ui.designsystem.theme.LocalFuseMotion
import io.github.matiyaaa.fuse.ui.designsystem.theme.LocalRenderQuality
import io.github.matiyaaa.fuse.ui.designsystem.theme.ambientOn
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * A loading placeholder: [shape] filled with the skeleton colour role, with a calm band of light
 * passing over it now and then. Use it for the shapes of content that is on its way (a row of tile
 * outlines, text lines, a cover) so a loading screen already has its layout.
 *
 * Every shimmer on screen moves in step and in window coordinates, so a page of placeholders reads
 * as one surface catching the light rather than many blinking boxes. The light rests between passes
 * and moves at about 30 frames per second. It stays still (fill only) under Reduced and Minimal
 * motion and in Low Power Mode.
 */
fun Modifier.skeleton(shape: Shape = RectangleShape, active: Boolean = true): Modifier =
    this then ShimmerElement(shape, fill = true, active = active)

/**
 * Only the passing light of [skeleton], over whatever this element draws: for content that is
 * already shown but still loading (generated art while the real art arrives). Nothing when
 * [active] is false or motion is still.
 */
fun Modifier.shimmer(shape: Shape = RectangleShape, active: Boolean = true): Modifier =
    this then ShimmerElement(shape, fill = false, active = active)

private class ShimmerElement(val shape: Shape, val fill: Boolean, val active: Boolean) : ModifierNodeElement<ShimmerNode>() {
    override fun create() = ShimmerNode(shape, fill, active)

    override fun update(node: ShimmerNode) = node.update(shape, fill, active)

    override fun equals(other: Any?) =
        other is ShimmerElement && other.shape == shape && other.fill == fill && other.active == active

    override fun hashCode() = (shape.hashCode() * 31 + fill.hashCode()) * 31 + active.hashCode()

    override fun InspectorInfo.inspectableProperties() {
        name = if (fill) "skeleton" else "shimmer"
        properties["active"] = active
    }
}

private class ShimmerNode(
    private var shape: Shape,
    private var fill: Boolean,
    private var active: Boolean,
) : Modifier.Node(), DrawModifierNode, GlobalPositionAwareModifierNode, CompositionLocalConsumerModifierNode {

    /** 0..1 position of the light through one pass; negative while it rests. */
    private var phase = -1f
    private var running = false

    private var originX = 0f
    private var rootWidth = 0f

    private var cachedSize = Size.Unspecified
    private var outline: Outline? = null
    private var path: Path? = null
    private var band: Brush? = null
    private var bandColor = Color.Unspecified
    private var bandWidth = 0f

    override val shouldAutoInvalidate: Boolean get() = false

    fun update(shape: Shape, fill: Boolean, active: Boolean) {
        if (shape != this.shape) cachedSize = Size.Unspecified
        this.shape = shape
        this.fill = fill
        if (active != this.active) {
            this.active = active
            if (active) start() else phase = -1f
        }
        invalidateDraw()
    }

    override fun onAttach() = start()

    override fun onDetach() {
        running = false
        phase = -1f
    }

    private fun start() {
        if (!active || running || !isAttached) return
        val motion = currentValueOf(LocalFuseMotion)
        if (!motion.ambientOn(currentValueOf(LocalRenderQuality))) return
        running = true
        coroutineScope.launch {
            try {
                var last = 0L
                while (isActive && active) {
                    val now = withInfiniteAnimationFrameMillis { it }
                    // About 30 frames per second is plenty for a soft band of light.
                    if (now - last < FRAME_MS) continue
                    last = now
                    val t = (now % Durations.SHIMMER).toFloat() / Durations.SHIMMER
                    val next = if (t < PASS) Easings.Fade.transform(t / PASS) else -1f
                    if (next != phase) {
                        phase = next
                        invalidateDraw()
                    }
                    // While the light rests, sleep until its next pass instead of waking every frame.
                    if (next < 0f) delay(((1f - t) * Durations.SHIMMER).toLong().coerceAtLeast(1L))
                }
            } finally {
                running = false
            }
        }
    }

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        originX = coordinates.positionInRoot().x
        rootWidth = coordinates.findRootCoordinates().size.width.toFloat()
    }

    override fun ContentDrawScope.draw() {
        val colors = currentValueOf(LocalFuseLook).colors
        if (cachedSize != size || bandColor != colors.shimmer) {
            cachedSize = size
            bandColor = colors.shimmer
            val o = shape.createOutline(size, layoutDirection, this)
            outline = o
            path = Path().apply { addOutline(o) }
            // One band width for every placeholder, so the light lines up across blocks of any size.
            bandWidth = BAND.toPx()
            // A soft band, a little angled, defined around x = 0 and moved by translation.
            band = Brush.linearGradient(
                0f to Color.Transparent,
                0.5f to bandColor,
                1f to Color.Transparent,
                start = Offset(-bandWidth / 2, 0f),
                end = Offset(bandWidth / 2, bandWidth * 0.18f),
            )
        }
        if (fill) outline?.let { drawOutline(it, colors.skeleton) }
        drawContent()
        val p = phase
        val brush = band
        val clip = path
        if (!active || p < 0f || brush == null || clip == null) return
        val span = maxOf(rootWidth, size.width) + bandWidth * 2
        val x = -bandWidth + span * p - originX
        if (x < -bandWidth || x > size.width + bandWidth) return
        clipPath(clip) {
            translate(left = x) {
                drawRect(brush, topLeft = Offset(-bandWidth, 0f), size = Size(bandWidth * 2, size.height))
            }
        }
    }

    private companion object {
        const val FRAME_MS = 33L

        /** The width of the band of light. */
        val BAND = 200.dp

        /** Share of each cycle the light is moving; it rests for the rest. */
        const val PASS = 0.62f
    }
}
