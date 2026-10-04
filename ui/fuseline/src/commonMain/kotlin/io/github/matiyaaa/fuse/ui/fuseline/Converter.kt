package io.github.matiyaaa.fuse.ui.fuseline

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.cbrt
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * How a value is taken apart into numbers for Fuseline to move one at a time, and put back
 * together. [threshold] is how close counts as arrived for a spring, in the value's own units.
 */
interface Converter<T> {
    val size: Int
    val threshold: Float

    fun write(value: T, out: FloatArray)

    fun read(values: FloatArray): T
}

object FloatConverter : Converter<Float> {
    override val size = 1
    override val threshold = 0.01f
    override fun write(value: Float, out: FloatArray) { out[0] = value }
    override fun read(values: FloatArray): Float = values[0]
}

/** Whole numbers move through the fractions between them and are rounded when read. */
object IntConverter : Converter<Int> {
    override val size = 1
    override val threshold = 1f
    override fun write(value: Int, out: FloatArray) { out[0] = value.toFloat() }
    override fun read(values: FloatArray): Int = values[0].roundToInt()
}

object DpConverter : Converter<Dp> {
    override val size = 1
    override val threshold = 0.1f
    override fun write(value: Dp, out: FloatArray) { out[0] = value.value }
    override fun read(values: FloatArray): Dp = values[0].dp
}

object OffsetConverter : Converter<Offset> {
    override val size = 2
    override val threshold = 0.5f
    override fun write(value: Offset, out: FloatArray) { out[0] = value.x; out[1] = value.y }
    override fun read(values: FloatArray): Offset = Offset(values[0], values[1])
}

object IntOffsetConverter : Converter<IntOffset> {
    override val size = 2
    override val threshold = 1f
    override fun write(value: IntOffset, out: FloatArray) { out[0] = value.x.toFloat(); out[1] = value.y.toFloat() }
    override fun read(values: FloatArray): IntOffset = IntOffset(values[0].roundToInt(), values[1].roundToInt())
}

object SizeConverter : Converter<Size> {
    override val size = 2
    override val threshold = 0.5f
    override fun write(value: Size, out: FloatArray) { out[0] = value.width; out[1] = value.height }
    override fun read(values: FloatArray): Size = Size(values[0], values[1])
}

object IntSizeConverter : Converter<IntSize> {
    override val size = 2
    override val threshold = 1f
    override fun write(value: IntSize, out: FloatArray) { out[0] = value.width.toFloat(); out[1] = value.height.toFloat() }
    override fun read(values: FloatArray): IntSize = IntSize(values[0].roundToInt().coerceAtLeast(0), values[1].roundToInt().coerceAtLeast(0))
}

object RectConverter : Converter<Rect> {
    override val size = 4
    override val threshold = 0.5f
    override fun write(value: Rect, out: FloatArray) { out[0] = value.left; out[1] = value.top; out[2] = value.right; out[3] = value.bottom }
    override fun read(values: FloatArray): Rect = Rect(values[0], values[1], values[2], values[3])
}

/**
 * Colours move through Oklab, a space where equal steps look like equal changes, so a fade from
 * one colour to another stays bright and even instead of dipping grey through the middle. Alpha
 * moves on its own, straight. The conversion is Björn Ottosson's Oklab, from sRGB.
 */
object ColorConverter : Converter<Color> {
    override val size = 4
    override val threshold = 0.001f

    override fun write(value: Color, out: FloatArray) {
        val c = value.convert(ColorSpaces.Srgb)
        Oklab.fromSrgb(c.red, c.green, c.blue, out)
        out[3] = c.alpha
    }

    override fun read(values: FloatArray): Color {
        val rgb = FloatArray(3)
        Oklab.toSrgb(values[0], values[1], values[2], rgb)
        return Color(rgb[0].coerceIn(0f, 1f), rgb[1].coerceIn(0f, 1f), rgb[2].coerceIn(0f, 1f), values[3].coerceIn(0f, 1f))
    }
}

/** sRGB to and from Oklab (L, a, b). */
internal object Oklab {
    private fun linear(c: Float): Float = if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).toDouble().pow(2.4).toFloat()

    private fun encoded(c: Float): Float = if (c <= 0.0031308f) c * 12.92f else (1.055 * c.toDouble().pow(1.0 / 2.4) - 0.055).toFloat()

    fun fromSrgb(r: Float, g: Float, b: Float, out: FloatArray) {
        val lr = linear(r)
        val lg = linear(g)
        val lb = linear(b)
        val l = cbrt(0.4122214708f * lr + 0.5363325363f * lg + 0.0514459929f * lb)
        val m = cbrt(0.2119034982f * lr + 0.6806995451f * lg + 0.1073969566f * lb)
        val s = cbrt(0.0883024619f * lr + 0.2817188376f * lg + 0.6299787005f * lb)
        out[0] = 0.2104542553f * l + 0.7936177850f * m - 0.0040720468f * s
        out[1] = 1.9779984951f * l - 2.4285922050f * m + 0.4505937099f * s
        out[2] = 0.0259040371f * l + 0.7827717662f * m - 0.8086757660f * s
    }

    fun toSrgb(lightness: Float, a: Float, b: Float, out: FloatArray) {
        val l = lightness + 0.3963377774f * a + 0.2158037573f * b
        val m = lightness - 0.1055613458f * a - 0.0638541728f * b
        val s = lightness - 0.0894841775f * a - 1.2914855480f * b
        val l3 = l * l * l
        val m3 = m * m * m
        val s3 = s * s * s
        out[0] = encoded((4.0767416621f * l3 - 3.3077115913f * m3 + 0.2309699292f * s3).coerceIn(0f, 1f))
        out[1] = encoded((-1.2684380046f * l3 + 2.6097574011f * m3 - 0.3413193965f * s3).coerceIn(0f, 1f))
        out[2] = encoded((-0.0041960863f * l3 - 0.7034186147f * m3 + 1.7076147010f * s3).coerceIn(0f, 1f))
    }
}
