package io.github.matiyaaa.fuse.integrations.retroachievements

import io.github.matiyaaa.fuse.integrations.ByteSource
import io.github.matiyaaa.fuse.integrations.Md5
import io.github.matiyaaa.fuse.integrations.readFully

/**
 * RetroAchievements' disc hashes for the PlayStation family, following rcheevos' rc_hash (MIT
 * licensed): the data track is read as ISO 9660, the boot executable is found the way the console
 * finds it, and the executable (with its name) is hashed. Raw .bin tracks (2352 or 2336 bytes per
 * sector, mode 1 or mode 2) and .iso images (2048) are read; compressed images are not.
 */
object RaDisc {

    sealed interface Result {
        data class Hash(val md5: String) : Result
        data class Failed(val reason: RaHashUnsupportedReason) : Result
    }

    /** PlayStation: BOOT in SYSTEM.CNF (or PSX.EXE), its name then the executable, sized by its PS-X EXE header. */
    suspend fun playStation(source: ByteSource): Result {
        val track = CdTrack.open(source) ?: return Result.Failed(RaHashUnsupportedReason.NOT_A_DISC)
        var found = bootExecutable(track, "BOOT", "cdrom:")
        if (found == null) track.findFile("PSX.EXE")?.let { found = Found("PSX.EXE", it.first, it.second) }
        val exe = found ?: return Result.Failed(RaHashUnsupportedReason.NO_EXECUTABLE)
        val head = ByteArray(32)
        if (track.read(exe.sector, head, head.size) < head.size) return Result.Failed(RaHashUnsupportedReason.NO_EXECUTABLE)
        // The PS-X EXE header gives the code size 28 bytes in, without the 2048-byte header itself.
        val size = if (head.copyOfRange(0, 7).contentEquals("PS-X EXE".encodeToByteArray().copyOf(7))) {
            ((head[28].toLong() and 0xFF) or ((head[29].toLong() and 0xFF) shl 8) or ((head[30].toLong() and 0xFF) shl 16) or ((head[31].toLong() and 0xFF) shl 24)) + 2048
        } else {
            exe.size
        }
        val md5 = Md5()
        md5.update(exe.name.encodeToByteArray())
        if (!hashFile(md5, track, exe.sector, size)) return Result.Failed(RaHashUnsupportedReason.NO_EXECUTABLE)
        return Result.Hash(md5.digestHex())
    }

    /** PlayStation 2: BOOT2 in SYSTEM.CNF, its name then the executable. */
    suspend fun playStation2(source: ByteSource): Result {
        val track = CdTrack.open(source) ?: return Result.Failed(RaHashUnsupportedReason.NOT_A_DISC)
        val exe = bootExecutable(track, "BOOT2", "cdrom0:") ?: return Result.Failed(RaHashUnsupportedReason.NO_EXECUTABLE)
        val md5 = Md5()
        md5.update(exe.name.encodeToByteArray())
        if (!hashFile(md5, track, exe.sector, exe.size)) return Result.Failed(RaHashUnsupportedReason.NO_EXECUTABLE)
        return Result.Hash(md5.digestHex())
    }

    /** PSP: PSP_GAME/PARAM.SFO, then PSP_GAME/SYSDIR/EBOOT.BIN. */
    suspend fun psp(source: ByteSource): Result {
        val track = CdTrack.open(source) ?: return Result.Failed(RaHashUnsupportedReason.NOT_A_DISC)
        val sfo = track.findFile("PSP_GAME\\PARAM.SFO") ?: return Result.Failed(RaHashUnsupportedReason.NOT_A_DISC)
        val md5 = Md5()
        if (!hashFile(md5, track, sfo.first, sfo.second)) return Result.Failed(RaHashUnsupportedReason.NOT_A_DISC)
        val eboot = track.findFile("PSP_GAME\\SYSDIR\\EBOOT.BIN") ?: return Result.Failed(RaHashUnsupportedReason.NO_EXECUTABLE)
        if (!hashFile(md5, track, eboot.first, eboot.second)) return Result.Failed(RaHashUnsupportedReason.NO_EXECUTABLE)
        return Result.Hash(md5.digestHex())
    }

    private data class Found(val name: String, val sector: Long, val size: Long)

    /**
     * The executable SYSTEM.CNF boots: the line starting with [key], then "=", the [prefix] and any
     * backslashes, up to a space or ";".
     */
    private suspend fun bootExecutable(track: CdTrack, key: String, prefix: String): Found? {
        val cnf = track.findFile("SYSTEM.CNF") ?: return null
        val buffer = ByteArray(SECTOR)
        val read = track.read(cnf.first, buffer, SECTOR - 1)
        if (read <= 0) return null
        val text = buffer.copyOf(read).decodeToString().substringBefore('\u0000')
        for (line in text.split('\n')) {
            if (!line.startsWith(key)) continue
            var rest = line.substring(key.length).trimStart()
            if (!rest.startsWith("=")) continue
            rest = rest.substring(1).trimStart()
            if (rest.startsWith(prefix)) rest = rest.substring(prefix.length)
            rest = rest.trimStart('\\')
            val name = rest.takeWhile { !it.isWhitespace() && it != ';' }.take(MAX_NAME)
            val file = track.findFile(name) ?: return null
            return Found(name, file.first, file.second)
        }
        return null
    }

    /** Adds [size] bytes of the file starting at [sector] (at most 64 MiB), sector by sector. */
    private suspend fun hashFile(md5: Md5, track: CdTrack, sector: Long, size: Long): Boolean {
        val buffer = ByteArray(SECTOR)
        var left = size.coerceAtMost(RaHasher.MAX_BUFFER_BYTES)
        var at = sector
        var read = track.read(at, buffer, SECTOR)
        if (read < SECTOR) return false
        while (true) {
            val take = minOf(read.toLong(), left).toInt()
            md5.update(buffer, 0, take)
            left -= take
            if (left <= 0) break
            at++
            read = track.read(at, buffer, minOf(SECTOR.toLong(), left).toInt())
            if (read <= 0) break
        }
        return true
    }

    internal const val SECTOR = 2048
    private const val MAX_NAME = 63
}

/**
 * A disc's first data track, read 2048 bytes of user data per sector. The layout is found the way
 * rcheevos' reader finds it: a sync pattern at sector 16 means raw sectors (2352 or 2336 bytes, with
 * a 24-byte mode 2 or 16-byte mode 1 header), else "CD001" there means a plain 2048-byte image.
 */
internal class CdTrack private constructor(private val source: ByteSource, private val sectorSize: Int, private val headerSize: Int) {

    /** Reads up to [length] bytes of [sector]'s data into [buffer]. Returns the bytes read. */
    suspend fun read(sector: Long, buffer: ByteArray, length: Int): Int {
        val position = sector * sectorSize + headerSize
        if (sector < 0 || position >= source.size) return 0
        return source.readFully(position, buffer, 0, minOf(length, RaDisc.SECTOR, buffer.size))
    }

    /**
     * The first sector and size of the file at [path] ("DIR\\FILE.EXT", any case), from the root
     * directory the primary volume descriptor names. Like rcheevos, a directory is read from its
     * first sector; only the root can span several.
     */
    suspend fun findFile(path: String): Pair<Long, Long>? {
        var name = path.trimStart('\\')
        val buffer = ByteArray(RaDisc.SECTOR)
        var sector: Long
        var sectors: Long
        val slash = name.lastIndexOf('\\')
        if (slash >= 0) {
            sector = findFile(name.substring(0, slash))?.first ?: return null
            sectors = 0
            name = name.substring(slash + 1)
        } else {
            if (read(16, buffer, 256) < 256) return null
            sector = (buffer[158].u()) or (buffer[159].u() shl 8) or (buffer[160].u() shl 16)
            val blockSize = buffer[128].u() or (buffer[129].u() shl 8)
            sectors = if (blockSize == 0L) 1 else ((buffer[166].u()) or (buffer[167].u() shl 8) or (buffer[168].u() shl 16) or (buffer[169].u() shl 24)) / blockSize
        }
        if (read(sector, buffer, RaDisc.SECTOR) <= 0) return null
        val wanted = name.encodeToByteArray()
        var at = 0
        while (true) {
            if (at >= buffer.size || buffer[at] == 0.toByte()) {
                // The end of this sector's records; the root keeps going on the next sector.
                if (sectors > 1) {
                    sectors--
                    sector++
                    if (read(sector, buffer, RaDisc.SECTOR) > 0) {
                        at = 0
                        continue
                    }
                }
                return null
            }
            val length = buffer[at].u().toInt()
            val nameLength = if (at + 32 < buffer.size) buffer[at + 32].u().toInt() else 0
            val nameStart = at + 33
            // "FILENAME;version" or a directory's plain name.
            val fits = nameStart + wanted.size <= buffer.size
            val sized = nameLength == wanted.size || (nameStart + wanted.size < buffer.size && buffer[nameStart + wanted.size] == ';'.code.toByte())
            if (fits && sized && sameIgnoringCase(buffer, nameStart, wanted)) {
                val first = buffer[at + 2].u() or (buffer[at + 3].u() shl 8) or (buffer[at + 4].u() shl 16)
                val size = buffer[at + 10].u() or (buffer[at + 11].u() shl 8) or (buffer[at + 12].u() shl 16) or (buffer[at + 13].u() shl 24)
                return first to size
            }
            if (length <= 0) return null
            at += length
        }
    }

    private fun sameIgnoringCase(buffer: ByteArray, start: Int, wanted: ByteArray): Boolean =
        wanted.indices.all { i -> buffer[start + i].toInt().toChar().uppercaseChar() == wanted[i].toInt().toChar().uppercaseChar() }

    private fun Byte.u(): Long = toLong() and 0xFF

    companion object {
        private val SYNC = byteArrayOf(0, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 0)
        private val CD001 = "CD001".encodeToByteArray()

        suspend fun open(source: ByteSource): CdTrack? {
            val header = ByteArray(32)
            for (raw in listOf(2352, 2336)) {
                if (source.readFully(16L * raw, header) < header.size) continue
                if (header.copyOfRange(0, 12).contentEquals(SYNC)) {
                    val mode2 = header.copyOfRange(25, 30).contentEquals(CD001)
                    return CdTrack(source, raw, if (mode2) 24 else 16)
                }
            }
            if (source.readFully(16L * 2048, header) >= 6 && header.copyOfRange(1, 6).contentEquals(CD001)) return CdTrack(source, 2048, 0)
            return null
        }
    }
}

/** Which file of a disc game holds what RetroAchievements hashes. */
object RaDiscFiles {
    /**
     * The first disc of an .m3u playlist, and the first track file a .cue sheet names (the data
     * track on PlayStation discs); any other path is returned as it is. [readText] reads a small
     * text file, or null.
     */
    suspend fun dataFile(path: String, readText: suspend (String) -> String?): String {
        var current = path
        if (current.endsWith(".m3u", ignoreCase = true) || current.endsWith(".m3u8", ignoreCase = true)) {
            val first = readText(current)?.lineSequence()?.map { it.trim() }?.firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
                ?: return current
            current = resolve(current, first)
        }
        if (current.endsWith(".cue", ignoreCase = true)) {
            val text = readText(current) ?: return current
            val file = text.lineSequence().map { it.trim() }.firstOrNull { it.startsWith("FILE", ignoreCase = true) } ?: return current
            val rest = file.substring(4).trim()
            val name = if (rest.startsWith("\"")) rest.substring(1).substringBefore('"') else rest.substringBeforeLast(' ').ifEmpty { rest }
            if (name.isNotEmpty()) current = resolve(current, name)
        }
        return current
    }

    private fun resolve(base: String, name: String): String {
        if (name.startsWith("/") || (name.length > 2 && name[1] == ':')) return name
        val dir = base.substringBeforeLast('/', missingDelimiterValue = "").ifEmpty { base.substringBeforeLast('\\', missingDelimiterValue = "") }
        return if (dir.isEmpty()) name else "$dir/${name.replace('\\', '/')}"
    }
}
