package io.github.matiyaaa.fuse.ui.designsystem.background

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

// Scenes from nature: petals at dusk, fireflies in a wood, light through water and dunes at noon.

/**
 * Petals drifting down and to the left through a dusk sky: indigo above, rose lower down and a
 * peach glow where the sun went, with the first stars out high on the right. Near petals are
 * larger, brighter and faster than far ones, and each one turns and tumbles as it falls.
 */
internal class PetalsScene(look: SceneLook) : Scene(look) {
    private val pink = look.accent
    private val dusk = look.second ?: lerp(look.accent, Color(0xFFFFB38A), 0.7f)
    private val sky = Brush.verticalGradient(
        0f to Color.Transparent,
        0.45f to lerp(pink, Color(0xFF6A4CFF), 0.55f).copy(alpha = 0.05f * k),
        0.78f to pink.copy(alpha = 0.09f * k),
        1f to lerp(pink, dusk, 0.6f).copy(alpha = (0.2f * k).coerceAtMost(1f)),
    )
    private val sun = Glow(lerp(dusk, pink, 0.25f))
    private val rose = Glow(pink)
    private val shape = Path().apply {
        // One petal, a unit long and centred on the origin, with the notch at its outer end.
        moveTo(-0.5f, 0f)
        cubicTo(-0.32f, -0.36f, 0.12f, -0.44f, 0.44f, -0.17f)
        quadraticTo(0.53f, -0.08f, 0.37f, 0f)
        quadraticTo(0.53f, 0.08f, 0.44f, 0.17f)
        cubicTo(0.12f, 0.44f, -0.32f, 0.36f, -0.5f, 0f)
        close()
    }
    private val stars = StarField(28, dusk, top = 0.55f)
    private val tint = Brush.linearGradient(
        listOf(lerp(pink, Color(0xFF8A1E52), 0.18f), pink, lerp(pink, Color.White, 0.55f)),
        start = Offset(-0.5f, 0f),
        end = Offset(0.5f, 0f),
    )

    private val n = 40
    private val fx = FloatArray(n)
    private val fy = FloatArray(n)
    private val z = FloatArray(n)
    private val turn0 = FloatArray(n)
    private val turnRate = FloatArray(n)
    private val tumble = FloatArray(n)
    private val tumblePhase = FloatArray(n)
    private val swayRate = FloatArray(n)
    private val swayPhase = FloatArray(n)

    init {
        val r = Seeded(0x51A7E)
        // Far petals first, so near ones are drawn over them.
        val depths = FloatArray(n) { r.next() }.also { it.sort() }
        for (i in 0 until n) {
            fx[i] = r.next()
            fy[i] = r.next()
            z[i] = depths[i]
            turn0[i] = r.next() * 360f
            turnRate[i] = r.between(-24f, 24f)
            tumble[i] = r.between(0.25f, 0.7f)
            tumblePhase[i] = r.next() * 6.28f
            swayRate[i] = r.between(0.22f, 0.5f)
            swayPhase[i] = r.next() * 6.28f
        }
    }

    override fun DrawScope.paint(t: Float) {
        val w = size.width
        val h = size.height
        val d = density * fit
        drawRect(sky)
        stars.draw(this, t * 0.3f, k * 0.5f)
        // A wide, low glow reads as the horizon after sunset rather than a dome of light.
        sun.draw(this, w * 0.66f, h * 1.08f, size.maxDimension * 0.85f, size.maxDimension * 0.25f, alpha = 0.6f * k)
        rose.draw(this, w * 0.12f, h * 1.1f, size.maxDimension * 0.42f, size.maxDimension * 0.26f, alpha = 0.2f * k)
        val m = 40f * d
        val spanW = w + 2 * m
        val spanH = h + 2 * m
        for (i in 0 until n) {
            val depth = z[i]
            val sway = (10f + 22f * depth) * d * sin(t * swayRate[i] + swayPhase[i])
            val x = wrap(fx[i] * spanW - (5f + 12f * depth) * d * t + sway, spanW) - m
            val y = wrap(fy[i] * spanH + (7f + 15f * depth) * d * t, spanH) - m
            val s = (9f + 15f * depth) * d
            var flip = cos(t * tumble[i] + tumblePhase[i])
            if (abs(flip) < 0.2f) flip = if (flip < 0f) -0.2f else 0.2f
            val angle = turn0[i] + turnRate[i] * t + 14f * sin(t * swayRate[i] + swayPhase[i])
            val alpha = ((0.32f + 0.55f * depth) * k * calm(x, y)).coerceIn(0f, 1f)
            withTransform({
                translate(x, y)
                rotate(angle, Offset.Zero)
                scale(s * flip, s, Offset.Zero)
            }) {
                drawPath(shape, tint, alpha = alpha)
            }
        }
    }
}

/**
 * A dark wood after sunset: tall trunks standing in a low mist, the farthest soft with fog, the
 * nearer ones crisp silhouettes, a fern-lined forest floor, the canopy dark overhead, and fireflies
 * wandering between the trees, each glowing for a few seconds and resting for longer.
 */
internal class FirefliesScene(look: SceneLook) : Scene(look) {
    private val mistColor = look.second ?: lerp(look.accent, Color(0xFF3FBF8F), 0.6f)
    private val mist = Glow(mistColor)
    private val moon = Glow(lerp(mistColor, Color.White, 0.45f))
    private val halo = Glow(look.accent)
    private val core = lerp(look.accent, Color.White, 0.65f)
    // Far trunks are drawn a unit wide and stretched, so one soft-edged brush serves them all.
    private val fog = lerp(look.ink, mistColor, 0.22f).copy(alpha = 0.55f * k.coerceAtMost(1f))
    private val farTrunk = Brush.horizontalGradient(
        0f to fog.copy(alpha = 0f), 0.35f to fog, 0.65f to fog, 1f to fog.copy(alpha = 0f), startX = 0f, endX = 1f,
    )
    private val midTone = lerp(look.ink, mistColor, 0.05f)
    private val nearTone = lerp(look.ink, Color.Black, 0.7f)
    private val canopy = Brush.verticalGradient(0f to look.ink, 0.45f to look.ink.copy(alpha = 0f))
    private val mid = Path()
    private val near = Path()
    private val floor = Path()
    private val rims = Path()
    private var rim = Stroke()
    private var rimLight: Brush = canopy

    private val n = 36
    private val fx = FloatArray(n)
    private val fy = FloatArray(n)
    private val z = FloatArray(n)
    private val wander = FloatArray(n)
    private val wanderPhase = FloatArray(n)
    private val blink = FloatArray(n)
    private val blinkPhase = FloatArray(n)

    init {
        val r = Seeded(0xF1EF1)
        var i = 0
        while (i < n) {
            val x = r.between(0.03f, 0.98f)
            val y = r.between(0.26f, 0.9f)
            // Few over the title area.
            if (x < 0.48f && y < 0.45f && r.next() < 0.85f) continue
            fx[i] = x
            fy[i] = y
            z[i] = r.next()
            wander[i] = r.between(0.05f, 0.12f)
            wanderPhase[i] = r.next() * 6.28f
            blink[i] = r.between(0.07f, 0.15f)
            blinkPhase[i] = r.next()
            i++
        }
    }

    override fun DrawScope.build() {
        val w = size.width
        val h = size.height
        val d = density * fit
        // Trunks widen a little toward the ground and flare at the root.
        fun trunks(path: Path, list: FloatArray, groundShare: Float) {
            path.reset()
            val ground = h * groundShare
            for (i in list.indices step 2) {
                val x = w * list[i]
                val half = list[i + 1] * d / 2f
                path.moveTo(x - half * 0.82f, 0f)
                path.lineTo(x + half * 0.82f, 0f)
                path.lineTo(x + half, ground - half * 1.4f)
                path.quadraticTo(x + half, ground, x + half * 1.9f, ground + half * 0.5f)
                path.lineTo(x - half * 1.9f, ground + half * 0.5f)
                path.quadraticTo(x - half, ground, x - half, ground - half * 1.4f)
                path.close()
            }
        }
        trunks(mid, MID, 0.9f)
        trunks(near, NEAR, 1.02f)
        // Moonlight catches the right edge of the nearer trunks, strongest where the mist is.
        rims.reset()
        for (list in arrayOf(MID, NEAR)) {
            for (i in list.indices step 2) {
                val x = w * list[i]
                val half = list[i + 1] * d / 2f
                val inset = 1.2f * d
                rims.moveTo(x + half * 0.82f - inset, 0f)
                rims.lineTo(x + half - inset, h * 0.9f - half * 1.4f)
            }
        }
        rim = Stroke(width = 1.4f * d, cap = StrokeCap.Round)
        rimLight = Brush.verticalGradient(
            0f to mistColor.copy(alpha = 0f),
            0.45f to mistColor.copy(alpha = 0.05f * k.coerceAtMost(1f)),
            0.85f to lerp(mistColor, Color.White, 0.3f).copy(alpha = 0.3f * k.coerceAtMost(1f)),
            1f to mistColor.copy(alpha = 0f),
            startY = 0f,
            endY = h * 0.92f,
        )
        // The forest floor: a low bank with fine grass and ferns along its edge.
        floor.reset()
        floor.moveTo(0f, h)
        val steps = 180
        for (i in 0..steps) {
            val u = i.toFloat() / steps
            val bank = h * (0.91f + 0.016f * sin(u * 9f + 1f) + 0.008f * sin(u * 23f))
            val blade = if (i % 2 == 0) 0f else h * (0.004f + 0.012f * sin(u * 37f + 0.6f).let { it * it })
            floor.lineTo(w * u, bank - blade)
        }
        floor.lineTo(w, h)
        floor.close()
    }

    override fun DrawScope.paint(t: Float) {
        val w = size.width
        val h = size.height
        val d = density * fit
        moon.draw(this, w * 0.9f, -h * 0.1f, size.maxDimension * 0.5f, alpha = 0.12f * k)
        mist.draw(this, w * 0.62f, h * 0.84f, w * 0.75f, h * 0.3f, alpha = 0.22f * k)
        mist.draw(this, w * 0.14f, h * 0.94f, w * 0.38f, h * 0.18f, alpha = 0.1f * k)
        for (i in FAR.indices step 2) {
            withTransform({
                translate(w * FAR[i], 0f)
                scale(FAR[i + 1] * d, 1f, Offset.Zero)
            }) {
                drawRect(farTrunk, topLeft = Offset.Zero, size = Size(1f, h))
            }
        }
        mist.draw(this, w * 0.5f, h * 0.9f, w * 0.9f, h * 0.12f, alpha = 0.12f * k)
        drawRect(canopy)
        fireflies(t, near = false)
        drawPath(mid, midTone)
        drawPath(floor, nearTone)
        fireflies(t, near = true)
        drawPath(near, nearTone)
        drawPath(rims, rimLight, style = rim)
    }

    private fun DrawScope.fireflies(t: Float, near: Boolean) {
        val w = size.width
        val h = size.height
        val d = density * fit
        for (i in 0 until n) {
            val depth = z[i]
            if ((depth >= 0.5f) != near) continue
            val a = t * wander[i] + wanderPhase[i]
            val x = w * fx[i] + (16f + 34f * depth) * d * (sin(a) + 0.4f * sin(a * 2.3f + 1f))
            val y = h * fy[i] + (10f + 24f * depth) * d * sin(a * 0.8f + 2f)
            val u = wrap(t * blink[i] + blinkPhase[i], 1f)
            val pulse = if (u < LIT) sin(PI.toFloat() * u / LIT).let { it * it } else 0f
            val glow = (0.06f + 0.94f * pulse) * k * calm(x, y)
            halo.draw(this, x, y, (14f + 20f * depth) * d, alpha = 0.6f * glow)
            drawCircle(core, radius = (1.3f + 1.5f * depth) * d, center = Offset(x, y), alpha = glow.coerceIn(0f, 1f))
        }
    }

    private companion object {
        /** The share of each blink cycle a firefly glows for. */
        const val LIT = 0.38f

        // Trunks as (x as a share of the width, width in dp).
        val FAR = floatArrayOf(0.17f, 22f, 0.29f, 14f, 0.44f, 28f, 0.555f, 16f, 0.68f, 24f, 0.81f, 15f, 0.93f, 30f)
        val MID = floatArrayOf(0.075f, 30f, 0.37f, 22f, 0.63f, 36f, 0.88f, 26f)
        val NEAR = floatArrayOf(0.012f, 60f, 0.775f, 48f)
    }
}

/**
 * Looking down into clear water: light rays slanting in from the top right and a net of caustic
 * light, a warped honeycomb whose cells stretch, breathe and drift, brighter in slow patches where
 * the surface focuses the sun, strongest low on the right and gone by the title area. A few motes
 * rise slowly.
 */
internal class CausticsScene(look: SceneLook) : Scene(look) {
    private val water = look.accent
    private val sky = look.second ?: lerp(water, Color(0xFF7FC8FF), 0.6f)
    private val surface = Glow(lerp(sky, water, 0.35f))
    private val ray = Glow(lerp(sky, Color.White, 0.35f))
    private val light = lerp(water, Color.White, 0.55f)
    private val deep = Brush.verticalGradient(0.5f to Color.Transparent, 1f to Color.Black.copy(alpha = if (look.dark) 0.34f else 0.08f))
    private var net: Brush = deep
    private val paths = Array(LEVELS) { Path() }
    private var thin = Stroke()
    private var wide = Stroke()

    private var baseX = FloatArray(0)
    private var baseY = FloatArray(0)
    private var posX = FloatArray(0)
    private var posY = FloatArray(0)
    private var edges = IntArray(0)
    private var edgePhase = FloatArray(0)
    private var cell = 1f

    private val motes = 26
    private val moteX = FloatArray(motes)
    private val moteY = FloatArray(motes)
    private val moteZ = FloatArray(motes)

    init {
        val r = Seeded(0xCA051)
        for (i in 0 until motes) {
            moteX[i] = r.between(0.3f, 1f)
            moteY[i] = r.next()
            moteZ[i] = r.next()
        }
    }

    override fun DrawScope.build() {
        val w = size.width
        val h = size.height
        cell = 44f * density * fit
        thin = Stroke(width = 1.3f * density * maxOf(fit, 0.7f), cap = StrokeCap.Round, join = StrokeJoin.Round)
        wide = Stroke(width = 7f * density * fit, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val cx = w * 0.76f
        val cy = h * 0.88f
        val reach = size.maxDimension * 0.6f
        net = Brush.radialGradient(
            0f to light.copy(alpha = (0.46f * k).coerceAtMost(1f)),
            0.45f to light.copy(alpha = (0.26f * k).coerceAtMost(1f)),
            0.8f to light.copy(alpha = 0.06f * k),
            1f to light.copy(alpha = 0f),
            center = Offset(cx, cy),
            radius = reach,
        )
        // A pointy-top honeycomb over the whole screen; vertices shared between cells are merged.
        val index = HashMap<Long, Int>()
        val xs = ArrayList<Float>()
        val ys = ArrayList<Float>()
        val pairs = HashSet<Long>()
        val edgeList = ArrayList<Int>()
        val dx = sqrt(3f) * cell
        val dy = 1.5f * cell
        val cols = (w / dx).toInt() + 3
        val rows = (h / dy).toInt() + 3
        fun vertex(x: Float, y: Float): Int {
            val key = ((x * 2f).toInt().toLong() shl 32) or ((y * 2f).toInt().toLong() and 0xFFFFFFFFL)
            return index.getOrPut(key) {
                xs += x
                ys += y
                xs.size - 1
            }
        }
        for (row in -1 until rows) {
            for (col in -1 until cols) {
                val hx = dx * (col + 0.5f * (row and 1))
                val hy = dy * row
                var first = -1
                var prev = -1
                for (c in 0..6) {
                    val a = (PI / 180.0 * (30 + 60 * (c % 6))).toFloat()
                    val v = if (c == 6) first else vertex(hx + cell * cos(a), hy + cell * sin(a))
                    if (c == 0) first = v
                    if (prev >= 0) {
                        val lo = minOf(prev, v)
                        val hi = maxOf(prev, v)
                        val mx = (xs[prev] + xs[v]) / 2f
                        val my = (ys[prev] + ys[v]) / 2f
                        val reachShare = sqrt((mx - cx) * (mx - cx) + (my - cy) * (my - cy)) / reach
                        // Edges too faint to see are never drawn.
                        if (reachShare < 1.05f && pairs.add((lo.toLong() shl 32) or hi.toLong())) {
                            edgeList += prev
                            edgeList += v
                        }
                    }
                    prev = v
                }
            }
        }
        baseX = xs.toFloatArray()
        baseY = ys.toFloatArray()
        posX = FloatArray(baseX.size)
        posY = FloatArray(baseY.size)
        edges = edgeList.toIntArray()
        val r = Seeded(0xCA052)
        edgePhase = FloatArray(edges.size / 2) { r.next() * 6.28f }
    }

    override fun DrawScope.paint(t: Float) {
        val w = size.width
        val h = size.height
        surface.draw(this, w * 0.84f, -h * 0.14f, size.maxDimension * 0.8f, size.maxDimension * 0.55f, alpha = 0.22f * k)
        for (i in 0 until RAYS) {
            val tilt = 22f + 4f * (i % 3)
            val a = tilt * (PI.toFloat() / 180f)
            val len = h * 0.78f
            val ox = w * RAY_X[i]
            val px = ox - sin(a) * len * 0.55f
            val py = -h * 0.06f + cos(a) * len * 0.55f
            val alpha = (0.07f + 0.04f * sin(t * 0.21f + i * 1.9f)) * k
            ray.draw(this, px, py, RAY_W[i] * density * fit, len, alpha = alpha, degrees = tilt)
        }
        drawRect(deep)
        // The net: every vertex moves with smooth fields (neighbours move together, like water, and
        // a slower, larger one stretches some cells and squeezes others), and every edge bows a
        // little. Edges are sorted into a few brightness levels by a slow field of bright patches.
        val f1 = (2 * PI / (5.3f * cell)).toFloat()
        val f2 = (2 * PI / (3.1f * cell)).toFloat()
        val f3 = (2 * PI / (11f * cell)).toFloat()
        val amp = cell * 0.3f
        val big = cell * 0.85f
        for (i in baseX.indices) {
            val bx = baseX[i]
            val by = baseY[i]
            posX[i] = bx + amp * (sin(by * f1 + t * 0.55f) + 0.6f * sin((bx + by) * f2 - t * 0.4f)) + big * sin(by * f3 + bx * f3 * 0.4f + t * 0.12f)
            posY[i] = by + amp * (cos(bx * f1 - t * 0.45f) + 0.6f * cos((bx - by) * f2 + t * 0.47f)) + big * cos(bx * f3 - by * f3 * 0.3f - t * 0.1f)
        }
        for (path in paths) path.reset()
        val g1 = (2 * PI / (7f * cell)).toFloat()
        val g2 = (2 * PI / (5f * cell)).toFloat()
        var e = 0
        while (e < edges.size) {
            val a = edges[e]
            val b = edges[e + 1]
            val ax = posX[a]
            val ay = posY[a]
            val bx = posX[b]
            val by = posY[b]
            val mx = (ax + bx) / 2f
            val my = (ay + by) / 2f
            val patch = 0.5f + 0.5f * sin(mx * g1 + t * 0.21f) * cos(my * g2 - t * 0.17f + mx * g2 * 0.3f)
            val path = paths[(patch * LEVELS).toInt().coerceIn(0, LEVELS - 1)]
            val bend = 0.24f * sin(t * 0.6f + edgePhase[e / 2])
            path.moveTo(ax, ay)
            path.quadraticTo(mx - (by - ay) * bend, my + (bx - ax) * bend, bx, by)
            e += 2
        }
        for (level in 0 until LEVELS) {
            val bright = 0.25f + 0.75f * level / (LEVELS - 1)
            drawPath(paths[level], net, alpha = 0.36f * bright, style = wide)
            drawPath(paths[level], net, alpha = 0.6f * bright, style = thin)
        }
        for (i in 0 until motes) {
            val depth = moteZ[i]
            val d = density * fit
            val x = w * moteX[i] + 8f * d * sin(t * 0.3f + i)
            val y = wrap(moteY[i] * h * 1.1f - t * (3f + 6f * depth) * d, h * 1.1f) - h * 0.05f
            drawCircle(light, radius = (0.8f + 1.2f * depth) * d, center = Offset(x, y), alpha = ((0.12f + 0.3f * depth) * k * calm(x, y)).coerceAtMost(1f))
        }
    }

    private companion object {
        /** Brightness levels of the net. */
        const val LEVELS = 5
        const val RAYS = 5
        val RAY_X = floatArrayOf(0.6f, 0.71f, 0.8f, 0.9f, 1.02f)
        val RAY_W = floatArrayOf(46f, 90f, 58f, 110f, 70f)
    }
}

/**
 * Dunes at noon: four ranges from a hazy far one to a warm near one, drifting very slowly with the
 * wind. Each crest is crisp: its steep slip face sits in a soft shadow, the long face takes the sun
 * high on the right.
 */
internal class DunesScene(look: SceneLook) : Scene(look) {
    private val bright = !look.dark
    private val sand = look.second ?: Color(0xFFE2A46A)
    private val sky = Brush.verticalGradient(
        0f to (if (bright) lerp(lerp(look.ink, Color.White, 0.7f), look.accent, 0.06f) else lerp(look.ink, sand, 0.05f)),
        0.6f to look.ink.copy(alpha = 0f),
    )
    private val sunCore = Glow(if (bright) Color.White else lerp(sand, Color.White, 0.6f))
    private val sunHalo = Glow(lerp(sand, Color.White, 0.35f))
    private val haze = Glow(if (bright) Color.White else lerp(look.ink, sand, 0.2f))
    private val tones = Array(LAYERS) { i -> lerp(look.ink, sand, (if (bright) 1f else 0.4f) * MIX[i]) }
    private val shadowTone = lerp(sand, if (bright) Color(0xFF6B3A1C) else Color.Black, 0.5f)
    private val crestTone = if (bright) Color.White else lerp(sand, Color.White, 0.3f)
    private var bodies = Array<Brush>(LAYERS) { sky }
    private var shades = Array<Brush>(LAYERS) { sky }
    private val bodyPaths = Array(LAYERS) { Path() }
    private val shadePaths = Array(LAYERS) { Path() }
    private val crestPaths = Array(LAYERS) { Path() }
    private val ys = FloatArray(STEPS + 1)
    private val depth = FloatArray(STEPS + 1)
    private var crest = Stroke()

    override val fps: Int = 20

    override fun DrawScope.build() {
        val h = size.height
        crest = Stroke(width = 1.2f * density, cap = StrokeCap.Round, join = StrokeJoin.Round)
        bodies = Array(LAYERS) { i ->
            val top = h * (BASE[i] - AMP[i] * 1.1f)
            Brush.verticalGradient(
                listOf(tones[i], lerp(tones[i], shadowTone, if (bright) 0.16f else 0.3f)),
                startY = top,
                endY = h,
            )
        }
        shades = Array(LAYERS) { i ->
            val base = h * BASE[i]
            val amp = minOf(h, size.width * 0.75f) * AMP[i]
            Brush.verticalGradient(
                0f to shadowTone.copy(alpha = (if (bright) 0.5f else 0.5f) * k.coerceAtMost(1f)),
                1f to shadowTone.copy(alpha = 0f),
                startY = base - amp,
                endY = base + amp * 0.7f,
            )
        }
    }

    override fun DrawScope.paint(t: Float) {
        val w = size.width
        val h = size.height
        drawRect(sky)
        sunHalo.draw(this, w * 0.83f, h * 0.2f, h * 0.55f, alpha = (if (bright) 0.4f else 0.14f) * k)
        sunCore.draw(this, w * 0.83f, h * 0.2f, h * 0.13f, alpha = (if (bright) 0.95f else 0.4f) * k.coerceAtMost(1f))
        haze.draw(this, w * 0.5f, h * 0.6f, w * 0.75f, h * 0.08f, alpha = (if (bright) 0.4f else 0.12f) * k)
        // On a tall screen dunes keep their landscape shape: as high as they would be on a wide
        // screen of the same width, and as long as on one of the same height.
        val tall = minOf(h, w * 0.75f)
        val long = maxOf(w, h * 1.2f)
        for (layer in 0 until LAYERS) {
            val base = h * BASE[layer]
            val amp = tall * AMP[layer]
            val period = long * PERIOD[layer]
            val drift = t * DRIFT[layer] * density / period
            for (i in 0..STEPS) {
                val x = w * i / STEPS
                val u1 = wrap(x / period + drift + layer * 0.37f, 1f)
                val u2 = wrap(x / (period * 0.47f) + drift * 1.3f + layer * 0.61f, 1f)
                ys[i] = base - amp * (0.78f * profile(u1) + 0.22f * profile(u2))
                // The shadow fills the slip face and, just past the crest, curves back up to it, so
                // the ridge line runs down into the dune as it does when seen from a little above.
                depth[i] = amp * 1.15f * when {
                    u1 >= WINDWARD -> smooth(WINDWARD, WINDWARD + 0.1f, u1)
                    u1 < RIDGE -> (1f - u1 / RIDGE).let { it * sqrt(it) }
                    else -> 0f
                }
            }
            val body = bodyPaths[layer]
            val shade = shadePaths[layer]
            val line = crestPaths[layer]
            body.reset()
            shade.reset()
            line.reset()
            body.moveTo(0f, h)
            for (i in 0..STEPS) {
                val x = w * i / STEPS
                body.lineTo(x, ys[i])
                if (i == 0) line.moveTo(x, ys[i]) else line.lineTo(x, ys[i])
            }
            body.lineTo(w, h)
            body.close()
            // The shadow runs along the crest line and back along its lower edge.
            shade.moveTo(0f, ys[0])
            for (i in 1..STEPS) shade.lineTo(w * i / STEPS, ys[i])
            for (i in STEPS downTo 0) shade.lineTo(w * i / STEPS, ys[i] + depth[i])
            shade.close()
            drawPath(body, bodies[layer])
            // Wind ripples across the nearer ranges.
            for (r in 1..RIPPLES[layer]) {
                translate(0f, amp * 0.32f * r) {
                    drawPath(line, shadowTone, alpha = (if (bright) 0.09f else 0.06f) * k.coerceAtMost(1f) / r, style = crest)
                }
            }
            drawPath(shade, shades[layer])
            drawPath(line, crestTone, alpha = (if (bright) 0.55f else 0.2f) * k.coerceAtMost(1f) * (0.5f + 0.5f * layer / (LAYERS - 1)), style = crest)
        }
    }

    /** One dune across a period: 1 at the crest (u = 0), down the long sunlit face, up the steep slip face. */
    private fun profile(u: Float): Float =
        if (u < WINDWARD) 0.5f + 0.5f * cos(PI.toFloat() * u / WINDWARD)
        else ((u - WINDWARD) / (1f - WINDWARD)).pow(1.7f)

    private companion object {
        const val LAYERS = 4
        const val STEPS = 120
        /** The share of each dune that is the long, sunlit face. */
        const val WINDWARD = 0.7f
        /** How far past the crest, as a share of the dune, its shadow curves back up. */
        const val RIDGE = 0.1f
        val BASE = floatArrayOf(0.6f, 0.68f, 0.79f, 0.92f)
        val AMP = floatArrayOf(0.035f, 0.05f, 0.07f, 0.09f)
        val PERIOD = floatArrayOf(0.5f, 0.68f, 0.9f, 1.3f)
        /** How fast each range drifts, in dp a second. */
        val DRIFT = floatArrayOf(1.5f, 2.5f, 4f, 6f)
        val MIX = floatArrayOf(0.15f, 0.3f, 0.5f, 0.72f)
        val RIPPLES = intArrayOf(0, 0, 2, 3)
    }
}
