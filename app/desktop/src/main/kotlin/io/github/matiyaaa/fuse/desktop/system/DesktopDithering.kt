package io.github.matiyaaa.fuse.desktop.system

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.skiaPaint
import androidx.compose.ui.unit.IntSize
import io.github.matiyaaa.fuse.ui.designsystem.effects.Dithering
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ImageInfo

/**
 * Smooth light on a computer's 8-bit screen ([Dithering]). Every gradient Fuse draws behind the
 * interface is wrapped in a small shader ([NOISE]) that adds triangular noise to its colour while it
 * is still in floating point, before the graphics card rounds it to 8 bits: the steps of a dark
 * gradient become an even, fine grain instead of bands. A blurred room is worked out in floating
 * point and rounded to 8 bits once, with a triangular step of signed noise, darker and lighter
 * alike, so it has neither bands nor squares; and any other picture baked once gets that noise too.
 *
 * Skia's own dither (what 0.4.2.7 asked for) is an ordered pattern of half a step: a TV's own picture
 * processing (noise reduction, its sharpening, a picture sent as YCbCr 4:2:0 at 4K and 120 Hz) evens
 * that pattern out and the bands come back. On a TV ([Drawing.largeOutput]) the noise is two steps
 * each way; on a monitor, one. Without the shader (a driver that can't build it) Skia's dither stays.
 */
internal object DesktopDithering {
    fun install() {
        Dithering.gradients = ::ditherGradient
        Dithering.pictures = ::dither
        Dithering.blurs = ::blur
    }

    /**
     * Triangular noise of [amp] (in 8-bit steps, each way) added to [content] before it is rounded.
     * Interleaved gradient noise (Jimenez) by pixel (Compose draws in pixels, so the shader's own
     * coordinates are the screen's), two samples apart, for an even grain with no pattern; a premultiplied colour stays within its alpha, and transparent stays transparent.
     */
    internal const val NOISE = """
        uniform shader content;
        uniform float amp;
        float ign(float2 p) { return fract(52.9829189 * fract(dot(p, float2(0.06711056, 0.00583715)))); }
        half4 main(float2 coords) {
            half4 c = content.eval(coords);
            float2 p = floor(coords);
            float n = ign(p) + ign(p + float2(47.0, 113.0)) - 1.0;
            c.rgb = clamp(c.rgb + half(n * amp / 255.0), 0.0, c.a);
            return c;
        }
    """

    private val noise: org.jetbrains.skia.RuntimeEffect? by lazy {
        runCatching { org.jetbrains.skia.RuntimeEffect.makeForShader(NOISE) }
            .onFailure { io.github.matiyaaa.fuse.desktop.Log.warn("gradients keep Skia's own dither: the noise shader couldn't be built", it) }
            .getOrNull()
    }

    /** Steps of noise each way: two on a TV, one on a monitor. */
    internal fun amplitude(): Float = if (io.github.matiyaaa.fuse.ui.designsystem.effects.Drawing.largeOutput) 2f else 1f

    /** [paint]'s gradient with noise in it before rounding (see [NOISE]); Skia's ordered dither where that can't be. */
    fun ditherGradient(paint: androidx.compose.ui.graphics.Paint) {
        val skia = paint.skiaPaint
        val shader = skia.shader
        val effect = noise
        if (shader == null || effect == null) {
            skia.isDither = true
            return
        }
        val wrapped = runCatching {
            org.jetbrains.skia.RuntimeShaderBuilder(effect).run {
                child("content", shader)
                uniform("amp", amplitude())
                makeShader()
            }
        }.getOrNull()
        if (wrapped == null) skia.isDither = true else skia.shader = wrapped
    }

    /**
     * [small] blurred by [radius] (in its own pixels, as Compose's blur takes it), stretched to
     * [size] with a cubic B-spline and rounded to 8 bits with triangular noise of one step, all in
     * floating point until that last rounding. Kept in 8 bits at a quarter of the size instead, a
     * blurred room has steps one level apart that a stretch widens into bands and a grid of squares.
     */
    fun blur(small: ImageBitmap, radius: Float, size: IntSize): ImageBitmap {
        val source = small.asSkiaBitmap()
        val sw = source.width
        val sh = source.height
        require(sw > 0 && sh > 0 && size.width > 0 && size.height > 0)
        val info = ImageInfo.makeN32Premul(sw, sh)
        val bytes = source.readPixels(info, sw * 4, 0, 0) ?: error("unreadable")
        val plane = FloatArray(sw * sh * 4) { (bytes[it].toInt() and 0xFF).toFloat() }
        gaussian(plane, sw, sh, sigma = 0.57735f * radius + 0.5f)
        val out = stretch(plane, sw, sh, size.width, size.height, seed = sw * 31 + sh)
        val outInfo = ImageInfo.makeN32Premul(size.width, size.height)
        val bitmap = Bitmap()
        check(bitmap.installPixels(outInfo, out, size.width * 4))
        bitmap.setImmutable()
        return bitmap.asComposeImageBitmap()
    }

    /** A gaussian of [sigma] over a 4-channel [plane] in place: three box blurs each way, edges held. */
    fun gaussian(plane: FloatArray, w: Int, h: Int, sigma: Float) {
        if (sigma <= 0.5f) return
        // The three box widths whose sum of variances is closest to sigma squared (Kovesi).
        val ideal = kotlin.math.sqrt(12f * sigma * sigma / 3 + 1)
        var lower = ideal.toInt().let { if (it % 2 == 0) it - 1 else it }.coerceAtLeast(1)
        val upper = lower + 2
        val m = ((12f * sigma * sigma - 3 * lower * lower - 12 * lower - 9) / (-4f * lower - 4)).let { kotlin.math.round(it).toInt() }
        val scratch = FloatArray(plane.size)
        for (pass in 0 until 3) {
            val r = ((if (pass < m) lower else upper) - 1) / 2
            if (r <= 0) continue
            boxRows(plane, scratch, w, h, r)
            boxColumns(scratch, plane, w, h, r)
        }
    }

    private fun boxRows(from: FloatArray, to: FloatArray, w: Int, h: Int, r: Int) {
        val scale = 1f / (2 * r + 1)
        for (y in 0 until h) for (c in 0 until 4) {
            val row = y * w
            fun at(x: Int) = from[(row + x.coerceIn(0, w - 1)) * 4 + c]
            var sum = 0f
            for (k in -r..r) sum += at(k)
            for (x in 0 until w) {
                to[(row + x) * 4 + c] = sum * scale
                sum += at(x + r + 1) - at(x - r)
            }
        }
    }

    private fun boxColumns(from: FloatArray, to: FloatArray, w: Int, h: Int, r: Int) {
        val scale = 1f / (2 * r + 1)
        for (x in 0 until w) for (c in 0 until 4) {
            fun at(y: Int) = from[(y.coerceIn(0, h - 1) * w + x) * 4 + c]
            var sum = 0f
            for (k in -r..r) sum += at(k)
            for (y in 0 until h) {
                to[(y * w + x) * 4 + c] = sum * scale
                sum += at(y + r + 1) - at(y - r)
            }
        }
    }

    /** Cubic B-spline weights for the four samples around a point [t] of the way between two. */
    private fun spline(t: Float, into: FloatArray, at: Int) {
        val t2 = t * t
        val t3 = t2 * t
        into[at] = (1 - 3 * t + 3 * t2 - t3) / 6f
        into[at + 1] = (4 - 6 * t2 + 3 * t3) / 6f
        into[at + 2] = (1 + 3 * t + 3 * t2 - 3 * t3) / 6f
        into[at + 3] = t3 / 6f
    }

    /** Where each output pixel falls among the input's: the first of its four samples and their weights. */
    private fun taps(input: Int, output: Int): Pair<IntArray, FloatArray> {
        val first = IntArray(output)
        val weights = FloatArray(output * 4)
        val step = input.toFloat() / output
        for (o in 0 until output) {
            val p = (o + 0.5f) * step - 0.5f
            val i = kotlin.math.floor(p).toInt()
            first[o] = i - 1
            spline(p - i, weights, o * 4)
        }
        return first to weights
    }

    /**
     * [plane] (sw by sh) stretched to dw by dh with a cubic B-spline, rounded with triangular noise
     * of [steps] each way (two on a TV, whose own processing smooths away one).
     * Each output row is worked out on its own, from the four source rows around it stretched across
     * once and kept, with noise seeded by [seed] and the row: the rows run on every core at once,
     * so a 4K room is ready in a few frames' time instead of holding the move to the next game.
     */
    fun stretch(plane: FloatArray, sw: Int, sh: Int, dw: Int, dh: Int, seed: Int, steps: Float = amplitude()): ByteArray {
        val (xs, xw) = taps(sw, dw)
        val (ys, yw) = taps(sh, dh)
        // Each output column's four source columns, clamped at the edges, as float offsets.
        val xo = IntArray(dw * 4) { (xs[it / 4] + it % 4).coerceIn(0, sw - 1) * 4 }
        val span = dw * 4
        // Each source row stretched across: needed by up to four output rows each, so worked out once.
        val rows = arrayOfNulls<FloatArray>(sh)
        java.util.stream.IntStream.range(0, sh).parallel().forEach { y ->
            val row = FloatArray(span)
            val src = y * sw * 4
            var o = 0
            for (x in 0 until dw) {
                val t = x * 4
                val w0 = xw[t]; val w1 = xw[t + 1]; val w2 = xw[t + 2]; val w3 = xw[t + 3]
                val p0 = src + xo[t]; val p1 = src + xo[t + 1]; val p2 = src + xo[t + 2]; val p3 = src + xo[t + 3]
                for (c in 0 until 4) row[o + c] = w0 * plane[p0 + c] + w1 * plane[p1 + c] + w2 * plane[p2 + c] + w3 * plane[p3 + c]
                o += 4
            }
            rows[y] = row
        }
        val out = ByteArray(dw * dh * 4)
        java.util.stream.IntStream.range(0, dh).parallel().forEach { y ->
            val t = y * 4
            val w0 = yw[t]; val w1 = yw[t + 1]; val w2 = yw[t + 2]; val w3 = yw[t + 3]
            val r0 = rows[ys[y].coerceIn(0, sh - 1)]!!
            val r1 = rows[(ys[y] + 1).coerceIn(0, sh - 1)]!!
            val r2 = rows[(ys[y] + 2).coerceIn(0, sh - 1)]!!
            val r3 = rows[(ys[y] + 3).coerceIn(0, sh - 1)]!!
            var state = (seed.toLong() * 0x9E3779B97F4A7C15uL.toLong()) xor (y.toLong() * 0xBF58476D1CE4E5B9uL.toLong())
            if (state == 0L) state = 1L
            var i = 0
            var o = y * span
            while (i < span) {
                val a = w0 * r0[i + 3] + w1 * r1[i + 3] + w2 * r2[i + 3] + w3 * r3[i + 3]
                val alpha = kotlin.math.round(a).coerceIn(0f, 255f)
                // Triangular noise of [steps] (one, two on a TV): the difference of two even draws, the same for the three colours.
                state = state xor (state shl 13)
                state = state xor (state ushr 7)
                state = state xor (state shl 17)
                val noise = if (alpha > 0f) (((state ushr 40) and 0xFFFF) - ((state ushr 16) and 0xFFFF)).toFloat() / 65536f * steps else 0f
                for (c in 0 until 3) {
                    val v = w0 * r0[i + c] + w1 * r1[i + c] + w2 * r2[i + c] + w3 * r3[i + c]
                    out[o + c] = kotlin.math.round(v + noise).coerceIn(0f, alpha).toInt().toByte()
                }
                out[o + 3] = alpha.toInt().toByte()
                i += 4
                o += 4
            }
        }
        return out
    }

    /** A copy of [image] with signed noise of about one 8-bit step in each colour. */
    fun dither(image: ImageBitmap): ImageBitmap {
        val source = image.asSkiaBitmap()
        val w = source.width
        val h = source.height
        if (w <= 0 || h <= 0) return image
        val info = ImageInfo.makeN32Premul(w, h)
        val bytes = source.readPixels(info, w * 4, 0, 0) ?: return image
        ditherBytes(bytes, seed = w * 31 + h)
        val out = Bitmap()
        if (!out.installPixels(info, bytes, w * 4)) return image
        out.setImmutable()
        return out.asComposeImageBitmap()
    }

    /**
     * Adds triangular noise of -1, 0 or +1 (one 8-bit step) to the three colour bytes of each
     * premultiplied 4-byte pixel in [bytes], the same for all three so it adds no colour, kept
     * within 0 and the pixel's alpha. Transparent pixels stay as they are.
     */
    fun ditherBytes(bytes: ByteArray, seed: Int) {
        var state = seed.toLong() xor 0x9E3779B97F4A7C15uL.toLong()
        if (state == 0L) state = 1L
        var i = 0
        while (i + 3 < bytes.size) {
            val alpha = bytes[i + 3].toInt() and 0xFF
            if (alpha != 0) {
                // xorshift64: two bits make a triangular -1, 0, 0, +1.
                state = state xor (state shl 13)
                state = state xor (state ushr 7)
                state = state xor (state shl 17)
                val n = ((state and 1L) - ((state ushr 1) and 1L)).toInt()
                if (n != 0) {
                    for (c in 0..2) {
                        val v = (bytes[i + c].toInt() and 0xFF) + n
                        bytes[i + c] = v.coerceIn(0, alpha).toByte()
                    }
                }
            }
            i += 4
        }
    }
}
