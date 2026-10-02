package io.github.matiyaaa.fuse.ui.designsystem.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.matiyaaa.fuse.ui.designsystem.theme.Durations
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse

/**
 * Softens the top and bottom edges of a vertically scrolling area, so rows slide out of view under
 * headers and the hint line instead of being cut off.
 */
fun Modifier.fadingEdges(top: Dp = 24.dp, bottom: Dp = 40.dp): Modifier = fadingEdges(top, bottom, { 1f }, { 1f })

/**
 * [fadingEdges] that follows [state]: an edge softens only while there is more to scroll that way,
 * and eases in and out as it starts or stops being true, so the first row of a list at rest is never
 * faded.
 */
@Composable
fun Modifier.fadingEdges(state: ScrollableState, top: Dp = 24.dp, bottom: Dp = 40.dp): Modifier {
    val motion = Fuse.motion
    val t by animateFloatAsState(if (state.canScrollBackward) 1f else 0f, motion.tween(Durations.FAST), label = "fadeTop")
    val b by animateFloatAsState(if (state.canScrollForward) 1f else 0f, motion.tween(Durations.FAST), label = "fadeBottom")
    return fadingEdges(top, bottom, { t }, { b })
}

private fun Modifier.fadingEdges(top: Dp, bottom: Dp, topStrength: () -> Float, bottomStrength: () -> Float): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithCache {
        // Each edge is a gradient built once per size and erased out of the content (DstOut) at the
        // edge's current strength, so scrolling and easing an edge in never allocate a brush.
        val t = top.toPx().coerceAtMost(size.height / 2)
        val b = bottom.toPx().coerceAtMost(size.height / 2)
        val topBrush = Brush.verticalGradient(0f to Color.Black, 1f to Color.Transparent, startY = 0f, endY = t)
        val bottomBrush = Brush.verticalGradient(0f to Color.Transparent, 1f to Color.Black, startY = size.height - b, endY = size.height)
        onDrawWithContent {
            drawContent()
            if (size.height <= 0f) return@onDrawWithContent
            val ts = topStrength().coerceIn(0f, 1f)
            val bs = bottomStrength().coerceIn(0f, 1f)
            if (ts > 0f && t > 0f) drawRect(topBrush, size = Size(size.width, t), alpha = ts, blendMode = BlendMode.DstOut)
            if (bs > 0f && b > 0f) {
                drawRect(bottomBrush, topLeft = Offset(0f, size.height - b), size = Size(size.width, b), alpha = bs, blendMode = BlendMode.DstOut)
            }
        }
    }

/**
 * Softens the left and right edges of a horizontally scrolling row, only where more of it is out of
 * view ([start], [end]), so a row of buttons shows that it goes on.
 */
fun Modifier.fadingEdgesHorizontal(start: Boolean, end: Boolean, width: Dp = 40.dp): Modifier =
    if (!start && !end) this else this
        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithCache {
            val w = size.width
            val f = if (w > 0f) (width.toPx() / w).coerceIn(0f, 0.5f) else 0f
            val brush = Brush.horizontalGradient(
                0f to if (start) Color.Transparent else Color.Black,
                f to Color.Black,
                1f - f to Color.Black,
                1f to if (end) Color.Transparent else Color.Black,
            )
            onDrawWithContent {
                drawContent()
                if (w > 0f) drawRect(brush, blendMode = BlendMode.DstIn)
            }
        }
