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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.matiyaaa.fuse.ui.designsystem.components.FText
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcon
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.Size
import io.github.matiyaaa.fuse.ui.designsystem.theme.Space
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
    Countdown(state as? CaptureController.State.Countdown, Modifier.align(Alignment.Center))
    Flash(capture.shots)
    SavedCard(capture.saved, Modifier.align(Alignment.BottomStart).padding(start = Space.gutter, bottom = Size.hintHeight + Space.m))
}

@Composable
private fun Countdown(countdown: CaptureController.State.Countdown?, modifier: Modifier) {
    val c = Fuse.colors
    val reduced = Fuse.motion.reduced
    // Shown and hidden at once, never faded: a fading countdown could still be on screen when the
    // picture is taken.
    if (countdown == null) return
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(132.dp)
                .shadow(24.dp, CircleShape, clip = false)
                .clip(CircleShape)
                .background(c.ink.copy(alpha = 0.72f))
                .border(1.dp, Color.White.copy(alpha = 0.12f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            SecondRing(countdown.secondsLeft)
            AnimatedContent(
                targetState = countdown.secondsLeft,
                transitionSpec = {
                    if (reduced) {
                        fadeIn(tween(120)) togetherWith fadeOut(tween(120))
                    } else {
                        (fadeIn(tween(180)) + scaleIn(tween(240), initialScale = 0.72f)) togetherWith
                            (fadeOut(tween(140)) + scaleOut(tween(180), targetScale = 1.18f))
                    }
                },
                label = "countdown",
            ) { n ->
                FText(n.toString(), Fuse.type.hero.copy(fontSize = 64.sp, lineHeight = 64.sp), color = c.text)
            }
        }
        Spacer(Modifier.height(Space.m))
        Row(
            Modifier.clip(RoundedCornerShape(50)).background(c.ink.copy(alpha = 0.72f)).padding(horizontal = Space.m, vertical = Space.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.xs),
        ) {
            FuseIcon(if (countdown.recording) FuseIcons.CircleDot else FuseIcons.Camera, size = 16.dp, tint = c.text.copy(alpha = 0.85f))
            FText(if (countdown.recording) "Recording starts" else "Screenshot", Fuse.type.label, color = c.text.copy(alpha = 0.85f))
        }
    }
}

/** A thin accent ring that runs down once for each second of the countdown. */
@Composable
private fun SecondRing(second: Int) {
    val c = Fuse.colors
    val sweep = remember(second) { Animatable(1f) }
    LaunchedEffect(second) { sweep.animateTo(0f, tween(1_000, easing = LinearEasing)) }
    Canvas(Modifier.fillMaxSize().padding(6.dp)) {
        val stroke = 3.dp.toPx()
        val inset = stroke / 2
        val arc = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke)
        drawArc(c.text.copy(alpha = 0.1f), 0f, 360f, false, Offset(inset, inset), arc, style = Stroke(stroke))
        drawArc(c.accent, -90f, 360f * sweep.value, false, Offset(inset, inset), arc, style = Stroke(stroke, cap = StrokeCap.Round))
    }
}

/** A brief white flash after each screenshot, like a shutter. Left out under Reduced motion. */
@Composable
private fun Flash(shots: Int) {
    if (Fuse.motion.reduced) return
    val alpha = remember { Animatable(0f) }
    LaunchedEffect(shots) {
        if (shots == 0) return@LaunchedEffect
        alpha.snapTo(0.32f)
        alpha.animateTo(0f, tween(340))
    }
    if (alpha.value > 0f) Box(Modifier.fillMaxSize().graphicsLayer { this.alpha = alpha.value }.background(Color.White))
}

/** The capture just saved, with a small picture of it and where it went. */
@Composable
private fun SavedCard(saved: CaptureResult?, modifier: Modifier) {
    val c = Fuse.colors
    // Keeps the last card on screen while it slides away.
    val shown = remember { arrayOfNulls<CaptureResult>(1) }
    if (saved != null) shown[0] = saved
    AnimatedVisibility(
        visible = saved != null,
        modifier = modifier,
        enter = fadeIn(tween(180)) + slideInVertically(tween(260)) { it / 3 },
        exit = fadeOut(tween(160)) + slideOutVertically(tween(200)) { it / 3 },
    ) {
        val card = shown[0] ?: return@AnimatedVisibility
        Row(
            Modifier
                .shadow(18.dp, RoundedCornerShape(Fuse.geometry.panel), clip = false)
                .clip(RoundedCornerShape(Fuse.geometry.panel))
                .background(c.surfaceRaised.copy(alpha = 0.96f))
                .border(1.dp, c.hairline, RoundedCornerShape(Fuse.geometry.panel))
                .padding(Space.s),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(width = 128.dp, height = 72.dp).clip(RoundedCornerShape(10.dp)).background(c.ink), contentAlignment = Alignment.Center) {
                card.preview?.let { Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                if (card.video) {
                    Box(Modifier.size(28.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.55f)), contentAlignment = Alignment.Center) {
                        FuseIcon(FuseIcons.Play, size = 14.dp, tint = Color.White)
                    }
                }
            }
            Spacer(Modifier.width(Space.m))
            Column(Modifier.padding(end = Space.s)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
                    FuseIcon(FuseIcons.CircleCheck, size = 16.dp, tint = c.success)
                    FText(if (card.video) "Recording saved" else "Screenshot saved", Fuse.type.label, color = c.text)
                }
                Spacer(Modifier.height(2.dp))
                FText(card.place, Fuse.type.caption, color = c.textMuted)
            }
        }
    }
}

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
