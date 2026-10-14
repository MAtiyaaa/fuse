package io.github.matiyaaa.fuse.ui.player.ffmpeg

import org.bytedeco.ffmpeg.global.avutil.AV_SAMPLE_FMT_FLTP
import org.bytedeco.ffmpeg.global.avutil.av_channel_layout_default
import org.bytedeco.ffmpeg.global.avutil.av_frame_alloc
import org.bytedeco.ffmpeg.global.avutil.av_frame_free
import org.bytedeco.ffmpeg.global.avutil.av_frame_get_buffer
import org.bytedeco.ffmpeg.global.avutil.av_frame_make_writable
import org.bytedeco.javacpp.FloatPointer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Surround folded to stereo at its proper level (no longer about 8 dB down), never clipping. Every
 * stream is then evened out (AudioLevelTest has the leveller's own checks), so these only check that
 * nothing comes out faint or past the limiter.
 */
class AudioFilterTest {
    /** The left channel's peak, out of 32767, of [seconds] of [channels]-channel sound made by [sample] (channel, time). */
    private fun peak(channels: Int, seconds: Double = 0.5, from: Double = 0.0, sample: (Int, Double) -> Float): Int {
        val filter = AudioFilter()
        val frame = av_frame_alloc()
        val size = 1024
        frame.format(AV_SAMPLE_FMT_FLTP).sample_rate(48_000).nb_samples(size)
        av_channel_layout_default(frame.ch_layout(), channels)
        av_frame_get_buffer(frame, 0)
        var peak = 0
        var t = 0L
        var heard = 0L
        try {
            while (t < seconds * 48_000) {
                // The filter may still hold the last block: this one gets a buffer of its own.
                check(av_frame_make_writable(frame) >= 0)
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
                        if (heard++ >= from * 48_000) peak = maxOf(peak, abs(left))
                        i += 4
                    }
                }
                t += size
            }
        } finally {
            av_frame_free(frame)
            filter.release()
        }
        lastBuilds = filter.builds
        return peak
    }

    /** How many times the last [peak] built its graph. */
    private var lastBuilds = 0

    private fun tone(at: Double, amplitude: Double) = (amplitude * sin(2 * PI * 440 * at)).toFloat()

    @Test
    fun dialogueInTheCentreChannelKeepsItsLevel() {
        // 5.1 (FL FR FC LFE BL BR) with speech-like sound in the centre only, at half scale.
        val p = peak(6) { c, at -> if (c == 2) tone(at, 0.5) else 0f }
        // The centre goes into each side at full level (0.5 of full scale) and the leveller only
        // raises it from there; it used to come out near 0.2, then at the textbook -3 dB (0.354).
        assertTrue(p >= 0.5 * 32767 * 0.95, "centre at full level in each side, was $p of 32767")
        assertTrue(p <= (0.985 * 32767).toInt(), "and under the limiter, was $p")
    }

    @Test
    fun aFullSurroundMixNeverClips() {
        val p = peak(6) { _, at -> tone(at, 1.0) }
        assertTrue(p <= (0.985 * 32767).toInt(), "limited below full scale, was $p")
        assertTrue(p > 25_000, "and still loud, was $p")
    }

    @Test
    fun stereoIsNeitherFaintNorClipped() {
        val p = peak(2) { _, at -> tone(at, 0.5) }
        assertTrue(p in 16_000..(0.985 * 32767).toInt(), "half scale comes out at least as loud and under the limiter, was $p")
    }

    /** How much of a sound's first [ms] milliseconds comes through, against the same sound held long enough to settle. */
    private fun onsetKept(ms: Int): Double {
        // A loud burst after quiet: its start (a consonant, a hit) is what made speech clear.
        val p = peak(2, seconds = 1.0 + ms / 1000.0, from = 1.0) { _, at -> if (at < 1.0) tone(at, 0.01) else tone(at, 0.6) }
        val settled = peak(2, seconds = 3.0, from = 2.5) { _, at -> if (at < 1.0) tone(at, 0.01) else tone(at, 0.6) }
        return p.toDouble() / settled
    }

    @Test
    fun theStartOfALoudSoundComesThroughUnflattened() {
        // Within its first 10 ms a sudden sound keeps its own level, well above where evening out
        // settles it: the leveller doesn't press it down before it is heard.
        assertTrue(onsetKept(10) > 1.15, "the start was pressed down at once (${onsetKept(10)})")
    }

    @Test
    fun theGraphIsBuiltOnceForAStream() {
        // Built again for every frame, it threw away what the limiter held back each time (gaps
        // and clicks) and started the leveller over, so it never evened anything out.
        peak(6, seconds = 2.0) { c, at -> if (c == 2) tone(at, 0.5) else tone(at, 0.1) }
        assertTrue(lastBuilds == 1, "built $lastBuilds times")
    }

    @Test
    fun aLoudStretchIsEvenedOut() {
        // Held long enough, a loud sound settles well below where it starts: the leveller works.
        val settled = peak(2, seconds = 3.0, from = 2.5) { _, at -> if (at < 1.0) tone(at, 0.01) else tone(at, 0.6) }
        assertTrue(settled < 0.7 * 32767, "a loud stretch stayed at $settled")
    }
}
