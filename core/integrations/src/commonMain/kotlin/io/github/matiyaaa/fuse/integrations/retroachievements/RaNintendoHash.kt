package io.github.matiyaaa.fuse.integrations.retroachievements

import io.github.matiyaaa.fuse.integrations.ByteSource
import io.github.matiyaaa.fuse.integrations.Md5
import io.github.matiyaaa.fuse.integrations.readFully

/** GameCube and Wii hashes, ported from rcheevos (rc_hash_gamecube, rc_hash_wii) over a disc's bytes. */
internal object RaNintendoHash {
    private const val MAX_BUFFER = 64L * 1024 * 1024

    private suspend fun ByteSource.bytes(at: Long, length: Int): ByteArray = ByteArray(length).also { readFully(at, it, 0, length) }

    private fun be32(b: ByteArray, at: Int = 0): Long = ((b[at].toLong() and 0xFF) shl 24) or ((b[at + 1].toLong() and 0xFF) shl 16) or ((b[at + 2].toLong() and 0xFF) shl 8) or (b[at + 3].toLong() and 0xFF)

    /** A partition's header, apploader and boot DOL's segments (rc_hash_nintendo_disc_partition). */
    private suspend fun partition(md5: Md5, disc: ByteSource, partOffset: Long, shift: Int) {
        val baseHeader = 0x2440L
        val sizes = disc.bytes(partOffset + baseHeader + 0x14, 8)
        val body = be32(sizes, 0)
        val trailer = be32(sizes, 4)
        val headerSize = minOf(baseHeader + 0x20 + body + trailer, 1024L * 1024).toInt()
        val header = disc.bytes(partOffset, headerSize)
        md5.update(header)
        val dol = be32(header, 0x420) shl shift
        val table = disc.bytes(partOffset + dol, 0xD8)
        for (i in 0 until 18) {
            val offset = be32(table, i * 4) shl shift
            val size = be32(table, 0x90 + i * 4) shl shift
            if (size == 0L) continue
            var at = partOffset + offset
            var left = size
            val chunk = 1024 * 1024
            while (left > 0) {
                val take = minOf(left, chunk.toLong()).toInt()
                md5.update(disc.bytes(at, take))
                at += take
                left -= take
            }
        }
    }

    suspend fun gamecube(disc: ByteSource): RaDisc.Result {
        val magic = disc.bytes(0x1C, 4)
        if (be32(magic) != 0xC2339F3DL) return RaDisc.Result.Failed(RaHashUnsupportedReason.NOT_A_DISC)
        val md5 = Md5()
        partition(md5, disc, 0, 0)
        return RaDisc.Result.Hash(md5.digestHex())
    }

    suspend fun wii(disc: ByteSource): RaDisc.Result {
        val md5 = Md5()
        if (be32(disc.bytes(0x18, 4)) == 0x5D1C9EA3L) {
            val encrypted = disc.bytes(0x61, 1)[0] == 0.toByte()
            md5.update(disc.bytes(0, 0x80))
            md5.update(disc.bytes(0x4E000, 4))
            val info = disc.bytes(0x40000, 32)
            var total = 0L
            for (i in 0 until 8 step 2) total += be32(info, i * 4)
            if (total == 0L) return RaDisc.Result.Failed(RaHashUnsupportedReason.NOT_A_DISC)
            val partitions = ArrayList<Pair<Long, Long>>()
            for (j in 0 until 8 step 2) {
                val count = be32(info, j * 4).toInt()
                val table = disc.bytes(be32(info, (j + 1) * 4) shl 2, count * 8)
                for (i in 0 until count) partitions += be32(table, i * 8) to be32(table, i * 8 + 4)
            }
            for ((offset, type) in partitions) {
                if (type == 1L) continue // the update partition
                val base = offset shl 2
                val tmd = disc.bytes(base + 0x2A4, 8)
                val tmdSize = minOf(be32(tmd, 0), 0x7C00L).toInt()
                md5.update(disc.bytes(base + (be32(tmd, 4) shl 2), tmdSize))
                val part = disc.bytes(base + 0x2B8, 8)
                val partOffset = be32(part, 0) shl 2
                val partSize = be32(part, 4) shl 2
                if (encrypted) {
                    val clusters = minOf(partSize / 0x8000, 1024L).toInt()
                    for (i in 0 until clusters) md5.update(disc.bytes(partOffset + i * 0x8000L + 0x400, 0x7C00))
                } else {
                    partition(md5, disc, partOffset, 2)
                }
            }
            return RaDisc.Result.Hash(md5.digestHex())
        }
        val wad = disc.bytes(4, 4)
        if (wad[0] == 'I'.code.toByte() && wad[1] == 's'.code.toByte() && wad[2] == 0.toByte() && wad[3] == 0.toByte()) {
            fun align(v: Long, a: Long) = (v + a - 1) and (a - 1).inv()
            val head = disc.bytes(0, 0x20)
            val cert = align(be32(head, 8), 0x40)
            val ticket = align(be32(head, 0x10), 0x40)
            val tmdSize = minOf(align(be32(head, 0x14), 0x40), MAX_BUFFER)
            val tmdStart = 0x40 + cert + ticket
            md5.update(disc.bytes(tmdStart, tmdSize.toInt()))
            val countBytes = disc.bytes(tmdStart + 0x1DE, 2)
            val count = ((countBytes[0].toInt() and 0xFF) shl 8) or (countBytes[1].toInt() and 0xFF)
            var contentAt = tmdStart + tmdSize
            for (i in 0 until count) {
                val sizes = disc.bytes(tmdStart + 0x1E4 + 8 + i * 0x24L, 8)
                val size = if (be32(sizes, 0) == 0L) align(be32(sizes, 4), 0x10) else MAX_BUFFER
                md5.update(disc.bytes(contentAt, minOf(size, MAX_BUFFER).toInt()))
                contentAt = align(contentAt + size, 0x40)
            }
            return RaDisc.Result.Hash(md5.digestHex())
        }
        return RaDisc.Result.Failed(RaHashUnsupportedReason.NOT_A_DISC)
    }
}
