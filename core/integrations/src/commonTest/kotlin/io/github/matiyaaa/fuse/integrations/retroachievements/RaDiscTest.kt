package io.github.matiyaaa.fuse.integrations.retroachievements

import io.github.matiyaaa.fuse.integrations.ByteArraySource
import io.github.matiyaaa.fuse.integrations.Md5
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** RetroAchievements' Nintendo DS and PlayStation family hashes, on images built here. */
class RaDiscTest {

    private fun bytes(size: Int, seed: Int): ByteArray = ByteArray(size) { ((it * 37 + seed) and 0xFF).toByte() }

    private fun ByteArray.putU32(at: Int, value: Long) {
        for (i in 0 until 4) this[at + i] = ((value shr (8 * i)) and 0xFF).toByte()
    }

    // ------------------------------------------------------------------------------ Nintendo DS

    private fun nds(): ByteArray {
        val rom = bytes(0x8000, 3)
        rom.putU32(0x20, 0x4000)
        rom.putU32(0x2C, 0x1000)
        rom.putU32(0x30, 0x5800)
        rom.putU32(0x3C, 0x800)
        rom.putU32(0x68, 0x7000)
        return rom
    }

    private fun ndsExpected(rom: ByteArray, offset: Int = 0): String {
        val md5 = Md5()
        md5.update(rom, offset, 0x160)
        md5.update(rom, offset + 0x4000, 0x1000)
        md5.update(rom, offset + 0x5800, 0x800)
        md5.update(rom, offset + 0x7000, 0xA00)
        return md5.digestHex()
    }

    @Test
    fun nintendoDsHashesHeaderCodeAndIcon() = runTest {
        val rom = nds()
        val result = RaHasher.hash(RaConsoleIds.NDS, "game.nds", ByteArraySource(rom))
        assertEquals(RaHashResult.Hashed(ndsExpected(rom), RaHashMethod.NINTENDO_DS), result)
        // DSi games hash the same way.
        assertEquals(ndsExpected(rom), RaHasher.hash(RaConsoleIds.DSI, "game.dsi", ByteArraySource(rom)).md5OrNull)
    }

    @Test
    fun aSuperCardHeaderIsSkipped() = runTest {
        val rom = nds()
        val superCard = ByteArray(512).also {
            it[0] = 0x2E; it[3] = 0xEA.toByte()
            it[0xB0] = 0x44; it[0xB1] = 0x46; it[0xB2] = 0x96.toByte()
        } + rom
        assertEquals(ndsExpected(rom), RaHasher.hash(RaConsoleIds.NDS, "game.nds", ByteArraySource(superCard)).md5OrNull)
    }

    @Test
    fun aShortIconBlockIsPaddedWithZeros() = runTest {
        val rom = nds().copyOf(0x7000 + 0x100)
        val md5 = Md5()
        md5.update(rom, 0, 0x160)
        md5.update(rom, 0x4000, 0x1000)
        md5.update(rom, 0x5800, 0x800)
        md5.update(rom.copyOfRange(0x7000, 0x7100) + ByteArray(0xA00 - 0x100))
        assertEquals(md5.digestHex(), RaHasher.hash(RaConsoleIds.NDS, "game.nds", ByteArraySource(rom)).md5OrNull)
    }

    @Test
    fun hugeCodeSizesAreNotADsCartridge() = runTest {
        val rom = nds().also { it.putU32(0x2C, 20L * 1024 * 1024) }
        assertEquals(RaHashResult.Unsupported(RaHashUnsupportedReason.NINTENDO_DS), RaHasher.hash(RaConsoleIds.NDS, "game.nds", ByteArraySource(rom)))
    }

    // ------------------------------------------------------------------------------- ISO 9660

    /** A file or directory on the test disc. */
    private sealed interface Node {
        val name: String
    }

    private class FileNode(override val name: String, val data: ByteArray) : Node
    private class DirNode(override val name: String, val children: List<Node>) : Node

    /** Builds a 2048-byte-sector ISO 9660 image: volume descriptor at 16, directories and files after it. */
    private fun iso(root: List<Node>): ByteArray {
        val sectors = HashMap<Int, ByteArray>()
        var next = 20
        fun sectorsFor(size: Int) = maxOf(1, (size + 2047) / 2048)
        // Lays out a directory: its own sector first, then its children; returns (sector, size).
        fun layout(children: List<Node>): Pair<Int, Int> {
            val own = next++
            val records = ArrayList<ByteArray>()
            for (child in children) {
                val (at, size, isDir) = when (child) {
                    is FileNode -> {
                        val at = next
                        next += sectorsFor(child.data.size)
                        child.data.toList().chunked(2048).forEachIndexed { i, chunk -> sectors[at + i] = chunk.toByteArray().copyOf(2048) }
                        Triple(at, child.data.size, false)
                    }
                    is DirNode -> layout(child.children).let { Triple(it.first, it.second, true) }
                }
                val id = (if (isDir) child.name else "${child.name};1").encodeToByteArray()
                val length = 33 + id.size + (if (id.size % 2 == 0) 1 else 0)
                val record = ByteArray(length)
                record[0] = length.toByte()
                record.putU32(2, at.toLong())
                record.putU32(10, size.toLong())
                record[25] = if (isDir) 2 else 0
                record[32] = id.size.toByte()
                id.copyInto(record, 33)
                records += record
            }
            val dir = ByteArray(2048)
            var at = 0
            for (r in records) {
                r.copyInto(dir, at)
                at += r.size
            }
            sectors[own] = dir
            return own to 2048
        }
        val (rootSector, rootSize) = layout(root)
        val pvd = ByteArray(2048)
        pvd[0] = 1
        "CD001".encodeToByteArray().copyInto(pvd, 1)
        pvd[128] = 0x00; pvd[129] = 0x08 // 2048-byte blocks
        pvd[156] = 34
        pvd.putU32(158, rootSector.toLong())
        pvd.putU32(166, rootSize.toLong())
        sectors[16] = pvd
        val image = ByteArray(next * 2048)
        for ((n, data) in sectors) data.copyInto(image, n * 2048)
        return image
    }

    /** The same image as raw mode 2 sectors (2352 bytes: sync, header, subheader, data, error codes). */
    private fun raw(iso: ByteArray): ByteArray {
        val count = iso.size / 2048
        val out = ByteArray(count * 2352)
        for (n in 0 until count) {
            val base = n * 2352
            out[base] = 0
            for (i in 1..10) out[base + i] = 0xFF.toByte()
            out[base + 11] = 0
            out[base + 15] = 2
            iso.copyInto(out, base + 24, n * 2048, n * 2048 + 2048)
        }
        return out
    }

    private fun psxExe(codeSize: Int): ByteArray {
        val exe = bytes(2048 + codeSize + 512, 11)
        "PS-X EXE".encodeToByteArray().copyInto(exe, 0)
        exe.putU32(28, codeSize.toLong())
        return exe
    }

    @Test
    fun playStationHashesTheBootExecutableAndItsName() = runTest {
        val exe = psxExe(4096)
        val cnf = "BOOT = cdrom:\\SLUS_012.34;1\r\nTCB = 4\r\n".encodeToByteArray()
        val image = iso(listOf(FileNode("SYSTEM.CNF", cnf), FileNode("SLUS_012.34", exe)))
        val expected = Md5().update("SLUS_012.34".encodeToByteArray()).update(exe, 0, 2048 + 4096).digestHex()
        assertEquals(expected, RaHasher.hash(RaConsoleIds.PLAYSTATION, "game.iso", ByteArraySource(image)).md5OrNull)
        // A raw .bin of the same disc hashes the same.
        assertEquals(expected, RaHasher.hash(RaConsoleIds.PLAYSTATION, "game.bin", ByteArraySource(raw(image))).md5OrNull)
    }

    @Test
    fun playStationWithoutSystemCnfBootsPsxExe() = runTest {
        val exe = psxExe(2048)
        val image = iso(listOf(FileNode("PSX.EXE", exe)))
        val expected = Md5().update("PSX.EXE".encodeToByteArray()).update(exe, 0, 4096).digestHex()
        assertEquals(expected, RaHasher.hash(RaConsoleIds.PLAYSTATION, "game.iso", ByteArraySource(image)).md5OrNull)
    }

    @Test
    fun playStation2HashesBoot2() = runTest {
        val elf = bytes(5000, 5).also { byteArrayOf(0x7F, 0x45, 0x4C, 0x46).copyInto(it) }
        val cnf = "BOOT2 = cdrom0:\\SLUS_200.62;1\nVER = 1.00\n".encodeToByteArray()
        val image = iso(listOf(FileNode("SYSTEM.CNF", cnf), FileNode("SLUS_200.62", elf)))
        val expected = Md5().update("SLUS_200.62".encodeToByteArray()).update(elf).digestHex()
        assertEquals(expected, RaHasher.hash(RaConsoleIds.PS2, "game.iso", ByteArraySource(image)).md5OrNull)
        // A PlayStation 1 disc is not a PlayStation 2 one.
        assertIs<RaHashResult.Unsupported>(RaHasher.hash(RaConsoleIds.PS2, "game.iso", ByteArraySource(iso(listOf(FileNode("PSX.EXE", psxExe(2048)))))))
    }

    @Test
    fun pspHashesParamSfoThenEboot() = runTest {
        val sfo = bytes(700, 2)
        val eboot = bytes(9000, 4)
        val image = iso(
            listOf(
                DirNode("PSP_GAME", listOf(FileNode("PARAM.SFO", sfo), DirNode("SYSDIR", listOf(FileNode("EBOOT.BIN", eboot))))),
                FileNode("UMD_DATA.BIN", bytes(32, 1)),
            ),
        )
        val expected = Md5().update(sfo).update(eboot).digestHex()
        assertEquals(expected, RaHasher.hash(RaConsoleIds.PSP, "game.iso", ByteArraySource(image)).md5OrNull)
        assertEquals(RaHashUnsupportedReason.COMPRESSED, (RaHasher.hash(RaConsoleIds.PSP, "game.cso", ByteArraySource(image)) as RaHashResult.Unsupported).reason)
    }

    // --------------------------------------------------------------------------- disc files

    @Test
    fun playlistsAndCueSheetsLeadToTheDataTrack() = runTest {
        val files = mapOf(
            "/roms/psx/Game.m3u" to "#EXTM3U\nGame (Disc 1).cue\nGame (Disc 2).cue\n",
            "/roms/psx/Game (Disc 1).cue" to "FILE \"Game (Disc 1) (Track 1).bin\" BINARY\n  TRACK 01 MODE2/2352\n    INDEX 01 00:00:00\nFILE \"Game (Disc 1) (Track 2).bin\" BINARY\n",
            "/roms/psx/Plain.cue" to "FILE Plain.bin BINARY\n  TRACK 01 MODE2/2352\n",
        )
        val read: suspend (String) -> String? = { files[it] }
        assertEquals("/roms/psx/Game (Disc 1) (Track 1).bin", RaDiscFiles.dataFile("/roms/psx/Game.m3u", read))
        assertEquals("/roms/psx/Plain.bin", RaDiscFiles.dataFile("/roms/psx/Plain.cue", read))
        assertEquals("/roms/psx/Game.iso", RaDiscFiles.dataFile("/roms/psx/Game.iso", read))
    }
}
