package io.github.matiyaaa.fuse.ui.designsystem.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import io.github.matiyaaa.fuse.ui.designsystem.theme.Durations
import io.github.matiyaaa.fuse.ui.designsystem.theme.Easings
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.FuseMotion

enum class OverlayEdge { END, BOTTOM, CENTER }

/**
 * Hosts a panel above the current screen with a dimming scrim. Tapping the scrim calls [onDismiss].
 *
 * The scrim fades in while the panel arrives: from its [edge] it travels a short way and settles
 * from 98% (a side or bottom sheet), or it grows from 96% when centred. Leaving is quicker than
 * arriving: it fades at once and accelerates away, so closing a menu never holds the player up.
 * Under Reduced motion both are short fades. A side sheet's scrim deepens towards its edge, so the
 * sheet sits in its own shadow. Panels inside take the overlay level of the elevation family (see
 * [Panel]).
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
    val c = Fuse.colors
    Box(modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(motion.tween(Durations.BASE, Easings.Fade)),
            exit = fadeOut(motion.tween(Durations.FAST, Easings.Standard)),
        ) {
            val scrim = c.scrim
            val shade = c.ink.copy(alpha = if (c.isDark) 0.4f else 0.18f)
            Box(
                Modifier
                    .fillMaxSize()
                    .background(scrim)
                    .then(
                        when (edge) {
                            OverlayEdge.CENTER -> Modifier
                            else -> Modifier.drawWithCache {
                                val brush = if (edge == OverlayEdge.END) {
                                    Brush.horizontalGradient(0.45f to Color.Transparent, 1f to shade)
                                } else {
                                    Brush.verticalGradient(0.4f to Color.Transparent, 1f to shade)
                                }
                                onDrawBehind { drawRect(brush) }
                            }
                        },
                    )
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
            enter = overlayEnter(edge, motion),
            exit = overlayExit(edge, motion),
        ) {
            CompositionLocalProvider(LocalOverlaySurface provides true) {
                Box(content = content)
            }
        }
    }
}

/** Arrival: a short travel from the edge (or growth from the centre), on the entering curve. */
private fun overlayEnter(edge: OverlayEdge, motion: FuseMotion): EnterTransition {
    val fade = fadeIn(motion.tween(Durations.FAST, Easings.Fade))
    if (motion.reduced) return fade
    val move = motion.tween<androidx.compose.ui.unit.IntOffset>(Durations.BASE, Easings.Enter)
    val grow = motion.tween<Float>(Durations.BASE, Easings.Enter)
    return when (edge) {
        OverlayEdge.END ->
            slideInHorizontally(move) { it / 10 } + scaleIn(grow, initialScale = 0.98f, transformOrigin = TransformOrigin(1f, 0.5f)) + fade
        OverlayEdge.BOTTOM ->
            slideInVertically(move) { it / 8 } + scaleIn(grow, initialScale = 0.98f, transformOrigin = TransformOrigin(0.5f, 1f)) + fade
        OverlayEdge.CENTER ->
            scaleIn(motion.tween(Durations.BASE, Easings.Enter), initialScale = 0.96f) + fade
    }
}

/**
 * Departure: quicker than arrival and travelling less than it came. It moves on the leaving curve
 * (accelerating away) but fades from the first frame, so a closed menu never seems to hang.
 */
private fun overlayExit(edge: OverlayEdge, motion: FuseMotion): ExitTransition {
    val fade = fadeOut(motion.tween(Durations.FAST, Easings.Standard))
    if (motion.reduced) return fade
    val move = motion.tween<androidx.compose.ui.unit.IntOffset>(Durations.FAST, Easings.Exit)
    val shrink = motion.tween<Float>(Durations.FAST, Easings.Exit)
    return when (edge) {
        OverlayEdge.END ->
            slideOutHorizontally(move) { it / 16 } + scaleOut(shrink, targetScale = 0.99f, transformOrigin = TransformOrigin(1f, 0.5f)) + fade
        OverlayEdge.BOTTOM ->
            slideOutVertically(move) { it / 12 } + scaleOut(shrink, targetScale = 0.99f, transformOrigin = TransformOrigin(0.5f, 1f)) + fade
        OverlayEdge.CENTER ->
            scaleOut(shrink, targetScale = 0.97f) + fade
    }
}
