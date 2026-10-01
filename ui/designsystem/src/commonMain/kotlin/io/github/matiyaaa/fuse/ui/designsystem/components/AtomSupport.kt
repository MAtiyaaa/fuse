package io.github.matiyaaa.fuse.ui.designsystem.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.State
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.matiyaaa.fuse.model.CornerFamily
import io.github.matiyaaa.fuse.ui.designsystem.shape.PillShape
import io.github.matiyaaa.fuse.ui.designsystem.theme.Durations
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.FuseColors
import kotlinx.coroutines.launch

/*
 * Small shared pieces of the atoms in this package: press and hover state, the focus ring drawn just
 * outside an element, the lit edge every surface shares and the theme's control shape. They stay
 * internal so the public surface is the components themselves.
 */

/** Hover (desktop pointer) and press, as 0..1 values that ease in and out. */
@Stable
internal class AtomPress(private val hover: State<Float>, private val press: State<Float>) {
    val hovered: Float get() = hover.value
    val pressed: Float get() = press.value
}

/**
 * Follows [interaction]'s hover and press. Pressing lands quickly and lets go on a soft spring, so
 * a tap feels like a key going down and coming back up. Under Reduced motion both are short fades.
 */
@Composable
internal fun rememberAtomPress(interaction: InteractionSource, enabled: Boolean = true): AtomPress {
    val motion = Fuse.motion
    val isHovered by interaction.collectIsHoveredAsState()
    val isPressed by interaction.collectIsPressedAsState()
    val hover = animateFloatAsState(
        if (isHovered && enabled) 1f else 0f,
        motion.tween(Durations.FAST),
        label = "hover",
    )
    val press = animateFloatAsState(
        if (isPressed && enabled) 1f else 0f,
        when {
            motion.reduced -> snap()
            isPressed -> tween(Durations.INSTANT)
            else -> spring(dampingRatio = 0.6f, stiffness = 600f)
        },
        label = "press",
    )
    return remember(hover, press) { AtomPress(hover, press) }
}

/** Laid over an element under the mouse pointer. */
internal fun FuseColors.hoverOverlay(): Color = text.copy(alpha = if (isDark) 0.06f else 0.05f)

/** Laid over an element while it is pressed. */
internal fun FuseColors.pressOverlay(): Color = text.copy(alpha = if (isDark) 0.10f else 0.08f)

/** Quiet fills for wells and tracks: the text colour at a low strength, so every theme gets one. */
internal fun FuseColors.quietFill(alpha: Float = 0.08f): Color = text.copy(alpha = alpha)

/**
 * The top surface of the elevation family: overlays (dialogs, menus, sheets) sit a step lighter than
 * the panels they cover in dark themes, and on plain white in light ones.
 */
internal fun FuseColors.overlaySurface(): Color = if (isDark) lerp(surfaceRaised, text, 0.035f) else surface

/** The theme's control shape: a pill, except in sharp themes, where controls keep their corners. */
@Composable
@ReadOnlyComposable
internal fun controlShape(): Shape =
    if (Fuse.geometry.family == CornerFamily.SHARP) RoundedCornerShape(Fuse.geometry.control) else PillShape

/** [controlShape] grown by [gap] all round, so a ring around a control stays concentric. */
@Composable
@ReadOnlyComposable
internal fun controlRingShape(gap: Dp = RING_GAP): Shape =
    if (Fuse.geometry.family == CornerFamily.SHARP) RoundedCornerShape(Fuse.geometry.control + gap) else PillShape

/** Space between an element and its focus ring, and the ring's width. */
internal val RING_GAP: Dp = 3.dp
internal val RING_WIDTH: Dp = 2.dp

/**
 * A focus ring just outside the element, so focusing never changes the layout. As [progress] rises
 * the ring settles outward from the element's edge to [gap] away and fades in. [ringShape] is the
 * ring's own shape at its full size (see [controlRingShape]). The path is built once per size.
 * Place it before any clip.
 */
internal fun Modifier.ringOutside(
    progress: () -> Float,
    color: Color,
    ringShape: Shape,
    gap: Dp = RING_GAP,
    width: Dp = RING_WIDTH,
): Modifier = drawWithCache {
    val g = gap.toPx()
    val w = width.toPx()
    val grow = g + w / 2f
    val outer = Size(size.width + grow * 2, size.height + grow * 2)
    val path = Path().apply { addOutline(ringShape.createOutline(outer, layoutDirection, this@drawWithCache)) }
    val stroke = Stroke(w)
    onDrawWithContent {
        drawContent()
        val p = progress().coerceIn(0f, 1f)
        if (p > 0.01f) {
            // Settle outward: start hugging the edge, end a gap away. Scaling the cached path keeps
            // this free of allocations while it animates.
            val sx = atomMix((size.width + w) / outer.width, 1f, p)
            val sy = atomMix((size.height + w) / outer.height, 1f, p)
            translate(-grow, -grow) {
                scale(sx, sy, pivot = Offset(outer.width / 2, outer.height / 2)) {
                    drawPath(path, color, alpha = p, style = stroke)
                }
            }
        }
    }
}

/**
 * The lit edge every surface shares: a hairline all round in [rest], brightening to [top] along the
 * top edge and fading back within [reach], so the surface reads as an object catching light from
 * above. It sits just inside the outline, so it shows whole on clipped surfaces too.
 */
internal fun Modifier.litEdge(
    shape: Shape,
    top: Color,
    rest: Color,
    reach: Dp = 40.dp,
    width: Dp = 1.dp,
): Modifier = drawWithCache {
    val path = Path().apply { addOutline(shape.createOutline(size, layoutDirection, this@drawWithCache)) }
    val stop = if (size.height > 0f) (reach.toPx() / size.height).coerceIn(0.05f, 0.6f) else 0.3f
    val brush = Brush.verticalGradient(0f to top, stop to rest, 1f to rest)
    // Twice the width, centred on the outline and clipped to it: exactly [width] shows, inside.
    val stroke = Stroke(width.toPx() * 2)
    onDrawWithContent {
        drawContent()
        clipPath(path) { drawPath(path, brush, style = stroke) }
    }
}

/** Linear blend of two floats. */
internal fun atomMix(a: Float, b: Float, t: Float): Float = a + (b - a) * t

/**
 * True inside an [Overlay]'s panel: panels there take the overlay level of the elevation family (a
 * lighter fill, a deeper shadow) without every dialog having to ask for it. A panel resets it for its
 * own content, so cards inside a dialog stay cards.
 */
internal val LocalOverlaySurface = staticCompositionLocalOf { false }

/**
 * An indicator that glides between places and stretches as it goes: its leading edge runs ahead on
 * a stiff spring and its trailing edge follows on a softer one, so it reads as moving, not blinking
 * from one place to another. Positions are in the caller's units (an index, a fraction); the
 * indicator spans [target] to [target] + [span]. Under Reduced motion it moves at once.
 */
@Stable
internal class StretchGlide(private val startState: State<Float>, private val endState: State<Float>) {
    val start: Float get() = startState.value
    val end: Float get() = endState.value
}

@Composable
internal fun rememberStretchGlide(target: Float, span: Float = 1f): StretchGlide {
    val motion = Fuse.motion
    val start = remember { Animatable(target) }
    val end = remember { Animatable(target + span) }
    LaunchedEffect(target, span, motion.reduced) {
        if (motion.reduced) {
            start.snapTo(target)
            end.snapTo(target + span)
            return@LaunchedEffect
        }
        val forward = target + span >= end.value
        val lead = spring<Float>(dampingRatio = 0.86f, stiffness = 1400f)
        val trail = spring<Float>(dampingRatio = 0.9f, stiffness = 650f)
        launch { start.animateTo(target, if (forward) trail else lead) }
        launch { end.animateTo(target + span, if (forward) lead else trail) }
    }
    return remember(start, end) { StretchGlide(start.asState(), end.asState()) }
}
