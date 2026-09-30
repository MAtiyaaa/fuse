package io.github.matiyaaa.fuse.ui.designsystem.shape

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * A rectangle with continuous ("squircle") corners: the curvature ramps up gradually instead of
 * jumping from a straight edge into a circle, which is what makes tiles read as objects rather than
 * boxes. [smoothing] 0 gives a plain rounded rectangle, 0.6 is the Fuse default.
 *
 * The corner is built from two cubic Beziers either side of a shortened circular arc, following the
 * published construction for smoothed corners.
 */
@Immutable
class SquircleShape private constructor(
    private val radius: Dp?,
    private val fraction: Float?,
    private val smoothing: Float,
) : Shape {

    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val short = min(size.width, size.height)
        val r = when {
            radius != null -> with(density) { radius.toPx() }
            else -> short * (fraction ?: 0f)
        }
        if (r <= 0.5f) return Outline.Rectangle(Rect(0f, 0f, size.width, size.height))
        return Outline.Generic(squirclePath(size.width, size.height, r, smoothing))
    }

    override fun equals(other: Any?): Boolean =
        other is SquircleShape && other.radius == radius && other.fraction == fraction && other.smoothing == smoothing

    override fun hashCode(): Int = (radius?.hashCode() ?: 0) * 31 + (fraction?.hashCode() ?: 0) * 17 + smoothing.hashCode()

    companion object {
        /** Fixed corner radius. */
        operator fun invoke(radius: Dp, smoothing: Float = 0.6f) = SquircleShape(radius, null, smoothing)

        /** Corner radius as a fraction of the shorter side, so tiles keep proportions at any size. */
        fun fraction(fraction: Float, smoothing: Float = 0.6f) = SquircleShape(null, fraction, smoothing)
    }
}

/** Builds the continuous-corner outline. Exposed for drawing rings that follow the same curve. */
fun squirclePath(w: Float, h: Float, radius: Float, smoothing: Float): Path {
    val half = min(w, h) / 2f
    val r = min(radius, half)
    val p = min((1f + smoothing) * r, half)
    val s = (p / r - 1f).coerceIn(0f, 1f)

    val arcMeasure = 90f * (1f - s)
    val arcSection = sin(rad(arcMeasure / 2f)) * r * sqrt(2f)
    val alpha = (90f - arcMeasure) / 2f
    val p3p4 = r * tan(rad(alpha / 2f))
    val beta = 45f * s
    val c = p3p4 * cos(rad(beta))
    val d = c * tan(rad(beta))
    val b = (p - arcSection - c - d) / 3f
    val a = 2f * b

    return Path().apply {
        moveTo(p, 0f)
        // top right
        lineTo(w - p, 0f)
        cubicTo(w - p + a, 0f, w - p + a + b, 0f, w - p + a + b + c, d)
        arcTo(Rect(w - 2 * r, 0f, w, 2 * r), -90f + alpha, arcMeasure, false)
        cubicTo(w, p - a - b, w, p - a, w, p)
        // bottom right
        lineTo(w, h - p)
        cubicTo(w, h - p + a, w, h - p + a + b, w - d, h - p + a + b + c)
        arcTo(Rect(w - 2 * r, h - 2 * r, w, h), alpha, arcMeasure, false)
        cubicTo(w - p + a + b, h, w - p + a, h, w - p, h)
        // bottom left
        lineTo(p, h)
        cubicTo(p - a, h, p - a - b, h, p - a - b - c, h - d)
        arcTo(Rect(0f, h - 2 * r, 2 * r, h), 90f + alpha, arcMeasure, false)
        cubicTo(0f, h - p + a + b, 0f, h - p + a, 0f, h - p)
        // top left
        lineTo(0f, p)
        cubicTo(0f, p - a, 0f, p - a - b, d, p - a - b - c)
        arcTo(Rect(0f, 0f, 2 * r, 2 * r), 180f + alpha, arcMeasure, false)
        cubicTo(p - a - b, 0f, p - a, 0f, p, 0f)
        close()
    }
}

private fun rad(deg: Float): Float = (deg * PI / 180.0).toFloat()

/** Pill shape for chips and toggles. */
val PillShape = RoundedCornerShape(percent = 50)
