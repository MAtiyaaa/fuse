package io.github.matiyaaa.fuse.integrations.retroachievements

import io.github.matiyaaa.fuse.integrations.ByteSource
import io.github.matiyaaa.fuse.integrations.Md5
import io.github.matiyaaa.fuse.integrations.disc.DiscFiles
import io.github.matiyaaa.fuse.integrations.disc.DiscImage
import io.github.matiyaaa.fuse.integrations.disc.NintendoImages
import io.github.matiyaaa.fuse.integrations.readFully

/**
 * A disc game's RetroAchievements hash, whatever it is kept as: a cue sheet and its tracks, a .gdi,
 * a CHD, a CSO or ZSO, Dolphin's GCZ, WIA or RVZ (GameCube), a 3DS dump, or a plain image. Picks
 * rcheevos' method for the console, as rc_hash_from_file does, and reads only what that method hashes.
 */
object RaFileHasher {
    /** Consoles whose games are discs (or, for the 3DS, need more than the whole file). */
    val discConsoles: Set<Int> = setOf(
        RaConsoleIds.SEGA_CD, RaConsoleIds.PLAYSTATION, RaConsoleIds.GAMECUBE, RaConsoleIds.WII, RaConsoleIds.PS2,
        RaConsoleIds.SATURN, RaConsoleIds.DREAMCAST, RaConsoleIds.PSP, THREE_DO, PC_FX, NEO_GEO_CD,
        RaConsoleIds.PC_ENGINE_CD, JAGUAR_CD, RaConsoleIds.N3DS,
    )

    const val THREE_DO = 43
    const val PC_FX = 49
    const val NEO_GEO_CD = 56
    const val JAGUAR_CD = 77

    /**
     * Hashes the game at [path] for [consoleId]. [files] opens it and its other files (tracks, a
     * playlist's discs); [keys3ds] gives the person's own Azahar or Citra aes_keys.txt, when there
     * is one, for 3DS dumps that are still encrypted.
     */
    suspend fun hash(consoleId: Int, path: String, files: DiscFiles, keys3ds: suspend () -> String? = { null }): RaHashResult {
        val ext = path.substringAfterLast('.', "").lowercase()
        if (ext == "m3u" || ext == "m3u8") {
            val first = files.readText(path)?.lineSequence()?.map { it.trim() }?.firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
                ?: return RaHashResult.Unsupported(RaHashUnsupportedReason.NOT_A_DISC)
            return hash(consoleId, DiscImage.sibling(path, first), files, keys3ds)
        }
        if (ext in UNREADABLE) return RaHashResult.Unsupported(RaHashUnsupportedReason.COMPRESSED)
        fun result(r: RaDisc.Result, method: RaHashMethod) = when (r) {
            is RaDisc.Result.Hash -> RaHashResult.Hashed(r.md5, method)
            is RaDisc.Result.Failed -> RaHashResult.Unsupported(r.reason)
        }
        suspend fun image(): DiscImage? = DiscImage.open(path, files)
        val notDisc = RaHashResult.Unsupported(RaHashUnsupportedReason.NOT_A_DISC)
        return when (consoleId) {
            RaConsoleIds.PLAYSTATION -> image()?.let { result(RaDiscHash.playStation(it), RaHashMethod.PLAYSTATION) } ?: notDisc
            RaConsoleIds.PS2 -> image()?.let { result(RaDiscHash.playStation2(it), RaHashMethod.PLAYSTATION_2) } ?: notDisc
            RaConsoleIds.PSP -> if (ext == "pbp") wholeFile(files.open(path), RaHashMethod.PSP) else image()?.let { result(RaDiscHash.psp(it), RaHashMethod.PSP) } ?: notDisc
            RaConsoleIds.SEGA_CD, RaConsoleIds.SATURN -> image()?.let { result(RaDiscHash.segaCd(it), RaHashMethod.SEGA_CD) } ?: notDisc
            RaConsoleIds.DREAMCAST -> image()?.let { result(RaDiscHash.dreamcast(it), RaHashMethod.DREAMCAST) } ?: notDisc
            RaConsoleIds.PC_ENGINE_CD -> if (ext == "cue" || ext == "chd") {
                image()?.let { result(RaDiscHash.pcEngineCd(it), RaHashMethod.PC_ENGINE_CD) } ?: notDisc
            } else {
                files.open(path)?.let { RaHasher.hash(RaHashMethod.PC_ENGINE, it) } ?: notDisc
            }
            PC_FX -> image()?.let { result(RaDiscHash.pcfx(it), RaHashMethod.PC_FX) } ?: notDisc
            NEO_GEO_CD -> image()?.let { result(RaDiscHash.neoGeoCd(it), RaHashMethod.NEO_GEO_CD) } ?: notDisc
            THREE_DO -> image()?.let { result(RaDiscHash.threeDo(it), RaHashMethod.THREE_DO) } ?: notDisc
            JAGUAR_CD -> image()?.let { result(RaDiscHash.jaguarCd(it), RaHashMethod.JAGUAR_CD) } ?: notDisc
            RaConsoleIds.GAMECUBE -> files.open(path)?.let { NintendoImages.open(it) }?.let { result(RaNintendoHash.gamecube(it), RaHashMethod.GAMECUBE) }
                ?: RaHashResult.Unsupported(RaHashUnsupportedReason.COMPRESSED)
            RaConsoleIds.WII -> {
                // Wii games in WIA or RVZ keep their partitions decrypted: not readable the way rcheevos hashes them.
                if (ext == "rvz" || ext == "wia" || ext == "gcz" || ext == "wbfs") RaHashResult.Unsupported(RaHashUnsupportedReason.COMPRESSED)
                else files.open(path)?.let { result(RaNintendoHash.wii(it), RaHashMethod.WII) } ?: notDisc
            }
            RaConsoleIds.N3DS -> files.open(path)?.let { Ra3dsHash.hash(it, Ra3dsHash.Keys(keys3ds())) } ?: notDisc
            else -> RaHashResult.Unsupported(RaHashUnsupportedReason.UNKNOWN_CONSOLE)
        }
    }

    private suspend fun wholeFile(source: ByteSource?, method: RaHashMethod): RaHashResult {
        val src = source ?: return RaHashResult.Unsupported(RaHashUnsupportedReason.EMPTY_FILE)
        val md5 = Md5()
        val buffer = ByteArray(1 shl 20)
        var at = 0L
        while (at < src.size) {
            val n = src.readFully(at, buffer, 0, minOf(buffer.size.toLong(), src.size - at).toInt())
            if (n <= 0) break
            md5.update(buffer, 0, n)
            at += n
        }
        return RaHashResult.Hashed(md5.digestHex(), method)
    }

    /** Formats no reader here understands: they are matched by name. */
    private val UNREADABLE = setOf("7z", "rar", "ecm", "wbfs", "nkit")
}
