package io.github.matiyaaa.fuse.ui.designsystem.background

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import io.github.matiyaaa.fuse.model.BackgroundStyle
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Theme backgrounds drawn in code (no image assets), all original. When animation is allowed they
 * move slowly and are throttled to [fps] so an idle home screen costs almost nothing; otherwise they
 * are drawn once and never invalidate.
 */
@Composable
fun AmbientBackground(
    style: BackgroundStyle,
    accent: Color,
    modifier: Modifier = Modifier,
    animate: Boolean = Fuse.motion.ambient && Fuse.quality.animatedBackground,
    fps: Int = 30,
) {
    val colors = Fuse.colors
    var time by remember { mutableFloatStateOf(0f) }
    if (animate && style != BackgroundStyle.SOLID && style != BackgroundStyle.HERO) {
        LaunchedEffect(fps) {
            val frame = 1000L / fps
            var last = 0L
            val start = withFrameMillis { it }
            while (true) {
                withFrameMillis { now ->
                    if (now - last >= frame) {
                        time = (now - start) / 1000f
                        last = now
                    }
                }
            }
        }
    }
    Canvas(modifier.fillMaxSize().graphicsLayer()) {
        drawRect(colors.ink)
        when (style) {
            BackgroundStyle.WAVE -> wave(time, accent, colors.isDark)
            BackgroundStyle.AURORA -> aurora(time, accent)
            BackgroundStyle.ORBITAL -> orbital(time, accent)
            BackgroundStyle.GRID -> grid(time, accent, colors.isDark)
            BackgroundStyle.SOLID, BackgroundStyle.HERO -> vignette(accent, colors.isDark)
        }
    }
}

/** A room without art: an ember glow low on the left and a faint cool light high on the right, for depth. */
private fun DrawScope.vignette(accent: Color, dark: Boolean) {
    drawRect(
        Brush.radialGradient(
            listOf(accent.copy(alpha = 0.12f), accent.copy(alpha = 0.04f), Color.Transparent),
            center = Offset(size.width * 0.2f, size.height * 1.02f),
            radius = size.maxDimension * 0.8f,
        ),
    )
    val cool = lerp(accent, Color(0xFF3D7BFF), 0.85f)
    drawRect(
        Brush.radialGradient(
            listOf(cool.copy(alpha = if (dark) 0.07f else 0.035f), Color.Transparent),
            center = Offset(size.width * 0.9f, -size.height * 0.1f),
            radius = size.maxDimension * 0.6f,
        ),
    )
}

private fun DrawScope.wave(t: Float, accent: Color, dark: Boolean) {
    drawRect(
        Brush.verticalGradient(
            listOf(lerp(Color.Black, accent, if (dark) 0.10f else 0.04f), Color.Transparent),
        ),
    )
    val w = size.width
    val h = size.height
    for (layer in 0 until 4) {
        val path = Path()
        val amp = h * (0.05f + 0.02f * layer)
        val baseY = h * (0.58f + 0.035f * layer)
        val freq = 1.4f + 0.35f * layer
        val phase = t * (0.18f + 0.05f * layer) + layer * 1.7f
        var x = 0f
        path.moveTo(0f, baseY)
        while (x <= w) {
            val u = x / w
            val y = baseY + sin((u * freq * 2 * PI + phase).toFloat()) * amp * (0.6f + 0.4f * sin((u * PI).toFloat()))
            path.lineTo(x, y)
            x += w / 64f
        }
        drawPath(
            path,
            Brush.horizontalGradient(
                listOf(Color.Transparent, accent.copy(alpha = 0.45f - 0.08f * layer), Color.White.copy(alpha = 0.35f - 0.07f * layer), Color.Transparent),
            ),
            style = Stroke(width = (1.2f + layer * 0.6f) * density),
        )
    }
}

private fun DrawScope.aurora(t: Float, accent: Color) {
    val w = size.width
    val h = size.height
    val tints = listOf(accent, lerp(accent, Color(0xFF2A7BFF), 0.5f), lerp(accent, Color.White, 0.3f))
    for ((i, tint) in tints.withIndex()) {
        val cx = w * (0.3f + 0.25f * sin(t * 0.07f + i * 2.1f))
        val cy = h * (0.35f + 0.2f * cos(t * 0.05f + i * 1.3f))
        drawCircle(
            Brush.radialGradient(listOf(tint.copy(alpha = 0.22f), Color.Transparent), center = Offset(cx, cy), radius = size.maxDimension * 0.55f),
            radius = size.maxDimension * 0.55f,
            center = Offset(cx, cy),
        )
    }
}

private fun DrawScope.orbital(t: Float, accent: Color) {
    val center = Offset(size.width * 0.72f, size.height * 0.5f)
    val base = size.minDimension * 0.18f
    for (i in 0 until 6) {
        val rx = base * (1f + i * 0.55f)
        val ry = rx * 0.42f
        drawOval(
            accent.copy(alpha = 0.16f - i * 0.02f),
            topLeft = Offset(center.x - rx, center.y - ry),
            size = Size(rx * 2, ry * 2),
            style = Stroke(width = density),
        )
        val a = t * (0.25f - i * 0.03f) + i * 1.1f
        val dot = Offset(center.x + cos(a) * rx, center.y + sin(a) * ry)
        drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.8f), Color.Transparent), center = dot, radius = 10f * density), radius = 10f * density, center = dot)
    }
    drawCircle(
        Brush.radialGradient(listOf(accent.copy(alpha = 0.25f), Color.Transparent), center = center, radius = base * 1.4f),
        radius = base * 1.4f,
        center = center,
    )
}

private fun DrawScope.grid(t: Float, accent: Color, dark: Boolean) {
    val w = size.width
    val h = size.height
    val horizon = h * 0.52f
    val line = if (dark) accent.copy(alpha = 0.22f) else accent.copy(alpha = 0.18f)
    drawRect(
        Brush.verticalGradient(0f to Color.Transparent, 0.5f to accent.copy(alpha = 0.12f), 0.52f to accent.copy(alpha = 0.2f), 1f to Color.Transparent),
    )
    // Vertical lines converge on a vanishing point.
    val vp = Offset(w / 2, horizon)
    for (i in -12..12) {
        val xBottom = w / 2 + i * w / 8f
        drawLine(line, vp, Offset(xBottom, h), strokeWidth = density)
    }
    // Horizontal lines scroll toward the viewer.
    val scroll = (t * 0.25f) % 1f
    for (i in 0 until 12) {
        val z = (i + scroll) / 12f
        val y = horizon + (h - horizon) * z * z
        drawLine(line.copy(alpha = line.alpha * z), Offset(0f, y), Offset(w, y), strokeWidth = density)
    }
}
