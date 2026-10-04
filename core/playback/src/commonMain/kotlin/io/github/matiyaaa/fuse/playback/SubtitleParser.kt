package io.github.matiyaaa.fuse.playback

/**
 * Reads subtitle files into cues: SubRip (SRT), WebVTT and Advanced SubStation Alpha (ASS and SSA).
 * Forgiving, like players are: a broken cue is skipped, never the file. For ASS it keeps what reads
 * on a TV (styles, colours, bold, italic, underline, alignment, `\pos`, margins, outline, boxes)
 * and leaves out effects (karaoke, motion, fades, drawings).
 */
object SubtitleParser {
    fun parse(text: String, format: SubtitleFormat): CueTimeline = when (format) {
        SubtitleFormat.SRT -> parseSrt(text)
        SubtitleFormat.VTT -> parseVtt(text)
        SubtitleFormat.ASS -> parseAss(text)
    }

    /** The format a file's name or a codec name means, or null for one Fuse doesn't read. */
    fun formatOf(codecOrName: String?): SubtitleFormat? {
        val c = codecOrName?.lowercase()?.substringAfterLast('.') ?: return null
        return when (c) {
            "srt", "subrip" -> SubtitleFormat.SRT
            "vtt", "webvtt" -> SubtitleFormat.VTT
            "ass", "ssa" -> SubtitleFormat.ASS
            else -> null
        }
    }

    // SubRip ------------------------------------------------------------------------------------

    fun parseSrt(text: String): CueTimeline {
        val cues = ArrayList<Cue>()
        for (block in blocks(text)) {
            val lines = block.lines()
            val timing = lines.indexOfFirst { "-->" in it }
            if (timing < 0) continue
            val (start, end) = timingOf(lines[timing]) ?: continue
            val body = lines.drop(timing + 1)
            if (body.isEmpty()) continue
            val (align, cleaned) = leadingAlign(body.joinToString("\n"))
            cues += TextCue(start, end, markup(cleaned), align = align)
        }
        return CueTimeline(cues)
    }

    // WebVTT ------------------------------------------------------------------------------------

    fun parseVtt(text: String): CueTimeline {
        val cues = ArrayList<Cue>()
        for (block in blocks(text)) {
            val lines = block.lines()
            val first = lines.firstOrNull()?.trim().orEmpty()
            if (first.startsWith("WEBVTT") || first.startsWith("NOTE") || first.startsWith("STYLE") || first.startsWith("REGION")) continue
            val timing = lines.indexOfFirst { "-->" in it }
            if (timing < 0) continue
            val (start, end) = timingOf(lines[timing]) ?: continue
            val settings = lines[timing].substringAfter("-->").trim().split(Regex("\\s+")).drop(1)
            val body = lines.drop(timing + 1)
            if (body.isEmpty()) continue
            // Only the placement settings that matter on a TV: top lines and left or right alignment.
            val line = settings.firstOrNull { it.startsWith("line:") }?.removePrefix("line:")?.substringBefore(',')
            val top = line != null && (line.removeSuffix("%").toFloatOrNull()?.let { if (line.endsWith("%")) it < 50 else it >= 0 && it < 4 } == true)
            val horizontal = when (settings.firstOrNull { it.startsWith("align:") }?.removePrefix("align:")) {
                "left", "start" -> 0
                "right", "end" -> 2
                else -> 1
            }
            val align = (if (top) 7 else 1) + horizontal
            cues += TextCue(start, end, markup(body.joinToString("\n")), align = align)
        }
        return CueTimeline(cues)
    }

    // Advanced SubStation Alpha -------------------------------------------------------------------

    private class AssStyle(
        val size: Float,
        val primary: Long,
        val outlineColor: Long,
        val back: Long,
        val bold: Boolean,
        val italic: Boolean,
        val underline: Boolean,
        val borderStyle: Int,
        val outline: Float,
        val shadow: Float,
        val align: Int,
        val marginV: Float,
    )

    fun parseAss(text: String): CueTimeline {
        var section = ""
        var playResY = 288f
        var playResX = 384f
        var styleFormat: List<String> = emptyList()
        var eventFormat: List<String> = emptyList()
        val styles = HashMap<String, AssStyle>()
        val rawStyles = ArrayList<String>()
        val dialogues = ArrayList<String>()
        for (raw in text.removePrefix("\uFEFF").lineSequence()) {
            val line = raw.trimEnd('\r')
            val t = line.trim()
            if (t.startsWith("[") && t.endsWith("]")) {
                section = t.lowercase()
                continue
            }
            when {
                section == "[script info]" && t.startsWith("PlayResY:", ignoreCase = true) -> t.substringAfter(':').trim().toFloatOrNull()?.let { if (it > 0) playResY = it }
                section == "[script info]" && t.startsWith("PlayResX:", ignoreCase = true) -> t.substringAfter(':').trim().toFloatOrNull()?.let { if (it > 0) playResX = it }
                section.endsWith("styles]") && t.startsWith("Format:", ignoreCase = true) -> styleFormat = fields(t)
                section.endsWith("styles]") && t.startsWith("Style:", ignoreCase = true) -> rawStyles += t
                section == "[events]" && t.startsWith("Format:", ignoreCase = true) -> eventFormat = fields(t)
                section == "[events]" && t.startsWith("Dialogue:", ignoreCase = true) -> dialogues += t
            }
        }
        if (styleFormat.isEmpty()) {
            styleFormat = "Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding"
                .split(',').map { it.trim().lowercase() }
        }
        for (s in rawStyles) {
            val values = s.substringAfter(':').split(',', limit = styleFormat.size).map { it.trim() }
            fun v(name: String) = styleFormat.indexOf(name).takeIf { it >= 0 }?.let { values.getOrNull(it) }
            val name = v("name") ?: continue
            styles[name] = AssStyle(
                size = (v("fontsize")?.toFloatOrNull() ?: 20f) / playResY,
                primary = assColor(v("primarycolour")) ?: WHITE,
                outlineColor = assColor(v("outlinecolour")) ?: BLACK,
                back = assColor(v("backcolour")) ?: BLACK,
                bold = v("bold")?.let { it == "-1" || it == "1" } == true,
                italic = v("italic")?.let { it == "-1" || it == "1" } == true,
                underline = v("underline")?.let { it == "-1" || it == "1" } == true,
                borderStyle = v("borderstyle")?.toIntOrNull() ?: 1,
                outline = (v("outline")?.toFloatOrNull() ?: 2f) / playResY,
                shadow = (v("shadow")?.toFloatOrNull() ?: 0f) / playResY,
                // SSA (v4) numbers alignments differently; V4+ uses the numpad, as Fuse does.
                align = v("alignment")?.toIntOrNull()?.takeIf { it in 1..9 } ?: 2,
                marginV = (v("marginv")?.toFloatOrNull() ?: 10f) / playResY,
            )
        }
        if (eventFormat.isEmpty()) {
            eventFormat = "Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text".split(',').map { it.trim().lowercase() }
        }
        val textIndex = eventFormat.indexOf("text").takeIf { it >= 0 } ?: (eventFormat.size - 1)
        val cues = ArrayList<Cue>()
        for (d in dialogues) {
            val values = d.substringAfter(':').split(',', limit = eventFormat.size).map { it.trimStart() }
            fun v(name: String) = eventFormat.indexOf(name).takeIf { it >= 0 }?.let { values.getOrNull(it) }
            val start = assTime(v("start")) ?: continue
            val end = assTime(v("end")) ?: continue
            if (end <= start) continue
            val style = styles[v("style")?.trim()?.removePrefix("*")] ?: styles["Default"] ?: styles.values.firstOrNull()
            val body = values.getOrNull(textIndex) ?: continue
            val marginV = v("marginv")?.trim()?.toFloatOrNull()?.takeIf { it > 0 }?.let { it / playResY }
            val layer = v("layer")?.trim()?.toIntOrNull() ?: 0
            assCue(start, end, body, style, marginV, layer, playResX, playResY)?.let { cues += it }
        }
        return CueTimeline(cues)
    }

    private fun assCue(start: Long, end: Long, body: String, style: AssStyle?, marginV: Float?, layer: Int, resX: Float, resY: Float): TextCue? {
        var align = style?.align ?: 2
        var posX: Float? = null
        var posY: Float? = null
        var bold = style?.bold == true
        var italic = style?.italic == true
        var underline = style?.underline == true
        var color: Long? = style?.primary
        var size: Float? = style?.size
        var drawing = false
        val lines = ArrayList<List<Span>>()
        var current = ArrayList<Span>()
        val text = StringBuilder()
        fun flush() {
            if (text.isNotEmpty()) {
                if (!drawing) current += Span(text.toString(), bold, italic, underline, color)
                text.clear()
            }
        }
        var i = 0
        while (i < body.length) {
            val ch = body[i]
            when {
                ch == '{' -> {
                    val close = body.indexOf('}', i)
                    if (close < 0) {
                        text.append(ch)
                        i++
                        continue
                    }
                    flush()
                    for (tag in body.substring(i + 1, close).split('\\').drop(1)) {
                        val t = tag.trim()
                        when {
                            t.startsWith("an") -> t.drop(2).toIntOrNull()?.takeIf { it in 1..9 }?.let { align = it }
                            t.startsWith("pos(") -> {
                                val n = t.removePrefix("pos(").substringBefore(')').split(',').mapNotNull { it.trim().toFloatOrNull() }
                                if (n.size == 2) {
                                    posX = n[0] / resX
                                    posY = n[1] / resY
                                }
                            }
                            t.startsWith("fs") && t.drop(2).toFloatOrNull() != null -> size = t.drop(2).toFloat() / resY
                            t.startsWith("p") && t.drop(1).toIntOrNull() != null -> drawing = t.drop(1).toInt() > 0
                            t == "b1" || (t.startsWith("b") && (t.drop(1).toIntOrNull() ?: 0) >= 700) -> bold = true
                            t == "b0" -> bold = false
                            t == "i1" -> italic = true
                            t == "i0" -> italic = false
                            t == "u1" -> underline = true
                            t == "u0" -> underline = false
                            t.startsWith("1c") || (t.startsWith("c") && t.startsWith("clip").not()) -> {
                                val value = if (t.startsWith("1c")) t.drop(2) else t.drop(1)
                                assColor(value)?.let { c -> color = (c and 0x00FFFFFF) or ((color ?: WHITE) and 0xFF000000) }
                            }
                            t.startsWith("1a") || t.startsWith("alpha") -> {
                                val value = if (t.startsWith("1a")) t.drop(2) else t.drop(5)
                                value.trim('&', 'H', 'h').toLongOrNull(16)?.let { a -> color = ((255 - a.coerceIn(0, 255)) shl 24) or ((color ?: WHITE) and 0x00FFFFFF) }
                            }
                            t.startsWith("r") -> {
                                bold = style?.bold == true
                                italic = style?.italic == true
                                underline = style?.underline == true
                                color = style?.primary
                                size = style?.size
                            }
                        }
                    }
                    i = close + 1
                    continue
                }
                ch == '\\' && i + 1 < body.length && (body[i + 1] == 'N' || body[i + 1] == 'n') -> {
                    flush()
                    lines += current
                    current = ArrayList()
                    i += 2
                    continue
                }
                ch == '\\' && i + 1 < body.length && body[i + 1] == 'h' -> {
                    text.append('\u00A0')
                    i += 2
                    continue
                }
                else -> text.append(ch)
            }
            i++
        }
        flush()
        lines += current
        val kept = lines.dropWhile { it.isEmpty() }.dropLastWhile { it.isEmpty() }
        if (kept.isEmpty() || kept.all { line -> line.all { it.text.isBlank() } }) return null
        return TextCue(
            start, end, kept, align = align, posX = posX, posY = posY, layer = layer,
            style = CueStyle(
                size = size,
                color = null,
                outlineColor = style?.outlineColor,
                outline = style?.outline?.takeIf { style.borderStyle != 3 },
                shadow = style?.shadow,
                marginV = marginV ?: style?.marginV,
                box = if (style?.borderStyle == 3) style.outlineColor else null,
            ),
        )
    }

    private fun fields(formatLine: String) = formatLine.substringAfter(':').split(',').map { it.trim().lowercase() }

    /** `&HAABBGGRR&` (or `&HBBGGRR&`) to ARGB; ASS alpha counts up to transparent. */
    private fun assColor(value: String?): Long? {
        val hex = value?.trim()?.trim('&')?.removePrefix("H")?.removePrefix("h")?.trimEnd('&') ?: return null
        val n = hex.toLongOrNull(16) ?: return null
        val a = if (hex.length > 6) (n shr 24) and 0xFF else 0
        val b = (n shr 16) and 0xFF
        val g = (n shr 8) and 0xFF
        val r = n and 0xFF
        return ((255 - a) shl 24) or (r shl 16) or (g shl 8) or b
    }

    /** `H:MM:SS.cc`. */
    private fun assTime(value: String?): Long? {
        val p = value?.trim()?.split(':') ?: return null
        if (p.size != 3) return null
        val h = p[0].toLongOrNull() ?: return null
        val m = p[1].toLongOrNull() ?: return null
        val s = p[2].toDoubleOrNull() ?: return null
        return h * 3_600_000 + m * 60_000 + (s * 1000).toLong()
    }

    // Shared ------------------------------------------------------------------------------------

    private fun blocks(text: String): List<String> =
        text.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n').split(Regex("\n[ \t]*\n+")).map { it.trim('\n') }.filter { it.isNotBlank() }

    /** "00:01:02,500 --> 00:01:04,000" (SRT) or "01:02.500 --> 01:04.000 align:start" (VTT). */
    private fun timingOf(line: String): Pair<Long, Long>? {
        val parts = line.split("-->")
        if (parts.size != 2) return null
        val start = clock(parts[0].trim()) ?: return null
        val end = clock(parts[1].trim().substringBefore(' ')) ?: return null
        return if (end > start) start to end else null
    }

    private fun clock(s: String): Long? {
        val p = s.replace(',', '.').split(':')
        val (h, m, sec) = when (p.size) {
            3 -> Triple(p[0].toLongOrNull() ?: return null, p[1].toLongOrNull() ?: return null, p[2].toDoubleOrNull() ?: return null)
            2 -> Triple(0L, p[0].toLongOrNull() ?: return null, p[1].toDoubleOrNull() ?: return null)
            else -> return null
        }
        return h * 3_600_000 + m * 60_000 + kotlin.math.round(sec * 1000).toLong()
    }

    /** An SRT line starting with `{\an8}` (common in rips) is placed where it says. */
    private fun leadingAlign(text: String): Pair<Int, String> {
        val m = Regex("^\\{\\\\an([1-9])\\}").find(text) ?: return 2 to text
        return m.groupValues[1].toInt() to text.removeRange(m.range)
    }

    /** The small HTML-like markup SRT and WebVTT share: b, i, u, font colour; anything else is dropped. */
    private fun markup(text: String): List<List<Span>> {
        val lines = ArrayList<List<Span>>()
        var bold = 0
        var italic = 0
        var underline = 0
        val colors = ArrayList<Long>()
        for (raw in text.split('\n')) {
            val spans = ArrayList<Span>()
            val buf = StringBuilder()
            fun flush() {
                if (buf.isNotEmpty()) {
                    spans += Span(unescape(buf.toString()), bold > 0, italic > 0, underline > 0, colors.lastOrNull())
                    buf.clear()
                }
            }
            var i = 0
            // Leftover ASS-style overrides in SRT files are dropped.
            val line = raw.replace(Regex("\\{\\\\[^}]*\\}"), "")
            while (i < line.length) {
                val ch = line[i]
                if (ch == '<') {
                    val close = line.indexOf('>', i)
                    if (close > i) {
                        flush()
                        val tag = line.substring(i + 1, close).trim()
                        val closing = tag.startsWith("/")
                        val name = tag.removePrefix("/").substringBefore(' ').substringBefore('.').lowercase()
                        when (name) {
                            "b" -> bold = (bold + if (closing) -1 else 1).coerceAtLeast(0)
                            "i" -> italic = (italic + if (closing) -1 else 1).coerceAtLeast(0)
                            "u" -> underline = (underline + if (closing) -1 else 1).coerceAtLeast(0)
                            "font" -> if (closing) {
                                if (colors.isNotEmpty()) colors.removeAt(colors.lastIndex)
                            } else {
                                val c = Regex("color\\s*=\\s*\"?#?([0-9a-fA-F]{6})").find(tag)?.groupValues?.get(1)?.toLongOrNull(16)
                                colors += if (c != null) 0xFF000000 or c else (colors.lastOrNull() ?: WHITE)
                            }
                        }
                        i = close + 1
                        continue
                    }
                }
                buf.append(ch)
                i++
            }
            flush()
            lines += spans
        }
        return lines.dropWhile { it.isEmpty() }.dropLastWhile { it.isEmpty() }
    }

    private fun unescape(s: String) = s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", "\u00A0").replace("&lrm;", "").replace("&rlm;", "")

    private const val WHITE = 0xFFFFFFFFL
    private const val BLACK = 0xFF000000L
}
