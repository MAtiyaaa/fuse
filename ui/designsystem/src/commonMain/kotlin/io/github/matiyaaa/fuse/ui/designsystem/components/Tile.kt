package io.github.matiyaaa.fuse.ui.designsystem.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import io.github.matiyaaa.fuse.model.FocusStyle
import io.github.matiyaaa.fuse.ui.designsystem.shape.SquircleShape
import io.github.matiyaaa.fuse.ui.designsystem.shape.squirclePath
import io.github.matiyaaa.fuse.ui.designsystem.theme.Durations
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.Size

/**
 * The building block of every browsable surface: a piece of art that lifts toward you when selected.
 *
 * Selection is shown three ways at once so it never depends on colour alone: the tile scales up, a
 * short "spark" bar appears underneath, and (per theme) a tinted glow or an outline ring. When a tile
 * gains focus a single band of light sweeps across it; the sweep is skipped in reduced or minimal
 * motion and in Low Power Mode.
 *
 * [glow] tints the lift shadow (usually the art's or platform's colour).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Tile(
    selected: Boolean,
    modifier: Modifier = Modifier,
    shape: Shape = SquircleShape.fraction(Fuse.geometry.tileCornerFraction),
    glow: Color = Fuse.colors.accent,
    cornerFraction: Float = Fuse.geometry.tileCornerFraction,
    showSpark: Boolean = true,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val motion = Fuse.motion
    val look = Fuse.look
    val colors = Fuse.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()

    val lift by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = motion.focusSpring(),
        label = "lift",
    )
    val press by animateFloatAsState(if (pressed) 1f else 0f, motion.tween(Durations.INSTANT), label = "press")
    val sweep = remember { Animatable(1f) }
    val sweepOn = motion.sweep && Fuse.quality.animatedBackground
    LaunchedEffect(selected, sweepOn) {
        if (selected && sweepOn) {
            sweep.snapTo(0f)
            sweep.animateTo(1f, tween(Durations.SWEEP, easing = LinearEasing))
        } else {
            sweep.snapTo(1f)
        }
    }

    val style = look.focusStyle
    val ring = style == FocusStyle.RING || look.highContrastFocus
    val scale = 1f + (motion.focusScale - 1f) * lift - 0.03f * press

    val clickable = if (onClick != null || onLongClick != null) {
        Modifier.combinedClickable(
            interactionSource = interaction,
            indication = null,
            onClick = { onClick?.invoke() },
            onLongClick = onLongClick,
        )
    } else Modifier

    Box(
        modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                if (style == FocusStyle.GLOW) {
                    // A soft halo in the tile's own colour, darkened so it lifts the tile without
                    // washing the row below in colour.
                    shadowElevation = (4f + 12f * lift) * density
                    spotShadowColor = lerp(glow, Color.Black, 0.45f)
                    ambientShadowColor = lerp(glow, Color.Black, 0.7f).copy(alpha = 0.35f)
                } else {
                    shadowElevation = (4f + 10f * lift) * density
                }
                this.shape = shape
                clip = false
            }
            .drawWithContent {
                drawContent()
                val w = size.width
                val h = size.height
                val corner = minOf(w, h) * cornerFraction
                // Upper light edge: a hairline that makes the tile read as a raised object.
                val edge = squirclePath(w, h, corner, 0.6f)
                drawPath(
                    edge,
                    Brush.verticalGradient(
                        0f to Color.White.copy(alpha = 0.16f + 0.14f * lift),
                        0.35f to Color.White.copy(alpha = 0.04f),
                        1f to Color.Transparent,
                    ),
                    style = Stroke(width = 1.dp.toPx()),
                )
                if (sweep.value < 1f && lift > 0.3f) {
                    val band = w * 0.55f
                    val x = -band + (w + band * 2) * sweep.value
                    clipPath(edge) {
                        drawRect(
                            Brush.linearGradient(
                                0f to Color.Transparent,
                                0.5f to Color.White.copy(alpha = 0.22f),
                                1f to Color.Transparent,
                                start = Offset(x - band, 0f),
                                end = Offset(x + band * 0.4f, h),
                            ),
                        )
                    }
                }
                if (ring && lift > 0.01f) {
                    val gap = 3.dp.toPx() * lift
                    val stroke = Size.focusStroke.toPx()
                    translate(-gap - stroke / 2, -gap - stroke / 2) {
                        drawPath(
                            squirclePath(w + (gap + stroke / 2) * 2, h + (gap + stroke / 2) * 2, corner + gap, 0.6f),
                            colors.focus.copy(alpha = lift),
                            style = Stroke(stroke),
                        )
                    }
                }
                if (showSpark && lift > 0.01f && style != FocusStyle.RING) {
                    val barW = Size.sparkWidth.toPx() * (if (style == FocusStyle.BAR) 1.4f else 1f) * lift
                    val barH = Size.sparkHeight.toPx() * (if (style == FocusStyle.BAR) 1.3f else 1f)
                    val y = h + 7.dp.toPx()
                    drawRoundRect(
                        color = colors.accent.copy(alpha = lift),
                        topLeft = Offset((w - barW) / 2, y),
                        size = androidx.compose.ui.geometry.Size(barW, barH),
                        cornerRadius = CornerRadius(barH / 2),
                    )
                }
            }
            .clip(shape)
            .then(clickable),
        content = content,
    )
}
