package io.github.matiyaaa.fuse.ui.shell.app

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import io.github.matiyaaa.fuse.ui.designsystem.components.FText
import io.github.matiyaaa.fuse.ui.designsystem.components.Spinner
import io.github.matiyaaa.fuse.ui.designsystem.effects.elevated
import io.github.matiyaaa.fuse.ui.designsystem.effects.rememberReveal
import io.github.matiyaaa.fuse.ui.designsystem.effects.reveal
import io.github.matiyaaa.fuse.ui.designsystem.input.InputLayer
import io.github.matiyaaa.fuse.ui.designsystem.input.LayerPriority
import io.github.matiyaaa.fuse.ui.designsystem.input.NavResult
import io.github.matiyaaa.fuse.ui.designsystem.media.ArtSlot
import io.github.matiyaaa.fuse.ui.designsystem.media.Artwork
import io.github.matiyaaa.fuse.ui.designsystem.media.GeneratedArt
import io.github.matiyaaa.fuse.ui.designsystem.shape.SquircleShape
import io.github.matiyaaa.fuse.ui.designsystem.theme.Durations
import io.github.matiyaaa.fuse.ui.designsystem.theme.Easings
import io.github.matiyaaa.fuse.ui.designsystem.theme.Elevation
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.Size
import io.github.matiyaaa.fuse.ui.designsystem.theme.Space
import io.github.matiyaaa.fuse.ui.designsystem.theme.flourishOn
import io.github.matiyaaa.fuse.ui.designsystem.theme.toColor
import io.github.matiyaaa.fuse.ui.shell.components.LocalTileMetrics

/**
 * The moment between Play and the emulator appearing, told as an occasion rather than a flash of
 * nothing: the game's room fills the screen and slowly settles, darkened from below like a
 * cinema, and its cover, system and name rise into the lower left one after another. The status
 * line says only what Fuse knows (it is starting the game), with a quiet spinner and no invented
 * progress. It is dark in every theme, since an emulator is about to take the screen.
 *
 * Input is held while it shows. Under Reduced motion and in Low Power Mode nothing settles or
 * rises: it simply fades in.
 */
@Composable
fun LaunchVeilContent(veil: LaunchVeil) {
    val c = Fuse.colors
    val motion = Fuse.motion
    val quality = Fuse.quality
    val flourish = motion.flourishOn(quality)
    val settle = remember { Animatable(if (flourish) 1.06f else 1f) }
    LaunchedEffect(veil) {
        if (flourish) settle.animateTo(1f, tween(motion.ms(Durations.DELIBERATE * 3), easing = Easings.Enter))
    }
    InputLayer(priority = LayerPriority.SYSTEM, modal = true) { NavResult.CONSUMED }
    val accent = veil.accent.toColor()
    val floor = c.artScrim.copy(alpha = 1f)
    val reveal = rememberReveal(veil)
    BoxWithConstraints(Modifier.fillMaxSize().background(floor)) {
        val compact = maxHeight < COMPACT_HEIGHT || maxWidth < COMPACT_WIDTH
        val blur = veil.artBlurred && quality.blur
        Artwork(
            veil.art,
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = settle.value
                    scaleY = settle.value
                }
                .then(if (blur) Modifier.blur(Space.x3, BlurredEdgeTreatment.Rectangle) else Modifier),
            focusX = veil.artFocusX,
            focusY = veil.artFocusY,
            loading = false,
            fallback = { GeneratedArt(veil.title, accent, slot = ArtSlot.HERO, showText = false) },
        )
        // The room dims from below and from the left, where the words sit, and glows faintly in the
        // game's colour, the way the home screen is lit. Covers used as the room are darker still.
        Spacer(
            Modifier.fillMaxSize().drawWithCache {
                val dim = floor.copy(alpha = if (veil.artBlurred) 0.5f else 0.18f)
                val below = Brush.verticalGradient(
                    0f to Color.Transparent,
                    0.38f to floor.copy(alpha = 0.12f),
                    0.66f to floor.copy(alpha = 0.62f),
                    1f to floor.copy(alpha = 0.94f),
                )
                val side = Brush.horizontalGradient(0f to floor.copy(alpha = 0.55f), 0.55f to Color.Transparent)
                val glow = Brush.radialGradient(
                    0f to accent.copy(alpha = 0.26f),
                    0.6f to accent.copy(alpha = 0.06f),
                    1f to Color.Transparent,
                    center = Offset(size.width * 0.1f, size.height * 1.05f),
                    radius = size.maxDimension * 0.6f,
                )
                onDrawBehind {
                    drawRect(dim)
                    drawRect(below)
                    drawRect(side)
                    drawRect(glow)
                }
            },
        )
        val gutter = if (compact) Space.gutterCompact else Space.gutter
        Row(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(start = gutter, end = gutter, bottom = if (compact) Space.xl else Space.x4),
            verticalAlignment = Alignment.Bottom,
        ) {
            // The game's own tile, as it was picked: its art, or its initials when it has none.
            val side = if (compact) Size.thumbL * 1.5f else LocalTileMetrics.current.icon
            val shape = SquircleShape.fraction(Fuse.geometry.tileCornerFraction)
            Box(
                Modifier
                    .reveal(reveal, 0)
                    .size(side)
                    .elevated(Elevation.tileFocused, shape),
            ) {
                Artwork(
                    veil.cover,
                    Modifier.fillMaxSize(),
                    backdrop = true,
                    fallback = { GeneratedArt(veil.title, accent, slot = ArtSlot.ICON) },
                )
            }
            Spacer(Modifier.width(if (compact) Space.l else Space.xl))
            Column(Modifier.weight(1f)) {
                if (veil.system != null) {
                    Row(
                        Modifier.reveal(reveal, 1).padding(bottom = Space.s),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(Size.dot).background(accent, CircleShape))
                        Spacer(Modifier.width(Space.s))
                        FText(veil.system.uppercase(), Fuse.type.overline, color = c.onArtMuted, maxLines = 1)
                    }
                }
                val titleStyle = if (compact) Fuse.type.display else Fuse.type.hero
                val title: @Composable () -> Unit = {
                    FText(veil.title, titleStyle, color = c.onArt, maxLines = 2, modifier = Modifier.widthIn(max = TITLE_WIDTH))
                }
                Box(Modifier.reveal(reveal, 2)) {
                    if (veil.logo != null) {
                        Artwork(
                            veil.logo,
                            Modifier.height(if (compact) LOGO_HEIGHT_COMPACT else LOGO_HEIGHT).fillMaxWidth(0.5f),
                            contentScale = ContentScale.Fit,
                            focusX = 0f,
                            focusY = 1f,
                            loading = false,
                            fallback = title,
                        )
                    } else {
                        title()
                    }
                }
                Row(
                    Modifier.reveal(reveal, 3).padding(top = if (compact) Space.m else Space.l),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Spinner(size = Size.iconS, color = c.onArt)
                    Spacer(Modifier.width(Space.s))
                    FText("Starting", Fuse.type.label, color = c.onArtMuted, maxLines = 1)
                }
            }
        }
    }
}

/** Short (handheld) or narrow (phone) screens set the veil tighter and smaller. */
private val COMPACT_HEIGHT = 560.dp
private val COMPACT_WIDTH = 600.dp

/** A long title wraps before it reaches across the art. */
private val TITLE_WIDTH = 760.dp

/** The game's logo, where it replaces the written title. */
private val LOGO_HEIGHT = 120.dp
private val LOGO_HEIGHT_COMPACT = 80.dp
