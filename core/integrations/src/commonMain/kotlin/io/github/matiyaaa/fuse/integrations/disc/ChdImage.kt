package io.github.matiyaaa.fuse.integrations.disc

import io.github.matiyaaa.fuse.integrations.ByteSource

/**
 * A CHD's tracks, from the metadata chdman writes (CHT2, CHTR, CHGD; a DVD is one track of 2048-byte
 * sectors). Tracks are addressed as on the disc, so a cue sheet's and a CHD's of the same disc hash
 * the same: each starts where the ones before it end, and a GD-ROM's high-density area at sector
 * 45000. Audio is given back in the byte order a .bin has (CHD keeps it big-endian).
 */
internal class ChdImage private constructor(private val chd: ChdFile, private val tracks: List<Info>) : DiscImage {
    internal class Info(
        val number: Int,
        val type: String,
        val frames: Long,
        val pregap: Long,
        val pregapStored: Boolean,
        /** Where its frames start in the CHD, in frames. */
        val chdFrame: Long,
        /** Its first sector after the pregap, as the disc addresses it. */
        val discFirst: Long,
    ) {
        val audio: Boolean get() = type == "AUDIO"
    }

    override suspend fun open(which: TrackWanted): DiscTrack? {
        val info = when (which) {
            is TrackWanted.Number -> tracks.firstOrNull { it.number == which.number }
            TrackWanted.FirstData -> tracks.firstOrNull { !it.audio }
            TrackWanted.Last -> tracks.lastOrNull()
            TrackWanted.Largest -> tracks.filter { !it.audio }.maxByOrNull { it.frames }
            TrackWanted.FirstOfSecondSession -> null
        } ?: return null
        return Track(info)
    }

    private inner class Track(private val info: Info) : DiscTrack {
        private val dvd = info.type == "DVD"
        private val frameBytes = if (dvd) 2048 else ChdFile.CD_FRAME
        private val rawData = if (info.audio) 2352 else 2048
        private var header = when (info.type) {
            "MODE1", "MODE2_FORM1", "DVD" -> 0
            "MODE2", "MODE2_FORM_MIX" -> 8
            "MODE2_FORM2" -> 8
            "AUDIO" -> 0
            else -> -1 // raw sectors: looked at below, as rcheevos does
        }

        override val firstSector: Long get() = info.discFirst

        private suspend fun headerSize(): Int {
            if (header >= 0) return header
            // Mode 2 discs carry "CD001" 25 bytes into sector 16 (an 8-byte subheader after the 16-byte header).
            val probe = ByteArray(32)
            val frame = info.chdFrame + (if (info.pregapStored) info.pregap else 0) + 16
            chd.read(frame * frameBytes, probe, 0, 32)
            header = if (probe.copyOfRange(25, 30).contentEquals("CD001".encodeToByteArray())) 24 else 16
            return header
        }

        override suspend fun read(sector: Long, buffer: ByteArray, length: Int): Int {
            if (sector < info.discFirst) return 0
            val head = headerSize()
            var current = sector
            var at = 0
            var left = minOf(length, buffer.size)
            while (left > 0) {
                val index = current - info.discFirst
                if (!dvd && index >= info.frames - (if (info.pregapStored) info.pregap else 0)) break
                val frame = info.chdFrame + (if (info.pregapStored) info.pregap else 0) + index
                val take = minOf(left, rawData)
                val n = chd.read(frame * frameBytes + head, buffer, at, take)
                if (info.audio) for (i in at until at + n - 1 step 2) { val t = buffer[i]; buffer[i] = buffer[i + 1]; buffer[i + 1] = t }
                at += n
                if (n < take) break
                left -= take
                current++
            }
            return at
        }
    }

    companion object {
        private const val CHT2 = 0x43485432
        private const val CHTR = 0x43485452
        private const val CHGD = 0x43484744
        private const val DVD = 0x44564420

        /** The CHD in [source] as a disc, or null when it isn't one Fuse can read (see [ChdFile.open]). */
        suspend fun open(source: ByteSource): ChdImage? {
            val chd = ChdFile.open(source).first ?: return null
            val tracks = tracksOf(chd) ?: return null
            return ChdImage(chd, tracks)
        }

        private fun field(text: String, name: String): String? = Regex("""\b$name:(\S+)""").find(text)?.groupValues?.get(1)

        internal suspend fun tracksOf(chd: ChdFile): List<Info>? {
            if (chd.metadata(DVD) != null) {
                return listOf(Info(1, "DVD", chd.logicalBytes / 2048, 0, false, 0, 0))
            }
            val raw = ArrayList<Triple<String, Int, Boolean>>() // text, kind tag, gdrom
            for (tag in listOf(CHT2, CHTR, CHGD)) {
                var i = 0
                while (true) {
                    val data = chd.metadata(tag, i++) ?: break
                    raw += Triple(data.decodeToString().trimEnd('\u0000'), tag, tag == CHGD)
                }
                if (raw.isNotEmpty()) break
            }
            if (raw.isEmpty()) return null
            val gdrom = raw.first().third
            val out = ArrayList<Info>()
            var chdFrame = 0L
            var disc = 0L
            for ((text, _, _) in raw.sortedBy { field(it.first, "TRACK")?.toIntOrNull() ?: 0 }) {
                val number = field(text, "TRACK")?.toIntOrNull() ?: return null
                val type = field(text, "TYPE") ?: return null
                val frames = field(text, "FRAMES")?.toLongOrNull() ?: return null
                val pregap = field(text, "PREGAP")?.toLongOrNull() ?: 0
                val stored = field(text, "PGTYPE")?.startsWith("V") == true
                // chdman pads every track to a multiple of 4 frames. A GD-ROM track's FRAMES already
                // count its PAD (the gap up to the next track), so tracks follow each other on the disc
                // and the high-density area starts at sector 45000 as the .gdi said.
                val pad = (4 - frames % 4) % 4
                val first = if (gdrom && number == 3) maxOf(45000L, disc + pregap) else disc + pregap
                out += Info(number, type, frames, pregap, stored, chdFrame, first)
                chdFrame += frames + pad
                disc = first - pregap + frames + (if (stored) 0 else pregap)
            }
            return out
        }
    }
}

/**
 * A CSO or ZSO (compressed ISO, as PSP and PS2 players use): blocks of the image, each deflated
 * (CSO) or LZ4-compressed (ZSO) unless it didn't shrink, read as the plain image.
 */
internal class CsoSource private constructor(
    private val source: ByteSource,
    override val size: Long,
    private val blockSize: Int,
    private val align: Int,
    private val index: LongArray,
    private val lz4: Boolean,
) : ByteSource {
    private var cachedBlock = -1L
    private val block = ByteArray(blockSize)

    override suspend fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        if (position >= size) return -1
        var done = 0
        while (done < length && position + done < size) {
            val at = position + done
            val n = at / blockSize
            if (!load(n)) break
            val inBlock = (at % blockSize).toInt()
            val take = minOf(length - done, blockSize - inBlock, (size - at).toInt())
            block.copyInto(buffer, offset + done, inBlock, inBlock + take)
            done += take
        }
        return if (done == 0) -1 else done
    }

    private suspend fun load(n: Long): Boolean {
        if (n == cachedBlock) return true
        if (n + 1 >= index.size) return false
        val raw = index[n.toInt()]
        val plain = raw and 0x80000000L != 0L
        val start = (raw and 0x7FFFFFFFL) shl align
        val end = (index[n.toInt() + 1] and 0x7FFFFFFFL) shl align
        val length = (end - start).toInt()
        if (length <= 0) return false
        val data = ByteArray(length)
        if (source.read(start, data, 0, length) < minOf(length, blockSize)) return false
        val ok = when {
            plain || length >= blockSize && !lz4 -> { data.copyInto(block, 0, 0, minOf(length, blockSize)); true }
            lz4 -> Lz4.block(data, 0, length, block, blockSize)
            else -> Decompress.rawDeflate(data, 0, length, block, 0, blockSize)
        }
        cachedBlock = if (ok) n else -1
        return ok
    }

    companion object {
        suspend fun open(source: ByteSource): CsoSource? {
            val head = ByteArray(24)
            if (source.read(0, head, 0, 24) < 24) return null
            val magic = head.copyOfRange(0, 4).decodeToString()
            if (magic != "CISO" && magic != "ZISO") return null
            fun u32(at: Int) = (head[at].toLong() and 0xFF) or ((head[at + 1].toLong() and 0xFF) shl 8) or ((head[at + 2].toLong() and 0xFF) shl 16) or ((head[at + 3].toLong() and 0xFF) shl 24)
            val total = u32(8) or (u32(12) shl 32)
            val blockSize = u32(16).toInt()
            val align = head[21].toInt() and 0xFF
            if (blockSize <= 0 || total <= 0 || blockSize > 1 shl 20) return null
            val blocks = ((total + blockSize - 1) / blockSize).toInt()
            val table = ByteArray((blocks + 1) * 4)
            if (source.read(24, table, 0, table.size) < table.size) return null
            val index = LongArray(blocks + 1) { i -> (table[i * 4].toLong() and 0xFF) or ((table[i * 4 + 1].toLong() and 0xFF) shl 8) or ((table[i * 4 + 2].toLong() and 0xFF) shl 16) or ((table[i * 4 + 3].toLong() and 0xFF) shl 24) }
            return CsoSource(source, total, blockSize, align, index, magic == "ZISO")
        }
    }
}

/** LZ4's block format (ZSO's blocks). */
internal object Lz4 {
    fun block(src: ByteArray, srcOffset: Int, srcLength: Int, dest: ByteArray, destLength: Int): Boolean {
        var s = srcOffset
        val end = srcOffset + srcLength
        var d = 0
        while (s < end) {
            val token = src[s++].toInt() and 0xFF
            var literals = token ushr 4
            if (literals == 15) {
                while (s < end) { val b = src[s++].toInt() and 0xFF; literals += b; if (b != 255) break }
            }
            if (d + literals > destLength || s + literals > end) return false
            src.copyInto(dest, d, s, s + literals)
            s += literals
            d += literals
            if (s >= end) break
            val offset = (src[s].toInt() and 0xFF) or ((src[s + 1].toInt() and 0xFF) shl 8)
            s += 2
            if (offset == 0 || offset > d) return false
            var match = (token and 0xF) + 4
            if ((token and 0xF) == 15) {
                while (s < end) { val b = src[s++].toInt() and 0xFF; match += b; if (b != 255) break }
            }
            if (d + match > destLength) return false
            for (i in 0 until match) dest[d + i] = dest[d - offset + i]
            d += match
        }
        return d == destLength
    }
}
