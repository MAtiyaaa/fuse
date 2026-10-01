package io.github.matiyaaa.fuse.ui.designsystem.effects

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.HoverInteraction
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.semantics.Role
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.FuseMotion
import io.github.matiyaaa.fuse.ui.designsystem.theme.LocalFuseLook
import io.github.matiyaaa.fuse.ui.designsystem.theme.LocalFuseMotion
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Fuse's touch and mouse feedback, as an indication for any clickable:
 *
 * - **Press**: the element shrinks to [FuseMotion.pressScale] (about 96.5%) quickly and springs
 *   back with a hint of overshoot when let go, like a key. A tap shorter than the press-in still
 *   shows the whole press. Under Reduced motion nothing scales, so give such elements a [shape]:
 *   the pressed tint then answers on its own.
 * - **Hover** (desktop mouse): with a [shape], a soft highlight in the hover colour role fills it
 *   while the pointer is over the element.
 * - **Pressed tint**: with a [shape], the pressed colour role fills it while held.
 *
 * Drawn in the draw phase only: no layout, no recomposition, nothing allocated per frame.
 * Controller focus is not part of this; selection draws its own spark.
 *
 * [shape] null gives the press scale alone (for tiles and art that draw their own highlight).
 */
class PressIndication(
    private val shape: Shape? = null,
    private val scale: Boolean = true,
) : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode =
        PressNode(interactionSource, shape, scale)

    override fun equals(other: Any?) = other is PressIndication && other.shape == shape && other.scale == scale

    override fun hashCode() = (shape?.hashCode() ?: 0) * 31 + scale.hashCode()

    companion object {
        /** Press scale only. */
        val Default = PressIndication()
    }
}

/**
 * Adds Fuse's press and hover feedback to an element whose clickable shares [interactionSource].
 * See [PressIndication].
 */
fun Modifier.pressFeedback(interactionSource: InteractionSource, shape: Shape? = null, scale: Boolean = true): Modifier =
    indication(interactionSource, PressIndication(shape, scale))

/**
 * A soft highlight over the element (in [shape]) while the mouse is over it, and a slightly
 * stronger one while it is pressed, without any scaling: for rows, menu items and other large
 * surfaces that should not shrink. Uses the hover and pressed colour roles.
 */
fun Modifier.hoverHighlight(interactionSource: InteractionSource, shape: Shape = RectangleShape): Modifier =
    indication(interactionSource, PressIndication(shape, scale = false))

/**
 * Fuse's clickable: a click (and optional long click) with [PressIndication] built in, so every
 * touch and mouse target answers the same way. Pass a [shape] to get the hover and pressed
 * highlight as well (rows, buttons, chips); leave it null for tiles and art. [scale] false keeps
 * large surfaces from shrinking.
 *
 * Use this instead of `clickable(remember { MutableInteractionSource() }, null) { ... }`.
 */
@OptIn(ExperimentalFoundationApi::class)
fun Modifier.fuseClickable(
    enabled: Boolean = true,
    shape: Shape? = null,
    scale: Boolean = true,
    role: Role? = null,
    onClickLabel: String? = null,
    interactionSource: MutableInteractionSource? = null,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
): Modifier = combinedClickable(
    interactionSource = interactionSource,
    indication = PressIndication(shape, scale),
    enabled = enabled,
    onClickLabel = onClickLabel,
    role = role,
    onLongClick = onLongClick,
    onClick = onClick,
)

/**
 * How far [interactionSource] is pressed, 0 at rest to 1 held (briefly below 0 as it springs back),
 * for components that draw their own press, such as [io.github.matiyaaa.fuse.ui.designsystem.components.Tile].
 * Same timing as [PressIndication]: a quick tap still shows the whole press. Read the value in
 * drawing or layer lambdas so pressing never recomposes.
 */
@Composable
fun rememberPressProgress(interactionSource: InteractionSource): State<Float> {
    val progress = remember { Animatable(0f) }
    val motion by rememberUpdatedState(Fuse.motion)
    LaunchedEffect(interactionSource) {
        var held = 0
        var down: Job? = null
        interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is PressInteraction.Press -> {
                    held++
                    down = launch { progress.animateTo(1f, motion.pressIn()) }
                }
                is PressInteraction.Release, is PressInteraction.Cancel -> {
                    held = (held - 1).coerceAtLeast(0)
                    val pending = down
                    if (held == 0) launch {
                        pending?.join()
                        if (held == 0) progress.animateTo(0f, motion.pressOut())
                    }
                }
            }
        }
    }
    return progress.asState()
}

private class PressNode(
    private val source: InteractionSource,
    private val shape: Shape?,
    private val scaleOnPress: Boolean,
) : Modifier.Node(), DrawModifierNode, CompositionLocalConsumerModifierNode {

    private val press = Animatable(0f)
    private val hover = Animatable(0f)
    private var pressJob: Job? = null
    private var pressCount = 0
    private var hoverCount = 0

    private var outline: Outline? = null
    private var outlineSize = Size.Unspecified

    override val shouldAutoInvalidate: Boolean get() = false

    override fun onAttach() {
        coroutineScope.launch {
            source.interactions.collect { interaction ->
                val motion = currentValueOf(LocalFuseMotion)
                when (interaction) {
                    is PressInteraction.Press -> {
                        pressCount++
                        pressJob = launch { press.animateTo(1f, motion.pressIn()) { invalidateDraw() } }
                    }
                    is PressInteraction.Release, is PressInteraction.Cancel -> {
                        pressCount = (pressCount - 1).coerceAtLeast(0)
                        if (pressCount == 0) release(motion)
                    }
                    is HoverInteraction.Enter -> {
                        hoverCount++
                        launch { hover.animateTo(1f, motion.hover(entering = true)) { invalidateDraw() } }
                    }
                    is HoverInteraction.Exit -> {
                        hoverCount = (hoverCount - 1).coerceAtLeast(0)
                        if (hoverCount == 0) launch { hover.animateTo(0f, motion.hover(entering = false)) { invalidateDraw() } }
                    }
                }
            }
        }
    }

    private fun release(motion: FuseMotion) {
        val down = pressJob
        coroutineScope.launch {
            // A quick tap still shows the whole press before springing back.
            down?.join()
            if (pressCount == 0) press.animateTo(0f, motion.pressOut()) { invalidateDraw() }
        }
    }

    override fun onDetach() {
        pressCount = 0
        hoverCount = 0
        pressJob = null
    }

    override fun ContentDrawScope.draw() {
        val p = press.value
        val h = hover.value
        val motion = currentValueOf(LocalFuseMotion)
        val s = if (scaleOnPress) 1f - (1f - motion.pressScale) * p else 1f
        if (s == 1f) {
            drawContent()
            overlay(p, h)
        } else {
            scale(s) {
                this@draw.drawContent()
                overlay(p, h)
            }
        }
    }

    private fun DrawScope.overlay(p: Float, h: Float) {
        val shape = shape ?: return
        if (p <= 0.001f && h <= 0.001f) return
        if (outlineSize != size) {
            outline = shape.createOutline(size, layoutDirection, this)
            outlineSize = size
        }
        val colors = currentValueOf(LocalFuseLook).colors
        val o = outline ?: return
        if (h > 0.001f) drawOutline(o, colors.hover, alpha = h.coerceIn(0f, 1f))
        if (p > 0.001f) drawOutline(o, colors.pressed, alpha = p.coerceIn(0f, 1f))
    }
}
