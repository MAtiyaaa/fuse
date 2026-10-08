package io.github.matiyaaa.fuse.romm

import io.github.matiyaaa.fuse.library.FsPath
import io.github.matiyaaa.fuse.library.parse.FilenameParser
import io.github.matiyaaa.fuse.model.BiosFile
import io.github.matiyaaa.fuse.model.BiosState
import io.github.matiyaaa.fuse.model.BiosStatus
import io.github.matiyaaa.fuse.model.ContentKind
import io.github.matiyaaa.fuse.model.Platform

/** A game in Fuse's library, as matching sees it. */
data class LocalGame(
    val id: Long,
    /** Fuse's platform id. */
    val platform: String,
    val title: String,
    /** The game's file or folder name, as on disk. */
    val fileName: String,
    val serial: String? = null,
    val md5: String? = null,
    val rommRomId: Long? = null,
    val missing: Boolean = false,
)

/** How sure a match is, strongest first; only these ever join a RomM game to a Fuse game. */
enum class MatchReason { LINKED, HASH, TITLE_ID, FILE_NAME, NAME_AND_TAGS }

data class RommMatchResult(val romId: Long, val gameId: Long, val reason: MatchReason)

/**
 * Which Fuse game each RomM game is, so one game is never two: the RomM link Fuse keeps, else a hash,
 * a console's own title id, the exact file name, or the same name with the same region and revision
 * when it is the only one on either side. A name that only looks alike is never enough.
 */
object RommMatch {
    /**
     * Matches [roms] to [games]. [platformOf] turns RomM's platform slug into Fuse's platform id. A
     * Fuse game is matched at most once, to the strongest candidate.
     */
    fun match(roms: List<RommRom>, games: List<LocalGame>, platformOf: (String) -> String?): List<RommMatchResult> {
        val live = games.filter { !it.missing }
        val byLink = live.filter { it.rommRomId != null }.associateBy { it.rommRomId!! }
        val byMd5 = live.filter { !it.md5.isNullOrBlank() }.groupBy { it.md5!!.lowercase() }
        val bySerial = live.filter { !it.serial.isNullOrBlank() }.groupBy { it.platform to idKey(it.serial!!) }
        val byFile = live.groupBy { it.platform to it.fileName.lowercase() }
        val byName = live.groupBy { it.platform to nameKey(it.fileName.ifBlank { it.title }) }
        // PS4 and PS5 games are zips on RomM but played from the folder they unpack to, named for the
        // zip or carrying the game's own id (CUSA12345, PPSA12345), as Cartridge matches them.
        val byPsId = live.filter { it.platform in FOLDER_SYSTEMS }
            .mapNotNull { g -> (listOfNotNull(g.serial, g.fileName, g.title).firstNotNullOfOrNull(::playStationId))?.let { (g.platform to it) to g } }
            .groupBy({ it.first }, { it.second })
        val romsByName = roms.groupBy { (platformOf(it.platformSlug) ?: it.platformSlug) to nameKey(it.fsName.ifBlank { it.name }) }

        val taken = HashSet<Long>()
        val out = ArrayList<RommMatchResult>()
        // Strongest reasons first across every game, so a weak guess never takes a game another
        // RomM game is linked to by a stronger one.
        val candidates = ArrayList<RommMatchResult>()
        for (r in roms) {
            val platform = platformOf(r.platformSlug) ?: continue
            byLink[r.id]?.let { candidates += RommMatchResult(r.id, it.id, MatchReason.LINKED) }
            val hashes = listOfNotNull(r.md5) + r.files.mapNotNull { it.md5 }
            hashes.firstNotNullOfOrNull { h -> byMd5[h.lowercase()]?.singleOrNull { it.platform == platform } }?.let { candidates += RommMatchResult(r.id, it.id, MatchReason.HASH) }
            r.titleId?.let { t -> bySerial[platform to idKey(t)]?.singleOrNull() }?.let { candidates += RommMatchResult(r.id, it.id, MatchReason.TITLE_ID) }
            byFile[platform to r.fsName.lowercase()]?.singleOrNull()?.takeIf { weakCompatible(r, it) }?.let { candidates += RommMatchResult(r.id, it.id, MatchReason.FILE_NAME) }
            if (platform in FOLDER_SYSTEMS) {
                val unpacked = r.fsName.replace(ARCHIVE, "").lowercase()
                if (unpacked != r.fsName.lowercase()) byFile[platform to unpacked]?.singleOrNull()?.takeIf { weakCompatible(r, it) }?.let { candidates += RommMatchResult(r.id, it.id, MatchReason.FILE_NAME) }
                (listOfNotNull(r.titleId, r.fsName, r.name).firstNotNullOfOrNull(::playStationId))
                    ?.let { byPsId[platform to it]?.singleOrNull() }
                    ?.let { candidates += RommMatchResult(r.id, it.id, MatchReason.TITLE_ID) }
            }
            val key = platform to nameKey(r.fsName.ifBlank { r.name })
            val locals = byName[key]
            if (locals != null && locals.size == 1 && romsByName[key]?.size == 1 && key.second.isNotEmpty()) {
                if (weakCompatible(r, locals.single())) candidates += RommMatchResult(r.id, locals.single().id, MatchReason.NAME_AND_TAGS)
            }
        }
        val claimedRoms = HashSet<Long>()
        for (c in candidates.sortedBy { it.reason.ordinal }) {
            if (c.romId in claimedRoms || c.gameId in taken) continue
            claimedRoms += c.romId
            taken += c.gameId
            out += c
        }
        return out
    }

    /** Names never overrule incompatible explicit identities, even when both sides are unique. */
    private fun weakCompatible(rom: RommRom, game: LocalGame): Boolean {
        if (game.rommRomId != null && game.rommRomId != rom.id) return false
        val remoteId = rom.titleId?.takeIf { it.isNotBlank() } ?: if (game.platform in FOLDER_SYSTEMS) {
            listOf(rom.fsName, rom.name).firstNotNullOfOrNull(::playStationId)
        } else null
        val localId = game.serial?.takeIf { it.isNotBlank() } ?: if (game.platform in FOLDER_SYSTEMS) {
            listOf(game.fileName, game.title).firstNotNullOfOrNull(::playStationId)
        } else null
        if (remoteId != null && localId != null && idKey(remoteId) != idKey(localId)) return false
        val hashes = (listOfNotNull(rom.md5) + rom.files.mapNotNull { it.md5 }).filter { it.isNotBlank() }
        if (!game.md5.isNullOrBlank() && hashes.isNotEmpty() && hashes.none { it.equals(game.md5, ignoreCase = true) }) return false
        return true
    }

    /** Systems whose games RomM keeps as archives but that are played from the folder they unpack to. */
    private val FOLDER_SYSTEMS = setOf("ps4", "ps5")
    private val ARCHIVE = Regex("\\.(zip|7z|rar)$", RegexOption.IGNORE_CASE)
    private val PS_ID = Regex("\\b(CUSA|PPSA)[-_ ]?(\\d{5})\\b", RegexOption.IGNORE_CASE)

    /** A PS4 or PS5 game's own id in [text] (CUSA12345, PPSA12345), however it is written. */
    internal fun playStationId(text: String): String? = PS_ID.find(text)?.let { it.groupValues[1].uppercase() + it.groupValues[2] }

    /** A console id written any way ("SLUS-00067", "slus_000.67", "0100ABCD...") the same. */
    fun idKey(id: String): String = id.lowercase().filter { it.isLetterOrDigit() }

    /**
     * A name with what tells copies apart kept (region, revision, version, disc), and what doesn't
     * (case, punctuation, dump flags) dropped: "Metroid Fusion (USA) (Rev 1)" is never "Metroid
     * Fusion (Europe)".
     */
    fun nameKey(name: String): String {
        val p = FilenameParser.parse(name, hasExtension = name.contains('.'))
        val title = p.baseTitle.lowercase().filter { it.isLetterOrDigit() }
        if (title.isEmpty()) return ""
        val t = p.tags
        return listOf(title, t.regions.sorted().joinToString(",").lowercase(), t.revision.orEmpty().lowercase(), t.version.orEmpty().lowercase(), t.discNumber?.toString().orEmpty()).joinToString("|")
    }
}

/** One part of a RomM game as Fuse shows it: the base game, a disc, an update, DLC... */
data class RommPart(val kind: ContentKind, val label: String, val files: List<RommFile>, val disc: Int? = null) {
    val sizeBytes: Long get() = files.sumOf { it.sizeBytes }
}

/**
 * A RomM game's files sorted into what they are, the way Fuse's game page shows a game's content:
 * the game (or each of its discs), then updates, DLC, patches and the rest by RomM's own categories.
 */
object RommContent {
    fun parts(rom: RommRom): List<RommPart> {
        val byKind = rom.files.groupBy { it.kind }
        val out = ArrayList<RommPart>()
        val game = byKind[ContentKind.GAME].orEmpty()
        val discs = game.groupBy { discOf(it.name) }
        if (discs.keys.filterNotNull().size >= 2) {
            // Files without a disc number (an .m3u, a .cue's shared data) belong with the set.
            val shared = discs[null].orEmpty()
            for ((n, files) in discs.filterKeys { it != null }.toSortedMap(compareBy { it })) {
                out += RommPart(ContentKind.GAME, "Disc $n", files, disc = n)
            }
            if (shared.isNotEmpty()) out.add(0, RommPart(ContentKind.GAME, "Game", shared))
        } else if (game.isNotEmpty()) {
            out += RommPart(ContentKind.GAME, "Game", game)
        }
        for (kind in ContentKind.entries.filter { it != ContentKind.GAME }) {
            val files = byKind[kind].orEmpty()
            if (files.isEmpty()) continue
            // One part per folder inside the category ("update/1.04"), or per file at its top.
            files.groupBy { it.path.substringAfter('/', "").ifEmpty { "\u0000" + it.name } }.forEach { (key, fs) ->
                val label = if (key.startsWith("\u0000")) key.drop(1).substringBeforeLast('.') else key
                out += RommPart(kind, label.ifBlank { kind.slug }, fs)
            }
        }
        return out
    }

    private fun discOf(name: String): Int? = FilenameParser.parse(name).tags.discNumber
}

/** A library folder Fuse knows on this device, with the folders directly inside it. */
data class RootListing(val path: String, val children: List<String>, val romm: Boolean = false)

/** Where a RomM game lands on this device, and how its files are laid out there. */
data class Placement(
    /** The system's folder ("/storage/SD/roms/psx"). */
    val folder: String,
    /** True when Fuse makes that folder (it wasn't there). */
    val creates: Boolean,
    /** Each file's path once in place. */
    val files: Map<Long, String>,
    /** The game's own path: the file, or its folder for a game of several files. */
    val gamePath: String,
)

/**
 * Where a RomM game goes, from what Fuse already knows: the person's choice for the system, else the
 * system's folder in a library Fuse already has (by RomM slug, ES-DE name or any name Fuse reads as
 * that system), else a new folder named RomM's way in the person's chosen download library. Never an
 * arbitrary Downloads folder for the person to sort out.
 */
object RommPlacement {
    /**
     * The system folder for [platform] (Fuse's), or null when Fuse has nowhere to put it (the person
     * chooses then). [overrides] is the person's folder by Fuse platform id; [preferredRoot] the
     * library they chose for new downloads.
     */
    fun systemFolder(
        platform: Platform,
        rommFsSlug: String,
        roots: List<RootListing>,
        overrides: Map<String, String>,
        preferredRoot: String?,
        resolveFolder: (String) -> Platform?,
    ): Pair<String, Boolean>? {
        overrides[platform.id.value]?.takeIf { it.isNotBlank() }?.let { return FsPath.normalize(it) to false }
        // Ordered: the person's chosen library first, then the rest.
        val ordered = roots.sortedByDescending { preferredRoot != null && FsPath.normalize(it.path) == FsPath.normalize(preferredRoot) }
        for (root in ordered) {
            val base = FsPath.normalize(root.path)
            // A folder of this system right inside, by any name Fuse reads as it.
            root.children.firstOrNull { resolveFolder(it)?.id == platform.id }?.let { return FsPath.join(base, it) to false }
        }
        val target = preferredRoot ?: roots.firstOrNull { it.romm }?.path ?: roots.firstOrNull()?.path ?: return null
        return FsPath.join(FsPath.normalize(target), rommFsSlug.ifBlank { platform.id.value }) to true
    }

    /**
     * Where each of [files] goes inside [folder]: a game of one file sits in the system folder; a game
     * of several keeps RomM's layout in a folder of its own (so its DLC and updates sit in `dlc/` and
     * `update/`, which Fuse's scan reads as that game's content).
     */
    fun layout(rom: RommRom, files: List<RommFile>, folder: String, existingGamePath: String? = null): Placement {
        val multi = rom.multi || rom.files.size > 1
        val base = FsPath.normalize(folder)
        if (!multi && files.size == 1) {
            val path = FsPath.join(base, safeName(files.single().name))
            return Placement(base, false, mapOf(files.single().id to path), path)
        }
        val gameDir = existingGamePath?.let(FsPath::normalize) ?: FsPath.join(base, safeName(rom.fsName.ifBlank { rom.name }))
        val map = files.associate { f ->
            val inner = f.path.split('/').filter { it.isNotEmpty() && it != "." && it != ".." }.map(::safeName)
            f.id to (if (inner.isEmpty()) FsPath.join(gameDir, safeName(f.name)) else FsPath.join(gameDir, *inner.toTypedArray(), safeName(f.name)))
        }
        return Placement(base, false, map, gameDir)
    }

    /** A file or folder name safe on every system Fuse runs on. */
    fun safeName(name: String): String {
        val cleaned = name.map { c -> if (c in "<>:\"/\\|?*" || c.code < 32) '_' else c }.joinToString("").trim().trimEnd('.')
        return cleaned.ifEmpty { "_" }.take(240)
    }
}

/**
 * One BIOS file Fuse would fetch from RomM, and where it goes. [install] is set for firmware an
 * emulator installs itself (a PS3 update, Vita firmware): the words saying how, since Fuse can only
 * bring the file and point at it.
 */
data class BiosPick(val firmware: RommFirmware, val platform: String, val destination: String, val why: String, val install: String? = null)

/**
 * Which of a server's BIOS and firmware files this device needs: for each system it plays whose
 * firmware Fuse found missing or partial, the server's files that are what is missing (by name,
 * then checked by size and hash where Fuse knows them). A file already there is never replaced.
 * Firmware an emulator installs itself (a PS3 update, Vita firmware) is brought to [installerFolder]
 * for that emulator's own installer, never into its storage; without a folder it is left out.
 */
object RommBios {
    fun needed(
        platforms: List<Platform>,
        statusOf: (Platform) -> BiosStatus?,
        firmware: List<RommFirmware>,
        rommPlatformOf: (Long) -> String?,
        resolveSlug: (String) -> Platform?,
        destinationFor: (Platform, BiosFile) -> String?,
        exists: (String) -> Boolean,
        all: Boolean = false,
        installerFolder: (Platform) -> String? = { null },
    ): List<BiosPick> {
        val out = ArrayList<BiosPick>()
        val bySystem = firmware.groupBy { f -> rommPlatformOf(f.platformId)?.let { resolveSlug(it)?.id?.value } }
        for (p in platforms) {
            val req = p.bios ?: continue
            val status = statusOf(p)
            if (req.installedInEmulator) {
                // Fuse can't see inside the emulator, so it can't know this is missing: offered when
                // asked for everything, or when Fuse's check says so (the person marked it otherwise).
                if (!all && status?.state != BiosState.MISSING) continue
                val folder = installerFolder(p) ?: continue
                for (file in req.files) {
                    val fw = bySystem[p.id.value].orEmpty().firstOrNull { fits(it, file) } ?: continue
                    val dest = folder.trimEnd('/', '\\') + "/" + fw.fileName
                    if (exists(dest)) continue
                    out += BiosPick(fw, p.id.value, dest, "For ${p.name}, installed from the emulator", install = req.hint.ifBlank { "Install it from the emulator's menu." })
                }
                continue
            }
            val wanted = when {
                all -> req.files
                status == null || status.state == BiosState.READY || status.state == BiosState.NOT_REQUIRED -> continue
                else -> req.files.filter { f -> status.missing.any { it.equals(f.name, ignoreCase = true) } }.ifEmpty { req.files }
            }
            val available = bySystem[p.id.value].orEmpty()
            for (file in wanted) {
                val fw = available.firstOrNull { fits(it, file) } ?: continue
                val dest = destinationFor(p, file) ?: continue
                if (exists(dest)) continue
                out += BiosPick(fw, p.id.value, dest, if (all) "Available for ${p.name}" else "Missing for ${p.name}")
            }
        }
        return out.distinctBy { it.destination }
    }

    /** Whether the server's [fw] is the [file] a system needs: same name (or alias), and the hash when Fuse knows good ones. */
    fun fits(fw: RommFirmware, file: BiosFile): Boolean {
        val names = (setOf(file.name) + file.aliases).map { it.lowercase() }
        val nameOk = names.any { n -> if ('*' in n || '?' in n) glob(n).matches(fw.fileName.lowercase()) else n == fw.fileName.lowercase() }
        if (!nameOk) return false
        if (file.minSize != null && fw.sizeBytes in 1 until file.minSize!!) return false
        // Known good dumps: a server copy whose hash is known must be one of them.
        if (file.md5.isNotEmpty() && fw.md5 != null && fw.md5.lowercase() !in file.md5) return false
        return true
    }

    private fun glob(p: String): Regex = Regex(p.map { c -> when (c) { '*' -> ".*"; '?' -> "."; else -> Regex.escape(c.toString()) } }.joinToString(""))
}
