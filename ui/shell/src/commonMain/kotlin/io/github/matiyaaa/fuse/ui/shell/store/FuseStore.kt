package io.github.matiyaaa.fuse.ui.shell.store

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

    /** A song that ships with Fuse ([io.github.matiyaaa.fuse.ui.shell.music.BundledMusic]) as a file the player can open. */
    suspend fun bundledTrack(id: String): String? = null
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
}

data class StorageUsage(
    val volumes: List<VolumeUsage>,
    /** Largest first. */
    val games: List<GameSize>,
    val measured: Int,
    val total: Int,
    val finished: Boolean,
)

/** A drive: its size, what's free, and how much of it is games, by system. */
data class VolumeUsage(
    val label: String,
    val totalBytes: Long,
    val freeBytes: Long,
    val gamesBytes: Long,
    val systems: List<SystemShare>,
)

data class SystemShare(val platform: PlatformId, val name: String, val accent: Long, val bytes: Long)

data class GameSize(val card: GameCard, val bytes: Long, val files: Int)

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
    suspend fun launch(id: GameId, emulator: EmulatorId? = null, discPath: String? = null, display: LaunchDisplay? = null): LaunchOutcome

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
}

data class SuggestedSource(val path: String, val label: String, val kind: LibrarySourceKind, val platformsFound: Int)

interface EmulatorOps {
    val installed: StateFlow<List<InstalledEmulator>>
    fun refresh()
    /** Emulators that could run [platform], installed ones first, with notes. */
    fun optionsFor(platform: PlatformId): List<EmulatorOption>
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
    val providers: StateFlow<List<ProviderStatus>>
    /** The last key check per provider; empty until a check ran. Checks run after a key is saved. */
    val keyChecks: StateFlow<Map<ScrapeProviderId, io.github.matiyaaa.fuse.integrations.KeyCheck?>>
    /** Tests every provider key that is set with a real request; null marks a check in progress. */
    fun checkKeys()
    /** Fetches logos, artwork and colours from the system art pack for every system with games. */
    fun downloadSystemArt()
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
}

data class PhoneLinkState(
    val running: Boolean = false,
    /** Addresses phones open, like "http://192.168.1.20:47300/". Empty when not on a network. */
    val addresses: List<String> = emptyList(),
    val username: String? = null,
    val sessions: Int = 0,
    val error: String? = null,
)
