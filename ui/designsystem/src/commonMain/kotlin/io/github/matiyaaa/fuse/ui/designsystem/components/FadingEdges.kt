package io.github.matiyaaa.fuse.ui.designsystem.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Softens the top and bottom edges of a vertically scrolling area, so rows slide out of view under
 * headers and the hint line instead of being cut off.
 */
fun Modifier.fadingEdges(top: Dp = 24.dp, bottom: Dp = 40.dp): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val h = size.height
        if (h <= 0f) return@drawWithContent
        val t = (top.toPx() / h).coerceIn(0f, 0.5f)
        val b = (bottom.toPx() / h).coerceIn(0f, 0.5f)
        drawRect(
            Brush.verticalGradient(
                0f to Color.Transparent,
                t to Color.Black,
                1f - b to Color.Black,
                1f to Color.Transparent,
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
