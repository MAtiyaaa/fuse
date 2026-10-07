package io.github.matiyaaa.fuse.ui.designsystem.effects

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline

/**
 * Colour read while drawing, not while composing: for a colour that animates (a button's fill
 * fading as focus arrives, a tab's tint), each frame of the fade redraws the one layer it paints
 * instead of composing the whole component again. The pixels are exactly those of the colour taken
 * at composition: the same shape, filled the same way, with the colour as it is at that frame.
 */
fun Modifier.background(color: () -> Color, shape: Shape = RectangleShape): Modifier =
    if (shape === RectangleShape) {
        drawBehind { drawRect(color()) }
    } else {
        // The shape's outline is made once per size; only the colour is read each frame.
        drawWithCache {
            val outline = shape.createOutline(size, layoutDirection, this)
            onDrawBehind { drawOutline(outline, color()) }
        }
    }
