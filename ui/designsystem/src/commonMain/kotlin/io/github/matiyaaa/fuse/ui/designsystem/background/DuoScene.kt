package io.github.matiyaaa.fuse.ui.designsystem.background

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import io.github.matiyaaa.fuse.ui.designsystem.effects.drawDitheredRect
import kotlin.math.PI
import kotlin.math.sin

/**
 * Duo's room: faint graph paper drifting slowly on the diagonal, as on a dual-screen handheld's
 * menu, with a band of soft grey light along the top and bottom and a few squares of the accent
 * that light and fade in turn. At night the paper is dark slate and the lines barely there.
 */
internal class DuoScene(look: SceneLook) : Scene(look) {
    private val night = look.dark
    private val line = if (night) Color.White.copy(alpha = 0.08f * k) else Color(0xFF8E98A4).copy(alpha = 0.2f * k)
    private val major = if (night) Color.White.copy(alpha = 0.13f * k) else Color(0xFF8E98A4).copy(alpha = 0.32f * k)
    private val lit = look.accent.copy(alpha = (if (night) 0.16f else 0.13f) * k)
    private var top: Brush = Brush.verticalGradient(listOf(Color.Transparent, Color.Transparent))
    private var bottom: Brush = Brush.verticalGradient(listOf(Color.Transparent, Color.Transparent))

    private class Cell(val col: Int, val row: Int, val phase: Float, val rate: Float)
    private val cells: List<Cell> = Seeded(0xD5).let { s ->
        List(CELLS) { Cell(s.between(0f, 60f).toInt(), s.between(0f, 34f).toInt(), s.next(), s.between(0.05f, 0.12f)) }
    }

    override val fps: Int = 20

    override fun DrawScope.build() {
        val band = if (night) Color(0xFF3A414B) else Color(0xFFD9DEE4)
        top = Brush.verticalGradient(listOf(band.copy(alpha = (if (night) 0.55f else 0.8f) * k.coerceAtMost(1f)), Color.Transparent), endY = size.height * 0.16f)
        bottom = Brush.verticalGradient(
            0f to Color.Transparent,
            1f to band.copy(alpha = (if (night) 0.6f else 0.85f) * k.coerceAtMost(1f)),
            startY = size.height * 0.82f, endY = size.height,
        )
    }

    override fun DrawScope.paint(t: Float) {
        val w = size.width
        val h = size.height
        val cell = CELL * density
        // The paper drifts one square every [DRIFT] seconds, down and to the right.
        val shift = wrap(t / DRIFT, 1f) * cell
        var i = -1
        var x = shift - cell
        while (x < w) {
            val isMajor = wrap(i.toFloat(), 4f) == 0f
            drawLine(if (isMajor) major else line, Offset(x, 0f), Offset(x, h), strokeWidth = density * (if (isMajor) 1f else 0.75f))
            x += cell
            i++
        }
        i = -1
        var y = shift - cell
        while (y < h) {
            val isMajor = wrap(i.toFloat(), 4f) == 0f
            drawLine(if (isMajor) major else line, Offset(0f, y), Offset(w, y), strokeWidth = density * (if (isMajor) 1f else 0.75f))
            y += cell
            i++
        }
        // A few squares of the accent light up and fade, one after another.
        for (c in cells) {
            val p = wrap(c.phase + t * c.rate, 1f)
            val a = if (p < 0.3f) sin(p / 0.3f * PI.toFloat()) else 0f
            if (a <= 0.01f) continue
            val cx = shift - cell + c.col * cell
            val cy = shift - cell + c.row * cell
            if (cx > w || cy > h) continue
            drawRect(lit, Offset(cx + density, cy + density), androidx.compose.ui.geometry.Size(cell - 2 * density, cell - 2 * density), alpha = a)
        }
        drawDitheredRect(top)
        drawDitheredRect(bottom)
    }

    private companion object {
        const val CELL = 22f
        const val DRIFT = 9f
        const val CELLS = 14
    }
}
