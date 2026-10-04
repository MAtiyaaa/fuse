package io.github.matiyaaa.fuse.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SubtitleParserTest {
    @Test
    fun srtKeepsTimesLinesStylesAndPlacement() {
        val t = SubtitleParser.parseSrt(
            "﻿1\r\n00:00:01,000 --> 00:00:02,500\r\nHello <i>there</i>\r\nsecond line\r\n\r\n" +
                "2\n00:00:03,000 --> 00:00:04,000\n{\\an8}<font color=\"#FF0000\">Sign</font>\n\n" +
                "broken\n00:00:05 --> nonsense\nskipped\n",
        )
        assertEquals(2, t.cues.size)
        val first = t.cues[0] as TextCue
        assertEquals(1_000, first.startMs)
        assertEquals(2_500, first.endMs)
        assertEquals("Hello there\nsecond line", first.plainText)
        assertTrue(first.lines[0][1].italic)
        val second = t.cues[1] as TextCue
        assertEquals(8, second.align)
        assertEquals(0xFFFF0000, second.lines[0][0].color)
    }

    @Test
    fun vttSkipsHeadersAndNotesAndPlacesTopLines() {
        val t = SubtitleParser.parseVtt(
            "WEBVTT - film\n\nNOTE a comment\n\nintro\n00:01.000 --> 00:02.000 line:0 align:start\n<v Ann>Up here</v>\n\n" +
                "00:00:03.000 --> 00:00:04.000\n<c.yellow>Plain</c> &amp; simple\n",
        )
        assertEquals(2, t.cues.size)
        val top = t.cues[0] as TextCue
        assertEquals(7, top.align)
        assertEquals("Up here", top.plainText)
        assertEquals("Plain & simple", (t.cues[1] as TextCue).plainText)
    }

    @Test
    fun assReadsStylesOverridesAndLeavesDrawingsOut() {
        val ass = """
            [Script Info]
            PlayResX: 1920
            PlayResY: 1080

            [V4+ Styles]
            Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
            Style: Default,Arial,54,&H00FFFFFF,&H000000FF,&H00000000,&H80000000,0,0,0,0,100,100,0,0,1,3,0,2,10,10,40,1
            Style: Sign,Arial,40,&H0000FFFF,&H000000FF,&H00000000,&H80000000,-1,0,0,0,100,100,0,0,3,2,0,8,10,10,20,1

            [Events]
            Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
            Dialogue: 0,0:00:01.00,0:00:03.50,Default,,0,0,0,,Line one\NLine {\i1}two{\i0}, with a comma
            Dialogue: 1,0:00:02.00,0:00:04.00,Sign,,0,0,0,,{\pos(960,100)\an5}Station
            Dialogue: 0,0:00:05.00,0:00:06.00,Default,,0,0,0,,{\p1}m 0 0 l 100 0 100 100{\p0}
        """.trimIndent()
        val t = SubtitleParser.parseAss(ass)
        assertEquals(2, t.cues.size)
        val line = t.cues[0] as TextCue
        assertEquals("Line one\nLine two, with a comma", line.plainText)
        assertTrue(line.lines[1][1].italic)
        assertEquals(0xFFFFFFFF, line.lines[0][0].color)
        assertEquals(54f / 1080f, line.style.size!!, 1e-6f)
        assertEquals(40f / 1080f, line.style.marginV!!, 1e-6f)
        val sign = t.cues[1] as TextCue
        assertEquals(5, sign.align)
        assertEquals(0.5f, sign.posX!!, 1e-6f)
        assertTrue(sign.lines[0][0].bold)
        // &H0000FFFF is yellow (blue-green-red order) and opaque.
        assertEquals(0xFFFFFF00, sign.lines[0][0].color)
        assertEquals(0xFF000000, sign.style.box)
        assertNull(sign.style.outline)
    }

    @Test
    fun theTimelineFindsOverlappingCuesQuickly() {
        val t = CueTimeline(
            listOf(
                TextCue(0, 10_000, listOf(listOf(Span("long sign")))),
                TextCue(1_000, 2_000, listOf(listOf(Span("a")))),
                TextCue(3_000, 4_000, listOf(listOf(Span("b")))),
            ),
        )
        assertEquals(listOf("long sign", "a"), t.at(1_500).map { (it as TextCue).plainText })
        assertEquals(listOf("long sign"), t.at(2_000).map { (it as TextCue).plainText })
        assertEquals(listOf("long sign", "b"), t.at(3_999).map { (it as TextCue).plainText })
        assertTrue(t.at(10_000).isEmpty())
        assertTrue(t.at(-1).isEmpty())
    }

    @Test
    fun formatsComeFromCodecsAndNames() {
        assertEquals(SubtitleFormat.SRT, SubtitleParser.formatOf("subrip"))
        assertEquals(SubtitleFormat.ASS, SubtitleParser.formatOf("film.en.ssa"))
        assertEquals(SubtitleFormat.VTT, SubtitleParser.formatOf("webvtt"))
        assertNull(SubtitleParser.formatOf("pgssub"))
    }
}
