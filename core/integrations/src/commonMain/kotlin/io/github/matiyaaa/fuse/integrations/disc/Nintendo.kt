package io.github.matiyaaa.fuse.integrations.disc

import io.github.matiyaaa.fuse.integrations.ByteSource
import io.github.matiyaaa.fuse.integrations.readFully

/**
 * GameCube and Wii disc images Dolphin keeps compressed, read as the plain image: GCZ (zlib blocks),
 * and WIA and RVZ (groups of the disc compressed with Zstandard, LZMA or LZMA2, and in RVZ the
 * disc's junk padding stored as the seed that makes it). Wii partitions inside WIA and RVZ are kept
 * decrypted there and aren't read: Wii games in those formats are matched by name.
 */
internal object NintendoImages {
    suspend fun open(source: ByteSource): ByteSource? {
        val magic = ByteArray(4)
        if (source.readFully(0, magic, 0, 4) < 4) return null
        return when {
            magic.contentEquals(byteArrayOf(0x01, 0xC0.toByte(), 0x0B, 0xB1.toByte())) -> GczSource.open(source)
            magic.contentEquals("WIA\u0001".encodeToByteArray()) -> RvzSource.open(source, rvz = false)
            magic.contentEquals("RVZ\u0001".encodeToByteArray()) -> RvzSource.open(source, rvz = true)
            else -> source
        }
    }
}

private fun le32(b: ByteArray, at: Int): Long = (b[at].toLong() and 0xFF) or ((b[at + 1].toLong() and 0xFF) shl 8) or ((b[at + 2].toLong() and 0xFF) shl 16) or ((b[at + 3].toLong() and 0xFF) shl 24)
private fun le64(b: ByteArray, at: Int): Long = le32(b, at) or (le32(b, at + 4) shl 32)
private fun be32(b: ByteArray, at: Int): Long = ((b[at].toLong() and 0xFF) shl 24) or ((b[at + 1].toLong() and 0xFF) shl 16) or ((b[at + 2].toLong() and 0xFF) shl 8) or (b[at + 3].toLong() and 0xFF)
private fun be64(b: ByteArray, at: Int): Long = (be32(b, at) shl 32) or be32(b, at + 4)

/** A block cache over a [ByteSource] made of fixed-size blocks. */
private abstract class BlockSource(override val size: Long, private val blockSize: Int) : ByteSource {
    private var cached = -1L
    private val block = ByteArray(blockSize)

    abstract suspend fun load(index: Long, into: ByteArray): Boolean

    override suspend fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        if (position >= size) return -1
        var done = 0
        while (done < length && position + done < size) {
            val at = position + done
            val index = at / blockSize
            if (index != cached) {
                if (!load(index, block)) { cached = -1; break }
                cached = index
            }
            val inBlock = (at % blockSize).toInt()
            val take = minOf(length - done, blockSize - inBlock, (size - at).toInt())
            block.copyInto(buffer, offset + done, inBlock, inBlock + take)
            done += take
        }
        return if (done == 0) -1 else done
    }
}

/** Dolphin's GCZ: a table of block offsets (the top bit marks a block stored as it is), each block zlib-compressed. */
private class GczSource(private val source: ByteSource, size: Long, private val blockSize: Int, private val pointers: LongArray, private val dataStart: Long, private val compressedSize: Long) : BlockSource(size, blockSize) {
    override suspend fun load(index: Long, into: ByteArray): Boolean {
        val i = index.toInt()
        if (i >= pointers.size) return false
        val raw = pointers[i]
        val plain = raw < 0
        val start = raw and Long.MAX_VALUE
        val end = if (i + 1 < pointers.size) pointers[i + 1] and Long.MAX_VALUE else compressedSize
        val length = (end - start).toInt()
        if (length <= 0) return false
        val data = ByteArray(length)
        if (source.readFully(dataStart + start, data, 0, length) != length) return false
        if (plain) { data.copyInto(into, 0, 0, minOf(length, blockSize)); return true }
        val want = minOf(blockSize.toLong(), size - index * blockSize).toInt()
        return Decompress.zlib(data, 0, length, into, 0, want)
    }

    companion object {
        suspend fun open(source: ByteSource): GczSource? {
            val head = ByteArray(32)
            if (source.readFully(0, head, 0, 32) < 32) return null
            val compressed = le64(head, 8)
            val dataSize = le64(head, 16)
            val blockSize = le32(head, 24).toInt()
            val blocks = le32(head, 28).toInt()
            if (blockSize <= 0 || blocks <= 0 || blocks > 16_000_000) return null
            val table = ByteArray(blocks * 8)
            if (source.readFully(32, table, 0, table.size) != table.size) return null
            val pointers = LongArray(blocks) { le64(table, it * 8) }
            val dataStart = 32L + blocks * 8L + blocks * 4L
            return GczSource(source, dataSize, blockSize, pointers, dataStart, compressed)
        }
    }
}

/** Dolphin's WIA and RVZ, for the disc's raw (unpartitioned) data: everything a GameCube disc is. */
private class RvzSource(
    private val source: ByteSource,
    override val size: Long,
    private val rvz: Boolean,
    private val compression: Int,
    private val compressorData: ByteArray,
    private val chunkSize: Long,
    private val discHeader: ByteArray,
    private val raw: List<LongArray>,
    private val groups: List<LongArray>,
) : ByteSource {
    private var cachedOffset = -1L
    private var cachedChunk = ByteArray(0)

    override suspend fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        if (position >= size) return -1
        var at = position
        var out = offset
        var left = minOf(length.toLong(), size - position)
        if (at < 0x80) {
            val take = minOf(0x80 - at, left).toInt()
            discHeader.copyInto(buffer, out, at.toInt(), at.toInt() + take)
            at += take; out += take; left -= take
        }
        while (left > 0) {
            // The raw data entry holding [at]: data offset, size, first group, groups.
            val entry = raw.firstOrNull { at >= it[0] - it[0] % SECTOR && at < it[0] + it[1] } ?: break
            val skipped = entry[0] % SECTOR
            val dataOffset = entry[0] - skipped
            val dataSize = entry[1] + skipped
            val i = (at - dataOffset) / chunkSize
            if (i >= entry[3]) break
            val group = groups.getOrNull((entry[2] + i).toInt()) ?: break
            val groupOffsetInData = i * chunkSize
            val thisChunk = minOf(chunkSize, dataSize - groupOffsetInData)
            val inGroup = at - groupOffsetInData - dataOffset
            val take = minOf(thisChunk - inGroup, left).toInt()
            val chunk = chunk(group, thisChunk.toInt(), groupOffsetInData) ?: break
            chunk.copyInto(buffer, out, inGroup.toInt(), inGroup.toInt() + take)
            at += take; out += take; left -= take
        }
        val done = out - offset
        return if (done == 0) -1 else done
    }

    private suspend fun chunk(group: LongArray, length: Int, dataOffset: Long): ByteArray? {
        val fileOffset = group[0] shl 2
        if (fileOffset == cachedOffset && cachedChunk.size == length) return cachedChunk
        var dataSize = group[1]
        var method = compression
        if (rvz) {
            if (dataSize and 0x80000000L == 0L) method = NONE
            dataSize = dataSize and 0x7FFFFFFFL
        }
        val packed = if (rvz) group[2].toInt() else 0
        val out = ByteArray(length)
        if (dataSize == 0L) return out.also { cachedOffset = fileOffset; cachedChunk = it }
        val data = ByteArray(dataSize.toInt())
        if (source.readFully(fileOffset, data, 0, data.size) != data.size) return null
        val unpackedSize = if (packed != 0) packed else length
        val plain = ByteArray(unpackedSize)
        val ok = when (method) {
            NONE -> { data.copyInto(plain, 0, 0, minOf(data.size, unpackedSize)); data.size >= unpackedSize }
            ZSTD -> Decompress.zstd(data, 0, data.size, plain, 0, unpackedSize)
            LZMA -> Decompress.lzma(data, 0, data.size, compressorData[0].toInt() and 0xFF, le32(compressorData, 1).toInt(), plain, 0, unpackedSize)
            LZMA2 -> lzma2(data, plain)
            else -> false
        }
        if (!ok) return null
        if (packed != 0) {
            if (!unpack(plain, out, dataOffset)) return null
        } else {
            plain.copyInto(out, 0, 0, length)
        }
        cachedOffset = fileOffset
        cachedChunk = out
        return out
    }

    private fun lzma2(data: ByteArray, out: ByteArray): Boolean {
        val prop = compressorData.getOrNull(0)?.toInt()?.and(0xFF) ?: return false
        val dict = if (prop >= 40) Int.MAX_VALUE else ((2 or (prop and 1)) shl (prop / 2 + 11))
        return Decompress.lzma2(data, 0, data.size, dict, out, 0, out.size)
    }

    /** RVZ's packing: runs of real data and of junk made from a seed (Dolphin's RVZPackDecompressor). */
    private fun unpack(packed: ByteArray, out: ByteArray, startOffset: Long): Boolean {
        var p = 0
        var o = 0
        var dataOffset = startOffset
        val lfg = LaggedFibonacci()
        while (o < out.size) {
            if (p + 4 > packed.size) return false
            val word = be32(packed, p)
            p += 4
            val junk = word and 0x80000000L != 0L
            val size = (word and 0x7FFFFFFFL).toInt()
            val take = minOf(size, out.size - o)
            if (junk) {
                if (p + 68 > packed.size) return false
                lfg.seed(packed, p)
                p += 68
                lfg.forward((dataOffset % SECTOR).toInt())
                lfg.bytes(out, o, take)
            } else {
                if (p + take > packed.size) return false
                packed.copyInto(out, o, p, p + take)
                p += take
            }
            o += take
            dataOffset += take
        }
        return true
    }

    companion object {
        private const val SECTOR = 0x8000L
        private const val NONE = 0
        private const val LZMA = 3
        private const val LZMA2 = 4
        private const val ZSTD = 5

        suspend fun open(source: ByteSource, rvz: Boolean): RvzSource? {
            val h1 = ByteArray(0x48)
            if (source.readFully(0, h1, 0, h1.size) != h1.size) return null
            val header2Size = be32(h1, 12).toInt()
            val isoSize = be64(h1, 0x24)
            if (header2Size < 0xDC || header2Size > 0x1000) return null
            val h2 = ByteArray(header2Size)
            if (source.readFully(0x48, h2, 0, h2.size) != h2.size) return null
            val discType = be32(h2, 0).toInt()
            val compression = be32(h2, 4).toInt()
            val chunkSize = be32(h2, 12)
            val discHeader = h2.copyOfRange(16, 16 + 0x80)
            val rawCount = be32(h2, 0x9C).toInt()
            val rawOffset = be64(h2, 0xA0)
            val rawSize = be32(h2, 0xA8).toInt()
            val groupCount = be32(h2, 0xAC).toInt()
            val groupOffset = be64(h2, 0xB0)
            val groupSize = be32(h2, 0xB8).toInt()
            val compressorSize = h2[0xBC].toInt() and 0xFF
            val compressorData = h2.copyOfRange(0xBD, 0xBD + minOf(compressorSize, 7))
            // Only a GameCube disc (type 1) is all raw data.
            if (discType != 1 || chunkSize <= 0) return null
            val reader = RvzSource(source, isoSize, rvz, compression, compressorData, chunkSize, discHeader, emptyList(), emptyList())
            val rawTable = reader.table(rawOffset, rawSize, rawCount * 0x18) ?: return null
            val groupEntry = if (rvz) 12 else 8
            val groupTable = reader.table(groupOffset, groupSize, groupCount * groupEntry) ?: return null
            val raws = (0 until rawCount).map { i -> longArrayOf(be64(rawTable, i * 0x18), be64(rawTable, i * 0x18 + 8), be32(rawTable, i * 0x18 + 16), be32(rawTable, i * 0x18 + 20)) }
            val groups = (0 until groupCount).map { i -> LongArray(3) { k -> if (k < 2 || rvz) be32(groupTable, i * groupEntry + k * 4) else 0 } }
            return RvzSource(source, isoSize, rvz, compression, compressorData, chunkSize, discHeader, raws, groups)
        }
    }

    /** One of the header's tables, compressed as the groups are. */
    private suspend fun table(offset: Long, stored: Int, length: Int): ByteArray? {
        val data = ByteArray(stored)
        if (source.readFully(offset, data, 0, stored) != stored) return null
        val out = ByteArray(length)
        val ok = when (compression) {
            NONE -> { data.copyInto(out, 0, 0, minOf(stored, length)); stored >= length }
            ZSTD -> Decompress.zstd(data, 0, stored, out, 0, length)
            LZMA -> Decompress.lzma(data, 0, stored, compressorData[0].toInt() and 0xFF, le32(compressorData, 1).toInt(), out, 0, length)
            LZMA2 -> lzma2(data, out)
            else -> false
        }
        return if (ok) out else null
    }
}

/** Dolphin's lagged Fibonacci generator, which makes the junk that pads a Nintendo disc (LaggedFibonacciGenerator.cpp). */
internal class LaggedFibonacci {
    private val buffer = IntArray(K)
    private var position = 0

    fun seed(data: ByteArray, at: Int) {
        position = 0
        for (i in 0 until SEED) buffer[i] = be32(data, at + i * 4).toInt()
        for (i in SEED until K) buffer[i] = (buffer[i - 17] shl 23) xor (buffer[i - 16] ushr 9) xor buffer[i - 1]
        for (i in 0 until K) {
            val x = buffer[i]
            buffer[i] = Integer.reverseBytes((x and 0xFF00FFFF.toInt()) or ((x ushr 2) and 0x00FF0000))
        }
        repeat(4) { step() }
    }

    private fun step() {
        for (i in 0 until J) buffer[i] = buffer[i] xor buffer[i + K - J]
        for (i in J until K) buffer[i] = buffer[i] xor buffer[i - J]
    }

    fun forward(count: Int) {
        position += count
        while (position >= K * 4) {
            step()
            position -= K * 4
        }
    }

    /** The bytes of the words as they are kept (big-endian words, byte-swapped in [seed]). */
    private fun byteAt(index: Int): Byte {
        val word = buffer[index / 4]
        // Kept as the C code keeps them in memory on a little-endian machine.
        return (word ushr (8 * (index % 4))).toByte()
    }

    fun bytes(out: ByteArray, offset: Int, count: Int) {
        for (i in 0 until count) {
            out[offset + i] = byteAt(position)
            position++
            if (position == K * 4) {
                step()
                position = 0
            }
        }
    }

    private object Integer {
        fun reverseBytes(x: Int): Int = ((x and 0xFF) shl 24) or ((x and 0xFF00) shl 8) or ((x ushr 8) and 0xFF00) or ((x ushr 24) and 0xFF)
    }

    companion object {
        private const val K = 521
        private const val J = 32
        private const val SEED = 17
    }
}
