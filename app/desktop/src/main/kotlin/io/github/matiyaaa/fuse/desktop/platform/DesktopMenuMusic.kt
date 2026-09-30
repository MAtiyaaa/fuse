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
 * Menu music on Linux: MP3 through JLayer's decoder, WAV through Java Sound, both written to one
 * Java Sound line on a background thread. Volume and fades are applied to the samples, so they work
 * on every sound system. A song that can't be decoded, or no sound device, means silence.
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
            if (path == song) return
            song = path
            thread?.interrupt()
            thread = path?.takeIf { File(it).isFile }?.let { start(it) }
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
        }
    }

    private fun start(path: String): Thread = Thread({ play(path) }, "fuse-menu-music").apply {
        isDaemon = true
        start()
    }

    /** Loops [path] until the song changes. */
    private fun play(path: String) {
        val me = Thread.currentThread()
        var line: SourceDataLine? = null
        var level = 0f
        try {
            while (!me.isInterrupted && !closed && song == path) {
                val source = if (path.endsWith(".mp3", ignoreCase = true)) Mp3Source(path) else PcmSource(path)
                var played = false
                source.use {
                    while (!me.isInterrupted && song == path) {
                        // Paused: fade out, then wait until wanted again.
                        if (!wanted && level <= 0f) {
                            line?.let { l -> l.drain(); l.stop() }
                            synchronized(lock) { while (!wanted && song == path && !me.isInterrupted) lock.wait(500) }
                            line?.start()
                            continue
                        }
                        val chunk = source.next() ?: break
                        played = true
                        val format = AudioFormat(chunk.rate.toFloat(), 16, chunk.channels, true, false)
                        if (line == null || line?.format?.matches(format) != true) {
                            line?.close()
                            line = AudioSystem.getSourceDataLine(format).apply { open(format); start() }
                        }
                        val seconds = chunk.samples.size.toFloat() / chunk.channels / chunk.rate
                        val step = seconds * 1000f / if (wanted) FADE_IN_MS else FADE_OUT_MS
                        level = if (wanted) (level + step).coerceAtMost(1f) else (level - step).coerceAtLeast(0f)
                        // Perceived loudness follows the square of the level, so low settings stay usable.
                        val gain = volume * volume * level
                        val bytes = ByteArray(chunk.samples.size * 2)
                        for (i in chunk.samples.indices) {
                            val v = (chunk.samples[i] * gain).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                            bytes[i * 2] = (v and 0xFF).toByte()
                            bytes[i * 2 + 1] = (v shr 8 and 0xFF).toByte()
                        }
                        line?.write(bytes, 0, bytes.size)
                    }
                }
                // A file with nothing to play would otherwise loop without pause.
                if (!played) break
            }
        } catch (e: InterruptedException) {
            // The song changed or Fuse is closing.
        } catch (e: Exception) {
            Log.warn("menu music stopped", e)
        } finally {
            line?.close()
        }
    }

    /** Decoded samples: interleaved 16-bit values. */
    private class Chunk(val samples: ShortArray, val rate: Int, val channels: Int)

    private interface Source : AutoCloseable {
        /** The next samples, or null at the end of the song. */
        fun next(): Chunk?
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
        const val FADE_IN_MS = 1_200f
        const val FADE_OUT_MS = 500f
    }
}
