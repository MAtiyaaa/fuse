package io.github.matiyaaa.fuse.ui.shell.store

import io.github.matiyaaa.fuse.model.AchievementState
import io.github.matiyaaa.fuse.model.AppFilter
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
}

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

    suspend fun launch(id: GameId, emulator: EmulatorId? = null, discPath: String? = null): LaunchOutcome

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
}

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
    /** Bulk fill for a game, platform or everything ([platform] and [game] null). */
    fun fill(mode: MediaFillMode, kinds: Set<MediaKind>, platform: PlatformId? = null, game: GameId? = null)
    val fillProgress: StateFlow<FillProgress?>
    suspend fun candidates(game: GameId): List<ScrapeCandidate>
    suspend fun acceptCandidate(game: GameId, candidate: ScrapeCandidate)
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
    data class Options(val options: List<ArtworkOption>) : ArtworkResult
    data class NeedsMatch(val candidates: List<ScrapeCandidate>) : ArtworkResult
    data class Unavailable(val reason: String) : ArtworkResult
}

data class RecentDownload(val download: io.github.matiyaaa.fuse.model.CartridgeDownload, val game: GameCard?)

data class FillProgress(val done: Int, val total: Int, val current: String?, val added: Int, val finished: Boolean)

interface CollectionOps {
    val collections: StateFlow<List<GameCollection>>
    suspend fun create(name: String): CollectionId
    suspend fun rename(id: CollectionId, name: String)
    suspend fun delete(id: CollectionId)
    suspend fun add(id: CollectionId, game: GameId)
    suspend fun remove(id: CollectionId, game: GameId)
    suspend fun membership(game: GameId): Set<CollectionId>
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
    fun apps(filter: AppFilter): Flow<List<AppCard>>
    suspend fun launch(app: AppCard)
    suspend fun setPinned(app: AppCard, pinned: Boolean)
    suspend fun setHidden(app: AppCard, hidden: Boolean)
    suspend fun rename(app: AppCard, title: String?)
    suspend fun openInfo(app: AppCard)
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
    suspend fun check(): ReleaseInfo?
    suspend fun install(release: ReleaseInfo): Result<Unit>
    val currentVersion: String
}
