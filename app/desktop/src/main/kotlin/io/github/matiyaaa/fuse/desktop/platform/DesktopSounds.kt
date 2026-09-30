package io.github.matiyaaa.fuse.desktop.platform

import io.github.matiyaaa.fuse.desktop.Log
import io.github.matiyaaa.fuse.model.SoundProfile
import io.github.matiyaaa.fuse.ui.designsystem.sound.SoundCue
import io.github.matiyaaa.fuse.ui.designsystem.sound.ToneSynth
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine
import io.github.matiyaaa.fuse.ui.designsystem.sound.UiSounds

/**
 * Interface sounds through javax.sound. Every cue is rendered once by [ToneSynth] per profile and
 * kept as PCM; cues are mixed in software into one output line on a single audio thread. One line
 * (instead of a Clip per cue) works on systems whose ALSA device can only be opened once, and lets
 * rapid movement sounds overlap without cutting each other off.
 *
 * When no audio device can be opened, Fuse stays silent; nothing else changes.
 */
internal class DesktopSounds : UiSounds, AutoCloseable {
    private class Voice(val pcm: ShortArray, var pos: Int = 0)

    @Volatile private var profile: SoundProfile = SoundProfile.SOFT
    @Volatile private var volume: Float = 0.6f
    @Volatile private var bank: Map<SoundCue, ShortArray> = emptyMap()
    @Volatile private var closed = false
    private val queue = LinkedBlockingQueue<ShortArray>()
    private var thread: Thread? = null
    @Volatile private var unavailable = false

    override fun play(cue: SoundCue) {
        if (closed || unavailable || profile == SoundProfile.OFF || volume <= 0.001f) return
        val pcm = bank[cue] ?: return
        if (pcm.isEmpty()) return
        // Rapid repeats never pile up: a small backlog is enough.
        if (queue.size < 8) queue.offer(pcm)
        ensureThread()
    }

    override fun setVolume(volume: Float) {
        this.volume = volume.coerceIn(0f, 1f)
    }

    override fun setProfile(profile: SoundProfile) {
        if (profile == this.profile && bank.isNotEmpty()) return
        this.profile = profile
        bank = if (profile == SoundProfile.OFF) emptyMap() else SoundCue.entries.associateWith { ToneSynth.render(it, profile) }
    }

    @Synchronized
    private fun ensureThread() {
        if (thread != null || unavailable) return
        thread = Thread(::run, "fuse-sounds").apply {
            isDaemon = true
            priority = Thread.MAX_PRIORITY - 1
            start()
        }
    }

    /**
     * Prefers ALSA's "default" device (routed through PipeWire or PulseAudio, so it is shared with
     * games) over raw hardware devices, which only one program can hold.
     */
    private fun openLine(): SourceDataLine? {
        val format = AudioFormat(ToneSynth.SAMPLE_RATE.toFloat(), 16, 1, true, false)
        val info = javax.sound.sampled.DataLine.Info(SourceDataLine::class.java, format)
        val preferred = AudioSystem.getMixerInfo().firstOrNull { it.name.startsWith("default") }
            ?.let { AudioSystem.getMixer(it) }?.takeIf { it.isLineSupported(info) }
        return try {
            val line = (preferred?.getLine(info) as? SourceDataLine) ?: AudioSystem.getSourceDataLine(format)
            // About 23 ms of buffer: low latency, still safe from underruns while mixing.
            line.open(format, FRAMES_PER_CHUNK * 2 * 4)
            line
        } catch (e: Exception) {
            if (!warned) Log.warn("no audio output for interface sounds", e)
            warned = true
            null
        }
    }

    private var warned = false

    private fun run() {
        val line = openLine()
        if (line == null) {
            queue.clear()
            // Try again later (a device may appear), but not on every cue.
            unavailable = true
            Thread({
                Thread.sleep(30_000)
                unavailable = false
            }, "fuse-sounds-retry").apply { isDaemon = true }.start()
            synchronized(this) { thread = null }
            return
        }
        val voices = ArrayList<Voice>()
        val mix = IntArray(FRAMES_PER_CHUNK)
        val out = ByteArray(FRAMES_PER_CHUNK * 2)
        var idleSince = System.nanoTime()
        try {
            while (!closed) {
                if (voices.isEmpty()) {
                    // Idle: wait for the next cue without spinning, and give the device back after a
                    // few quiet seconds so a game started from Fuse never finds it busy.
                    val next = queue.poll(250, TimeUnit.MILLISECONDS)
                    if (next == null) {
                        if (System.nanoTime() - idleSince > TimeUnit.SECONDS.toNanos(IDLE_CLOSE_SECONDS)) break
                        continue
                    }
                    voices += Voice(next)
                    if (!line.isRunning) line.start()
                }
                while (true) voices += Voice(queue.poll() ?: break)
                mix.fill(0)
                val it = voices.iterator()
                while (it.hasNext()) {
                    val v = it.next()
                    val n = minOf(FRAMES_PER_CHUNK, v.pcm.size - v.pos)
                    for (i in 0 until n) mix[i] += v.pcm[v.pos + i].toInt()
                    v.pos += n
                    if (v.pos >= v.pcm.size) it.remove()
                }
                val gain = volume
                for (i in 0 until FRAMES_PER_CHUNK) {
                    val s = (mix[i] * gain).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                    out[i * 2] = (s and 0xFF).toByte()
                    out[i * 2 + 1] = (s shr 8 and 0xFF).toByte()
                }
                line.write(out, 0, out.size)
                if (voices.isEmpty() && queue.isEmpty()) {
                    line.drain()
                    idleSince = System.nanoTime()
                }
            }
        } catch (e: Exception) {
            Log.warn("interface sounds stopped", e)
        } finally {
            line.close()
            synchronized(this) { thread = null }
            // A cue that arrived while closing starts a fresh line.
            if (!closed && queue.isNotEmpty()) ensureThread()
        }
    }

    override fun close() {
        closed = true
        queue.clear()
    }

    private companion object {
        /** 256 frames: about 6 ms per chunk at 44.1 kHz. */
        const val FRAMES_PER_CHUNK = 256
        const val IDLE_CLOSE_SECONDS = 4L
    }
}
