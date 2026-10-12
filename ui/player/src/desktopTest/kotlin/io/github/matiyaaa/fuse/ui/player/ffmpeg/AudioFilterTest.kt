package io.github.matiyaaa.fuse.ui.player.ffmpeg

import org.bytedeco.ffmpeg.global.avutil.AV_SAMPLE_FMT_FLTP
import org.bytedeco.ffmpeg.global.avutil.av_channel_layout_default
import org.bytedeco.ffmpeg.global.avutil.av_frame_alloc
import org.bytedeco.ffmpeg.global.avutil.av_frame_free
import org.bytedeco.ffmpeg.global.avutil.av_frame_get_buffer
import org.bytedeco.javacpp.FloatPointer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertTrue

/** Surround folded to stereo at its proper level (no longer about 8 dB down), never clipping; stereo untouched. */
class AudioFilterTest {
    /** The left channel's peak, out of 32767, of [seconds] of [channels]-channel sound made by [sample] (channel, time). */
    private fun peak(channels: Int, seconds: Double = 0.5, sample: (Int, Double) -> Float): Int {
        val filter = AudioFilter()
        val frame = av_frame_alloc()
        val size = 1024
        frame.format(AV_SAMPLE_FMT_FLTP).sample_rate(48_000).nb_samples(size)
        av_channel_layout_default(frame.ch_layout(), channels)
        av_frame_get_buffer(frame, 0)
        var peak = 0
        var t = 0L
        try {
            while (t < seconds * 48_000) {
                for (c in 0 until channels) {
                    val data = FloatPointer(frame.data(c))
                    for (s in 0 until size) data.put(s.toLong(), sample(c, (t + s) / 48_000.0))
                }
                frame.pts(t)
                check(filter.prepare(frame, 1f))
                filter.push(frame) { data, length ->
                    val bytes = ByteArray(length)
                    data.position(0).get(bytes, 0, length)
                    var i = 0
                    while (i + 3 < length) {
                        val left = ((bytes[i + 1].toInt() shl 8) or (bytes[i].toInt() and 0xFF)).toShort().toInt()
                        peak = maxOf(peak, abs(left))
                        i += 4
                    }
                }
                t += size
            }
        } finally {
            av_frame_free(frame)
            filter.release()
        }
        return peak
    }

    private fun tone(at: Double, amplitude: Double) = (amplitude * sin(2 * PI * 440 * at)).toFloat()

    @Test
    fun dialogueInTheCentreChannelKeepsItsLevel() {
        // 5.1 (FL FR FC LFE BL BR) with speech-like sound in the centre only, at half scale.
        val p = peak(6) { c, at -> if (c == 2) tone(at, 0.5) else 0f }
        // The standard fold-down puts the centre in each side at -3 dB: about 0.354 of full scale.
        assertTrue(p in 10_800..12_400, "centre at -3 dB in each side, was $p of 32767")
    }

    @Test
    fun aFullSurroundMixNeverClips() {
        val p = peak(6) { _, at -> tone(at, 1.0) }
        assertTrue(p <= (0.985 * 32767).toInt(), "limited below full scale, was $p")
        assertTrue(p > 25_000, "and still loud, was $p")
    }

    @Test
    fun stereoIsLeftAsItIs() {
        val p = peak(2) { _, at -> tone(at, 0.5) }
        assertTrue(p in 16_200..16_500, "half scale stays half scale, was $p")
    }
}
