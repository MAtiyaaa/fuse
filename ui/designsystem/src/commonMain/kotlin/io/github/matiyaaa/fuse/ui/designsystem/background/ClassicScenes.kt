package io.github.matiyaaa.fuse.ui.designsystem.background

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

// The backgrounds Fuse shipped with: the room, ribbons, aurora, orbits, the horizon grid, stars and
// pinstripes. Their look is unchanged; they now draw from cached brushes and paths.

/** A room without art: an ember glow low on the left and a faint cool light high on the right, for depth. */
internal class RoomScene(look: SceneLook) : Scene(look) {
    private val ember = Glow(look.accent)
    private val cool = Glow(look.second ?: lerp(look.accent, Color(0xFF3D7BFF), 0.85f))

    override fun DrawScope.paint(t: Float) {
        if (k <= 0f) return
        val w = size.width
        val h = size.height
        ember.draw(this, w * 0.2f, h * 1.02f, size.maxDimension * 0.85f, alpha = 0.13f * k)
        cool.draw(this, w * 0.9f, -h * 0.1f, size.maxDimension * 0.62f, alpha = (if (look.dark) 0.075f else 0.04f) * k)
    }
}

/** Four slow ribbons of light crossing the lower half, each a soft glow under a fine bright line. */
internal class WaveScene(look: SceneLook) : Scene(look) {
    private val accent = look.accent
    private val core = look.second ?: Color.White
    private val top = Brush.verticalGradient(
        listOf(lerp(Color.Black, accent, if (look.dark) 0.10f else 0.04f).copy(alpha = k.coerceAtMost(1f)), Color.Transparent),
    )
    private val paths = Array(LAYERS) { Path() }
    private val ribbons = Array(LAYERS) { layer ->
        Brush.horizontalGradient(
            0f to Color.Transparent,
            0.3f to accent.copy(alpha = ((0.45f - 0.08f * layer) * k).coerceIn(0f, 1f)),
            0.7f to core.copy(alpha = ((0.38f - 0.07f * layer) * k).coerceIn(0f, 1f)),
            1f to Color.Transparent,
        )
    }
    private var lines = Array(LAYERS) { Stroke() }
    private var glows = Array(LAYERS) { Stroke() }
    private val bloom = Glow(accent)

    override fun DrawScope.build() {
        lines = Array(LAYERS) { Stroke(width = (1.2f + it * 0.6f) * density, cap = StrokeCap.Round) }
        glows = Array(LAYERS) { Stroke(width = (7f + it * 3f) * density, cap = StrokeCap.Round) }
    }

    override fun DrawScope.paint(t: Float) {
        val w = size.width
        val h = size.height
        drawRect(top)
        bloom.draw(this, w * 0.62f, h * 0.64f, w * 0.55f, h * 0.2f, alpha = (if (look.dark) 0.16f else 0.08f) * k)
        for (layer in 0 until LAYERS) {
            val path = paths[layer]
            path.reset()
            val amp = h * (0.05f + 0.02f * layer)
            val baseY = h * (0.58f + 0.035f * layer)
            val freq = 1.4f + 0.35f * layer
            val phase = t * (0.18f + 0.05f * layer) + layer * 1.7f
            path.moveTo(0f, baseY)
            for (i in 0..STEPS) {
                val x = w * i / STEPS
                val u = i.toFloat() / STEPS
                val y = baseY + sin((u * freq * 2 * PI + phase).toFloat()) * amp * (0.6f + 0.4f * sin((u * PI).toFloat()))
                path.lineTo(x, y)
            }
            drawPath(path, ribbons[layer], alpha = 0.22f, style = glows[layer])
            drawPath(path, ribbons[layer], style = lines[layer])
        }
    }

    private companion object {
        const val LAYERS = 4
        const val STEPS = 64
    }
}

/** Soft fields of light wandering slowly. */
internal class AuroraScene(look: SceneLook) : Scene(look) {
    private val lights = arrayOf(
        Glow(look.accent),
        Glow(look.second ?: lerp(look.accent, Color(0xFF2A7BFF), 0.5f)),
        Glow(lerp(look.accent, Color.White, 0.3f)),
    )

    override fun DrawScope.paint(t: Float) {
        val w = size.width
        val h = size.height
        val r = size.maxDimension * 0.62f
        for (i in lights.indices) {
            // The lights wander around the middle of the screen, dimmer when they pass the title.
            val cx = w * (0.5f + 0.28f * sin(t * 0.07f + i * 2.1f))
            val cy = h * (0.5f + 0.22f * cos(t * 0.05f + i * 1.3f))
            lights[i].draw(this, cx, cy, r, alpha = 0.23f * k * (0.5f + 0.5f * calm(cx, cy)))
        }
    }
}

/** Orbit lines around a soft centre, each with a light travelling along it, over a far field of stars. */
internal class OrbitalScene(look: SceneLook) : Scene(look) {
    private val stars = StarField(60, look.second ?: look.accent)
    private val hub = Glow(look.accent)
    private val white = Glow(Color.White)
    private val tinted = Glow(look.second ?: Color.White)
    private var ring = Stroke()

    override fun DrawScope.build() {
        ring = Stroke(width = density)
    }

    override fun DrawScope.paint(t: Float) {
        stars.draw(this, t, k * 0.6f)
        val cx = size.width * 0.72f
        val cy = size.height * 0.5f
        val base = size.minDimension * 0.18f
        for (i in 0 until 6) {
            val rx = base * (1f + i * 0.55f)
            val ry = rx * 0.42f
            drawOval(
                look.accent.copy(alpha = ((0.16f - i * 0.02f) * k).coerceIn(0f, 1f)),
                topLeft = Offset(cx - rx, cy - ry),
                size = Size(rx * 2, ry * 2),
                style = ring,
            )
            val a = t * (0.25f - i * 0.03f) + i * 1.1f
            val glow = if (i % 2 == 0) white else tinted
            glow.draw(this, cx + cos(a) * rx, cy + sin(a) * ry, 12f * density * fit, alpha = 0.8f * k)
        }
        hub.draw(this, cx, cy, base * 1.6f, alpha = 0.28f * k)
    }
}

/** A deep field of stars that drift and twinkle, with a nebula glow in the accent and second colour. */
internal class StarsScene(look: SceneLook) : Scene(look) {
    private val nebula = Glow(look.accent)
    private val far = Glow(look.second ?: lerp(look.accent, Color(0xFF3D7BFF), 0.6f))
    private val stars = StarField(110, Color.White)

    override fun DrawScope.paint(t: Float) {
        val w = size.width
        val h = size.height
        nebula.draw(this, w * 0.78f, h * 0.28f, size.maxDimension * 0.55f, alpha = 0.15f * k)
        far.draw(this, w * 0.15f, h * 0.85f, size.maxDimension * 0.6f, alpha = 0.11f * k)
        stars.draw(this, t, k)
    }
}

/**
 * Seeded stars (the same every frame), drifting left very slowly and twinkling, in the top [top]
 * share of the screen; every seventh one takes [tint].
 */
internal class StarField(private val count: Int, private val tint: Color, private val top: Float = 1f) {
    private val x0 = FloatArray(count)
    private val y0 = FloatArray(count)
    private val depth = FloatArray(count)
    private val phase = FloatArray(count)

    init {
        val r = Seeded(0x2F6E2B1)
        for (i in 0 until count) {
            x0[i] = r.next()
            y0[i] = r.next()
            depth[i] = 0.3f + r.next() * 0.7f
            phase[i] = r.next() * 6.28f
        }
    }

    fun draw(scope: DrawScope, t: Float, k: Float) = with(scope) {
        val w = size.width
        val h = size.height
        for (i in 0 until count) {
            val d = depth[i]
            val x = wrap(x0[i] * w - t * 6f * density * d, w)
            val y = y0[i] * h * top
            val twinkle = 0.55f + 0.45f * sin(t * (0.8f + d) + phase[i])
            val c = if (i % 7 == 0) tint else Color.White
            drawCircle(c, radius = (0.6f + d * 1.1f) * density, center = Offset(x, y), alpha = (0.75f * d * twinkle * k * calm(x, y)).coerceIn(0f, 1f))
        }
    }
}

/** Bright themes: light from the top over soft diagonal pinstripes that drift very slowly. */
internal class StripesScene(look: SceneLook) : Scene(look) {
    private var top: Brush = Brush.verticalGradient(listOf(Color.Transparent, Color.Transparent))
    private val corner = Glow(look.accent)
    private val line = look.text.copy(alpha = (if (look.dark) 0.035f else 0.045f) * k)

    override fun DrawScope.build() {
        top = Brush.verticalGradient(
            listOf(Color.White.copy(alpha = (if (look.dark) 0.04f else 0.55f) * k), Color.Transparent),
            endY = size.height * 0.6f,
        )
    }

    override fun DrawScope.paint(t: Float) {
        val w = size.width
        val h = size.height
        drawRect(top)
        val gap = 22f * density
        var x = -h + (t * 4f * density) % gap
        while (x < w) {
            drawLine(line, Offset(x, h), Offset(x + h, 0f), strokeWidth = density)
            x += gap
        }
        corner.draw(this, w * 0.85f, h * 1.05f, size.maxDimension * 0.65f, alpha = 0.11f * k)
    }
}

/** A fine grid running to a lit horizon, its lines scrolling toward the viewer. */
internal class GridScene(look: SceneLook) : Scene(look) {
    private val line = look.accent.copy(alpha = ((if (look.dark) 0.22f else 0.18f) * k).coerceAtMost(1f))
    private val band = Brush.verticalGradient(
        0f to Color.Transparent,
        0.5f to look.accent.copy(alpha = 0.12f * k),
        0.52f to look.accent.copy(alpha = (0.2f * k).coerceAtMost(1f)),
        1f to Color.Transparent,
    )

    override fun DrawScope.paint(t: Float) {
        val w = size.width
        val h = size.height
        val horizon = h * 0.52f
        drawRect(band)
        // Vertical lines converge on a vanishing point.
        val vp = Offset(w / 2, horizon)
        for (i in -12..12) {
            drawLine(line, vp, Offset(w / 2 + i * w / 8f, h), strokeWidth = density)
        }
        // Horizontal lines scroll toward the viewer.
        val scroll = (t * 0.25f) % 1f
        for (i in 0 until 12) {
            val z = (i + scroll) / 12f
            val y = horizon + (h - horizon) * z * z
            drawLine(line.copy(alpha = line.alpha * z), Offset(0f, y), Offset(w, y), strokeWidth = density)
        }
    }
}
