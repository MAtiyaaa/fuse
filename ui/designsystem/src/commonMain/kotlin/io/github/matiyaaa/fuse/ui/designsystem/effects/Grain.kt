package io.github.matiyaaa.fuse.ui.designsystem.effects

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.DrawScope
import kotlin.random.Random

/**
 * A fine, even grain over large soft gradients. A glow fading over a dark room has more steps of
 * brightness than an 8-bit screen can show, so it breaks into visible bands; a whisper of noise
 * (overlaid, so it neither lightens nor darkens) hides the steps, and the light reads smooth.
 */
fun Modifier.grain(strength: Float = GRAIN_STRENGTH): Modifier = drawWithContent {
    drawContent()
    drawGrain(strength)
}

/** [grain] drawn straight into a canvas, over whatever was drawn there. */
fun DrawScope.drawGrain(strength: Float = GRAIN_STRENGTH) {
    // Coprime periods have no common repetition within any supported display (127 * 131 px).
    // Each texel stays one canvas pixel; UI density never enlarges a noise cell. A picture the
    // system enlarges (gamescope on a TV) enlarges the cells too, into visible squares: there the
    // grain fades by as much, so it still smooths the steps without being seen.
    val enlarged = Drawing.upscale.coerceAtLeast(1f)
    val alpha = strength * 0.70710677f / enlarged
    drawRect(Grain.first, alpha = alpha, blendMode = BlendMode.Overlay)
    drawRect(Grain.second, alpha = alpha, blendMode = BlendMode.Overlay)
    // Overlay scales its noise by the colour under it, so over a dark room (most of Fuse's
    // backgrounds) it moves pixels by far less than one 8-bit step and can't break up bands at all.
    // The dither adds 0 to 2 steps of light per pixel whatever the colour under it, which is what
    // makes a glow over near black read as smooth on screens and drivers that don't dither for us.
    // Where the platform dithers each gradient as it is drawn ([Dithering]), the steps are already
    // broken up, and this white noise would only lighten the room (and, enlarged, show as squares).
    if (Dithering.gradients == null) drawRect(Grain.dither)
}

/**
 * The dither alone, over everything already drawn (a whole window): 0 to 2 steps of light per
 * pixel, which is what breaks the bands of every gradient under it where the graphics library
 * doesn't dither them itself (Skia on desktops, unlike Android's).
 */
fun DrawScope.drawDither() = drawRect(Grain.dither)

/** How much grain: enough to break up bands, never enough to be seen as texture. */
const val GRAIN_STRENGTH = 0.07f

private object Grain {
    val first: ShaderBrush by lazy { brush(127, 0x6C1) }
    val second: ShaderBrush by lazy { brush(131, 0x5A17) }

    /** White at 0, 1 or 2 of 255 alpha per texel, triangular: ordered noise of about one 8-bit step. */
    val dither: ShaderBrush by lazy {
        val size = 137
        val image = ImageBitmap(size, size, ImageBitmapConfig.Argb8888)
        val canvas = androidx.compose.ui.graphics.Canvas(image)
        val paint = androidx.compose.ui.graphics.Paint().apply { isAntiAlias = false; blendMode = BlendMode.Src }
        val random = Random(0x3D17)
        for (y in 0 until size) {
            for (x in 0 until size) {
                val steps = ((random.nextFloat() + random.nextFloat()) * 1.5f).toInt().coerceIn(0, 2)
                paint.color = androidx.compose.ui.graphics.Color(1f, 1f, 1f, steps / 255f)
                canvas.drawRect(x.toFloat(), y.toFloat(), x + 1f, y + 1f, paint)
            }
        }
        ShaderBrush(ImageShader(image, TileMode.Repeated, TileMode.Repeated))
    }

    /** Independent centered noise fields; their common period exceeds even an 8K display. */
    private fun brush(size: Int, seed: Int): ShaderBrush {
        val image = ImageBitmap(size, size, ImageBitmapConfig.Argb8888)
        val canvas = androidx.compose.ui.graphics.Canvas(image)
        val paint = androidx.compose.ui.graphics.Paint().apply { isAntiAlias = false }
        val random = Random(seed)
        for (y in 0 until size) {
            for (x in 0 until size) {
                val v = (0.5f + (random.nextFloat() + random.nextFloat() - 1f) * 0.25f).coerceIn(0f, 1f)
                paint.color = androidx.compose.ui.graphics.Color(v, v, v)
                canvas.drawRect(x.toFloat(), y.toFloat(), x + 1f, y + 1f, paint)
            }
        }
        return ShaderBrush(ImageShader(image, TileMode.Repeated, TileMode.Repeated))
    }
}
