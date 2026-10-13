package io.github.matiyaaa.fuse.ui.designsystem.effects

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas

/**
 * What the platform does for smooth light on an 8-bit screen, installed by the app at start.
 *
 * A soft glow or a blurred picture over a dark room has more steps of brightness than 8 bits hold,
 * so it breaks into bands; and a picture worked out small and shown large shows its grid. Skia on a
 * computer doesn't dither its gradients unless asked, unlike Android's, so the desktop app sets
 * [gradients] (Skia's own ordered dither, on the graphics card and on the processor alike) and
 * [pictures] (signed noise baked into a picture once, so it costs nothing a frame) and [blurs] (a
 * blurred room worked out in full precision and rounded to 8 bits once, with noise). Where nothing
 * is installed, drawing is as it was.
 */
object Dithering {
    /** Asks the platform to dither what [Paint] draws (its gradients), where it can. */
    @Volatile var gradients: ((Paint) -> Unit)? = null

    /** A copy of a picture with about one 8-bit step of signed noise in it, or the picture itself. */
    @Volatile var pictures: ((ImageBitmap) -> ImageBitmap)? = null

    /**
     * A sharp small copy blurred by a radius in its own pixels, stretched to a size, and rounded to
     * 8 bits with signed noise only then, so it has neither bands nor the small copy's grid.
     */
    @Volatile var blurs: ((small: ImageBitmap, radius: Float, size: androidx.compose.ui.unit.IntSize) -> ImageBitmap)? = null

    fun picture(image: ImageBitmap): ImageBitmap = pictures?.let { runCatching { it(image) }.getOrNull() } ?: image
}

/** [brush] over the whole scope, dithered where the platform can ([Dithering.gradients]). */
fun androidx.compose.ui.graphics.drawscope.DrawScope.drawDitheredRect(brush: androidx.compose.ui.graphics.Brush, alpha: Float = 1f) {
    val dither = Dithering.gradients ?: return drawRect(brush, alpha = alpha)
    drawIntoCanvas { canvas ->
        val paint = Paint()
        brush.applyTo(size, paint, alpha)
        dither(paint)
        canvas.drawRect(0f, 0f, size.width, size.height, paint)
    }
}

/** A circle of [brush], dithered where the platform can ([Dithering.gradients]). */
fun androidx.compose.ui.graphics.drawscope.DrawScope.drawDitheredCircle(
    brush: androidx.compose.ui.graphics.Brush,
    radius: Float,
    center: androidx.compose.ui.geometry.Offset,
    alpha: Float = 1f,
) {
    val dither = Dithering.gradients ?: return drawCircle(brush, radius = radius, center = center, alpha = alpha)
    drawIntoCanvas { canvas ->
        val paint = Paint()
        brush.applyTo(androidx.compose.ui.geometry.Size(radius * 2, radius * 2), paint, alpha)
        dither(paint)
        canvas.drawCircle(center, radius, paint)
    }
}
