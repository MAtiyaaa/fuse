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
import io.github.matiyaaa.fuse.model.RecentAchievement
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
import kotlinx.coroutines.flow.map

/**
 * Everything the interface reads and does, in one place. Screens only talk to this interface; the
 * default implementation composes the database, scanner, launch system and integrations. Every
 * call is safe from the UI thread: reads are flows, writes are suspend or fire-and-forget and run
 * on background dispatchers.
 */
interface FuseStore {
    /** Standby belongs to this running installation, rather than to one window. */
    val displaySession: DisplaySession
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

    /** Fuse Performance Observatory: background work's cost, and how quickly the library answers. */
    val observatory: ObservatoryOps get() = ObservatoryOps.None

    /** How each game runs on this device, in the person's own words (compatibility reports). */
    val compat: CompatOps get() = CompatOps.None

    /** The Store, where Fuse can install apps (Android); [AppStoreOps.None] elsewhere. */
    val appStore: AppStoreOps get() = AppStoreOps.None

    /** Fuse Ports, on a computer; [PortsOps.None] elsewhere. */
    val ports: PortsOps get() = PortsOps.None

    /** Jellyfin, an addon; idle until it is turned on in Settings, Addons, Jellyfin. */
    val jellyfin: io.github.matiyaaa.fuse.jellyfin.JellyfinService? get() = null

    /** What Home shows: the library's feed, with Jellyfin's widgets when there are any. */
    val homeFeed: kotlinx.coroutines.flow.StateFlow<HomeFeed> get() = library.home

    /** Games, updates, DLC and licences installed into RPCS3 and Vita3K by Fuse itself. */
    val content: ContentOps get() = ContentOps.None

    /** What installed emulators can be given beyond their games (graphic packs, patches, firmware, Pretendo). */
    val extras: ExtrasOps get() = ExtrasOps.None

    /**
     * Starts what Fuse does by itself (scans, art fills, Cartridge, achievements, update checks)
     * when the store was created in safe mode, which holds it back. Does nothing otherwise.
     */
    fun resumeAutomaticWork() = Unit

    /** Fuse Sync by Fuse: off and invisible until turned on in Settings, Addons, Fuse Sync. */
    val sync: SyncOps get() = SyncOps.None

    /** Downloads: every transfer Fuse makes for the person, in one place. */
    val transfers: TransfersOps get() = TransfersOps.None

    /** RomM, the RomM native integration. */
    val romm: RommOps get() = RommOps.None

    /** The Fuse Library: the household's games on its other devices, through Fuse Sync. */
    val reach: ReachOps get() = ReachOps.None

    /** Streaming games from a computer at home with Moonlight. */
    val streaming: StreamingOps get() = StreamingOps.None

    /** Jellyfin films and episodes kept on this device for watching offline. */
    val offlineMedia: OfflineMediaOps get() = OfflineMediaOps.None

    /** Syncthing, for people who run it: off until chosen in setup or Settings, Addons, Syncthing. */
    val syncthing: io.github.matiyaaa.fuse.sync.syncthing.SyncthingService? get() = null
    /** RomM's save sync, when RomM is set up here. */
    val rommSaves: io.github.matiyaaa.fuse.sync.romm.RommSaveService? get() = null

    /**
     * One of Fuse's songs ([io.github.matiyaaa.fuse.ui.shell.music.BundledMusic]) as a file the player
     * can open: unpacked when it ships inside Fuse, fetched and kept the first time otherwise. Null
     * when it isn't one, or it can't be fetched now (offline).
     */
    suspend fun bundledTrack(id: String): String? = null

    /** Whether the song [id] can play now without the network. */
    suspend fun musicHere(id: String): Boolean = false

    /** Fetches every song not kept yet, saying how far it has got; returns how many couldn't be fetched. */
    suspend fun downloadAllMusic(onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }): Int = 0

    /** Forgets the songs that were fetched (the two inside Fuse stay). */
    suspend fun removeDownloadedMusic() = Unit

    /** What moves this device's saves (only one at a time); the others' save sync is turned off here. */
    suspend fun setSaveProvider(provider: io.github.matiyaaa.fuse.data.settings.SaveProvider) = Unit
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
     * What the interface found under [key] (Fuse's entries in Steam, which only it can read), shown
     * with the store's own findings; an empty list takes them away.
     */
    fun setFound(key: String, issues: List<HealthIssue>) = Unit

    /**
     * A diagnostics report for a bug report: versions, systems, emulators found, drives and folders
     * with their state, integrations on or off, the health findings and recent launch problems, plus
     * [device] lines the interface knows. Personal folder names and anything secret are removed.
     * Nothing is saved or sent; the caller shows it first.
     */
    suspend fun diagnostics(device: List<String> = emptyList(), crash: String? = null): String = ""

    /**
     * Library Health: pictures whose files are gone go back to their web address, or are forgotten
     * so a fill finds others; pictures the person chose are left as they are. Returns how many changed.
     */
    suspend fun repairPictures(): Int = 0

    /** Library Health: forgets the pictures of games no longer in the library (their files stay). Returns how many. */
    suspend fun tidyPictures(): Int = 0

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
 * file) and deleting games' files: in Settings, Storage, and from a game's own options.
 */
interface StorageOps {
    /** The latest measurement; null until [refresh] first runs. Updates as games are measured. */
    val usage: StateFlow<StorageUsage?>
    /** Measures every game again. */
    fun refresh()
    /** What [game] takes on disk, or null when it is gone. */
    suspend fun size(game: GameId): Long?
    /** Every file [game] is made of on disk (what deleting it removes), or null when it is gone. */
    suspend fun files(game: GameId): io.github.matiyaaa.fuse.library.storage.GameFileSet? = null
    /**
     * Deletes the games' files (all discs, tracks and folder contents; never saves next to them)
     * and forgets the games. Only paths inside the game's library folder are touched.
     */
    suspend fun delete(games: List<GameId>): DeleteReport

    /** Connected drives games can be moved to, each with its games folder when it has one. */
    suspend fun moveTargets(): List<MoveTarget> = emptyList()

    /** What Fuse's own art and caches take (the system may clear them), or null when it can't tell. */
    suspend fun cacheBytes(): Long? = null

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
    /** Only the library's games in this list (Unplayed, Play together...). */
    val smart: SmartFilter? = null,
    /**
     * Only games in the Journal with one of these statuses (any status when empty), in the Journal's
     * order: playing first, then wanted, on hold and the ones done with, each the latest set first.
     */
    val journal: Set<io.github.matiyaaa.fuse.model.PlayStatus>? = null,
)

/** A launcher found to move in from: where it keeps its files, and what it would bring here. */
data class MoveInView(val source: io.github.matiyaaa.fuse.library.movein.MoveInSource, val where: String, val report: MoveInReport)

/**
 * What moving in brings, or brought: the games it changes, and of them how many become favourites,
 * are hidden, gain play time or a last played, and gain a status or a rating; and the collections.
 */
data class MoveInReport(
    val games: Int = 0,
    val favourites: Int = 0,
    val hidden: Int = 0,
    val timed: Int = 0,
    val journal: Int = 0,
    val collections: Int = 0,
) {
    val isEmpty: Boolean get() = games == 0 && collections == 0
}

/** Which games a [GameQuery] lists: the library, or games whose files are gone, hidden or removed. */
enum class GameSet { LIBRARY, MISSING, HIDDEN, REMOVED }

/** One file of a game's saves on this device, as its emulator keeps it. */
/**
 * Where a game was left ([LibraryOps.resumePoint]): the emulator that carries on from it, when it
 * was made, and the device it was made on when Fuse One says (null when only this device knows it).
 */
data class ResumeInfo(val emulator: String, val at: Long?, val device: String? = null)

/** An emulator Fuse writes its hotkeys into: its name, and its family ([io.github.matiyaaa.fuse.launch.settings.Hotkeys.families]). */
data class HotkeyEmulator(val name: String, val family: String)

/** What [LibraryOps.applyHotkeys] did in one emulator: its name, and the actions left on the person's own binding. */
data class HotkeysApplied(val emulator: String, val kept: List<String>)

data class LocalSave(
    val name: String,
    val path: String,
    val kind: io.github.matiyaaa.fuse.sync.SaveKind,
    val bytes: Long,
    val modifiedAt: Long,
)

/**
 * A game's saves on this device ([LibraryOps.localSaves]): the files where [emulator] keeps them,
 * newest first, and [note] when Fuse can't look (no emulator plays it here, or one Fuse can't read).
 */
data class LocalSaves(
    val emulator: String?,
    val saves: List<LocalSave>,
    val note: String? = null,
    /** Newer saves of it in folders the emulator used before (another install, a moved data folder). */
    val older: List<io.github.matiyaaa.fuse.sync.OldSave> = emptyList(),
)

/** How many games a view holds ([LibraryOps.counts]), and how many of them are favourites and were played. */
data class GameCounts(val total: Int = 0, val favorites: Int = 0, val played: Int = 0)

/**
 * A system's play on this device, for the Systems page: the time played in all, how many of its
 * games have been played, and the few played last and played most.
 */
data class SystemActivity(
    val seconds: Long = 0,
    val played: Int = 0,
    val recent: List<GameCard> = emptyList(),
    val mostPlayed: List<GameCard> = emptyList(),
)

interface LibraryOps {
    val home: StateFlow<HomeFeed>

    /** [id]'s saves on this device, with or without any save sync. */
    suspend fun localSaves(id: GameId): LocalSaves = LocalSaves(null, emptyList())

    /**
     * Where [id] was left, when the emulator it starts in here can carry on from there (Continue):
     * its resume state on this device (Fuse One puts the newest in place as the game starts), when
     * it was made and on which device. Null when there is none, or Continue is off.
     */
    suspend fun resumePoint(id: GameId): ResumeInfo? = null

    /** Whether the game being played can be asked to close from Fuse (on Linux and macOS). */
    val canQuitPlaying: Boolean get() = false

    /**
     * Asks the game being played to close, as closing its window would; its play time is kept
     * and its saves go up as it ends, as after any game. False when none of Fuse's is running.
     */
    suspend fun quitPlaying(): Boolean = false

    /** The emulators here Fuse can write the same hotkeys into (set up, on a computer). */
    suspend fun hotkeysHere(): List<HotkeyEmulator> = emptyList()

    /**
     * Fuse's hotkeys written into every emulator here that takes them ([on]), each beside the
     * person's own bindings, or taken out again. What each emulator got; one the settings can't
     * be changed in is left out.
     */
    suspend fun applyHotkeys(on: Boolean): List<HotkeysApplied> = emptyList()

    /** [old]'s save copied to where [id]'s emulator reads it now, the one there kept aside first; where it was kept. */
    suspend fun useOldSave(id: GameId, old: io.github.matiyaaa.fuse.sync.OldSave): Result<String?> = Result.failure(UnsupportedOperationException())

    /** Today's game in [platform]'s spotlight, and why; null while the system has no games. */
    fun spotlight(platform: PlatformId): Flow<Suggestion?> = kotlinx.coroutines.flow.flowOf(null)
    val platforms: StateFlow<List<PlatformCard>>
    fun games(query: GameQuery): Flow<List<GameCard>>

    /**
     * How many games [query] lists, and how many of them are favourites and played ([GameCounts]):
     * for the counts on the Library's tabs. Worked out from the games alone, never their art, so a
     * count costs nothing like the list it counts.
     */
    fun counts(query: GameQuery): Flow<GameCounts> = games(query).map { list ->
        GameCounts(list.size, list.count { it.favorite }, list.count { it.lastPlayedAt != null })
    }

    /** How many of the library's games (of [platform], or every system) each [SmartFilter] holds, from the games alone. */
    fun smartCounts(platform: PlatformId?): Flow<Map<SmartFilter, Int>> = kotlinx.coroutines.flow.flowOf(emptyMap())

    /**
     * [platform]'s play here ([SystemActivity]): worked out from its games without their art, the
     * art read only for the [shown] played last and the [shown] played most.
     */
    fun systemActivity(platform: io.github.matiyaaa.fuse.model.PlatformId, shown: Int = 4): Flow<SystemActivity> =
        games(GameQuery(platform = platform)).map { list ->
            val played = list.filter { it.lastPlayedAt != null }
            SystemActivity(
                list.sumOf { it.playSeconds }, played.size,
                played.sortedByDescending { it.lastPlayedAt }.take(shown),
                list.filter { it.playSeconds > 0 }.sortedByDescending { it.playSeconds }.take(shown),
            )
        }

    /** The ids of the games [query] lists, in its order, without their art: for code that only needs which games there are. */
    fun gameIds(query: GameQuery): Flow<List<GameId>> = games(query).map { list -> list.map { it.id } }
    fun game(id: GameId): Flow<GameDetail?>

    /** [game] as its page shows it when opened from a list that plays it [via] its port or its emulator. */
    fun game(id: GameId, via: PlayVia): Flow<GameDetail?> = game(id)
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
        /** Played with others: hosting it here, or joining someone's (see [together]). */
        together: Together? = null,
        /** With its port, or its emulator, as the list it was started from says ([PlayVia]). */
        via: PlayVia = PlayVia.DEFAULT,
        /** Carry on from where it was left ([resumePoint]), where its emulator can; otherwise it starts as always. */
        resume: Boolean = false,
        /** Told as the launch moves on: checking the save first (when Fuse Sync or Syncthing does), then starting. */
        onStage: (LaunchStage) -> Unit = {},
    ): LaunchOutcome

    /**
     * Returns once the game playing now (if any) has ended and everything after it is done: its
     * session counted, its saves kept and sent where they go. Fast Start waits on it before Fuse
     * leaves, so nothing a game made is left half done.
     */
    suspend fun awaitPlayed() {}

    /**
     * The emulator's own command for [id], exactly as Play would start it now (its emulator, core,
     * disc playlist and screen), for a Steam entry that starts the emulator with nothing of Fuse's in
     * between; or, in words, why the game can't be started that way.
     */
    suspend fun directCommand(id: GameId): DirectCommand = DirectCommand.Unavailable("Only Fuse can start it here")

    /**
     * A session of [id] played from its Direct entry in Steam, as the Fuse Decky plugin saw Steam
     * start and stop it: counted once, like one Fuse saw. False when it was already counted.
     */
    suspend fun recordSteamSession(id: GameId, startedAt: Long, endedAt: Long): Boolean = false

    /** lsfg-vk is set up on this device, so frame generation can be chosen (Linux). */
    fun frameGenerationReady(): Boolean = false

    /** Every game's own files (its path, and the file it starts from), each to its game: for finding shortcuts others made. */
    suspend fun gameFiles(): Map<String, GameId> = emptyMap()

    /** Called when Fuse comes back to the foreground: closes the running session, checks for changes. */
    fun onResume()
    fun onPause()

    suspend fun setFavorite(id: GameId, favorite: Boolean)

    /**
     * [id]'s Journal as [journal] says: where the person is with it (when it was set comes along as
     * it changes), their rating and their note. A game from RomM takes it there too while changes go.
     */
    suspend fun setJournal(id: GameId, journal: io.github.matiyaaa.fuse.model.GameJournal) {}

    /** The launchers found on this device that the person could move in from, each with what it would bring here. */
    suspend fun moveInLook(): List<MoveInView> = emptyList()

    /**
     * Brings in what [sources] say about the games here: favourites, hidden games, play time and
     * last played, completed games and ratings into the Journal, and collections. Nothing set in
     * Fuse is undone, and all of it can be put back with [undoMoveIn].
     */
    suspend fun moveIn(sources: Set<io.github.matiyaaa.fuse.library.movein.MoveInSource>): MoveInReport = MoveInReport()

    /** Puts back what the last move in changed; false when there is none to put back. */
    suspend fun undoMoveIn(): Boolean = false

    /** When the last move in that can still be put back was made, or null. */
    suspend fun lastMoveIn(): Long? = null

    /** How many of the library's games (of [platform], or every system) the Journal has with each status. */
    fun journalCounts(platform: PlatformId?): Flow<Map<io.github.matiyaaa.fuse.model.PlayStatus, Int>> = kotlinx.coroutines.flow.flowOf(emptyMap())
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

    /**
     * Removes removed games for good: their art goes and they leave the Removed view, while Fuse
     * still remembers them so a scan never brings them back. Files are never touched.
     */
    suspend fun purgeRemoved(ids: List<GameId>) {}

    /** The library's game for Steam's app [appId], when Steam's game is in the library here. */
    suspend fun steamGame(appId: Long): GameId? = null

    /** Clean Display Names: preview, apply and undo. Files are never renamed. */
    suspend fun previewCleanNames(): List<Pair<String, String>>
    suspend fun applyCleanNames(enabled: Boolean)
    suspend fun undoCleanNames(): Boolean

    /** Folder browser for FOLDER_BROWSER games: files inside the game folder that could be launched. */
    suspend fun launchCandidates(id: GameId): List<String>

    /**
     * What a PlayStation or PS2 disc image says about itself (its serial, and PCSX2's CRC for PS2
     * discs), read from the image (ISO, BIN, CHD, CSO or ZSO) once and remembered. Null for other
     * games and images that can't be read.
     */
    suspend fun discIdentity(id: GameId): io.github.matiyaaa.fuse.library.disc.DiscIdentity? = null

    /** How long [id] takes to beat (HowLongToBeat), or null when it's off, unknown or can't be asked now. */
    suspend fun howLongToBeat(id: GameId): HltbView? = null

    /** HowLongToBeat's games named like [id], for choosing the right one. */
    suspend fun howLongToBeatChoices(id: GameId): List<io.github.matiyaaa.fuse.integrations.hltb.HltbGame> = emptyList()

    /** The person's choice for [id]: [game], or none of them (null), kept from now on. */
    suspend fun chooseHowLongToBeat(id: GameId, game: io.github.matiyaaa.fuse.integrations.hltb.HltbGame?) {}

    /** Every library game's household keys (Fuse Identity), its own first: what saves and play time from other devices are filed under. */
    suspend fun householdKeys(): Map<GameId, List<String>> = emptyMap()

    /**
     * What RPCS3's public compatibility list says about the PS3 game [id], by its title id. Asks
     * rpcs3.net only when called (the user asked), sending only the title id; answers are kept a week.
     */
    suspend fun rpcs3Compatibility(id: GameId): CompatibilityAnswer = CompatibilityAnswer.Unreachable

    /**
     * The patches and cheats for the game [id] in its emulator's own settings (PCSX2, DuckStation,
     * PPSSPP), each with who turned it on. Reads only.
     */
    suspend fun gamePatches(id: GameId): GamePatchList = GamePatchList.Unavailable("Not here", "Patches and cheats can only be changed from Fuse on a computer.")

    /**
     * Turns the patch or cheat [name] on or off in the emulator's settings for [id]. Fuse turns off
     * only what it turned on itself; anything set in the emulator stays as it is. False when nothing
     * changed.
     */
    suspend fun setGamePatch(id: GameId, name: String, on: Boolean, cheat: Boolean = false): Boolean = false

    /** The cheats, patches and texture packs a person added to [id] from files of their own, and what can be added here. */
    suspend fun enhancements(id: GameId): Enhancements = Enhancements(emptyList(), emptyList())

    /**
     * What adding [source] (a file, folder or `.zip`) to [id] as [kind] would do: which files go in,
     * which they replace, which settings change, and what is left out. Nothing changes yet.
     */
    suspend fun planEnhancement(id: GameId, kind: io.github.matiyaaa.fuse.launch.patches.EnhancementKind, source: String): EnhancementPreview =
        EnhancementPreview.Unavailable("Not here", "Your own cheats, patches and textures can be added from Fuse on a computer.")

    /** Adds what the preview with [token] showed, after making a restoration point. Null when it worked, else why not. */
    suspend fun addEnhancement(token: String): String? = "Not here."

    /** Which game on GameBanana [id] is, and the games a search for [query] (else its name) finds there. */
    suspend fun modSiteGames(id: GameId, query: String? = null): ModSiteGames = ModSiteGames(null, emptyList(), query.orEmpty(), "Mods can be found from Fuse on a computer.")

    /** Remembers [game] as [id]'s game on GameBanana (null forgets it). */
    suspend fun linkModSiteGame(id: GameId, game: io.github.matiyaaa.fuse.integrations.gamebanana.GbGame?) = Unit

    /** A page (from 1) of the mods on GameBanana for [id]'s game there, most liked first. */
    suspend fun modSiteMods(id: GameId, page: Int = 1): ModSiteMods = ModSiteMods.Unavailable("Not here", "Mods can be found from Fuse on a computer.")

    /** A mod's page on GameBanana, with what Fuse can do with each of its files for [id]. */
    suspend fun modSitePage(id: GameId, modId: Long): ModSitePage = ModSitePage.Unavailable("Not here", "Mods can be found from Fuse on a computer.")

    /**
     * Fetches the file [fileId] of mod [modId] into Fuse's own folder (checked against GameBanana's
     * size and checksum) and works out what adding it to [id] would do, as [planEnhancement] does.
     * Nothing in the emulator's folders changes yet.
     */
    suspend fun fetchModSiteFile(id: GameId, modId: Long, fileId: Long, onProgress: (Float) -> Unit = {}): EnhancementPreview =
        EnhancementPreview.Unavailable("Not here", "Mods can be added from Fuse on a computer.")

    /** The Nexus Mods account whose key is on this device, checked with Nexus Mods. */
    suspend fun nexusAccount(): NexusAccount = NexusAccount.NoKey

    /** Which game on Nexus Mods [id] is, and the games its list has by [query] (else the game's name). */
    suspend fun nexusGames(id: GameId, query: String? = null): NexusGames = NexusGames(null, emptyList(), query.orEmpty(), "Mods can be found from Fuse on a computer.")

    /** Remembers [game] as [id]'s game on Nexus Mods (null forgets it). */
    suspend fun linkNexusGame(id: GameId, game: io.github.matiyaaa.fuse.integrations.nexus.NexusGame?) = Unit

    /** [id]'s game's trending, latest added and latest updated mods on Nexus Mods. */
    suspend fun nexusMods(id: GameId): NexusModList = NexusModList.Unavailable("Not here", "Mods can be found from Fuse on a computer.")

    /** A mod on Nexus Mods ([game], [modId]), with what Fuse can do with each of its files for [id]. */
    suspend fun nexusPage(id: GameId, game: String, modId: Long): NexusPage = NexusPage.Unavailable("Not here", "Mods can be found from Fuse on a computer.")

    /**
     * Fetches [fileId] of a mod on Nexus Mods into Fuse's own folder and works out what adding it to
     * [id] would do, as [planEnhancement] does: straight away for a Premium account, else with the
     * website's [nxm] link for the file. Nothing in the emulator's folders changes yet.
     */
    suspend fun fetchNexusFile(id: GameId, game: String, modId: Long, fileId: Long, nxm: String? = null, onProgress: (Float) -> Unit = {}): EnhancementPreview =
        EnhancementPreview.Unavailable("Not here", "Mods can be added from Fuse on a computer.")

    /** Playing [id] with others here: its emulator's way, what it needs, and who in the household hosts it now. */
    suspend fun together(id: GameId): TogetherView = TogetherView.Unavailable("Not here", "Playing together is set up from Fuse on a computer.")

    /**
     * Sets [id]'s emulator up for playing with others, as its own settings: RPCS3's network and
     * RPCN, PCSX2's network adapter, PPSSPP's wireless, Dolphin's name. Each change can be put back.
     */
    suspend fun setUpTogether(id: GameId): Boolean = false

    /**
     * After an emulator ([family], `ppsspp`) was updated: whether what [setUpTogether] set in it is
     * still there; the words to say when it isn't, else null.
     */
    suspend fun recheckTogether(family: String): String? = null

    /** Puts back what [setUpTogether] and hosting or joining changed in [id]'s emulator. */
    suspend fun putBackTogether(id: GameId): Boolean = false

    /** Turns Dolphin's WiiConnect24 through WiiLink [on] or off, for [id] (a Wii game); put back with the rest. */
    suspend fun setWiiLink(id: GameId, on: Boolean): Boolean = false

    /**
     * Readies [id]'s emulator for [together] without starting it, for an emulator whose own window
     * starts it (Dolphin's NetPlay): null when done, else why not.
     */
    suspend fun readyTogether(id: GameId, together: Together): String? = "Playing together is set up from Fuse on a computer."

    /**
     * Signs RPCS3 in to RPCN as [user] for [id]'s emulator: the password is stretched as RPCS3 does
     * and used once; the [token] is the one RPCN sent by email. Null when done, else why not.
     */
    suspend fun signInRpcn(id: GameId, user: String, password: String, token: String): String? = "RPCN is set up from Fuse on a computer."

    /** Signs RPCS3 out of RPCN (its user name stays). */
    suspend fun signOutRpcn(id: GameId): Boolean = false

    /**
     * Makes an RPCN account as [user] with [password] and [email] for [id]'s emulator (RPCS3), as
     * RPCS3's own dialog makes it, and signs RPCS3 in with it; RPCN then emails a token for
     * [rpcnToken]. Null when made, else why not.
     */
    suspend fun createRpcn(id: GameId, user: String, password: String, email: String): String? = "RPCN is set up from Fuse on a computer."

    /** Gives RPCS3's RPCN account the [token] RPCN emailed. Null when done, else why not. */
    suspend fun rpcnToken(id: GameId, token: String): String? = "RPCN is set up from Fuse on a computer."

    /** What EmuCoreX's public catalogues have for [id] (a PS2 game): texture packs by its serial, pnach files by its CRC. */
    suspend fun emuCoreX(id: GameId): EmuCoreXFinds = EmuCoreXFinds(null, null, emptyList(), emptyList(), "EmuCoreX's packs and cheats can be added from Fuse on a computer.")

    /**
     * Fetches EmuCoreX's entry [entryId] for [id] into Fuse's own folder (a pack checked against the
     * catalogue's size and SHA-256) and works out what adding it would do, as [planEnhancement] does.
     */
    suspend fun fetchEmuCoreX(id: GameId, entryId: String, onProgress: (Float) -> Unit = {}): EnhancementPreview =
        EnhancementPreview.Unavailable("Not here", "EmuCoreX's packs and cheats can be added from Fuse on a computer.")

    /** Takes out [enhancement] from [id], putting back what was there before; [force] even when it was changed since. */
    suspend fun removeEnhancement(id: GameId, enhancement: String, force: Boolean = false): EnhancementRemoval =
        EnhancementRemoval.Failed("Not here.")

    /**
     * The emulator's own settings (PCSX2, DuckStation, PPSSPP) for the game [id], or for every game
     * in [emulator] when [id] is null, each with where its value comes from.
     */
    suspend fun emulatorSettings(id: GameId?, emulator: EmulatorId? = null): EmulatorSettingsView =
        EmulatorSettingsView.Unavailable("Not here", "Emulator settings can only be changed from Fuse on a computer.")

    /** Gives [setting] the [value] for the game [id] (or every game); null makes a game follow every game's again. */
    suspend fun setEmulatorSetting(id: GameId?, emulator: EmulatorId?, setting: String, value: String?): Boolean = false

    /** Puts back what Fuse changed in these settings; anything changed in the emulator since stays. */
    suspend fun restoreEmulatorSettings(id: GameId?, emulator: EmulatorId?): Boolean = false

    /**
     * Every setting the emulator's own code has (Fuse Tune), for the game [id] or for every game in
     * [emulator], as the installed version has them, each with its value and where it comes from.
     */
    suspend fun everyEmulatorSetting(id: GameId?, emulator: EmulatorId? = null): EverySettingView =
        EverySettingView.Unavailable("Not here", "Emulator settings can only be changed from Fuse on a computer.")

    /**
     * Gives the Fuse Tune setting [setting] ([io.github.matiyaaa.fuse.launch.settings.TuneSetting.id])
     * the [value] for the game [id] (or every game); null makes a game follow every game's again, or
     * leaves every game with the emulator's default. A value the emulator doesn't read is refused.
     */
    suspend fun setEveryEmulatorSetting(id: GameId?, emulator: EmulatorId?, setting: String, value: String?): Boolean = false

    /** Gives the game [id] (in [emulator]'s settings) every value of [profile], one of [EmulatorSettingsView.Ready.profiles]. */
    suspend fun applySettingProfile(id: GameId?, emulator: EmulatorId?, profile: String): Boolean = false

    /** Keeps the game [id]'s settings now as a profile of the person's own called [name], for their other games. */
    suspend fun saveSettingProfile(id: GameId?, emulator: EmulatorId?, name: String): Boolean = false

    /** Deletes one of the person's own setting profiles; games given it keep their settings. */
    suspend fun deleteSettingProfile(profile: String): Boolean = false

    /** Play time today, this week, this month and in all, per day, per game and per system. */
    fun playTime(): Flow<PlayTimeReport> = kotlinx.coroutines.flow.flowOf(PlayTimeReport(loaded = true))

    /**
     * Files the game under [platform] for good (a rescan keeps it), or back under the system its
     * folder says when null. Its emulator choice goes with the old system.
     */
    suspend fun setPlatform(id: GameId, platform: PlatformId?) = Unit

    /**
     * Adds the game file at [path], wherever it is on the device, to [platform], named [title] when
     * given (else by its file's name). Null when it can't be read.
     */
    suspend fun addGameFile(path: String, platform: PlatformId, title: String? = null): GameId? = null

    /**
     * Adds the game folder at [path] (a game with its updates, DLC and other parts, laid out the way
     * the library scan reads folders) to [platform]. Every game found in it; empty when there is none.
     */
    suspend fun addGameFolder(path: String, platform: PlatformId): List<GameId> = emptyList()

    /**
     * [child] is part of [owner] (an update, DLC or other content Fuse took for a game of its own):
     * it joins [owner]'s content for good, with its play time. False when they can't be joined.
     */
    suspend fun attachAsContent(child: GameId, owner: GameId, kind: io.github.matiyaaa.fuse.model.ContentKind): Boolean = false

    /** Undoes [attachAsContent]: [child] is a game again. The game it was part of, or null. */
    suspend fun detachContent(child: GameId): GameId? = null

    /**
     * Applies the IPS, UPS, BPS or xdelta patch at [patchPath] to [id]'s game file, as a new copy beside it
     * that joins the library: the game itself is never changed. UPS and BPS are checked against the
     * game they were made for, and a patch for another version is refused.
     */
    suspend fun applyPatch(id: GameId, patchPath: String): PatchApplied = PatchApplied.Failed("Patching isn't available here.")

    /** The parts the person attached to [owner]: id, title and kind. */
    suspend fun userParts(owner: GameId): List<Triple<GameId, String, io.github.matiyaaa.fuse.model.ContentKind>> = emptyList()

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

    /** Remembered local identities. Presence never claims an authenticated session or game licence. */
    suspend fun steamAccounts(): List<io.github.matiyaaa.fuse.library.steam.SteamAccount> = emptyList()

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

    /** Every emulator Fuse knows for this system, with what it supports and what Fuse has checked. */
    suspend fun support(): List<EmulatorSupportRow> = emptyList()

    /** [support] as plain text to share, with the home folder and user names taken out. */
    suspend fun supportReport(): String = ""

    /** Where the user located each emulator, by id. */
    val located: StateFlow<Map<EmulatorId, String>> get() = MutableStateFlow(emptyMap())

    /** Uses the program at [path] for [emulator] and looks again. False when Fuse can't run it. */
    suspend fun locate(emulator: EmulatorId, path: String): Boolean = false

    /** Forgets where [emulator] was located and looks again. */
    suspend fun forget(emulator: EmulatorId) = Unit

    /** The settings folder Fuse Intelligence found for [emulator] is wrong: forgotten, and never found again. */
    suspend fun rejectSettingsFolder(emulator: EmulatorId) = Unit

    /** Extra folders searched for emulators. */
    val searchFolders: StateFlow<List<String>> get() = MutableStateFlow(emptyList())

    suspend fun setSearchFolders(folders: List<String>) = Unit

    /**
     * Bringing a person's things over to [emulator] from another of its family: where they could
     * come from, and what was brought over already (to take back).
     */
    suspend fun familyMoves(emulator: EmulatorId): FamilyMoves = FamilyMoves(emptyList(), emptyList())

    /** What bringing [from]'s things over to [emulator] would copy; nothing changes until [bringOver]. */
    suspend fun planFamilyMove(emulator: EmulatorId, from: String): FamilyMovePreview =
        FamilyMovePreview.Unavailable("Not on this device", "This is done from Fuse on a computer.")

    /** Copies what [token]'s preview showed. Null when it worked, else why it didn't. */
    suspend fun bringOver(token: String): String? = "Not on this device."

    /** Takes back what was brought over as [move]: the files it copied go, and nothing else changes. */
    suspend fun takeBackFamilyMove(emulator: EmulatorId, move: String): Boolean = false

    /** Downloads the RetroArch [core] into [emulator] (a desktop RetroArch). Null when it worked, else why not. */
    suspend fun installCore(emulator: EmulatorId, core: String): String? = "Cores can't be downloaded here."
}

interface MediaOps {
    fun media(owner: MediaOwner): Flow<io.github.matiyaaa.fuse.model.MediaSet>
    /**
     * A game is focused (its room is up): when it has no art yet, it is looked for now, ahead of
     * the background fill, as opening it does. Once per game while Fuse runs.
     */
    fun focused(game: io.github.matiyaaa.fuse.model.GameId) {}
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
    fun fillEverything(platform: PlatformId? = null) = fillEverything(platform, remote = false)

    /**
     * Fill everything over the library, and with [remote] the Fuse Library too: RomM's games and
     * the household's other devices' games Fuse shows without having, remembered the same way.
     */
    fun fillEverything(platform: PlatformId?, remote: Boolean)
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

    /**
     * The sets this device keeps for the library's games (PS3, PS4, Vita and Xbox 360 trophies,
     * Steam's achievements), whether or not RetroAchievements is set up; null until first read.
     */
    val local: StateFlow<List<LocalGameAchievements>?> get() = kotlinx.coroutines.flow.MutableStateFlow(emptyList())
    fun refresh(force: Boolean = false)
    suspend fun forGame(game: GameId): AchievementState?
    /** Checks a username + key against RetroAchievements and stores them securely on success. */
    suspend fun connect(username: String, apiKey: String): Result<Unit>
    suspend fun disconnect()

    /** Each emulator here that keeps a RetroAchievements sign-in, and who it is signed in as. */
    suspend fun emulatorSignIns(): EmulatorSignIns = EmulatorSignIns.Unavailable("On this device, each emulator signs in to RetroAchievements from its own settings.")

    /**
     * Signs the account in use in to RetroAchievements with [password] (used for that one request
     * and kept nowhere) and every emulator here in with the login token it gives, each in its own
     * settings, as it would sign itself in.
     */
    suspend fun signInEmulators(password: String): EmulatorSignInResult = EmulatorSignInResult(problem = "Not available on this device.")

    /** Puts back what signing the emulators in changed. */
    suspend fun signOutEmulators(): Boolean = false

    /**
     * The game being played and the achievements unlocked in it as Fuse notices them; kept once it
     * closes ([PlayUnlocks.endedAt]), for its recap, until the next game starts.
     */
    val playing: StateFlow<PlayUnlocks> get() = MutableStateFlow(PlayUnlocks())

    /**
     * Something on screen shows unlocks as they come (the second screen while a game runs): until
     * the returned function is called, RetroAchievements is asked about new ones every minute. With
     * nothing showing them, it is asked once, as the game closes.
     */
    fun showLive(): () -> Unit = {}
}

/**
 * A game being played (or the one just played, [endedAt]) and what was unlocked in it, oldest first,
 * as RetroAchievements reported them. [set] is the game's set as it stands, those unlocks included;
 * null without one.
 */
data class PlayUnlocks(
    val game: GameId? = null,
    val since: Long = 0L,
    val endedAt: Long? = null,
    val set: AchievementState? = null,
    val unlocks: List<RecentAchievement> = emptyList(),
) {
    /**
     * With what RetroAchievements answered about [recent] unlocks: those in this game ([raGameId])
     * since it started (a little before, for clocks that differ) not already counted, oldest first.
     */
    fun with(recent: List<RecentAchievement>, raGameId: Long): PlayUnlocks {
        val known = unlocks.mapTo(HashSet()) { it.achievement.id }
        val news = recent
            .filter { it.achievement.gameId == raGameId && it.earnedAt >= since - CLOCK_SLACK_MS && it.achievement.id !in known }
            .distinctBy { it.achievement.id }
            .sortedBy { it.earnedAt }
        if (news.isEmpty()) return this
        return copy(unlocks = unlocks + news, set = set?.withUnlocks(news))
    }

    companion object {
        /** How far apart this device's clock and RetroAchievements' may be. */
        const val CLOCK_SLACK_MS = 2 * 60_000L
    }
}

/** This set with [news] unlocked: each one earned when it was, the counts and points with them. */
fun AchievementState.withUnlocks(news: List<RecentAchievement>): AchievementState {
    if (news.isEmpty()) return this
    val byId = news.associateBy { it.achievement.id }
    if (achievements.isEmpty()) {
        val fresh = news.size
        return copy(
            earned = (earned + fresh).coerceAtMost(total),
            earnedHardcore = (earnedHardcore + news.count { it.hardcore }).coerceAtMost(total),
            pointsEarned = (pointsEarned + news.sumOf { it.achievement.points }).coerceAtMost(points.takeIf { it > 0 } ?: Int.MAX_VALUE),
        )
    }
    var earnedNow = 0
    var hardNow = 0
    var pointsNow = 0
    val list = achievements.map { a ->
        val n = byId[a.id]
        when {
            n == null -> a
            n.hardcore && a.earnedHardcoreAt == null -> {
                if (!a.earned) { earnedNow++; pointsNow += a.points }
                hardNow++
                a.copy(earnedAt = a.earnedAt ?: n.earnedAt, earnedHardcoreAt = n.earnedAt)
            }
            !a.earned -> {
                earnedNow++
                pointsNow += a.points
                a.copy(earnedAt = n.earnedAt)
            }
            else -> a
        }
    }
    return copy(
        achievements = list,
        earned = (earned + earnedNow).coerceAtMost(total),
        earnedHardcore = (earnedHardcore + hardNow).coerceAtMost(total),
        pointsEarned = pointsEarned + pointsNow,
    )
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

    /** Attaches the app [appId], a recomp or port, to the library [game] it plays (null: to none). */
    suspend fun setPlays(appId: String, game: GameId?) = Unit

    /**
     * What the app [packageName] is ([kind], and for a port the [plays] game): at once when it is
     * installed, else once Android has installed it.
     */
    suspend fun expect(packageName: String, kind: AppKind, plays: GameId? = null) = Unit

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

    /** Why the last [check] couldn't find out (no connection, GitHub not answering), or null when it could. */
    val checkProblem: String? get() = null
    /** Starts downloading [release] in the background (again after a failure). A second call while it runs does nothing. */
    fun download(release: ReleaseInfo)
    fun cancelDownload()
    /** Installs the ready update. Success(true): restart Fuse now; Success(false): the system took over. */
    suspend fun apply(): Result<Boolean>
    val currentVersion: String

    /** False where an update is got from its release page instead (Windows and macOS). */
    val inPlace: Boolean get() = true

    /** Why Fuse can't update itself here, when it knows. */
    val manualReason: String? get() = null

    /** The version Fuse can go back to, while it is kept. */
    fun previous(): PreviousRelease? = null

    /** Goes back to [previous]. Success(true): restart Fuse now. */
    suspend fun goBack(): Result<Boolean> = Result.failure(UnsupportedOperationException())

    /** What Fuse says once at start about an update it went back from; null when nothing. */
    fun takeStartupNote(): String? = null
}

sealed interface UpdateState {
    data object Idle : UpdateState
    /**
     * [progress] 0..1, or null while the size is unknown; [doneBytes] of [totalBytes] so far, at
     * [bytesPerSecond] lately (null until it has moved for a moment).
     */
    data class Downloading(
        val release: ReleaseInfo,
        val progress: Float?,
        val doneBytes: Long? = null,
        val totalBytes: Long? = null,
        val bytesPerSecond: Long? = null,
    ) : UpdateState
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

    /** Switch save backups a phone sent through Phone Link, each ready to read: a folder or a ZIP here. */
    val switchSaves: kotlinx.coroutines.flow.Flow<String> get() = kotlinx.coroutines.flow.emptyFlow()
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

/** What applying a patch to a game came to. */
sealed interface PatchApplied {
    /**
     * The patched copy, [name], made beside the game and added to the library as [game]. [checked]
     * when the patch said which game it fits and this was it; [headerRemoved] when the game's copier
     * header was left off for it.
     */
    data class Made(val game: GameId?, val name: String, val format: String, val checked: Boolean, val headerRemoved: Boolean) : PatchApplied

    /** The patch is for another version of the game (its CRC32s, as 8 hex digits). */
    data class WrongGame(val format: String, val expected: String, val actual: String) : PatchApplied

    /** The game already is what the patch makes. */
    data object AlreadyPatched : PatchApplied

    /** It couldn't be done; [reason] says why, in words for the person. */
    data class Failed(val reason: String) : PatchApplied
}

/** How long a game takes to beat, and whether the person chose that game themselves. */
data class HltbView(val game: io.github.matiyaaa.fuse.integrations.hltb.HltbGame, val chosen: Boolean)
