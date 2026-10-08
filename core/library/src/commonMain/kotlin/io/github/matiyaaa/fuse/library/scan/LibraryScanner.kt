package io.github.matiyaaa.fuse.library.scan

import io.github.matiyaaa.fuse.library.FsAccessException
import io.github.matiyaaa.fuse.library.FsEntry
import io.github.matiyaaa.fuse.library.FsPath
import io.github.matiyaaa.fuse.library.FuseFileSystem
import io.github.matiyaaa.fuse.library.PlatformCatalog
import io.github.matiyaaa.fuse.library.PlatformLookup
import io.github.matiyaaa.fuse.library.parse.FilenameParser
import io.github.matiyaaa.fuse.library.steam.SteamLibraryReader
import io.github.matiyaaa.fuse.model.DiscoveredFolder
import io.github.matiyaaa.fuse.model.FilenameTags
import io.github.matiyaaa.fuse.model.FolderInterpretation
import io.github.matiyaaa.fuse.model.FolderStateStore
import io.github.matiyaaa.fuse.model.LibrarySource
import io.github.matiyaaa.fuse.model.LibrarySourceId
import io.github.matiyaaa.fuse.model.LibrarySourceKind
import io.github.matiyaaa.fuse.model.LocationKind
import io.github.matiyaaa.fuse.model.Platform
import io.github.matiyaaa.fuse.model.PlatformFolderScan
import io.github.matiyaaa.fuse.model.PlatformId
import io.github.matiyaaa.fuse.model.ScanPhase
import io.github.matiyaaa.fuse.model.ScanProgress
import io.github.matiyaaa.fuse.model.ScanReport
import io.github.matiyaaa.fuse.model.ScanScope
import io.github.matiyaaa.fuse.model.ScannedGame
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.yield
import kotlin.coroutines.cancellation.CancellationException

/**
 * What to scan.
 *
 * @property scope QUICK skips unchanged platform folders, PLATFORM rescans [platform] fully,
 *   FULL rescans everything and ignores remembered folder state.
 * @property platform The platform for [ScanScope.PLATFORM].
 * @property policies Effective folder policy per platform and path (Global -> Platform -> Game).
 * @property mediaRoots ES-DE style media roots (`ES-DE/downloaded_media`) holding `<system>/<type>/`.
 * @property sourcePlatforms Explicit platform for [LibrarySourceKind.PLATFORM_FOLDER] sources whose
 *   folder name does not say which platform they hold.
 */
data class ScanRequest(
    val sources: List<LibrarySource>,
    val scope: ScanScope = ScanScope.QUICK,
    val platform: PlatformId? = null,
    val policies: FolderPolicyResolver = FolderPolicyResolver.CatalogDefaults,
    val mediaRoots: List<String> = emptyList(),
    val sourcePlatforms: Map<LibrarySourceId, PlatformId> = emptyMap(),
    /**
     * Systems left out wherever they are found (Steam, when the person said no to it: a games
     * folder's `steam` folder of shortcuts then isn't read). Shortcut folders, which hold Windows
     * games too, are still read.
     */
    val leaveOut: Set<PlatformId> = emptySet(),
    /** Folders each system keeps its updates and DLC in, outside its games folder (Switch). */
    val contentFolders: Map<PlatformId, List<ContentFolder>> = emptyMap(),
)

/** Events of [LibraryScanner.scanAsFlow]. */
sealed interface ScanEvent {
    data class Progress(val progress: ScanProgress) : ScanEvent

    data class Finished(val report: ScanReport) : ScanEvent
}

/**
 * A platform folder found in a library source.
 *
 * @property systemName The folder name that identified the platform (`psx` for RomM Structure B's
 *   `psx/roms`), used to find ES-DE media.
 * @property shortcuts True for a [LibrarySourceKind.SHORTCUTS] folder (steam and win games at once).
 * @property steamLibrary True for a [LibrarySourceKind.STEAM_LIBRARY]: Steam's installed games there.
 */
data class PlatformFolder(
    val sourceId: LibrarySourceId,
    val platform: Platform,
    val entry: FsEntry,
    val systemName: String = entry.name,
    val shortcuts: Boolean = false,
    val steamLibrary: Boolean = false,
) {
    val path: String get() = entry.path
}

/** Platform folders and unrecognised folders found in one source. */
data class SourceDiscovery(
    val platformFolders: List<PlatformFolder> = emptyList(),
    val unknownFolders: List<DiscoveredFolder> = emptyList(),
    val errors: List<String> = emptyList(),
)

/**
 * Walks library sources and turns them into [ScanReport]s. Read-only, cancellable and cooperative:
 * every directory listing checks for cancellation and yields.
 *
 * Source layouts:
 * - [LibrarySourceKind.ROMS_ROOT] / [LibrarySourceKind.ROMM_LIBRARY]: RomM Structure A
 *   (`<root>/roms/<platform>`), RomM Structure B (`<root>/<platform>/roms`, detected per folder), or
 *   ES-DE style (`<root>/<system>`). With a `roms/` folder present, other top-level folders only
 *   count when they follow Structure B, because Structure A libraries keep `bios/` and friends
 *   next to `roms/`.
 * - [LibrarySourceKind.PLATFORM_FOLDER]: the folder is one platform, from
 *   [ScanRequest.sourcePlatforms] or its name (`psx/roms` resolves from `psx`).
 * - [LibrarySourceKind.STEAM_LIBRARY]: Steam's installed games in that library, from its
 *   `appmanifest_*.acf` files; each game is its `steamapps/common/<installdir>` folder, with its
 *   Steam app id as its title id, so it starts through Steam.
 * - [LibrarySourceKind.SHORTCUTS]: `.steam` files and `.desktop` files that open `steam://` are
 *   Steam games; `.desktop`, `.gog`, `.epic`, `.amazon`, `.pcgame`, `.lnk`, `.exe` and `.bat` files
 *   are Windows games. Both platforms are reported for the folder.
 *
 * Quick scans: a platform folder is skipped when its modification time and the modification time of
 * each of its direct subfolders equal what [FolderStateStore] remembers. Adding a file inside
 * `snes/Hacks/` changes `Hacks`' time (not `snes`'), so it is caught; a change two or more levels
 * down (`psx/Europe/Game/disc.chd`, or `media/covers/x.png`) does not change either time and is
 * missed. Schedule a FULL scan now and then to reconcile. Folder state is only remembered after a
 * complete scan, so an unreadable folder is retried next time.
 */
class LibraryScanner(
    private val fs: FuseFileSystem,
    private val state: FolderStateStore,
    private val platforms: PlatformLookup = PlatformCatalog,
    private val options: ScanOptions = ScanOptions(),
) {
    private val interpreter = FolderInterpreter(fs, options)
    private val media = MediaLocator(fs)

    /** Scans [request], reporting progress through [onProgress]. */
    suspend fun scan(request: ScanRequest, onProgress: suspend (ScanProgress) -> Unit = {}): ScanReport {
        var visited = 0
        var found = 0
        suspend fun progress(phase: ScanPhase, path: String? = null) =
            onProgress(ScanProgress(phase, currentPath = path, foldersVisited = visited, gamesFound = found))

        progress(ScanPhase.DISCOVERING)
        val errors = ArrayList<String>()
        val unknown = ArrayList<DiscoveredFolder>()
        val folders = ArrayList<PlatformFolder>()
        for (source in request.sources.filter { it.enabled }) {
            currentCoroutineContext().ensureActive()
            val discovery = discover(source, request.sourcePlatforms[source.id])
            folders += discovery.platformFolders.filter { it.shortcuts || it.platform.id !in request.leaveOut }
            unknown += discovery.unknownFolders
            errors += discovery.errors
        }

        val selected = if (request.scope == ScanScope.PLATFORM) {
            val wanted = requireNotNull(request.platform) { "ScanScope.PLATFORM needs a platform" }
            folders.filter { it.platform.id == wanted || (it.shortcuts && wanted in SHORTCUT_PLATFORMS) }
        } else {
            folders
        }

        val scanned = ArrayList<PlatformFolderScan>()
        val unchanged = ArrayList<DiscoveredFolder>()
        for (folder in selected) {
            currentCoroutineContext().ensureActive()
            yield()
            progress(ScanPhase.SCANNING, folder.path)
            val children = try {
                visited++
                fs.list(folder.path)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                errors += "Cannot read ${folder.path}: ${e.message ?: e::class.simpleName}"
                continue
            }

            // Steam's manifests are few and cheap to read, and change without touching the folder times.
            // A system with updates and DLC kept elsewhere is read again each time: those folders change on their own.
            val elsewhere = platformsOf(folder).any { !request.contentFolders[it.id].isNullOrEmpty() }
            if (request.scope == ScanScope.QUICK && !folder.steamLibrary && !elsewhere && isUnchanged(folder.entry, children)) {
                for (platform in platformsOf(folder)) {
                    unchanged += DiscoveredFolder(folder.path, folder.entry.name, platform.id, folder.entry.modifiedAt)
                }
                continue
            }

            val results = if (folder.steamLibrary) {
                listOf(folder.platform to scanSteamLibrary(folder))
            } else if (folder.shortcuts) {
                scanShortcuts(folder, children) { path ->
                    visited++
                    progress(ScanPhase.SCANNING, path)
                }
            } else {
                val result = interpreter.scanPlatformFolder(
                    folder.platform,
                    folder.path,
                    folder.sourceId,
                    request.policies,
                    listing = children,
                    contentFolders = request.contentFolders[folder.platform.id].orEmpty(),
                ) {
                    visited++
                    progress(ScanPhase.SCANNING, it)
                }
                listOf(folder.platform to result)
            }

            var complete = true
            for ((platform, result) in results) {
                val games = withMedia(result.games, platform, folder, request.mediaRoots)
                found += games.size
                complete = complete && result.complete
                errors += result.errors
                scanned += PlatformFolderScan(
                    sourceId = folder.sourceId,
                    platformId = platform.id,
                    folderPath = folder.path,
                    folderModifiedAt = folder.entry.modifiedAt,
                    games = games,
                    complete = result.complete,
                    notGames = result.listed,
                    notGameTrees = result.skipped,
                    absorbed = result.absorbed,
                    absorbedBy = result.absorbedBy,
                    rulesVersion = SCANNER_RULES_VERSION,
                )
            }
            if (complete) rememberState(folder.entry, children)
        }

        progress(ScanPhase.DONE)
        return ScanReport(
            scanned = scanned,
            unchanged = unchanged,
            unknownFolders = if (request.scope == ScanScope.PLATFORM) emptyList() else unknown,
            errors = errors,
        )
    }

    /** [scan] as a cold flow: progress events, then one [ScanEvent.Finished]. Cancel by cancelling collection. */
    fun scanAsFlow(request: ScanRequest): Flow<ScanEvent> = flow {
        val report = scan(request) { emit(ScanEvent.Progress(it)) }
        emit(ScanEvent.Finished(report))
    }

    /** Finds the platform folders of one source. Unreadable roots are reported as errors. */
    suspend fun discover(source: LibrarySource, explicitPlatform: PlatformId? = null): SourceDiscovery {
        val rootPath = FsPath.normalize(source.path)
        val root = fs.stat(rootPath)?.takeIf { it.isDirectory }
            ?: return SourceDiscovery(errors = listOf("Library folder not found: $rootPath"))
        return when (source.kind) {
            LibrarySourceKind.PLATFORM_FOLDER -> discoverPlatformFolder(source, root, explicitPlatform)
            LibrarySourceKind.SHORTCUTS -> {
                val steam = platforms.byId(PlatformId("steam"))
                if (steam == null) {
                    SourceDiscovery(errors = listOf("Steam platform missing from the catalog"))
                } else {
                    SourceDiscovery(listOf(PlatformFolder(source.id, steam, root, shortcuts = true)))
                }
            }
            LibrarySourceKind.STEAM_LIBRARY -> {
                val steam = platforms.byId(PlatformId("steam"))
                if (steam == null) {
                    SourceDiscovery(errors = listOf("Steam platform missing from the catalog"))
                } else {
                    SourceDiscovery(listOf(PlatformFolder(source.id, steam, root, steamLibrary = true)))
                }
            }
            LibrarySourceKind.ROMS_ROOT, LibrarySourceKind.ROMM_LIBRARY -> discoverRoot(source, root)
        }
    }

    private suspend fun discoverPlatformFolder(source: LibrarySource, root: FsEntry, explicit: PlatformId?): SourceDiscovery {
        explicit?.let(platforms::byId)?.let { return SourceDiscovery(listOf(PlatformFolder(source.id, it, root))) }
        platforms.resolveFolder(root.name)?.let { return SourceDiscovery(listOf(PlatformFolder(source.id, it, root))) }
        // RomM Structure B platform folder added directly: ".../psx/roms".
        if (root.name.equals("roms", ignoreCase = true)) {
            val parentName = FsPath.parent(root.path)?.let(FsPath::name).orEmpty()
            platforms.resolveFolder(parentName)?.let {
                return SourceDiscovery(listOf(PlatformFolder(source.id, it, root, systemName = parentName)))
            }
        }
        // A folder named for something else ("Games", "My Switch"): its files say what it holds.
        systemOfFiles(root)?.let { return SourceDiscovery(listOf(PlatformFolder(source.id, it, root))) }
        return SourceDiscovery(unknownFolders = listOf(DiscoveredFolder(root.path, root.name, null, root.modifiedAt)))
    }

    /**
     * The one system whose own kind of files fill [folder] and the folders below it (a few levels),
     * when its name names no system: a folder of game folders full of `.nsp` and `.xci` files is a
     * Switch folder, whatever it is called. Only file types few systems use count (never `.zip`,
     * `.iso` or `.bin`), and one system must clearly lead; otherwise null.
     */
    private suspend fun systemOfFiles(folder: FsEntry): Platform? {
        val owners = HashMap<String, MutableList<Platform>>()
        for (p in platforms.all) for (e in p.extensions) owners.getOrPut(e.lowercase()) { ArrayList() } += p
        val telling = owners.filterValues { it.size <= 2 }.keys - SNIFF_IGNORED
        val counts = HashMap<Platform, Int>()
        var seen = 0
        var told = 0
        suspend fun walk(dir: String, depth: Int) {
            if (depth > SNIFF_DEPTH || seen > SNIFF_ENTRIES) return
            val children = runCatching { fs.list(dir) }.getOrNull() ?: return
            for (c in children) {
                if (++seen > SNIFF_ENTRIES) return
                if (c.isDirectory) continue
                val ext = FsPath.extension(c.name).lowercase()
                if (ext !in telling) continue
                told++
                for (p in owners.getValue(ext)) counts[p] = (counts[p] ?: 0) + 1
            }
            for (c in children.filter { it.isDirectory && !it.name.startsWith(".") && !ScanRules.isExcludedFolder(it.name, options.extraExcludedFolders) }) {
                walk(c.path, depth + 1)
            }
        }
        walk(folder.path, 0)
        if (told < SNIFF_MIN_FILES) return null
        // Ties (Switch and Switch 2 share .nsp and .xci) go to the system with more of the files, then the older one.
        val best = counts.entries.sortedWith(
            compareByDescending<Map.Entry<Platform, Int>> { it.value }.thenBy { it.key.releaseYear ?: Int.MAX_VALUE },
        ).firstOrNull() ?: return null
        return best.key.takeIf { best.value * 10 >= told * 8 }
    }

    private suspend fun discoverRoot(source: LibrarySource, root: FsEntry): SourceDiscovery {
        val children = try {
            fs.list(root.path)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return SourceDiscovery(errors = listOf("Cannot read ${root.path}: ${e.message ?: e::class.simpleName}"))
        }
        val found = ArrayList<PlatformFolder>()
        val unknown = ArrayList<DiscoveredFolder>()
        val errors = ArrayList<String>()

        fun classify(dir: FsEntry, folder: FsEntry = dir) {
            val name = dir.name
            if (ScanRules.isExcludedFolder(name, options.extraExcludedFolders)) return
            if (name.lowercase() in ScanRules.nonPlatformFolderNames) return
            val platform = platforms.resolveFolder(name)
            if (platform == null) {
                unknown += DiscoveredFolder(folder.path, name, null, folder.modifiedAt)
            } else {
                found += PlatformFolder(source.id, platform, folder, systemName = name)
            }
        }

        // RomM Structure A: <root>/roms/<platform>.
        val romsDir = children.firstOrNull { it.isDirectory && it.name.equals("roms", ignoreCase = true) }
        if (romsDir != null) {
            try {
                fs.list(romsDir.path).filter { it.isDirectory }.forEach { classify(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                errors += "Cannot read ${romsDir.path}: ${e.message ?: e::class.simpleName}"
            }
        }

        for (dir in children.filter { it.isDirectory && it !== romsDir }.sortedBy { it.name.lowercase() }) {
            if (ScanRules.isExcludedFolder(dir.name, options.extraExcludedFolders)) continue
            // RomM Structure B: <root>/<platform>/roms.
            val structureB = fs.stat(FsPath.join(dir.path, "roms"))?.takeIf { it.isDirectory }
            if (structureB != null) {
                classify(dir, folder = structureB)
                continue
            }
            if (romsDir == null) classify(dir)
        }
        // Nothing inside is named for a system: a folder of games ("Games/<game>/<files>") is one
        // system's folder when its files say so; otherwise each unknown folder may say so itself.
        if (found.isEmpty() && errors.isEmpty()) {
            systemOfFiles(root)?.let { return SourceDiscovery(listOf(PlatformFolder(source.id, it, root))) }
        }
        val named = unknown.mapNotNull { u ->
            val entry = fs.stat(u.path)?.takeIf { it.isDirectory } ?: return@mapNotNull null
            systemOfFiles(entry)?.let { u to PlatformFolder(source.id, it, entry, systemName = u.name) }
        }
        found += named.map { it.second }
        val stillUnknown = unknown - named.map { it.first }.toSet()
        return SourceDiscovery(found.sortedBy { it.path }, stillUnknown, errors)
    }

    private suspend fun isUnchanged(folder: FsEntry, children: List<FsEntry>): Boolean {
        if (state.rulesVersion(folder.path) != SCANNER_RULES_VERSION) return false
        if (folder.modifiedAt <= 0L) return false
        if (state.lastModified(folder.path) != folder.modifiedAt) return false
        for (child in children) {
            if (!child.isDirectory) continue
            if (child.modifiedAt <= 0L || state.lastModified(child.path) != child.modifiedAt) return false
        }
        return true
    }

    private suspend fun rememberState(folder: FsEntry, children: List<FsEntry>) {
        state.remember(folder.path, folder.modifiedAt)
        for (child in children) if (child.isDirectory) state.remember(child.path, child.modifiedAt)
    }

    private fun platformsOf(folder: PlatformFolder): List<Platform> =
        if (folder.shortcuts) SHORTCUT_PLATFORMS.mapNotNull(platforms::byId) else listOf(folder.platform)

    private suspend fun scanShortcuts(
        folder: PlatformFolder,
        children: List<FsEntry>,
        onFolder: suspend (String) -> Unit,
    ): List<Pair<Platform, FolderScanResult>> {
        val steam = platforms.byId(PlatformId("steam")) ?: return emptyList()
        val win = platforms.byId(PlatformId("win")) ?: return emptyList()
        val steamGames = ArrayList<ScannedGame>()
        val winGames = ArrayList<ScannedGame>()
        val errors = ArrayList<String>()
        var complete = true
        val visited = HashSet<String>()

        suspend fun walk(dir: String, entries: List<FsEntry>, depth: Int) {
            currentCoroutineContext().ensureActive()
            if (!visited.add(fs.canonical(dir) ?: dir)) return
            for (entry in entries.sortedBy { it.name.lowercase() }) {
                if (entry.isDirectory) {
                    if (depth >= options.maxDepth || ScanRules.isExcludedFolder(entry.name, options.extraExcludedFolders)) continue
                    onFolder(entry.path)
                    val sub = try {
                        fs.list(entry.path)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: FsAccessException) {
                        complete = false
                        errors += "Cannot read ${e.path}"
                        continue
                    }
                    walk(entry.path, sub, depth + 1)
                    continue
                }
                val target = when (entry.extension) {
                    "steam" -> steam
                    "desktop" -> if (fs.readText(entry.path, SHORTCUT_READ_LIMIT)?.contains("steam://") == true) steam else win
                    in WINDOWS_SHORTCUTS -> win
                    else -> null
                } ?: continue
                val game = ScannedGame(
                    platformId = target.id,
                    sourceId = folder.sourceId,
                    path = entry.path,
                    kind = LocationKind.FILE,
                    launchPath = entry.path,
                    title = FsPath.stem(entry.name),
                    tags = FilenameParser.parse(entry.name).tags,
                    sizeBytes = entry.sizeBytes,
                    modifiedAt = entry.modifiedAt,
                    interpretation = FolderInterpretation.SINGLE_FILE,
                )
                if (target === steam) steamGames += game else winGames += game
            }
        }
        walk(folder.path, children, 0)
        return listOf(
            steam to FolderScanResult(steamGames, complete, errors),
            win to FolderScanResult(winGames, complete, emptyList()),
        )
    }

    /**
     * A Steam library's installed games, as Steam's own manifests list them: each one its folder
     * under `steamapps/common`, named as Steam names it, with its app id as its title id. A game
     * Steam uninstalls loses its manifest, so it goes missing; nothing else does.
     */
    private suspend fun scanSteamLibrary(folder: PlatformFolder): FolderScanResult {
        val games = try {
            SteamLibraryReader(fs).games(listOf(folder.path))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return FolderScanResult(emptyList(), complete = false, errors = listOf("Cannot read ${folder.path}: ${e.message ?: e::class.simpleName}"))
        }
        return FolderScanResult(
            games.map { g ->
                ScannedGame(
                    platformId = folder.platform.id,
                    sourceId = folder.sourceId,
                    path = FsPath.normalize(g.folder),
                    kind = LocationKind.FOLDER,
                    launchPath = FsPath.normalize(g.folder),
                    title = g.name,
                    tags = FilenameTags(serial = g.appId.toString()),
                    sizeBytes = g.sizeBytes,
                    interpretation = FolderInterpretation.FOLDER_IS_GAME,
                )
            },
            complete = true,
            errors = emptyList(),
        )
    }

    private suspend fun withMedia(
        games: List<ScannedGame>,
        platform: Platform,
        folder: PlatformFolder,
        mediaRoots: List<String>,
    ): List<ScannedGame> {
        if (games.isEmpty()) return games
        val index = media.index(platform, folder.path, mediaRoots, folder.systemName)
        if (index.isEmpty) return games
        return games.map { game ->
            val found = index.find(media.keysFor(game, platform, folder.path))
            // Scraped art beside the library wins over what the game carries itself (sce_sys).
            if (found.isEmpty()) game else game.copy(localMedia = game.localMedia + found)
        }
    }

    private companion object {
        // Increase only for semantic changes that must reinterpret existing unchanged libraries.
        const val SCANNER_RULES_VERSION = 1L
        const val SHORTCUT_READ_LIMIT = 16 * 1024
        val SHORTCUT_PLATFORMS = listOf(PlatformId("steam"), PlatformId("win"))
        val WINDOWS_SHORTCUTS = setOf("gog", "epic", "amazon", "pcgame", "lnk", "exe", "bat")
    }
}

/** How deep, and how many entries, a folder's files are looked at to tell its system. */
private const val SNIFF_DEPTH = 3
private const val SNIFF_ENTRIES = 4000
private const val SNIFF_MIN_FILES = 2

/** File types too common across systems to tell one apart. */
private val SNIFF_IGNORED = setOf("zip", "7z", "rar", "iso", "bin", "cue", "img", "chd", "m3u", "txt", "exe", "bat", "sh", "lnk", "url", "desktop")
