package io.github.matiyaaa.fuse.ui.designsystem.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.matiyaaa.fuse.ui.designsystem.theme.Durations
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse

/*
 * Fuse's two signature marks, from the brand art: the spark (a hot core in a soft halo, the end of a
 * lit fuse) and the fuse line (a hairline that warms into the accent and ends in the spark). They
 * mark where things are happening: the focused tile, the active tab, a running job, the selected row.
 */

/**
 * The spark at [center]: a core of [radius] that runs from near white through a warm tint to
 * [accent], inside a halo about three times as wide. [intensity] (0..1) fades it in and out.
 */
fun DrawScope.drawSpark(center: Offset, radius: Float, accent: Color, intensity: Float = 1f) {
    val i = intensity.coerceIn(0f, 1f)
    if (i <= 0f || radius <= 0f) return
    val halo = radius * 3.2f
    drawCircle(
        Brush.radialGradient(
            0f to accent.copy(alpha = 0.9f * i),
            0.3f to accent.copy(alpha = 0.45f * i),
            0.6f to accent.copy(alpha = 0.13f * i),
            1f to accent.copy(alpha = 0f),
            center = center,
            radius = halo,
        ),
        radius = halo,
        center = center,
    )
    drawCircle(
        Brush.radialGradient(
            0f to lerp(accent, Color.White, 0.92f).copy(alpha = i),
            0.42f to lerp(accent, Color.White, 0.48f).copy(alpha = i),
            0.8f to accent.copy(alpha = i),
            1f to accent.copy(alpha = 0.6f * i),
            center = center,
            radius = radius,
        ),
        radius = radius,
        center = center,
    )
}

/**
 * Progress as a lit fuse: a faint track, a fill that warms from a glow into [color], and the spark
 * at its head while it burns. With [progress] null the length isn't known, and a spark with a short
 * glowing tail runs along the track instead (it rests in the middle when motion is reduced).
 */
@Composable
fun FuseLine(progress: Float?, modifier: Modifier = Modifier, height: Dp = 3.dp, color: Color = Fuse.colors.accent) {
    val c = Fuse.colors
    val motion = Fuse.motion
    if (progress != null) {
        val v by animateFloatAsState(progress.coerceIn(0f, 1f), motion.tween(Durations.SLOW), label = "fuse")
        Canvas(modifier.height(height)) {
            val h = size.height
            val r = CornerRadius(h / 2)
            drawRoundRect(c.text.copy(alpha = 0.12f), cornerRadius = r)
            val w = size.width * v
            if (w <= 0f) return@Canvas
            drawRoundRect(
                Brush.horizontalGradient(
                    0f to color.copy(alpha = 0.55f),
                    1f to color,
                    startX = 0f,
                    endX = w.coerceAtLeast(1f),
                ),
                size = size.copy(width = w.coerceAtLeast(h)),
                cornerRadius = r,
            )
            // Burning while it runs; a finished fuse is simply full.
            val burning = (1f - ((v - 0.97f) / 0.03f).coerceIn(0f, 1f)) * (v / 0.03f).coerceIn(0f, 1f)
            drawSpark(Offset(w, h / 2), h * 1.15f, color, burning * 0.9f)
        }
    } else {
        val phase = if (motion.reduced) {
            0.5f
        } else {
            val p by rememberInfiniteTransition(label = "fuse run").animateFloat(
                0f, 1f, infiniteRepeatable(tween(1300, easing = LinearEasing), RepeatMode.Restart), label = "phase",
            )
            p
        }
        Canvas(modifier.height(height)) {
            val h = size.height
            drawRoundRect(c.text.copy(alpha = 0.12f), cornerRadius = CornerRadius(h / 2))
            val tail = size.width * 0.28f
            val x = -tail * 0.2f + (size.width + tail * 0.4f) * phase
            drawRoundRect(
                Brush.horizontalGradient(0f to color.copy(alpha = 0f), 1f to color, startX = x - tail, endX = x),
                topLeft = Offset((x - tail).coerceAtLeast(0f), 0f),
                size = size.copy(width = (x - (x - tail).coerceAtLeast(0f)).coerceIn(0f, size.width)),
                cornerRadius = CornerRadius(h / 2),
            )
            if (x in 0f..size.width) drawSpark(Offset(x, h / 2), h * 1.15f, color, 0.9f)
        }
    }
}

/**
 * A hairline that runs out from where the content starts and fades away to the right, for section
 * titles that lead into what follows. [color] is the line at its brightest.
 */
fun Modifier.fuseRule(color: Color, startPadding: Dp = 0.dp): Modifier = drawBehind {
    val y = size.height / 2
    val start = startPadding.toPx()
    if (start >= size.width) return@drawBehind
    drawLine(
        Brush.horizontalGradient(0f to color, 1f to color.copy(alpha = 0f), startX = start, endX = size.width),
        Offset(start, y),
        Offset(size.width, y),
        strokeWidth = 1.dp.toPx(),
    )
}
