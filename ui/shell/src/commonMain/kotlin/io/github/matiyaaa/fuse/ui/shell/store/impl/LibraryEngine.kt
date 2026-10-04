package io.github.matiyaaa.fuse.ui.shell.store.impl

import io.github.matiyaaa.fuse.library.FsPath
import io.github.matiyaaa.fuse.library.bios.BiosChecker
import io.github.matiyaaa.fuse.library.bios.BiosSearchPaths
import io.github.matiyaaa.fuse.library.parse.DisplayNameCleaner
import io.github.matiyaaa.fuse.library.scan.FolderPolicyResolver
import io.github.matiyaaa.fuse.library.scan.LibraryScanner
import io.github.matiyaaa.fuse.library.scan.ScanRequest
import io.github.matiyaaa.fuse.model.BiosStatus
import io.github.matiyaaa.fuse.model.FolderPolicy
import io.github.matiyaaa.fuse.model.LibrarySource
import io.github.matiyaaa.fuse.model.LibrarySourceId
import io.github.matiyaaa.fuse.model.LibrarySourceKind
import io.github.matiyaaa.fuse.model.PlatformId
import io.github.matiyaaa.fuse.model.ScanPhase
import io.github.matiyaaa.fuse.model.ScanProgress
import io.github.matiyaaa.fuse.model.ScanScope
import io.github.matiyaaa.fuse.model.ScopedSettings
import io.github.matiyaaa.fuse.model.SettingScope
import io.github.matiyaaa.fuse.model.SourceState
import io.github.matiyaaa.fuse.model.SourceStatus
import io.github.matiyaaa.fuse.model.StorageVolume
import io.github.matiyaaa.fuse.library.storage.Volumes
import io.github.matiyaaa.fuse.ui.shell.store.SourceOps
import io.github.matiyaaa.fuse.ui.shell.store.SuggestedSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Library sources, scanning and firmware checks. Scans are incremental (QUICK skips platform folders
 * whose modification times are unchanged), cancellable, and only ever read the file system.
 */
internal class LibraryEngine(private val ctx: StoreContext) : SourceOps {
    private val data = ctx.data
    private val scanner = LibraryScanner(ctx.services.fs, data.folderState, ctx.platforms)
    private var biosJob: Job? = null

    override val sources: StateFlow<List<LibrarySource>> =
        data.sources.observeAll().resilient().stateIn(ctx.scope, SharingStarted.Eagerly, emptyList())

    private val scanState = MutableStateFlow(ScanProgress(ScanPhase.IDLE))
    override val scan: StateFlow<ScanProgress> = scanState

    /** Platform folders found by the last scans, by platform. */
    val platformFolders = MutableStateFlow<Map<PlatformId, List<String>>>(emptyMap())

    /** Games each scan added, as it finishes: they are identified and filled first. */
    val added = MutableSharedFlow<List<io.github.matiyaaa.fuse.model.GameId>>(extraBufferCapacity = 16)

    /** Firmware status of platforms that need firmware and have games. */
    val bios = MutableStateFlow<Map<PlatformId, BiosStatus>>(emptyMap())

    val drives = Drives(ctx)
    override val status: StateFlow<List<SourceStatus>> get() = drives.status
    override val volumes: StateFlow<List<StorageVolume>> get() = drives.volumes
    private var driveWatch: AutoCloseable? = null
    private val driveChanges = Channel<Unit>(Channel.CONFLATED)

    override fun refreshDrives() {
        driveChanges.trySend(Unit)
    }

    override suspend fun adoptDrive(source: LibrarySourceId): Boolean {
        val status = drives.status.value.firstOrNull { it.source.id == source } ?: return false
        val volume = status.volume ?: return false
        if (status.state != SourceState.OTHER_DRIVE) return false
        val path = FsPath.normalize(status.source.path)
        val relative = Volumes.relativeTo(path, FsPath.normalize(volume.mountPath)) ?: return false
        data.sources.setVolume(source, Volumes.refFor(status.source, volume, relative, ctx.now()))
        drives.refresh()
        rescan(ScanScope.QUICK)
        return true
    }

    override suspend fun add(path: String, kind: LibrarySourceKind): LibrarySource? {
        val normalized = FsPath.normalize(path.trim())
        if (normalized.isEmpty()) return null
        data.sources.all().firstOrNull { FsPath.normalize(it.path) == normalized }?.let { return it }
        val label = FsPath.name(normalized).ifBlank { normalized }
        val id = data.sources.add(normalized, label, kind)
        rescan(ScanScope.QUICK)
        return data.sources.get(id)
    }

    override suspend fun findSteamGames(extra: String?): List<io.github.matiyaaa.fuse.library.steam.SteamGame> {
        val reader = io.github.matiyaaa.fuse.library.steam.SteamLibraryReader(ctx.services.fs)
        val places = runCatching { ctx.services.locations.steamRoots() }.getOrDefault(io.github.matiyaaa.fuse.ui.shell.store.SteamPlaces())
        // A picked folder may be the library, its steamapps folder, or a Steam install.
        val picked = extra?.let { FsPath.normalize(it) }?.let { if (FsPath.name(it).equals("steamapps", ignoreCase = true)) FsPath.parent(it) ?: it else it }
        val mounted = runCatching { ctx.services.volumes.volumes() }.getOrDefault(emptyList()).flatMap { it.mountPaths }
        val libraries = reader.libraries(places.roots + listOfNotNull(picked), (places.drives + mounted).distinct())
        return reader.games(libraries)
    }

    override suspend fun addSteamGames(games: List<io.github.matiyaaa.fuse.library.steam.SteamGame>): Int {
        if (games.isEmpty()) return 0
        var folder: String? = null
        val names = HashSet<String>()
        var written = 0
        for (g in games) {
            // Two games with the same name each keep their own shortcut.
            var name = io.github.matiyaaa.fuse.library.steam.SteamLibraryReader.shortcutName(g)
            if (!names.add(name.lowercase())) name = name.removeSuffix(".steam") + " (${g.appId}).steam"
            val path = ctx.services.keepFile("steam/$name", g.appId.toString().encodeToByteArray()) ?: continue
            folder = FsPath.parent(FsPath.normalize(path))
            written++
        }
        val dir = folder ?: return 0
        if (data.sources.all().none { FsPath.normalize(it.path) == dir }) data.sources.add(dir, "Steam", LibrarySourceKind.SHORTCUTS)
        rescan(ScanScope.QUICK)
        return written
    }

    /**
     * Steam's games are small shortcut files Fuse keeps, each holding the game's Steam id. If any
     * went away (Fuse's folder moved, a cleaner removed them), Steam is asked again and those
     * shortcuts are written back, so a game Steam has installed never shows as missing in Fuse and
     * always starts through Steam. True when any came back.
     */
    suspend fun repairSteam(): Boolean {
        val gone = data.games.observeMissing().first()
            .filter { it.platformId.value == STEAM }
            .mapNotNull { data.games.get(it.id)?.location?.launchPath }
            .filter { it.endsWith(".steam", ignoreCase = true) }
            .map { FsPath.name(it).lowercase() }
            .toSet()
        if (gone.isEmpty()) return false
        val found = runCatching { findSteamGames(null) }.getOrDefault(emptyList())
        val back = found.filter { g ->
            val name = io.github.matiyaaa.fuse.library.steam.SteamLibraryReader.shortcutName(g)
            name.lowercase() in gone || (name.removeSuffix(".steam") + " (${g.appId}).steam").lowercase() in gone
        }
        return addSteamGames(back) > 0
    }

    override suspend fun remove(source: LibrarySource) {
        // Games from the source are marked missing (user edits survive a re-add). Files are untouched.
        data.sources.remove(source.id)
        rescan(ScanScope.QUICK)
    }

    override suspend fun suggestions(): List<SuggestedSource> {
        val existing = data.sources.all().map { FsPath.normalize(it.path) }.toSet()
        val hints = runCatching { ctx.services.locations.libraryCandidates() }.getOrDefault(emptyList())
        return hints
            .distinctBy { FsPath.normalize(it.path) }
            .filter { FsPath.normalize(it.path) !in existing }
            .mapNotNull { hint ->
                val probe = LibrarySource(LibrarySourceId(-1), hint.path, hint.label, hint.kind)
                val found = try {
                    scanner.discover(probe).platformFolders.map { it.platform.id }.distinct().size
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    return@mapNotNull null
                }
                if (found == 0) null else SuggestedSource(hint.path, hint.label, hint.kind, found)
            }
            .sortedByDescending { it.platformsFound }
    }

    /** One scan at a time: requests queue up and merge, so callers on any thread can ask. */
    private data class Pending(val scope: ScanScope, val platform: PlatformId?)

    private val requests = Channel<Pending>(Channel.UNLIMITED)

    fun start() {
        // Drives: plugging one in or out is noticed where the system tells, and on every scan.
        driveWatch = runCatching { ctx.services.volumes.watch { driveChanges.trySend(Unit) } }.getOrNull()
        ctx.scope.launch {
            drives.refresh()
            for (change in driveChanges) {
                // Mounting takes a moment to settle (Android sends several broadcasts per card).
                delay(DRIVE_SETTLE_MS)
                while (driveChanges.tryReceive().isSuccess) Unit
                val back = drives.refresh()
                if (back.isNotEmpty()) rescan(ScanScope.QUICK)
            }
        }
        ctx.scope.launch {
            for (first in requests) {
                // Collapse what queued up meanwhile: a full scan covers everything, duplicates run once.
                val batch = mutableListOf(first)
                while (true) batch += requests.tryReceive().getOrNull() ?: break
                if (batch.any { it.scope == ScanScope.FULL }) {
                    runScan(ScanScope.FULL, null)
                } else {
                    batch.distinct().forEach { runScan(it.scope, it.platform) }
                }
            }
        }
    }

    override fun rescan(scope: ScanScope, platform: PlatformId?) {
        requests.trySend(Pending(scope, platform))
    }

    private var rulesChecked = false

    private suspend fun runScan(scope: ScanScope, platform: PlatformId?) {
        // When the rules for what is a game change, the first scan reads every folder again, so
        // entries the old rules made (a game's own data folders) go away by themselves.
        if (!rulesChecked) {
            rulesChecked = true
            if (data.cache.entry(RULES_NS, RULES_KEY)?.valueJson != "$SCAN_RULES") {
                data.folderState.forget("")
                data.cache.put(RULES_NS, RULES_KEY, "$SCAN_RULES", ctx.now(), ttlMs = null)
            }
        }
        // Only folders that can be read now: a drive that is out is never scanned, so none of its
        // games is marked missing. They return as they were when the drive does.
        drives.refresh()
        val readable = drives.status.value.filter { it.scannable }.map { it.source.id }.toSet()
        val enabled = data.sources.all().filter { it.enabled && it.id in readable }
        if (enabled.isEmpty()) {
            platformFolders.value = emptyMap()
            scanState.value = ScanProgress(ScanPhase.DONE)
            return
        }
        var last = ScanProgress(ScanPhase.DISCOVERING)
        scanState.value = last
        val report = try {
            scanner.scan(ScanRequest(enabled, scope, platform, policyResolver())) { progress ->
                last = progress
                scanState.value = progress
            }
        } catch (e: CancellationException) {
            scanState.value = ScanProgress(ScanPhase.IDLE)
            throw e
        } catch (e: Exception) {
            scanState.value = last.copy(phase = ScanPhase.FAILED)
            return
        }
        scanState.value = last.copy(phase = ScanPhase.SAVING)
        // A drive pulled out while it was being read leaves an empty folder behind, which reads as
        // "every game deleted". Only folders still on the same drive after the scan are applied.
        val before = drives.status.value.filter { it.scannable }.associate { it.source.id to it.volume?.id }
        drives.refresh()
        val after = drives.status.value.filter { it.scannable }.associate { it.source.id to it.volume?.id }
        val stillThere = before.filter { (id, volume) -> id in after && after[id] == volume }.keys
        val dropped = enabled.map { it.id }.filterNot { it in stillThere }.toSet()
        val kept = if (dropped.isEmpty()) report else report.copy(scanned = report.scanned.filter { it.sourceId !in dropped })
        val cleanNew = ctx.settings.value.library.cleanDisplayNames
        val delta = data.indexer.apply(kept, ctx.now(), DisplayNameCleaner::clean, useCleanedForNew = cleanNew)
        enabled.filterNot { it.id in dropped }.forEach { data.sources.markScanned(it.id) }

        val found = HashMap<PlatformId, MutableList<String>>()
        report.scanned.forEach { found.getOrPut(it.platformId) { ArrayList() } += it.folderPath }
        report.unchanged.forEach { f -> f.platformId?.let { found.getOrPut(it) { ArrayList() } += f.path } }
        val folders = found.mapValues { (_, paths) -> paths.distinct() }
        platformFolders.value = if (platform == null) folders else platformFolders.value - platform + folders

        scanState.value = last.copy(
            phase = ScanPhase.DONE,
            added = delta.added,
            removed = delta.missing,
            changed = delta.updated + delta.restored,
        )
        if (delta.addedIds.isNotEmpty()) added.tryEmit(delta.addedIds)
        refreshBios()
    }

    /** Game -> Platform -> Global folder policies for the scanner. */
    private suspend fun policyResolver(): FolderPolicyResolver {
        val settings = data.scopedSettings
        val global = settings.resolve(ScopedSettings.FolderMode, null, null)
            .takeIf { it.from == SettingScope.GLOBAL && !it.isDefault && it.value != FolderPolicy.AUTO }
            ?.value
        val perPlatform = ctx.platforms.all.mapNotNull { p ->
            val r = settings.resolve(ScopedSettings.FolderMode, p.id, null)
            if (r.from == SettingScope.PLATFORM) p.id to r.value else null
        }.toMap()
        return FolderPolicyResolver.of(data.games.folderPolicyOverrides(), perPlatform, global)
    }

    override fun refreshBios() {
        if (biosJob?.isActive == true) return
        biosJob = ctx.scope.launch {
            val services = ctx.services
            val sources = data.sources.all()
            val roots = runCatching { services.locations.biosRoots() }.getOrDefault(emptyList())
            val emulatorFolders = services.emulators.biosFolders(ctx.installed.value)
            val unreadable = services.emulators.unreadablePaths()
            val checker = BiosChecker(services.fs)
            val platforms = data.games.platformCounts().first().keys.mapNotNull(ctx::platform).filter { it.bios != null }
            val result = HashMap<PlatformId, BiosStatus>()
            for (p in platforms) {
                val paths = BiosSearchPaths.forPlatform(p, roots, sources, emulatorFolders)
                result[p.id] = try {
                    checker.check(p, paths, unreadable)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    BiosStatus(io.github.matiyaaa.fuse.model.BiosState.UNKNOWN, note = "Fuse couldn't check this system's firmware folders")
                }
            }
            bios.value = result
        }
    }

    private companion object {
        /** Wait after a drive event before looking, so a card that is still mounting reads whole. */
        const val DRIVE_SETTLE_MS = 1_200L
    }
}

private const val RULES_NS = "scan.rules"
private const val RULES_KEY = "version"

/** Moves when the scanner's idea of what a game is changes (2: a game's own folders are never games). */
private const val SCAN_RULES = 2

/** The Steam system's id. */
private const val STEAM = "steam"
