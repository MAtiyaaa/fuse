package io.github.matiyaaa.fuse.integrations.retroachievements

import io.github.matiyaaa.fuse.integrations.ByteSource
import io.github.matiyaaa.fuse.integrations.Md5
import io.github.matiyaaa.fuse.integrations.readFully

/** How RetroAchievements (rcheevos) hashes a ROM for a console. */
enum class RaHashMethod {
    /** MD5 of the whole file. */
    WHOLE_FILE,

    /** MD5 after dropping a 16-byte iNES (`NES\x1a`) or FDS (`FDS\x1a`) header. */
    NES,

    /** MD5 after dropping a 512-byte copier header (size % 8192 == 512). */
    SNES,

    /** MD5 after dropping a 512-byte header (size % 131072 == 512). */
    PC_ENGINE,

    /** MD5 after dropping a 128-byte `ATARI7800` header. */
    ATARI_7800,

    /** MD5 after dropping a 64-byte `LYNX\0` header. */
    LYNX,

    /** MD5 of the ROM converted to big-endian (.z64) byte order. */
    N64,

    /** MD5 of the file name without its extension. */
    ARCADE_FILE_NAME,

    /** Nintendo DS and DSi: the header, the ARM9 and ARM7 code and the icon and title block. */
    NINTENDO_DS,

    /** PlayStation: the boot executable named in SYSTEM.CNF (or PSX.EXE), with its name. */
    PLAYSTATION,

    /** PlayStation 2: the boot executable named in SYSTEM.CNF (BOOT2), with its name. */
    PLAYSTATION_2,

    /** PSP: PSP_GAME/PARAM.SFO followed by PSP_GAME/SYSDIR/EBOOT.BIN. */
    PSP,
}

/** Why Fuse did not produce a hash. Never guess: an unsupported hash is reported, not faked. */
enum class RaHashUnsupportedReason(val message: String) {
    DISC_IMAGE("This system's discs use rcheevos' per-system disc hashing, which Fuse does not implement yet"),
    NINTENDO_DS("The file does not look like a Nintendo DS cartridge"),
    NINTENDO_3DS("Nintendo 3DS hashing needs decrypted content; not implemented"),
    UNKNOWN_CONSOLE("Fuse does not know how RetroAchievements hashes this console"),
    NOT_N64_ROM("The file does not start like a Nintendo 64 ROM (.z64/.v64/.n64/.ndd)"),
    EMPTY_FILE("The file is empty"),
    NOT_A_DISC("The image has no readable ISO 9660 data track"),
    NO_EXECUTABLE("The disc's boot executable could not be found"),
    COMPRESSED("Compressed images (CHD, CSO, RVZ, PBP) cannot be hashed; Fuse matches them by name"),
}

/** Result of [RaHasher.hash]. */
sealed interface RaHashResult {
    data class Hashed(val md5: String, val method: RaHashMethod) : RaHashResult
    data class Unsupported(val reason: RaHashUnsupportedReason) : RaHashResult

    /** The hash, or null when unsupported. */
    val md5OrNull: String? get() = (this as? Hashed)?.md5
}

/**
 * RetroAchievements ROM hashing, following rcheevos' rc_hash (MIT licensed): the cartridge systems
 * (the "simple cases"), Nintendo DS and DSi, and the PlayStation, PlayStation 2 and PSP discs
 * ([RaDisc]) from uncompressed images. Other disc systems and 3DS are reported as unsupported.
 *
 * Like rcheevos, at most the first [MAX_BUFFER_BYTES] of a file are read before header handling.
 */
object RaHasher {

    /** rcheevos reads at most 64 MiB for buffered (cartridge) hashing. */
    const val MAX_BUFFER_BYTES: Long = 64L * 1024 * 1024

    private const val CHUNK = 1 shl 20

    /** RA console id -> method, for consoles Fuse can hash. See [unsupportedReason] for the rest. */
    private val methods: Map<Int, RaHashMethod> = buildMap {
        // Whole-file consoles: MD, GB, GBA, GBC, 32X, SMS, NGP, Game Gear, Jaguar, Odyssey 2,
        // Pokemon Mini, 2600, Virtual Boy, SG-1000, ColecoVision, Intellivision, Vectrex,
        // WonderSwan, Channel F, Supervision, Mega Duck, WASM-4, Arcadia 2001, VC 4000, Elektor, Uzebox.
        listOf(1, 4, 5, 6, 10, 11, 14, 15, 17, 23, 24, 25, 28, 33, 44, 45, 46, 53, 57, 63, 69, 72, 73, 74, 75, 80)
            .forEach { put(it, RaHashMethod.WHOLE_FILE) }
        put(RaConsoleIds.NES, RaHashMethod.NES)
        put(RaConsoleIds.FDS, RaHashMethod.NES)
        put(RaConsoleIds.SNES, RaHashMethod.SNES)
        put(RaConsoleIds.PC_ENGINE, RaHashMethod.PC_ENGINE)
        put(RaConsoleIds.ATARI_7800, RaHashMethod.ATARI_7800)
        put(RaConsoleIds.LYNX, RaHashMethod.LYNX)
        put(RaConsoleIds.N64, RaHashMethod.N64)
        put(RaConsoleIds.ARCADE, RaHashMethod.ARCADE_FILE_NAME)
        put(RaConsoleIds.NDS, RaHashMethod.NINTENDO_DS)
        put(RaConsoleIds.DSI, RaHashMethod.NINTENDO_DS)
        put(RaConsoleIds.PLAYSTATION, RaHashMethod.PLAYSTATION)
        put(RaConsoleIds.PS2, RaHashMethod.PLAYSTATION_2)
        put(RaConsoleIds.PSP, RaHashMethod.PSP)
    }

    private val discConsoles = setOf(9, 16, 19, 20, 39, 40, 42, 43, 49, 56, 76, 77, 82)

    /** Image formats that would have to be decompressed first; these games are matched by name. */
    private val compressed = setOf("chd", "cso", "zso", "pbp", "rvz", "gcz", "wbfs", "wia", "7z", "rar", "ecm")

    /** The method for [consoleId], or null when Fuse cannot hash it (see [unsupportedReason]). */
    fun methodFor(consoleId: Int): RaHashMethod? = methods[consoleId]

    /** Why [consoleId] cannot be hashed, or null when it can. */
    fun unsupportedReason(consoleId: Int): RaHashUnsupportedReason? = when {
        consoleId in methods -> null
        consoleId in discConsoles -> RaHashUnsupportedReason.DISC_IMAGE
        consoleId == RaConsoleIds.N3DS -> RaHashUnsupportedReason.NINTENDO_3DS
        else -> RaHashUnsupportedReason.UNKNOWN_CONSOLE
    }

    /** True when a file with this name can't be hashed as it is (a compressed image or archive). */
    fun isCompressed(fileName: String): Boolean =
        fileName.substringAfterLast('/').substringAfterLast('\\').substringAfterLast('.', "").lowercase() in compressed

    /**
     * Hashes one ROM for [consoleId]. [fileName] is the file's name (a path is fine; only the last
     * segment is used, for arcade and to spot compressed images). [source] may be null for arcade;
     * for a disc it is the data track (the .bin a .cue names, or the .iso).
     */
    suspend fun hash(consoleId: Int, fileName: String, source: ByteSource?): RaHashResult {
        val method = methodFor(consoleId) ?: return RaHashResult.Unsupported(unsupportedReason(consoleId)!!)
        if (method == RaHashMethod.ARCADE_FILE_NAME) return RaHashResult.Hashed(hashArcade(fileName), method)
        if (isCompressed(fileName)) return RaHashResult.Unsupported(RaHashUnsupportedReason.COMPRESSED)
        val src = source ?: return RaHashResult.Unsupported(RaHashUnsupportedReason.EMPTY_FILE)
        return hash(method, src)
    }

    /** Hashes [source] with an explicit [method] (not for [RaHashMethod.ARCADE_FILE_NAME]). */
    suspend fun hash(method: RaHashMethod, source: ByteSource): RaHashResult {
        require(method != RaHashMethod.ARCADE_FILE_NAME) { "Arcade hashes the file name; use hashArcade()" }
        when (method) {
            RaHashMethod.NINTENDO_DS -> return hashNds(source)
            RaHashMethod.PLAYSTATION, RaHashMethod.PLAYSTATION_2, RaHashMethod.PSP -> {
                val md5 = when (method) {
                    RaHashMethod.PLAYSTATION -> RaDisc.playStation(source)
                    RaHashMethod.PLAYSTATION_2 -> RaDisc.playStation2(source)
                    else -> RaDisc.psp(source)
                }
                return when (md5) {
                    is RaDisc.Result.Hash -> RaHashResult.Hashed(md5.md5, method)
                    is RaDisc.Result.Failed -> RaHashResult.Unsupported(md5.reason)
                }
            }
            else -> Unit
        }
        val end = minOf(source.size, MAX_BUFFER_BYTES)
        if (end <= 0) return RaHashResult.Unsupported(RaHashUnsupportedReason.EMPTY_FILE)
        val head = ByteArray(minOf(end, 16L).toInt())
        source.readFully(0, head)
        val start: Long = when (method) {
            RaHashMethod.WHOLE_FILE -> 0
            RaHashMethod.NES -> if (head.startsWith(NES_MAGIC) || head.startsWith(FDS_MAGIC)) 16 else 0
            RaHashMethod.SNES -> if (source.size % 8192 == 512L) 512 else 0
            RaHashMethod.PC_ENGINE -> if (source.size % 131072 == 512L) 512 else 0
            RaHashMethod.ATARI_7800 -> if (head.size >= 10 && head.copyOfRange(1, 10).contentEquals(ATARI7800_MAGIC)) 128 else 0
            RaHashMethod.LYNX -> if (head.startsWith(LYNX_MAGIC)) 64 else 0
            RaHashMethod.N64 -> 0
            else -> error("unreachable")
        }
        if (method == RaHashMethod.N64) return hashN64(source, head, end)
        return RaHashResult.Hashed(md5Range(source, start.coerceAtMost(end), end, swap = ByteOrder.NONE), method)
    }

    /** RetroAchievements' arcade hash: MD5 of the file name without directory and extension. */
    fun hashArcade(fileName: String): String {
        val name = fileName.substringAfterLast('/').substringAfterLast('\\')
        val base = if ('.' in name) name.substringBeforeLast('.') else name
        return Md5.hex(base)
    }

    private suspend fun hashN64(source: ByteSource, head: ByteArray, end: Long): RaHashResult {
        val order = when (head.firstOrNull()?.toInt()?.and(0xFF)) {
            0x80 -> ByteOrder.NONE // .z64, already big-endian
            0x37 -> ByteOrder.SWAP_16 // .v64, byte-swapped
            0x40 -> ByteOrder.SWAP_32 // .n64, little-endian
            0xE8, 0x22 -> ByteOrder.NONE // .ndd disk images are hashed as-is
            else -> return RaHashResult.Unsupported(RaHashUnsupportedReason.NOT_N64_ROM)
        }
        return RaHashResult.Hashed(md5Range(source, 0, end, order), RaHashMethod.N64)
    }

    /**
     * rcheevos' Nintendo DS hash: the first 0x160 bytes of the header, the ARM9 and ARM7 code, and
     * the 0xA00-byte icon and title block (zero-filled when the file ends early). A SuperCard's
     * 512-byte header in front is skipped.
     */
    private suspend fun hashNds(source: ByteSource): RaHashResult {
        val header = ByteArray(512)
        if (source.readFully(0, header) < header.size) return RaHashResult.Unsupported(RaHashUnsupportedReason.NINTENDO_DS)
        var offset = 0L
        val superCard = header[0] == 0x2E.toByte() && header[1] == 0.toByte() && header[2] == 0.toByte() && header[3] == 0xEA.toByte() &&
            header[0xB0] == 0x44.toByte() && header[0xB1] == 0x46.toByte() && header[0xB2] == 0x96.toByte() && header[0xB3] == 0.toByte()
        if (superCard) {
            offset = 512
            if (source.readFully(offset, header) < header.size) return RaHashResult.Unsupported(RaHashUnsupportedReason.NINTENDO_DS)
        }
        val arm9Addr = header.u32(0x20)
        val arm9Size = header.u32(0x2C)
        val arm7Addr = header.u32(0x30)
        val arm7Size = header.u32(0x3C)
        val iconAddr = header.u32(0x68)
        // Code blocks are well under 1 MB each; anything bigger is not a DS cartridge.
        if (arm9Size + arm7Size > NDS_CODE_LIMIT) return RaHashResult.Unsupported(RaHashUnsupportedReason.NINTENDO_DS)
        val md5 = Md5()
        md5.update(header, 0, 0x160)
        for ((address, size) in listOf(arm9Addr to arm9Size, arm7Addr to arm7Size, iconAddr to NDS_ICON_SIZE)) {
            val block = ByteArray(size.toInt())
            source.readFully(address + offset, block)
            md5.update(block, 0, block.size)
        }
        return RaHashResult.Hashed(md5.digestHex(), RaHashMethod.NINTENDO_DS)
    }

    private fun ByteArray.u32(at: Int): Long =
        (this[at].toLong() and 0xFF) or ((this[at + 1].toLong() and 0xFF) shl 8) or
            ((this[at + 2].toLong() and 0xFF) shl 16) or ((this[at + 3].toLong() and 0xFF) shl 24)

    private const val NDS_CODE_LIMIT = 16L * 1024 * 1024
    private const val NDS_ICON_SIZE = 0xA00L

    private enum class ByteOrder { NONE, SWAP_16, SWAP_32 }

    private suspend fun md5Range(source: ByteSource, start: Long, end: Long, swap: ByteOrder): String {
        val md5 = Md5()
        val buffer = ByteArray(CHUNK)
        var position = start
        while (position < end) {
            val want = minOf(CHUNK.toLong(), end - position).toInt()
            val read = source.readFully(position, buffer, 0, want)
            if (read <= 0) break
            when (swap) {
                ByteOrder.NONE -> Unit
                ByteOrder.SWAP_16 -> {
                    var i = 0
                    while (i + 1 < read) {
                        val t = buffer[i]; buffer[i] = buffer[i + 1]; buffer[i + 1] = t
                        i += 2
                    }
                }
                ByteOrder.SWAP_32 -> {
                    var i = 0
                    while (i + 3 < read) {
                        var t = buffer[i]; buffer[i] = buffer[i + 3]; buffer[i + 3] = t
                        t = buffer[i + 1]; buffer[i + 1] = buffer[i + 2]; buffer[i + 2] = t
                        i += 4
                    }
                }
            }
            md5.update(buffer, 0, read)
            position += read
        }
        return md5.digestHex()
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
        size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

    private val NES_MAGIC = byteArrayOf('N'.code.toByte(), 'E'.code.toByte(), 'S'.code.toByte(), 0x1A)
    private val FDS_MAGIC = byteArrayOf('F'.code.toByte(), 'D'.code.toByte(), 'S'.code.toByte(), 0x1A)
    private val LYNX_MAGIC = byteArrayOf('L'.code.toByte(), 'Y'.code.toByte(), 'N'.code.toByte(), 'X'.code.toByte(), 0)
    private val ATARI7800_MAGIC = "ATARI7800".encodeToByteArray()
}

/** RetroAchievements console ids used by Fuse (rcheevos `RC_CONSOLE_*`). */
object RaConsoleIds {
    const val MEGA_DRIVE = 1
    const val N64 = 2
    const val SNES = 3
    const val GAME_BOY = 4
    const val GBA = 5
    const val GBC = 6
    const val NES = 7
    const val PC_ENGINE = 8
    const val SEGA_CD = 9
    const val SEGA_32X = 10
    const val MASTER_SYSTEM = 11
    const val PLAYSTATION = 12
    const val LYNX = 13
    const val NEO_GEO_POCKET = 14
    const val GAME_GEAR = 15
    const val GAMECUBE = 16
    const val JAGUAR = 17
    const val NDS = 18
    const val WII = 19
    const val PS2 = 21
    const val ATARI_2600 = 25
    const val ARCADE = 27
    const val VIRTUAL_BOY = 28
    const val SATURN = 39
    const val DREAMCAST = 40
    const val PSP = 41
    const val ATARI_7800 = 51
    const val WONDERSWAN = 53
    const val N3DS = 62
    const val PC_ENGINE_CD = 76
    const val DSI = 78
    const val FDS = 81
    const val PS3 = 82
}

/**
 * Finds the RetroAchievements game for a local ROM hash, using one console's API_GetGameList
 * response requested with h=1. Build it once per console and cache it ([RaCachePolicy.GAME_LIST]).
 */
class RaGameMatcher(gameList: List<RaGameListEntry>) {
    private val byHash: Map<String, RaGameListEntry> = buildMap {
        for (game in gameList) for (hash in game.hashes) {
            val key = hash.trim().lowercase()
            if (key.isNotEmpty()) getOrPut(key) { game }
        }
    }

    /** Number of distinct hashes indexed. */
    val size: Int get() = byHash.size

    /** The RA game entry for [md5] (any case), or null. */
    fun gameFor(md5: String): RaGameListEntry? = byHash[md5.trim().lowercase()]

    /** The RA game id for [md5], or null. */
    fun gameIdFor(md5: String): Long? = gameFor(md5)?.id
}
