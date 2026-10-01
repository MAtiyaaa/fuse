package io.github.matiyaaa.fuse.ui.designsystem.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
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
    .drawWithContent {
        drawContent()
        val h = size.height
        if (h <= 0f) return@drawWithContent
        val ts = topStrength().coerceIn(0f, 1f)
        val bs = bottomStrength().coerceIn(0f, 1f)
        if (ts <= 0f && bs <= 0f) return@drawWithContent
        val t = (top.toPx() / h).coerceIn(0f, 0.5f)
        val b = (bottom.toPx() / h).coerceIn(0f, 0.5f)
        drawRect(
            Brush.verticalGradient(
                0f to Color.Black.copy(alpha = 1f - ts),
                t to Color.Black,
                1f - b to Color.Black,
                1f to Color.Black.copy(alpha = 1f - bs),
            ),
            blendMode = BlendMode.DstIn,
        )
    }

/**
 * Softens the left and right edges of a horizontally scrolling row, only where more of it is out of
 * view ([start], [end]), so a row of buttons shows that it goes on.
 */
fun Modifier.fadingEdgesHorizontal(start: Boolean, end: Boolean, width: Dp = 40.dp): Modifier =
    if (!start && !end) this else this
        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            val w = size.width
            if (w <= 0f) return@drawWithContent
            val f = (width.toPx() / w).coerceIn(0f, 0.5f)
            drawRect(
                Brush.horizontalGradient(
                    0f to if (start) Color.Transparent else Color.Black,
                    f to Color.Black,
                    1f - f to Color.Black,
                    1f to if (end) Color.Transparent else Color.Black,
                ),
                blendMode = BlendMode.DstIn,
            )
        }
