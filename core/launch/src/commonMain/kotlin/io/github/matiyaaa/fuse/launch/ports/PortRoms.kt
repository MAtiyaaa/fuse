package io.github.matiyaaa.fuse.launch.ports

import io.github.matiyaaa.fuse.model.PortRom

/**
 * Whether a file is the ROM a recompilation is made from, checked the way the port itself checks
 * it, so a port is only ever handed a ROM it will take:
 *
 * - **N64** ([PortRom.xxh3]): the XXH3-64 of the ROM in big-endian order, as N64: Recompiled's
 *   runtime does ([PortPlan.bigEndianRom]).
 * - **CRC32 or SHA-1** ([PortRom.crc32], [PortRom.sha1]) of the ROM's own data: a NES file's 16-byte
 *   iNES header left out (nesrecomp `runner/src/launcher.c`, `verify_rom`, which skips it "so
 *   different header variants of the same ROM produce the same CRC32"), and a SNES file's 512-byte
 *   copier header when it has one (snesrecomp's launcher, as SuperMarioWorldRecomp `src/main.c`
 *   says: "skips a 512-byte SMC copier header automatically before hashing").
 * - **No hash**: the port checks nothing itself (Megaman3NESRecomp `extras.c`,
 *   `game_get_expected_crc32` is 0), and any copy of the game on its system will do.
 */
object PortRomCheck {
    fun matches(rom: PortRom, bytes: ByteArray): Boolean {
        if (rom.xxh3.isNotEmpty()) return PortPlan.isRom(rom, bytes)
        if (rom.crc32 == null && rom.sha1 == null) return bytes.isNotEmpty()
        val data = content(rom.system, bytes) ?: return false
        rom.crc32?.let { if (hex8(crc32(data)) == it.lowercase()) return true }
        rom.sha1?.let { if (sha1Hex(data) == it.lowercase()) return true }
        return false
    }

    /** [bytes] without the header its system's files can carry; null when it is too short to be a ROM. */
    fun content(system: String, bytes: ByteArray): ByteArray? = when (system) {
        "nes", "famicom" -> if (bytes.size >= INES && bytes[0] == 'N'.code.toByte() && bytes[1] == 'E'.code.toByte() &&
            bytes[2] == 'S'.code.toByte() && bytes[3] == 0x1A.toByte()
        ) bytes.copyOfRange(INES, bytes.size) else bytes
        "snes", "sfam" -> if (bytes.size % 1024 == COPIER && bytes.size > COPIER) bytes.copyOfRange(COPIER, bytes.size) else bytes
        else -> bytes
    }.takeIf { it.isNotEmpty() }

    fun crc32(data: ByteArray): Long {
        var c = 0xFFFFFFFFL
        for (b in data) {
            c = c xor (b.toLong() and 0xFF)
            repeat(8) { c = if (c and 1L != 0L) (c ushr 1) xor 0xEDB88320L else c ushr 1 }
        }
        return c xor 0xFFFFFFFFL
    }

    fun sha1Hex(data: ByteArray): String {
        var h0 = 0x67452301
        var h1 = 0xEFCDAB89.toInt()
        var h2 = 0x98BADCFE.toInt()
        var h3 = 0x10325476
        var h4 = 0xC3D2E1F0.toInt()
        val bitLength = data.size.toLong() * 8
        val padded = ByteArray(((data.size + 9 + 63) / 64) * 64)
        data.copyInto(padded)
        padded[data.size] = 0x80.toByte()
        for (i in 0 until 8) padded[padded.size - 1 - i] = (bitLength ushr (8 * i)).toByte()
        val w = IntArray(80)
        for (chunk in padded.indices step 64) {
            for (i in 0 until 16) {
                val at = chunk + i * 4
                w[i] = ((padded[at].toInt() and 0xFF) shl 24) or ((padded[at + 1].toInt() and 0xFF) shl 16) or
                    ((padded[at + 2].toInt() and 0xFF) shl 8) or (padded[at + 3].toInt() and 0xFF)
            }
            for (i in 16 until 80) w[i] = (w[i - 3] xor w[i - 8] xor w[i - 14] xor w[i - 16]).rotateLeft(1)
            var a = h0
            var b = h1
            var c = h2
            var d = h3
            var e = h4
            for (i in 0 until 80) {
                val (f, k) = when (i / 20) {
                    0 -> ((b and c) or (b.inv() and d)) to 0x5A827999
                    1 -> (b xor c xor d) to 0x6ED9EBA1
                    2 -> ((b and c) or (b and d) or (c and d)) to 0x8F1BBCDC.toInt()
                    else -> (b xor c xor d) to 0xCA62C1D6.toInt()
                }
                val t = a.rotateLeft(5) + f + e + k + w[i]
                e = d
                d = c
                c = b.rotateLeft(30)
                b = a
                a = t
            }
            h0 += a
            h1 += b
            h2 += c
            h3 += d
            h4 += e
        }
        return listOf(h0, h1, h2, h3, h4).joinToString("") { it.toUInt().toString(16).padStart(8, '0') }
    }

    private fun hex8(v: Long): String = v.toString(16).padStart(8, '0')

    private const val INES = 16
    private const val COPIER = 512
}
