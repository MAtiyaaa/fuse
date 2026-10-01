package io.github.matiyaaa.fuse.integrations.retroachievements

import io.github.matiyaaa.fuse.integrations.ByteArraySource
import io.github.matiyaaa.fuse.integrations.ByteSource
import io.github.matiyaaa.fuse.integrations.Md5
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class RaHasherTest {

    private fun payload(size: Int, seed: Int = 7): ByteArray = ByteArray(size) { ((it * 31 + seed) and 0xFF).toByte() }

    private suspend fun hashOf(consoleId: Int, bytes: ByteArray, name: String = "game.bin"): String? =
        RaHasher.hash(consoleId, name, ByteArraySource(bytes)).md5OrNull

    @Test
    fun md5KnownVectors() {
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", Md5.hex(""))
        assertEquals("0cc175b9c0f1b6a831c399e269772661", Md5.hex("a"))
        assertEquals("900150983cd24fb0d6963f7d28e17f72", Md5.hex("abc"))
        assertEquals("f96b697d7cb7938d525a2f31aaf161d0", Md5.hex("message digest"))
        assertEquals("c3fcd3d76192e4007dfb496cca67e13b", Md5.hex("abcdefghijklmnopqrstuvwxyz"))
        assertEquals("9e107d9d372bb6826bd81d3542a419d6", Md5.hex("The quick brown fox jumps over the lazy dog"))
        assertEquals(
            "57edf4a22be3c955ac49da2e2107b67a",
            Md5.hex("12345678901234567890123456789012345678901234567890123456789012345678901234567890"),
        )
    }

    @Test
    fun md5StreamingMatchesOneShot() {
        val data = payload(10_000)
        val streamed = Md5()
        var i = 0
        val steps = intArrayOf(1, 63, 64, 65, 127, 1000, 3)
        var s = 0
        while (i < data.size) {
            val n = minOf(steps[s++ % steps.size], data.size - i)
            streamed.update(data, i, n)
            i += n
        }
        assertEquals(Md5.hex(data), streamed.digestHex())
    }

    @Test
    fun md5MillionA() {
        val md5 = Md5()
        val chunk = ByteArray(1000) { 'a'.code.toByte() }
        repeat(1000) { md5.update(chunk) }
        assertEquals("7707d6ae4e027c70eea2a935c2296f21", md5.digestHex())
    }

    @Test
    fun wholeFileConsoles() = runTest {
        val rom = payload(4096)
        assertEquals(Md5.hex(rom), hashOf(RaConsoleIds.GBA, rom))
        assertEquals(Md5.hex(rom), hashOf(RaConsoleIds.MEGA_DRIVE, rom))
    }

    @Test
    fun nesHeaderIsStripped() = runTest {
        val body = payload(40960)
        val header = byteArrayOf('N'.code.toByte(), 'E'.code.toByte(), 'S'.code.toByte(), 0x1A) + ByteArray(12) { 1 }
        assertEquals(Md5.hex(body), hashOf(RaConsoleIds.NES, header + body))
        // Headerless ROMs hash as-is.
        assertEquals(Md5.hex(body), hashOf(RaConsoleIds.NES, body))
    }

    @Test
    fun fdsHeaderIsStripped() = runTest {
        val body = payload(65500)
        val header = byteArrayOf('F'.code.toByte(), 'D'.code.toByte(), 'S'.code.toByte(), 0x1A) + ByteArray(12)
        assertEquals(Md5.hex(body), hashOf(RaConsoleIds.FDS, header + body))
    }

    @Test
    fun snesCopierHeaderIsStrippedOnlyWhenPresent() = runTest {
        val body = payload(8192 * 4)
        val withHeader = ByteArray(512) + body
        assertEquals(Md5.hex(body), hashOf(RaConsoleIds.SNES, withHeader))
        assertEquals(Md5.hex(body), hashOf(RaConsoleIds.SNES, body))
    }

    @Test
    fun pcEngineHeaderIsStripped() = runTest {
        val body = payload(131072)
        assertEquals(Md5.hex(body), hashOf(RaConsoleIds.PC_ENGINE, ByteArray(512) { 9 } + body))
    }

    @Test
    fun atari7800AndLynxHeaders() = runTest {
        val body = payload(16384)
        val a78 = ByteArray(128).also { h ->
            h[0] = 1
            "ATARI7800".encodeToByteArray().copyInto(h, 1)
        }
        assertEquals(Md5.hex(body), hashOf(RaConsoleIds.ATARI_7800, a78 + body))
        val lnx = ByteArray(64).also { h -> "LYNX".encodeToByteArray().copyInto(h, 0) }
        assertEquals(Md5.hex(body), hashOf(RaConsoleIds.LYNX, lnx + body))
    }

    @Test
    fun n64ByteOrdersHashTheSame() = runTest {
        val z64 = payload(4096 + 8).also {
            it[0] = 0x80.toByte(); it[1] = 0x37; it[2] = 0x12; it[3] = 0x40
        }
        val v64 = ByteArray(z64.size) { i -> z64[i xor 1] }
        val n64 = ByteArray(z64.size) { i -> z64[(i and 3.inv()) + (3 - (i and 3))] }
        assertEquals(0x37, v64[0].toInt())
        assertEquals(0x40, n64[0].toInt())
        val expected = Md5.hex(z64)
        assertEquals(expected, hashOf(RaConsoleIds.N64, z64, "game.z64"))
        assertEquals(expected, hashOf(RaConsoleIds.N64, v64, "game.v64"))
        assertEquals(expected, hashOf(RaConsoleIds.N64, n64, "game.n64"))
    }

    @Test
    fun n64RejectsUnknownFormat() = runTest {
        val result = RaHasher.hash(RaConsoleIds.N64, "x.z64", ByteArraySource(payload(64).also { it[0] = 0x12 }))
        assertEquals(RaHashResult.Unsupported(RaHashUnsupportedReason.NOT_N64_ROM), result)
    }

    @Test
    fun arcadeHashesFileNameWithoutExtension() = runTest {
        assertEquals(Md5.hex("sf2"), RaHasher.hashArcade("/roms/arcade/sf2.zip"))
        assertEquals(Md5.hex("mslug"), RaHasher.hashArcade("C:\\roms\\mslug.7z"))
        val result = RaHasher.hash(RaConsoleIds.ARCADE, "/roms/arcade/sf2.zip", null)
        assertEquals(RaHashResult.Hashed(Md5.hex("sf2"), RaHashMethod.ARCADE_FILE_NAME), result)
    }

    @Test
    fun unsupportedConsolesSayWhy() = runTest {
        val src = ByteArraySource(payload(1024))
        // A PlayStation image that isn't a disc, and one Fuse can't read without decompressing it.
        assertEquals(RaHashUnsupportedReason.NOT_A_DISC, (RaHasher.hash(RaConsoleIds.PLAYSTATION, "x.bin", src) as RaHashResult.Unsupported).reason)
        assertEquals(RaHashUnsupportedReason.COMPRESSED, (RaHasher.hash(RaConsoleIds.PLAYSTATION, "Game.chd", src) as RaHashResult.Unsupported).reason)
        assertEquals(RaHashUnsupportedReason.DISC_IMAGE, RaHasher.unsupportedReason(RaConsoleIds.SATURN))
        assertNull(RaHasher.unsupportedReason(RaConsoleIds.PS2))
        assertNull(RaHasher.unsupportedReason(RaConsoleIds.NDS))
        assertEquals(RaHashUnsupportedReason.NINTENDO_3DS, RaHasher.unsupportedReason(RaConsoleIds.N3DS))
        assertEquals(RaHashUnsupportedReason.UNKNOWN_CONSOLE, RaHasher.unsupportedReason(9999))
        assertNull(RaHasher.hash(RaConsoleIds.NDS, "x.nds", src).md5OrNull)
        assertIs<RaHashResult.Unsupported>(RaHasher.hash(RaConsoleIds.GBA, "empty.gba", ByteArraySource(ByteArray(0))))
    }

    @Test
    fun shortReadsAreHandled() = runTest {
        val bytes = payload(5000)
        // A source that returns at most 7 bytes per read, like a slow stream.
        val trickle = object : ByteSource {
            override val size: Long = bytes.size.toLong()
            override suspend fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
                if (position >= bytes.size) return -1
                val n = minOf(7, length, bytes.size - position.toInt())
                bytes.copyInto(buffer, offset, position.toInt(), position.toInt() + n)
                return n
            }
        }
        assertEquals(Md5.hex(bytes), RaHasher.hash(RaConsoleIds.GAME_BOY, "x.gb", trickle).md5OrNull)
    }
}
