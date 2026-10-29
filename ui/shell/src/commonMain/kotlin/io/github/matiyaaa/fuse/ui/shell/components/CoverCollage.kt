package io.github.matiyaaa.fuse.ui.shell.components

import androidx.compose.foundation.background
import io.github.matiyaaa.fuse.ui.designsystem.effects.ditheredBackground
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Dp
import io.github.matiyaaa.fuse.ui.designsystem.effects.lightEdge
import io.github.matiyaaa.fuse.ui.designsystem.media.ArtSlot
import io.github.matiyaaa.fuse.ui.designsystem.media.Artwork
import io.github.matiyaaa.fuse.ui.designsystem.media.GeneratedArt
import io.github.matiyaaa.fuse.ui.designsystem.shape.SquircleShape
import io.github.matiyaaa.fuse.ui.designsystem.theme.Aspect
import io.github.matiyaaa.fuse.ui.designsystem.theme.Elevation
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.Space
import io.github.matiyaaa.fuse.ui.designsystem.theme.toColor
import io.github.matiyaaa.fuse.ui.shell.store.GameCard

/**
 * A list of games as a collage: [background] (else the first game's background art), dimmed, with
 * the covers of the first three games fanned over it. Used by Home's channels, collections and the
 * Library's All page (its places, its stage and the end of each shelf), so a set of games looks the
 * same wherever Fuse shows one.
 *
 * The covers are small objects of the same family as tiles: continuous corners, a contact shadow at
 * the raised level and a light edge along their top, so the fan reads as cases stood on a shelf.
 * [fanAt] places the fan (the middle of the foot by default; a wide card keeps it to one side, clear
 * of its words), and [tint] colours the background where there is no art (the first game's own
 * colour otherwise). With no games at all the background alone is drawn, in [tint].
 */
@Composable
fun CoverCollage(
    games: List<GameCard>,
    height: Dp,
    background: Any? = null,
    fanAt: Alignment = Alignment.BottomCenter,
    tint: Color? = null,
    coverShare: Float = COVER_HEIGHT,
    /** Drawn over the background and under the covers: a card's own wash behind its words. */
    under: (@Composable androidx.compose.foundation.layout.BoxScope.() -> Unit)? = null,
) {
    val first = games.firstOrNull()
    val c = Fuse.colors
    val corner = Fuse.geometry.tileCornerFraction * COLLAGE_CORNER
    val shape = remember(corner) { SquircleShape.fraction(corner) }
    val shadow = c.shadow
    Box(Modifier.fillMaxSize()) {
        // Without art of its own, a colour falling to the night. It sits under the dimming and the
        // covers, so the full generated picture (radial lights over the whole card) cost about a
        // third of every frame of a screen of these for nothing seen.
        val wash: @Composable () -> Unit = {
            val accent = tint ?: first?.accent?.toColor() ?: c.accent
            Box(Modifier.fillMaxSize().ditheredBackground(Brush.linearGradient(listOf(lerp(ART_NIGHT, accent, 0.55f), lerp(ART_NIGHT, accent, 0.1f)))))
        }
        val art = background ?: first?.let { it.art.hero ?: it.art.grid ?: it.art.boxart }
        if (art == null) wash() else Artwork(
            art,
            Modifier.fillMaxSize(),
            focusX = first?.art?.heroFocusX ?: 0.5f,
            focusY = first?.art?.heroFocusY ?: 0.5f,
            fallback = wash,
        )
        // Dimmed toward the bottom, where the covers stand, so they lift off the background.
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(0f to Color.Black.copy(alpha = TOP_DIM), 1f to c.artScrim.copy(alpha = c.artScrim.alpha * FLOOR_DIM)),
            ),
        )
        under?.invoke(this)
        if (first == null) return@Box
        val cover = height * coverShare
        val fan = games.take(3)
        // The fan's own width: the front cover and the two behind it leaning out on either side.
        val reach = cover * Aspect.BOX + cover * SPREAD * 2
        // Kept off the card's edge when it stands to one side.
        val inset = if (fanAt == Alignment.BottomCenter || fanAt == Alignment.Center || fanAt == Alignment.TopCenter) 0.dp else Space.l
        Box(Modifier.align(fanAt).padding(bottom = Space.m, start = inset, end = inset).width(reach), contentAlignment = Alignment.BottomCenter) {
            // Back to front, so the first game sits on top in the middle.
            val slots = listOf(1 to -1f, 2 to 1f, 0 to 0f).filter { it.first < fan.size }
            for ((index, side) in slots) {
                val g = fan[index]
                Box(
                    Modifier
                        .offset(x = cover * SPREAD * side)
                        .height(if (index == 0) cover else cover * BACK_SCALE)
                        .aspectRatio(Aspect.BOX)
                        .graphicsLayer {
                            rotationZ = TILT * side
                            shadowElevation = Elevation.raised.shadow.toPx()
                            spotShadowColor = shadow
                            ambientShadowColor = shadow.copy(alpha = shadow.alpha * 0.5f)
                            this.shape = shape
                            clip = true
                        }
                        .lightEdge(shape, Elevation.tile.edge)
                        .align(Alignment.BottomCenter),
                ) {
                    Artwork(g.art.boxart ?: g.art.grid ?: g.art.square ?: g.art.icon, Modifier.fillMaxSize(), fallback = {
                        // Only the front cover names its game; the ones behind would show cut-off words.
                        GeneratedArt(g.title, g.accent.toColor(), slot = ArtSlot.BOX, label = g.platformShort, showText = index == 0)
                    })
                }
            }
        }
    }
}

/**
 * The night a collage's colour falls to where it has no art: dark in every theme, since covers,
 * their shadows and white words stand on it (as they do on any game's art).
 */
internal val ART_NIGHT = Color(0xFF0B0C11)

/** Corners of the fanned covers against the theme's tile corner. */
private const val COLLAGE_CORNER = 0.45f

/** The front cover's height, as a share of the collage's. */
const val COVER_HEIGHT = 0.66f

/** The covers behind are a little smaller, so the front one leads. */
private const val BACK_SCALE = 0.88f

/** How far the covers behind fan out, as a share of the front cover's height. */
private const val SPREAD = 0.46f

/** Degrees the covers behind lean out. */
private const val TILT = 6f

/** The background's dimming at the top and, as a share of the art scrim, at the bottom. */
private const val TOP_DIM = 0.1f
private const val FLOOR_DIM = 0.76f
