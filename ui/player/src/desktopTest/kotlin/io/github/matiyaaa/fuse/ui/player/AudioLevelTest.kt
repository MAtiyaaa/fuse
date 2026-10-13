package io.github.matiyaaa.fuse.ui.player

import io.github.matiyaaa.fuse.ui.player.ffmpeg.AudioFilter
import org.bytedeco.ffmpeg.global.avutil
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertTrue

/** Quiet film sound comes out louder through the player's filters, and loud sound never clips. */
class AudioLevelTest {
    private fun run(channels: Int, amplitude: Double): Pair<Double, Int> {
        val filter = AudioFilter()
        val frame = avutil.av_frame_alloc()
        val rate = 48_000
        val n = 1_024
        var sum = 0.0
        var count = 0
        var peak = 0
        try {
            for (block in 0 until 200) {
                frame.nb_samples(n)
                frame.format(avutil.AV_SAMPLE_FMT_FLT)
                frame.sample_rate(rate)
                avutil.av_channel_layout_default(frame.ch_layout(), channels)
                frame.pts(block.toLong() * n)
                check(avutil.av_frame_get_buffer(frame, 0) >= 0)
                val data = frame.data(0).capacity(n.toLong() * channels * 4).asByteBuffer().order(java.nio.ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                for (i in 0 until n) {
                    val v = (amplitude * sin(2 * PI * 440 * (block * n + i) / rate)).toFloat()
                    for (c in 0 until channels) data.put(i * channels + c, v)
                }
                check(filter.prepare(frame, 1f))
                filter.push(frame) { bytes, length ->
                    val buf = ByteArray(length)
                    bytes.position(0).get(buf, 0, length)
                    var i = 0
                    while (i + 1 < length) {
                        val s = ((buf[i + 1].toInt() shl 8) or (buf[i].toInt() and 0xFF)).toShort().toInt()
                        // The first half second lets the compressor settle.
                        if (block > 25) {
                            sum += s.toDouble() * s
                            count++
                        }
                        peak = maxOf(peak, abs(s))
                        i += 2
                    }
                }
                avutil.av_frame_unref(frame)
            }
        } finally {
            avutil.av_frame_free(frame)
            filter.release()
        }
        return sqrt(sum / count.coerceAtLeast(1)) / Short.MAX_VALUE to peak
    }

    @Test
    fun quietDialogueComesUp() {
        // A tone at -30 dBFS in stereo: it comes out at least 6 dB louder.
        val (rms, _) = run(2, 0.0316)
        assertTrue(rms > 0.0316 / sqrt(2.0) * 2.0, "rms $rms")
    }

    @Test
    fun loudSurroundNeverClips() {
        val (_, peak) = run(6, 0.9)
        assertTrue(peak < Short.MAX_VALUE, "peak $peak")
    }
}
