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
 *
 * Surround folds down in floating point. Straight to 16-bit, FFmpeg's resampler scales the whole
 * mix down so no channel sum can pass full scale (swresample's rematrix_maxval): a 5.1 film came out
 * about 8 dB quieter, its dialogue in the centre channel quieter still, which is what made Jellyfin
 * films faint on a TV. In floating point the mix keeps its levels, with the centre channel (where
 * films put the dialogue) at full level in each side instead of the textbook -3 dB, the way a TV's
 * clear-voice setting folds it down. A peak limiter (alimiter, auto level off) catches the rare peak
 * a full mix would push past full scale, so nothing clips. Every stream, stereo too, is then evened
 * out (see [LEVEL]).
 */
internal class AudioFilter {
    private var graph: AVFilterGraph? = null
    private var source: AVFilterContext? = null
    private var sink: AVFilterContext? = null
    private var key: String? = null
    private val out: AVFrame = av_frame_alloc()
    private val layoutName = BytePointer(LAYOUT_BYTES.toLong())

    /** For tests: how many times the graph has been built. */
    internal var builds = 0
        private set

    /** Makes sure the graph fits frames like [frame] at [speed]; false when it can't be built. */
    fun prepare(frame: AVFrame, speed: Float): Boolean {
        val described = av_channel_layout_describe(frame.ch_layout(), layoutName, LAYOUT_BYTES.toLong())
        if (described < 0) return false
        // Up to its end only: what follows in the buffer is whatever was there before. Read whole,
        // it made every frame's key new, and the graph was built again for every frame of sound,
        // which threw away what the limiter held back and started the leveller over each time.
        val layout = layoutName.getString(Charsets.US_ASCII).substringBefore('\u0000')
        val fmt = av_get_sample_fmt_name(frame.format())?.string ?: return false
        val k = "${frame.sample_rate()}|$fmt|$layout|$speed"
        if (k == key && graph != null) return true
        close()
        val g = avfilter_graph_alloc() ?: return false
        val src = AVFilterContext()
        val args = "time_base=1/${frame.sample_rate()}:sample_rate=${frame.sample_rate()}:sample_fmt=$fmt:channel_layout=$layout"
        if (avfilter_graph_create_filter(src, avfilter_get_by_name("abuffer"), "in", args, null, g) < 0) return fail(g)
        val chain = ArrayList<AVFilterContext>()
        chain += src
        fun add(name: String, filter: String, filterArgs: String): Boolean {
            val c = AVFilterContext()
            if (avfilter_graph_create_filter(c, avfilter_get_by_name(filter), name, filterArgs, null, g) < 0) return false
            chain += c
            return true
        }
        for ((name, filter, filterArgs) in steps(frame.ch_layout().nb_channels())) {
            if (!add(name, filter, filterArgs)) return fail(g)
        }
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
        builds++
        source = src
        sink = snk
        key = k
        return true
    }

    companion object {
        private const val LAYOUT_BYTES = 256

        /**
         * Films are mixed for a cinema: dialogue sits near -27 LUFS with loud moments 20 dB above it,
         * and a TV's speakers turned all the way up still left it hard to hear. A TV or an Android box
         * evens that out as it decodes; here a gentle compressor does: 2:1 above -24 dBFS, raised by
         * about 8 dB, so quiet speech comes up and the loud moments come down to meet it.
         *
         * Its attack is slow on purpose. 0.4.2.7's evened sound out within 15 ms, which caught the
         * start of every consonant and every hit and pressed it down: speech lost its edges and the
         * whole sounded muffled. At 40 ms the first instant of each sound passes as it was mixed and
         * only what follows is evened, so it stays clear. The limiter after it keeps every peak under
         * full scale, letting go smoothly instead of within 50 ms (which roughened loud scenes).
         */
        internal const val LEVEL = "threshold=0.063:ratio=2:attack=40:release=300:makeup=2.5:knee=6"

        /** The fold-down to stereo: the centre at full level, the surrounds at the standard -3 dB. */
        internal const val MIX = "${AudioOut.RATE}:ochl=stereo:osf=dbl:clev=1.0:slev=0.707"

        /** The filters from the decoder's [channels] to 48 kHz 16-bit stereo, by name, filter and arguments. */
        fun steps(channels: Int): List<Triple<String, String, String>> = buildList {
            add(Triple("rate", "aresample", MIX))
            add(Triple("mix", "aformat", "sample_fmts=dbl:channel_layouts=stereo"))
            add(Triple("level", "acompressor", LEVEL))
            add(Triple("limit", "alimiter", "limit=0.98:attack=5:release=150:level=0"))
            add(Triple("fmt", "aformat", "sample_fmts=s16:channel_layouts=stereo"))
        }
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
        layoutName.deallocate()
    }
}
