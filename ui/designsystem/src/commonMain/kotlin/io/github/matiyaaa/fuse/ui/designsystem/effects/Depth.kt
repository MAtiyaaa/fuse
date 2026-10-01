package io.github.matiyaaa.fuse.ui.designsystem.effects

import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import io.github.matiyaaa.fuse.ui.designsystem.theme.Elevation
import io.github.matiyaaa.fuse.ui.designsystem.theme.ElevationLevel
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.Size

/**
 * The light edge every lifted surface shares: a hairline along the top of [shape] in white at
 * [alpha], fading out a third of the way down, so the surface reads as an object catching light
 * from above. It sits just inside the outline, so it shows whole on clipped surfaces too. The path
 * and brush are built once per size; [alpha] may change every frame.
 *
 * Usually reached through [elevated]; use it directly on surfaces that draw their own fill.
 */
fun Modifier.lightEdge(shape: Shape, alpha: () -> Float, width: Dp = Size.stroke): Modifier = drawWithCache {
    val path = Path().apply { addOutline(shape.createOutline(size, layoutDirection, this@drawWithCache)) }
    // Twice the width, centred on the outline and clipped to it: exactly [width] shows, inside.
    val stroke = Stroke(width.toPx() * 2)
    val brush = Brush.verticalGradient(
        0f to Color.White,
        0.14f to Color.White.copy(alpha = 0.45f),
        0.34f to Color.White.copy(alpha = 0.08f),
        0.6f to Color.Transparent,
    )
    onDrawWithContent {
        drawContent()
        val a = alpha()
        if (a > 0.001f) clipPath(path) { drawPath(path, brush, alpha = a.coerceAtMost(1f), style = stroke) }
    }
}

/** [lightEdge] with a fixed strength. */
fun Modifier.lightEdge(shape: Shape, alpha: Float, width: Dp = Size.stroke): Modifier = lightEdge(shape, { alpha }, width)

/**
 * A surface at an [ElevationLevel]: its fill, its shadow and its light edge, so panels, cards,
 * menus and dialogs all read as one family of objects. [fill] defaults to the level's surface role
 * (panel: surface, raised: surfaceRaised, overlay: surfaceOverlay). Content is clipped to [shape].
 *
 * Glass themes make the fill translucent and drop the shadow (a shadow under frosted glass looks
 * like a stain); the edge stays, which is what makes glass read as glass.
 */
@Composable
fun Modifier.elevated(level: ElevationLevel, shape: Shape, fill: Color? = null): Modifier {
    val c = Fuse.colors
    val glass = Fuse.look.glass
    val base = fill ?: when (level) {
        Elevation.overlay -> c.surfaceOverlay
        Elevation.raised -> c.surfaceRaised
        Elevation.room -> c.ink
        else -> c.surface
    }
    val shadowColor = c.shadow
    val edge = level.edgeAlpha(c.isDark)
    val glassy = glass.enabled && level != Elevation.room
    return this
        .graphicsLayer {
            this.shape = shape
            clip = true
            if (!glassy && level.shadow.value > 0f) {
                shadowElevation = level.shadow.toPx()
                spotShadowColor = shadowColor
                ambientShadowColor = shadowColor.copy(alpha = shadowColor.alpha * 0.5f)
            }
        }
        .background(if (glassy) base.copy(alpha = glass.surfaceOpacity) else base)
        .lightEdge(shape, edge)
}
