package io.github.matiyaaa.fuse.desktop.platform

import io.github.matiyaaa.fuse.desktop.Log
import io.github.matiyaaa.fuse.ui.shell.platform.MenuMusicPlayer
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
import javax.sound.sampled.SourceDataLine

/**
 * Menu music on Linux: MP3 through JLayer's decoder, WAV through Java Sound, mixed on one background
 * thread into one Java Sound line. Volume, fades and crossfades are applied to the samples, so they
 * work on every sound system. Changing songs while music plays crossfades (when both songs have the
 * same sample rate and channels; otherwise the new one simply takes over). A song that can't be
 * decoded, or no sound device, means silence.
 */
class DesktopMenuMusic : MenuMusicPlayer, AutoCloseable {
    @Volatile private var song: String? = null
    @Volatile private var volume = 0.2f
    @Volatile private var wanted = true
    @Volatile private var closed = false
    private val lock = Object()
    private var thread: Thread? = null

    override fun setSong(path: String?) {
        synchronized(lock) {
            val next = path?.takeIf { File(it).isFile }
            if (next == song) return
            song = next
            if (next != null && thread?.isAlive != true && !closed) {
                thread = Thread({ mix() }, "fuse-menu-music").apply {
                    isDaemon = true
                    start()
                }
            }
            lock.notifyAll()
        }
    }

    override fun setVolume(volume: Float) {
        this.volume = volume.coerceIn(0f, 1f)
    }

    override fun setPlaying(playing: Boolean) {
        wanted = playing
        synchronized(lock) { lock.notifyAll() }
    }

    override fun close() {
        closed = true
        synchronized(lock) {
            thread?.interrupt()
            thread = null
            lock.notifyAll()
        }
    }

    /** One song being played: its looping stream and where it is in the crossfade. */
    private class Voice(val path: String, val stream: LoopingStream, var mix: Float)

    /** Mixes the current song, and songs fading out, until Fuse closes or nothing is left to play. */
    private fun mix() {
        val me = Thread.currentThread()
        var line: SourceDataLine? = null
        var gate = 0f
        var current: Voice? = null
        val leaving = ArrayList<Voice>()
        try {
            while (!me.isInterrupted && !closed) {
                val want = song
                if (current?.path != want) {
                    current?.let { old -> if (gate > 0f) leaving += old else old.stream.close() }
                    current = want?.let { Voice(it, LoopingStream(it), mix = if (leaving.isNotEmpty()) 0f else 1f) }
                }
                val audible = wanted && current != null
                if (!audible && gate <= 0f) {
                    // Silent: drop what was fading out and wait until there is something to play.
                    leaving.forEach { it.stream.close() }
                    leaving.clear()
                    line?.let { l -> l.drain(); l.stop() }
                    synchronized(lock) {
                        while (!closed && !me.isInterrupted && song == current?.path && !(wanted && current != null)) lock.wait(500)
                        if (song == null && current == null) return
                    }
                    line?.start()
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
                val seconds = frames.toFloat() / rate
                gate = if (audible) (gate + seconds * 1000f / FADE_IN_MS).coerceAtMost(1f) else (gate - seconds * 1000f / FADE_OUT_MS).coerceAtLeast(0f)
                current?.let { it.mix = (it.mix + seconds * 1000f / CROSSFADE_MS).coerceAtMost(1f) }
                leaving.removeAll { v ->
                    v.mix = (v.mix - seconds * 1000f / CROSSFADE_MS).coerceAtLeast(0f)
                    (v.mix <= 0f).also { gone -> if (gone) v.stream.close() }
                }
                val lineFormat = AudioFormat(rate.toFloat(), 16, channels, true, false)
                if (line == null || line?.format?.matches(lineFormat) != true) {
                    line?.close()
                    line = AudioSystem.getSourceDataLine(lineFormat).apply { open(lineFormat); start() }
                }
                // Perceived loudness follows the square of the level, so low settings stay usable.
                val gain = volume * volume * gate
                val bytes = ByteArray(out.size * 2)
                for (i in out.indices) {
                    val v = (out[i] * gain).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                    bytes[i * 2] = (v and 0xFF).toByte()
                    bytes[i * 2 + 1] = (v shr 8 and 0xFF).toByte()
                }
                line?.write(bytes, 0, bytes.size)
            }
        } catch (e: InterruptedException) {
            // Fuse is closing.
        } catch (e: Exception) {
            Log.warn("menu music stopped", e)
        } finally {
            current?.stream?.close()
            leaving.forEach { it.stream.close() }
            line?.close()
            synchronized(lock) { if (thread === me) thread = null }
        }
    }

    /** A song decoded to 16-bit samples, starting over at its end. */
    private class LoopingStream(private val path: String) : AutoCloseable {
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

    private companion object {
        const val BLOCK_FRAMES = 2048
        const val FADE_IN_MS = 1_200f
        const val FADE_OUT_MS = 500f
        const val CROSSFADE_MS = 2_500f
    }
}
