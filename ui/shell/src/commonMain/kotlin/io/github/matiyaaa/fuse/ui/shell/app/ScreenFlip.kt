package io.github.matiyaaa.fuse.ui.shell.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.fuseline.Spring
import io.github.matiyaaa.fuse.ui.fuseline.Swap
import io.github.matiyaaa.fuse.ui.fuseline.SwapTransform
import io.github.matiyaaa.fuse.ui.fuseline.fadeIn
import io.github.matiyaaa.fuse.ui.fuseline.fadeOut
import io.github.matiyaaa.fuse.ui.fuseline.scaleIn
import io.github.matiyaaa.fuse.ui.fuseline.scaleOut
import io.github.matiyaaa.fuse.ui.fuseline.slideInVertically
import io.github.matiyaaa.fuse.ui.fuseline.slideOutVertically

/**
 * One screen of a two-screen device, as Fuse (the menus here) and Flipped (the menus on the other
 * screen) trade places. The menus travel between the screens rather than blinking: on the screen
 * they leave they sink toward the other screen, shrinking a little as they go, while what takes
 * their place rises from the opposite edge; on the screen they reach they arrive from the side
 * facing the other screen and settle with a soft spring. [below] is true on the lower screen, so
 * both screens move the same way, as one picture.
 */
@Composable
fun ScreenFlip(
    menusHere: Boolean,
    below: Boolean,
    modifier: Modifier = Modifier,
    menus: @Composable () -> Unit,
    other: @Composable () -> Unit,
) {
    val motion = Fuse.motion
    // The settle: quick, a breath of overshoot, then still.
    val glide = if (motion.reduced) motion.tween(io.github.matiyaaa.fuse.ui.fuseline.Durations.FAST) else Spring(dampingRatio = 0.86f, stiffness = 210f)
    val fade = motion.tween(io.github.matiyaaa.fuse.ui.fuseline.Durations.DELIBERATE)
    val quick = motion.tween(io.github.matiyaaa.fuse.ui.fuseline.Durations.SLOW)
    Swap(
        menusHere,
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
        label = "screenFlip",
        transitionSpec = {
            // Which way the menus travel on this screen: toward the other screen when leaving,
            // from it when arriving. The other screen is above the lower one, and below the upper.
            val arriving = targetState
            // +1 moves down the screen, -1 up.
            val towardOther = if (below) -1 else 1
            val dir = if (arriving) -towardOther else towardOther
            if (arriving) {
                // The menus come in from the edge nearest the other screen; what was here steps back.
                SwapTransform(
                    slideInVertically(glide) { h -> -dir * h * 2 / 5 } + fadeIn(fade) +
                        scaleIn(glide, initialScale = 0.9f, transformOrigin = TransformOrigin(0.5f, if (towardOther > 0) 1f else 0f)),
                    fadeOut(quick) + scaleOut(quick, targetScale = 0.94f) + slideOutVertically(quick) { h -> dir * h / 6 },
                    sizeTransform = null,
                )
            } else {
                // The menus sink toward the other screen; the screen's own content rises in behind them.
                SwapTransform(
                    fadeIn(fade) + scaleIn(glide, initialScale = 0.96f) + slideInVertically(glide) { h -> -dir * h / 8 },
                    slideOutVertically(quick) { h -> dir * h * 2 / 5 } + fadeOut(quick) +
                        scaleOut(quick, targetScale = 0.88f, transformOrigin = TransformOrigin(0.5f, if (towardOther > 0) 1f else 0f)),
                    sizeTransform = null,
                )
            }
        },
    ) { here ->
        Box(Modifier.fillMaxSize()) {
            if (here) menus() else other()
        }
    }
}
