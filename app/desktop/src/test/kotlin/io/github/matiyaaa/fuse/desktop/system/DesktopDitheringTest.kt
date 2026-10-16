package io.github.matiyaaa.fuse.desktop.system

import androidx.compose.ui.unit.IntSize
import org.jetbrains.skia.ImageInfo
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Light over near black, on the 8-bit screen Skia draws to on a computer: no bands. */
class DesktopDitheringTest {
    /** A near-black ramp, green 5 to 22 across [w] pixels and [h] rows, drawn the way Fuse draws one; its pixels. */
    private fun ramp(dither: Boolean, w: Int = 1024, h: Int = 8, alpha: Float = 1f): IntArray {
        val image = androidx.compose.ui.graphics.ImageBitmap(w, h)
        val canvas = androidx.compose.ui.graphics.Canvas(image)
        val paint = androidx.compose.ui.graphics.Paint()
        androidx.compose.ui.graphics.Brush.horizontalGradient(
            listOf(androidx.compose.ui.graphics.Color(4, 5, 8, (alpha * 255).toInt()), androidx.compose.ui.graphics.Color(20, 22, 30, (alpha * 255).toInt())), 0f, w.toFloat(),
        ).applyTo(androidx.compose.ui.geometry.Size(w.toFloat(), h.toFloat()), paint, 1f)
        if (dither) {
            DesktopDithering.install()
            io.github.matiyaaa.fuse.ui.designsystem.effects.Dithering.gradients!!(paint)
        }
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
        val pixels = IntArray(w * h)
        image.readPixels(pixels)
        return pixels
    }

    /** The longest run of one green value along a row of a near-black ramp drawn the way Fuse draws one. */
    private fun longestRun(dither: Boolean): Int {
        val w = 1024
        val pixels = ramp(dither, w)
        var longest = 0
        var run = 0
        var last = -1
        for (x in 0 until w) {
            val g = (pixels[4 * w + x] shr 8) and 0xFF
            run = if (g == last) run + 1 else 1
            last = g
            longest = maxOf(longest, run)
        }
        return longest
    }

    @Test
    fun ditherBreaksTheBandsOfAGlowOverNearBlack() {
        val banded = longestRun(dither = false)
        val smooth = longestRun(dither = true)
        println("Near-black ramp, longest flat band: undithered $banded px, dithered $smooth px")
        // 17 steps over 1024 pixels: undithered, each step is a flat band about 60 pixels wide.
        assertTrue(banded >= 40, "undithered bands $banded px wide")
        assertTrue(smooth <= 12, "dithered runs at most $smooth px")
    }

    @Test
    fun theNoiseGoesInBeforeRoundingAndKeepsTheLevel() {
        val w = 1024
        val h = 64
        val plain = ramp(dither = false, w = w, h = h)
        val noisy = ramp(dither = true, w = w, h = h)
        fun green(p: IntArray, i: Int) = (p[i] shr 8) and 0xFF
        // Neither lighter nor darker on the whole (a dither drawn over the top adds light).
        val mean = noisy.indices.map { green(noisy, it) }.average() - plain.indices.map { green(plain, it) }.average()
        assertTrue(abs(mean) < 0.1, "the level moved by $mean steps")
        // Seen the way a TV's own smoothing sees it, 16 by 16 pixels at a time, it follows the ramp
        // itself between the steps: what reaches the eye is the gradient, not its bands.
        var worst = 0.0
        for (bx in 0 until w / 16) for (by in 0 until h / 16) {
            var sum = 0.0
            for (x in 0 until 16) for (y in 0 until 16) sum += green(noisy, (by * 16 + y) * w + bx * 16 + x)
            val ideal = 5.0 + 17.0 * (bx * 16 + 7.5) / w
            worst = maxOf(worst, abs(sum / 256 - ideal))
        }
        assertTrue(worst < 0.3, "a 16 by 16 patch was ${"%.2f".format(worst)} steps off the ramp")
    }

    @Test
    fun theNoiseIsStrongerOnATvAndNeverMoreThanAsked() {
        fun spread(large: Boolean): Int {
            io.github.matiyaaa.fuse.ui.designsystem.effects.Drawing.largeOutput = large
            try {
                val plain = ramp(dither = false, w = 256, h = 64)
                val noisy = ramp(dither = true, w = 256, h = 64)
                return noisy.indices.maxOf { abs(((noisy[it] shr 8) and 0xFF) - ((plain[it] shr 8) and 0xFF)) }
            } finally {
                io.github.matiyaaa.fuse.ui.designsystem.effects.Drawing.largeOutput = false
            }
        }
        assertTrue(spread(large = false) in 1..2, "monitor ${spread(false)}")
        assertTrue(spread(large = true) in 2..3, "TV ${spread(true)}")
    }

    @Test
    fun transparentStaysTransparent() {
        val pixels = ramp(dither = true, w = 256, h = 16, alpha = 0f)
        assertTrue(pixels.all { it == 0 }, "noise in a transparent gradient")
    }

    @Test
    fun aBakedPictureGetsSignedNoiseOfOneStepAndKeepsItsLevel() {
        val w = 256
        val bytes = ByteArray(w * 4) { i -> if (i % 4 == 3) 0xFF.toByte() else 40 }
        // A transparent pixel stays as it is.
        bytes[3] = 0; bytes[0] = 0; bytes[1] = 0; bytes[2] = 0
        DesktopDithering.ditherBytes(bytes, seed = 7)
        val values = (1 until w).map { bytes[it * 4 + 1].toInt() and 0xFF }
        assertTrue(values.all { abs(it - 40) <= 1 }, "never more than one step")
        assertTrue(values.any { it == 39 } && values.any { it == 41 }, "darker and lighter alike")
        val mean = values.average()
        assertTrue(abs(mean - 40) < 0.2, "the level is kept, mean $mean")
        assertEquals(0, bytes[0].toInt())
        // Never past the pixel's alpha (premultiplied).
        val dim = ByteArray(4 * 64) { i -> if (i % 4 == 3) 10 else 10 }
        DesktopDithering.ditherBytes(dim, seed = 3)
        assertTrue(dim.indices.filter { it % 4 != 3 }.all { (dim[it].toInt() and 0xFF) <= 10 })
        // Unused import guard: ImageInfo stays referenced for the layout this works on.
        assertEquals(4, ImageInfo.makeN32Premul(1, 1).bytesPerPixel)
    }

    /** A dark room: a soft warm light over near black, at [w] by [h], the way a blurred cover looks. */
    private fun room(w: Int, h: Int): androidx.compose.ui.graphics.ImageBitmap {
        val image = androidx.compose.ui.graphics.ImageBitmap(w, h)
        val paint = androidx.compose.ui.graphics.Paint()
        androidx.compose.ui.graphics.Brush.radialGradient(
            listOf(androidx.compose.ui.graphics.Color(0xFF3A2418), androidx.compose.ui.graphics.Color(0xFF07080B)),
            center = androidx.compose.ui.geometry.Offset(w * 0.6875f, h / 3f), radius = w * 0.5625f,
        ).applyTo(androidx.compose.ui.geometry.Size(w.toFloat(), h.toFloat()), paint, 1f)
        androidx.compose.ui.graphics.Canvas(image).drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
        return image
    }

    private fun green(image: androidx.compose.ui.graphics.ImageBitmap): IntArray {
        val px = IntArray(image.width * image.height)
        image.readPixels(px)
        return IntArray(px.size) { (px[it] shr 8) and 0xFF }
    }

    /**
     * How often a pixel along the rows through the light, where it fades (x from 16% to 68%), has
     * the same value as the next: near 1 where the fade is flat bands, much lower where it is dithered.
     */
    private fun sameAsNext(g: IntArray, w: Int, h: Int): Double {
        var same = 0
        var all = 0
        for (y in h / 4 until h / 2) for (x in (w * 0.16f).toInt() until (w * 0.68f).toInt()) {
            if (g[y * w + x] == g[y * w + x + 1]) same++
            all++
        }
        return same.toDouble() / all
    }

    @Test
    fun aBlurredRoomWorkedOutInFullPrecisionHasNoBands() {
        val size = IntSize(1280, 720)
        // What Fuse drew until now: the room in 8 bits at a quarter of the size, stretched every frame.
        val old = androidx.compose.ui.graphics.ImageBitmap(size.width, size.height)
        androidx.compose.ui.graphics.Canvas(old).drawImageRect(
            room(320, 180), srcSize = IntSize(320, 180), dstSize = size,
            paint = androidx.compose.ui.graphics.Paint().apply { filterQuality = androidx.compose.ui.graphics.FilterQuality.Medium },
        )
        val baked = DesktopDithering.blur(room(320, 180), radius = 12f, size = size)
        assertEquals(size.width, baked.width)
        assertEquals(size.height, baked.height)
        System.getenv("FUSE_BAKE_PNG")?.let { dir ->
            for ((name, img) in listOf("before" to old, "after" to baked)) {
                val px = IntArray(size.width * size.height)
                img.readPixels(px)
                val out = java.awt.image.BufferedImage(size.width, size.height, java.awt.image.BufferedImage.TYPE_INT_RGB)
                fun boost(v: Int) = ((v - 4) * 6).coerceIn(0, 255)
                for (k in px.indices) {
                    out.setRGB(k % size.width, k / size.width, (boost((px[k] shr 16) and 0xFF) shl 16) or (boost((px[k] shr 8) and 0xFF) shl 8) or boost(px[k] and 0xFF))
                }
                javax.imageio.ImageIO.write(out, "png", java.io.File(dir, "$name.png"))
            }
        }
        val before = sameAsNext(green(old), size.width, size.height)
        val after = sameAsNext(green(baked), size.width, size.height)
        println("Dark room, share of pixels equal to the next: stretched every frame ${"%.2f".format(before)}, baked ${"%.2f".format(after)}")
        assertTrue(before >= 0.85, "the old stretch is flat bands, $before")
        assertTrue(after <= 0.75, "the baked room is dithered, $after")
    }

    @Test
    fun theBlurKeepsTheLevelOfAFlatPicture() {
        val flat = androidx.compose.ui.graphics.ImageBitmap(40, 24)
        androidx.compose.ui.graphics.Canvas(flat).drawRect(0f, 0f, 40f, 24f, androidx.compose.ui.graphics.Paint().apply { color = androidx.compose.ui.graphics.Color(0xFF282828) })
        val g = green(DesktopDithering.blur(flat, radius = 6f, size = IntSize(160, 96)))
        assertTrue(g.all { abs(it - 0x28) <= 1 }, "within one step everywhere")
        assertTrue(abs(g.average() - 0x28) < 0.1, "the level is kept, mean ${g.average()}")
    }

    /** How long baking a room takes, once per cover, off the drawing thread (printed, not held to a number). */
    @Test
    fun aRoomBakesOnceInATimeWorthPrinting() {
        for ((small, big) in listOf(IntSize(480, 270) to IntSize(1920, 1080), IntSize(960, 540) to IntSize(2560, 1440), IntSize(960, 540) to IntSize(3840, 2160))) {
            val copy = room(small.width, small.height)
            // The fastest of a few, once the compiler has caught up.
            val best = (0 until 6).minOf {
                val start = System.nanoTime()
                DesktopDithering.blur(copy, radius = 12f, size = big)
                (System.nanoTime() - start) / 1_000_000
            }
            println("Baking a ${big.width}x${big.height} room: $best ms")
        }
    }

    /**
     * A soft light drawn faint (a glow at an eighth, as the room behind Fuse draws its lights) over
     * black, under a transform that scales a small drawing up (as a glow is drawn): its pixels.
     */
    private fun faintScaledRamp(dither: Boolean, w: Int = 1024, h: Int = 8): IntArray {
        val image = androidx.compose.ui.graphics.ImageBitmap(w, h)
        val canvas = androidx.compose.ui.graphics.Canvas(image)
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), androidx.compose.ui.graphics.Paint().apply { color = androidx.compose.ui.graphics.Color.Black })
        val scale = 32f
        val paint = androidx.compose.ui.graphics.Paint()
        androidx.compose.ui.graphics.Brush.horizontalGradient(
            listOf(androidx.compose.ui.graphics.Color(0, 0, 0), androidx.compose.ui.graphics.Color(255, 255, 255)), 0f, w / scale,
        ).applyTo(androidx.compose.ui.geometry.Size(w / scale, h / scale), paint, 0.125f)
        if (dither) {
            DesktopDithering.install()
            // As a glow is drawn: told the scale it is drawn under.
            io.github.matiyaaa.fuse.ui.designsystem.effects.Dithering.scaledGradients!!(paint, scale, scale)
        }
        canvas.save()
        canvas.scale(scale, scale)
        canvas.drawRect(0f, 0f, w / scale, h / scale, paint)
        canvas.restore()
        val pixels = IntArray(w * h)
        image.readPixels(pixels)
        return pixels
    }

    @Test
    fun aFaintLightDrawnScaledUpGetsAFullStepOfFineNoise() {
        val w = 1024
        fun longest(p: IntArray): Int {
            var longest = 0
            var run = 0
            var last = -1
            for (x in 0 until w) {
                val g = (p[4 * w + x] shr 8) and 0xFF
                run = if (g == last) run + 1 else 1
                last = g
                longest = maxOf(longest, run)
            }
            return longest
        }
        val banded = longest(faintScaledRamp(dither = false))
        val smooth = longest(faintScaledRamp(dither = true))
        println("Faint light scaled up, longest flat band: undithered $banded px, dithered $smooth px")
        // 32 steps over 1024 pixels: about 32 pixels each undithered. The noise must survive the
        // paint's alpha and be laid out per screen pixel, not per scaled-up unit (the scale it is
        // told, as Skia here has no sk_FragCoord for runtime shaders).
        assertTrue(banded >= 24, "undithered bands $banded px wide")
        assertTrue(smooth <= 10, "dithered runs at most $smooth px")
    }

    /**
     * A dithered near-black ramp with a black dim over it at [dim], as a room is darkened behind the
     * interface: the longest run of one green value along a row.
     */
    private fun dimmedRun(ditherDim: Boolean, dim: Float = 0.6f, w: Int = 1024, h: Int = 8): Int {
        val image = androidx.compose.ui.graphics.ImageBitmap(w, h)
        val canvas = androidx.compose.ui.graphics.Canvas(image)
        val ramp = androidx.compose.ui.graphics.Paint()
        // Green 10 to 54: after a dim of 0.6, 4 to 22, steps about 57 px apart.
        androidx.compose.ui.graphics.Brush.horizontalGradient(
            listOf(androidx.compose.ui.graphics.Color(8, 10, 14), androidx.compose.ui.graphics.Color(48, 54, 70)), 0f, w.toFloat(),
        ).applyTo(androidx.compose.ui.geometry.Size(w.toFloat(), h.toFloat()), ramp, 1f)
        DesktopDithering.install()
        io.github.matiyaaa.fuse.ui.designsystem.effects.Dithering.gradients!!(ramp)
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), ramp)
        val veil = androidx.compose.ui.graphics.Paint()
        androidx.compose.ui.graphics.SolidColor(androidx.compose.ui.graphics.Color.Black.copy(alpha = dim))
            .applyTo(androidx.compose.ui.geometry.Size(w.toFloat(), h.toFloat()), veil, 1f)
        if (ditherDim) io.github.matiyaaa.fuse.ui.designsystem.effects.Dithering.placedGradients!!(veil, canvas)
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), veil)
        val pixels = IntArray(w * h)
        image.readPixels(pixels)
        var longest = 0
        var run = 0
        var last = -1
        for (x in 0 until w) {
            val g = (pixels[4 * w + x] shr 8) and 0xFF
            run = if (g == last) run + 1 else 1
            last = g
            longest = maxOf(longest, run)
        }
        return longest
    }

    @Test
    fun aDimOverADitheredRoomKeepsItSmooth() {
        // A dim of 0.6 leaves 0.4 of the room's noise, under half a step: its bands come back
        // (Linux, 0.4.2.22). The dim's own noise brings a full step back.
        val plain = dimmedRun(ditherDim = false)
        val dithered = dimmedRun(ditherDim = true)
        println("Dimmed room, longest flat band: plain dim $plain px, dithered dim $dithered px")
        assertTrue(plain >= 20, "a plain dim leaves bands, $plain px")
        assertTrue(dithered <= 12, "a dithered dim keeps it smooth, $dithered px")
    }

    /** A flat grey drawn through the noise on a canvas moved by [dx] (a fraction of a pixel): its pixels. */
    private fun greyMovedBy(dx: Float, w: Int = 256, h: Int = 4): IntArray {
        val image = androidx.compose.ui.graphics.ImageBitmap(w, h)
        val canvas = androidx.compose.ui.graphics.Canvas(image)
        val grey = androidx.compose.ui.graphics.Color(60, 60, 60)
        val paint = androidx.compose.ui.graphics.Paint()
        androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(grey, grey), 0f, w.toFloat()).applyTo(androidx.compose.ui.geometry.Size(w.toFloat(), h.toFloat()), paint, 1f)
        DesktopDithering.install()
        canvas.save()
        canvas.translate(dx, 0f)
        io.github.matiyaaa.fuse.ui.designsystem.effects.Dithering.placedGradients!!(paint, canvas)
        canvas.drawRect(-8f, 0f, w + 8f, h.toFloat(), paint)
        canvas.restore()
        val pixels = IntArray(w * h)
        image.readPixels(pixels)
        return pixels
    }

    @Test
    fun theGrainStaysOnTheScreenAsALightMoves() {
        // A soft light drifting behind the interface moves by fractions of a pixel each frame: its
        // grain must stay where it is on the screen, or it shimmers.
        val still = greyMovedBy(0f)
        val moved = greyMovedBy(3.4f)
        val same = still.indices.count { still[it] == moved[it] }
        println("Grain unchanged after the light moved: $same of ${still.size} pixels")
        assertTrue(same >= still.size * 95 / 100, "only $same of ${still.size} pixels kept their grain")
        // And there is grain at all.
        assertTrue(still.distinct().size > 1)
    }
}
