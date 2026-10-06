package io.github.matiyaaa.fuse.ui.shell.store

import io.github.matiyaaa.fuse.data.backup.BackupArchive
import io.github.matiyaaa.fuse.data.backup.BackupPart
import io.github.matiyaaa.fuse.data.backup.BackupProblem
import io.github.matiyaaa.fuse.data.backup.RestoreReport
import io.github.matiyaaa.fuse.model.AchievementState
import io.github.matiyaaa.fuse.model.AppFilter
import io.github.matiyaaa.fuse.model.AppKind
import io.github.matiyaaa.fuse.model.ArtworkOption
import io.github.matiyaaa.fuse.model.BorderStyle
import io.github.matiyaaa.fuse.model.CartridgeRoute
import io.github.matiyaaa.fuse.model.CartridgeStatus
import io.github.matiyaaa.fuse.model.CollectionId
import io.github.matiyaaa.fuse.model.EmulatorId
import io.github.matiyaaa.fuse.model.FolderPolicy
import io.github.matiyaaa.fuse.model.GameCollection
import io.github.matiyaaa.fuse.model.GameId
import io.github.matiyaaa.fuse.model.InstalledEmulator
import io.github.matiyaaa.fuse.model.LaunchDisplay
import io.github.matiyaaa.fuse.model.LibraryLayout
import io.github.matiyaaa.fuse.model.LibrarySource
import io.github.matiyaaa.fuse.model.LibrarySourceKind
import io.github.matiyaaa.fuse.model.MediaFillMode
import io.github.matiyaaa.fuse.model.MediaKind
import io.github.matiyaaa.fuse.model.MediaOwner
import io.github.matiyaaa.fuse.model.PlatformId
import io.github.matiyaaa.fuse.model.ProviderStatus
import io.github.matiyaaa.fuse.model.ReleaseInfo
import io.github.matiyaaa.fuse.model.Resolved
import io.github.matiyaaa.fuse.model.ScanProgress
import io.github.matiyaaa.fuse.model.ScanScope
import io.github.matiyaaa.fuse.model.ScopeRef
import io.github.matiyaaa.fuse.model.ScopedKey
import io.github.matiyaaa.fuse.model.ScrapeCandidate
import io.github.matiyaaa.fuse.model.ScrapeProviderId
import io.github.matiyaaa.fuse.model.SortOrder
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Everything the interface reads and does, in one place. Screens only talk to this interface; the
 * default implementation composes the database, scanner, launch system and integrations. Every
 * call is safe from the UI thread: reads are flows, writes are suspend or fire-and-forget and run
 * on background dispatchers.
 */
interface FuseStore {
    val prefs: StateFlow<UiPrefs>
    fun updatePrefs(transform: (UiPrefs) -> UiPrefs)

    /**
     * Resets Home on this device only ([reset] makes the new Home from the current one). With Fuse
     * Sync, a Home the profile shares becomes this device's own, so the person's Home on their other
     * devices stays as it is. The Home before is kept for [undoHomeReset].
     */
    fun resetHome(reset: (io.github.matiyaaa.fuse.model.HomeLayoutConfig) -> io.github.matiyaaa.fuse.model.HomeLayoutConfig) {}

    /** Puts Home back as it was before the last reset here (rejoining the profile's, if the reset left it). */
    fun undoHomeReset() {}

    val library: LibraryOps
    val sources: SourceOps
    val emulators: EmulatorOps
    val media: MediaOps
    val collections: CollectionOps
    val achievements: AchievementOps
    val apps: AppOps
    val cartridge: CartridgeOps
    val settings: ScopedSettingsOps
    val credentials: CredentialOps
    val updates: UpdateOps
    val storage: StorageOps
    val themes: ThemeOps

    /** What Fuse found wrong with the setup, and how to fix it. */
    val health: HealthOps get() = HealthOps.None

    /** Backups of what the user made in Fuse, and restoring them. */
    val backup: BackupOps get() = BackupOps.None

    /** The Store, where Fuse can install apps (Android); [AppStoreOps.None] elsewhere. */
    val appStore: AppStoreOps get() = AppStoreOps.None

    /** Jellyfin, an addon; idle until it is turned on in Settings, Addons, Jellyfin. */
    val jellyfin: io.github.matiyaaa.fuse.jellyfin.JellyfinService? get() = null

    /** What Home shows: the library's feed, with Jellyfin's widgets when there are any. */
    val homeFeed: kotlinx.coroutines.flow.StateFlow<HomeFeed> get() = library.home

    /** Games, updates, DLC and licences installed into RPCS3 and Vita3K by Fuse itself. */
    val content: ContentOps get() = ContentOps.None

    /**
     * Starts what Fuse does by itself (scans, art fills, Cartridge, achievements, update checks)
     * when the store was created in safe mode, which holds it back. Does nothing otherwise.
     */
    fun resumeAutomaticWork() = Unit

    /** Fuse Sync by Fuse: off and invisible until turned on in Settings, Addons, Fuse Sync. */
    val sync: SyncOps get() = SyncOps.None

    /** Downloads: every transfer Fuse makes for the person, in one place. */
    val transfers: TransfersOps get() = TransfersOps.None

    /** Fuse RomM, the Fuse RomM native integration. */
    val romm: RommOps get() = RommOps.None

    /** Streaming games from a computer at home with Moonlight. */
    val streaming: StreamingOps get() = StreamingOps.None

    /** Jellyfin films and episodes kept on this device for watching offline. */
    val offlineMedia: OfflineMediaOps get() = OfflineMediaOps.None

    /** Syncthing, for people who run it: off until chosen in setup or Settings, Addons, Syncthing. */
    val syncthing: io.github.matiyaaa.fuse.sync.syncthing.SyncthingService? get() = null

    /** A song that ships with Fuse ([io.github.matiyaaa.fuse.ui.shell.music.BundledMusic]) as a file the player can open. */
    suspend fun bundledTrack(id: String): String? = null
}

/**
 * System health: everything Fuse can tell is wrong or needs attention in the setup (library
 * folders and drives, emulators, firmware, playlists, provider keys, updates), each told as a
 * [Problem] with what to do. Only what Fuse can really check is reported; what it can't know is
 * left out rather than guessed.
 */
interface HealthOps {
    val report: StateFlow<HealthReport>

    /** Checks everything again, game files included. */
    fun check()

    /**
     * A diagnostics report for a bug report: versions, systems, emulators found, drives and folders
     * with their state, integrations on or off, the health findings and recent launch problems, plus
     * [device] lines the interface knows. Personal folder names and anything secret are removed.
     * Nothing is saved or sent; the caller shows it first.
     */
    suspend fun diagnostics(device: List<String> = emptyList(), crash: String? = null): String = ""

    object None : HealthOps {
        override val report: StateFlow<HealthReport> = MutableStateFlow(HealthReport())
        override fun check() = Unit
    }
}

/**
 * Backups (`.fusebackup`): settings, how Fuse looks and Home, each game's changes (names,
 * favourites, emulators, systems, details), collections, chosen art and play time. Never games,
 * firmware, keys or passwords. Restoring merges into this library: it never deletes, games the
 * backup names that aren't here are left out, and a copy of how things were is kept first.
 */
interface BackupOps {
    /** Makes a backup of everything now; null when it couldn't be made. */
    suspend fun create(): BackupMade? = null

    /** Opens [bytes] as a backup and tells what restoring it would do. */
    suspend fun open(bytes: ByteArray): BackupOpened = BackupOpened.Failed(BackupProblem.NOT_A_BACKUP)

    /** Restores [parts] of [backup]. Null when nothing could be restored (and nothing changed). */
    suspend fun restore(backup: BackupPreview, parts: Set<BackupPart>): RestoreReport? = null

    /** True while the copy made before this session's last restore can be put back. */
    val canUndo: StateFlow<Boolean> get() = MutableStateFlow(false)

    /** Puts settings, look and Home back as they were before the last restore. */
    suspend fun undo(): Boolean = false

    object None : BackupOps
}

/** A backup made now: its file name and bytes, and what it holds. */
class BackupMade(
    val name: String,
    val bytes: ByteArray,
    val games: Int,
    val collections: Int,
    val sessions: Int,
    val pictures: Int,
)

sealed interface BackupOpened {
    data class Ready(val preview: BackupPreview) : BackupOpened
    data class Failed(val problem: BackupProblem) : BackupOpened
}

/** What an opened backup holds and how much of it fits this library. */
class BackupPreview internal constructor(
    internal val archive: BackupArchive,
    val createdAt: Long,
    val fuseVersion: String,
    val host: String,
    /** Games the backup has changes for. */
    val games: Int,
    /** Of [games], those found in this library. */
    val gamesHere: Int,
    val collections: Int,
    val sessions: Int,
    val pictures: Int,
    val hasSettings: Boolean,
    /** Made by a newer Fuse; what this one doesn't know is left out. */
    val newer: Boolean,
)

/** One thing System health found, and what it is about: the whole setup, a system or a game. */
data class HealthIssue(
    /** Stable for the same finding, so lists keep their place as checks run again. */
    val id: String,
    val problem: Problem,
    val platform: PlatformId? = null,
    val game: GameId? = null,
)

data class HealthReport(
    val issues: List<HealthIssue> = emptyList(),
    /** When the last full check (game files included) finished; null before it has run. */
    val checkedAt: Long? = null,
    val checking: Boolean = false,
) {
    /** How the setup is overall: the most serious issue, or healthy. */
    val worst: Severity get() = issues.maxOfOrNull { it.problem.severity } ?: Severity.HEALTHY

    fun forPlatform(platform: PlatformId): List<HealthIssue> = issues.filter { it.platform == platform && it.game == null }

    fun forGame(game: GameId): List<HealthIssue> = issues.filter { it.game == game }
}

/**
 * Themes added by the user, from a link, a file or pasted text (docs/THEMES.md). Nothing is applied
 * or kept until [add]; removing one only forgets it on this device.
 */
interface ThemeOps {
    /** The text behind [url]: https only, at most 64 KB; GitHub and gist pages lead to their files. */
    suspend fun fetch(url: String): Result<String>

    /** The text of a theme file at [path], at most 64 KB. */
    suspend fun readFile(path: String): Result<String>

    /** Reads a theme, repairing what it can (see [io.github.matiyaaa.fuse.model.ThemeCodec]). */
    fun parse(text: String): io.github.matiyaaa.fuse.model.ThemeCodec.Result

    /** Keeps [spec] (as the file [json] it came from), replacing one with the same id, and uses it when [apply]. */
    suspend fun add(spec: io.github.matiyaaa.fuse.model.ThemeSpec, json: String, source: String?, apply: Boolean)

    /** Forgets the added theme [id]; Fuse goes back to its own theme if it was in use. */
    suspend fun remove(id: String)

    /** [spec] as a theme file: the file it was added from, or a new one for a built-in theme. */
    fun export(spec: io.github.matiyaaa.fuse.model.ThemeSpec): String
}

/**
 * Space on the drives the library lives on, what each game takes (every disc, track and folder
 * file) and deleting games' files. Deleting is only offered on the device, in Settings, Storage.
 */
interface StorageOps {
    /** The latest measurement; null until [refresh] first runs. Updates as games are measured. */
    val usage: StateFlow<StorageUsage?>
    /** Measures every game again. */
    fun refresh()
    /** What [game] takes on disk, or null when it is gone. */
    suspend fun size(game: GameId): Long?
    /**
     * Deletes the games' files (all discs, tracks and folder contents; never saves next to them)
     * and forgets the games. Only paths inside the game's library folder are touched.
     */
    suspend fun delete(games: List<GameId>): DeleteReport

    /** Connected drives games can be moved to, each with its games folder when it has one. */
    suspend fun moveTargets(): List<MoveTarget> = emptyList()

    /** The drive [game]'s files are on (a [MoveTarget.volumeId]), or null when Fuse can't tell. */
    suspend fun driveOf(game: GameId): String? = null

    /**
     * Makes a games folder ("ROMs") at the top of the drive [volumeId] and adds it to the library,
     * so games can be moved there. Its path, or null when the drive can't be written.
     */
    suspend fun makeGamesFolder(volumeId: String): String? = null

    /**
     * Sets a drive up for games: an Emulation folder at [at] (the drive's top when null) with a
     * ROMs folder holding one folder per system, and a BIOS folder for the systems that need
     * firmware; the ROMs folder joins the library. Null when the drive can't be written.
     */
    suspend fun setUpDrive(volumeId: String, at: String? = null): DriveSetup? = null

    /**
     * Moves the games' files (every disc, track and folder) into the games folder on [volumeId],
     * each into its system's folder there, then removes them from where they were. Play time,
     * edits and art stay with each game. A game is only removed from its old place once its copy
     * is whole. Runs until done or [cancelMove]; [moving] follows it.
     */
    suspend fun move(games: List<GameId>, volumeId: String): MoveReport = MoveReport(0, 0, games.map { it.value.toString() })

    /** The move under way, or null. */
    val moving: StateFlow<MoveProgress?> get() = MutableStateFlow(null)

    /** Stops a move after the game being copied (which is left where it was). */
    fun cancelMove() = Unit
}

/** A drive set up for games: its ROMs folder (now in the library) and how many system folders it got. */
data class DriveSetup(val romsFolder: String, val systems: Int)

/** A drive games can be moved to: its space, and its games folder (null until one is made). */
data class MoveTarget(
    val volumeId: String,
    val label: String,
    val kind: io.github.matiyaaa.fuse.model.VolumeKind,
    val freeBytes: Long,
    val gamesFolder: String?,
    val removable: Boolean,
)

/** A move under way: the game being copied ([index] of [count]) and the bytes so far of all of them. */
data class MoveProgress(
    val title: String,
    val index: Int,
    val count: Int,
    val doneBytes: Long,
    val totalBytes: Long,
    val to: String,
    val card: GameCard? = null,
)

/** How a move went: games moved, bytes moved, and the games that stayed where they were (with why, when one reason covers them). */
data class MoveReport(val moved: Int, val bytes: Long, val failed: List<String>, val reason: String? = null)

data class StorageUsage(
    val volumes: List<VolumeUsage>,
    /** Largest first. */
    val games: List<GameSize>,
    val measured: Int,
    val total: Int,
    val finished: Boolean,
)

/**
 * A drive the library is on: its size, what's free, and how much of it is games, by system. A drive
 * that is out ([online] false) keeps its place with what Fuse last knew of it.
 */
data class VolumeUsage(
    val label: String,
    val totalBytes: Long,
    val freeBytes: Long,
    val gamesBytes: Long,
    val systems: List<SystemShare>,
    /** The drive's id ([io.github.matiyaaa.fuse.model.StorageVolume.id]), or a stand-in where the system has none. */
    val id: String = label,
    val kind: io.github.matiyaaa.fuse.model.VolumeKind = io.github.matiyaaa.fuse.model.VolumeKind.OTHER,
    val removable: Boolean = false,
    val online: Boolean = true,
    /** When an offline drive was last seen connected. */
    val lastSeenAt: Long? = null,
    /** Games stored on it (measured or, while offline, last known). */
    val games: Int = 0,
    val readOnly: Boolean = false,
    /** Where it is mounted and its filesystem, for technical details only. */
    val mountPath: String? = null,
    val fsType: String? = null,
)

data class SystemShare(val platform: PlatformId, val name: String, val accent: Long, val bytes: Long)

/**
 * What one game takes, on which drive ([volumeId], matching [VolumeUsage.id]). A game whose drive is
 * out shows the size the last scan saw ([lastKnown]).
 */
data class GameSize(
    val card: GameCard,
    val bytes: Long,
    val files: Int,
    val volumeId: String? = null,
    val lastKnown: Boolean = false,
)

data class DeleteReport(val deleted: Int, val freedBytes: Long, val failed: List<String>)

data class GameQuery(
    val platform: PlatformId? = null,
    val collection: CollectionId? = null,
    val favoritesOnly: Boolean = false,
    val includeHidden: Boolean = false,
    val sort: SortOrder = SortOrder.TITLE,
    val set: GameSet = GameSet.LIBRARY,
)

/** Which games a [GameQuery] lists: the library, or games whose files are gone, hidden or removed. */
enum class GameSet { LIBRARY, MISSING, HIDDEN, REMOVED }

interface LibraryOps {
    val home: StateFlow<HomeFeed>
    val platforms: StateFlow<List<PlatformCard>>
    fun games(query: GameQuery): Flow<List<GameCard>>
    fun game(id: GameId): Flow<GameDetail?>
    fun search(query: String): Flow<SearchResults>

    /** [display] overrides the screen settings for this launch (the user just picked one). */
    /**
     * Starts [id]. With Fuse Sync, its newest save is put in place first; [skipSaveCheck] starts it
     * as it is here (after the person settled a conflict). [playAnyway] doesn't wait for another
     * device still playing it or sending its save.
     */
    suspend fun launch(
        id: GameId,
        emulator: EmulatorId? = null,
        discPath: String? = null,
        display: LaunchDisplay? = null,
        skipSaveCheck: Boolean = false,
        playAnyway: Boolean = false,
        /** Told as the launch moves on: checking the save first (when Fuse Sync or Syncthing does), then starting. */
        onStage: (LaunchStage) -> Unit = {},
    ): LaunchOutcome

    /** Called when Fuse comes back to the foreground: closes the running session, checks for changes. */
    fun onResume()
    fun onPause()

    suspend fun setFavorite(id: GameId, favorite: Boolean)
    suspend fun setHidden(id: GameId, hidden: Boolean)
    suspend fun setPinned(id: GameId, pinned: Boolean)
    suspend fun rename(id: GameId, title: String?)
    suspend fun setEmulator(id: GameId, emulator: EmulatorId?)
    suspend fun setFolderPolicy(id: GameId, policy: FolderPolicy?)
    /** Removes the entry from Fuse only. Files are never touched. */
    suspend fun removeFromFuse(id: GameId)
    /** Brings back a hidden or removed game. */
    suspend fun restore(id: GameId)
    /** Forgets a game whose file is gone, with its art and play history. Files are never touched. */
    suspend fun forgetMissing(id: GameId)

    /** Clean Display Names: preview, apply and undo. Files are never renamed. */
    suspend fun previewCleanNames(): List<Pair<String, String>>
    suspend fun applyCleanNames(enabled: Boolean)
    suspend fun undoCleanNames(): Boolean

    /** Folder browser for FOLDER_BROWSER games: files inside the game folder that could be launched. */
    suspend fun launchCandidates(id: GameId): List<String>

    /**
     * What a PlayStation or PS2 disc image says about itself (its serial, and PCSX2's CRC for PS2
     * discs), read from the image once and remembered. Null for other games, compressed images and
     * images that can't be read.
     */
    suspend fun discIdentity(id: GameId): io.github.matiyaaa.fuse.library.disc.DiscIdentity? = null

    /**
     * What RPCS3's public compatibility list says about the PS3 game [id], by its title id. Asks
     * rpcs3.net only when called (the user asked), sending only the title id; answers are kept a week.
     */
    suspend fun rpcs3Compatibility(id: GameId): CompatibilityAnswer = CompatibilityAnswer.Unreachable

    /**
     * The PCSX2 patches for the PS2 game [id] (its patch files and PCSX2's bundled ones), each with
     * who turned it on. Reads only.
     */
    suspend fun pcsx2Patches(id: GameId): Pcsx2PatchList = Pcsx2PatchList.Unavailable("Not here", "PCSX2's patches can only be changed from Fuse on a computer.")

    /**
     * Turns the patch [name] on or off in PCSX2's settings for [id]. Fuse turns off only patches it
     * turned on itself; anything set in PCSX2 stays as it is. False when nothing changed.
     */
    suspend fun setPcsx2Patch(id: GameId, name: String, on: Boolean): Boolean = false

    /** Play time today, this week, this month and in all, per day, per game and per system. */
    fun playTime(): Flow<PlayTimeReport> = kotlinx.coroutines.flow.flowOf(PlayTimeReport(loaded = true))

    /**
     * Files the game under [platform] for good (a rescan keeps it), or back under the system its
     * folder says when null. Its emulator choice goes with the old system.
     */
    suspend fun setPlatform(id: GameId, platform: PlatformId?) = Unit

    /** Adds the game file at [path], wherever it is on the device, to [platform]. Null when it can't be read. */
    suspend fun addGameFile(path: String, platform: PlatformId): GameId? = null

    /** What is in the folder at [path] for Fuse's file picker; the device's storage when null. */
    suspend fun browse(path: String?): BrowseListing = BrowseListing(null, null, emptyList())
}

/** A folder's content in Fuse's file picker: folders first, then files, by name. */
data class BrowseListing(
    /** The folder shown, or null for the list of storage places. */
    val path: String?,
    /** Where "up" goes: the folder above, or null for the storage places. */
    val parent: String?,
    val entries: List<BrowseEntry>,
    /** Set when the folder couldn't be read. */
    val error: String? = null,
    /** The storage place's name, then each folder below it ("Internal storage", "Download", "Games"). */
    val trail: List<String> = emptyList(),
)

data class BrowseEntry(val path: String, val name: String, val isDirectory: Boolean, val sizeBytes: Long = 0)

interface SourceOps {
    val sources: StateFlow<List<LibrarySource>>
    val scan: StateFlow<ScanProgress>
    suspend fun add(path: String, kind: LibrarySourceKind = LibrarySourceKind.ROMS_ROOT): LibrarySource?
    suspend fun remove(source: LibrarySource)
    /** Suggested library folders found on this device (ES-DE ROMs, RomM library folders, Cartridge's folders). */
    suspend fun suggestions(): List<SuggestedSource>
    fun rescan(scope: ScanScope = ScanScope.QUICK, platform: PlatformId? = null)
    fun refreshBios()

    /** Every library folder with the drive it is on and whether Fuse can read it right now. */
    val status: StateFlow<List<io.github.matiyaaa.fuse.model.SourceStatus>> get() = MutableStateFlow(emptyList())

    /** The drives mounted now. */
    val volumes: StateFlow<List<io.github.matiyaaa.fuse.model.StorageVolume>> get() = MutableStateFlow(emptyList())

    /** Looks at the drives again ("Retry" after plugging one in); folders that came back are scanned. */
    fun refreshDrives() = Unit

    /**
     * For a folder in [io.github.matiyaaa.fuse.model.SourceState.OTHER_DRIVE]: the user says the drive
     * mounted there now holds this library (a card that was reformatted or cloned). False when the
     * folder can't be read there.
     */
    suspend fun adoptDrive(source: io.github.matiyaaa.fuse.model.LibrarySourceId): Boolean = false

    /**
     * The games Steam has installed on this computer, on every drive, plus those in [extra] (a
     * Steam library folder the user picked). Reads only; empty where there is no Steam.
     */
    suspend fun findSteamGames(extra: String? = null): List<io.github.matiyaaa.fuse.library.steam.SteamGame> = emptyList()

    /**
     * Puts [games] in the library under Steam: Fuse keeps a small shortcut for each in its own
     * folder (Steam's files are never touched) and plays them through Steam. Returns how many it added.
     */
    suspend fun addSteamGames(games: List<io.github.matiyaaa.fuse.library.steam.SteamGame>): Int = 0
}

data class SuggestedSource(val path: String, val label: String, val kind: LibrarySourceKind, val platformsFound: Int)

interface EmulatorOps {
    val installed: StateFlow<List<InstalledEmulator>>
    fun refresh()
    /** Emulators that could run [platform], installed ones first, with notes. */
    fun optionsFor(platform: PlatformId): List<EmulatorOption>

    /**
     * Emulators for [game]'s system, each saying why it can't run this game when it can't (the
     * file type, a folder it can't open, not installed). Checks only; nothing is written or started.
     */
    suspend fun optionsForGame(game: GameId): List<EmulatorOption> = emptyList()

    /** What Fuse knows about [emulator]; null when it doesn't know it on this device. */
    suspend fun details(emulator: EmulatorId): EmulatorDetails? = null

    /** A game to try [emulator] with: one on its systems, played most recently, whose file is here. */
    suspend fun testGame(emulator: EmulatorId): GameCard? = null
    suspend fun setPlatformEmulator(platform: PlatformId, emulator: EmulatorId?)
    suspend fun openEmulator(emulator: EmulatorId)
    fun limitations(emulator: EmulatorId): List<String>
    fun homepage(emulator: EmulatorId): String?

    /** True where Fuse can be pointed at an emulator it didn't find (Windows and macOS). */
    val canLocate: Boolean get() = false

    /** Every emulator Fuse can start on this device, found or not, by name. */
    fun known(): List<EmulatorOption> = emptyList()

    /** Where the user located each emulator, by id. */
    val located: StateFlow<Map<EmulatorId, String>> get() = MutableStateFlow(emptyMap())

    /** Uses the program at [path] for [emulator] and looks again. False when Fuse can't run it. */
    suspend fun locate(emulator: EmulatorId, path: String): Boolean = false

    /** Forgets where [emulator] was located and looks again. */
    suspend fun forget(emulator: EmulatorId) = Unit

    /** Extra folders searched for emulators. */
    val searchFolders: StateFlow<List<String>> get() = MutableStateFlow(emptyList())

    suspend fun setSearchFolders(folders: List<String>) = Unit
}

interface MediaOps {
    fun media(owner: MediaOwner): Flow<io.github.matiyaaa.fuse.model.MediaSet>
    /** Searches the configured providers for art of [kind]. Never saves anything by itself. */
    suspend fun artworkOptions(owner: MediaOwner, kind: MediaKind): ArtworkResult
    /** Saves [option] as custom art (source USER). */
    suspend fun apply(owner: MediaOwner, option: ArtworkOption)
    suspend fun setFromFile(owner: MediaOwner, kind: MediaKind, path: String)
    suspend fun adjust(owner: MediaOwner, kind: MediaKind, focusX: Float, focusY: Float, zoom: Float)
    suspend fun reset(owner: MediaOwner, kind: MediaKind?)
    /**
     * Bulk fill for a game, platform or everything ([platform] and [game] null). Kinds no configured
     * source can return are left out, and a bulk "fill missing" skips games whose sources had
     * nothing a short while ago (a single game is always searched again).
     */
    fun fill(mode: MediaFillMode, kinds: Set<MediaKind>, platform: PlatformId? = null, game: GameId? = null)
    /** Every art kind and every detail the sources have, for games missing any, plus system art. */
    fun fillEverything(platform: PlatformId? = null)
    fun cancelFill()
    val fillProgress: StateFlow<FillProgress?>
    /** The name art and details are searched with for [game]; null when the game is gone. */
    suspend fun searchTitle(game: GameId): SearchTitle?
    /** Every match the configured sources list for [game] (by its search name), best first. Never saves anything. */
    suspend fun identify(game: GameId): IdentifyResult
    /** Links [game] to [candidate]: takes its name and details and replaces scraped art. False when the source no longer lists it. */
    suspend fun acceptCandidate(game: GameId, candidate: ScrapeCandidate): Boolean
    /**
     * Forgets what sources said about [game]: the name and details they gave it and the art they
     * found (RomM's included). The user's own name and picks and art from the game's folder stay.
     * For a game a source mixed up with another. False when the game is gone.
     */
    suspend fun resetDetails(game: GameId): Boolean
    /**
     * After the user renamed [game] (it went by [previous]): when the details it has were found
     * under a name that isn't this game's, they are forgotten and looked for again under the new
     * name, so a corrected game doesn't keep another game's description. True when it looked again.
     */
    suspend fun followRename(game: GameId, previous: String): Boolean = false
    val providers: StateFlow<List<ProviderStatus>>
    /** The last key check per provider; empty until a check ran. Checks run after a key is saved. */
    val keyChecks: StateFlow<Map<ScrapeProviderId, io.github.matiyaaa.fuse.integrations.KeyCheck?>>
    /** Tests every provider key that is set with a real request; null marks a check in progress. */
    fun checkKeys()
    /** Fetches logos, artwork and colours from the system art pack for every system with games. */
    fun downloadSystemArt()

    /**
     * Puts [platforms] (every system with games when empty) back to Fuse's own art: downloaded and
     * chosen art is removed and not downloaded again by itself. Null where this can't be done.
     */
    suspend fun restoreDefaultSystemArt(platforms: List<PlatformId>): ArtUndo? = null

    /** Puts back what [restoreDefaultSystemArt] replaced. */
    suspend fun undoSystemArt(undo: ArtUndo) {}

    /** [platform]'s games with screenshots (or a background) to make its panel from, the most recently played first. */
    suspend fun panelGames(platform: PlatformId): List<PanelGame> = emptyList()

    /** [game]'s screenshots and background, to pick a system's panel from. */
    suspend fun panelPictures(game: GameId): List<ArtworkOption> = emptyList()

    /** Makes [option] (one of [panelPictures]) [platform]'s panel, cut like the pack's; kept until changed. */
    suspend fun setSystemPanel(platform: PlatformId, option: ArtworkOption) {}

    /** [platform]'s panel goes back to the one Fuse picks from its games by itself. */
    suspend fun autoSystemPanel(platform: PlatformId) {}
    val systemArtProgress: StateFlow<FillProgress?>
}

sealed interface ArtworkResult {
    /** Art to pick from; [guess] is the game it was found for when that is only a best guess by name. */
    data class Options(val options: List<ArtworkOption>, val guess: ScrapeCandidate? = null) : ArtworkResult
    data class NeedsMatch(val candidates: List<ScrapeCandidate>) : ArtworkResult
    data class Unavailable(val reason: String) : ArtworkResult
}

/** The name searches use for a game: [custom] when the user set it, else [default] (its title). */
data class SearchTitle(val current: String, val custom: Boolean, val default: String)

sealed interface IdentifyResult {
    /** Matches for [query], best first. */
    data class Matches(val query: String, val candidates: List<ScrapeCandidate>) : IdentifyResult
    data class Unavailable(val reason: String) : IdentifyResult
}

data class RecentDownload(val download: io.github.matiyaaa.fuse.model.CartridgeDownload, val game: GameCard?)

data class FillProgress(
    val done: Int,
    val total: Int,
    /** The game (or system) being worked on; with several at once, the latest one started. */
    val current: String?,
    /** Images added. */
    val added: Int,
    val finished: Boolean,
    /** Games whose details (description, year, genres, series, rating) were filled in. */
    val details: Int = 0,
    /** Games with several close matches, for the user to pick in Identify game. */
    val needsYou: List<FillChoice> = emptyList(),
    val cancelled: Boolean = false,
    /** Sources resting (out of requests, key rejected or not answering); the others carry on. */
    val paused: List<String> = emptyList(),
    /** Started by Fuse after a scan rather than by the user. */
    val automatic: Boolean = false,
) {
    val fraction: Float get() = if (total <= 0) 1f else (done.toFloat() / total).coerceIn(0f, 1f)
}

/** A game a fill couldn't name by itself. */
data class FillChoice(val game: GameId, val title: String)

interface CollectionOps {
    val collections: StateFlow<List<GameCollection>>
    suspend fun create(name: String): CollectionId
    suspend fun rename(id: CollectionId, name: String)
    suspend fun delete(id: CollectionId)
    suspend fun add(id: CollectionId, game: GameId)
    suspend fun addGames(id: CollectionId, games: List<GameId>)
    suspend fun remove(id: CollectionId, game: GameId)
    suspend fun membership(game: GameId): Set<CollectionId>
    /** Makes a series the user's own collection: Fuse stops changing it (hide its name from series too). */
    suspend fun keepSeries(id: CollectionId)
}

interface AchievementOps {
    /** Null when RetroAchievements isn't set up. */
    val feed: StateFlow<AchievementsFeed?>
    val configured: StateFlow<Boolean>
    fun refresh(force: Boolean = false)
    suspend fun forGame(game: GameId): AchievementState?
    /** Checks a username + key against RetroAchievements and stores them securely on success. */
    suspend fun connect(username: String, apiKey: String): Result<Unit>
    suspend fun disconnect()
}

interface AppOps {
    val supported: Boolean
    /** Whether apps that are games join the library in the Android system (Android only). */
    val gamesInLibrary: Boolean get() = false
    fun apps(filter: AppFilter): Flow<List<AppCard>>
    /** Every installed app, hidden ones too, by name (for pickers such as "Add a game"). */
    fun everyApp(): Flow<List<AppCard>> = apps(AppFilter.ALL)
    /** [display] is the screen to open on (main when null or on devices with one screen). */
    suspend fun launch(app: AppCard, display: LaunchDisplay? = null)
    suspend fun setPinned(app: AppCard, pinned: Boolean)
    suspend fun setHidden(app: AppCard, hidden: Boolean)
    suspend fun rename(app: AppCard, title: String?)
    suspend fun openInfo(app: AppCard)

    /** Says what the app with [appId] is: a game joins the Android system, an emulator lists under Emulators. Null lets Fuse decide. */
    suspend fun setKind(appId: String, kind: AppKind?) = Unit

    /**
     * Hands the APK at [path] to Android's installer (which asks the user) and makes the app a game
     * once it is installed. The file itself is left where it is.
     */
    suspend fun installGame(path: String): ApkInstall = ApkInstall.Failed("Apps can't be installed on this system.")
}

/** What happened when an APK was handed to the system installer. */
sealed interface ApkInstall {
    /** Android is asking the user to confirm. */
    data class Started(val packageName: String, val label: String) : ApkInstall
    data class Failed(val message: String) : ApkInstall
}

interface CartridgeOps {
    val status: StateFlow<CartridgeStatus>
    /** Cartridge's latest downloads, matched to games in the library when Fuse has found them. */
    val recent: StateFlow<List<RecentDownload>>
    fun open(route: CartridgeRoute)
    /** Latest Cartridge release for this platform (for "Install Cartridge"). */
    suspend fun latestRelease(): ReleaseInfo?
    /** Downloads and hands the release to the system installer. Only after the user confirmed. */
    suspend fun install(release: ReleaseInfo): Result<Unit>
    fun refresh()

    /**
     * Hands [game] to Cartridge to upload to RomM: every file of it, other discs, DLC and updates
     * included. Cartridge shows what it would send and uploads only after the user confirms there.
     */
    suspend fun upload(game: GameId): UploadHandoff

    /** Short messages for the user, such as names put back after RomM had mixed games up. */
    val notices: kotlinx.coroutines.flow.Flow<String> get() = kotlinx.coroutines.flow.emptyFlow()

    /**
     * Checks every game that has RomM's details against its own names and puts back the ones that
     * are another game's (see CartridgeDetails). Returns how many were put back.
     */
    suspend fun checkRommMatches(): Int = 0
}

/** What happened when Fuse handed a game to Cartridge to upload. */
enum class UploadHandoff {
    /** Cartridge is showing the upload. */
    OPENED,
    NOT_INSTALLED,
    /** This Cartridge can't take uploads (bridge protocol 3). */
    TOO_OLD,
    /** None of the game's files were found. */
    NO_FILES,
    /** Cartridge didn't open. */
    FAILED,
}

/** Global -> Platform -> Game settings with visible inheritance. */
interface ScopedSettingsOps {
    fun <T> observe(key: ScopedKey<T>, platform: PlatformId?, game: GameId?): Flow<Resolved<T>>
    suspend fun <T> set(key: ScopedKey<T>, scope: ScopeRef, value: T)
    suspend fun <T> clear(key: ScopedKey<T>, scope: ScopeRef)
    suspend fun setLayout(platform: PlatformId?, layout: LibraryLayout)
    suspend fun setBorder(scope: ScopeRef, border: BorderStyle)
}

interface CredentialOps {
    /** Which secrets are stored (never the values). */
    val stored: StateFlow<Set<String>>
    suspend fun put(key: String, value: String)
    suspend fun remove(key: String)
}

interface UpdateOps {
    val available: StateFlow<ReleaseInfo?>
    /** Where an update stands: downloading with progress, ready to install, installing, failed. */
    val state: StateFlow<UpdateState>
    suspend fun check(): ReleaseInfo?
    /** Starts downloading [release] in the background (again after a failure). A second call while it runs does nothing. */
    fun download(release: ReleaseInfo)
    fun cancelDownload()
    /** Installs the ready update. Success(true): restart Fuse now; Success(false): the system took over. */
    suspend fun apply(): Result<Boolean>
    val currentVersion: String

    /** False where an update is got from its release page instead (Windows and macOS). */
    val inPlace: Boolean get() = true
}

sealed interface UpdateState {
    data object Idle : UpdateState
    /** [progress] 0..1, or null while the size is unknown. */
    data class Downloading(val release: ReleaseInfo, val progress: Float?) : UpdateState
    data class Ready(val release: ReleaseInfo, val file: String) : UpdateState
    data class Installing(val release: ReleaseInfo) : UpdateState
    data class Failed(val release: ReleaseInfo, val message: String) : UpdateState
}

/**
 * Phone Link as Settings sees it. The server itself lives in :ui:link and follows
 * [UiPrefs.phoneLinkEnabled]; account changes happen only here, on the device.
 */
interface PhoneLinkControl {
    val state: StateFlow<PhoneLinkState>
    /** Sets the username and password phones sign in with; every signed-in phone is signed out. */
    suspend fun setAccount(username: String, password: String): Result<Unit>
    suspend fun signOutAll()
    /** The QR code for [text] as rows of dark modules. */
    fun qr(text: String): List<BooleanArray>?

    /**
     * A link that signs a phone in without the password and opens its keyboard, for the code beside
     * the on-screen keyboard: single use, for two minutes. [address] is one of [PhoneLinkState.addresses]
     * (the first when null). Null while Phone Link isn't running or there is no network.
     */
    suspend fun pairingLink(address: String? = null): String? = null
}

data class PhoneLinkState(
    val running: Boolean = false,
    /** Addresses phones open, like "http://192.168.1.20:47300/". Empty when not on a network. */
    val addresses: List<String> = emptyList(),
    val username: String? = null,
    val sessions: Int = 0,
    val error: String? = null,
)

/** What a "Restore Fuse Default Art" replaced, for Undo; [count] systems had art of their own. */
interface ArtUndo {
    val count: Int
}
