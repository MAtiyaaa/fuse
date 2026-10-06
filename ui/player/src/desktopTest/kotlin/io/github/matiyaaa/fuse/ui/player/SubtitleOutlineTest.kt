package io.github.matiyaaa.fuse.ui.player

import androidx.compose.ui.graphics.Color
import io.github.matiyaaa.fuse.playback.SubtitleParser
import io.github.matiyaaa.fuse.playback.TextCue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A subtitle's outline is one colour under the text. ASS subtitles (every text subtitle in a video
 * file reads as one) give each piece of text the style's white; drawn into the outline too, the
 * text was outlined in white and read doubled.
 */
class SubtitleOutlineTest {
    @Test
    fun theOutlineNeverTakesTheTextsColour() {
        val doc = """
            [Script Info]
            PlayResY: 288

            [V4+ Styles]
            Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
            Style: Default,Arial,16,&H00FFFFFF,&H000000FF,&H00000000,&H00000000,0,0,0,0,100,100,0,0,1,1,0,2,10,10,10,1

            [Events]
            Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
            Dialogue: 0,0:00:01.00,0:00:03.00,Default,,0,0,0,,Hello {\c&H00FFFF&}there
        """.trimIndent()
        val cue = SubtitleParser.parseAss(doc).cues.single() as TextCue
        val text = annotated(cue, colors = true)
        val outline = annotated(cue, colors = false)
        assertEquals(text.text, outline.text)
        // The text keeps its colours (white, then yellow)...
        assertTrue(text.spanStyles.any { it.item.color == Color.White })
        assertTrue(text.spanStyles.any { it.item.color == Color(0xFFFFFF00) })
        // ...and the outline has none of its own, so it is drawn in the outline colour (black).
        assertTrue(outline.spanStyles.all { it.item.color == Color.Unspecified })
        assertEquals(0xFF000000, cue.style.outlineColor)
    }
}
