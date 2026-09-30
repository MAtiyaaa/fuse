package io.github.matiyaaa.fuse.ui.designsystem.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import io.github.matiyaaa.fuse.ui.designsystem.theme.Durations
import io.github.matiyaaa.fuse.ui.designsystem.theme.Easings
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse

enum class OverlayEdge { END, BOTTOM, CENTER }

/**
 * Hosts a panel above the current screen with a dimming scrim. Tapping the scrim calls [onDismiss].
 * The panel slides from [edge] (or scales in when centred) and the scrim fades, both short enough
 * that opening a menu never feels like waiting.
 */
@Composable
fun Overlay(
    visible: Boolean,
    onDismiss: () -> Unit,
    edge: OverlayEdge = OverlayEdge.END,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val motion = Fuse.motion
    Box(modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(motion.fade(Durations.FAST)),
            exit = fadeOut(motion.fade(Durations.FAST)),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Fuse.colors.scrim)
                    .clickable(remember { MutableInteractionSource() }, null, onClick = onDismiss),
            )
        }
        val align = when (edge) {
            OverlayEdge.END -> Alignment.CenterEnd
            OverlayEdge.BOTTOM -> Alignment.BottomCenter
            OverlayEdge.CENTER -> Alignment.Center
        }
        AnimatedVisibility(
            visible = visible,
            modifier = Modifier.align(align),
            enter = when (edge) {
                OverlayEdge.END -> slideInHorizontally(motion.tween(Durations.BASE, Easings.Enter)) { it / 3 } + fadeIn(motion.fade(Durations.FAST))
                OverlayEdge.BOTTOM -> slideInVertically(motion.tween(Durations.BASE, Easings.Enter)) { it / 3 } + fadeIn(motion.fade(Durations.FAST))
                OverlayEdge.CENTER -> scaleIn(motion.tween(Durations.BASE, Easings.Enter), initialScale = if (motion.reduced) 1f else 0.94f) + fadeIn(motion.fade(Durations.FAST))
            },
            exit = when (edge) {
                OverlayEdge.END -> slideOutHorizontally(motion.tween(Durations.FAST, Easings.Exit)) { it / 4 } + fadeOut(motion.fade(Durations.INSTANT))
                OverlayEdge.BOTTOM -> slideOutVertically(motion.tween(Durations.FAST, Easings.Exit)) { it / 4 } + fadeOut(motion.fade(Durations.INSTANT))
                OverlayEdge.CENTER -> scaleOut(motion.tween(Durations.FAST, Easings.Exit), targetScale = if (motion.reduced) 1f else 0.96f) + fadeOut(motion.fade(Durations.INSTANT))
            },
        ) {
            Box(content = content)
        }
    }
}
