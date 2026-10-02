package io.github.matiyaaa.fuse.library.disc

import io.github.matiyaaa.fuse.library.FuseFileSystem
import kotlinx.serialization.Serializable

/**
 * What a PlayStation or PlayStation 2 disc image says about itself: the serial its boot program is
 * named after (`SLUS-20946`), and for PS2 discs the CRC PCSX2 files patches and settings under.
 */
@Serializable
data class DiscIdentity(
    val serial: String,
    /** PCSX2's CRC of the boot program (XOR of its 32-bit words); null for PS1 discs or when the program couldn't be read. */
    val crc: Long? = null,
    /** True for a PS2 disc (BOOT2), false for a PS1 disc (BOOT). */
    val ps2: Boolean,
    /** The game's own version (SYSTEM.CNF VER), when it says one. */
    val version: String? = null,
    /** NTSC or PAL (SYSTEM.CNF VMODE), when it says one. */
    val videoMode: String? = null,
) {
    /** The CRC as PCSX2 writes it in file names: eight upper-case hex digits. */
    val crcText: String? get() = crc?.toString(16)?.uppercase()?.padStart(8, '0')
}

/**
 * Reads [DiscIdentity] from uncompressed disc images: ISO files (2048-byte sectors) and raw BIN
 * images (2352-byte sectors, mode 1 or mode 2). Compressed images (CHD, CSO, GZ) aren't read: their
 * games are identified by name instead. The rules follow PCSX2 (pcsx2/CDVD/CDVD.cpp,
 * ExecutablePathToSerial and GetPS2ElfName; pcsx2/Elfheader.cpp, ElfObject::GetCRC), so the serial
 * and CRC are the ones PCSX2 shows for the same disc.
 */
class PlayStationDisc(private val fs: FuseFileSystem) {
    /** The identity of the image at [path], or null when it isn't a readable PlayStation disc. */
    suspend fun identify(path: String): DiscIdentity? {
        val image = open(path) ?: return null
        val root = image.rootDirectory() ?: return null
        val cnf = image.find(root, "SYSTEM.CNF") ?: return null
        if (cnf.size > MAX_CNF) return null
        val text = image.readFile(cnf)?.decodeToString() ?: return null
        val config = parseSystemCnf(text) ?: return null
        val serial = serialFromBootPath(config.boot) ?: return null
        val crc = if (config.ps2) bootProgramCrc(image, root, config.boot) else null
        return DiscIdentity(serial, crc, config.ps2, config.version, config.videoMode)
    }

    private suspend fun bootProgramCrc(image: Image, root: Entry, boot: String): Long? {
        val name = bootFileName(boot) ?: return null
        var dir = root
        val parts = name.split('\\', '/').filter { it.isNotEmpty() }
        for (part in parts.dropLast(1)) dir = image.find(dir, part)?.takeIf { it.directory } ?: return null
        val elf = image.find(dir, parts.last()) ?: return null
        if (elf.size > MAX_ELF) return null
        var crc = 0L
        var carry = ByteArray(0)
        var done = 0L
        while (done < elf.size) {
            val chunk = minOf(CHUNK_SECTORS.toLong() * SECTOR, elf.size - done).toInt()
            val bytes = image.readRange(elf.sector + done / SECTOR, chunk) ?: return null
            val all = carry + bytes
            val whole = all.size / 4 * 4
            var i = 0
            while (i < whole) {
                val word = (all[i].toLong() and 0xFF) or ((all[i + 1].toLong() and 0xFF) shl 8) or
                    ((all[i + 2].toLong() and 0xFF) shl 16) or ((all[i + 3].toLong() and 0xFF) shl 24)
                crc = crc xor word
                i += 4
            }
            carry = all.copyOfRange(whole, all.size)
            done += chunk
        }
        // A last partial word is left out, as PCSX2 does (size / 4 words).
        return crc
    }

    private suspend fun open(path: String): Image? {
        val head = fs.readBytes(path, 0, 16) ?: return null
        // A raw sector starts with the CD sync pattern: 00, ten FF, 00.
        val raw = head.size == 16 && head[0] == 0.toByte() && (1..10).all { head[it] == 0xFF.toByte() } && head[11] == 0.toByte()
        if (!raw) return Image(fs, path, SECTOR, 0)
        val mode = (fs.readBytes(path, 16L * RAW_SECTOR + 15, 1) ?: return null).firstOrNull()?.toInt() ?: return null
        return Image(fs, path, RAW_SECTOR, if (mode == 1) 16 else 24)
    }

    /** A disc image read sector by sector: [stride] bytes per sector, its 2048 bytes of data at [offset]. */
    private class Image(private val fs: FuseFileSystem, private val path: String, private val stride: Int, private val offset: Int) {
        suspend fun sector(lba: Long): ByteArray? = fs.readBytes(path, lba * stride + offset, SECTOR)?.takeIf { it.size == SECTOR }

        /** [length] bytes of data from sector [lba] on, across sectors. */
        suspend fun readRange(lba: Long, length: Int): ByteArray? {
            if (stride == SECTOR) return fs.readBytes(path, lba * SECTOR, length)?.takeIf { it.size == length }
            val out = ByteArray(length)
            var at = 0
            var s = lba
            while (at < length) {
                val data = sector(s++) ?: return null
                val n = minOf(SECTOR, length - at)
                data.copyInto(out, at, 0, n)
                at += n
            }
            return out
        }

        suspend fun readFile(e: Entry): ByteArray? = readRange(e.sector, e.size.toInt())

        suspend fun rootDirectory(): Entry? {
            val pvd = sector(16) ?: return null
            if (pvd[0] != 1.toByte() || pvd.decodeToString(1, 6) != "CD001") return null
            return record(pvd, 156)
        }

        /** The entry called [name] in directory [dir], ignoring case and the ";1" version. */
        suspend fun find(dir: Entry, name: String): Entry? {
            val want = name.substringBefore(';').uppercase()
            val sectors = (dir.size + SECTOR - 1) / SECTOR
            for (i in 0 until minOf(sectors, MAX_DIR_SECTORS.toLong())) {
                val data = sector(dir.sector + i) ?: return null
                var pos = 0
                while (pos < SECTOR) {
                    val len = data[pos].toInt() and 0xFF
                    // Records never cross a sector; a zero length means the rest of it is padding.
                    if (len == 0) break
                    val e = record(data, pos)
                    if (e != null && e.name.substringBefore(';').uppercase() == want) return e
                    pos += len
                }
            }
            return null
        }

        private fun record(data: ByteArray, at: Int): Entry? {
            if (at + 33 > data.size) return null
            val nameLength = data[at + 32].toInt() and 0xFF
            if (at + 33 + nameLength > data.size) return null
            return Entry(
                sector = le32(data, at + 2),
                size = le32(data, at + 10),
                directory = (data[at + 25].toInt() and 0x02) != 0,
                name = data.decodeToString(at + 33, at + 33 + nameLength),
            )
        }
    }

    private class Entry(val sector: Long, val size: Long, val directory: Boolean, val name: String)

    internal class SystemCnf(val boot: String, val ps2: Boolean, val version: String?, val videoMode: String?)

    companion object {
        private const val SECTOR = 2048
        private const val RAW_SECTOR = 2352
        private const val CHUNK_SECTORS = 64
        private const val MAX_CNF = 64 * 1024L
        private const val MAX_ELF = 64L * 1024 * 1024
        private const val MAX_DIR_SECTORS = 256

        private fun le32(b: ByteArray, at: Int): Long =
            (b[at].toLong() and 0xFF) or ((b[at + 1].toLong() and 0xFF) shl 8) or
                ((b[at + 2].toLong() and 0xFF) shl 16) or ((b[at + 3].toLong() and 0xFF) shl 24)

        /** BOOT2 (PS2) or BOOT (PS1) with VER and VMODE, from SYSTEM.CNF's `KEY = value` lines. */
        internal fun parseSystemCnf(text: String): SystemCnf? {
            var boot: String? = null
            var ps2 = false
            var version: String? = null
            var mode: String? = null
            for (raw in text.split('\n')) {
                val line = raw.trim()
                val eq = line.indexOf('=')
                if (eq <= 0) continue
                val key = line.substring(0, eq).trim()
                val value = line.substring(eq + 1).trim()
                if (value.isEmpty()) continue
                when (key) {
                    "BOOT2" -> { boot = value; ps2 = true }
                    "BOOT" -> { boot = value; ps2 = false }
                    "VER" -> version = value
                    "VMODE" -> mode = value
                }
            }
            return boot?.let { SystemCnf(it, ps2, version, mode) }
        }

        /** `cdrom0:\SLUS_209.46;1` to `SLUS-20946`, as PCSX2's ExecutablePathToSerial does; null when it isn't shaped like one. */
        internal fun serialFromBootPath(path: String): String? {
            var serial = when {
                '\\' in path -> path.substringAfterLast('\\')
                ':' in path -> path.substringAfterLast(':')
                else -> path
            }
            serial = serial.substringBeforeLast(';')
            // ????_???.??* or ????-???.??*
            if (serial.length < 11 || (serial[4] != '_' && serial[4] != '-') || serial[8] != '.') return null
            return serial.filter { it != '.' }.map { if (it == '_') '-' else it.uppercaseChar() }.joinToString("")
        }

        /** The boot program's path inside the disc: `cdrom0:\SLUS_209.46;1` to `SLUS_209.46`. */
        internal fun bootFileName(boot: String): String? {
            if (!boot.startsWith("cdrom:") && !boot.startsWith("cdrom0:")) return null
            var start = if (boot.getOrNull(5) == '0') 7 else 6
            while (start < boot.length && (boot[start] == '\\' || boot[start] == '/')) start++
            return boot.substring(start).substringBefore(';').takeIf { it.isNotEmpty() }
        }
    }
}
