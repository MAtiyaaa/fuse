package io.github.matiyaaa.fuse.integrations.disc

import io.github.matiyaaa.fuse.integrations.ByteSource
import io.github.matiyaaa.fuse.integrations.readFully

/**
 * A CHD (MAME's "Compressed Hunks of Data") read the way libchdr reads it: versions 3 to 5, every
 * codec chdman writes for CD-ROMs and DVDs (zlib, LZMA, Huffman, Zstandard and their CD forms),
 * the compressed hunk map, and the metadata naming the tracks. Images that need a parent CHD, and
 * the rare hunk chdman stored with FLAC, are reported as unreadable: such games are matched by name.
 */
internal class ChdFile private constructor(
    private val source: ByteSource,
    private val version: Int,
    val hunkBytes: Int,
    val unitBytes: Int,
    val logicalBytes: Long,
    private val codecs: IntArray,
    private val map: ByteArray,
    private val metaOffset: Long,
) {
    private val hunkCount = ((logicalBytes + hunkBytes - 1) / hunkBytes).toInt()
    private var cachedHunk = -1
    private val cache = ByteArray(hunkBytes)
    private val compressed get() = codecs[0] != 0

    /** [hunk]'s bytes, or null when it can't be read. The last one read is kept. */
    suspend fun hunk(hunk: Int): ByteArray? {
        if (hunk == cachedHunk) return cache
        if (!read(hunk, cache, 0)) {
            cachedHunk = -1
            return null
        }
        cachedHunk = hunk
        return cache
    }

    /** Reads [length] logical bytes at [position]; the count read. */
    suspend fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        var done = 0
        while (done < length) {
            val at = position + done
            if (at >= logicalBytes) break
            val data = hunk((at / hunkBytes).toInt()) ?: break
            val inHunk = (at % hunkBytes).toInt()
            val take = minOf(length - done, hunkBytes - inHunk, (logicalBytes - at).toInt().coerceAtLeast(0))
            data.copyInto(buffer, offset + done, inHunk, inHunk + take)
            done += take
        }
        return done
    }

    private suspend fun read(hunk: Int, dest: ByteArray, depth: Int): Boolean {
        if (hunk !in 0 until hunkCount || depth > 8) return false
        return if (version >= 5) readV5(hunk, dest, depth) else readV34(hunk, dest, depth)
    }

    private suspend fun readV5(hunk: Int, dest: ByteArray, depth: Int): Boolean {
        if (!compressed) {
            val block = u32(map, hunk * 4)
            if (block == 0L) return false
            return source.readFully(block * hunkBytes, dest, 0, hunkBytes) == hunkBytes
        }
        val at = hunk * 12
        val type = map[at].toInt() and 0xFF
        val length = u24(map, at + 1).toInt()
        val offset = u48(map, at + 4)
        val crc = u16(map, at + 10).toInt()
        return when (type) {
            in 0..3 -> {
                val data = ByteArray(length)
                if (source.readFully(offset, data, 0, length) != length) return false
                decompress(codecs[type], data, dest) && crc16(dest, hunkBytes) == crc
            }
            COMPRESSION_NONE -> source.readFully(offset, dest, 0, hunkBytes) == hunkBytes && crc16(dest, hunkBytes) == crc
            COMPRESSION_SELF -> offset < hunk && read(offset.toInt(), dest, depth + 1)
            // A parent CHD isn't at hand: the game is matched by name.
            else -> false
        }
    }

    private suspend fun readV34(hunk: Int, dest: ByteArray, depth: Int): Boolean {
        val at = hunk * 16
        val offset = u64(map, at)
        val length = u16(map, at + 12).toInt() or ((map[at + 14].toInt() and 0xFF) shl 16)
        return when (map[at + 15].toInt() and 0x0F) {
            V34_COMPRESSED -> {
                val data = ByteArray(length)
                if (source.readFully(offset, data, 0, length) != length) return false
                // Versions 3 and 4 only ever used zlib (codecs 1 and 2) for discs.
                codecs[0] in 1..2 && Decompress.rawDeflate(data, 0, length, dest, 0, hunkBytes)
            }
            V34_UNCOMPRESSED -> source.readFully(offset, dest, 0, hunkBytes) == hunkBytes
            V34_MINI -> {
                for (i in 0 until 8) dest[i] = (offset ushr (56 - 8 * i)).toByte()
                for (i in 8 until hunkBytes) dest[i] = dest[i - 8]
                true
            }
            V34_SELF -> offset < hunk && read(offset.toInt(), dest, depth + 1)
            else -> false
        }
    }

    private fun decompress(codec: Int, src: ByteArray, dest: ByteArray): Boolean = when (codec) {
        ZLIB -> Decompress.rawDeflate(src, 0, src.size, dest, 0, hunkBytes)
        LZMA -> Decompress.lzma(src, 0, src.size, LZMA_PROPS, hunkBytes, dest, 0, hunkBytes)
        ZSTD -> Decompress.zstd(src, 0, src.size, dest, 0, hunkBytes)
        HUFFMAN -> huffman(src, dest, hunkBytes)
        CD_ZLIB -> cd(src, dest) { s, o, l, d, n -> Decompress.rawDeflate(s, o, l, d, 0, n) }
        CD_LZMA -> cd(src, dest) { s, o, l, d, n -> Decompress.lzma(s, o, l, LZMA_PROPS, n, d, 0, n) }
        CD_ZSTD -> cd(src, dest) { s, o, l, d, n -> Decompress.zstd(s, o, l, d, 0, n) }
        else -> false
    }

    private fun huffman(src: ByteArray, dest: ByteArray, length: Int): Boolean {
        val reader = BitReader(src, 0, src.size)
        val decoder = Huffman(256, 16)
        if (!decoder.importHuffman(reader)) return false
        for (i in 0 until length) dest[i] = decoder.decode(reader).toByte()
        return !reader.overflowed
    }

    /**
     * The CD codecs: each frame's 2352 bytes of sector data with one codec, its 96 bytes of subcode
     * with zlib (left out here: nothing that hashes reads it). Frames whose sync pattern and error
     * correction chdman dropped, because they can be made again, get the sync pattern back.
     */
    private inline fun cd(src: ByteArray, dest: ByteArray, base: (ByteArray, Int, Int, ByteArray, Int) -> Boolean): Boolean {
        val frames = hunkBytes / CD_FRAME
        val lengthBytes = if (hunkBytes < 65536) 2 else 3
        val eccBytes = (frames + 7) / 8
        val headerBytes = eccBytes + lengthBytes
        if (src.size < eccBytes + lengthBytes) return false
        var baseLength = ((src[eccBytes].toInt() and 0xFF) shl 8) or (src[eccBytes + 1].toInt() and 0xFF)
        if (lengthBytes > 2) baseLength = (baseLength shl 8) or (src[eccBytes + 2].toInt() and 0xFF)
        if (src.size < headerBytes + baseLength) return false
        val sectors = ByteArray(frames * CD_SECTOR)
        if (!base(src, headerBytes, baseLength, sectors, sectors.size)) return false
        dest.fill(0, 0, hunkBytes)
        for (frame in 0 until frames) {
            sectors.copyInto(dest, frame * CD_FRAME, frame * CD_SECTOR, (frame + 1) * CD_SECTOR)
            if ((src[frame / 8].toInt() and (1 shl (frame % 8))) != 0) SYNC.copyInto(dest, frame * CD_FRAME)
        }
        return true
    }

    /** The [index]th metadata entry tagged [tag], or null. */
    suspend fun metadata(tag: Int, index: Int = 0): ByteArray? {
        var at = metaOffset
        var seen = 0
        val header = ByteArray(16)
        var steps = 0
        while (at != 0L && steps++ < MAX_METADATA) {
            if (source.readFully(at, header, 0, 16) != 16) return null
            val entryTag = u32(header, 0).toInt()
            val length = u24(header, 5).toInt()
            val next = u64(header, 8)
            if (entryTag == tag) {
                if (seen == index) {
                    val data = ByteArray(length)
                    return if (source.readFully(at + 16, data, 0, length) == length) data else null
                }
                seen++
            }
            at = next
        }
        return null
    }

    companion object {
        const val CD_SECTOR = 2352
        const val CD_SUBCODE = 96
        const val CD_FRAME = CD_SECTOR + CD_SUBCODE

        private const val ZLIB = 0x7A6C6962 // zlib
        private const val LZMA = 0x6C7A6D61 // lzma
        private const val HUFFMAN = 0x68756666 // huff
        private const val ZSTD = 0x7A737464 // zstd
        private const val CD_ZLIB = 0x63647A6C // cdzl
        private const val CD_LZMA = 0x63646C7A // cdlz
        private const val CD_ZSTD = 0x63647A73 // cdzs

        /** lc 3, lp 0, pb 2: what chdman's LZMA encoder writes. */
        private const val LZMA_PROPS = 93

        private const val COMPRESSION_NONE = 4
        private const val COMPRESSION_SELF = 5
        private const val COMPRESSION_PARENT = 6
        private const val RLE_SMALL = 7
        private const val RLE_LARGE = 8
        private const val SELF_0 = 9
        private const val SELF_1 = 10
        private const val PARENT_SELF = 11
        private const val PARENT_0 = 12
        private const val PARENT_1 = 13

        private const val V34_COMPRESSED = 1
        private const val V34_UNCOMPRESSED = 2
        private const val V34_MINI = 3
        private const val V34_SELF = 4

        private const val MAX_METADATA = 4096
        private const val MAX_MAP_BYTES = 64L * 1024 * 1024
        private val SYNC = byteArrayOf(0, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 0)

        /** Why a CHD can't be read. */
        enum class Problem { NOT_A_CHD, NEEDS_PARENT, UNSUPPORTED }

        /** The CHD in [source], or why it can't be read. */
        suspend fun open(source: ByteSource): Pair<ChdFile?, Problem?> {
            val head = ByteArray(124)
            if (source.readFully(0, head, 0, 16) != 16 || !head.copyOfRange(0, 8).contentEquals("MComprHD".encodeToByteArray())) return null to Problem.NOT_A_CHD
            val length = u32(head, 8).toInt()
            val version = u32(head, 12).toInt()
            if (version !in 3..5 || length > head.size) return null to Problem.UNSUPPORTED
            if (source.readFully(0, head, 0, length) != length) return null to Problem.NOT_A_CHD
            return when (version) {
                5 -> openV5(source, head)
                else -> openV34(source, head, version)
            }
        }

        private suspend fun openV5(source: ByteSource, head: ByteArray): Pair<ChdFile?, Problem?> {
            val codecs = IntArray(4) { u32(head, 16 + it * 4).toInt() }
            val logical = u64(head, 32)
            val mapOffset = u64(head, 40)
            val metaOffset = u64(head, 48)
            val hunkBytes = u32(head, 56).toInt()
            val unitBytes = u32(head, 60).toInt()
            if (hunkBytes <= 0 || unitBytes <= 0) return null to Problem.UNSUPPORTED
            // A parent's SHA-1 that isn't all zeros: the image only holds what differs from it.
            if ((104 until 124).any { head[it] != 0.toByte() }) return null to Problem.NEEDS_PARENT
            val hunks = ((logical + hunkBytes - 1) / hunkBytes)
            if (hunks * 12 > MAX_MAP_BYTES) return null to Problem.UNSUPPORTED
            val map = if (codecs[0] == 0) {
                ByteArray((hunks * 4).toInt()).also { if (source.readFully(mapOffset, it, 0, it.size) != it.size) return null to Problem.NOT_A_CHD }
            } else {
                decodeMap(source, mapOffset, hunks.toInt(), hunkBytes, unitBytes) ?: return null to Problem.NOT_A_CHD
            }
            if (codecs[0] != 0) {
                for (i in 0 until hunks.toInt()) {
                    val type = map[i * 12].toInt() and 0xFF
                    if (type == COMPRESSION_PARENT) return null to Problem.NEEDS_PARENT
                }
            }
            return ChdFile(source, 5, hunkBytes, unitBytes, logical, codecs, map, metaOffset) to null
        }

        private suspend fun openV34(source: ByteSource, head: ByteArray, version: Int): Pair<ChdFile?, Problem?> {
            val flags = u32(head, 16)
            if (flags and 1L != 0L) return null to Problem.NEEDS_PARENT
            val codec = u32(head, 20).toInt()
            val hunks = u32(head, 24)
            val logical = u64(head, 28)
            val metaOffset = u64(head, 36)
            val hunkBytes = (if (version == 3) u32(head, 76) else u32(head, 44)).toInt()
            if (hunkBytes <= 0 || hunks * 16 > MAX_MAP_BYTES) return null to Problem.UNSUPPORTED
            val map = ByteArray((hunks * 16).toInt())
            if (source.readFully(head.size.toLong().coerceAtMost(if (version == 3) 120L else 108L), map, 0, map.size) != map.size) return null to Problem.NOT_A_CHD
            // Discs of these versions are kept in 2448-byte frames.
            return ChdFile(source, version, hunkBytes, CD_FRAME, logical, intArrayOf(codec, 0, 0, 0), map, metaOffset) to null
        }

        /** The v5 map: each hunk's codec Huffman-coded with runs, then its length, offset and CRC as bits. */
        private suspend fun decodeMap(source: ByteSource, at: Long, hunks: Int, hunkBytes: Int, unitBytes: Int): ByteArray? {
            val head = ByteArray(16)
            if (source.readFully(at, head, 0, 16) != 16) return null
            val mapBytes = u32(head, 0)
            val firstOffset = u48(head, 4)
            val mapCrc = u16(head, 10).toInt()
            val lengthBits = head[12].toInt() and 0xFF
            val selfBits = head[13].toInt() and 0xFF
            val parentBits = head[14].toInt() and 0xFF
            if (lengthBits > 32 || selfBits > 32 || parentBits > 32 || mapBytes > MAX_MAP_BYTES) return null
            val packed = ByteArray(mapBytes.toInt())
            if (source.readFully(at + 16, packed, 0, packed.size) != packed.size) return null
            val reader = BitReader(packed, 0, packed.size)
            val decoder = Huffman(16, 8)
            if (!decoder.importRle(reader)) return null
            val map = ByteArray(hunks * 12)
            var repeat = 0
            var last = 0
            for (hunk in 0 until hunks) {
                if (repeat > 0) {
                    map[hunk * 12] = last.toByte()
                    repeat--
                } else {
                    when (val value = decoder.decode(reader)) {
                        RLE_SMALL -> { map[hunk * 12] = last.toByte(); repeat = 2 + decoder.decode(reader) }
                        RLE_LARGE -> { map[hunk * 12] = last.toByte(); repeat = 2 + 16 + (decoder.decode(reader) shl 4); repeat += decoder.decode(reader) }
                        else -> { last = value; map[hunk * 12] = value.toByte() }
                    }
                }
            }
            var current = firstOffset
            var lastSelf = 0L
            var lastParent = 0L
            for (hunk in 0 until hunks) {
                val e = hunk * 12
                var offset = current
                var length = 0L
                var crc = 0
                when (map[e].toInt() and 0xFF) {
                    0, 1, 2, 3 -> { length = reader.read(lengthBits).toLong() and 0xFFFFFFFFL; current += length; crc = reader.read(16) }
                    COMPRESSION_NONE -> { length = hunkBytes.toLong(); current += length; crc = reader.read(16) }
                    COMPRESSION_SELF -> { offset = reader.read(selfBits).toLong() and 0xFFFFFFFFL; lastSelf = offset }
                    COMPRESSION_PARENT -> { offset = reader.read(parentBits).toLong() and 0xFFFFFFFFL; lastParent = offset }
                    SELF_1, SELF_0 -> {
                        if ((map[e].toInt() and 0xFF) == SELF_1) lastSelf++
                        map[e] = COMPRESSION_SELF.toByte()
                        offset = lastSelf
                    }
                    PARENT_SELF -> { map[e] = COMPRESSION_PARENT.toByte(); offset = hunk.toLong() * hunkBytes / unitBytes; lastParent = offset }
                    PARENT_1, PARENT_0 -> {
                        if ((map[e].toInt() and 0xFF) == PARENT_1) lastParent += (hunkBytes / unitBytes).toLong()
                        map[e] = COMPRESSION_PARENT.toByte()
                        offset = lastParent
                    }
                }
                put24(map, e + 1, length)
                put48(map, e + 4, offset)
                put16(map, e + 10, crc)
            }
            if (crc16(map, map.size) != mapCrc) return null
            return map
        }

        private fun u16(b: ByteArray, at: Int): Long = ((b[at].toLong() and 0xFF) shl 8) or (b[at + 1].toLong() and 0xFF)
        private fun u24(b: ByteArray, at: Int): Long = ((b[at].toLong() and 0xFF) shl 16) or u16(b, at + 1)
        private fun u32(b: ByteArray, at: Int): Long = ((b[at].toLong() and 0xFF) shl 24) or u24(b, at + 1)
        private fun u48(b: ByteArray, at: Int): Long = (u16(b, at) shl 32) or u32(b, at + 2)
        private fun u64(b: ByteArray, at: Int): Long = (u32(b, at) shl 32) or u32(b, at + 4)
        private fun put16(b: ByteArray, at: Int, v: Int) { b[at] = (v ushr 8).toByte(); b[at + 1] = v.toByte() }
        private fun put24(b: ByteArray, at: Int, v: Long) { b[at] = (v ushr 16).toByte(); b[at + 1] = (v ushr 8).toByte(); b[at + 2] = v.toByte() }
        private fun put48(b: ByteArray, at: Int, v: Long) { for (i in 0 until 6) b[at + i] = (v ushr (40 - 8 * i)).toByte() }

        /** CRC-16/CCITT (MAME's), as CHD checks each hunk and the map with. */
        fun crc16(data: ByteArray, length: Int): Int {
            var crc = 0xFFFF
            for (i in 0 until length) {
                crc = ((crc shl 8) xor CRC16[((crc ushr 8) xor (data[i].toInt() and 0xFF)) and 0xFF]) and 0xFFFF
            }
            return crc
        }

        private val CRC16 = IntArray(256) { n ->
            var c = n shl 8
            repeat(8) { c = if (c and 0x8000 != 0) (c shl 1) xor 0x1021 else c shl 1 }
            c and 0xFFFF
        }
    }
}
