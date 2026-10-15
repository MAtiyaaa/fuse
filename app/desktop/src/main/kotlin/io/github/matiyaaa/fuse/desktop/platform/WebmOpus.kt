package io.github.matiyaaa.fuse.desktop.platform

import io.github.jaredmdobson.concentus.OpusDecoder

/**
 * The Opus song in a WebM file, as Fuse's menu music ships it (tools/music/encode.sh): the stream's
 * channels and pre-skip from its OpusHead, and its packets in order. Only what such a file holds is
 * read: one audio track, blocks without lacing. The whole file is held in memory (a song is a couple
 * of megabytes), so nothing is read from disk while the music plays.
 */
internal class WebmOpus private constructor(private val data: ByteArray) {
    /** Channels, from the OpusHead (1 or 2). */
    var channels = 2
        private set

    /** Samples (per channel, at 48 kHz) the encoder put before the music, which are dropped. */
    var preSkip = 0
        private set

    private val packets = ArrayList<IntRange>()

    /** The packets' bytes, in order. */
    val packetCount: Int get() = packets.size

    fun packet(i: Int): Pair<Int, Int> = packets[i].let { it.first to (it.last - it.first + 1) }

    fun bytes(): ByteArray = data

    private var pos = 0

    private fun vint(keepMarker: Boolean): Long {
        if (pos >= data.size) throw IllegalStateException("Ran past the end of the file")
        val first = data[pos].toInt() and 0xFF
        var length = 1
        var mask = 0x80
        while (length <= 8 && first and mask == 0) {
            length++
            mask = mask shr 1
        }
        if (length > 8 || pos + length > data.size) throw IllegalStateException("A malformed element")
        var value = (if (keepMarker) first else first and (mask - 1)).toLong()
        for (k in 1 until length) value = (value shl 8) or (data[pos + k].toLong() and 0xFF)
        // All ones after the marker: a size that isn't known (an element that runs to its parent's end).
        val unknown = !keepMarker && value == (1L shl (7 * length)) - 1
        pos += length
        return if (unknown) -1 else value
    }

    private fun walk(end: Int) {
        while (pos < end) {
            val id = vint(keepMarker = true)
            val size = vint(keepMarker = false)
            val start = pos
            val stop = if (size < 0) end else (start + size).toInt().coerceAtMost(end)
            when (id) {
                SEGMENT, TRACKS, TRACK_ENTRY, CLUSTER, BLOCK_GROUP -> walk(stop)
                CODEC_PRIVATE -> head(start, stop)
                SIMPLE_BLOCK, BLOCK -> block(start, stop)
            }
            pos = stop
        }
    }

    private fun head(start: Int, stop: Int) {
        // "OpusHead", version, channels, pre-skip (little-endian), rate, gain, mapping.
        if (stop - start < 19 || String(data, start, 8, Charsets.US_ASCII) != "OpusHead") return
        channels = (data[start + 9].toInt() and 0xFF).coerceIn(1, 2)
        preSkip = (data[start + 10].toInt() and 0xFF) or ((data[start + 11].toInt() and 0xFF) shl 8)
    }

    private fun block(start: Int, stop: Int) {
        pos = start
        vint(keepMarker = false) // the track
        val flags = data[pos + 2].toInt() and 0xFF
        val payload = pos + 3
        // Laced blocks are never written for these files; one would be skipped rather than misread.
        if (flags and 0x06 != 0 || payload >= stop) return
        packets += payload until stop
    }

    companion object {
        private const val SEGMENT = 0x18538067L
        private const val TRACKS = 0x1654AE6BL
        private const val TRACK_ENTRY = 0xAEL
        private const val CODEC_PRIVATE = 0x63A2L
        private const val CLUSTER = 0x1F43B675L
        private const val BLOCK_GROUP = 0xA0L
        private const val SIMPLE_BLOCK = 0xA3L
        private const val BLOCK = 0xA1L

        /** The file's song, or null when it isn't a WebM file holding Opus packets. */
        fun read(bytes: ByteArray): WebmOpus? = runCatching {
            WebmOpus(bytes).apply {
                walk(bytes.size)
            }.takeIf { it.packets.isNotEmpty() }
        }.getOrNull()
    }
}

/**
 * Decodes [song] packet by packet into 16-bit samples at 48 kHz, dropping the encoder's pre-skip so
 * the song loops without a gap.
 */
internal class OpusPcm(private val song: WebmOpus) {
    private val decoder = OpusDecoder(RATE, song.channels)
    private val out = ShortArray(MAX_FRAME * song.channels)
    private var next = 0
    private var skip = song.preSkip

    val channels: Int get() = song.channels

    /** The next block of interleaved samples, or null at the end of the song. */
    fun next(): ShortArray? {
        while (next < song.packetCount) {
            val (at, length) = song.packet(next++)
            val frames = runCatching { decoder.decode(song.bytes(), at, length, out, 0, MAX_FRAME, false) }.getOrDefault(0)
            if (frames <= 0) continue
            val drop = minOf(skip, frames)
            skip -= drop
            if (drop == frames) continue
            return out.copyOfRange(drop * channels, frames * channels)
        }
        return null
    }

    companion object {
        const val RATE = 48_000

        /** The longest Opus packet: 120 ms at 48 kHz. */
        const val MAX_FRAME = 5_760
    }
}
