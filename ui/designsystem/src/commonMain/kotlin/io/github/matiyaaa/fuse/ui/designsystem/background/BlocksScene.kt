package io.github.matiyaaa.fuse.ui.designsystem.background

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import io.github.matiyaaa.fuse.ui.designsystem.effects.drawDitheredRect
import kotlin.math.floor
import kotlin.math.sin
import kotlin.random.Random

/**
 * Rounded squares scattered over a dark room, as a handheld's menu draws them: some only outlined,
 * some filled, a few large, each breathing in and out on its own beat, and one lit in the accent
 * that moves to another place every few seconds, fading out and in. The second colour (or a soft
 * lavender) draws the squares. Everything is laid out once per size; a frame only reads it.
 */
internal class BlocksScene(look: SceneLook) : Scene(look) {
    private val tone = look.second?.let { lerp(it, Color.White, 0.35f) } ?: Color(0xFFB4B8F0)
    private val lit = look.accent
    override val fps: Int = 20

    private val count = 46
    private val x = FloatArray(count)
    private val y = FloatArray(count)
    private val side = FloatArray(count)
    private val filled = BooleanArray(count)
    private val phase = FloatArray(count)
    private val beat = FloatArray(count)
    private var cell = 0f
    private var stroke = Stroke()
    private var litStroke = Stroke()
    private var glowStroke = Stroke()

    /** The big outlined squares the lit one moves between. */
    private val stops = IntArray(6)

    override fun DrawScope.build() {
        val w = size.width
        val h = size.height
        cell = minOf(w, h) / 6.5f
        val rnd = Random(3_640_811)
        val cols = (w / cell).toInt() + 1
        val rows = (h / cell).toInt() + 1
        // Each block on a cell of a coarse grid, a little off it, so the pattern reads as placed.
        var bigs = 0
        for (i in 0 until count) {
            val cx = rnd.nextInt(cols)
            val cy = rnd.nextInt(rows)
            val big = i < 10
            val s = if (big) cell * (0.9f + rnd.nextFloat() * 0.25f) else cell * (0.3f + rnd.nextFloat() * 0.25f)
            x[i] = cx * cell + (cell - s) * rnd.nextFloat()
            y[i] = cy * cell + (cell - s) * rnd.nextFloat()
            side[i] = s
            filled[i] = !big && rnd.nextFloat() < 0.55f
            phase[i] = rnd.nextFloat() * 6.283f
            beat[i] = 0.25f + rnd.nextFloat() * 0.5f
            if (big && bigs < stops.size) stops[bigs++] = i
        }
        stroke = Stroke(maxOf(cell * 0.045f, density * 1.5f))
        litStroke = Stroke(maxOf(cell * 0.07f, density * 2.5f))
        glowStroke = Stroke(maxOf(cell * 0.22f, density * 6f))
    }

    override fun DrawScope.paint(t: Float) {
        // A low light from the top, so the room isn't flat.
        drawDitheredRect(androidx.compose.ui.graphics.Brush.verticalGradient(0f to tone.copy(alpha = 0.06f * k), 0.6f to Color.Transparent))
        for (i in 0 until count) {
            val breath = 0.55f + 0.45f * sin(phase[i] + t * beat[i])
            val a = ((if (filled[i]) 0.34f else 0.42f) * breath * k).coerceIn(0f, 1f)
            val s = side[i]
            val r = CornerRadius(s * 0.2f)
            if (filled[i]) {
                drawRoundRect(tone, Offset(x[i], y[i]), Size(s, s), r, alpha = a)
            } else {
                drawRoundRect(tone, Offset(x[i], y[i]), Size(s, s), r, alpha = a, style = stroke)
            }
        }
        // The lit square: on one big square for a while, then fading over to the next.
        val hold = 4.5f
        val step = floor(t / hold).toInt()
        val within = t / hold - step
        val at = stops[((step % stops.size) + stops.size) % stops.size]
        val fade = when {
            within < 0.12f -> within / 0.12f
            within > 0.88f -> (1f - within) / 0.12f
            else -> 1f
        }.coerceIn(0f, 1f)
        val s = side[at]
        val corner = CornerRadius(s * 0.2f)
        drawRoundRect(lit, Offset(x[at], y[at]), Size(s, s), corner, alpha = 0.18f * fade * k, style = glowStroke)
        drawRoundRect(lit, Offset(x[at], y[at]), Size(s, s), corner, alpha = (0.95f * fade).coerceAtMost(1f), style = litStroke)
    }
}
