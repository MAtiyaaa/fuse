package io.github.matiyaaa.fuse.library.content

import io.github.matiyaaa.fuse.library.FsPath

/** What one file is to its game. */
enum class ItemRole { LICENCE, GAME, UPDATE, DLC }

/** Where an item stands. */
enum class ItemStatus {
    /** In the emulator already (read from its storage, or installed and checked by Fuse). */
    INSTALLED,

    /** Ready to install. */
    READY,

    /** Can't install until its licence is found. */
    NEEDS_LICENCE,

    /** An older update than the one already installed, or a duplicate: nothing to do. */
    SUPERSEDED,

    /** For another console, or a kind the emulator doesn't install. */
    UNSUPPORTED,
}

/** Where an item's licence comes from. */
enum class LicenceSource {
    /** Already in the emulator (RPCS3 exdata, Vita3K ux0/license). */
    INSTALLED,

    /** A file beside the game, under its right name. */
    NAMED,

    /** The only unnamed `.rap` beside the game, for the only package missing one: copied under the right name. */
    RENAMED,

    /** A file the user picked. */
    PICKED,

    /** A zRIF found in a text file beside the game. */
    ZRIF,

    /** A Vita `.rif`/`work.bin` beside the game, turned into the zRIF Vita3K takes. */
    RIF,

    /** Inside the game itself (a NoNpDrm `.vpk` with `work.bin`, homebrew): nothing to add. */
    BUILT_IN,
}

data class LicenceMatch(
    val contentId: String,
    val source: LicenceSource,
    /** The licence file (copied into the emulator for PS3), when there is one. */
    val path: String? = null,
    /** The key Vita3K takes for a `.pkg`, kept in memory only. */
    val zrif: String? = null,
)

/** One step of an install: a file, what it is, where it stands, and its licence. */
data class PlanItem(
    val role: ItemRole,
    val path: String,
    val titleId: String?,
    val contentId: String?,
    val version: String?,
    val status: ItemStatus,
    val licence: LicenceMatch? = null,
    val sizeBytes: Long = 0,
    /** For a licence step: the name it has to have in the emulator (`<contentId>.rap`). */
    val installAs: String? = null,
    /** A Vita `.vpk`/`.zip` (installed by giving it to Vita3K as its game) rather than a `.pkg`. */
    val archive: Boolean = false,
    /** Why it can't install, for [ItemStatus.NEEDS_LICENCE] and [ItemStatus.UNSUPPORTED]. */
    val why: String? = null,
) {
    val fileName: String get() = FsPath.name(path)
    val needsWork: Boolean get() = status == ItemStatus.READY
}

/** The states a game page shows, in this order. */
enum class ContentState(val label: String) {
    READY("Ready to play"),
    NEEDS_INSTALL("Needs installation"),
    MISSING_LICENCE("Missing licence"),
    UPDATE_AVAILABLE("Update available locally"),
    DLC_AVAILABLE("DLC available locally"),
}

/**
 * Everything to install for one game in one emulator, in the order it has to go in: licences,
 * then the game, then its updates from oldest to newest (PS3 updates install one over the other),
 * then its DLC. Items already in the emulator stay listed as [ItemStatus.INSTALLED].
 */
data class ContentPlan(
    val emulator: ContentEmulator,
    val titleIds: Set<String>,
    val items: List<PlanItem>,
    /** Whether the emulator's storage could be read (if not, "installed" can't be known). */
    val storageReadable: Boolean,
    /** True when the game itself is in the emulator, ready to start by title id. */
    val gameInstalled: Boolean,
    /** True when the game is a disc or folder the emulator starts directly (no base package needed). */
    val playsWithoutInstall: Boolean = false,
) {
    val toInstall: List<PlanItem> get() = items.filter { it.needsWork }
    val missingLicences: List<PlanItem> get() = items.filter { it.status == ItemStatus.NEEDS_LICENCE }

    val states: List<ContentState>
        get() = buildList {
            val gameItem = items.firstOrNull { it.role == ItemRole.GAME }
            val gameLicenceMissing = gameItem?.status == ItemStatus.NEEDS_LICENCE
            if ((gameInstalled || playsWithoutInstall) && !gameLicenceMissing) add(ContentState.READY)
            if (!gameInstalled && gameItem != null && gameItem.status != ItemStatus.UNSUPPORTED) add(ContentState.NEEDS_INSTALL)
            if (missingLicences.isNotEmpty()) add(ContentState.MISSING_LICENCE)
            if (items.any { it.role == ItemRole.UPDATE && (it.status == ItemStatus.READY || it.status == ItemStatus.NEEDS_LICENCE) }) add(ContentState.UPDATE_AVAILABLE)
            if (items.any { it.role == ItemRole.DLC && (it.status == ItemStatus.READY || it.status == ItemStatus.NEEDS_LICENCE) }) add(ContentState.DLC_AVAILABLE)
        }
}

/**
 * Works out a [ContentPlan] from what is on disk ([ContentSources]) and what the emulator already
 * holds ([InstalledContent]). Pure: no file is read or written here.
 *
 * Licences are matched by content id, the way the emulators look for them: RPCS3 boots a PSN game
 * only with `exdata/<contentId>.rap` (main_window.cpp InstallFileInExData copies under the file's
 * own name), and Vita3K decrypts with the zRIF whose licence carries the package's content id.
 */
object ContentPlanner {
    /**
     * @param recorded Files Fuse installed earlier and checked (path to size), for content the
     *   emulator's storage can't tell apart (PS3 DLC goes into the game's own folder).
     * @param picked Licence files the user chose, by content id.
     * @param playsWithoutInstall True when the game is a disc image or folder the emulator starts as it is.
     */
    fun plan(
        emulator: ContentEmulator,
        sources: ContentSources,
        installed: InstalledContent,
        recorded: Map<String, Long> = emptyMap(),
        picked: Map<String, String> = emptyMap(),
        playsWithoutInstall: Boolean = false,
    ): ContentPlan = when (emulator) {
        ContentEmulator.RPCS3 -> ps3(sources, installed, recorded, picked, playsWithoutInstall)
        ContentEmulator.VITA3K -> vita(sources, installed, recorded, picked, playsWithoutInstall)
        ContentEmulator.AZAHAR -> threeDs(sources, installed, playsWithoutInstall)
    }

    /**
     * Nintendo 3DS: the game's CIA, then its updates oldest first, then its DLC, each installed by
     * Azahar (`-i`) into its SD card, where each one's own title folder shows it is in. A CIA needs
     * no licence (it has to be decrypted already; Azahar refuses encrypted ones).
     */
    private fun threeDs(sources: ContentSources, installed: InstalledContent, playsWithoutInstall: Boolean): ContentPlan {
        val items = ArrayList<PlanItem>()
        val order = compareBy<CiaFile>({ it.kind.ordinal }, { it.version.split('.').map { v -> v.toIntOrNull() ?: 0 }.fold(0L) { a, v -> a * 1000 + v } }, { it.fileName.lowercase() })
        for (c in sources.cias.sortedWith(order)) {
            val role = role(c.kind)
            if (role == null || role == ItemRole.LICENCE) {
                items += PlanItem(ItemRole.GAME, c.path, c.titleId, null, c.version, ItemStatus.UNSUPPORTED, sizeBytes = c.sizeBytes, why = "A system title, not a game, update or DLC")
                continue
            }
            val have = installed.games[c.titleId]
            val done = have != null && PsPackages.compareVersions(have.version, c.version) >= 0
            items += PlanItem(role, c.path, c.titleId, null, c.version, if (done) ItemStatus.INSTALLED else ItemStatus.READY, LicenceMatch(c.titleId, LicenceSource.BUILT_IN), c.sizeBytes)
        }
        val planned = supersede(items)
        val games = sources.cias.map { it.gameId }.toSet() + sources.cartridges.values
        val gameInstalled = games.any { installed.games[it] != null }
        return ContentPlan(ContentEmulator.AZAHAR, games, planned, installed.readable, gameInstalled, playsWithoutInstall || sources.cartridges.isNotEmpty())
    }

    private fun ps3(
        sources: ContentSources,
        installed: InstalledContent,
        recorded: Map<String, Long>,
        picked: Map<String, String>,
        playsWithoutInstall: Boolean,
    ): ContentPlan {
        val pkgs = sources.packages.filter { it.system == PsSystem.PS3 }
        val titleIds = pkgs.mapNotNull { it.titleId }.toSet()
        val raps = sources.licences.filter { it.kind == LicenceKind.RAP }
        val named = raps.filter { it.contentId != null }.associateBy { it.contentId!! }
        val needing = pkgs.filter { it.needsLicence && it.kind != PackageKind.UNSUPPORTED && it.kind != PackageKind.EXTRA }.map { it.contentId.uppercase() }.distinct()
        val matches = LinkedHashMap<String, LicenceMatch>()
        for (cid in needing) {
            matches[cid] = when {
                picked[cid] != null -> LicenceMatch(cid, LicenceSource.PICKED, picked.getValue(cid))
                // Already in RPCS3: nothing to copy, so a retry never copies it twice.
                installed.hasLicence(cid) -> LicenceMatch(cid, LicenceSource.INSTALLED)
                named[cid] != null -> LicenceMatch(cid, LicenceSource.NAMED, named.getValue(cid).path)
                else -> continue
            }
        }
        // One unnamed .rap and one package still missing its licence: that is its licence (Cartridge's rule too).
        val loose = raps.filter { it.contentId == null || it.contentId !in needing }
        val missing = needing.filter { it !in matches }
        if (missing.size == 1 && loose.size == 1) matches[missing[0]] = LicenceMatch(missing[0], LicenceSource.RENAMED, loose[0].path)

        val items = ArrayList<PlanItem>()
        // Licence steps: each needed .rap that isn't in RPCS3 yet, under the name RPCS3 looks for.
        for (m in matches.values) {
            if (m.source == LicenceSource.INSTALLED || m.path == null) continue
            items += PlanItem(ItemRole.LICENCE, m.path, m.contentId.substring(7, 16), m.contentId, null, ItemStatus.READY, m, Licences.RAP_SIZE, installAs = "${m.contentId}.rap")
        }
        // .edat licences beside the game (RPCS3 copies them into exdata too).
        for (e in sources.licences.filter { it.kind == LicenceKind.EDAT && it.contentId != null }) {
            val done = installed.hasLicence(e.contentId!!) || recorded[e.path] != null
            items += PlanItem(ItemRole.LICENCE, e.path, e.titleId, e.contentId, null, if (done) ItemStatus.INSTALLED else ItemStatus.READY, installAs = FsPath.name(e.path))
        }
        for (p in pkgs.sortedWith(order)) {
            val role = role(p.kind) ?: continue
            val cid = p.contentId.uppercase()
            val licence = matches[cid]
            val inGame = p.titleId?.let { installed.games[it] }
            val already = when (role) {
                // A disc game's update leaves a "GD" folder; that isn't the game itself.
                ItemRole.GAME -> inGame != null && inGame.category?.uppercase() != "GD"
                ItemRole.UPDATE -> p.version?.let { v -> installed.versionOf(p.titleId ?: "")?.let { PsPackages.compareVersions(it, v) >= 0 } }
                    ?: (recorded[p.path] == p.sizeBytes && inGame != null)
                ItemRole.DLC -> recorded[p.path] == p.sizeBytes && inGame != null
                ItemRole.LICENCE -> installed.hasLicence(cid)
            }
            val status = when {
                p.titleId == null -> ItemStatus.UNSUPPORTED
                already == true -> ItemStatus.INSTALLED
                p.needsLicence && licence == null -> ItemStatus.NEEDS_LICENCE
                else -> ItemStatus.READY
            }
            items += PlanItem(
                role, p.path, p.titleId, cid, p.version, status, licence, p.sizeBytes,
                why = when (status) {
                    ItemStatus.NEEDS_LICENCE -> "Needs $cid.rap"
                    ItemStatus.UNSUPPORTED -> "The package names no title id"
                    else -> null
                },
            )
        }
        val planned = supersede(items)
        val gameInstalled = titleIds.any { id -> installed.games[id]?.let { it.category?.uppercase() != "GD" } == true }
        return ContentPlan(ContentEmulator.RPCS3, titleIds, planned, installed.readable, gameInstalled, playsWithoutInstall)
    }

    private fun vita(
        sources: ContentSources,
        installed: InstalledContent,
        recorded: Map<String, Long>,
        picked: Map<String, String>,
        playsWithoutInstall: Boolean,
    ): ContentPlan {
        val pkgs = sources.packages.filter { it.system == PsSystem.VITA }
        val titleIds = (pkgs.mapNotNull { it.titleId } + sources.archives.map { it.titleId }).toSet()
        val keys = sources.keys.associateBy { it.contentId.uppercase() }
        val rifs = sources.licences.filter { it.kind == LicenceKind.RIF && it.contentId != null && it.zrif != null }.associateBy { it.contentId!!.uppercase() }
        val gameContent = pkgs.filter { it.kind == PackageKind.GAME }.associate { it.titleId to it.contentId.uppercase() }

        fun licenceFor(p: PsPackage): LicenceMatch? {
            if (!p.needsLicence) return LicenceMatch(p.contentId, LicenceSource.BUILT_IN)
            val wanted = buildList {
                add(p.contentId.uppercase())
                // An update is decrypted with the game's licence (Vita3K's find_pkg_zrif looks under the
                // update's own content id, which is the game's).
                if (p.kind == PackageKind.UPDATE) gameContent[p.titleId]?.let(::add)
            }
            for (cid in wanted) {
                picked[cid]?.let { return LicenceMatch(cid, LicenceSource.PICKED, it) }
                keys[cid]?.let { return LicenceMatch(cid, LicenceSource.ZRIF, it.path, it.zrif) }
                rifs[cid]?.let { return LicenceMatch(cid, LicenceSource.RIF, it.path, it.zrif) }
                installed.licenceFiles[cid]?.let { return LicenceMatch(cid, LicenceSource.INSTALLED, it) }
            }
            return null
        }

        val items = ArrayList<PlanItem>()
        for (a in sources.archives.sortedBy { it.fileName.lowercase() }) {
            val role = when (a.category) {
                "gp" -> ItemRole.UPDATE
                "ac" -> ItemRole.DLC
                else -> ItemRole.GAME
            }
            val have = if (role == ItemRole.UPDATE) installed.patches[a.titleId] else installed.games[a.titleId]
            val done = have != null && (a.version == null || have.version == null || PsPackages.compareVersions(have.version, a.version) >= 0)
            items += PlanItem(
                role, a.path, a.titleId, null, a.version, if (done) ItemStatus.INSTALLED else ItemStatus.READY,
                LicenceMatch(a.titleId, LicenceSource.BUILT_IN), a.sizeBytes, archive = true,
            )
        }
        for (p in pkgs.sortedWith(order)) {
            val role = role(p.kind)
            if (role == null || role == ItemRole.LICENCE || p.titleId == null) {
                items += PlanItem(ItemRole.GAME, p.path, p.titleId, p.contentId, p.version, ItemStatus.UNSUPPORTED, sizeBytes = p.sizeBytes, why = "Vita3K installs games, updates, DLC and themes from packages")
                continue
            }
            val id = p.titleId
            val already = when (role) {
                ItemRole.GAME -> installed.games[id] != null
                ItemRole.UPDATE -> installed.patches[id]?.let { have -> p.version == null || have.version == null || PsPackages.compareVersions(have.version, p.version) >= 0 } == true
                ItemRole.DLC -> "$id/${p.contentId.takeLast(16)}" in installed.addons || (recorded[p.path] == p.sizeBytes && installed.games[id] != null)
                ItemRole.LICENCE -> false
            }
            val licence = licenceFor(p)
            val status = when {
                already -> ItemStatus.INSTALLED
                licence == null -> ItemStatus.NEEDS_LICENCE
                else -> ItemStatus.READY
            }
            items += PlanItem(
                role, p.path, id, p.contentId, p.version, status, licence, p.sizeBytes,
                why = if (status == ItemStatus.NEEDS_LICENCE) "Needs the zRIF for ${p.contentId}" else null,
            )
        }
        val planned = supersede(items.sortedWith(compareBy { it.role.ordinal }))
        val gameInstalled = titleIds.any { id ->
            installed.games[id] != null && (!id.startsWith("PCS") || id in installed.workBin || installed.licences.any { it.substring(7, 16) == id } ||
                planned.any { it.titleId == id && it.role == ItemRole.GAME && it.licence?.source == LicenceSource.BUILT_IN })
        }
        return ContentPlan(ContentEmulator.VITA3K, titleIds, planned, installed.readable, gameInstalled, playsWithoutInstall)
    }

    /**
     * Older updates than the newest one ready to install stay in the plan (PS3 updates are cumulative
     * only in order), but a second copy of the same file or version is marked superseded.
     */
    private fun supersede(items: List<PlanItem>): List<PlanItem> {
        val seen = HashSet<String>()
        return items.map { item ->
            val key = when (item.role) {
                ItemRole.UPDATE -> "u:${item.titleId}:${item.version ?: item.fileName}"
                ItemRole.GAME -> "g:${item.titleId}:${item.archive}"
                ItemRole.DLC -> "d:${item.contentId ?: item.fileName}"
                ItemRole.LICENCE -> "l:${item.installAs ?: item.fileName}"
            }
            if (item.status == ItemStatus.READY && !seen.add(key)) item.copy(status = ItemStatus.SUPERSEDED, why = "Same as another file") else item.also { seen += key }
        }
    }

    private fun role(kind: PackageKind): ItemRole? = when (kind) {
        PackageKind.GAME -> ItemRole.GAME
        PackageKind.UPDATE -> ItemRole.UPDATE
        PackageKind.DLC -> ItemRole.DLC
        PackageKind.LICENCE -> ItemRole.LICENCE
        PackageKind.EXTRA, PackageKind.UNSUPPORTED -> null
    }

    /** Game, then updates oldest to newest, then DLC; by name within each (numbers compared as numbers). */
    private val order: Comparator<PsPackage> = compareBy<PsPackage> {
        when (it.kind) {
            PackageKind.LICENCE -> 0
            PackageKind.GAME -> 1
            PackageKind.UPDATE -> 2
            PackageKind.DLC -> 3
            else -> 4
        }
    }.thenComparator { a, b -> if (a.kind == PackageKind.UPDATE) PsPackages.compareVersions(a.version, b.version) else 0 }
        .thenComparator { a, b -> naturalCompare(a.fileName, b.fileName) }

    /** Compares names with their numbers as numbers (`Update 2` before `Update 10`). */
    fun naturalCompare(a: String, b: String): Int {
        val x = Regex("\\d+|\\D+").findAll(a.lowercase()).map { it.value }.toList()
        val y = Regex("\\d+|\\D+").findAll(b.lowercase()).map { it.value }.toList()
        for (i in 0 until minOf(x.size, y.size)) {
            val p = x[i]
            val q = y[i]
            val c = if (p[0].isDigit() && q[0].isDigit()) {
                p.trimStart('0').length.compareTo(q.trimStart('0').length).takeIf { it != 0 } ?: p.trimStart('0').compareTo(q.trimStart('0'))
            } else {
                p.compareTo(q)
            }
            if (c != 0) return c
        }
        return x.size.compareTo(y.size)
    }
}
