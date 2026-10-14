package io.github.matiyaaa.fuse.integrations.retroachievements

import io.github.matiyaaa.fuse.integrations.ByteArraySource
import io.github.matiyaaa.fuse.integrations.ByteSource
import io.github.matiyaaa.fuse.integrations.disc.DiscFiles
import kotlinx.coroutines.test.runTest
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.Adler32
import java.util.zip.Deflater
import java.util.zip.ZipInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Disc hashing against rcheevos itself: every expected hash below came from rcheevos' own `rhash`
 * tool run on the same files (the cue sheets, .gdi and plain images in `ra/discs.zip`). The CHDs
 * were made from those files with MAME's `chdman`, so each must hash exactly as its source does.
 */
class RaFileHasherTest {
    private val files: Map<String, ByteArray> by lazy {
        val out = HashMap<String, ByteArray>()
        ZipInputStream(javaClass.getResourceAsStream("/ra/discs.zip")!!).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                out[e.name] = zip.readBytes()
            }
        }
        out
    }

    private val expected: List<Triple<Int, String, String>> by lazy {
        files.getValue("expected.txt").decodeToString().lines().filter { it.isNotBlank() }.map {
            val (console, name, md5) = it.trim().split(' ')
            Triple(console.toInt(), name, md5)
        }
    }

    private fun discFiles(extra: Map<String, ByteArray> = emptyMap()) = object : DiscFiles {
        override suspend fun open(path: String): ByteSource? = (extra[name(path)] ?: files[name(path)])?.let(::ByteArraySource)
        override suspend fun readText(path: String): String? = (extra[name(path)] ?: files[name(path)])?.decodeToString()
        private fun name(path: String) = path.substringAfterLast('/')
    }

    private suspend fun hash(console: Int, name: String, extra: Map<String, ByteArray> = emptyMap()): RaHashResult =
        RaFileHasher.hash(console, "/games/$name", discFiles(extra))

    private fun expectedFor(name: String) = expected.first { it.second == name }

    @Test
    fun `every system hashes as rcheevos does`() = runTest {
        for ((console, name, md5) in expected) {
            val result = hash(console, name)
            assertIs<RaHashResult.Hashed>(result, "$name: $result")
            assertEquals(md5, result.md5, name)
        }
    }

    @Test
    fun `a CHD hashes as the image it was made from, whatever its codec`() = runTest {
        val sources = mapOf(
            "psx.chd" to "psx.cue", "psx-zlib.chd" to "psx.cue", "psx-zstd.chd" to "psx.cue",
            "ps2.chd" to "ps2.iso", "ps2-zstd.chd" to "ps2.iso", "ps2-huff.chd" to "ps2.iso",
            "psp.chd" to "psp.iso", "segacd.chd" to "segacd.cue", "saturn.chd" to "saturn.cue",
            "pce.chd" to "pce.cue", "ngcd.chd" to "ngcd.cue", "dc.chd" to "dc.gdi", "3do.chd" to "3do.cue",
        )
        for ((chd, source) in sources) {
            val (console, _, md5) = expectedFor(source)
            val result = hash(console, chd)
            assertIs<RaHashResult.Hashed>(result, "$chd: $result")
            assertEquals(md5, result.md5, chd)
        }
    }

    @Test
    fun `CSO and ZSO hash as the plain image`() = runTest {
        for (name in listOf("ps2.iso", "psp.iso")) {
            val (console, _, md5) = expectedFor(name)
            val iso = files.getValue(name)
            val cso = name.replace(".iso", ".cso")
            val zso = name.replace(".iso", ".zso")
            assertEquals(md5, hash(console, cso, mapOf(cso to cso(iso))).md5OrNull, cso)
            assertEquals(md5, hash(console, zso, mapOf(zso to zso(iso))).md5OrNull, zso)
        }
    }

    @Test
    fun `a GameCube GCZ hashes as the plain image`() = runTest {
        val (console, _, md5) = expectedFor("gc.iso")
        assertEquals(md5, hash(console, "gc.gcz", mapOf("gc.gcz" to gcz(files.getValue("gc.iso")))).md5OrNull)
    }

    @Test
    fun `a playlist hashes as its first disc`() = runTest {
        val (console, _, md5) = expectedFor("psx.cue")
        assertEquals(md5, hash(console, "game.m3u", mapOf("game.m3u" to "# discs\npsx.cue\npsx.chd\n".encodeToByteArray())).md5OrNull)
    }

    @Test
    fun `what can't be read says why instead of guessing`() = runTest {
        val wii = hash(RaConsoleIds.WII, "game.rvz", mapOf("game.rvz" to ByteArray(64)))
        assertEquals(RaHashUnsupportedReason.COMPRESSED, (wii as RaHashResult.Unsupported).reason)
        val junk = hash(RaConsoleIds.SATURN, "junk.iso", mapOf("junk.iso" to ByteArray(4096)))
        assertIs<RaHashResult.Unsupported>(junk)
        val encrypted = files.getValue("n3ds.3ds").copyOf()
        // Clear NoCrypto in the NCCH's flags: without keys this dump can't be read.
        val ncch = (ByteBuffer.wrap(encrypted, 0x120, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() * 0x200).toInt()
        encrypted[ncch + 0x188 + 7] = (encrypted[ncch + 0x188 + 7].toInt() and 0x04.inv()).toByte()
        val locked = hash(RaConsoleIds.N3DS, "locked.3ds", mapOf("locked.3ds" to encrypted))
        assertEquals(RaHashUnsupportedReason.NINTENDO_3DS, (locked as RaHashResult.Unsupported).reason)
    }

    // --- writers for the compressed formats, as PPSSPP's and Dolphin's tools lay them out ---

    private fun cso(data: ByteArray, block: Int = 2048): ByteArray = blocked("CISO", data, block) { chunk ->
        val d = Deflater(9, true)
        d.setInput(chunk); d.finish()
        val out = ByteArray(chunk.size * 2 + 64)
        val n = d.deflate(out)
        d.end()
        out.copyOf(n)
    }

    /** ZSO with literal-only LZ4 blocks: the plainest valid LZ4 there is. */
    private fun zso(data: ByteArray, block: Int = 2048): ByteArray = blocked("ZISO", data, block) { chunk ->
        val out = ByteArrayOutputStream()
        out.write(0xF0)
        var rest = chunk.size - 15
        while (rest >= 255) { out.write(255); rest -= 255 }
        out.write(rest)
        out.write(chunk)
        out.toByteArray()
    }

    private fun blocked(magic: String, data: ByteArray, block: Int, pack: (ByteArray) -> ByteArray): ByteArray {
        val blocks = (data.size + block - 1) / block
        val header = ByteBuffer.allocate(24 + (blocks + 1) * 4).order(ByteOrder.LITTLE_ENDIAN)
        header.put(magic.encodeToByteArray()).putInt(24).putLong(data.size.toLong()).putInt(block).put(1).put(0).putShort(0)
        val body = ByteArrayOutputStream()
        var at = header.capacity()
        for (i in 0 until blocks) {
            val chunk = data.copyOfRange(i * block, minOf(data.size, (i + 1) * block))
            val packed = pack(chunk)
            header.putInt(at)
            body.write(packed)
            at += packed.size
        }
        header.putInt(at)
        return header.array() + body.toByteArray()
    }

    private fun gcz(data: ByteArray, block: Int = 16384): ByteArray {
        val blocks = (data.size + block - 1) / block
        val packed = (0 until blocks).map { i ->
            val d = Deflater(9)
            d.setInput(data.copyOfRange(i * block, minOf(data.size, (i + 1) * block))); d.finish()
            val out = ByteArray(block * 2 + 64)
            val n = d.deflate(out)
            d.end()
            out.copyOf(n)
        }
        val head = ByteBuffer.allocate(32 + blocks * 12).order(ByteOrder.LITTLE_ENDIAN)
        head.putInt(0xB10BC001.toInt()).putInt(0).putLong(packed.sumOf { it.size }.toLong()).putLong(data.size.toLong()).putInt(block).putInt(blocks)
        var at = 0L
        packed.forEach { head.putLong(at); at += it.size }
        packed.forEach { head.putInt(Adler32().apply { update(it) }.value.toInt()) }
        return head.array() + packed.reduce { a, b -> a + b }
    }
}
