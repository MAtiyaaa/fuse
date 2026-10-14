package io.github.matiyaaa.fuse.ui.designsystem.sound

import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * What Fuse itself plays on a computer (interface sounds, menu music, Fuse Player), for a recording
 * of Fuse's window. Each part hands over exactly what it writes to its own output; while a recording
 * listens, the parts are mixed into one 48 kHz stereo stream on a steady clock, silence where nothing
 * plays. While nothing listens, handing sound over costs one check and nothing is kept.
 */
object AudioTap {
    const val RATE = 48_000

    @Volatile private var mixer: Mixer? = null

    /** True while a recording listens. */
    val listening: Boolean get() = mixer != null

    /**
     * [length] bytes of 16-bit little-endian samples from [source], [channels] interleaved at [rate],
     * as they were just written to its output.
     */
    fun feed(source: String, pcm: ByteArray, offset: Int, length: Int, rate: Int, channels: Int) {
        val m = mixer ?: return
        if (length <= 0 || rate <= 0 || channels <= 0) return
        m.feed(source, pcm, offset, length, rate, channels)
    }

    /**
     * Starts listening: from [startNanos] (a [System.nanoTime]) on, [out] gets 16-bit little-endian
     * stereo at [RATE], exactly [RATE] frames for every second that passes, until the returned
     * listening is closed. One recording listens at a time; null when another already does.
     */
    @Synchronized
    fun listen(out: OutputStream, startNanos: Long = System.nanoTime()): AutoCloseable? {
        if (mixer != null) return null
        val m = Mixer(out, startNanos)
        mixer = m
        m.start()
        return AutoCloseable {
            synchronized(this) { if (mixer === m) mixer = null }
            m.stop()
        }
    }

    /** One source's sound, made 48 kHz stereo and kept until the mixer takes it. */
    internal class Track {
        private val ring = ShortArray(RATE * 2 * BUFFER_SECONDS)
        private var head = 0
        private var count = 0
        private var primed = false

        // Linear resampling: where the next output frame falls, in input frames after [lastL]/[lastR].
        private var phase = 0.0
        private var lastL = 0
        private var lastR = 0
        private var started = false

        @Synchronized
        fun put(pcm: ByteArray, offset: Int, length: Int, rate: Int, channels: Int) {
            val frames = length / (2 * channels)
            if (frames <= 0) return
            fun sample(frame: Int, channel: Int): Int {
                val at = offset + (frame * channels + minOf(channel, channels - 1)) * 2
                return (pcm[at].toInt() and 0xFF) or (pcm[at + 1].toInt() shl 8)
            }
            if (rate == RATE) {
                for (f in 0 until frames) push(sample(f, 0), sample(f, 1))
                lastL = sample(frames - 1, 0); lastR = sample(frames - 1, 1); started = true
                return
            }
            if (!started) {
                lastL = sample(0, 0); lastR = sample(0, 1); started = true
            }
            val step = rate.toDouble() / RATE
            // Input frame i is at position i + 1 here; position 0 is the last frame of the chunk before.
            var pos = phase
            while (pos < frames) {
                val i = pos.toInt()
                val t = pos - i
                val l0 = if (i == 0) lastL else sample(i - 1, 0)
                val r0 = if (i == 0) lastR else sample(i - 1, 1)
                val l1 = sample(i, 0)
                val r1 = sample(i, 1)
                push((l0 + (l1 - l0) * t).toInt(), (r0 + (r1 - r0) * t).toInt())
                pos += step
            }
            phase = pos - frames
            lastL = sample(frames - 1, 0); lastR = sample(frames - 1, 1)
        }

        private fun push(l: Int, r: Int) {
            if (count == ring.size) { head = (head + 2) % ring.size; count -= 2 }
            val tail = (head + count) % ring.size
            ring[tail] = l.toShort()
            ring[tail + 1] = r.toShort()
            count += 2
        }

        /**
         * Adds up to [frames] frames into [mix]. A source starts being heard once a little of it is
         * kept ([PRIME_FRAMES]), so one that hands sound over in bursts plays smoothly.
         */
        @Synchronized
        fun takeInto(mix: IntArray, frames: Int) {
            if (!primed) {
                if (count < PRIME_FRAMES * 2) return
                primed = true
            }
            val n = minOf(frames * 2, count)
            for (i in 0 until n) mix[i] += ring[(head + i) % ring.size].toInt()
            head = (head + n) % ring.size
            count -= n
            if (count == 0) primed = false
        }
    }

    private class Mixer(private val out: OutputStream, private val startNanos: Long) {
        private val tracks = ConcurrentHashMap<String, Track>()
        @Volatile private var running = true
        private var thread: Thread? = null

        fun feed(source: String, pcm: ByteArray, offset: Int, length: Int, rate: Int, channels: Int) {
            tracks.getOrPut(source) { Track() }.put(pcm, offset, length, rate, channels)
        }

        fun start() {
            thread = Thread(::run, "fuse-recording-sound").apply { isDaemon = true; start() }
        }

        fun stop() {
            running = false
            thread?.join(2_000)
        }

        private fun run() {
            val mix = IntArray(BLOCK_FRAMES * 2)
            val bytes = ByteArray(BLOCK_FRAMES * 4)
            var written = 0L
            try {
                while (running) {
                    val due = (System.nanoTime() - startNanos).coerceAtLeast(0) * RATE / 1_000_000_000L
                    if (written + BLOCK_FRAMES > due) {
                        Thread.sleep(4)
                        continue
                    }
                    mix.fill(0)
                    for (t in tracks.values) t.takeInto(mix, BLOCK_FRAMES)
                    for (i in mix.indices) {
                        val s = mix[i].coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                        bytes[i * 2] = (s and 0xFF).toByte()
                        bytes[i * 2 + 1] = (s shr 8).toByte()
                    }
                    out.write(bytes)
                    written += BLOCK_FRAMES
                }
            } catch (e: Exception) {
                // The encoder stopped listening: the picture carries on alone.
            } finally {
                runCatching { out.flush() }
            }
        }
    }

    /** 10 ms. */
    internal const val BLOCK_FRAMES = RATE / 100
    internal const val PRIME_FRAMES = RATE * 60 / 1000
    private const val BUFFER_SECONDS = 1
}
