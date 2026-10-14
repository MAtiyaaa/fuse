package io.github.matiyaaa.fuse.integrations.disc

import io.github.matiyaaa.fuse.integrations.ByteSource
import io.github.matiyaaa.fuse.integrations.readFully

/** Files a disc image is made of, read where the game is (its cue sheet and tracks, a .gdi and its tracks). */
interface DiscFiles {
    suspend fun open(path: String): ByteSource?

    /** A small text file (a cue sheet, a .gdi, an .m3u), or null. */
    suspend fun readText(path: String): String?
}

/** Which track to open, as rcheevos asks for one. */
sealed interface TrackWanted {
    data class Number(val number: Int) : TrackWanted
    data object FirstData : TrackWanted
    data object Last : TrackWanted
    data object Largest : TrackWanted
    data object FirstOfSecondSession : TrackWanted
}

/** One track of a disc, read the way rcheevos' CD reader reads it: user data, sector by sector. */
interface DiscTrack {
    /** The track's first sector (after its pregap), in the disc's own addressing. */
    val firstSector: Long

    /**
     * Reads up to [length] bytes from [sector] on into [buffer]: 2048 bytes of each data sector (or
     * all 2352 of an audio one), going on into the next sectors when more is asked for. The count read.
     */
    suspend fun read(sector: Long, buffer: ByteArray, length: Int): Int
}

/** A disc image: its tracks, opened by [open]. */
interface DiscImage {
    suspend fun open(which: TrackWanted): DiscTrack?

    companion object {
        /**
         * The image at [path]: a cue sheet, a .gdi, a CHD, or one track file (.iso, .bin, .img).
         * Null when it can't be read as a disc.
         */
        suspend fun open(path: String, files: DiscFiles): DiscImage? {
            val ext = path.substringAfterLast('.', "").lowercase()
            return when (ext) {
                "cue" -> files.readText(path)?.let { CueImage(path, it, files) }
                "gdi" -> files.readText(path)?.let { GdiImage(path, it, files) }
                "chd" -> files.open(path)?.let { ChdImage.open(it) }
                "cso", "zso" -> files.open(path)?.let { CsoSource.open(it) }?.let { SingleTrackImage(it) }
                else -> files.open(path)?.let { SingleTrackImage(it) }
            }
        }

        internal fun sibling(base: String, name: String): String {
            val clean = name.replace('\\', '/')
            if (clean.startsWith("/") || (clean.length > 2 && clean[1] == ':')) return clean
            val slash = maxOf(base.lastIndexOf('/'), base.lastIndexOf('\\'))
            return if (slash < 0) clean else base.substring(0, slash + 1) + clean
        }
    }
}

/**
 * A track in a file of raw or cooked sectors, as rcheevos' reader finds its layout: the sync pattern
 * at sector 16 (raw 2352 or 2336-byte sectors, with a 16-byte mode 1 or 24-byte mode 2 header; the
 * header's minute, second and frame giving where the track starts), else "CD001" there (a plain
 * 2048-byte image), else the file's size.
 */
internal class FileTrack(
    private val source: ByteSource,
    private val fileOffset: Long,
    private var sectorSize: Int,
    private var headerSize: Int,
    private var rawDataSize: Int,
    private var trackFirstSector: Long,
    private val pregap: Long,
) : DiscTrack {
    override val firstSector: Long get() = trackFirstSector + pregap

    /** Puts the track at [sector] on the disc (a .gdi says where each track is). */
    fun startAt(sector: Long) {
        trackFirstSector = sector
    }

    override suspend fun read(sector: Long, buffer: ByteArray, length: Int): Int {
        if (sector < trackFirstSector) return 0
        var position = (sector - trackFirstSector) * sectorSize + headerSize + fileOffset
        var at = 0
        var left = minOf(length, buffer.size)
        while (left > rawDataSize) {
            val n = source.readFully(position, buffer, at, rawDataSize)
            if (n <= 0) return at
            at += n
            if (n < rawDataSize) return at
            position += sectorSize
            left -= rawDataSize
        }
        val n = source.readFully(position, buffer, at, left)
        return at + n.coerceAtLeast(0)
    }

    companion object {
        private val SYNC = byteArrayOf(0, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 0)
        private val CD001 = "CD001".encodeToByteArray()

        private fun msf(header: ByteArray): Long {
            fun bcd(b: Byte) = ((b.toInt() ushr 4) and 0xF) * 10 + (b.toInt() and 0xF)
            return ((bcd(header[12]) * 60L + bcd(header[13])) * 75 + bcd(header[14])) - 150
        }

        /**
         * Opens a track at [fileOffset] in [source]. [mode] is the cue sheet's ("MODE1/2352",
         * "AUDIO"...), used when the sectors don't say; null for a lone file, whose size decides.
         */
        suspend fun open(source: ByteSource, fileOffset: Long, mode: String?, trackFirstSector: Long, pregap: Long): FileTrack? {
            val header = ByteArray(32)
            val toc = 16 + pregap
            var first = trackFirstSector
            fun found(size: Int, headerSize: Int, raw: Int = 2048) = FileTrack(source, fileOffset, size, headerSize, raw, first, pregap)
            for (raw in intArrayOf(2352, 2336)) {
                if (source.readFully(toc * raw + fileOffset, header, 0, 32) < 32) continue
                if (header.copyOfRange(0, 12).contentEquals(SYNC)) {
                    val mode2 = header.copyOfRange(25, 30).contentEquals(CD001)
                    first = msf(header) - toc
                    return found(raw, if (mode2) 24 else 16)
                }
                // rcheevos only tries 2336 when 2352 had no sync; the first 32 bytes read either way.
            }
            if (source.readFully(toc * 2048 + fileOffset, header, 0, 32) >= 6 && header.copyOfRange(1, 6).contentEquals(CD001)) return found(2048, 0)
            val m = mode?.uppercase()
            if (m != null) {
                return when {
                    m.startsWith("MODE2/2352") -> found(2352, 24)
                    m.startsWith("MODE1/2048") -> found(2048, 0)
                    m.startsWith("MODE2/2336") -> found(2336, 8)
                    m.startsWith("MODE1/2352") -> found(2352, 16)
                    m.startsWith("AUDIO") -> found(2352, 0, 2352)
                    else -> null
                }
            }
            val size = source.size
            return when {
                size % 2352 == 0L -> found(2352, 24)
                size % 2048 == 0L -> found(2048, 0)
                size % 2336 == 0L -> found(2336, 8)
                else -> null
            }
        }
    }
}

/** One file that is the whole disc (.iso, .bin, .img, a CSO): track 1 only, as rcheevos reads it without a cue sheet. */
internal class SingleTrackImage(private val source: ByteSource) : DiscImage {
    override suspend fun open(which: TrackWanted): DiscTrack? {
        if (which is TrackWanted.Number && which.number > 1) return null
        if (which == TrackWanted.FirstOfSecondSession) return null
        return FileTrack.open(source, 0, null, 0, 0)
    }
}

/** A cue sheet's tracks, found the way rcheevos' cue reader finds them (port of cdreader_open_cue_track). */
internal class CueImage(private val path: String, private val text: String, private val files: DiscFiles) : DiscImage {
    private class Track(
        var id: Int = 0,
        var sectorSize: Int = 0,
        var sectorCount: Long = 0,
        var firstSector: Long = -1,
        var pregap: Long = -1,
        var isData: Boolean = false,
        var fileTrackOffset: Long = 0,
        var fileFirstSector: Long = 0,
        var mode: String = "",
        var fileName: String = "",
    ) {
        fun copy() = Track(id, sectorSize, sectorCount, firstSector, pregap, isData, fileTrackOffset, fileFirstSector, mode, fileName)
    }

    private suspend fun binSize(name: String): Long = files.open(DiscImage.sibling(path, name))?.size ?: 0

    override suspend fun open(which: TrackWanted): DiscTrack? {
        var wanted: Int = when (which) {
            is TrackWanted.Number -> which.number
            else -> -1
        }
        var current = Track()
        // rcheevos starts from a zeroed track: the first file begins at sector 0.
        var previous = Track(firstSector = 0)
        var largest = Track()
        var session = 1
        var done = false
        for (raw in text.lineSequence()) {
            val line = raw.trimStart(' ', '\t')
            when {
                line.startsWith("INDEX ", ignoreCase = true) -> {
                    val parts = line.substring(6).trim().split(Regex("\\s+"))
                    val index = parts.getOrNull(0)?.toIntOrNull() ?: 0
                    val (m, s, f) = (parts.getOrNull(1) ?: "0:0:0").split(':').map { it.trim().toIntOrNull() ?: 0 }.let { Triple(it.getOrElse(0) { 0 }, it.getOrElse(1) { 0 }, it.getOrElse(2) { 0 }) }
                    val sectorOffset = (m * 60L + s) * 75 + f
                    if (current.firstSector == -1L) {
                        current.firstSector = sectorOffset
                        if (current.fileName == previous.fileName) {
                            previous.sectorCount = current.firstSector - previous.firstSector
                            current.fileTrackOffset += previous.sectorCount * previous.sectorSize
                        }
                        if (which == TrackWanted.Largest && previous.sectorCount > largest.sectorCount && previous.isData) largest = previous.copy()
                    }
                    if (index == 1) {
                        current.pregap = sectorOffset - current.firstSector
                        if (current.id == wanted && wanted > 0) { done = true }
                        else if (which == TrackWanted.FirstData && current.isData) { wanted = current.id; done = true }
                        else if (which == TrackWanted.FirstOfSecondSession && session == 2) { wanted = current.id; done = true }
                    }
                }
                line.startsWith("TRACK ", ignoreCase = true) -> {
                    if (current.sectorSize != 0) previous = current.copy()
                    val parts = line.substring(6).trim().split(Regex("\\s+"))
                    current.id = parts.getOrNull(0)?.toIntOrNull() ?: 0
                    current.pregap = -1
                    current.firstSector = -1
                    current.mode = parts.getOrNull(1).orEmpty()
                    current.isData = current.mode.startsWith("MODE", ignoreCase = true)
                    current.sectorSize = if (current.isData) current.mode.substringAfter('/', "").toIntOrNull() ?: 0 else 2352
                }
                line.startsWith("FILE ", ignoreCase = true) -> {
                    if (current.sectorSize != 0) {
                        previous = current.copy()
                        if (previous.sectorCount == 0L) previous.sectorCount = binSize(previous.fileName) / previous.sectorSize - previous.firstSector
                        if (which == TrackWanted.Largest && previous.isData && previous.sectorCount > largest.sectorCount) largest = previous.copy()
                    }
                    current = Track(fileFirstSector = previous.fileFirstSector + previous.firstSector + previous.sectorCount)
                    val rest = line.substring(5).trim()
                    current.fileName = if (rest.startsWith("\"")) rest.substring(1).substringBefore('"') else rest.substringBefore(' ')
                }
                line.startsWith("REM ", ignoreCase = true) -> {
                    val rest = line.substring(4).trimStart()
                    if (rest.startsWith("SESSION ", ignoreCase = true)) session = rest.substring(8).trim().toIntOrNull() ?: session
                }
            }
            if (done) break
        }
        if (which == TrackWanted.Largest) {
            if (current.sectorSize != 0 && current.isData) {
                current.sectorCount = binSize(current.fileName) / current.sectorSize - current.firstSector
                if (largest.sectorCount > current.sectorCount) current = largest
            } else {
                current = largest
            }
            wanted = current.id
        } else if (which == TrackWanted.Last && !done) {
            wanted = current.id
        }
        if (current.id != wanted || wanted <= 0) return null
        val source = files.open(DiscImage.sibling(path, current.fileName)) ?: return null
        return FileTrack.open(source, current.fileTrackOffset, current.mode, current.fileFirstSector + current.firstSector, current.pregap.coerceAtLeast(0))
    }
}

/** A Dreamcast .gdi's tracks (port of rcheevos' cdreader_open_gdi_track). */
internal class GdiImage(private val path: String, private val text: String, private val files: DiscFiles) : DiscImage {
    private class Line(val track: Int, val lba: Long, val type: Int, val sectorSize: String, val file: String)

    private val lines: List<Line> by lazy {
        text.lineSequence().drop(1).mapNotNull { raw ->
            val l = raw.trim()
            if (l.isEmpty()) return@mapNotNull null
            val m = Regex("""^(\d+)\s+(\d+)\s+(\d+)\s+(\d+)\s+("([^"]*)"|(\S+))""").find(l) ?: return@mapNotNull null
            Line(m.groupValues[1].toInt(), m.groupValues[2].toLong(), m.groupValues[3].toInt(), m.groupValues[4], m.groupValues[6].ifEmpty { m.groupValues[7] })
        }.toList()
    }

    override suspend fun open(which: TrackWanted): DiscTrack? {
        val line: Line = when (which) {
            is TrackWanted.Number -> lines.firstOrNull { it.track == which.number }
            TrackWanted.FirstData -> lines.firstOrNull { it.type == 4 }
            TrackWanted.Last -> text.lineSequence().firstOrNull()?.trim()?.toIntOrNull()?.let { n -> lines.firstOrNull { it.track == n } }
            TrackWanted.Largest -> lines.filter { it.type == 4 }.maxByOrNull { files.open(DiscImage.sibling(path, it.file))?.size ?: 0 }
            TrackWanted.FirstOfSecondSession -> null
        } ?: return null
        val source = files.open(DiscImage.sibling(path, line.file)) ?: return null
        // A .gdi gives each track's address on the disc itself, whatever its sectors say.
        return FileTrack.open(source, 0, "MODE1/${line.sectorSize}", line.lba, 0)?.also { it.startAt(line.lba) }
    }
}
