package io.github.matiyaaa.fuse.ui.player.ffmpeg

import io.github.matiyaaa.fuse.playback.BitmapCue
import io.github.matiyaaa.fuse.playback.Cue
import io.github.matiyaaa.fuse.playback.SubtitleParser
import io.github.matiyaaa.fuse.playback.TextCue
import org.bytedeco.ffmpeg.avcodec.AVCodecContext
import org.bytedeco.ffmpeg.avcodec.AVPacket
import org.bytedeco.ffmpeg.avcodec.AVSubtitle
import org.bytedeco.ffmpeg.global.avcodec.SUBTITLE_ASS
import org.bytedeco.ffmpeg.global.avcodec.SUBTITLE_BITMAP
import org.bytedeco.ffmpeg.global.avcodec.SUBTITLE_TEXT
import org.bytedeco.ffmpeg.global.avutil.AV_NOPTS_VALUE
import org.bytedeco.javacpp.IntPointer

/**
 * FFmpeg's decoded subtitles as Fuse cues. Text comes from FFmpeg as ASS events whatever the
 * format (SRT, WebVTT, mov_text, ASS itself), so it goes through Fuse's own ASS reader with the
 * stream's styles; pictures (PGS, DVD) become [BitmapCue]s placed on the picture.
 */
internal object SubtitleConverter {
    class Result(val cues: List<Cue>, val startMs: Long, val close: Boolean)

    fun convert(sub: AVSubtitle, ctx: AVCodecContext, packet: AVPacket, timeBase: Double, offsetMs: Long, videoWidth: Int, videoHeight: Int): Result {
        val basePts = when {
            sub.pts() != AV_NOPTS_VALUE -> sub.pts() / 1000
            packet.pts() != AV_NOPTS_VALUE -> (packet.pts() * timeBase * 1000).toLong()
            else -> 0
        } - offsetMs
        val start = basePts + sub.start_display_time().toUInt().toLong()
        val endDisplay = sub.end_display_time().toUInt().toLong()
        val end = when {
            endDisplay in 1 until 0xFFFFFFFFL -> basePts + endDisplay
            packet.duration() > 0 -> start + (packet.duration() * timeBase * 1000).toLong()
            else -> Long.MAX_VALUE
        }
        if (sub.num_rects() == 0) return Result(emptyList(), start, close = true)
        val cues = ArrayList<Cue>()
        val header = ctx.subtitle_header()?.takeIf { ctx.subtitle_header_size() > 0 }?.getString(Charsets.UTF_8).orEmpty()
        val w = ctx.width().takeIf { it > 0 } ?: videoWidth
        val h = ctx.height().takeIf { it > 0 } ?: videoHeight
        var bitmaps = false
        for (i in 0 until sub.num_rects()) {
            val rect = sub.rects(i) ?: continue
            when (rect.type()) {
                SUBTITLE_BITMAP -> {
                    bitmaps = true
                    bitmap(rect, start, end, w, h)?.let { cues += it }
                }
                SUBTITLE_ASS -> rect.ass()?.getString(Charsets.UTF_8)?.let { ass(header, it, start, end) }?.let { cues += it }
                SUBTITLE_TEXT -> rect.text()?.getString(Charsets.UTF_8)?.let {
                    cues += TextCue(start, end, it.lines().map { line -> listOf(io.github.matiyaaa.fuse.playback.Span(line)) })
                }
            }
        }
        return Result(cues, start, close = bitmaps)
    }

    /** "ReadOrder,Layer,Style,Name,MarginL,MarginR,MarginV,Effect,Text" read with the stream's styles. */
    private fun ass(header: String, event: String, start: Long, end: Long): TextCue? {
        val f = event.split(',', limit = 9)
        if (f.size < 9) return null
        val doc = buildString {
            append(header.ifBlank { "[Script Info]\nPlayResY: 288\n" })
            append("\n[Events]\nFormat: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n")
            append("Dialogue: ${f[1]},0:00:00.00,9:00:00.00,${f[2]},${f[3]},${f[4]},${f[5]},${f[6]},${f[7]},${f[8]}\n")
        }
        val cue = SubtitleParser.parseAss(doc).cues.firstOrNull() as? TextCue ?: return null
        return cue.copy(startMs = start, endMs = end)
    }

    private fun bitmap(rect: org.bytedeco.ffmpeg.avcodec.AVSubtitleRect, start: Long, end: Long, w: Int, h: Int): BitmapCue? {
        val rw = rect.w()
        val rh = rect.h()
        if (rw <= 0 || rh <= 0 || w <= 0 || h <= 0) return null
        val indices = rect.data(0) ?: return null
        val palette = IntPointer(rect.data(1))
        val colors = IntArray(rect.nb_colors().coerceIn(0, 256)) { palette.get(it.toLong()) }
        val stride = rect.linesize(0)
        val row = ByteArray(rw)
        val pixels = IntArray(rw * rh)
        for (y in 0 until rh) {
            indices.position((y * stride).toLong()).get(row, 0, rw)
            for (x in 0 until rw) pixels[y * rw + x] = colors.getOrElse(row[x].toInt() and 0xFF) { 0 }
        }
        indices.position(0)
        return BitmapCue(
            start, end, rw, rh, pixels,
            left = rect.x().toFloat() / w, top = rect.y().toFloat() / h,
            right = (rect.x() + rw).toFloat() / w, bottom = (rect.y() + rh).toFloat() / h,
        )
    }

    /** [cue] ending at [ms] (a picture subtitle cleared by the next display set). */
    fun ended(cue: Cue, ms: Long): Cue = when (cue) {
        is BitmapCue -> BitmapCue(cue.startMs, ms, cue.width, cue.height, cue.pixels, cue.left, cue.top, cue.right, cue.bottom)
        is TextCue -> cue.copy(endMs = ms)
    }
}
