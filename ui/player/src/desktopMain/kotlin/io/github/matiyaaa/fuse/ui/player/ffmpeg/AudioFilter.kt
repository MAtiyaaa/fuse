package io.github.matiyaaa.fuse.ui.player.ffmpeg

import org.bytedeco.ffmpeg.avfilter.AVFilterContext
import org.bytedeco.ffmpeg.avfilter.AVFilterGraph
import org.bytedeco.ffmpeg.avutil.AVFrame
import org.bytedeco.ffmpeg.global.avfilter.AV_BUFFERSRC_FLAG_KEEP_REF
import org.bytedeco.ffmpeg.global.avfilter.av_buffersink_get_frame
import org.bytedeco.ffmpeg.global.avfilter.av_buffersrc_add_frame_flags
import org.bytedeco.ffmpeg.global.avfilter.avfilter_get_by_name
import org.bytedeco.ffmpeg.global.avfilter.avfilter_graph_alloc
import org.bytedeco.ffmpeg.global.avfilter.avfilter_graph_config
import org.bytedeco.ffmpeg.global.avfilter.avfilter_graph_create_filter
import org.bytedeco.ffmpeg.global.avfilter.avfilter_graph_free
import org.bytedeco.ffmpeg.global.avfilter.avfilter_link
import org.bytedeco.ffmpeg.global.avutil.av_channel_layout_describe
import org.bytedeco.ffmpeg.global.avutil.av_frame_alloc
import org.bytedeco.ffmpeg.global.avutil.av_frame_free
import org.bytedeco.ffmpeg.global.avutil.av_frame_unref
import org.bytedeco.ffmpeg.global.avutil.av_get_sample_fmt_name
import org.bytedeco.javacpp.BytePointer

/**
 * Decoded sound to what the card plays: resampled to 48 kHz, mixed to stereo (surround folds down),
 * 16-bit, and at another speed without changing pitch (FFmpeg's atempo). Rebuilt when the decoder's
 * format or the speed changes.
 */
internal class AudioFilter {
    private var graph: AVFilterGraph? = null
    private var source: AVFilterContext? = null
    private var sink: AVFilterContext? = null
    private var key: String? = null
    private val out: AVFrame = av_frame_alloc()

    /** Makes sure the graph fits frames like [frame] at [speed]; false when it can't be built. */
    fun prepare(frame: AVFrame, speed: Float): Boolean {
        val layout = BytePointer(256L)
        av_channel_layout_describe(frame.ch_layout(), layout, 256)
        val fmt = av_get_sample_fmt_name(frame.format())?.string ?: return false
        val k = "${frame.sample_rate()}|$fmt|${layout.string}|$speed"
        if (k == key && graph != null) return true
        close()
        val g = avfilter_graph_alloc() ?: return false
        val src = AVFilterContext()
        val args = "time_base=1/${frame.sample_rate()}:sample_rate=${frame.sample_rate()}:sample_fmt=$fmt:channel_layout=${layout.string}"
        if (avfilter_graph_create_filter(src, avfilter_get_by_name("abuffer"), "in", args, null, g) < 0) return fail(g)
        val chain = ArrayList<AVFilterContext>()
        chain += src
        fun add(name: String, filter: String, filterArgs: String): Boolean {
            val c = AVFilterContext()
            if (avfilter_graph_create_filter(c, avfilter_get_by_name(filter), name, filterArgs, null, g) < 0) return false
            chain += c
            return true
        }
        if (!add("rate", "aresample", "${AudioOut.RATE}")) return fail(g)
        if (!add("fmt", "aformat", "sample_fmts=s16:channel_layouts=stereo")) return fail(g)
        // atempo takes 0.5 to 2 at a time; the player's speeds stay inside that.
        if (speed != 1f && !add("tempo", "atempo", speed.coerceIn(0.5f, 2f).toString())) return fail(g)
        val snk = AVFilterContext()
        if (avfilter_graph_create_filter(snk, avfilter_get_by_name("abuffersink"), "out", null as String?, null, g) < 0) return fail(g)
        chain += snk
        for (i in 0 until chain.size - 1) {
            if (avfilter_link(chain[i], 0, chain[i + 1], 0) < 0) return fail(g)
        }
        if (avfilter_graph_config(g, null) < 0) return fail(g)
        graph = g
        source = src
        sink = snk
        key = k
        return true
    }

    private fun fail(g: AVFilterGraph): Boolean {
        avfilter_graph_free(g)
        return false
    }

    /** Pushes [frame] in and hands each 48 kHz stereo block that comes out to [block] (bytes, length). */
    fun push(frame: AVFrame?, block: (BytePointer, Int) -> Unit) {
        val src = source ?: return
        val snk = sink ?: return
        if (av_buffersrc_add_frame_flags(src, frame, AV_BUFFERSRC_FLAG_KEEP_REF) < 0) return
        while (av_buffersink_get_frame(snk, out) >= 0) {
            block(out.data(0), out.nb_samples() * AudioOut.FRAME_BYTES)
            av_frame_unref(out)
        }
    }

    /** Drops what the graph holds (a seek): the next frame builds it afresh. */
    fun reset() = close()

    fun close() {
        graph?.let { avfilter_graph_free(it) }
        graph = null
        source = null
        sink = null
        key = null
    }

    fun release() {
        close()
        av_frame_free(out)
    }
}
