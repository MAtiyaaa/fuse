package io.github.matiyaaa.fuse.ui.designsystem.background

import io.github.matiyaaa.fuse.ui.designsystem.effects.drawDitheredRect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.PI
import kotlin.math.sin

/**
 * Fii's room: fine silver pinstripes running across a soft white (or, at night, faint blue lines on
 * deep slate), lit from the top, with a pale band of the accent along the bottom where Fii's bar
 * sits. A few glassy bubbles rise slowly through it, swaying as they go, each with a bright rim and
 * a catch of light at its top left. Calm enough to sit behind everything.
 */
internal class FiiScene(look: SceneLook) : Scene(look) {
    private val night = look.dark
    private val stripe = if (night) look.accent.copy(alpha = 0.05f * k) else Color(0xFF9AA6B2).copy(alpha = 0.16f * k)
    private val stripeLight = if (night) Color.White.copy(alpha = 0.015f * k) else Color.White.copy(alpha = 0.7f * k)
    private var top: Brush = Brush.verticalGradient(listOf(Color.Transparent, Color.Transparent))
    private var bottom: Brush = Brush.verticalGradient(listOf(Color.Transparent, Color.Transparent))
    private val corner = Glow(look.accent)
    private val sheen = Glow(Color.White)

    private val body = Glow(look.accent)
    private val rim = look.accent.copy(alpha = ((if (night) 0.5f else 0.6f) * k).coerceAtMost(1f))
    private val rimLight = Color.White.copy(alpha = ((if (night) 0.4f else 1f) * k).coerceAtMost(1f))
    private val spark = Color.White.copy(alpha = (if (night) 0.45f else 0.95f) * k)
    private var ring = Stroke(1f)

    private class Bubble(val x: Float, val r: Float, val speed: Float, val phase: Float, val sway: Float, val swayRate: Float)
    private val bubbles: List<Bubble> = Seeded(0x0F11).let { s ->
        List(BUBBLES) {
            Bubble(
                x = s.between(0.04f, 0.96f),
                r = s.between(6f, 22f),
                speed = s.between(0.012f, 0.03f),
                phase = s.next(),
                sway = s.between(6f, 18f),
                swayRate = s.between(0.25f, 0.6f),
            )
        }
    }

    override val fps: Int = 24

    override fun DrawScope.build() {
        top = Brush.verticalGradient(
            listOf(Color.White.copy(alpha = (if (night) 0.05f else 0.75f) * k), Color.Transparent),
            endY = size.height * 0.55f,
        )
        bottom = Brush.verticalGradient(
            0f to Color.Transparent,
            0.7f to look.accent.copy(alpha = (if (night) 0.07f else 0.08f) * k),
            1f to look.accent.copy(alpha = (if (night) 0.14f else 0.16f) * k),
            startY = size.height * 0.72f,
            endY = size.height,
        )
        ring = Stroke(width = 1.6f * density * fit)
    }

    override fun DrawScope.paint(t: Float) {
        val w = size.width
        val h = size.height
        drawDitheredRect(top)
        // Pinstripes: a thin shadowed line with a lit line under it, as on brushed silver.
        val gap = 6f * density
        var y = gap
        while (y < h) {
            drawLine(stripe, Offset(0f, y), Offset(w, y), strokeWidth = density * 0.75f)
            if (!night) drawLine(stripeLight, Offset(0f, y + density), Offset(w, y + density), strokeWidth = density * 0.75f)
            y += gap
        }
        drawDitheredRect(bottom)
        sheen.draw(this, w * 0.5f, -h * 0.12f, w * 0.7f, h * 0.38f, alpha = (if (night) 0.05f else 0.5f) * k)
        corner.draw(this, w * 0.1f, h * 1.02f, size.maxDimension * 0.42f, alpha = (if (night) 0.12f else 0.1f) * k)

        // Bubbles rise from below the bottom edge to above the top, then start again.
        val scale = density * fit
        val travel = h + 60f * scale
        for (b in bubbles) {
            val r = b.r * scale
            val p = wrap(b.phase + t * b.speed, 1f)
            val cy = h + 30f * scale - p * travel
            val cx = b.x * w + sin((t * b.swayRate + b.phase * 6f) * PI.toFloat()) * b.sway * scale
            // Fade in at the bottom and out near the top, and stay quiet behind titles.
            val fade = smooth(0f, 0.12f, p) * (1f - smooth(0.78f, 1f, p)) * (0.45f + 0.55f * calm(cx, cy))
            if (fade <= 0.01f) continue
            body.draw(this, cx, cy, r, alpha = (if (night) 0.1f else 0.18f) * fade * k)
            drawCircle(rim, radius = r, center = Offset(cx, cy), style = ring, alpha = fade)
            drawArc(
                rimLight, startAngle = 200f, sweepAngle = 70f, useCenter = false,
                topLeft = Offset(cx - r * 0.78f, cy - r * 0.78f), size = androidx.compose.ui.geometry.Size(r * 1.56f, r * 1.56f),
                style = ring, alpha = fade,
            )
            drawCircle(spark, radius = r * 0.16f, center = Offset(cx - r * 0.42f, cy - r * 0.46f), alpha = fade)
        }
    }

    private companion object {
        const val BUBBLES = 18
    }
}
