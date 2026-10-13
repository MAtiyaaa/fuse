package io.github.matiyaaa.fuse.desktop.system

import androidx.compose.ui.unit.IntSize
import org.jetbrains.skia.ImageInfo
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Light over near black, on the 8-bit screen Skia draws to on a computer: no bands. */
class DesktopDitheringTest {
    /** The longest run of one green value along a row of a near-black ramp drawn the way Fuse draws one. */
    private fun longestRun(dither: Boolean): Int {
        val w = 1024
        val image = androidx.compose.ui.graphics.ImageBitmap(w, 8)
        val canvas = androidx.compose.ui.graphics.Canvas(image)
        val paint = androidx.compose.ui.graphics.Paint()
        androidx.compose.ui.graphics.Brush.horizontalGradient(
            listOf(androidx.compose.ui.graphics.Color(4, 5, 8), androidx.compose.ui.graphics.Color(20, 22, 30)), 0f, w.toFloat(),
        ).applyTo(androidx.compose.ui.geometry.Size(w.toFloat(), 8f), paint, 1f)
        if (dither) {
            DesktopDithering.install()
            io.github.matiyaaa.fuse.ui.designsystem.effects.Dithering.gradients!!(paint)
        }
        canvas.drawRect(0f, 0f, w.toFloat(), 8f, paint)
        val pixels = IntArray(w * 8)
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
    fun skiasOwnDitherBreaksTheBandsOfAGlowOverNearBlack() {
        val banded = longestRun(dither = false)
        val smooth = longestRun(dither = true)
        println("Near-black ramp, longest flat band: undithered $banded px, dithered $smooth px")
        // 17 steps over 1024 pixels: undithered, each step is a flat band about 60 pixels wide.
        assertTrue(banded >= 40, "undithered bands $banded px wide")
        assertTrue(smooth <= 12, "dithered runs at most $smooth px")
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
        for ((small, big) in listOf(IntSize(480, 270) to IntSize(1920, 1080), IntSize(960, 540) to IntSize(2560, 1440))) {
            val copy = room(small.width, small.height)
            DesktopDithering.blur(copy, radius = 12f, size = big)
            val start = System.nanoTime()
            DesktopDithering.blur(copy, radius = 12f, size = big)
            println("Baking a ${big.width}x${big.height} room: ${(System.nanoTime() - start) / 1_000_000} ms")
        }
    }
}
