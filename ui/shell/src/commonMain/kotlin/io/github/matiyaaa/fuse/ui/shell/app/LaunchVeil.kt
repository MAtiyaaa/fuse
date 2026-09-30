package io.github.matiyaaa.fuse.ui.shell.app

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import io.github.matiyaaa.fuse.ui.designsystem.components.FText
import io.github.matiyaaa.fuse.ui.designsystem.components.Spinner
import io.github.matiyaaa.fuse.ui.designsystem.input.InputLayer
import io.github.matiyaaa.fuse.ui.designsystem.input.LayerPriority
import io.github.matiyaaa.fuse.ui.designsystem.input.NavResult
import io.github.matiyaaa.fuse.ui.designsystem.media.ArtSlot
import io.github.matiyaaa.fuse.ui.designsystem.media.Artwork
import io.github.matiyaaa.fuse.ui.designsystem.media.GeneratedArt
import io.github.matiyaaa.fuse.ui.designsystem.theme.Durations
import io.github.matiyaaa.fuse.ui.designsystem.theme.Easings
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.Space
import io.github.matiyaaa.fuse.ui.designsystem.theme.toColor

/**
 * The moment between Play and the emulator appearing: the game's art settles in full screen with its
 * name, so the handoff feels intentional instead of a flash of nothing. Input is held while it shows.
 */
@Composable
fun LaunchVeilContent(veil: LaunchVeil) {
    val settle = remember { Animatable(1.06f) }
    val motion = Fuse.motion
    LaunchedEffect(veil) {
        if (!motion.reduced) settle.animateTo(1f, motion.tween(Durations.DELIBERATE * 3, Easings.Enter))
    }
    InputLayer(priority = LayerPriority.SYSTEM, modal = true) { NavResult.CONSUMED }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Artwork(
            veil.art,
            Modifier.fillMaxSize().graphicsLayer { scaleX = settle.value; scaleY = settle.value },
            fallback = { GeneratedArt(veil.title, veil.accent.toColor(), slot = ArtSlot.HERO) },
        )
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.35f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.88f))))
        Column(Modifier.align(Alignment.BottomStart).padding(horizontal = Space.gutter, vertical = Space.x3)) {
            FText(veil.title, Fuse.type.hero, color = Color.White, maxLines = 2)
            Row(Modifier.padding(top = Space.m), horizontalArrangement = Arrangement.spacedBy(Space.m), verticalAlignment = Alignment.CenterVertically) {
                Spinner(size = 18.dp, color = Color.White)
                FText("Starting", Fuse.type.label, color = Color.White.copy(alpha = 0.75f))
            }
        }
    }
}
