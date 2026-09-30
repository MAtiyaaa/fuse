package io.github.matiyaaa.fuse.desktop.ui

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.painter.Painter

/** Fuse's brand colors for the splash and the window icon. */
object FuseBrand {
    val Ink = Color(0xFF0B0D12)
    val Mark = Color(0xFFF2F4F8)
    val Spark = Color(0xFFFF8A3D)
}

/**
 * Fuse's mark as a [Painter] for the window and taskbar icon: the same geometry as the shell's
 * `FuseMark` (rounded frame, curved fuse line, glowing spark), on an ink tile so it reads on light and
 * dark panels.
 */
class FuseMarkPainter(private val background: Boolean = true) : Painter() {
    override val intrinsicSize: Size = Size(256f, 256f)

    override fun DrawScope.onDraw() {
        val s = size.minDimension
        if (background) drawRoundRect(FuseBrand.Ink, size = Size(s, s), cornerRadius = CornerRadius(s * 0.22f))
        val m = if (background) s * 0.64f else s
        val o = (s - m) / 2
        translate(o, o) { drawFuseMark(m, FuseBrand.Mark, FuseBrand.Spark) }
    }
}

/** Draws the mark in a [w] by [w] box at the origin. */
fun DrawScope.drawFuseMark(w: Float, color: Color, spark: Color) {
    val stroke = w * 0.1f
    drawRoundRect(
        color,
        topLeft = Offset(stroke / 2, stroke / 2),
        size = Size(w - stroke, w - stroke),
        cornerRadius = CornerRadius(w * 0.28f),
        style = Stroke(stroke),
    )
    val fuse = Path().apply {
        moveTo(w * 0.28f, w * 0.7f)
        cubicTo(w * 0.42f, w * 0.7f, w * 0.44f, w * 0.34f, w * 0.64f, w * 0.34f)
    }
    drawPath(fuse, color, style = Stroke(stroke, cap = StrokeCap.Round))
    val center = Offset(w * 0.7f, w * 0.3f)
    drawCircle(
        Brush.radialGradient(listOf(spark, spark.copy(alpha = 0f)), center = center, radius = w * 0.22f),
        radius = w * 0.22f,
        center = center,
    )
    drawCircle(spark, radius = w * 0.07f, center = center)
}
