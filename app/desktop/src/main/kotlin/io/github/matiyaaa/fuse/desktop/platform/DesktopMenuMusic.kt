package io.github.matiyaaa.fuse.desktop.platform

import io.github.matiyaaa.fuse.desktop.Log
import io.github.matiyaaa.fuse.ui.shell.platform.MenuMusicPlayer
import io.github.matiyaaa.fuse.ui.shell.platform.MusicState
import javazoom.jl.decoder.Bitstream
import javazoom.jl.decoder.Decoder
import javazoom.jl.decoder.SampleBuffer
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.SourceDataLine

/**
 * Where decoded music goes: one sound output at a sample rate and channel count. The real one is a
 * Java Sound line ([SharedLineOutput]); tests use their own.
 */
internal interface MusicOutput : AutoCloseable {
    val rate: Int
    val channels: Int

    /** Writes interleaved 16-bit little-endian samples, blocking while the output is full. */
    fun write(bytes: ByteArray)

    /** Lets what was written finish and stops, keeping the output open for later. */
    fun pause()
    fun resume()
}

/**
 * Menu music on Linux: MP3 through JLayer's decoder, WAV through Java Sound, mixed on one background
 * thread into one output. Volume, fades and crossfades are applied to the samples, so they work on
 * every sound system, and the output's buffer is short so a volume change is heard at once (ramped
 * across a block, so it never clicks). Changing songs while music plays crossfades (when both songs
 * have the same sample rate and channels; otherwise the new one simply takes over).
 *
 * [apply] always checks the mixer is alive: when the output fails (a device unplugged, a sound
 * server restarting) the mixer starts again after a short wait instead of leaving the song set and
 * silent. A song that can't be decoded, or no sound device at all, means silence.
 */
class DesktopMenuMusic internal constructor(
    private val openOutput: (rate: Int, channels: Int) -> MusicOutput,
    private val retryMs: Long,
) : MenuMusicPlayer, AutoCloseable {
    constructor() : this(::SharedLineOutput, RETRY_MS)

    @Volatile private var song: String? = null
    @Volatile private var volume = 0.2f
    @Volatile private var wanted = true
    @Volatile private var loop = true
    @Volatile private var ended: ((String) -> Unit)? = null
    @Volatile private var closed = false
    private val lock = Object()
    private var thread: Thread? = null

    override fun apply(state: MusicState) {
        volume = state.volume.coerceIn(0f, 1f)
        wanted = state.playing
        loop = state.loop
        synchronized(lock) {
            song = state.song?.takeIf { File(it).isFile }
            if (song != null && thread?.isAlive != true && !closed) {
                thread = Thread({ run() }, "fuse-menu-music").apply {
                    isDaemon = true
                    start()
                }
            }
            lock.notifyAll()
        }
    }

    override fun onSongEnded(listener: ((String) -> Unit)?) {
        ended = listener
    }

    override fun close() {
        closed = true
        synchronized(lock) {
            thread?.interrupt()
            thread = null
            lock.notifyAll()
        }
    }

    /** True while the mixer thread runs (for tests). */
    internal val running: Boolean get() = synchronized(lock) { thread?.isAlive == true }

    /** One song being played: its looping stream and where it is in the crossfade. */
    private class Voice(val path: String, val stream: LoopingStream, var mix: Float) {
        var reported = false
    }

    /**
     * Mixes until Fuse closes or nothing is left to play. A failing output is opened again after a
     * wait that grows with each failure in a row, up to [MAX_FAILURES]; a later [apply] starts over.
     */
    private fun run() {
        val me = Thread.currentThread()
        var failures = 0
        try {
            while (!me.isInterrupted && !closed) {
                try {
                    if (mix()) return
                    failures = 0
                } catch (e: InterruptedException) {
                    return
                } catch (e: Exception) {
                    failures++
                    Log.warn("menu music stopped ($failures)", e)
                    if (failures >= MAX_FAILURES) return
                    Thread.sleep(retryMs * failures)
                }
            }
        } catch (e: InterruptedException) {
            // Fuse is closing.
        } finally {
            synchronized(lock) { if (thread === me) thread = null }
        }
    }

    /** One mixing session. Returns true when there is nothing left to play; throws when the output fails. */
    private fun mix(): Boolean {
        val me = Thread.currentThread()
        var output: MusicOutput? = null
        var gate = 0f
        var gain = 0f
        var current: Voice? = null
        val leaving = ArrayList<Voice>()
        try {
            while (!me.isInterrupted && !closed) {
                val want = song
                if (current?.path != want) {
                    current?.let { old -> if (gate > 0f && !old.stream.ended) leaving += old else old.stream.close() }
                    current = want?.let { Voice(it, LoopingStream(it) { loop }, mix = if (leaving.isNotEmpty()) 0f else 1f) }
                }
                val audible = wanted && current != null
                if (!audible && gate <= 0f) {
                    // Silent: drop what was fading out and wait until there is something to play.
                    leaving.forEach { it.stream.close() }
                    leaving.clear()
                    output?.pause()
                    synchronized(lock) {
                        while (!closed && !me.isInterrupted && song == current?.path && !(wanted && current != null)) {
                            // Off for good: the thread ends, and the next song starts a new one.
                            if (song == null) return true
                            lock.wait(500)
                        }
                    }
                    output?.resume()
                    continue
                }
                val lead = current ?: leaving.firstOrNull()
                if (lead == null) {
                    gate = 0f
                    continue
                }
                val format = lead.stream.format() ?: run {
                    // Nothing decodable in this file.
                    lead.stream.close()
                    if (lead === current) current = null else leaving.remove(lead)
                    null
                } ?: continue
                val (rate, channels) = format
                val frames = BLOCK_FRAMES
                val out = FloatArray(frames * channels)
                val voices = listOfNotNull(current) + leaving
                for (v in voices) {
                    if (v.stream.format() != format) {
                        // A crossfade needs matching formats; otherwise the old song just stops.
                        if (v !== current) {
                            v.stream.close()
                            leaving.remove(v)
                        }
                        continue
                    }
                    val samples = v.stream.read(frames * channels) ?: continue
                    for (i in samples.indices) out[i] += samples[i] * v.mix
                }
                // A song played once (shuffle) says so, and silence plays until the next one arrives.
                current?.takeIf { it.stream.ended && !it.reported }?.let { v ->
                    v.reported = true
                    ended?.invoke(v.path)
                }
                val seconds = frames.toFloat() / rate
                gate = if (audible) (gate + seconds * 1000f / FADE_IN_MS).coerceAtMost(1f) else (gate - seconds * 1000f / FADE_OUT_MS).coerceAtLeast(0f)
                current?.let { it.mix = (it.mix + seconds * 1000f / CROSSFADE_MS).coerceAtMost(1f) }
                leaving.removeAll { v ->
                    v.mix = (v.mix - seconds * 1000f / CROSSFADE_MS).coerceAtLeast(0f)
                    (v.mix <= 0f).also { gone -> if (gone) v.stream.close() }
                }
                if (output == null || output.rate != rate || output.channels != channels) {
                    output?.close()
                    output = null
                    output = openOutput(rate, channels)
                }
                // Perceived loudness follows the square of the level, so low settings stay usable. The
                // gain glides from the last block's to this one's, so a volume change never clicks.
                val v = volume
                val target = v * v * gate
                val bytes = ByteArray(out.size * 2)
                for (f in 0 until frames) {
                    val g = gain + (target - gain) * (f + 1) / frames
                    for (c in 0 until channels) {
                        val i = f * channels + c
                        val sample = (out[i] * g).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                        bytes[i * 2] = (sample and 0xFF).toByte()
                        bytes[i * 2 + 1] = (sample shr 8 and 0xFF).toByte()
                    }
                }
                gain = target
                output.write(bytes)
            }
            return true
        } finally {
            current?.stream?.close()
            leaving.forEach { it.stream.close() }
            runCatching { output?.close() }
        }
    }

    /** A song decoded to 16-bit samples, starting over at its end while [loop] says so. */
    private class LoopingStream(private val path: String, private val loop: () -> Boolean) : AutoCloseable {
        /** Reached its end with looping off. */
        var ended = false
            private set
        private var source: Source? = null
        private var pending = ShortArray(0)
        private var offset = 0
        private var format: Pair<Int, Int>? = null
        private var empty = false

        /** Sample rate and channels, or null when the file has nothing to play. */
        fun format(): Pair<Int, Int>? {
            if (format == null && !empty) fill()
            return format
        }

        /** The next [count] samples, or null when the file has nothing to play. */
        fun read(count: Int): ShortArray? {
            val out = ShortArray(count)
            var n = 0
            while (n < count) {
                if (offset >= pending.size && !fill()) return if (n == 0) null else out
                val take = minOf(count - n, pending.size - offset)
                pending.copyInto(out, n, offset, offset + take)
                offset += take
                n += take
            }
            return out
        }

        /** Decodes the next chunk, reopening the file at its end; false when it has nothing to play. */
        private fun fill(): Boolean {
            if (empty) return false
            if (ended) {
                if (!loop()) return false
                ended = false
            }
            repeat(2) {
                val s = source ?: open().also { source = it }
                val chunk = s.next()
                if (chunk != null) {
                    if (format == null) format = chunk.rate to chunk.channels
                    pending = chunk.samples
                    offset = 0
                    return true
                }
                s.close()
                source = null
                if (format != null && !loop()) {
                    ended = true
                    return false
                }
            }
            empty = true
            return false
        }

        private fun open(): Source = if (path.endsWith(".mp3", ignoreCase = true)) Mp3Source(path) else PcmSource(path)

        override fun close() {
            source?.close()
            source = null
        }
    }

    /** Decoded samples: interleaved 16-bit values. */
    private class Chunk(val samples: ShortArray, val rate: Int, val channels: Int)

    private interface Source : AutoCloseable {
        /** The next samples, or null at the end of the song. */
        fun next(): Chunk?

        override fun close()
    }

    private class Mp3Source(path: String) : Source {
        private val input: InputStream = BufferedInputStream(FileInputStream(path))
        private val bitstream = Bitstream(input)
        private val decoder = Decoder()

        override fun next(): Chunk? {
            val header = bitstream.readFrame() ?: return null
            val out = decoder.decodeFrame(header, bitstream) as SampleBuffer
            bitstream.closeFrame()
            return Chunk(out.buffer.copyOf(out.bufferLength), out.sampleFrequency, out.channelCount)
        }

        override fun close() {
            runCatching { bitstream.close() }
            runCatching { input.close() }
        }
    }

    private class PcmSource(path: String) : Source {
        private val stream: AudioInputStream
        private val rate: Int
        private val channels: Int

        init {
            val raw = AudioSystem.getAudioInputStream(File(path))
            val f = raw.format
            val target = AudioFormat(AudioFormat.Encoding.PCM_SIGNED, f.sampleRate, 16, f.channels, f.channels * 2, f.sampleRate, false)
            stream = AudioSystem.getAudioInputStream(target, raw)
            rate = f.sampleRate.toInt()
            channels = f.channels
        }

        override fun next(): Chunk? {
            val buf = ByteArray(4096 * channels)
            val n = stream.read(buf)
            if (n <= 0) return null
            val samples = ShortArray(n / 2) { i -> ((buf[i * 2].toInt() and 0xFF) or (buf[i * 2 + 1].toInt() shl 8)).toShort() }
            return Chunk(samples, rate, channels)
        }

        override fun close() {
            runCatching { stream.close() }
        }
    }

    internal companion object {
        /** About 23 ms at 44.1 kHz: how often the volume and fades are worked out. */
        const val BLOCK_FRAMES = 1024
        const val MAX_FAILURES = 5
        const val RETRY_MS = 1_000L
        const val FADE_IN_MS = 1_200f
        const val FADE_OUT_MS = 500f
        const val CROSSFADE_MS = 2_500f
    }
}

/**
 * A Java Sound line, preferring ALSA's "default" device (routed through PipeWire or PulseAudio, so it
 * is shared with the interface sounds and games) over raw hardware devices only one program can hold.
 * Its buffer holds about four mixing blocks, so a change is heard within a tenth of a second.
 */
private class SharedLineOutput(override val rate: Int, override val channels: Int) : MusicOutput {
    private val line: SourceDataLine

    init {
        val format = AudioFormat(rate.toFloat(), 16, channels, true, false)
        val info = DataLine.Info(SourceDataLine::class.java, format)
        val preferred = AudioSystem.getMixerInfo().firstOrNull { it.name.startsWith("default") }
            ?.let { AudioSystem.getMixer(it) }?.takeIf { it.isLineSupported(info) }
        line = (preferred?.getLine(info) as? SourceDataLine) ?: AudioSystem.getSourceDataLine(format)
        line.open(format, DesktopMenuMusic.BLOCK_FRAMES * channels * 2 * 4)
        line.start()
    }

    override fun write(bytes: ByteArray) {
        line.write(bytes, 0, bytes.size)
    }

    override fun pause() {
        line.drain()
        line.stop()
    }

    override fun resume() = line.start()

    override fun close() = line.close()
}
