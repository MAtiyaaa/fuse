package io.github.matiyaaa.fuse.ui.shell.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import io.github.matiyaaa.fuse.ui.designsystem.media.ArtSlot
import io.github.matiyaaa.fuse.ui.designsystem.media.Artwork
import io.github.matiyaaa.fuse.ui.designsystem.media.GeneratedArt
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.Space
import io.github.matiyaaa.fuse.ui.designsystem.theme.toColor
import io.github.matiyaaa.fuse.ui.shell.store.GameCard

/**
 * A list of games as a collage: [background] (else the first game's background art), dimmed, with
 * the covers of the first three games fanned over it. Used by Home's channels and by collections.
 */
@Composable
fun CoverCollage(games: List<GameCard>, height: Dp, background: Any? = null) {
    val first = games.first()
    val shape = io.github.matiyaaa.fuse.ui.designsystem.shape.SquircleShape.fraction(Fuse.geometry.tileCornerFraction * 0.45f)
    Box(Modifier.fillMaxSize()) {
        Artwork(
            background ?: first.art.hero ?: first.art.grid ?: first.art.boxart,
            Modifier.fillMaxSize(),
            focusX = first.art.heroFocusX,
            focusY = first.art.heroFocusY,
            fallback = { GeneratedArt(first.title, first.accent.toColor(), slot = ArtSlot.WIDE, showText = false) },
        )
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.1f), 1f to Color.Black.copy(alpha = 0.55f))))
        val cover = height * 0.66f
        val fan = games.take(3)
        Box(Modifier.align(Alignment.BottomCenter).padding(bottom = Space.m)) {
            // Back to front, so the first game sits on top in the middle.
            val slots = listOf(1 to -1f, 2 to 1f, 0 to 0f).filter { it.first < fan.size }
            for ((index, side) in slots) {
                val g = fan[index]
                Box(
                    Modifier
                        .offset(x = cover * 0.46f * side)
                        .height(if (index == 0) cover else cover * 0.88f)
                        .aspectRatio(0.72f)
                        .graphicsLayer { rotationZ = 6f * side; shadowElevation = 12f; this.shape = shape; clip = true }
                        .align(Alignment.BottomCenter),
                ) {
                    Artwork(g.art.boxart ?: g.art.grid ?: g.art.icon, Modifier.fillMaxSize(), fallback = {
                        // Only the front cover names its game; the ones behind would show cut-off words.
                        GeneratedArt(g.title, g.accent.toColor(), slot = ArtSlot.BOX, label = g.platformShort, showText = index == 0)
                    })
                }
            }
        }
    }
}
