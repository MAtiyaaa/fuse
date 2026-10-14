package io.github.matiyaaa.fuse.ui.designsystem.background

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import io.github.matiyaaa.fuse.ui.designsystem.effects.drawDitheredRect

/**
 * Trio's room: a soft light from the top over a fine grid of dots, as on a 3D handheld's home, with
 * wide, faint sheens crossing it on the diagonal now and then. At night the light is a deep
 * charcoal and the dots glow faintly.
 */
internal class TrioScene(look: SceneLook) : Scene(look) {
    private val night = look.dark
    private val dot = if (night) Color.White.copy(alpha = 0.07f * k) else Color(0xFF7D8894).copy(alpha = 0.2f * k)
    private val sheenColor = if (night) look.accent.copy(alpha = 0.06f * k) else Color.White.copy(alpha = 0.55f * k)
    private var top: Brush = Brush.verticalGradient(listOf(Color.Transparent, Color.Transparent))
    private var sheen: Brush = Brush.horizontalGradient(listOf(Color.Transparent, Color.Transparent))
    private val glow = Glow(look.accent)

    override val fps: Int = 24

    override fun DrawScope.build() {
        top = Brush.verticalGradient(
            listOf((if (night) Color(0xFF2B3138) else Color.White).copy(alpha = (if (night) 0.6f else 0.85f) * k.coerceAtMost(1f)), Color.Transparent),
            endY = size.height * 0.6f,
        )
        val band = SHEEN * density
        sheen = Brush.horizontalGradient(
            0f to Color.Transparent, 0.5f to sheenColor, 1f to Color.Transparent,
            startX = -band / 2, endX = band / 2,
        )
    }

    override fun DrawScope.paint(t: Float) {
        val w = size.width
        val h = size.height
        drawDitheredRect(top)
        glow.draw(this, w * 0.5f, h * 1.05f, w * 0.6f, h * 0.35f, alpha = (if (night) 0.1f else 0.07f) * k)
        val gap = GAP * density
        val r = density * (if (night) 0.9f else 1.1f)
        var y = gap / 2
        while (y < h) {
            // Rows fade a little towards the top, where the light is.
            val a = 0.45f + 0.55f * (y / h)
            var x = gap / 2
            while (x < w) {
                drawCircle(dot, radius = r, center = Offset(x, y), alpha = a)
                x += gap
            }
            y += gap
        }
        // Two sheens cross on the diagonal, each every [PERIOD] seconds, apart from each other.
        for (n in 0..1) {
            val p = wrap(t / PERIOD + n * 0.5f, 1f)
            if (p > 0.6f) continue
            val x = -w * 0.3f + (w * 1.6f) * (p / 0.6f)
            rotate(-24f, Offset(x, h / 2)) {
                translate(left = x) { drawRect(sheen, Offset(-SHEEN * density / 2, -h), androidx.compose.ui.geometry.Size(SHEEN * density, h * 3)) }
            }
        }
    }

    private companion object {
        const val GAP = 14f
        const val SHEEN = 260f
        const val PERIOD = 14f
    }
}
