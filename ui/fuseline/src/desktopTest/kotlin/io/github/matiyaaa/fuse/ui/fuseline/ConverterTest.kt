package io.github.matiyaaa.fuse.ui.fuseline

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The vector core: every type Fuseline moves goes to floats and back. Each converter round-trips
 * exactly (whole numbers, sizes, positions, rectangles, transforms) or to within a hair (colours,
 * through Oklab), at zero, negative and huge values, with the vector length it says, and a threshold
 * below what anyone could see in its units. Colours are fuzzed across the whole sRGB cube.
 */
class ConverterTest {
    private fun <T> roundTrip(c: Converter<T>, value: T): T {
        val out = FloatArray(c.size)
        c.write(value, out)
        return c.read(out)
    }

    @Test
    fun exactTypesRoundTripExactly() {
        val values = floatArrayOf(0f, -0f, 1f, -1f, 0.5f, -123.25f, 1e6f, -1e6f, 3.4e7f)
        for (x in values) for (y in values) {
            assertEquals(x, roundTrip(FloatConverter, x))
            assertEquals(Offset(x, y), roundTrip(OffsetConverter, Offset(x, y)))
            assertEquals(Size(x, y), roundTrip(SizeConverter, Size(x, y)))
            assertEquals(Rect(x, y, x + 1, y + 1), roundTrip(RectConverter, Rect(x, y, x + 1, y + 1)))
            assertEquals(CornerRadius(x, y), roundTrip(CornerRadiusConverter, CornerRadius(x, y)))
            assertEquals(DpOffset(x.dp, y.dp), roundTrip(DpOffsetConverter, DpOffset(x.dp, y.dp)))
            assertEquals(DpSize(x.dp, y.dp), roundTrip(DpSizeConverter, DpSize(x.dp, y.dp)))
            assertEquals(x.dp, roundTrip(DpConverter, x.dp))
        }
        for (i in intArrayOf(0, 1, -1, 1000, -1000, 1 shl 20)) {
            assertEquals(i, roundTrip(IntConverter, i))
            assertEquals(IntOffset(i, -i), roundTrip(IntOffsetConverter, IntOffset(i, -i)))
        }
        // Sizes can't be negative; whole-pixel sizes round, and never below zero.
        assertEquals(IntSize(3, 0), IntSizeConverter.read(floatArrayOf(2.6f, -4f)))
        val t = MotionTransform(10f, -20f, 1.1f, 0.9f, 45f, 0.5f)
        assertEquals(t, roundTrip(MotionTransformConverter, t))
        assertEquals(MotionTransform.Identity, roundTrip(MotionTransformConverter, MotionTransform.Identity))
    }

    @Test
    fun vectorSizesAndThresholds() {
        val sizes = mapOf<Converter<*>, Int>(
            FloatConverter to 1, IntConverter to 1, DpConverter to 1, OffsetConverter to 2, IntOffsetConverter to 2,
            SizeConverter to 2, IntSizeConverter to 2, CornerRadiusConverter to 2, DpOffsetConverter to 2, DpSizeConverter to 2,
            RectConverter to 4, ColorConverter to 4, MotionTransformConverter to 6,
        )
        for ((c, n) in sizes) {
            assertEquals(n, c.size, "$c")
            // Visible threshold: above zero, and no more than a pixel (or a whole number for whole-number types).
            assertTrue(c.threshold > 0f && c.threshold <= 1f, "$c threshold ${c.threshold}")
        }
    }

    @Test
    fun coloursRoundTripThroughOklab() {
        val seed = 0xC010L
        val rnd = Random(seed)
        repeat(20_000) { n ->
            val c = Color(rnd.nextFloat(), rnd.nextFloat(), rnd.nextFloat(), rnd.nextFloat())
            val back = roundTrip(ColorConverter, c)
            assertTrue(abs(c.red - back.red) < 2e-3f && abs(c.green - back.green) < 2e-3f && abs(c.blue - back.blue) < 2e-3f, "seed $seed case $n: $c became $back")
            assertEquals(c.alpha, back.alpha, 1e-6f)
        }
        // The corners of the cube, exactly enough to tell apart.
        for (c in listOf(Color.Black, Color.White, Color.Red, Color.Green, Color.Blue, Color.Transparent)) {
            val back = roundTrip(ColorConverter, c)
            assertEquals(c.red, back.red, 2e-3f)
            assertEquals(c.green, back.green, 2e-3f)
            assertEquals(c.blue, back.blue, 2e-3f)
        }
        // Out-of-gamut values (a spring overshooting) come back clamped into the colour space, never broken.
        val wild = ColorConverter.read(floatArrayOf(2f, 1f, -1f, 1.5f))
        assertTrue(wild.red in 0f..1f && wild.green in 0f..1f && wild.blue in 0f..1f && wild.alpha == 1f)
    }
}
