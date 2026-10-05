package io.github.matiyaaa.fuse.ui.designsystem.background

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import kotlin.math.abs
import kotlin.math.round
import kotlin.math.sin
import kotlin.random.Random

/**
 * Fusi's room, in the look of a cute cartoon from the early 2000s: a pink sky with drifting polka
 * dots, puffy clouds and twinkling sparkles, a soft mint hill dotted with pixel flowers, her pink
 * doghouse and her bowl. On a dark palette it is the same room at night, under a lilac sky.
 *
 * In a room of its own ([FusiScreen.OWN]) it draws Fusi too. On a two-screen device the rooms are
 * only the room, and [FusiPet] draws her above whatever art covers it, so she is always in view.
 */
internal class FusiScene(look: SceneLook, private val screen: FusiScreen) : Scene(look) {
    private val night = look.dark
    private val world = if (screen == FusiScreen.OWN) FusiWorld() else null

    private var sky: Brush = Brush.verticalGradient(listOf(Color.White, Color.White))
    private var glow: Brush = sky
    private var glowAt = Offset.Zero
    private var glowR = 0f
    private val farHill = Path()
    private val hill = Path()
    private val hillEdge = Path()
    private var hillBrush: Brush = sky
    private val sparkle = Path()

    private val rnd = Random(611)
    private val cloudX = FloatArray(CLOUDS) { rnd.nextFloat() }
    private val cloudY = FloatArray(CLOUDS) { 0.08f + rnd.nextFloat() * 0.36f }
    private val cloudS = FloatArray(CLOUDS) { 0.7f + rnd.nextFloat() * 0.6f }
    private val sparkX = FloatArray(SPARKLES) { rnd.nextFloat() }
    private val sparkY = FloatArray(SPARKLES) { 0.05f + rnd.nextFloat() * 0.6f }
    private val sparkP = FloatArray(SPARKLES) { rnd.nextFloat() * 6.28f }
    private val flowerX = FloatArray(FLOWERS) { rnd.nextFloat() }
    private val flowerKind = IntArray(FLOWERS) { rnd.nextInt(3) }

    private val dayTop = Color(0xFFFFE9F4)
    private val dayMid = Color(0xFFFFD3E8)
    private val dayLow = Color(0xFFFCC3DF)
    private val nightTop = Color(0xFF241233)
    private val nightMid = Color(0xFF3B1D4B)
    private val nightLow = Color(0xFF55275E)
    private val dot = if (night) Color(0x40E9CBFF) else Color(0xA6FFFFFF)
    private val cloud = if (night) Color(0x40E7D2F5) else Color(0xEEFFFFFF)
    private val cloudShade = if (night) Color(0x22B79AD2) else Color(0x40F7A9CF)
    private val farHillColor = if (night) Color(0xFF3F2456) else Color(0xFFF2C6E6)
    private val hillTop = if (night) Color(0xFF3A2C5E) else Color(0xFFCDF4DF)
    private val hillLow = if (night) Color(0xFF2A1F47) else Color(0xFFADE8C8)
    private val edge = if (night) Color(0x55B9A3E8) else Color(0xCCF0FFF6)
    private val petals = arrayOf(Color(0xFFFF8FBF), if (night) Color(0xFFD9C7FF) else Color(0xFFFFFFFF), Color(0xFFFFB3D4))
    private val flowerHeart = Color(0xFFFFD86B)
    private val stem = if (night) Color(0xFF6E8F7E) else Color(0xFF6CC79A)

    override val fps: Int get() = 30

    override fun DrawScope.build() {
        val w = size.width
        val h = size.height
        sky = Brush.verticalGradient(
            0f to (if (night) nightTop else dayTop),
            0.55f to (if (night) nightMid else dayMid),
            1f to (if (night) nightLow else dayLow),
        )
        glowAt = Offset(w * 0.8f, h * 0.16f)
        glowR = minOf(w, h) * 0.55f
        glow = Brush.radialGradient(
            listOf((if (night) Color(0xFFF6E3FF) else Color.White).copy(alpha = if (night) 0.22f else 0.75f), Color.Transparent),
            glowAt, glowR,
        )
        hillBrush = Brush.verticalGradient(listOf(hillTop, hillLow), startY = h * FusiGround.BASE - 20f * density, endY = h)

        // The far hills: a soft lilac roll behind.
        farHill.reset()
        farHill.moveTo(0f, h)
        var x = 0f
        val stepX = 6f * density
        while (x <= w + stepX) {
            val y = h * (FusiGround.BASE - 0.07f) + sin(x / w * 4.2f + 2.1f) * 16f * density * fit
            farHill.lineTo(x, y)
            x += stepX
        }
        farHill.lineTo(w, h)
        farHill.close()

        // The hill she walks on, following the ground exactly.
        hill.reset()
        hillEdge.reset()
        hill.moveTo(0f, h)
        x = 0f
        while (x <= w + stepX) {
            val y = FusiGround.y(x, w, h, density)
            hill.lineTo(x, y)
            if (x == 0f) hillEdge.moveTo(x, y) else hillEdge.lineTo(x, y)
            x += stepX
        }
        hill.lineTo(w, h)
        hill.close()

        // A four-pointed sparkle, one unit across, scaled where it is drawn.
        sparkle.reset()
        sparkle.moveTo(0f, -1f)
        sparkle.quadraticTo(0.12f, -0.12f, 1f, 0f)
        sparkle.quadraticTo(0.12f, 0.12f, 0f, 1f)
        sparkle.quadraticTo(-0.12f, 0.12f, -1f, 0f)
        sparkle.quadraticTo(-0.12f, -0.12f, 0f, -1f)
        sparkle.close()
    }

    override fun DrawScope.paint(t: Float) {
        val w = size.width
        val h = size.height
        val f = fit
        drawRect(sky)
        drawCircle(glow, glowR, glowAt, alpha = k.coerceAtMost(1f))

        // Polka dots drifting slowly down and across, a 2000s wallpaper above the hill.
        val gap = 46f * density * f
        val r = 2.6f * density * f
        val drift = (t * 6f * density) % gap
        val horizon = h * (FusiGround.BASE - 0.05f)
        var row = 0
        var y = -gap + drift
        while (y < horizon) {
            var x = -gap + drift * 0.6f + if (row % 2 == 0) 0f else gap / 2f
            while (x < w + gap) {
                drawCircle(dot, r, Offset(x, y), alpha = (1f - y / horizon * 0.6f) * k.coerceAtMost(1f))
                x += gap
            }
            y += gap
            row++
        }

        // Puffy clouds, drifting right and round again.
        for (i in 0 until CLOUDS) {
            val s = cloudS[i] * 54f * density * f
            val cx = ((cloudX[i] + t * 0.004f * (0.6f + cloudS[i] * 0.4f)) % 1.2f) * (w + 4f * s) - 2f * s
            val cy = cloudY[i] * h
            drawCloud(cx, cy, s)
        }

        // Sparkles twinkling in turn.
        val sparkleTint = lerp(Color.White, look.accent, if (night) 0.25f else 0.45f)
        for (i in 0 until SPARKLES) {
            val p = sin(t * 1.6f + sparkP[i])
            val a = (p * p) * k.coerceAtMost(1f)
            if (a < 0.04f) continue
            val sx = sparkX[i] * w
            val sy = sparkY[i] * horizon
            val sz = (5f + 5f * a) * density * f
            withTransform({
                translate(sx, sy)
                scale(sz, sz, Offset.Zero)
            }) {
                drawPath(sparkle, sparkleTint, alpha = a)
            }
        }

        drawPath(farHill, farHillColor)
        drawPath(hill, hillBrush)
        drawPath(hillEdge, edge, style = Stroke(2.2f * density * f))

        // Pixel flowers along the hill, nodding a little in the breeze.
        val cell = FusiGround.cell(w, h, density)
        val fc = cell
        for (i in 0 until FLOWERS) {
            val fx = flowerX[i] * w
            // Not in front of her house or her bowl.
            if (abs(flowerX[i] - FusiGround.HOUSE_X) < 0.07f || abs(flowerX[i] - FusiGround.BOWL_X) < 0.04f) continue
            val gy = FusiGround.y(fx, w, h, density) + (4f + (i % 4) * 5f) * density * f
            val nod = if (sin(t * 1.3f + i) > 0.6f) fc else 0f
            drawPixelFlower(fx + nod, gy, fc, petals[flowerKind[i]])
        }

        // Her house, and her bowl.
        val palette = if (night) FusiPalette.night else FusiPalette.day
        val house = FusiFrames.house
        val hx = FusiGround.HOUSE_X * w
        val hgy = FusiGround.y(hx, w, h, density)
        // Her house is drawn larger than she is, so she fits inside it.
        val hc = round(cell * 1.75f)
        with(house) { draw(snap(hx - house.width * hc / 2f), snap(hgy + hc * 1.2f), hc, false, palette) }
        val bowl = FusiFrames.bowl
        val bx = FusiGround.BOWL_X * w
        val bgy = FusiGround.y(bx, w, h, density)
        val bc = round(cell * 1.4f)
        with(bowl) { draw(snap(bx - bowl.width * bc / 2f), snap(bgy + bc), bc, false, palette) }

        world?.let {
            it.frame(0, w, h, density, still = t == 0f)
            it.draw(this, 0, night)
        }
    }

    private fun DrawScope.drawCloud(cx: Float, cy: Float, s: Float) {
        drawRoundRect(cloudShade, Offset(cx - s * 1.05f, cy + s * 0.1f), Size(s * 2.2f, s * 0.62f), CornerRadius(s * 0.31f))
        drawCircle(cloud, s * 0.42f, Offset(cx - s * 0.5f, cy))
        drawCircle(cloud, s * 0.58f, Offset(cx, cy - s * 0.22f))
        drawCircle(cloud, s * 0.4f, Offset(cx + s * 0.55f, cy + s * 0.02f))
        drawRoundRect(cloud, Offset(cx - s * 0.95f, cy), Size(s * 1.95f, s * 0.48f), CornerRadius(s * 0.24f))
    }

    private fun DrawScope.drawPixelFlower(x: Float, y: Float, c: Float, petal: Color) {
        val left = snap(x)
        val top = snap(y)
        drawRect(stem, Offset(left, top), Size(c, c * 3f))
        drawRect(petal, Offset(left, top - 2f * c), Size(c, c))
        drawRect(petal, Offset(left - c, top - c), Size(c, c))
        drawRect(petal, Offset(left + c, top - c), Size(c, c))
        drawRect(petal, Offset(left, top), Size(c, c))
        drawRect(flowerHeart, Offset(left, top - c), Size(c, c))
    }

    private fun snap(v: Float): Float = round(v)

    private companion object {
        const val CLOUDS = 5
        const val SPARKLES = 14
        const val FLOWERS = 18
    }
}

/**
 * Fusi herself, for a screen of a two-screen device (or a main screen whose room is under art): she
 * is drawn here, above whatever covers her room, and moves on at most [fps] frames a second. With
 * motion reduced she sits still.
 */
@Composable
fun FusiPet(
    screen: FusiScreen,
    modifier: Modifier = Modifier,
    animate: Boolean = Fuse.motion.ambient && Fuse.quality.animatedBackground,
    fps: Int = 30,
) {
    val dark = Fuse.colors.isDark
    var tick by remember { mutableIntStateOf(0) }
    if (animate) {
        LaunchedEffect(fps) {
            val frame = 1000L / fps.coerceAtLeast(1)
            var last = 0L
            while (true) {
                withFrameMillis { now ->
                    if (now - last >= frame) {
                        last = now
                        tick++
                    }
                }
            }
        }
    }
    val index = if (screen == FusiScreen.BOTTOM) 1 else 0
    Canvas(modifier.fillMaxSize().graphicsLayer()) {
        // Read here, so a new frame redraws without composing anything.
        if (tick < 0) return@Canvas
        FusiWorld.shared.frame(index, size.width, size.height, density, still = !animate)
        FusiWorld.shared.draw(this, index, dark)
    }
}
