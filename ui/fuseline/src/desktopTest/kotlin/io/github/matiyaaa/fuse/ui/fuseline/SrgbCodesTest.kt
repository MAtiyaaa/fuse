package io.github.matiyaaa.fuse.ui.fuseline

import androidx.compose.ui.graphics.Color
import kotlin.math.pow
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Fuseline 4.4 reads a moving colour without sRGB's transfer curve: the 8-bit codes come from a
 * table of where each code begins. These prove it makes the very same colours, to the bit, as the
 * curve and Compose's packing did.
 */
class SrgbCodesTest {
    @Test
    fun everyCodeBeginsWhereTheCurveSaysItDoes() {
        for (k in 1..255) {
            val at = SrgbCodes.step[k]
            assertEquals(k, SrgbCodes.code(at), "code at its own step $k")
            assertEquals(k, SrgbCodes.exact(at), "the curve at step $k")
            val before = Float.fromBits(at.toRawBits() - 1)
            assertEquals(SrgbCodes.exact(before), SrgbCodes.code(before), "just below step $k")
        }
        assertEquals(0, SrgbCodes.code(0f))
        assertEquals(255, SrgbCodes.code(1f))
    }

    @Test
    fun aMillionLinearValuesAgree() {
        val r = Random(44)
        repeat(1_000_000) {
            val c = when (it % 4) {
                0 -> r.nextFloat()
                1 -> r.nextFloat() * 0.01f
                2 -> Float.fromBits(r.nextInt(0, 1f.toRawBits() + 1))
                else -> 1f - r.nextFloat() * 0.001f
            }
            assertEquals(SrgbCodes.exact(c), SrgbCodes.code(c), "linear $c")
        }
    }

    @Test
    fun movingColoursReadAsBefore() {
        val r = Random(4)
        val v = FloatArray(4)
        repeat(1_000_000) {
            v[0] = r.nextFloat() * 1.1f - 0.05f
            v[1] = r.nextFloat() * 0.8f - 0.4f
            v[2] = r.nextFloat() * 0.8f - 0.4f
            v[3] = r.nextFloat() * 1.2f - 0.1f
            assertEquals(before(v), ColorConverter.read(v), "Oklab ${v.toList()}")
        }
        // Every code survives the trip back through Compose's packing.
        for (k in 0..255) assertEquals(k, ((Color(k / 255f, 0f, 0f, 1f).value shr 48).toInt() and 0xFF))
    }

    /** The colour read as Fuseline 4.2 read it: the transfer curve's power on each channel. */
    private fun before(values: FloatArray): Color {
        val l = values[0] + 0.3963377774f * values[1] + 0.2158037573f * values[2]
        val m = values[0] - 0.1055613458f * values[1] - 0.0638541728f * values[2]
        val s = values[0] - 0.0894841775f * values[1] - 1.2914855480f * values[2]
        val l3 = l * l * l
        val m3 = m * m * m
        val s3 = s * s * s
        fun enc(c: Float): Float = if (c <= 0.0031308f) c * 12.92f else (1.055 * c.toDouble().pow(1.0 / 2.4) - 0.055).toFloat()
        val red = enc((4.0767416621f * l3 - 3.3077115913f * m3 + 0.2309699292f * s3).coerceIn(0f, 1f))
        val green = enc((-1.2684380046f * l3 + 2.6097574011f * m3 - 0.3413193965f * s3).coerceIn(0f, 1f))
        val blue = enc((-0.0041960863f * l3 - 0.7034186147f * m3 + 1.7076147010f * s3).coerceIn(0f, 1f))
        return Color(red.coerceIn(0f, 1f), green.coerceIn(0f, 1f), blue.coerceIn(0f, 1f), values[3].coerceIn(0f, 1f))
    }
}
