package io.github.matiyaaa.fuse.ui.shell.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import io.github.matiyaaa.fuse.model.BorderMode
import io.github.matiyaaa.fuse.model.BorderShape
import io.github.matiyaaa.fuse.model.BorderStyle
import io.github.matiyaaa.fuse.model.PlatformId
import io.github.matiyaaa.fuse.ui.designsystem.components.FText
import io.github.matiyaaa.fuse.ui.designsystem.media.Artwork
import io.github.matiyaaa.fuse.ui.designsystem.shape.PillShape
import io.github.matiyaaa.fuse.ui.designsystem.shape.SquircleShape
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.Space
import io.github.matiyaaa.fuse.ui.designsystem.theme.toColor

/** Dynamic borders per platform (Global -> Platform), resolved once for all tiles on screen. */
class TileBorders(private val byPlatform: Map<PlatformId, BorderStyle> = emptyMap()) {
    fun of(platform: PlatformId): BorderStyle = byPlatform[platform] ?: None

    companion object {
        val None = BorderStyle()
    }
}

val LocalTileBorders = staticCompositionLocalOf { TileBorders() }

/**
 * Draws a game tile's dynamic border inside the tile's clip: a frame in the platform's colour
 * (optionally a gradient), the user's own frame image, and an optional system badge.
 */
@Composable
fun BoxScope.TileBorder(style: BorderStyle, accent: Color, cornerFraction: Float, badge: String?) {
    if (style.mode == BorderMode.OFF) return
    val color = style.accent?.toColor() ?: accent
    val frame = style.customFramePath.takeIf { style.mode == BorderMode.CUSTOM }
    if (frame != null) {
        Artwork(frame, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds, fadeIn = false)
    } else {
        val shape = when (style.shape) {
            BorderShape.ROUNDED -> SquircleShape.fraction(cornerFraction)
            BorderShape.SQUARE -> SquircleShape.fraction(0f)
            BorderShape.CIRCLE -> SquircleShape.fraction(0.5f)
        }
        Box(
            Modifier.fillMaxSize().drawWithCache {
                val width = 3.dp.toPx()
                val outline = shape.createOutline(size, layoutDirection, this)
                val brush = if (style.gradient) {
                    Brush.linearGradient(
                        listOf(color, color.copy(alpha = 0.35f), color),
                        start = Offset.Zero,
                        end = Offset(size.width, size.height),
                    )
                } else {
                    Brush.linearGradient(listOf(color, color))
                }
                onDrawWithContent {
                    drawContent()
                    // Centred on the clip edge, so exactly the inner half shows and follows the corners.
                    drawOutline(outline, brush, style = Stroke(width * 2))
                }
            },
        )
    }
    if (style.logoOverlay && badge != null) {
        Box(
            Modifier
                .align(Alignment.BottomStart)
                .padding(Space.s)
                .clip(PillShape)
                .background(color.copy(alpha = 0.85f))
                .padding(horizontal = Space.s, vertical = 2.dp),
        ) {
            FText(badge, Fuse.type.caption, color = Fuse.colors.ink, maxLines = 1)
        }
    }
}
