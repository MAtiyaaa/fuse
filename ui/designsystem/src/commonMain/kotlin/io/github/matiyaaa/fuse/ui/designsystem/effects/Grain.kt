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
    // Each texel stays one canvas pixel; UI density never enlarges a noise cell.
    val alpha = strength * 0.70710677f
    drawRect(Grain.first, alpha = alpha, blendMode = BlendMode.Overlay)
    drawRect(Grain.second, alpha = alpha, blendMode = BlendMode.Overlay)
}

/** How much grain: enough to break up bands, never enough to be seen as texture. */
const val GRAIN_STRENGTH = 0.07f

private object Grain {
    val first: ShaderBrush by lazy { brush(127, 0x6C1) }
    val second: ShaderBrush by lazy { brush(131, 0x5A17) }

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
