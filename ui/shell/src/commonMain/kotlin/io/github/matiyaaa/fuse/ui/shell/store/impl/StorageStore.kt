package io.github.matiyaaa.fuse.ui.shell.store.impl

import io.github.matiyaaa.fuse.library.FsPath
import io.github.matiyaaa.fuse.library.storage.GameFiles
import io.github.matiyaaa.fuse.library.storage.Volumes
import io.github.matiyaaa.fuse.model.GameId
import io.github.matiyaaa.fuse.model.LibrarySource
import io.github.matiyaaa.fuse.model.LibrarySourceId
import io.github.matiyaaa.fuse.model.LibrarySourceKind
import io.github.matiyaaa.fuse.model.SourceState
import io.github.matiyaaa.fuse.model.SourceStatus
import io.github.matiyaaa.fuse.model.StorageVolume
import io.github.matiyaaa.fuse.model.VolumeKind
import io.github.matiyaaa.fuse.ui.shell.store.DeleteReport
import io.github.matiyaaa.fuse.ui.shell.store.GameSize
import io.github.matiyaaa.fuse.ui.shell.store.MoveProgress
import io.github.matiyaaa.fuse.ui.shell.store.MoveReport
import io.github.matiyaaa.fuse.ui.shell.store.MoveTarget
import io.github.matiyaaa.fuse.ui.shell.store.StorageOps
import io.github.matiyaaa.fuse.ui.shell.store.StorageUsage
import io.github.matiyaaa.fuse.ui.shell.store.SystemShare
import io.github.matiyaaa.fuse.ui.shell.store.VolumeUsage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

internal class DefaultStorageOps(
    private val ctx: StoreContext,
    private val drives: Drives,
    private val sources: io.github.matiyaaa.fuse.ui.shell.store.SourceOps? = null,
) : StorageOps {
    private val state = MutableStateFlow<StorageUsage?>(null)
    override val usage: StateFlow<StorageUsage?> = state
    private var job: Job? = null

    override fun refresh() {
        job?.cancel()
        job = ctx.scope.launch {
            drives.refresh()
            val statuses = drives.status.value
            val mounted = drives.volumes.value
            val sources = ctx.data.sources.all().filter { it.enabled }
            // Android games are apps: Android's own storage settings measure them.
            val summaries = ctx.data.games.observeAll().first().filterNot { it.isApp }
            val cards = ctx.cardsOnce(summaries).associateBy { it.id }
            val driveOf = statuses.associate { it.source.id to (it.volume?.id ?: it.source.volume?.id) }
            val sizes = ArrayList<GameSize>()
            val sourceOf = HashMap<GameId, LibrarySourceId>()
            val legacy = if (mounted.isEmpty()) volumesOf(sources) else emptyList()
            fun publish(finished: Boolean) {
                state.value = StorageUsage(
                    volumes = if (mounted.isEmpty()) {
                        legacy.map { v -> v.withGames(sizes.filter { sourceOf[it.card.id] in v.sources }) }
                    } else {
                        driveUsage(mounted, statuses, sizes)
                    },
                    games = sizes.sortedByDescending { it.bytes },
                    measured = sizes.size,
                    total = summaries.size,
                    finished = finished,
                )
            }
            publish(finished = summaries.isEmpty())
            for ((i, summary) in summaries.withIndex()) {
                val game = ctx.data.games.get(summary.id) ?: continue
                val card = cards[summary.id] ?: continue
                sourceOf[game.id] = game.location.sourceId
                val drive = driveOf[game.location.sourceId] ?: Volumes.locate(game.location.path, mounted)?.first?.id
                if (card.unavailable != null) {
                    // Its drive is out: what the last scan saw, without touching the missing files.
                    sizes += GameSize(card, game.location.sizeBytes, maxOf(1, game.discs.size), drive, lastKnown = true)
                    continue
                }
                val set = try {
                    GameFiles.resolve(ctx.services.fs, game.location, game.discs)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    null
                } ?: continue
                sizes += GameSize(card, set.sizeBytes, set.fileCount, drive)
                if (i % 25 == 24) publish(finished = false)
            }
            publish(finished = true)
        }
    }

    /**
     * Every drive that holds a library folder or a measured game: connected ones with their real
     * size and free space, then the ones that are out, as they were last seen.
     */
    private fun driveUsage(mounted: List<StorageVolume>, statuses: List<SourceStatus>, sizes: List<GameSize>): List<VolumeUsage> {
        val ids = LinkedHashSet<String>()
        statuses.filter { it.source.enabled }.forEach { s -> (s.volume?.id ?: s.source.volume?.id)?.let(ids::add) }
        sizes.mapNotNullTo(ids) { it.volumeId }
        val byDrive = sizes.groupBy { it.volumeId }
        val out = ids.mapNotNull { id ->
            val games = byDrive[id].orEmpty()
            val volume = mounted.firstOrNull { it.id == id }
            if (volume != null) {
                val (free, total) = if (volume.totalBytes > 0) volume.freeBytes to volume.totalBytes else volumeSpace(volume.mountPath) ?: (0L to 0L)
                VolumeUsage(
                    label = volume.label, totalBytes = total, freeBytes = free, gamesBytes = games.sumOf { it.bytes },
                    systems = sharesOf(games), id = id, kind = volume.kind, removable = volume.removable, online = true,
                    games = games.size, readOnly = volume.readOnly, mountPath = volume.mountPath, fsType = volume.fsType,
                )
            } else {
                val ref = statuses.firstOrNull { it.source.volume?.id == id }?.source?.volume ?: return@mapNotNull null
                VolumeUsage(
                    label = ref.label, totalBytes = 0, freeBytes = 0, gamesBytes = games.sumOf { it.bytes },
                    systems = sharesOf(games), id = id, kind = ref.kind, removable = ref.removable, online = false,
                    lastSeenAt = ref.lastSeenAt, games = games.size,
                )
            }
        }
        // The device's own storage first, other connected drives by name, drives that are out last.
        return out.sortedWith(compareBy<VolumeUsage>({ !it.online }, { it.kind != VolumeKind.INTERNAL }, { it.label.lowercase() }))
    }

    private fun sharesOf(games: List<GameSize>): List<SystemShare> =
        games.groupBy { it.card.platformId }.map { (platform, list) ->
            SystemShare(platform, ctx.platformName(platform), list.first().card.accent, list.sumOf { it.bytes })
        }.sortedByDescending { it.bytes }

    override suspend fun size(game: GameId): Long? {
        val g = ctx.data.games.get(game) ?: return null
        return runCatching { GameFiles.resolve(ctx.services.fs, g.location, g.discs) }.getOrNull()?.takeIf { !it.isEmpty }?.sizeBytes
    }

    override suspend fun delete(games: List<GameId>): DeleteReport {
        val sources = ctx.data.sources.all().associateBy { it.id }
        var freed = 0L
        val failed = ArrayList<String>()
        val gone = HashSet<GameId>()
        // Files are only deleted where Fuse is sure which drive it is looking at.
        drives.refresh()
        val readable = drives.status.value.filter { it.state == SourceState.ONLINE }.map { it.source.id }.toSet()
        for (id in games.distinct()) {
            val game = ctx.data.games.get(id) ?: continue
            if (game.location.sourceId !in readable) {
                failed += game.displayTitle
                continue
            }
            val root = sources[game.location.sourceId]?.path?.let(FsPath::normalize)
            val set = runCatching { GameFiles.resolve(ctx.services.fs, game.location, game.discs) }.getOrNull()
            // Only files inside the game's own library folder, and never the folder itself.
            val safe = root != null && set != null && set.paths.all { inside(it, root) }
            if (!safe) {
                failed += game.displayTitle
                continue
            }
            val ok = set!!.paths.all { ctx.services.fs.delete(it) }
            if (ok) {
                gone += id
                freed += set.sizeBytes
                ctx.data.games.forgetDeleted(id, ctx.now())
            } else {
                failed += game.displayTitle
            }
        }
        // The list follows at once, then the drives are measured again.
        state.value = state.value?.let { u -> u.copy(games = u.games.filter { it.card.id !in gone }) }
        if (gone.isNotEmpty()) refresh()
        return DeleteReport(gone.size, freed, failed)
    }

    // Moving games -------------------------------------------------------------------------------

    private val moveState = MutableStateFlow<MoveProgress?>(null)
    override val moving: StateFlow<MoveProgress?> = moveState

    @kotlin.concurrent.Volatile private var cancelled = false

    override fun cancelMove() {
        cancelled = true
    }

    override suspend fun moveTargets(): List<MoveTarget> {
        drives.refresh()
        val statuses = drives.status.value
        return drives.volumes.value.filter { !it.readOnly && it.mountPath.isNotEmpty() }.map { v ->
            val folder = statuses.firstOrNull { s ->
                s.volume?.id == v.id && s.state == SourceState.ONLINE && s.source.enabled && s.source.kind == LibrarySourceKind.ROMS_ROOT
            }?.source?.path
            MoveTarget(
                volumeId = v.id,
                label = v.label,
                kind = v.kind,
                freeBytes = ctx.services.fs.freeSpace(folder ?: v.mountPath) ?: v.freeBytes,
                gamesFolder = folder,
                removable = v.removable,
            )
        }
    }

    override suspend fun driveOf(game: GameId): String? {
        val g = ctx.data.games.get(game) ?: return null
        val status = drives.status.value.firstOrNull { it.source.id == g.location.sourceId }
        return status?.volume?.id ?: status?.source?.volume?.id ?: Volumes.locate(g.location.path, drives.volumes.value)?.first?.id
    }

    override suspend fun makeGamesFolder(volumeId: String): String? {
        val v = drives.volumes.value.firstOrNull { it.id == volumeId } ?: return null
        val path = FsPath.join(v.mountPath, GAMES_FOLDER)
        if (!ctx.services.fs.makeDirs(path)) return null
        return sources?.add(path, LibrarySourceKind.ROMS_ROOT)?.path ?: path
    }

    override suspend fun setUpDrive(volumeId: String, at: String?): io.github.matiyaaa.fuse.ui.shell.store.DriveSetup? {
        drives.refresh()
        val v = drives.volumes.value.firstOrNull { it.id == volumeId } ?: return null
        val base = FsPath.join(at?.let(FsPath::normalize) ?: v.mountPath, SETUP_FOLDER)
        val roms = FsPath.join(base, "ROMs")
        // Lower case, as Fuse's firmware check looks for it beside a ROMs folder.
        val bios = FsPath.join(base, "bios")
        val fs = ctx.services.fs
        if (!fs.makeDirs(roms) || !fs.makeDirs(bios)) return null
        // The systems already in the library, then the ones most people play.
        val inLibrary = runCatching { ctx.data.games.platformCounts().first().keys }.getOrDefault(emptySet())
        val systems = (inLibrary.map { it.value } + SETUP_SYSTEMS).distinct().mapNotNull { id -> ctx.platform(io.github.matiyaaa.fuse.model.PlatformId(id)) }
            .filter { !it.isAppPlatform() }
        var made = 0
        for (p in systems) {
            if (fs.makeDirs(FsPath.join(roms, p.id.value))) made++
            // Fuse looks for firmware in bios/<system> beside a ROMs folder.
            if (p.bios != null) fs.makeDirs(FsPath.join(bios, p.id.value))
        }
        val source = sources?.add(roms, LibrarySourceKind.ROMS_ROOT)
        return io.github.matiyaaa.fuse.ui.shell.store.DriveSetup(source?.path ?: roms, made)
    }

    /** Android apps and PC shortcuts live elsewhere; they get no ROM folder. */
    private fun io.github.matiyaaa.fuse.model.Platform.isAppPlatform(): Boolean = id.value in setOf("android", "steam", "windows", "pc", "linux", "macos")

    override suspend fun move(games: List<GameId>, volumeId: String): MoveReport {
        val target = moveTargets().firstOrNull { it.volumeId == volumeId }
        val root = target?.gamesFolder ?: return MoveReport(0, 0, games.mapNotNull { ctx.data.games.get(it)?.displayTitle }, "There is no games folder on that drive yet")
        val rootSource = ctx.data.sources.all().firstOrNull { FsPath.normalize(it.path) == FsPath.normalize(root) }
            ?: return MoveReport(0, 0, games.mapNotNull { ctx.data.games.get(it)?.displayTitle }, "The games folder on that drive isn't in the library")
        val fs = ctx.services.fs
        val readable = drives.status.value.filter { it.state == SourceState.ONLINE }.map { it.source.id }.toSet()
        val allSources = ctx.data.sources.all().associateBy { it.id }

        /** One game ready to go: what to copy, from where to where. */
        class Plan(val id: GameId, val title: String, val card: io.github.matiyaaa.fuse.ui.shell.store.GameCard?, val base: String, val destBase: String, val folder: String, val paths: List<String>, val bytes: Long)
        val failed = ArrayList<String>()
        val plans = ArrayList<Plan>()
        // The system folders already on the drive, so a game joins "PSX" there rather than making "psx" beside it.
        val existing = runCatching { fs.list(root) }.getOrDefault(emptyList()).filter { it.isDirectory }
        fun systemFolderFor(platform: io.github.matiyaaa.fuse.model.PlatformId, name: String): String =
            existing.firstOrNull { it.name.equals(name, ignoreCase = true) }?.path
                ?: existing.firstOrNull { ctx.platforms.resolveFolder(it.name)?.id == platform }?.path
                ?: FsPath.join(root, name)
        for (id in games.distinct()) {
            val game = ctx.data.games.get(id) ?: continue
            val summary = ctx.data.games.summary(id)
            val title = game.displayTitle
            val sourceRoot = allSources[game.location.sourceId]?.path?.let(FsPath::normalize)
            val set = runCatching { GameFiles.resolve(fs, game.location, game.discs) }.getOrNull()
            val base = FsPath.parent(FsPath.normalize(game.location.path))
            val systemFolder = summary?.folderPath?.let(FsPath::normalize)
            // Only whole games, read where Fuse is sure of the drive, all inside their own system folder.
            val ok = game.location.sourceId in readable && sourceRoot != null && set != null && !set.isEmpty && base != null &&
                systemFolder != null && (base == systemFolder || inside(base, systemFolder)) && set.paths.all { inside(it, base) } &&
                game.location.sourceId != rootSource.id
            if (!ok) {
                failed += title
                continue
            }
            val destFolder = FsPath.normalize(systemFolderFor(game.scannedPlatformId ?: game.platformId, FsPath.name(systemFolder!!)))
            val below = base!!.removePrefix(systemFolder).trim('/')
            val destBase = if (below.isEmpty()) destFolder else FsPath.join(destFolder, below)
            plans += Plan(id, title, ctx.cardsOnce(listOfNotNull(summary)).firstOrNull(), base, destBase, destFolder, set!!.paths.map(FsPath::normalize), set.sizeBytes)
        }
        val total = plans.sumOf { it.bytes }
        val free = fs.freeSpace(root) ?: target.freeBytes
        if (free in 1 until total + MOVE_HEADROOM) {
            return MoveReport(0, 0, plans.map { it.title } + failed, "That drive has ${io.github.matiyaaa.fuse.ui.shell.home.bytesText(free)} free and these games need ${io.github.matiyaaa.fuse.ui.shell.home.bytesText(total)}")
        }
        cancelled = false
        var done = 0L
        var moved = 0
        var movedBytes = 0L
        try {
            for ((i, plan) in plans.withIndex()) {
                if (cancelled) {
                    failed += plans.drop(i).map { it.title }
                    break
                }
                moveState.value = MoveProgress(plan.title, i, plans.size, done, total, target.label, plan.card)
                val copied = ArrayList<String>()
                var whole = true
                for (p in plan.paths) {
                    val to = FsPath.join(plan.destBase, p.removePrefix(plan.base).trim('/'))
                    val ok = fs.copy(p, to) { n ->
                        done += n
                        moveState.value = moveState.value?.copy(doneBytes = done)
                    }
                    if (!ok) {
                        whole = false
                        break
                    }
                    copied += to
                }
                // A copy that isn't whole goes again; the game stays where it was.
                if (!whole) {
                    copied.forEach { fs.delete(it) }
                    failed += plan.title
                    continue
                }
                // Everything of the game sat under its folder and keeps its place below it, so the record moves by that folder.
                if (!ctx.data.games.relocate(plan.id, rootSource.id, plan.folder, plan.base, plan.destBase, ctx.now())) {
                    copied.forEach { fs.delete(it) }
                    failed += plan.title
                    continue
                }
                plan.paths.forEach { fs.delete(it) }
                moved++
                movedBytes += plan.bytes
            }
        } finally {
            moveState.value = null
        }
        if (moved > 0) {
            sources?.rescan(io.github.matiyaaa.fuse.model.ScanScope.QUICK)
            refresh()
        }
        return MoveReport(moved, movedBytes, failed)
    }

    private fun inside(path: String, root: String): Boolean {
        val p = FsPath.normalize(path)
        return p != root && p.startsWith(root.trimEnd('/') + "/")
    }

    private companion object {
        /** The folder made at the top of a drive for games moved there. */
        const val GAMES_FOLDER = "ROMs"

        /** The folder a drive set up for games gets, with ROMs and BIOS inside. */
        const val SETUP_FOLDER = "Emulation"

        /** Systems a freshly set up drive gets folders for, besides those already in the library. */
        val SETUP_SYSTEMS = listOf(
            "nes", "snes", "n64", "gb", "gbc", "gba", "nds", "3ds", "gc", "wii", "switch",
            "psx", "ps2", "psp", "psvita", "genesis", "mastersystem", "saturn", "dreamcast", "arcade",
        )

        /** Space left free on the drive after a move, so it never fills to the last byte. */
        const val MOVE_HEADROOM = 256L * 1024 * 1024
    }

    /** A drive with the library folders on it. */
    private data class Volume(val label: String, val total: Long, val free: Long, val sources: Set<LibrarySourceId>)

    /**
     * Where the system names no drives (tests, unusual hosts): library folders on the same drive
     * (same size and free space) count once.
     */
    private fun volumesOf(sources: List<LibrarySource>): List<Volume> =
        sources.mapNotNull { s -> volumeSpace(s.path)?.let { (free, total) -> Triple(s, free, total) } }
            .groupBy { (_, free, total) -> free to total }
            .map { (space, members) ->
                Volume(
                    label = members.joinToString(", ") { it.first.label },
                    total = space.second,
                    free = space.first,
                    sources = members.map { it.first.id }.toSet(),
                )
            }

    private fun Volume.withGames(games: List<GameSize>): VolumeUsage =
        VolumeUsage(label, total, free, games.sumOf { it.bytes }, sharesOf(games), games = games.size)
}
