package io.github.matiyaaa.fuse.launch.ports

import io.github.matiyaaa.fuse.model.PortRom
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CRC32 and SHA-1 against Python's `zlib.crc32` and `hashlib.sha1` over the bytes
 * `(i * 31 + 7) & 0xff`, at the lengths SHA-1's padding treats differently, and a ROM checked the
 * way the NES, SNES and Mega Drive recompilations check theirs.
 */
class PortRomCheckTest {
    private fun bytes(n: Int) = ByteArray(n) { ((it * 31 + 7) and 0xff).toByte() }

    @Test
    fun hashesAreTheReferenceOnes() {
        assertEquals(0xCBF43926L, PortRomCheck.crc32("123456789".encodeToByteArray()))
        assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", PortRomCheck.sha1Hex("abc".encodeToByteArray()))
        val sha1 = mapOf(
            55 to "749bbefb28edc4638b28b2b9a9e03ab9a4032b90", 56 to "a5b6e9c29d201c774753ff8e7fb64931656f5e63",
            63 to "d1a454409359fc372b4d22b3cea6488d6ba1be00", 64 to "39a0d8b645ad85f1f976731ed112ac9455e28b78",
            65 to "d0c96e18890114a14716e9686528d2e3fdba8d9e", 100000 to "35ab00c96f47c268b087d40d0dcd7245809f0d5c",
        )
        for ((n, hex) in sha1) assertEquals(hex, PortRomCheck.sha1Hex(bytes(n)), "length $n")
        assertEquals(0x92858800L, PortRomCheck.crc32(bytes(100000)))
    }

    @Test
    fun aNesRomIsKnownWithOrWithoutItsHeader() {
        // nesrecomp verify_rom: the 16-byte iNES header left out, so any header variant of the ROM matches.
        val rom = PortRom(system = "nes", crc32 = "8f21f416")
        val data = bytes(40960)
        val headed = byteArrayOf(0x4E, 0x45, 0x53, 0x1A) + ByteArray(12) { 1 } + data
        val otherHeader = byteArrayOf(0x4E, 0x45, 0x53, 0x1A) + ByteArray(12) { 9 } + data
        assertTrue(PortRomCheck.matches(rom, headed))
        assertTrue(PortRomCheck.matches(rom, otherHeader))
        assertTrue(PortRomCheck.matches(rom, data))
        assertFalse(PortRomCheck.matches(rom, data.copyOf(40959)), "a different ROM")
        assertTrue(PortRomCheck.matches(rom.copy(crc32 = "8F21F416"), headed), "a hash written in capitals")
    }

    @Test
    fun aSnesRomIsKnownWithOrWithoutACopierHeader() {
        val data = bytes(512 * 1024)
        val copier = ByteArray(512) { 7 } + data
        val byCrc = PortRom(system = "snes", crc32 = "d37043e7")
        val bySha1 = PortRom(system = "snes", sha1 = "f64cad1321b3b25baa16ab48b0783b49dd4b0a22")
        for (rom in listOf(byCrc, bySha1)) {
            assertTrue(PortRomCheck.matches(rom, data))
            assertTrue(PortRomCheck.matches(rom, copier))
        }
        // Only a NES file's header is a NES header: a Mega Drive ROM is checked whole.
        assertFalse(PortRomCheck.matches(byCrc.copy(system = "genesis"), copier))
        assertTrue(PortRomCheck.matches(byCrc.copy(system = "genesis"), data))
    }

    @Test
    fun aRomNoHashNamesIsAnyFileOfTheGame() {
        assertTrue(PortRomCheck.matches(PortRom(system = "nes"), bytes(16)))
        assertFalse(PortRomCheck.matches(PortRom(system = "nes"), ByteArray(0)))
        assertNull(PortRomCheck.content("nes", byteArrayOf(0x4E, 0x45, 0x53, 0x1A) + ByteArray(12)), "a header with nothing after it")
    }
}
