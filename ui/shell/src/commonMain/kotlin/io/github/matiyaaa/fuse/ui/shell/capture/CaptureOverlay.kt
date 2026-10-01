package io.github.matiyaaa.fuse.ui.shell.capture

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as GeoSize
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import io.github.matiyaaa.fuse.ui.designsystem.components.FText
import io.github.matiyaaa.fuse.ui.designsystem.components.IconBadge
import io.github.matiyaaa.fuse.ui.designsystem.effects.elevated
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcon
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.designsystem.shape.PillShape
import io.github.matiyaaa.fuse.ui.designsystem.theme.Aspect
import io.github.matiyaaa.fuse.ui.designsystem.theme.Durations
import io.github.matiyaaa.fuse.ui.designsystem.theme.Easings
import io.github.matiyaaa.fuse.ui.designsystem.theme.Elevation
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.Radius
import io.github.matiyaaa.fuse.ui.designsystem.theme.Size
import io.github.matiyaaa.fuse.ui.designsystem.theme.Space
import io.github.matiyaaa.fuse.ui.designsystem.theme.tabular
import io.github.matiyaaa.fuse.ui.shell.platform.CaptureResult
import kotlin.time.Clock
import kotlinx.coroutines.delay

/**
 * What a capture shows of its own: the 3, 2, 1 countdown in the middle of the screen, a soft flash
 * when a screenshot is taken, and a card in the corner with what was saved. All of it is gone from
 * the screen before a picture or a recording starts.
 */
@Composable
fun BoxScope.CaptureOverlay(capture: CaptureController) {
    val state = capture.state
    // While a capture is being taken the flash and the last capture's card are not drawn at all:
    // left to fade out they could still be on screen, faintly, in the next picture.
    val capturing = state == CaptureController.State.Capturing
    Countdown(state as? CaptureController.State.Countdown, Modifier.align(Alignment.Center))
    Flash(capture.shots, hidden = capturing)
    SavedCard(capture.saved, hidden = capturing, Modifier.align(Alignment.BottomStart).padding(start = Space.gutter, bottom = Size.hintHeight + Space.m))
}

@Composable
private fun Countdown(countdown: CaptureController.State.Countdown?, modifier: Modifier) {
    val c = Fuse.colors
    val motion = Fuse.motion
    // Shown and hidden at once, never faded: a fading countdown could still be on screen when the
    // picture is taken.
    if (countdown == null) return
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(COUNTDOWN_DISC)
                .elevated(Elevation.overlay, CircleShape, fill = c.surfaceOverlay.copy(alpha = 0.92f)),
            contentAlignment = Alignment.Center,
        ) {
            SecondRing(countdown.secondsLeft)
            AnimatedContent(
                targetState = countdown.secondsLeft,
                transitionSpec = {
                    if (motion.reduced) {
                        fadeIn(motion.fade(Durations.FAST)) togetherWith fadeOut(motion.fade(Durations.FAST))
                    } else {
                        // The next number grows into place as the last one swells and fades.
                        (fadeIn(motion.enter(Durations.FAST)) + scaleIn(motion.enter(Durations.BASE), initialScale = 0.72f)) togetherWith
                            (fadeOut(motion.exit(Durations.INSTANT)) + scaleOut(motion.exit(Durations.FAST), targetScale = 1.18f))
                    }
                },
                label = "countdown",
            ) { n ->
                FText(n.toString(), Fuse.type.hero.tabular(), color = c.text)
            }
        }
        Spacer(Modifier.height(Space.m))
        // What is coming, on a small pill of the same surface.
        Row(
            Modifier
                .elevated(Elevation.overlay, PillShape, fill = c.surfaceOverlay.copy(alpha = 0.92f))
                .padding(start = Space.m, end = Space.m + Space.xxs, top = Space.xs + Space.xxs, bottom = Space.xs + Space.xxs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.s),
        ) {
            FuseIcon(if (countdown.recording) FuseIcons.CircleDot else FuseIcons.Camera, size = Size.iconS, tint = if (countdown.recording) c.danger else c.text)
            FText(if (countdown.recording) "Recording starts" else "Screenshot", Fuse.type.label, color = c.text)
        }
    }
}

/** A thin accent ring that runs down once for each second of the countdown, on a faint track. */
@Composable
private fun SecondRing(second: Int) {
    val c = Fuse.colors
    val sweep = remember(second) { Animatable(1f) }
    LaunchedEffect(second) { sweep.animateTo(0f, tween(1_000, easing = LinearEasing)) }
    val track = c.text.copy(alpha = 0.1f)
    val accent = c.accent
    Spacer(
        Modifier.fillMaxSize().padding(Space.s).drawWithCache {
            val stroke = Size.track.toPx()
            val inset = stroke / 2
            val arc = GeoSize(size.width - stroke, size.height - stroke)
            val trackStroke = Stroke(stroke)
            val arcStroke = Stroke(stroke, cap = StrokeCap.Round)
            onDrawBehind {
                drawArc(track, 0f, 360f, false, Offset(inset, inset), arc, style = trackStroke)
                drawArc(accent, -90f, 360f * sweep.value, false, Offset(inset, inset), arc, style = arcStroke)
            }
        },
    )
}

/** A brief white flash after each screenshot, like a shutter. Left out under Reduced motion. */
@Composable
private fun Flash(shots: Int, hidden: Boolean) {
    if (Fuse.motion.reduced) return
    val flash = Fuse.colors.onArt
    val alpha = remember { Animatable(0f) }
    LaunchedEffect(shots) {
        if (shots == 0) return@LaunchedEffect
        alpha.snapTo(0.32f)
        alpha.animateTo(0f, tween(340, easing = Easings.Standard))
    }
    if (alpha.value > 0f && !hidden) Box(Modifier.fillMaxSize().graphicsLayer { this.alpha = alpha.value }.background(flash))
}

/**
 * The capture just saved, with a small picture of it and where it went, on the overlay surface (the
 * same object as a toast, so it never reads as part of the screen behind). [hidden] draws nothing at
 * once, even mid-animation, while the next capture is taken; the card still rises in afterwards.
 */
@Composable
private fun SavedCard(saved: CaptureResult?, hidden: Boolean, modifier: Modifier) {
    val c = Fuse.colors
    val motion = Fuse.motion
    val rise = if (motion.reduced) 0 else 1
    // Keeps the last card on screen while it leaves.
    val shown = remember { arrayOfNulls<CaptureResult>(1) }
    if (saved != null) shown[0] = saved
    AnimatedVisibility(
        visible = saved != null,
        modifier = modifier,
        enter = fadeIn(motion.enter(Durations.FAST)) + slideInVertically(motion.enter(Durations.SLOW)) { it * rise / 3 },
        exit = fadeOut(motion.exit(Durations.FAST)) + slideOutVertically(motion.exit(Durations.BASE)) { it * rise / 4 },
    ) {
        val card = shown[0] ?: return@AnimatedVisibility
        if (hidden) return@AnimatedVisibility
        val shape = RoundedCornerShape(Fuse.geometry.panel)
        Row(
            Modifier
                .elevated(Elevation.overlay, shape)
                .padding(Space.s)
                .padding(end = Space.m),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val thumb = RoundedCornerShape((Fuse.geometry.panel - Space.s).coerceAtLeast(Radius.xs))
            Box(
                Modifier.width(THUMB_WIDTH).aspectRatio(Aspect.SCREENSHOT).clip(thumb).background(c.surfaceDim),
                contentAlignment = Alignment.Center,
            ) {
                card.preview?.let { Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                if (card.video) IconBadge(FuseIcons.Play, tint = c.onArt, background = c.artScrim, size = Size.chipCompact)
            }
            Spacer(Modifier.width(Space.m))
            Column(verticalArrangement = Arrangement.spacedBy(Space.xxs)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s - Space.xxs)) {
                    FuseIcon(FuseIcons.CircleCheck, size = Size.iconS, tint = c.success)
                    FText(if (card.video) "Recording saved" else "Screenshot saved", Fuse.type.bodyStrong, color = c.text, maxLines = 1)
                }
                FText(card.place, Fuse.type.caption, color = c.textMuted, maxLines = 1)
            }
        }
    }
}

/** The countdown's disc: large enough for the numeral and its ring, small enough to stay calm. */
private val COUNTDOWN_DISC = Space.x5 + Space.xxl

/** The saved card's picture, 16:9. */
private val THUMB_WIDTH = Space.x5 + Space.xxl

/** How long the recording has run, "0:42", ticking each second; null while not recording. */
@Composable
fun rememberRecordingTime(capture: CaptureController?): String? {
    val since = (capture?.state as? CaptureController.State.Recording)?.since ?: return null
    val now by produceState(Clock.System.now().toEpochMilliseconds(), since) {
        while (true) {
            value = Clock.System.now().toEpochMilliseconds()
            delay(1_000 - value % 1_000)
        }
    }
    return elapsedText(now - since)
}

/** "0:42", "12:05": minutes and seconds of a recording. */
fun elapsedText(ms: Long): String {
    val s = (ms / 1_000).coerceAtLeast(0)
    return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
}
