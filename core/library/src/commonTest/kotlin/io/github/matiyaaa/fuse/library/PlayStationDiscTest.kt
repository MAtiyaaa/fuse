package io.github.matiyaaa.fuse.library

import io.github.matiyaaa.fuse.library.disc.PlayStationDisc
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Files as bytes, read in ranges like a real disc image. */
private class BytesFs(val files: Map<String, ByteArray>) : FuseFileSystem {
    override suspend fun list(path: String): List<FsEntry> = emptyList()
    override suspend fun stat(path: String): FsEntry? = null
    override suspend fun readText(path: String, maxBytes: Int): String? = files[path]?.decodeToString()
    override suspend fun md5(path: String): String? = null
    override suspend fun canonical(path: String): String? = path
    override suspend fun readBytes(path: String, offset: Long, length: Int): ByteArray? {
        val f = files[path] ?: return null
        if (offset >= f.size) return ByteArray(0)
        return f.copyOfRange(offset.toInt(), minOf(f.size, offset.toInt() + length))
    }
}

class PlayStationDiscTest {
    private val elf = ByteArray(10) { (it + 1).toByte() } // two whole words and two bytes left over

    /** A minimal ISO 9660 image: SYSTEM.CNF and the boot program in its root. */
    private fun iso(cnf: String, elfName: String = "SLUS_209.46;1"): ByteArray {
        val sector = 2048
        val image = ByteArray(sector * 24)
        fun le32(at: Int, v: Int) { for (i in 0..3) image[at + i] = (v ushr (8 * i)).toByte() }
        fun record(at: Int, lba: Int, size: Int, dir: Boolean, name: String): Int {
            val n = name.encodeToByteArray()
            val len = 33 + n.size + (if (n.size % 2 == 0) 1 else 0)
            image[at] = len.toByte()
            le32(at + 2, lba)
            le32(at + 10, size)
            image[at + 25] = if (dir) 2 else 0
            image[at + 32] = n.size.toByte()
            n.copyInto(image, at + 33)
            return len
        }
        val pvd = 16 * sector
        image[pvd] = 1
        "CD001".encodeToByteArray().copyInto(image, pvd + 1)
        record(pvd + 156, 18, sector, true, "\u0000")
        var at = 18 * sector
        at += record(at, 18, sector, true, "\u0000")
        at += record(at, 18, sector, true, "\u0001")
        val cnfBytes = cnf.encodeToByteArray()
        at += record(at, 20, cnfBytes.size, false, "SYSTEM.CNF;1")
        record(at, 21, elf.size, false, elfName)
        cnfBytes.copyInto(image, 20 * sector)
        elf.copyInto(image, 21 * sector)
        return image
    }

    /** The same image as raw mode 2 sectors: sync, header and subheader, then the data. */
    private fun rawMode2(iso: ByteArray): ByteArray {
        val sectors = iso.size / 2048
        val out = ByteArray(sectors * 2352)
        for (s in 0 until sectors) {
            val base = s * 2352
            out[base] = 0
            for (i in 1..10) out[base + i] = 0xFF.toByte()
            out[base + 11] = 0
            out[base + 15] = 2
            iso.copyInto(out, base + 24, s * 2048, s * 2048 + 2048)
        }
        return out
    }

    private val ps2Cnf = "BOOT2 = cdrom0:\\SLUS_209.46;1\r\nVER = 1.01\r\nVMODE = NTSC\r\n"

    // XOR of the little-endian words 0x04030201 and 0x08070605.
    private val expectedCrc = 0x04030201L xor 0x08070605L

    @Test
    fun aPs2IsoGivesPcsx2sSerialAndCrc() = runTest {
        val id = PlayStationDisc(BytesFs(mapOf("/g.iso" to iso(ps2Cnf)))).identify("/g.iso")!!
        assertEquals("SLUS-20946", id.serial)
        assertEquals(expectedCrc, id.crc)
        assertEquals("0C040404", id.crcText)
        assertEquals("1.01", id.version)
        assertEquals("NTSC", id.videoMode)
        assertEquals(true, id.ps2)
    }

    @Test
    fun aRawBinReadsTheSame() = runTest {
        val id = PlayStationDisc(BytesFs(mapOf("/g.bin" to rawMode2(iso(ps2Cnf))))).identify("/g.bin")!!
        assertEquals("SLUS-20946", id.serial)
        assertEquals(expectedCrc, id.crc)
    }

    @Test
    fun aPs1DiscHasASerialButNoCrc() = runTest {
        val cnf = "BOOT = cdrom:\\SCUS_944.26;1\nTCB = 4\n"
        val id = PlayStationDisc(BytesFs(mapOf("/p.bin" to rawMode2(iso(cnf, "SCUS_944.26;1"))))).identify("/p.bin")!!
        assertEquals("SCUS-94426", id.serial)
        assertNull(id.crc)
        assertEquals(false, id.ps2)
    }

    @Test
    fun otherFilesAreNotDiscs() = runTest {
        val fs = BytesFs(mapOf("/a.iso" to ByteArray(4096), "/b.chd" to "MComprHD".encodeToByteArray()))
        assertNull(PlayStationDisc(fs).identify("/a.iso"))
        assertNull(PlayStationDisc(fs).identify("/b.chd"))
        assertNull(PlayStationDisc(fs).identify("/missing.iso"))
    }

    @Test
    fun bootPathsBecomeSerialsTheWayPcsx2Does() {
        assertEquals("SLES-50330", PlayStationDisc.serialFromBootPath("cdrom0:\\SLES_503.30;1"))
        assertEquals("SCES-12345", PlayStationDisc.serialFromBootPath("cdrom:SCES_123.45;1"))
        assertEquals("SLPM-65009", PlayStationDisc.serialFromBootPath("cdrom0:\\DATA\\slpm_650.09;1"))
        assertNull(PlayStationDisc.serialFromBootPath("cdrom0:\\MAIN.ELF;1"))
        assertEquals("DATA\\SLPM_650.09", PlayStationDisc.bootFileName("cdrom0:\\DATA\\SLPM_650.09;1"))
    }
}
