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
