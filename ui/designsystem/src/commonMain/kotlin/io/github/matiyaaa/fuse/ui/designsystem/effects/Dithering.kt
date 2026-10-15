package io.github.matiyaaa.fuse.ui.designsystem.effects

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.draw.drawWithCache
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

/** [brush] over the whole scope (or [size] at [topLeft], as `drawRect`), dithered where the platform can ([Dithering.gradients]). */
fun androidx.compose.ui.graphics.drawscope.DrawScope.drawDitheredRect(
    brush: androidx.compose.ui.graphics.Brush,
    alpha: Float = 1f,
    topLeft: androidx.compose.ui.geometry.Offset = androidx.compose.ui.geometry.Offset.Zero,
    size: androidx.compose.ui.geometry.Size = this.size.let { androidx.compose.ui.geometry.Size(it.width - topLeft.x, it.height - topLeft.y) },
) {
    val dither = Dithering.gradients ?: return drawRect(brush, topLeft = topLeft, size = size, alpha = alpha)
    drawIntoCanvas { canvas ->
        val paint = Paint()
        brush.applyTo(size, paint, alpha)
        dither(paint)
        canvas.drawRect(topLeft.x, topLeft.y, topLeft.x + size.width, topLeft.y + size.height, paint)
    }
}

/** [path] filled with [brush], as `drawPath`, dithered where the platform can ([Dithering.gradients]). */
fun androidx.compose.ui.graphics.drawscope.DrawScope.drawDitheredPath(path: androidx.compose.ui.graphics.Path, brush: androidx.compose.ui.graphics.Brush, alpha: Float = 1f) {
    val dither = Dithering.gradients ?: return drawPath(path, brush, alpha = alpha)
    drawIntoCanvas { canvas ->
        val paint = Paint()
        brush.applyTo(size, paint, alpha)
        dither(paint)
        canvas.drawPath(path, paint)
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

/**
 * [brush] behind the content, as `background(brush)`, dithered where the platform can
 * ([Dithering.gradients]): for the dark scrims over art and other wide gradients that band on a TV.
 * The dithered paint is made once per size, never per frame.
 */
fun androidx.compose.ui.Modifier.ditheredBackground(brush: androidx.compose.ui.graphics.Brush): androidx.compose.ui.Modifier =
    this.then(
        androidx.compose.ui.Modifier.drawWithCache {
            val dither = Dithering.gradients
            if (dither == null) {
                onDrawBehind { drawRect(brush) }
            } else {
                val paint = Paint()
                brush.applyTo(size, paint, 1f)
                dither(paint)
                onDrawBehind { drawIntoCanvas { it.drawRect(0f, 0f, size.width, size.height, paint) } }
            }
        },
    )
