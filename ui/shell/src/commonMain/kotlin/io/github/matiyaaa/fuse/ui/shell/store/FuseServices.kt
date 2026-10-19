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
import io.github.matiyaaa.fuse.model.CartridgeUpload
import io.github.matiyaaa.fuse.model.EmulatorId
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

    /**
     * Fuse's own data directory, which the system never clears (unlike [cacheDir]): the transfer
     * queue and its partial work are kept here so they survive a restart. Defaults to the cache.
     */
    val dataDir: String get() = cacheDir

    /** Where films and episodes kept for watching offline go, unless the person chose a folder. */
    val mediaDir: String get() = "$dataDir/Offline"

    /** Sends a Wake-on-LAN [packet] to every port in [ports], broadcast on this network (and to [address] directly). */
    suspend fun sendWake(packet: ByteArray, ports: List<Int>, address: String?): Boolean = false

    /**
     * Starts Moonlight streaming [app] from the computer Moonlight knows as [hostName] (or by
     * [uniqueId], or [address]). Null when it started, else what is wrong in words for the person.
     */
    suspend fun startStream(hostName: String, uniqueId: String?, address: String, app: String, client: String): String? = "Streaming isn't available on this device."

    /** Whether a Moonlight is installed here to stream with. */
    fun streamClient(): String? = null

    /** Whether this device's connection is unmetered (Wi-Fi or wired), for transfers kept to Wi-Fi. */
    fun unmetered(): Boolean = true

    /**
     * Keeps Fuse running while transfers the person started are moving (Android: a foreground
     * service with a quiet notification saying [text], with [progress] 0..100 when known); called
     * again as either changes. [active] false lets it go. Nothing to do on a computer.
     */
    fun keepAliveForTransfers(active: Boolean, text: String, progress: Int?) = Unit

    /**
     * Whether the device is awake for Fuse's own rounds on the network and optional work
     * ([io.github.matiyaaa.fuse.sync.Awake]): on Android its screen on, or a transfer moving. False
     * holds what can wait (the host's news, Jellyfin's checks, art) until it wakes. Always true on a
     * computer, which sleeps as a whole.
     */
    val awake: StateFlow<Boolean> get() = io.github.matiyaaa.fuse.sync.Awake.always

    /** lsfg-vk is set up here (Linux, its configuration file there), so games can have frame generation. */
    fun frameGenerationReady(): Boolean = false

    /** This device's name, as servers list it (Jellyfin's dashboard). */
    val deviceName: String get() = "Fuse"

    /**
     * Fuse Sync by Fuse on this device, over [data] (the person's records and settings here), or
     * null where it can't run. Built once, when the store starts; off until turned on.
     */
    fun syncService(data: io.github.matiyaaa.fuse.sync.ProfileDataPort, scope: kotlinx.coroutines.CoroutineScope): io.github.matiyaaa.fuse.sync.SyncService? = null

    /** Syncthing, for people who run it (see [io.github.matiyaaa.fuse.sync.syncthing.SyncthingService]); null where Fuse can't use it. */
    fun syncthingService(scope: kotlinx.coroutines.CoroutineScope): io.github.matiyaaa.fuse.sync.syncthing.SyncthingService? = null

    /**
     * RomM's save sync ([io.github.matiyaaa.fuse.sync.romm.RommSaveService]): [env] finds the saves,
     * [client] is RomM signed in with its save sync on (null otherwise), [server] names the server,
     * [person] who is playing when the device has more than one person (their saves are their own).
     */
    fun rommSaves(
        env: () -> io.github.matiyaaa.fuse.sync.SaveEnvironment?,
        client: suspend () -> io.github.matiyaaa.fuse.romm.RommClient?,
        server: () -> String,
        person: () -> String? = { null },
    ): io.github.matiyaaa.fuse.sync.romm.RommSaveService? = null

    /** RomM on this computer through the Docker or Podman installed here ([RommHomeHost]); null where Fuse can't. */
    val rommHome: RommHomeHost? get() = null

    /** Finds Jellyfin servers on the network; null where there is no way to. */
    val jellyfinDiscovery: io.github.matiyaaa.fuse.jellyfin.ServerDiscovery? get() = null

    val emulators: EmulatorDetector

    /**
     * Fuse Intelligence's place on this device: what it may look at (never change) and Fuse's own
     * file for what it learns. Null where it has nothing to look at.
     */
    fun intelligence(): Pair<io.github.matiyaaa.fuse.launch.intelligence.ReadOnlyLook, io.github.matiyaaa.fuse.launch.intelligence.KnowledgeStore>? = null
    val launcher: GameLauncher
    val cartridge: CartridgeBridge
    val installer: ReleaseInstaller

    /** Installed apps for the Apps section, or null where the platform has no app list. */
    val apps: AppsProvider?

    /** Installing and removing apps for the Store, or null where Fuse can't (everywhere but Android). */
    val packages: PackageBridge? get() = null

    /** Where to look for existing libraries and firmware on this device. */
    val locations: DeviceLocations

    /** The drives mounted now, and word when one comes or goes. */
    val volumes: VolumeMonitor get() = VolumeMonitor.None

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

    /**
     * The bytes of the file at [path] (a picture the user chose, for a backup), or null when it
     * can't be read or is larger than [maxBytes].
     */
    suspend fun readFile(path: String, maxBytes: Int): ByteArray? = null

    /**
     * Writes [bytes] to [relativePath] below Fuse's own data folder, where it is kept (unlike the
     * cache): restored pictures and the backup made before a restore. Returns the absolute path, or
     * null when it could not be written. [relativePath] never leaves that folder.
     */
    suspend fun keepFile(relativePath: String, bytes: ByteArray): String? = null

    /**
     * Writes [bytes] as a new file named [name] in the folder [beside] is in (a patched copy of a
     * game, beside the game): written under a temporary name and moved into place, so a half-written
     * file never appears, and never over a file that is there. Returns its path, or null when it
     * could not be written (the folder can't be written to, or the name is taken).
     */
    suspend fun writeBeside(beside: String, name: String, bytes: ByteArray): String? = null

    /** Where the Store puts programs on a computer; null on Android (which has [packages]) and in tests. */
    val desktopApps: io.github.matiyaaa.fuse.ui.shell.store.DesktopInstaller? get() = null

    /** Fuse Ports' folder, downloads, unpacking and links on a computer; null on Android and in tests that don't need it. */
    val portFiles: PortFiles? get() = null

    /**
     * Emulators' own settings files, for the few changes Fuse makes there when asked (PCSX2 patches).
     * Null where Fuse can't reach them (Android).
     */
    val emulatorFiles: EmulatorFiles? get() = null

    /** This device's addresses on the home network, the likeliest first, for another device to reach a game hosted here. */
    fun homeAddresses(): List<String> = emptyList()

    /** Offset of local time from UTC right now, for "today" and "this week" playtime buckets. */
    fun utcOffsetMillis(): Long
}

/**
 * The drives the system has mounted: internal storage, SD cards, USB drives, second disks. Library
 * folders remember theirs, so an unplugged drive reads as offline rather than as deleted games.
 */
interface VolumeMonitor {
    /** Every mounted drive Fuse could keep games on. Never throws; empty when the system won't say. */
    suspend fun volumes(): List<io.github.matiyaaa.fuse.model.StorageVolume>

    /**
     * Calls [onChange] (on any thread) when a drive is mounted, unmounted or moved, where the system
     * tells; null when it can't, and Fuse then looks on resume and before every scan.
     */
    fun watch(onChange: () -> Unit): AutoCloseable? = null

    object None : VolumeMonitor {
        override suspend fun volumes(): List<io.github.matiyaaa.fuse.model.StorageVolume> = emptyList()
    }
}

/** Finds installed emulators and where they keep their firmware. */
interface EmulatorDetector {
    /** Every installed emulator Fuse has an adapter for. Runs off the main thread. */
    suspend fun detect(): List<InstalledEmulator>

    /** Emulation setups here that keep emulators of their own ("EmuDeck", "RetroDECK"), so the Store can say so. */
    suspend fun suites(): Set<String> = emptySet()

    /** Firmware folders of installed emulators that Fuse can read (RetroArch `system/`, Dolphin `Sys/`). */
    fun biosFolders(installed: List<InstalledEmulator>): List<String> = emptyList()

    /** The data folders of the emulators that install firmware inside themselves (Switch, PS3, Vita), to check it is there. */
    fun firmwareFolders(installed: List<InstalledEmulator>): List<io.github.matiyaaa.fuse.library.bios.EmulatorDataFolder> = emptyList()

    /** RPCS3's dev_hdd0 folders on this device, where its trophies are kept. */
    fun rpcs3DevHdd0(installed: List<InstalledEmulator>): List<String> = emptyList()

    /** shadPS4's user folders on this device, where its trophies are kept. */
    fun shadPs4UserDirs(installed: List<InstalledEmulator>): List<String> = emptyList()

    /** Xenia's content folders on this device, where each profile keeps its achievements. */
    fun xeniaContentRoots(installed: List<InstalledEmulator>): List<String> = emptyList()

    /**
     * Paths Fuse can never read on this device (Android 11+ `Android/data` of other apps). Firmware
     * that could only be there is reported as Unknown, never as Missing.
     */
    fun unreadablePaths(): Set<String> = emptySet()

    /** Linux: absolute path of a RetroArch core for [installed]; null elsewhere or when not found. */
    fun retroArchCorePath(installed: InstalledEmulator, core: String): String? = null

    /**
     * Desktop: true when Fuse can see where [installed] keeps its cores, so a core it doesn't find
     * there really isn't installed. False where it can't tell (a Steam copy, cores kept elsewhere).
     */
    fun retroArchCoresVisible(installed: InstalledEmulator): Boolean = false

    /** Desktop: whether Fuse can download a core into [installed]'s own cores folder (see RetroArchBuildbot). */
    fun canInstallRetroArchCore(installed: InstalledEmulator): Boolean = false

    /**
     * Downloads [core] from libretro's buildbot into [installed]'s cores folder, as RetroArch's own
     * Core Downloader would. The core's path when it worked, else why not.
     */
    suspend fun installRetroArchCore(installed: InstalledEmulator, core: String): Result<String> =
        Result.failure(IllegalStateException("Cores can't be downloaded here."))

    /** Linux: the user's home directory, for `~` in emulator paths. */
    val homeDir: String? get() = null

    /** True where the user can point Fuse at an emulator it didn't find (Windows and macOS). */
    val canLocate: Boolean get() = false

    /**
     * Remembers [path] (a program, or a macOS app) as where [emulator] is, so [detect] finds it from
     * now on. False when it isn't something Fuse can run.
     */
    suspend fun locate(emulator: EmulatorId, path: String): Boolean = false

    /** Forgets where [emulator] was located; [detect] searches for it again. */
    suspend fun forget(emulator: EmulatorId) = Unit

    /** Extra folders searched for emulators, besides the usual places. */
    fun searchFolders(): List<String> = emptyList()

    suspend fun setSearchFolders(folders: List<String>) = Unit

    /** Where the user located each emulator, by id. */
    fun located(): Map<EmulatorId, String> = emptyMap()
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

    /** It couldn't start; [diagnostic] holds what an emulator that stopped at once left behind, when there is that. */
    data class Failed(val message: String, val diagnostic: io.github.matiyaaa.fuse.launch.LaunchDiagnostic? = null) : RunResult
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

    /**
     * Hands [upload] to Cartridge (bridge protocol 3), which shows the files and uploads them to RomM
     * once the user confirms there. False when nothing handled it.
     */
    suspend fun upload(upload: CartridgeUpload): Boolean = false

    /** Calls [onChange] when Cartridge reports a change (Android ContentObserver, Linux file watch). */
    fun watch(onChange: () -> Unit): AutoCloseable? = null
}

/** Downloads a release and hands it to the system. Only ever after the user confirmed. */
interface ReleaseInstaller {
    val platform: ReleasePlatform

    /**
     * False where Fuse can't update itself and offers the release page instead (Windows and macOS,
     * whose installers Windows and macOS run themselves).
     */
    val inPlace: Boolean get() = true

    /** Why Fuse can't update itself here, when [inPlace] is false and it knows; said beside the release page. */
    val manualReason: String? get() = null

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

    /** The version the last update replaced, while Fuse can still go back to it (Linux); null elsewhere. */
    fun previous(): PreviousRelease? = null

    /** Puts [previous] back in this one's place. Success(true): restart Fuse now to start it. */
    suspend fun goBack(): Result<Boolean> = Result.failure(UnsupportedOperationException("Fuse can't go back to an earlier version here."))

    /** What Fuse says once at start about an update it went back from (or the library it brought back); null when nothing. */
    fun takeStartupNote(): String? = null
}

/**
 * A version of Fuse an update replaced, kept to go back to: [libraryGoesBack] when going back also
 * brings back the library as it was before the update (the newer one is kept aside), [since] when
 * it was replaced.
 */
data class PreviousRelease(val version: String, val libraryGoesBack: Boolean, val since: Long)

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

    /** Hands the APK at [path] to the system installer, which asks the user. The file stays where it is. */
    suspend fun installApk(path: String): ApkInstall = ApkInstall.Failed("Apps can't be installed on this system.")
}

/** Image loader model for an installed app's icon. */
data class AppIconModel(val packageName: String) {
    companion object {
        private const val SCHEME = "app-icon:"

        /** How the icon is written where art is stored as text (an Android game's icon in the library). */
        fun ref(packageName: String): String = SCHEME + packageName

        /** The model a [ref] stands for, or null for any other text. */
        fun parse(text: String): AppIconModel? =
            text.takeIf { it.startsWith(SCHEME) }?.removePrefix(SCHEME)?.takeIf { it.isNotEmpty() }?.let(::AppIconModel)
    }
}

/** Candidate folders to offer during onboarding and in Settings -> Library. */
data class LocationHint(val path: String, val label: String, val kind: LibrarySourceKind = LibrarySourceKind.ROMS_ROOT)

interface DeviceLocations {
    /** Existing folders that commonly hold ROMs (ES-DE, RomM layouts, EmuDeck, RetroDECK, Cartridge's library). */
    suspend fun libraryCandidates(): List<LocationHint>

    /** Configured firmware folders (ES-DE `BIOS/`, RetroArch `system/`). */
    suspend fun biosRoots(): List<String>

    /** Where Fuse's file picker starts: internal storage and SD cards, or the home folder and drives. */
    suspend fun storageRoots(): List<LocationHint> = emptyList()

    /**
     * Where Steam is installed on this computer (each folder holding `steamapps`), and the drive
     * roots to look on for more Steam libraries. Empty where Steam doesn't run (Android).
     */
    suspend fun steamRoots(): SteamPlaces = SteamPlaces()
}

/** Steam's installs ([roots]) and the drives that may hold more of its libraries ([drives]). */
data class SteamPlaces(val roots: List<String> = emptyList(), val drives: List<String> = emptyList())

/**
 * Emulator settings files. Reads anything; writes only inside a data folder this returned, atomically,
 * after keeping a copy of the file as it was before Fuse first changed it.
 */
interface EmulatorFiles {
    /** Where the PCSX2 install [installed] keeps its data; null when it hasn't been set up or can't be found. */
    suspend fun pcsx2(installed: io.github.matiyaaa.fuse.model.InstalledEmulator): io.github.matiyaaa.fuse.launch.patches.Pcsx2Home?

    /** Where the DuckStation install [installed] keeps its data; null when it hasn't been set up or can't be found. */
    suspend fun duckstation(installed: io.github.matiyaaa.fuse.model.InstalledEmulator): io.github.matiyaaa.fuse.launch.patches.DuckStationHome? = null

    /** The memory stick of the PPSSPP install [installed] (the folder holding `PSP`); null when it can't be found. */
    suspend fun ppsspp(installed: io.github.matiyaaa.fuse.model.InstalledEmulator): io.github.matiyaaa.fuse.launch.patches.PpssppHome? = null

    /** Where the Dolphin install [installed] keeps its settings; null until it has a Dolphin.ini. */
    suspend fun dolphin(installed: io.github.matiyaaa.fuse.model.InstalledEmulator): io.github.matiyaaa.fuse.launch.settings.DolphinHome? = null

    /** Where the RetroArch install [installed] keeps its settings and cheats; null until it has a retroarch.cfg. */
    suspend fun retroArch(installed: io.github.matiyaaa.fuse.model.InstalledEmulator): io.github.matiyaaa.fuse.launch.patches.RetroArchHome? = null

    /** Removes the file at [path] inside a known emulator data folder; for a file Fuse made and emptied again. */
    suspend fun remove(path: String): Boolean = false

    /** The text of [entry] inside the zip at [zip], or null. */
    suspend fun zipText(zip: String, entry: String): String?

    /** Writes [text] to [path] inside a known emulator data folder; false when it may not or could not. */
    suspend fun write(path: String, text: String): Boolean

    /**
     * Writes [text] to [path] as [write] does, keeping no copy of the file as it was: for a file
     * that holds a person's sign-in (RPCS3's `rpcn.yml`), which Fuse never keeps a copy of.
     */
    suspend fun writePrivate(path: String, text: String): Boolean = write(path, text)

    /**
     * RPCS3's `dev_hdd0` folders for [installed], worked out as RPCS3 does (its config folder, or the
     * folder its `vfs.yml` moved `/dev_hdd0/` to). Not checked to exist; empty where unknown.
     */
    suspend fun rpcs3Storage(installed: io.github.matiyaaa.fuse.model.InstalledEmulator): List<String> = emptyList()

    /** RPCS3's config folder for [installed] (where `patches/` and its settings are); null until it has one. */
    suspend fun rpcs3Config(installed: io.github.matiyaaa.fuse.model.InstalledEmulator): String? = null

    /** shadPS4's user folder for [installed] (where `patches/` is); null until it has one. */
    suspend fun shadPs4User(installed: io.github.matiyaaa.fuse.model.InstalledEmulator): String? = null

    /** Vita3K's pref paths for [installed] (where its `ux0` lives): `config.yml`'s `pref-path`, else its defaults. */
    suspend fun vita3kStorage(installed: io.github.matiyaaa.fuse.model.InstalledEmulator): List<String> = emptyList()

    /** Azahar's emulated SD card (`sdmc`) for [installed]: a custom one from its settings, else in its user folder. */
    suspend fun azaharStorage(installed: io.github.matiyaaa.fuse.model.InstalledEmulator): List<String> = emptyList()

    /**
     * Runs an emulator's own installer and waits for it to finish (or for [InstallerRun.stopWhen] to
     * show in its output, then stops it). Null where Fuse can't run programs.
     */
    suspend fun runInstaller(run: InstallerRun, onOutput: (String) -> Unit = {}): InstallerResult? = null

    /**
     * Copies the file at [source] into a folder of Fuse's own as [name] (a licence the emulator only
     * takes under its content id) and returns the copy. Nothing is written anywhere else.
     */
    suspend fun stage(source: String, name: String): String? = null

    /** Removes what [stage] copied. */
    suspend fun clearStaged() = Unit


    /**
     * What is in the file, folder or archive at [source], before anything is copied: its entries
     * (paths below a folder, the entries of a `.zip`, or just its name for any other file). Links
     * inside a folder are left out. Null when it can't be read.
     */
    suspend fun inspect(source: String): SourceListing? = null

    /** The text of [entry] in [source] (as [inspect] listed it), up to 1 MB; null when it isn't there or isn't text. */
    suspend fun sourceText(source: String, entry: String): String? = null

    /**
     * Puts [files] from [source] and [texts] (destination to its whole text) in place inside known
     * emulator folders, all or nothing. First a restoration point named [restorePoint] is made: each
     * file already at a destination is copied aside, and what is new is noted, so [undo] can put
     * everything back as it was. False when any destination is outside the emulator's folders or a
     * copy fails, with whatever was done already put back.
     */
    suspend fun install(source: String?, files: List<io.github.matiyaaa.fuse.launch.patches.PlannedFile>, texts: Map<String, String>, restorePoint: String): Boolean = false

    /**
     * Puts back what [install] changed under [restorePoint]: new files are removed (and the folders made
     * for them, once empty), files that were replaced get their earlier copy back. Destinations in
     * [keep] are left as they are. The restoration point goes too. False when it is not there.
     */
    suspend fun undo(restorePoint: String, keep: Set<String> = emptySet()): Boolean = false

    /**
     * Downloads [url] into a folder of Fuse's own as [name], at most [maxBytes], and checks it against
     * [md5] and [sha256] when given (and [size] when known). Returns the file, or null when it couldn't
     * be fetched or wasn't what was promised (then nothing of it is kept). Nothing is opened or run.
     */
    suspend fun fetch(url: String, name: String, size: Long?, md5: String?, maxBytes: Long, onProgress: (Float) -> Unit = {}, sha256: String? = null): String? = null

    /** Removes what [fetch] downloaded. */
    suspend fun clearFetched() = Unit

    /**
     * Where the game [query] describes keeps its mods in the emulator it starts in (Dolphin, Cemu,
     * the Switch emulators), found as the save adapters find that emulator's folder, with that
     * folder then open to [install]. A failure says why, in a sentence.
     */
    suspend fun modHome(query: io.github.matiyaaa.fuse.sync.SaveQuery): Result<io.github.matiyaaa.fuse.sync.ModHome> =
        Result.failure(IllegalStateException("Mods are added from Fuse on a computer."))

    /**
     * The `USRDIR` folder of the RPCS3 game at [launchPath] (a game folder's `PS3_GAME/USRDIR`, or
     * `dev_hdd0/game/<serial>/USRDIR` for one installed in RPCS3), then open to [install]; null when
     * there is none.
     */
    suspend fun rpcs3GameFiles(installed: io.github.matiyaaa.fuse.model.InstalledEmulator, launchPath: String, serial: String?): String? = null

    /**
     * The shadPS4 mods folder for the game at [launchPath] (`<game folder>-mods`, beside it) and the
     * names at the top of the game's own folder, with the mods folder then open to [install]; null
     * when the game isn't a folder with an eboot.bin.
     */
    suspend fun shadPs4Mods(launchPath: String): Pair<String, Set<String>>? = null

    /**
     * The other emulators of [installed]'s family (see [io.github.matiyaaa.fuse.sync.EmulatorFamilies])
     * with a folder here holding a person's things, as places to bring them over from.
     */
    suspend fun familyFolders(installed: io.github.matiyaaa.fuse.model.InstalledEmulator): List<io.github.matiyaaa.fuse.sync.FamilyFolder> = emptyList()

    /**
     * What bringing [from]'s things over to [installed] would copy (nothing yet), with [installed]'s
     * own folder then open to [install]; null when [installed] has no folder yet.
     */
    suspend fun familyMove(installed: io.github.matiyaaa.fuse.model.InstalledEmulator, from: String): io.github.matiyaaa.fuse.sync.FamilyMove? = null
}

/**
 * A file, folder or archive someone chose to add, as Fuse read it: [entries] are paths below a folder
 * or in an archive (or the file's own name), [bytes] their size together, and [truncated] when there
 * were more than Fuse lists.
 */
data class SourceListing(
    val name: String,
    val form: SourceForm,
    val entries: List<String>,
    val bytes: Long,
    val truncated: Boolean = false,
)

enum class SourceForm { FILE, FOLDER, ARCHIVE }

/**
 * One run of an emulator's installer: [argv] (program first), started in [workingDir] with [env]
 * added to Fuse's environment. When [stopWhen] matches its output the run is over and the program
 * is stopped (Vita3K starts the game it just installed from a .vpk). Stopped after [timeoutMs].
 */
data class InstallerRun(
    val argv: List<String>,
    val workingDir: String? = null,
    val env: Map<String, String> = emptyMap(),
    val stopWhen: Regex? = null,
    val timeoutMs: Long = 60 * 60 * 1000L,
)

/** How an installer run ended: its exit code (null when Fuse stopped it), and the end of what it printed. */
data class InstallerResult(val exitCode: Int?, val output: String, val timedOut: Boolean = false, val started: Boolean = true)

/**
 * RomM on this computer ([io.github.matiyaaa.fuse.romm.RommHome]): Compose run through the Docker or
 * Podman the person already has. Nothing runs except through [start] and [stop], which only the
 * person's own choice calls.
 */
interface RommHomeHost {
    /** The way to run Compose here, or null when neither Docker nor Podman is installed. */
    suspend fun engine(): io.github.matiyaaa.fuse.romm.RommHome.Engine?

    /** What was set up before, or null. */
    suspend fun current(): io.github.matiyaaa.fuse.romm.RommHome.Spec?

    /** Writes the Compose file for [spec] (its secrets made once, kept in the secret store) and starts RomM. */
    suspend fun start(spec: io.github.matiyaaa.fuse.romm.RommHome.Spec): Result<Unit>

    /** Stops RomM, keeping everything. */
    suspend fun stop(): Result<Unit>

    /** Whether its containers run now. */
    suspend fun running(): Boolean
}
