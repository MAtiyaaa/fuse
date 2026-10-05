package io.github.matiyaaa.fuse.ui.designsystem.background

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope

/**
 * A small picture drawn pixel by pixel from rows of letters (see [FusiFrames] for the legend). Each
 * row is kept as runs of one colour, so a frame is a few dozen rectangles, and drawing it allocates
 * nothing. Pixels land on whole device pixels, so the art stays crisp at any size.
 */
internal class PixelSprite(vararg rows: String) {
    val width: Int = rows.maxOf { it.length }
    val height: Int = rows.size

    /** Runs as (x, y, length, colour index), four ints each. */
    private val runs: IntArray

    init {
        val list = ArrayList<Int>()
        rows.forEachIndexed { y, row ->
            var x = 0
            while (x < row.length) {
                val ch = row[x]
                if (ch == '.') {
                    x++
                    continue
                }
                var end = x + 1
                while (end < row.length && row[end] == ch) end++
                list += x
                list += y
                list += end - x
                list += FusiPalette.index(ch)
                x = end
            }
        }
        runs = list.toIntArray()
    }

    /**
     * Draws it with its bottom-left corner at ([left], [bottom]), each pixel [cell] device pixels
     * square, mirrored when [flip] (the art faces right). [palette] gives each colour; [alpha] fades it.
     */
    fun DrawScope.draw(left: Float, bottom: Float, cell: Float, flip: Boolean, palette: Array<Color>, alpha: Float = 1f) {
        val top = bottom - height * cell
        var i = 0
        while (i < runs.size) {
            val x = runs[i]
            val y = runs[i + 1]
            val len = runs[i + 2]
            val c = palette[runs[i + 3]]
            val px = if (flip) width - x - len else x
            drawRect(c, Offset(left + px * cell, top + y * cell), Size(len * cell, cell), alpha = alpha)
            i += 4
        }
    }
}

/** Fusi's colours: her own whatever the theme (she is a white Maltese with a pink bow). */
internal object FusiPalette {
    private const val KEYS = "owsSenmtcbBrRdkyh"

    fun index(ch: Char): Int = KEYS.indexOf(ch).also { require(it >= 0) { "No colour for '$ch'" } }

    /** The day colours. */
    val day: Array<Color> = arrayOf(
        Color(0xFF6A3F5C), // o outline, a soft plum rather than black
        Color(0xFFFFFFFF), // w fur
        Color(0xFFEEE0EC), // s fur in shade
        Color(0xFFDEC8DA), // S where an ear meets the face
        Color(0xFF2B1B26), // e eyes
        Color(0xFF2B1B26), // n nose
        Color(0xFF6A3F5C), // m mouth
        Color(0xFFFF82A8), // t tongue
        Color(0xFFFFB6D2), // c blush
        Color(0xFFFF6FAE), // b bow
        Color(0xFFE23E87), // B bow in shade
        Color(0xFFFF80B2), // r pink (roof, ball, bowl, hearts)
        Color(0xFFD6468C), // R deep pink
        Color(0xFF7A486C), // d doorway
        Color(0xFFC48460), // k kibble
        Color(0xFFFFE28C), // y kibble, lit
        Color(0xFFFFFFFF), // h shine
    )

    /** At night the fur takes the room's moonlight a little, so she never glows against a dark sky. */
    val night: Array<Color> = day.copyOf().also {
        it[1] = Color(0xFFF3E8F6)
        it[2] = Color(0xFFD9C6E2)
        it[3] = Color(0xFFC7AFD3)
    }
}
