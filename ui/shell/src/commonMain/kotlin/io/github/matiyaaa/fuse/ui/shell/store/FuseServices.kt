package io.github.matiyaaa.fuse.ui.shell.store

import io.github.matiyaaa.fuse.data.FuseData
import io.github.matiyaaa.fuse.data.settings.SecretStore
import io.github.matiyaaa.fuse.integrations.github.ReleasePlatform
import io.github.matiyaaa.fuse.launch.ResolvedLaunch
import io.github.matiyaaa.fuse.library.FuseFileSystem
import io.github.matiyaaa.fuse.model.AppEntry
import io.github.matiyaaa.fuse.model.CartridgeGame
import io.github.matiyaaa.fuse.model.CartridgeRoute
import io.github.matiyaaa.fuse.model.CartridgeStatus
import io.github.matiyaaa.fuse.model.Host
import io.github.matiyaaa.fuse.model.InstalledEmulator
import io.github.matiyaaa.fuse.model.LibrarySourceKind
import io.github.matiyaaa.fuse.model.ReleaseAsset
import io.ktor.client.HttpClient
import kotlinx.coroutines.flow.StateFlow

/**
 * What [DefaultFuseStore] needs from the operating system. The Android and Linux apps implement it;
 * everything above it (library, launching, scraping, achievements, Cartridge) is shared code.
 *
 * Rules every implementation follows:
 * - Nothing here deletes, renames or moves a user's files. The only writes go to Fuse's own
 *   directories ([cacheDir]) or to places the user explicitly picked.
 * - Secrets live in [secrets] only. They are never logged and never put in an exported intent.
 * - No call blocks the main thread; suspend functions switch to their own dispatcher.
 */
interface FuseServices {
    val host: Host
    val appVersion: String
    val data: FuseData
    val fs: FuseFileSystem
    val secrets: SecretStore

    /** Shared HTTP client (built with FuseHttp.client on the platform engine). */
    val http: HttpClient

    /**
     * Fuse's own cache directory. Generated disc playlists and downloaded updates go below it,
     * never into a ROM folder.
     */
    val cacheDir: String

    val emulators: EmulatorDetector
    val launcher: GameLauncher
    val cartridge: CartridgeBridge
    val installer: ReleaseInstaller

    /** Installed apps for the Apps section, or null where the platform has no app list. */
    val apps: AppsProvider?

    /** Where to look for existing libraries and firmware on this device. */
    val locations: DeviceLocations

    /**
     * Writes a small text file below [cacheDir] (for example `playlists/42/Game.m3u`) and returns
     * its absolute path, or null when it could not be written. [relativePath] never leaves the cache.
     */
    fun writeCacheFile(relativePath: String, content: String): String?

    /**
     * The file at [relativePath] below [cacheDir], written from [content] when it isn't there yet
     * (for example a bundled song unpacked once). Writes are atomic, so a file that exists is
     * complete. Null when it could not be written or this platform has no files.
     */
    suspend fun cacheFile(relativePath: String, content: suspend () -> ByteArray): String? = null

    /** Offset of local time from UTC right now, for "today" and "this week" playtime buckets. */
    fun utcOffsetMillis(): Long
}

/** Finds installed emulators and where they keep their firmware. */
interface EmulatorDetector {
    /** Every installed emulator Fuse has an adapter for. Runs off the main thread. */
    suspend fun detect(): List<InstalledEmulator>

    /** Firmware folders of installed emulators that Fuse can read (RetroArch `system/`, Dolphin `Sys/`). */
    fun biosFolders(installed: List<InstalledEmulator>): List<String> = emptyList()

    /**
     * Paths Fuse can never read on this device (Android 11+ `Android/data` of other apps). Firmware
     * that could only be there is reported as Unknown, never as Missing.
     */
    fun unreadablePaths(): Set<String> = emptySet()

    /** Linux: absolute path of a RetroArch core for [installed]; null elsewhere or when not found. */
    fun retroArchCorePath(installed: InstalledEmulator, core: String): String? = null

    /** Linux: the user's home directory, for `~` in emulator paths. */
    val homeDir: String? get() = null
}

/** Result of handing a game or app to the system. */
sealed interface RunResult {
    /**
     * The emulator was started. On Linux [awaitExit] suspends until its process ends, so the play
     * session closes at the real end. On Android the session closes when Fuse returns to the front.
     */
    data class Started(val awaitExit: (suspend () -> Unit)? = null) : RunResult

    /**
     * The game could not be started directly (for example a required activity is missing in this
     * build), so the emulator app was opened instead. [reason] tells the user what to do there.
     */
    data class OpenedAppInstead(val reason: String) : RunResult

    /** The app is not installed (any more). */
    data object NotInstalled : RunResult

    data class Failed(val message: String) : RunResult
}

/** Runs launch plans. Implementations never change emulator configuration. */
interface GameLauncher {
    /**
     * Starts [launch] (an Android intent or a Linux command). [displayId] asks for a specific screen
     * when the device has more than one.
     */
    suspend fun run(launch: ResolvedLaunch, displayId: Int? = null): RunResult

    /** Opens an emulator or app by its id (Android package, or `package/activity`; Linux executable, Flatpak id or `.desktop` file). */
    suspend fun openApp(appId: String): RunResult

    /** Opens the emulator's own settings screen, when its adapter documents one. */
    suspend fun openSettings(installed: InstalledEmulator): RunResult = openApp(installed.appId)

    /** The display to use for "Open games on the second screen", or null when there is none. */
    fun secondaryDisplayId(): Int? = null
}

/** Talks to Cartridge: status provider or status file, and deep links. */
interface CartridgeBridge {
    /** Current status. Cheap enough to call on every resume. Never throws. */
    suspend fun read(): CartridgeStatus

    /**
     * The games Cartridge downloaded, with RomM's details (bridge protocol 2); null when this
     * Cartridge doesn't offer them. Read only when [CartridgeStatus.gamesRevision] or the library
     * changed, since it can be large.
     */
    suspend fun games(): List<CartridgeGame>? = null

    /** Opens [route] in Cartridge ([link] is the built `cartridge://` URL). False when nothing handled it. */
    fun open(route: CartridgeRoute, link: String): Boolean

    /** Calls [onChange] when Cartridge reports a change (Android ContentObserver, Linux file watch). */
    fun watch(onChange: () -> Unit): AutoCloseable? = null
}

/** Downloads a release and hands it to the system. Only ever after the user confirmed. */
interface ReleaseInstaller {
    val platform: ReleasePlatform

    /**
     * Downloads [asset] into the cache, verifies its `sha256:` digest when GitHub published one, and
     * opens the system installer (Android) or places the new AppImage next to the running one and
     * marks it executable (Linux). [onProgress] gets 0..1.
     */
    suspend fun install(asset: ReleaseAsset, onProgress: (Float) -> Unit = {}): Result<Unit>

    /**
     * The first half of a Fuse update: downloads and verifies [asset] and keeps it ready. Returns a
     * handle for [applyUpdate] (the downloaded file). Nothing is installed yet.
     */
    suspend fun download(asset: ReleaseAsset, onProgress: (Float) -> Unit = {}): Result<String> =
        install(asset, onProgress).map { "" }

    /**
     * Installs what [download] prepared. Returns true when Fuse must restart itself into it (Linux);
     * false when the system takes over (Android asks to confirm and restarts Fuse).
     */
    suspend fun applyUpdate(downloaded: String): Result<Boolean> = Result.success(false)
}

/** Launchable apps (Android launcher apps; Linux `.desktop` applications). */
interface AppsProvider {
    /** Installed launchable apps, excluding Fuse itself. Updated on install and removal. */
    val apps: StateFlow<List<AppEntry>>

    /** Image model for the app's icon (an [AppIconModel] the platform's image loader understands). */
    fun iconModel(entry: AppEntry): Any?
    fun refresh()
    /** Opens [entry], on the display with [displayId] when given (the device's second screen). */
    suspend fun launch(entry: AppEntry, displayId: Int? = null): RunResult
    fun openInfo(entry: AppEntry)
}

/** Image loader model for an installed app's icon. */
data class AppIconModel(val packageName: String)

/** Candidate folders to offer during onboarding and in Settings -> Library. */
data class LocationHint(val path: String, val label: String, val kind: LibrarySourceKind = LibrarySourceKind.ROMS_ROOT)

interface DeviceLocations {
    /** Existing folders that commonly hold ROMs (ES-DE, RomM layouts, EmuDeck, RetroDECK, Cartridge's library). */
    suspend fun libraryCandidates(): List<LocationHint>

    /** Configured firmware folders (ES-DE `BIOS/`, RetroArch `system/`). */
    suspend fun biosRoots(): List<String>
}
