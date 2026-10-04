package io.github.matiyaaa.fuse.ui.player.ffmpeg

import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine

/**
 * The sound card, and the clock everything else follows: 48 kHz, 16-bit stereo through
 * `javax.sound`. Each block written is noted with the stream time it starts at, so the time being
 * heard is known from how many frames the card has played. Without a card (a server, a test) it
 * keeps time by the wall clock instead and drops the sound.
 */
internal class AudioOut {
    private val format = AudioFormat(RATE.toFloat(), 16, CHANNELS, true, false)
    private var line: SourceDataLine? = null
    private val lock = Object()

    /** Blocks written: the card's frame count where each starts, the stream time it starts at, and the speed it plays at. */
    private val marks = ArrayDeque<Mark>()
    private var written = 0L
    private var running = false

    /** The wall clock, when there is no card or no sound. */
    private var wallBaseMs = 0L
    private var wallStartNanos = 0L
    var speed = 1f
        set(value) {
            synchronized(lock) {
                wallBaseMs = clockMsLocked()
                wallStartNanos = System.nanoTime()
                field = value
            }
        }

    /** A stream with no sound keeps time by the wall clock even with a card open. */
    @Volatile var silent = false

    /** True when sound reaches a card; false when the clock is the wall's. */
    var hasCard = false
        private set

    private class Mark(val frame: Long, val ptsMs: Long, val speed: Float)

    fun open(): Boolean = synchronized(lock) {
        if (line != null) return true
        val l = runCatching { AudioSystem.getSourceDataLine(format) }.getOrNull() ?: return false
        return runCatching {
            l.open(format, RATE * FRAME_BYTES * BUFFER_MS / 1000)
            line = l
            hasCard = true
            true
        }.getOrElse { false }
    }

    /** Writes [length] bytes of interleaved samples that start at [ptsMs]; blocks while the card's buffer is full. */
    fun write(bytes: ByteArray, length: Int, ptsMs: Long) {
        val l = synchronized(lock) {
            marks.addLast(Mark(written, ptsMs, speed))
            while (marks.size > MAX_MARKS) marks.removeFirst()
            written += length / FRAME_BYTES
            line
        } ?: return
        var off = 0
        while (off < length) {
            val n = l.write(bytes, off, length - off)
            if (n <= 0) break
            off += n
        }
    }

    /** Starts the clock (and the card) at [ptsMs] when nothing has been written since the last reset. */
    fun start(): Unit = synchronized(lock) {
        if (running) return
        running = true
        wallStartNanos = System.nanoTime()
        line?.start()
    }

    fun pause(): Unit = synchronized(lock) {
        if (!running) return
        wallBaseMs = clockMsLocked()
        running = false
        line?.stop()
    }

    /** Drops what the card holds and puts the clock at [ptsMs] (a seek). */
    fun reset(ptsMs: Long): Unit = synchronized(lock) {
        line?.let {
            it.stop()
            it.flush()
            if (running) it.start()
        }
        marks.clear()
        written = line?.longFramePosition ?: 0L
        wallBaseMs = ptsMs
        wallStartNanos = System.nanoTime()
        resetAt = ptsMs
    }

    private var resetAt = 0L

    /** The stream time being heard (or, without sound, the wall clock's). */
    fun clockMs(): Long = synchronized(lock) { clockMsLocked() }

    private fun clockMsLocked(): Long {
        val l = line
        // With a card but nothing written yet (just opened, or just after a seek) time waits for the sound.
        if (l != null && marks.isEmpty() && !silent) return wallBaseMs
        if (l == null || silent) {
            if (!running) return wallBaseMs
            return wallBaseMs + ((System.nanoTime() - wallStartNanos) / 1_000_000 * speed).toLong()
        }
        val played = l.longFramePosition
        var m = marks.first()
        for (x in marks) {
            if (x.frame <= played) m = x else break
        }
        if (played < m.frame) return resetAt
        return m.ptsMs + ((played - m.frame) * 1000L / RATE * m.speed).toLong()
    }

    /** True once every frame written has been heard (the end of a stream). */
    fun drained(): Boolean = synchronized(lock) {
        val l = line ?: return true
        l.longFramePosition >= written - RATE / 50
    }

    /** True when the card is about to run dry: playing, with less than a little buffered. */
    fun starving(): Boolean = synchronized(lock) {
        val l = line ?: return false
        running && l.available() >= l.bufferSize - FRAME_BYTES * RATE / 50
    }

    fun close(): Unit = synchronized(lock) {
        line?.let {
            runCatching {
                it.stop()
                it.flush()
                it.close()
            }
        }
        line = null
        hasCard = false
    }

    companion object {
        const val RATE = 48_000
        const val CHANNELS = 2
        const val FRAME_BYTES = 4
        const val BUFFER_MS = 250
        private const val MAX_MARKS = 512
    }
}
