package io.github.matiyaaa.fuse.integrations.retroachievements

import io.github.matiyaaa.fuse.integrations.Md5
import io.github.matiyaaa.fuse.integrations.disc.DiscImage
import io.github.matiyaaa.fuse.integrations.disc.DiscTrack
import io.github.matiyaaa.fuse.integrations.disc.TrackWanted

/**
 * RetroAchievements' disc hashes, ported function by function from rcheevos' rc_hash_disc.c (MIT
 * licensed), over any [DiscImage]: a cue sheet and its tracks, a .gdi, a CHD, a CSO or a plain image.
 */
internal object RaDiscHash {
    private const val SECTOR = 2048
    private const val MAX_BUFFER = 64L * 1024 * 1024

    private fun failed(reason: RaHashUnsupportedReason) = RaDisc.Result.Failed(reason)

    /** The sector and size of [path] ("DIR\\FILE", any case) in [track]'s ISO 9660 file system (rc_cd_find_file_sector). */
    suspend fun findFile(track: DiscTrack, path: String): Pair<Long, Long>? {
        var name = path.removePrefix("\\")
        val buffer = ByteArray(SECTOR)
        var sector: Long
        var sectors = 0L
        val slash = name.lastIndexOf('\\')
        if (slash >= 0) {
            sector = findFile(track, name.substring(0, slash))?.first ?: return null
            name = name.substring(slash + 1)
        } else {
            if (track.read(track.firstSector + 16, buffer, 256) < 256) return null
            sector = u24(buffer, 158)
            val blockSize = (buffer[128].toLong() and 0xFF) or ((buffer[129].toLong() and 0xFF) shl 8)
            sectors = if (blockSize == 0L) 1 else u32(buffer, 166) / blockSize
        }
        if (track.read(sector, buffer, SECTOR) <= 0) return null
        val wanted = name.encodeToByteArray()
        var at = 0
        while (true) {
            if (at >= buffer.size || buffer[at] == 0.toByte()) {
                if (sectors > 1) {
                    sectors--
                    if (track.read(++sector, buffer, SECTOR) > 0) {
                        at = 0
                        continue
                    }
                }
                return null
            }
            val nameLength = buffer.getOrNull(at + 32)?.toInt()?.and(0xFF) ?: 0
            val semicolon = at + 33 + wanted.size
            val matchesLength = nameLength == wanted.size || (semicolon < buffer.size && buffer[semicolon] == ';'.code.toByte())
            if (matchesLength && at + 33 + wanted.size <= buffer.size && wanted.indices.all { i -> upper(buffer[at + 33 + i]) == upper(wanted[i]) }) {
                return u24(buffer, at + 2) to u32(buffer, at + 10)
            }
            val length = buffer[at].toInt() and 0xFF
            if (length == 0) return null
            at += length
        }
    }

    /** Adds [size] bytes of the file at [sector] (at most 64 MiB); false when its first sector can't be read (rc_hash_cd_file). */
    suspend fun hashFile(md5: Md5, track: DiscTrack, sector: Long, size: Long): Boolean {
        val buffer = ByteArray(SECTOR)
        var read = track.read(sector, buffer, SECTOR)
        if (read < SECTOR) return false
        var left = size.coerceAtMost(MAX_BUFFER)
        if (left < read) read = left.toInt()
        var at = sector
        while (true) {
            md5.update(buffer, 0, read)
            if (left <= read) break
            left -= read
            at++
            read = track.read(at, buffer, if (left >= SECTOR) SECTOR else left.toInt())
            if (read <= 0) break
        }
        return true
    }

    // ------------------------------------------------------------------ the PlayStation family

    private suspend fun bootExecutable(track: DiscTrack, key: String, prefix: String): Triple<String, Long, Long>? {
        val cnf = findFile(track, "SYSTEM.CNF") ?: return null
        val buffer = ByteArray(SECTOR)
        val read = track.read(cnf.first, buffer, SECTOR - 1).coerceAtLeast(0)
        val text = buffer.copyOf(read).decodeToString().substringBefore('\u0000')
        var at = 0
        while (at < text.length) {
            if (text.startsWith(key, at)) {
                var p = at + key.length
                while (p < text.length && text[p].isWhitespace()) p++
                if (p < text.length && text[p] == '=') {
                    p++
                    while (p < text.length && text[p].isWhitespace()) p++
                    if (text.startsWith(prefix, p)) p += prefix.length
                    while (p < text.length && text[p] == '\\') p++
                    val start = p
                    while (p < text.length && !text[p].isWhitespace() && text[p] != ';') p++
                    val name = text.substring(start, p).take(63)
                    val file = findFile(track, name) ?: return null
                    return Triple(name, file.first, file.second)
                }
            }
            while (at < text.length && text[at] != '\n') at++
            at++
        }
        return null
    }

    suspend fun playStation(image: DiscImage): RaDisc.Result {
        val track = image.open(TrackWanted.Number(1)) ?: return failed(RaHashUnsupportedReason.NOT_A_DISC)
        var exe = bootExecutable(track, "BOOT", "cdrom:")
        if (exe == null) findFile(track, "PSX.EXE")?.let { exe = Triple("PSX.EXE", it.first, it.second) }
        val (name, sector, found) = exe ?: return failed(RaHashUnsupportedReason.NO_EXECUTABLE)
        val head = ByteArray(32)
        if (track.read(sector, head, 32) < 32) return failed(RaHashUnsupportedReason.NO_EXECUTABLE)
        val size = if (head.copyOfRange(0, 7).contentEquals("PS-X EX".encodeToByteArray())) u32(head, 28) + 2048 else found
        val md5 = Md5().update(name.encodeToByteArray())
        if (!hashFile(md5, track, sector, size)) return failed(RaHashUnsupportedReason.NO_EXECUTABLE)
        return RaDisc.Result.Hash(md5.digestHex())
    }

    suspend fun playStation2(image: DiscImage): RaDisc.Result {
        val track = image.open(TrackWanted.Number(1)) ?: return failed(RaHashUnsupportedReason.NOT_A_DISC)
        val (name, sector, size) = bootExecutable(track, "BOOT2", "cdrom0:") ?: return failed(RaHashUnsupportedReason.NO_EXECUTABLE)
        val head = ByteArray(4)
        if (track.read(sector, head, 4) < 4) return failed(RaHashUnsupportedReason.NO_EXECUTABLE)
        val md5 = Md5().update(name.encodeToByteArray())
        if (!hashFile(md5, track, sector, size)) return failed(RaHashUnsupportedReason.NO_EXECUTABLE)
        return RaDisc.Result.Hash(md5.digestHex())
    }

    suspend fun psp(image: DiscImage): RaDisc.Result {
        val track = image.open(TrackWanted.Number(1)) ?: return failed(RaHashUnsupportedReason.NOT_A_DISC)
        val sfo = findFile(track, "PSP_GAME\\PARAM.SFO") ?: return failed(RaHashUnsupportedReason.NOT_A_DISC)
        val md5 = Md5()
        if (!hashFile(md5, track, sfo.first, sfo.second)) return failed(RaHashUnsupportedReason.NOT_A_DISC)
        val eboot = findFile(track, "PSP_GAME\\SYSDIR\\EBOOT.BIN") ?: return failed(RaHashUnsupportedReason.NO_EXECUTABLE)
        if (!hashFile(md5, track, eboot.first, eboot.second)) return failed(RaHashUnsupportedReason.NO_EXECUTABLE)
        return RaDisc.Result.Hash(md5.digestHex())
    }

    // ------------------------------------------------------------------ Sega

    /** Sega CD and Saturn: the first 512 bytes of sector 0, the volume and ROM headers (rc_hash_sega_cd). */
    suspend fun segaCd(image: DiscImage): RaDisc.Result {
        val track = image.open(TrackWanted.Number(1)) ?: return failed(RaHashUnsupportedReason.NOT_A_DISC)
        val buffer = ByteArray(512)
        track.read(0, buffer, 512)
        val head = buffer.copyOfRange(0, 16).decodeToString()
        if (head != "SEGADISCSYSTEM  " && head != "SEGA SEGASATURN ") return failed(RaHashUnsupportedReason.NOT_A_DISC)
        return RaDisc.Result.Hash(Md5().update(buffer).digestHex())
    }

    /** Dreamcast: IP.BIN's 256 bytes from track 3 (or a MIL-CD's first data track), then the boot file it names. */
    suspend fun dreamcast(image: DiscImage): RaDisc.Result {
        val buffer = ByteArray(256)
        var track = image.open(TrackWanted.Number(3))
        track?.let { it.read(it.firstSector, buffer, 256) }
        if (!buffer.copyOfRange(0, 16).contentEquals("SEGA SEGAKATANA ".encodeToByteArray())) {
            track = image.open(TrackWanted.FirstData) ?: return failed(RaHashUnsupportedReason.NOT_A_DISC)
            buffer.fill(0)
            track.read(track.firstSector, buffer, 256)
            if (!buffer.copyOfRange(0, 16).contentEquals("SEGA SEGAKATANA ".encodeToByteArray())) return failed(RaHashUnsupportedReason.NOT_A_DISC)
        }
        val md5 = Md5().update(buffer, 0, 256)
        var i = 0
        while (i < 16 && !(buffer[96 + i].toInt().toChar().isWhitespace())) i++
        if (i == 0) return failed(RaHashUnsupportedReason.NO_EXECUTABLE)
        val exe = buffer.copyOfRange(96, 96 + i).decodeToString()
        val (sector, size) = findFile(track!!, exe) ?: return failed(RaHashUnsupportedReason.NO_EXECUTABLE)
        val probe = ByteArray(1)
        val inTrack = if (track.read(sector, probe, 1) > 0) track else image.open(TrackWanted.Last) ?: return failed(RaHashUnsupportedReason.NO_EXECUTABLE)
        if (!hashFile(md5, inTrack, sector, size)) return failed(RaHashUnsupportedReason.NO_EXECUTABLE)
        return RaDisc.Result.Hash(md5.digestHex())
    }

    // ------------------------------------------------------------------ NEC

    private suspend fun pceTrack(track: DiscTrack): RaDisc.Result {
        val buffer = ByteArray(SECTOR)
        if (track.read(track.firstSector + 1, buffer, 128) < 128) return failed(RaHashUnsupportedReason.NOT_A_DISC)
        if (buffer.copyOfRange(32, 55).contentEquals("PC Engine CD-ROM SYSTEM".encodeToByteArray())) {
            val md5 = Md5().update(buffer, 106, 22)
            var sector = u24be(buffer, 0)
            var count = buffer[3].toInt() and 0xFF
            sector += track.firstSector
            while (count-- > 0) {
                track.read(sector++, buffer, SECTOR)
                md5.update(buffer, 0, SECTOR)
            }
            return RaDisc.Result.Hash(md5.digestHex())
        }
        val boot = findFile(track, "BOOT.BIN")
        if (boot != null && boot.second < MAX_BUFFER) {
            val md5 = Md5()
            var (sector, size) = boot
            while (size > SECTOR) {
                track.read(sector++, buffer, SECTOR)
                md5.update(buffer, 0, SECTOR)
                size -= SECTOR
            }
            if (size > 0) {
                track.read(sector, buffer, size.toInt())
                md5.update(buffer, 0, size.toInt())
            }
            return RaDisc.Result.Hash(md5.digestHex())
        }
        return failed(RaHashUnsupportedReason.NOT_A_DISC)
    }

    /** PC Engine CD: the first data track's boot header and program, or a GameExpress disc's BOOT.BIN (rc_hash_pce_cd). */
    suspend fun pcEngineCd(image: DiscImage): RaDisc.Result =
        image.open(TrackWanted.FirstData)?.let { pceTrack(it) } ?: failed(RaHashUnsupportedReason.NOT_A_DISC)

    /** PC-FX: the boot header in the largest data track (else track 2) and the program it names (rc_hash_pcfx_cd). */
    suspend fun pcfx(image: DiscImage): RaDisc.Result {
        val buffer = ByteArray(SECTOR)
        var track = image.open(TrackWanted.Largest) ?: return failed(RaHashUnsupportedReason.NOT_A_DISC)
        var sector = track.firstSector
        track.read(sector, buffer, 32)
        val marker = "PC-FX:Hu_CD-ROM".encodeToByteArray()
        if (!buffer.copyOfRange(0, 15).contentEquals(marker)) {
            track = image.open(TrackWanted.Number(2)) ?: return failed(RaHashUnsupportedReason.NOT_A_DISC)
            sector = track.firstSector
            track.read(sector, buffer, 32)
        }
        if (buffer.copyOfRange(0, 15).contentEquals(marker)) {
            track.read(sector + 1, buffer, 128)
            val md5 = Md5().update(buffer, 0, 128)
            var program = ((buffer[34].toLong() and 0xFF) shl 16) + ((buffer[33].toLong() and 0xFF) shl 8) + (buffer[32].toLong() and 0xFF)
            var count = ((buffer[38].toInt() and 0xFF) shl 16) + ((buffer[37].toInt() and 0xFF) shl 8) + (buffer[36].toInt() and 0xFF)
            program += track.firstSector
            while (count-- > 0) {
                track.read(program++, buffer, SECTOR)
                md5.update(buffer, 0, SECTOR)
            }
            return RaDisc.Result.Hash(md5.digestHex())
        }
        track.read(sector + 1, buffer, 128)
        if (buffer.copyOfRange(32, 55).contentEquals("PC Engine CD-ROM SYSTEM".encodeToByteArray())) return pceTrack(track)
        return failed(RaHashUnsupportedReason.NOT_A_DISC)
    }

    // ------------------------------------------------------------------ the rest

    /** Neo Geo CD: every .PRG IPL.TXT names, in order (rc_hash_neogeo_cd). */
    suspend fun neoGeoCd(image: DiscImage): RaDisc.Result {
        val track = image.open(TrackWanted.Number(1)) ?: return failed(RaHashUnsupportedReason.NOT_A_DISC)
        val ipl = findFile(track, "IPL.TXT") ?: return failed(RaHashUnsupportedReason.NOT_A_DISC)
        val buffer = ByteArray(1024)
        if (track.read(ipl.first, buffer, 1024) == 0) return failed(RaHashUnsupportedReason.NOT_A_DISC)
        buffer[1023] = 0
        val text = buffer.decodeToString().substringBefore('\u0000').substringBefore('\u001a')
        val md5 = Md5()
        for (line in text.split('\n')) {
            val dot = line.indexOf('.')
            if (dot < 0 || !line.regionMatches(dot, ".PRG", 0, 4, ignoreCase = true)) continue
            val name = line.substring(0, dot + 4)
            val (sector, size) = findFile(track, name) ?: return failed(RaHashUnsupportedReason.NO_EXECUTABLE)
            if (!hashFile(md5, track, sector, size)) return failed(RaHashUnsupportedReason.NO_EXECUTABLE)
        }
        return RaDisc.Result.Hash(md5.digestHex())
    }

    /** 3DO: the Opera volume header and the LaunchMe file (rc_hash_3do). */
    suspend fun threeDo(image: DiscImage): RaDisc.Result {
        val track = image.open(TrackWanted.Number(1)) ?: return failed(RaHashUnsupportedReason.NOT_A_DISC)
        val buffer = ByteArray(SECTOR)
        val opera = byteArrayOf(1, 0x5A, 0x5A, 0x5A, 0x5A, 0x5A, 1)
        if (track.read(0, buffer, 132) < 132 || !buffer.copyOfRange(0, 7).contentEquals(opera)) return failed(RaHashUnsupportedReason.NOT_A_DISC)
        val md5 = Md5().update(buffer, 0, 132)
        fun b(i: Int) = buffer[i].toInt() and 0xFF
        var blockSize = b(0x4D) * 65536 + b(0x4E) * 256 + b(0x4F)
        var blockLocation = (b(0x65) * 65536 + b(0x66) * 256 + b(0x67)).toLong() * blockSize
        var sector = blockLocation / 2048
        var size = 0L
        while (true) {
            track.read(sector, buffer, SECTOR)
            var offset = b(0x12) * 256 + b(0x13)
            val stop = b(0x0D) * 65536 + b(0x0E) * 256 + b(0x0F)
            while (offset < stop && offset + 0x48 <= SECTOR) {
                if (b(offset + 3) == 2) {
                    val name = buffer.copyOfRange(offset + 0x20, minOf(offset + 0x40, SECTOR)).decodeToString().substringBefore('\u0000')
                    if (name.equals("LaunchMe", ignoreCase = true)) {
                        blockSize = b(offset + 0x0D) * 65536 + b(offset + 0x0E) * 256 + b(offset + 0x0F)
                        blockLocation = (b(offset + 0x45) * 65536 + b(offset + 0x46) * 256 + b(offset + 0x47)).toLong() * blockSize
                        size = (b(offset + 0x11) * 65536 + b(offset + 0x12) * 256 + b(offset + 0x13)).toLong()
                        break
                    }
                }
                offset += 0x48 + b(offset + 0x43) * 4
            }
            if (size != 0L) break
            val next = b(0x02) * 256 + b(0x03)
            if (next == 0xFFFF) break
            sector = (blockLocation + next.toLong() * blockSize) / 2048
        }
        if (size == 0L) return failed(RaHashUnsupportedReason.NO_EXECUTABLE)
        sector = blockLocation / 2048
        while (size > SECTOR) {
            track.read(sector++, buffer, SECTOR)
            md5.update(buffer, 0, SECTOR)
            size -= SECTOR
        }
        track.read(sector, buffer, size.toInt())
        md5.update(buffer, 0, size.toInt())
        return RaDisc.Result.Hash(md5.digestHex())
    }

    /** Atari Jaguar CD: the boot code the second session's first track's header describes (rc_hash_jaguar_cd). */
    suspend fun jaguarCd(image: DiscImage): RaDisc.Result {
        var track = image.open(TrackWanted.FirstOfSecondSession) ?: return failed(RaHashUnsupportedReason.NOT_A_DISC)
        val buffer = ByteArray(2352)
        var sector = track.firstSector
        track.read(sector, buffer, buffer.size)
        var size = 0L
        var offset = 0
        var swapped = false
        val plain = "ATARI APPROVED DATA HEADER ATRI ".encodeToByteArray()
        val swappedMarker = "TARA IPARPVODED TA AEHDAREA RT I".encodeToByteArray()
        fun b(i: Int) = buffer[i].toLong() and 0xFF
        for (i in 64 until buffer.size - 32 - 4 * 3) {
            if (buffer.copyOfRange(i, i + 32).contentEquals(swappedMarker)) {
                swapped = true
                offset = i + 32 + 4
                size = (b(offset) shl 16) or (b(offset + 1) shl 24) or b(offset + 2) or (b(offset + 3) shl 8)
                break
            }
            if (buffer.copyOfRange(i, i + 32).contentEquals(plain)) {
                offset = i + 32 + 4
                size = (b(offset) shl 24) or (b(offset + 1) shl 16) or (b(offset + 2) shl 8) or b(offset + 3)
                break
            }
        }
        if (size == 0L) return failed(RaHashUnsupportedReason.NOT_A_DISC)
        var pass = 0
        while (true) {
            val md5 = Md5()
            offset += 4
            if (size > MAX_BUFFER) size = MAX_BUFFER
            while (true) {
                if (swapped) for (i in 0 until buffer.size - 1 step 2) { val t = buffer[i]; buffer[i] = buffer[i + 1]; buffer[i + 1] = t }
                val remaining = buffer.size - offset
                if (remaining >= size) {
                    md5.update(buffer, offset, size.toInt())
                    size = 0
                    break
                }
                md5.update(buffer, offset, remaining)
                size -= remaining
                offset = 0
                if (track.read(++sector, buffer, buffer.size) != buffer.size) break
            }
            if (size > 0) return failed(RaHashUnsupportedReason.NOT_A_DISC)
            val hash = md5.digestHex()
            // Homebrew shares one boot program and keeps the game in track 2.
            if (hash != "254487b59ab21bc005338e85cbf9fd2f" || !swapped || pass == 1) return RaDisc.Result.Hash(hash)
            pass++
            track = image.open(TrackWanted.Number(2)) ?: return failed(RaHashUnsupportedReason.NOT_A_DISC)
            sector = track.firstSector
            track.read(sector, buffer, buffer.size)
            if (!buffer.copyOfRange(0x5E, 0x66).contentEquals("RT!IRTKA".encodeToByteArray())) return failed(RaHashUnsupportedReason.NO_EXECUTABLE)
            offset = 0xA6
            size = (b(offset) shl 16) or (b(offset + 1) shl 24) or b(offset + 2) or (b(offset + 3) shl 8)
        }
    }

    private fun upper(b: Byte): Int = b.toInt().toChar().uppercaseChar().code
    private fun u24(b: ByteArray, at: Int): Long = (b[at].toLong() and 0xFF) or ((b[at + 1].toLong() and 0xFF) shl 8) or ((b[at + 2].toLong() and 0xFF) shl 16)
    private fun u24be(b: ByteArray, at: Int): Long = ((b[at].toLong() and 0xFF) shl 16) or ((b[at + 1].toLong() and 0xFF) shl 8) or (b[at + 2].toLong() and 0xFF)
    private fun u32(b: ByteArray, at: Int): Long = u24(b, at) or ((b[at + 3].toLong() and 0xFF) shl 24)
}
