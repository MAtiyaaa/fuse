package io.github.matiyaaa.fuse.ui.designsystem.background

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

// Drawn worlds: a neon horizon, a dot-matrix screen, mother of pearl and a contour map.

/**
 * A neon sun setting behind low mountains, its lower half cut by bands that slide slowly down, over
 * a grid floor that runs to the horizon and scrolls toward the viewer. The accent lights the sky and
 * the sun; the second colour draws the grid.
 */
internal class HorizonScene(look: SceneLook) : Scene(look) {
    private val neon = look.accent
    private val line = look.second ?: Color(0xFF3EE6FF)
    private val gold = lerp(neon, Color(0xFFFFD36E), 0.85f)
    private val violet = lerp(neon, Color(0xFF5B2BFF), 0.6f)
    private val halo = Glow(lerp(neon, gold, 0.3f))
    private val bloom = Glow(neon)
    private val hill = lerp(look.ink, violet, 0.14f)
    private var sky: Brush = Brush.verticalGradient(listOf(Color.Transparent, Color.Transparent))
    private var sun: Brush = sky
    private var floor: Brush = sky
    private var haze: Brush = sky
    private var rimLine: Brush = sky
    private var rays: Brush = sky
    private val mountains = Path()
    private val rim = Path()
    private var thin = Stroke()
    private var glow = Stroke()
    private var edge = Stroke()

    private val stars = 40
    private val starX = FloatArray(stars)
    private val starY = FloatArray(stars)
    private val starPhase = FloatArray(stars)

    init {
        val r = Seeded(0x5E7)
        for (i in 0 until stars) {
            starX[i] = r.between(0.28f, 1f)
            starY[i] = r.between(0.08f, 0.85f)
            starPhase[i] = r.next() * 6.28f
        }
    }

    override fun DrawScope.build() {
        val w = size.width
        val h = size.height
        val hy = h * HORIZON
        val sunR = minOf(h * 0.2f, w * 0.3f)
        val sunY = hy - sunR * 0.3f
        thin = Stroke(width = 1.1f * density)
        glow = Stroke(width = 4f * density, cap = StrokeCap.Round)
        edge = Stroke(width = 1.2f * density, join = StrokeJoin.Round)
        sky = Brush.verticalGradient(
            0f to Color.Transparent,
            0.5f to violet.copy(alpha = 0.07f * k),
            0.85f to neon.copy(alpha = 0.14f * k),
            1f to neon.copy(alpha = (0.3f * k).coerceAtMost(1f)),
            startY = 0f,
            endY = hy,
        )
        sun = Brush.verticalGradient(
            listOf(gold, lerp(gold, neon, 0.6f), neon),
            startY = sunY - sunR,
            endY = hy,
        )
        floor = Brush.verticalGradient(
            0f to lerp(neon, violet, 0.4f).copy(alpha = 0.16f * k),
            0.25f to violet.copy(alpha = 0.04f * k),
            1f to Color.Transparent,
            startY = hy,
            endY = h,
        )
        rays = Brush.verticalGradient(
            0f to line.copy(alpha = 0.3f),
            0.3f to line,
            1f to line.copy(alpha = 0.3f),
            startY = hy,
            endY = h,
        )
        haze = Brush.verticalGradient(
            0f to lerp(neon, gold, 0.25f).copy(alpha = (0.5f * k).coerceAtMost(1f)),
            1f to neon.copy(alpha = 0f),
            startY = hy,
            endY = hy + h * 0.1f,
        )
        rimLine = Brush.horizontalGradient(
            0f to lerp(neon, Color.White, 0.4f).copy(alpha = 0.12f * k),
            SUN_X to lerp(neon, Color.White, 0.5f).copy(alpha = (0.85f * k).coerceAtMost(1f)),
            1f to lerp(neon, Color.White, 0.4f).copy(alpha = 0.35f * k),
        )
        // Low, angular mountains: the highest of a few triangular peaks at every point.
        mountains.reset()
        rim.reset()
        mountains.moveTo(0f, hy)
        val steps = 96
        for (i in 0..steps) {
            val u = i.toFloat() / steps
            var top = 0f
            for (p in PEAKS.indices step 3) {
                top = max(top, (1f - abs(u - PEAKS[p]) / PEAKS[p + 2]).coerceAtLeast(0f) * PEAKS[p + 1])
            }
            val y = hy - h * (0.006f + top)
            mountains.lineTo(w * u, y)
            if (i == 0) rim.moveTo(0f, y) else rim.lineTo(w * u, y)
        }
        mountains.lineTo(w, hy)
        mountains.close()
    }

    override fun DrawScope.paint(t: Float) {
        val w = size.width
        val h = size.height
        val d = density
        val hy = h * HORIZON
        val sunR = minOf(h * 0.2f, w * 0.3f)
        val sunX = w * SUN_X
        val sunY = hy - sunR * 0.3f
        // The sky gradient ends at the horizon; below it the floor takes over.
        drawRect(sky, size = Size(w, hy))
        for (i in 0 until stars) {
            val x = w * starX[i]
            val y = hy * starY[i]
            val twinkle = 0.5f + 0.5f * sin(t * 0.9f + starPhase[i])
            drawCircle(Color.White, radius = 0.9f * d, center = Offset(x, y), alpha = (0.55f * twinkle * k * calm(x, y) * (1f - y / hy)).coerceIn(0f, 1f))
        }
        halo.draw(this, sunX, sunY, sunR * 2.6f, alpha = 0.32f * k)
        // The sun in slices: solid on top, then bands that thicken toward the horizon and slide down.
        val sunAlpha = (0.92f * k).coerceAtMost(1f)
        val bandTop = sunY - sunR * 0.4f
        val spacing = (hy - bandTop) / BANDS
        val slide = (t * 0.07f) % 1f
        var top = sunY - sunR - 1f
        for (j in 0..BANDS) {
            val centre = bandTop + (j - 1 + slide) * spacing
            val gap = sunR * (0.012f + 0.085f * ((centre - bandTop) / (hy - bandTop)).coerceIn(0f, 1f))
            val bottom = minOf(centre - gap / 2f, hy)
            if (bottom > top) {
                clipRect(0f, top, w, bottom) { drawCircle(sun, radius = sunR, center = Offset(sunX, sunY), alpha = sunAlpha) }
            }
            top = maxOf(top, centre + gap / 2f)
        }
        if (top < hy) clipRect(0f, top, w, hy) { drawCircle(sun, radius = sunR, center = Offset(sunX, sunY), alpha = sunAlpha) }
        drawPath(mountains, hill)
        drawPath(rim, line, alpha = 0.32f * k.coerceAtMost(1f), style = edge)
        bloom.draw(this, sunX, hy, w * 0.6f, h * 0.07f, alpha = 0.5f * k)
        drawRect(floor, topLeft = Offset(0f, hy), size = Size(w, h - hy))
        // The floor grid: lines from the vanishing point under the sun, and rows coming closer.
        val vp = Offset(sunX, hy)
        for (i in -16..16) {
            val end = Offset(sunX + i * w * 0.07f, h)
            drawLine(rays, vp, end, strokeWidth = glow.width, alpha = 0.07f * k)
            drawLine(rays, vp, end, strokeWidth = thin.width, alpha = (0.42f * k).coerceAtMost(1f))
        }
        val scroll = (t * 0.1f) % 1f
        for (i in 0 until ROWS) {
            val z = (i + scroll) / ROWS
            val y = hy + (h - hy) * z.pow(2.4f)
            val fade = z * (1f - 0.7f * smooth(h * 0.78f, h, y))
            drawLine(line, Offset(0f, y), Offset(w, y), strokeWidth = glow.width, alpha = 0.07f * k * fade)
            drawLine(line, Offset(0f, y), Offset(w, y), strokeWidth = thin.width, alpha = (0.46f * k * fade).coerceAtMost(1f))
        }
        drawRect(haze, topLeft = Offset(0f, hy), size = Size(w, h * 0.1f))
        drawLine(rimLine, Offset(0f, hy), Offset(w, hy), strokeWidth = 1.4f * d)
    }

    private companion object {
        const val HORIZON = 0.64f
        const val SUN_X = 0.7f
        const val BANDS = 7
        const val ROWS = 15
        // Peaks as (x, height, half width), as shares of the screen.
        val PEAKS = floatArrayOf(
            0.04f, 0.07f, 0.11f, 0.16f, 0.045f, 0.08f, 0.29f, 0.03f, 0.09f, 0.47f, 0.02f, 0.07f,
            0.6f, 0.042f, 0.065f, 0.79f, 0.036f, 0.07f, 0.93f, 0.065f, 0.1f, 1.03f, 0.045f, 0.06f,
        )
    }
}

/**
 * A dot-matrix screen in the theme's own few tones: a fine pixel grid, a pixel sun, two ranges of
 * pixel hills and clouds that step one pixel at a time, leaving a faint ghost as old screens did,
 * under a glint of glass. Every mark sits on the grid.
 */
internal class LcdScene(look: SceneLook) : Scene(look) {
    private val ink = look.ink
    private val mid = look.second ?: lerp(ink, look.text, 0.35f)
    private val gap = lerp(ink, if (look.dark) look.text else Color.White, if (look.dark) 0.05f else 0.34f)
    private val farHill = lerp(ink, mid, 0.32f)
    private val nearHill = lerp(ink, mid, 0.58f)
    private val cloud = lerp(ink, if (look.dark) look.text else Color.White, if (look.dark) 0.1f else 0.5f)
    private val outline = lerp(ink, mid, 0.55f)
    private var p = 8f
    private val grid = Path()
    private val far = Path()
    private val near = Path()
    private val sunPath = Path()
    private val sunRim = Path()
    private val pines = Path()
    private val pine = lerp(ink, mid, 0.9f)
    private var hair = Stroke()
    private var glass: Brush = Brush.verticalGradient(listOf(Color.Transparent, Color.Transparent))
    private var bezel: Brush = glass
    private val clouds = Array(CLOUDS.size) { Path() }

    /** Clouds step a pixel at a time, so a few frames a second show every step. */
    override val fps: Int = 8

    override fun DrawScope.build() {
        val w = size.width
        val h = size.height
        // Whole device pixels, so every line is crisp.
        p = (5f * density).roundToInt().coerceAtLeast(3).toFloat()
        hair = Stroke(width = 1f)
        val cols = ceil(w / p).toInt()
        val rows = ceil(h / p).toInt()
        grid.reset()
        for (c in 0..cols) {
            grid.moveTo(c * p + 0.5f, 0f)
            grid.lineTo(c * p + 0.5f, h)
        }
        for (r in 0..rows) {
            grid.moveTo(0f, r * p + 0.5f)
            grid.lineTo(w, r * p + 0.5f)
        }
        hills(far, cols, rows, base = 0.17f, a = 4.5f, f1 = 0.045f, f2 = 0.11f, phase = 1.3f)
        hills(near, cols, rows, base = 0.08f, a = 3.5f, f1 = 0.07f, f2 = 0.16f, phase = 4.1f)
        // A few pixel pines standing on the near hills.
        pines.reset()
        for (share in PINES) {
            val c = (cols * share).roundToInt()
            val ground = rows - hillHeight(c, rows, base = 0.08f, a = 3.5f, f1 = 0.07f, f2 = 0.16f, phase = 4.1f)
            PINE.forEachIndexed { r, line ->
                line.forEachIndexed { dc, ch -> if (ch == 'X') pines.addCell(c + dc - 2, ground - PINE.size + r) }
            }
        }
        // A pixel sun high on the right.
        sunPath.reset()
        sunRim.reset()
        val sc = (cols * 0.86f).roundToInt()
        val sr = (rows * 0.22f).roundToInt()
        for (dy in -5..5) for (dx in -5..5) {
            val dd = dx * dx + dy * dy
            if (dd <= 20) sunRim.addCell(sc + dx, sr + dy)
            if (dd <= 12) sunPath.addCell(sc + dx, sr + dy)
        }
        for (i in CLOUDS.indices) {
            clouds[i].reset()
            CLOUDS[i].forEachIndexed { r, line -> line.forEachIndexed { c, ch -> if (ch == 'X') clouds[i].addCell(c, r) } }
        }
        glass = Brush.linearGradient(
            0f to Color.White.copy(alpha = if (look.dark) 0.04f else 0.2f),
            1f to Color.Transparent,
            start = Offset(w, 0f),
            end = Offset(w * 0.55f, h * 0.55f),
        )
        bezel = Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.07f), 1f to Color.Transparent, endY = h * 0.05f)
    }

    private fun Path.addCell(c: Int, r: Int) {
        addRect(Rect(c * p, r * p, (c + 1) * p, (r + 1) * p))
    }

    private fun hillHeight(c: Int, rows: Int, base: Float, a: Float, f1: Float, f2: Float, phase: Float): Int =
        (rows * base + a * sin(c * f1 + phase) + a * 0.45f * sin(c * f2 + phase * 2f)).roundToInt().coerceAtLeast(1)

    private fun hills(path: Path, cols: Int, rows: Int, base: Float, a: Float, f1: Float, f2: Float, phase: Float) {
        path.reset()
        val bottom = rows * p
        path.moveTo(0f, bottom)
        for (c in 0..cols) {
            val top = bottom - hillHeight(c, rows, base, a, f1, f2, phase) * p
            path.lineTo(c * p, top)
            path.lineTo((c + 1) * p, top)
        }
        path.lineTo((cols + 1) * p, bottom)
        path.close()
    }

    override fun DrawScope.paint(t: Float) {
        val w = size.width
        val h = size.height
        val rows = (h / p).toInt()
        drawPath(sunRim, outline, alpha = 0.55f * k.coerceAtMost(1f))
        drawPath(sunPath, cloud, alpha = 0.9f * k.coerceAtMost(1f))
        val span = (w / p).toInt() + 40
        for (i in CLOUD_X.indices) {
            // Clouds move by whole pixels; the one they just left still glows faintly.
            val col = floor(wrap(CLOUD_X[i] * span - t * CLOUD_SPEED[i], span.toFloat())).toInt() - 20
            val row = (rows * CLOUD_Y[i]).roundToInt()
            val shape = clouds[i % clouds.size]
            val a = k.coerceAtMost(1f)
            translate((col + 1) * p, row * p) { drawPath(shape, cloud, alpha = 0.28f * a) }
            translate(col * p, (row + 1) * p) { drawPath(shape, outline, alpha = 0.5f * a) }
            translate(col * p, row * p) { drawPath(shape, cloud, alpha = a) }
        }
        drawPath(far, farHill, alpha = k.coerceAtMost(1f))
        drawPath(near, nearHill, alpha = k.coerceAtMost(1f))
        drawPath(pines, pine, alpha = k.coerceAtMost(1f))
        drawPath(grid, gap, alpha = 0.4f, style = hair)
        drawRect(glass)
        drawRect(bezel)
    }

    private companion object {
        val PINES = floatArrayOf(0.07f, 0.1f, 0.56f, 0.6f, 0.91f)
        val PINE = listOf(
            "..X..",
            ".XXX.",
            "..X..",
            ".XXX.",
            "XXXXX",
            "..X..",
        )
        val CLOUD_X = floatArrayOf(0.15f, 0.48f, 0.7f, 0.9f)
        val CLOUD_Y = floatArrayOf(0.3f, 0.14f, 0.38f, 0.2f)
        /** Pixels a second. */
        val CLOUD_SPEED = floatArrayOf(0.7f, 0.45f, 0.9f, 0.55f)
        val CLOUDS = listOf(
            listOf(
                "....XXXX......",
                "..XXXXXXXX.XX.",
                ".XXXXXXXXXXXXX",
                "XXXXXXXXXXXXXX",
                ".XXXXXXXXXXXX.",
            ),
            listOf(
                "...XXX...",
                ".XXXXXXX.",
                "XXXXXXXXX",
                ".XXXXXXX.",
            ),
            listOf(
                "......XXXX..........",
                "...XXXXXXXXX..XXX...",
                ".XXXXXXXXXXXXXXXXXX.",
                "XXXXXXXXXXXXXXXXXXXX",
                ".XXXXXXXXXXXXXXXXXX.",
            ),
        )
    }
}

/**
 * Mother of pearl: soft pastel lights (the accent, the second colour, rose, sky and peach) that
 * wander and blend, a pale calm over the title corner and one slow sheen crossing now and then.
 * On a dark theme the same lights glow low instead.
 */
internal class MeshScene(look: SceneLook) : Scene(look) {
    private val bright = !look.dark
    private val lights = arrayOf(
        Glow(pastel(look.accent, 0.42f)),
        Glow(pastel(look.second ?: Color(0xFF5FE0C8), 0.3f)),
        Glow(pastel(Color(0xFFFF8AC0), 0.38f)),
        Glow(pastel(Color(0xFF79B8FF), 0.32f)),
        Glow(pastel(Color(0xFFFFC08A), 0.3f)),
    )
    private val pearl = Glow(if (bright) lerp(look.ink, Color.White, 0.55f) else look.ink)
    private var sheen: Brush = Brush.verticalGradient(listOf(Color.Transparent, Color.Transparent))
    private var band = 1f

    override val fps: Int = 20

    private fun pastel(c: Color, t: Float) = if (bright) lerp(c, Color.White, t) else c

    override fun DrawScope.build() {
        band = size.minDimension * 0.5f
        sheen = Brush.horizontalGradient(
            0f to Color.Transparent,
            0.5f to Color.White.copy(alpha = if (bright) 0.3f else 0.05f),
            1f to Color.Transparent,
            startX = -band / 2f,
            endX = band / 2f,
        )
    }

    override fun DrawScope.paint(t: Float) {
        val w = size.width
        val h = size.height
        val m = size.maxDimension
        val alpha = (if (bright) 0.66f else 0.2f) * k
        for (i in lights.indices) {
            val x = w * (CX[i] + 0.09f * sin(t * RATE[i] + i * 1.3f))
            val y = h * (CY[i] + 0.08f * sin(t * RATE[i] * 0.8f + i * 2.1f + 1f))
            lights[i].draw(this, x, y, m * R[i], m * R[i] * 0.85f, alpha = alpha, degrees = 20f * i)
        }
        pearl.draw(this, w * 0.04f, 0f, m * 0.62f, m * 0.5f, alpha = if (bright) 0.9f else 0.55f)
        // A sheen crosses every couple of minutes, entering and leaving fully off screen.
        val x = wrap(t * 9f * density, w + m * 1.4f) - m * 0.7f
        withTransform({
            translate(x, h * 0.55f)
            rotate(-28f, Offset.Zero)
        }) {
            drawRect(sheen, topLeft = Offset(-band / 2f, -m), size = Size(band, m * 2f), alpha = k.coerceAtMost(1f))
        }
    }

    private companion object {
        val CX = floatArrayOf(0.84f, 0.36f, 1.0f, 0.62f, 0.1f)
        val CY = floatArrayOf(0.86f, 1.02f, 0.34f, 0.6f, 0.86f)
        val R = floatArrayOf(0.5f, 0.48f, 0.42f, 0.38f, 0.38f)
        val RATE = floatArrayOf(0.05f, 0.04f, 0.06f, 0.045f, 0.055f)
    }
}

/**
 * A contour map of a quiet landscape: hills low on the right, flat land under the title, every
 * fifth line heavier, and small survey marks. The lines rise slowly toward the summits, new ones
 * starting at the foot of each hill.
 */
internal class ContoursScene(look: SceneLook) : Scene(look) {
    private val lines = Path()
    private val index = Path()
    private val marks = Path()
    private var thin = Stroke()
    private var bold = Stroke()
    private var tint: Brush = Brush.verticalGradient(listOf(Color.Transparent, Color.Transparent))
    private var cols = 0
    private var rows = 0
    private var step = 1f
    private var field = FloatArray(0)
    private var lo = FloatArray(0)
    private var hi = FloatArray(0)

    override val fps: Int = 15

    override fun DrawScope.build() {
        val w = size.width
        val h = size.height
        thin = Stroke(width = 1f * density, cap = StrokeCap.Round)
        bold = Stroke(width = 1.6f * density, cap = StrokeCap.Round)
        step = 12f * density
        cols = ceil(w / step).toInt() + 1
        rows = ceil(h / step).toInt() + 1
        field = FloatArray((cols + 1) * (rows + 1))
        var min = Float.MAX_VALUE
        var max = -Float.MAX_VALUE
        for (j in 0..rows) for (i in 0..cols) {
            val v = height(i * step / w, j * step / w, h / w)
            field[j * (cols + 1) + i] = v
            if (v < min) min = v
            if (v > max) max = v
        }
        for (n in field.indices) field[n] = (field[n] - min) / (max - min)
        lo = FloatArray(cols * rows)
        hi = FloatArray(cols * rows)
        for (j in 0 until rows) for (i in 0 until cols) {
            val a = field[j * (cols + 1) + i]
            val b = field[j * (cols + 1) + i + 1]
            val c = field[(j + 1) * (cols + 1) + i + 1]
            val d = field[(j + 1) * (cols + 1) + i]
            lo[j * cols + i] = minOf(minOf(a, b), minOf(c, d))
            hi[j * cols + i] = maxOf(maxOf(a, b), maxOf(c, d))
        }
        tint = Brush.radialGradient(
            0f to look.text.copy(alpha = 0.12f),
            0.6f to look.text,
            1f to look.text,
            center = Offset.Zero,
            radius = size.maxDimension * 0.7f,
        )
        // Survey crosses on a coarse grid.
        marks.reset()
        val gridStep = 160f * density
        val arm = 5f * density
        var y = gridStep * 0.75f
        while (y < h) {
            var x = gridStep * 0.75f
            while (x < w) {
                marks.moveTo(x - arm, y)
                marks.lineTo(x + arm, y)
                marks.moveTo(x, y - arm)
                marks.lineTo(x, y + arm)
                x += gridStep
            }
            y += gridStep
        }
    }

    /** The land at ([u], [v]), both measured in screen widths; [aspect] is height over width. */
    private fun height(u: Float, v: Float, aspect: Float): Float {
        var s = 0f
        for (n in HILLS.indices step 5) {
            val dx = (u - HILLS[n]) / HILLS[n + 2]
            val dy = (v - HILLS[n + 1] * aspect) / HILLS[n + 3]
            s += HILLS[n + 4] * exp(-(dx * dx + dy * dy))
        }
        return s + 0.035f * sin(u * 11f + v * 7f) + 0.02f * sin(u * 23f - v * 17f)
    }

    override fun DrawScope.paint(t: Float) {
        val phase = t * 0.03f
        lines.reset()
        index.reset()
        for (j in 0 until rows) for (i in 0 until cols) {
            val first = ceil(lo[j * cols + i] / LEVEL - phase).toInt()
            val last = floor(hi[j * cols + i] / LEVEL - phase).toInt()
            if (last < first) continue
            for (n in first..last) {
                val level = (n + phase) * LEVEL
                march(if (n.mod(5) == 0) index else lines, i, j, level)
            }
        }
        val a = k.coerceAtMost(1f)
        drawPath(marks, look.text, alpha = 0.2f * a, style = thin)
        drawPath(lines, tint, alpha = (if (look.dark) 0.12f else 0.15f) * a, style = thin)
        drawPath(index, tint, alpha = (if (look.dark) 0.2f else 0.26f) * a, style = bold)
    }

    /** Marching squares: the piece of the line at [level] inside cell ([i], [j]). */
    private fun march(path: Path, i: Int, j: Int, level: Float) {
        val stride = cols + 1
        val a = field[j * stride + i]
        val b = field[j * stride + i + 1]
        val c = field[(j + 1) * stride + i + 1]
        val d = field[(j + 1) * stride + i]
        val case = (if (a > level) 8 else 0) or (if (b > level) 4 else 0) or (if (c > level) 2 else 0) or (if (d > level) 1 else 0)
        if (case == 0 || case == 15) return
        val x0 = i * step
        val y0 = j * step
        // Where the line crosses each side of the cell.
        val topX = x0 + step * frac(a, b, level)
        val rightY = y0 + step * frac(b, c, level)
        val bottomX = x0 + step * frac(d, c, level)
        val leftY = y0 + step * frac(a, d, level)
        val x1 = x0 + step
        val y1 = y0 + step
        val centreHigh = (a + b + c + d) / 4f > level
        when (case) {
            1, 14 -> seg(path, x0, leftY, bottomX, y1)
            2, 13 -> seg(path, bottomX, y1, x1, rightY)
            3, 12 -> seg(path, x0, leftY, x1, rightY)
            4, 11 -> seg(path, topX, y0, x1, rightY)
            6, 9 -> seg(path, topX, y0, bottomX, y1)
            7, 8 -> seg(path, x0, leftY, topX, y0)
            5 -> if (centreHigh) {
                seg(path, x0, leftY, topX, y0)
                seg(path, bottomX, y1, x1, rightY)
            } else {
                seg(path, topX, y0, x1, rightY)
                seg(path, x0, leftY, bottomX, y1)
            }
            10 -> if (centreHigh) {
                seg(path, topX, y0, x1, rightY)
                seg(path, x0, leftY, bottomX, y1)
            } else {
                seg(path, x0, leftY, topX, y0)
                seg(path, bottomX, y1, x1, rightY)
            }
        }
    }

    private fun frac(from: Float, to: Float, level: Float): Float =
        if (to == from) 0.5f else ((level - from) / (to - from)).coerceIn(0f, 1f)

    private fun seg(path: Path, ax: Float, ay: Float, bx: Float, by: Float) {
        path.moveTo(ax, ay)
        path.lineTo(bx, by)
    }

    private companion object {
        /** The height between two lines, the land being 0 to 1. */
        const val LEVEL = 1f / 17f

        // Hills as (x, y as a share of the height, spread across, spread down, height).
        val HILLS = floatArrayOf(
            0.8f, 0.64f, 0.17f, 0.13f, 1f,
            0.63f, 0.84f, 0.09f, 0.08f, 0.45f,
            0.4f, 1.06f, 0.2f, 0.09f, 0.55f,
            1.03f, 0.12f, 0.12f, 0.09f, 0.5f,
            0.12f, 0.95f, 0.09f, 0.06f, 0.35f,
            0.3f, 0.3f, 0.25f, 0.18f, 0.08f,
        )
    }
}
