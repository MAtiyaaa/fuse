package io.github.matiyaaa.fuse.ui.shell.audit

import io.github.matiyaaa.fuse.data.FuseData
import io.github.matiyaaa.fuse.data.db.DesktopDatabase
import io.github.matiyaaa.fuse.launch.ResolvedLaunch
import io.github.matiyaaa.fuse.library.FsEntry
import io.github.matiyaaa.fuse.library.FuseFileSystem
import io.github.matiyaaa.fuse.model.AppEntry
import io.github.matiyaaa.fuse.model.CapabilityProfile
import io.github.matiyaaa.fuse.model.CartridgeRoute
import io.github.matiyaaa.fuse.model.CartridgeStatus
import io.github.matiyaaa.fuse.model.EmulatorId
import io.github.matiyaaa.fuse.model.Host
import io.github.matiyaaa.fuse.model.InstalledEmulator
import io.github.matiyaaa.fuse.model.PerformanceMetric
import io.github.matiyaaa.fuse.model.PlatformId
import io.github.matiyaaa.fuse.model.SystemStatus
import io.github.matiyaaa.fuse.ui.shell.platform.HomeRole
import io.github.matiyaaa.fuse.ui.shell.platform.MenuMusicPlayer
import io.github.matiyaaa.fuse.ui.shell.platform.PlatformFeatures
import io.github.matiyaaa.fuse.ui.shell.platform.PlatformUi
import io.github.matiyaaa.fuse.ui.shell.screenshots.ScreenshotPlatform
import io.github.matiyaaa.fuse.ui.shell.screenshots.ScreenshotServices
import io.github.matiyaaa.fuse.ui.shell.store.AppsProvider
import io.github.matiyaaa.fuse.ui.shell.store.CartridgeBridge
import io.github.matiyaaa.fuse.ui.shell.store.DeviceLocations
import io.github.matiyaaa.fuse.ui.shell.store.EmulatorDetector
import io.github.matiyaaa.fuse.ui.shell.store.FuseServices
import io.github.matiyaaa.fuse.ui.shell.store.GameLauncher
import io.github.matiyaaa.fuse.ui.shell.store.LocationHint
import io.github.matiyaaa.fuse.ui.shell.store.RunResult
import io.ktor.client.engine.mock.respondError
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import io.ktor.client.engine.mock.respond
import java.io.File

/**
 * What the audit can steer in the services: launches that wait until released, Cartridge's status,
 * the installed apps, where onboarding looks for libraries, and a pause in folder listing so a scan
 * can be caught while it runs.
 */
internal class AuditControls(val cache: File) {
    /** When set, starting a game waits for this result (the launch veil stays up meanwhile). */
    @Volatile var launchGate: CompletableDeferred<RunResult>? = null

    /** Result of a launch when no gate is set. Nothing here ever starts a real process. */
    @Volatile var launchResult: RunResult = RunResult.Failed("The audit never starts games.")

    @Volatile var cartridge: CartridgeStatus = CartridgeStatus(installed = false)

    /** The version an update replaced, as Settings, About offers to go back to it; none when null. */
    @Volatile var previousFuse: io.github.matiyaaa.fuse.ui.shell.store.PreviousRelease? = null

    @Volatile var libraryCandidates: List<LocationHint> = emptyList()

    /** Where Fuse's file picker starts. */
    @Volatile var storageRoots: List<LocationHint> = emptyList()

    /** Folder listings still allowed before listing pauses; negative means never pause. */
    private val listBudget = MutableStateFlow(-1)

    fun pauseListingAfter(listings: Int) {
        listBudget.value = listings
    }

    fun resumeListing() {
        listBudget.value = -1
    }

    suspend fun beforeList() {
        while (true) {
            val budget = listBudget.value
            if (budget < 0) return
            if (budget > 0) {
                if (listBudget.compareAndSet(budget, budget - 1)) return
                continue
            }
            listBudget.first { it != 0 }
        }
    }

    val apps: AuditApps = AuditApps.create(File(cache, "app-icons"))

    /** Fuse Sync as the library store made it, for scenarios to set up. */
    @Volatile var sync: AuditSync? = null

    /** Syncthing as the library store made it. */
    @Volatile var syncthing: AuditSyncthing? = null

    /** The drives the audit device reports; none by default, like a host that can't tell. */
    @Volatile var drives: List<io.github.matiyaaa.fuse.model.StorageVolume> = emptyList()
}

/**
 * The README screenshot services (real temporary library and database, reported emulators, no
 * network) with the audit's controls on top.
 */
internal class AuditServices(
    private val base: ScreenshotServices,
    private val controls: AuditControls,
    /** Android, for the screens only an Android device has (its games, APKs). */
    override val host: Host = base.host,
) : FuseServices by base {
    /** Fuse Sync, made up (see [AuditSync]); the scenarios steer it through [AuditControls.sync]. */
    override fun syncService(data: io.github.matiyaaa.fuse.sync.ProfileDataPort, scope: kotlinx.coroutines.CoroutineScope): io.github.matiyaaa.fuse.sync.SyncService =
        AuditSync(base.data.settings).also { controls.sync = it }

    /** Syncthing, made up (see [AuditSyncthing]); the scenarios steer it through [AuditControls.syncthing]. */
    override fun syncthingService(scope: kotlinx.coroutines.CoroutineScope): io.github.matiyaaa.fuse.sync.syncthing.SyncthingService =
        AuditSyncthing(base.data.settings).also { controls.syncthing = it }

    /** Windows and macOS: no app list, and emulators the user can point Fuse at. */
    private val desktop = host == Host.WINDOWS || host == Host.MACOS

    /** Fuse's own updates as the audit's computer has them: a version to go back to when a scenario sets one. */
    override val installer: io.github.matiyaaa.fuse.ui.shell.store.ReleaseInstaller = object : io.github.matiyaaa.fuse.ui.shell.store.ReleaseInstaller by base.installer {
        override fun previous() = controls.previousFuse
        override suspend fun goBack(): Result<Boolean> = Result.success(false)
    }

    /** Android has the Store: its catalogue and releases come from [AuditStore], its apps from [AuditPackages]. */
    override val packages: io.github.matiyaaa.fuse.ui.shell.store.PackageBridge? =
        if (host == Host.ANDROID) AuditPackages(File(base.cacheDir, "store")) else null

    /** On a computer, the Store fetches programs; this one only pretends to put them in place. */
    override val desktopApps: io.github.matiyaaa.fuse.ui.shell.store.DesktopInstaller? =
        if (desktop) AuditDesktopInstaller(host, File(base.cacheDir, "store-desktop")) else null

    /** The made-up Jellyfin server everywhere; the Store's catalogue where there is a Store. */
    override val http: io.ktor.client.HttpClient =
        io.ktor.client.HttpClient(io.ktor.client.engine.mock.MockEngine { request ->
            AuditJellyfin.answer(this, request)
                ?: AuditGameBanana.answer(this, request)
                ?: (if (host == Host.ANDROID || desktop) AuditStore.answer(this, request) else null)
                ?: respondError(io.ktor.http.HttpStatusCode.NotFound)
        }) { install(io.ktor.client.plugins.HttpTimeout) }

    override val fs: FuseFileSystem = object : FuseFileSystem by base.fs {
        override suspend fun list(path: String): List<FsEntry> {
            controls.beforeList()
            return base.fs.list(path)
        }
    }

    /** Fuse Intelligence looking at a made-up computer where every program can run and has its folders. */
    override fun intelligence() = object : io.github.matiyaaa.fuse.launch.intelligence.ReadOnlyLook {
        override val home = "/home/mo"
        override fun env(name: String): String? = null
        override fun exists(path: String) = true
        override fun isDirectory(path: String) = !path.substringAfterLast('/').contains('.') || path.endsWith(".app")
        override fun canExecute(path: String) = true
        override fun modified(path: String): Long? = 1_700_000_000_000L
        override fun names(path: String): List<String> = emptyList()
    } to object : io.github.matiyaaa.fuse.launch.intelligence.KnowledgeStore {
        private var text: String? = null
        override fun read() = text
        override fun write(text: String) { this.text = text }
    }

    override val emulators = object : EmulatorDetector {
        private val located = java.util.concurrent.ConcurrentHashMap<EmulatorId, String>()

        override suspend fun detect(): List<InstalledEmulator> = if (!desktop) {
            InstalledEmulators
        } else {
            WindowsEmulators + located.map { (id, path) ->
                InstalledEmulator(id, id.value.substringAfter('.'), host, path, platforms = emptySet(), detectedVia = "Located")
            }
        }

        override val homeDir: String? = base.emulators.homeDir
        override val canLocate: Boolean get() = desktop

        override suspend fun locate(emulator: EmulatorId, path: String): Boolean {
            if (!path.endsWith(".exe", ignoreCase = true)) return false
            located[emulator] = path
            return true
        }

        override suspend fun forget(emulator: EmulatorId) {
            located.remove(emulator)
        }

        override fun located(): Map<EmulatorId, String> = located.toMap()
    }

    override val launcher = object : GameLauncher {
        override suspend fun run(launch: ResolvedLaunch, displayId: Int?): RunResult =
            controls.launchGate?.await() ?: controls.launchResult

        override suspend fun openApp(appId: String): RunResult = RunResult.Started()
    }

    override val cartridge = object : CartridgeBridge {
        override suspend fun read(): CartridgeStatus = controls.cartridge
        override fun open(route: CartridgeRoute, link: String) = true
    }

    override val apps: AppsProvider? = if (desktop) null else controls.apps

    override val volumes = object : io.github.matiyaaa.fuse.ui.shell.store.VolumeMonitor {
        override suspend fun volumes() = controls.drives
    }

    /** RetroArch on the made-up computer, with the cheats it downloaded for every sample game. */
    override val emulatorFiles: io.github.matiyaaa.fuse.ui.shell.store.EmulatorFiles? =
        if (host == Host.ANDROID) null else AuditEmulatorFiles(File(base.cacheDir, "retroarch"))

    override val locations = object : DeviceLocations {
        override suspend fun libraryCandidates(): List<LocationHint> = controls.libraryCandidates
        override suspend fun biosRoots(): List<String> = emptyList()
        override suspend fun storageRoots(): List<LocationHint> = controls.storageRoots
    }

    companion object {
        /** Citron only opens its own window (Fuse cannot start a game in it), for the "opens the app" states. */
        val Citron = InstalledEmulator(
            id = EmulatorId("linux.citron"),
            name = "Citron",
            host = Host.LINUX,
            appId = "/usr/bin/citron",
            platforms = setOf(PlatformId("switch")),
            detectedVia = "PATH",
        )

        val InstalledEmulators: List<InstalledEmulator> = ScreenshotServices.InstalledEmulators + Citron

        /** What a Windows PC with a few emulators reports. */
        val WindowsEmulators: List<InstalledEmulator> = listOf(
            InstalledEmulator(EmulatorId("windows.retroarch"), "RetroArch", Host.WINDOWS, "C:/RetroArch-Win64/retroarch.exe", platforms = emptySet(), detectedVia = "Folder"),
            InstalledEmulator(EmulatorId("windows.duckstation"), "DuckStation", Host.WINDOWS, "D:/Emulators/duckstation/duckstation-qt-x64-ReleaseLTCG.exe", platforms = setOf(PlatformId("psx")), detectedVia = "Folder"),
            InstalledEmulator(EmulatorId("windows.pcsx2"), "PCSX2", Host.WINDOWS, "C:/Program Files/PCSX2/pcsx2-qt.exe", platforms = setOf(PlatformId("ps2")), detectedVia = "Folder"),
            InstalledEmulator(EmulatorId("windows.shortcut"), "Windows programs and shortcuts", Host.WINDOWS, "powershell.exe", platforms = emptySet(), detectedVia = "Built in"),
        )

        fun create(cache: File, controls: AuditControls, host: Host = Host.LINUX): AuditServices = AuditServices(
            ScreenshotServices(FuseData(DesktopDatabase.open(File(cache, "fuse-${System.nanoTime()}.db").absolutePath)), cache),
            controls,
            host,
        )
    }
}

/** Installed apps with icons drawn for the audit (a few have none, as some real apps do). */
internal class AuditApps(entries: List<AppEntry>, private val icons: Map<String, String>) : AppsProvider {
    override val apps: StateFlow<List<AppEntry>> = MutableStateFlow(entries)
    override fun iconModel(entry: AppEntry): Any? = icons[entry.id]
    override fun refresh() = Unit
    override suspend fun launch(entry: AppEntry, displayId: Int?): RunResult = RunResult.Started()
    override fun openInfo(entry: AppEntry) = Unit

    companion object {
        /** Invented apps, none named after a real product. */
        private val sample = listOf(
            Triple("Starfall Arena", true, 0xFF4F7BB0),
            Triple("Puzzle Kiln", true, 0xFFB5705B),
            Triple("Driftwood Rally", true, 0xFF5B9A8B),
            Triple("Lantern Solitaire", true, 0xFFB59A4F),
            Triple("Moonlit Hollow", true, 0xFF6E5BB5),
            Triple("Pixel Garden", true, 0xFF5BB56E),
            Triple("Tiny Tactics", true, null),
            Triple("Lumen Browser", false, 0xFF4FA3B0),
            Triple("Quill Notes", false, 0xFFB0A04F),
            Triple("Harbor Mail", false, 0xFF4F6FB0),
            Triple("Pebble Weather", false, 0xFF7FA8D0),
            Triple("Orbit Files", false, 0xFF8A8FA3),
            Triple("Kiln Photos", false, 0xFFD07F6A),
            Triple("Brightline Maps", false, 0xFF5BB58A),
            Triple("Station Radio", false, 0xFFB54F6E),
            Triple("Moss Podcasts", false, 0xFF6E9A4F),
            Triple("Cinder Chat", false, null),
            Triple("Parcel Tracker", false, 0xFFA87F4F),
            Triple("Foxglove Reader", false, 0xFF9A5BB5),
            Triple("Glide Remote", false, null),
            Triple("Tern Calendar", false, 0xFFB54F4F),
        )

        fun create(iconDir: File): AuditApps {
            iconDir.mkdirs()
            val entries = sample.mapIndexed { i, (label, game, _) ->
                val slug = label.lowercase().replace(' ', '.')
                AppEntry(
                    id = "app.sample.$slug",
                    label = label,
                    packageName = "app.sample.$slug",
                    isGame = game,
                    installedAt = 1_700_000_000_000L + i * 86_400_000L,
                )
            }
            val icons = HashMap<String, String>()
            sample.forEachIndexed { i, (label, _, color) ->
                if (color == null) return@forEachIndexed
                val file = File(iconDir, "${entries[i].id}.png")
                AuditIcons.write(file, label, color, i)
                icons[entries[i].id] = file.absolutePath
            }
            return AuditApps(entries, icons)
        }
    }
}

/**
 * The README screenshot platform (a Linux handheld with battery, Wi-Fi, brightness and volume) with
 * the screen of the size being rendered, optional Home role and second screen, and performance
 * numbers for the overlay.
 */
internal class AuditPlatform(
    size: AuditSize,
    override val features: PlatformFeatures = ScreenshotPlatform.features,
    override val homeRole: HomeRole? = null,
    metrics: List<PerformanceMetric> = SampleMetrics,
    override val capture: io.github.matiyaaa.fuse.ui.shell.platform.ScreenCapture? = null,
    /** How the device draws (null: not reported, so Fuse draws with every effect). */
    drawing: io.github.matiyaaa.fuse.ui.shell.platform.DrawingInfo? = null,
    /** Steam and Decky as a Linux computer has them, for setup's Steam journey. */
    override val steam: io.github.matiyaaa.fuse.ui.shell.platform.SteamIntegration? = null,
    override val decky: io.github.matiyaaa.fuse.ui.shell.platform.DeckyIntegration? = null,
    override val host: io.github.matiyaaa.fuse.model.Host = ScreenshotPlatform.host,
) : PlatformUi by ScreenshotPlatform {
    override val drawing: StateFlow<io.github.matiyaaa.fuse.ui.shell.platform.DrawingInfo?> = MutableStateFlow(drawing)
    override val device: CapabilityProfile = ScreenshotPlatform.device.copy(
        screenWidthPx = size.widthPx,
        screenHeightPx = size.heightPx,
        densityDpi = (size.density * 160).toInt(),
    )
    override val performance: StateFlow<List<PerformanceMetric>> = MutableStateFlow(metrics)

    /** A cheat file of a person's own, as the file picker hands it to the Patches and Cheats sheet. */
    private val ownCheats: File by lazy {
        val dir = java.nio.file.Files.createTempDirectory("fuse-audit-own").toFile().apply { deleteOnExit() }
        File(dir, "Emberline Saga extras.cht").apply {
            writeText("cheats = 2\n\ncheat0_desc = \"Max Gold\"\ncheat0_code = \"7E0DC0:FF\"\ncheat0_enable = false\n\ncheat1_desc = \"Walk Through Walls\"\ncheat1_code = \"7E0DC4:01\"\ncheat1_enable = false\n")
            deleteOnExit()
        }
    }

    override val storage: io.github.matiyaaa.fuse.ui.shell.platform.StorageAccess = object : io.github.matiyaaa.fuse.ui.shell.platform.StorageAccess by ScreenshotPlatform.storage {
        override suspend fun pickFile(title: String, label: String, extensions: List<String>): String? = ownCheats.absolutePath
    }

    /** What the file picker hands back next (a backup to restore); null is a cancelled picker. */
    @Volatile var pickedFile: io.github.matiyaaa.fuse.ui.shell.platform.OpenedFile? = null

    override suspend fun openFile(mimeTypes: List<String>, extensions: List<String>, maxBytes: Long) = pickedFile

    override suspend fun saveFile(name: String, mimeType: String, bytes: ByteArray): String? = "Downloads/$name"

    /** Settable, for battery states (charging, full, low). */
    val statusFlow = MutableStateFlow(ScreenshotPlatform.status.value)
    override val status: StateFlow<SystemStatus> = statusFlow

    /** A silent player, so the Sound settings show what a device with audio shows. */
    override val music: MenuMusicPlayer = object : MenuMusicPlayer {
        override fun apply(state: io.github.matiyaaa.fuse.ui.shell.platform.MusicState) = Unit
    }

    companion object {
        /** The keys real devices report: a Linux handheld's processor and memory, Android's battery and frame rate. */
        val SampleMetrics = listOf(
            PerformanceMetric("cpu", "Processor", "34%", 0.34f, source = "/proc/stat"),
            PerformanceMetric("memory", "Memory", "5.2 / 16.0 GB", 0.33f, source = "/proc/meminfo"),
            PerformanceMetric("cpu-temp", "CPU temperature", "48 °C", 0.48f, source = "thermal zone 0"),
            PerformanceMetric("battery_temp", "Battery", "31.5 °C", source = "battery broadcast"),
            PerformanceMetric("fuse_fps", "Fuse frame rate", "60 fps", source = "Compose frame clock"),
        )

        /** A device that could make Fuse its Home screen, not yet chosen. */
        fun homeRole(): HomeRole = object : HomeRole {
            override val isHome: StateFlow<Boolean> = MutableStateFlow(false)
            override fun request() = Unit
            override fun openHomeSettings() = Unit
            override fun disable() = Unit
        }
    }
}

/**
 * Screenshots and recordings as Android has them, without a screen to capture: every request is
 * granted, and what is "saved" is a small picture in the theme's colours.
 */
internal class AuditCapture : io.github.matiyaaa.fuse.ui.shell.platform.ScreenCapture {
    override val picturesPlace = "Pictures/Fuse"
    override val videosPlace = "Movies/Fuse"
    override val stoppedElsewhere = kotlinx.coroutines.flow.MutableSharedFlow<io.github.matiyaaa.fuse.ui.shell.platform.CaptureResult>()
    override val leftFuse = kotlinx.coroutines.flow.MutableSharedFlow<Unit>()
    private var recording = false

    private fun preview(): androidx.compose.ui.graphics.ImageBitmap {
        val image = androidx.compose.ui.graphics.ImageBitmap(256, 144)
        val canvas = androidx.compose.ui.graphics.Canvas(image)
        val paint = androidx.compose.ui.graphics.Paint().apply {
            shader = androidx.compose.ui.graphics.LinearGradientShader(
                androidx.compose.ui.geometry.Offset(0f, 0f), androidx.compose.ui.geometry.Offset(256f, 144f),
                listOf(androidx.compose.ui.graphics.Color(0xFF3A1E14), androidx.compose.ui.graphics.Color(0xFF111319)),
            )
        }
        canvas.drawRect(0f, 0f, 256f, 144f, paint)
        return image
    }

    override suspend fun screenshot(name: String) = io.github.matiyaaa.fuse.ui.shell.platform.CaptureResult(picturesPlace, preview())
    override suspend fun prepareRecording(withSound: Boolean) = io.github.matiyaaa.fuse.ui.shell.platform.RecordingReady.READY
    override fun startRecording(name: String) { recording = true }
    override suspend fun stopRecording(): io.github.matiyaaa.fuse.ui.shell.platform.CaptureResult? {
        if (!recording) return null
        recording = false
        return io.github.matiyaaa.fuse.ui.shell.platform.CaptureResult(videosPlace, preview(), video = true)
    }
}

/** Phone Link without a server: reports what a scenario sets and draws a code-like pattern. */
internal class AuditPhoneLink : io.github.matiyaaa.fuse.ui.shell.store.PhoneLinkControl {
    override val state = kotlinx.coroutines.flow.MutableStateFlow(io.github.matiyaaa.fuse.ui.shell.store.PhoneLinkState())

    override suspend fun setAccount(username: String, password: String): Result<Unit> {
        if (password.length < 6) return Result.failure(IllegalArgumentException("The password needs at least 6 characters"))
        state.value = state.value.copy(username = username, sessions = 0)
        return Result.success(Unit)
    }

    override suspend fun signOutAll() {
        state.value = state.value.copy(sessions = 0)
    }

    override suspend fun pairingLink(address: String?): String? =
        if (!state.value.running) null else (address ?: state.value.addresses.firstOrNull())?.trimEnd('/')?.plus("/pair/audit")

    /** A version 3 sized grid with the three finder squares and a fixed scatter, like a real code. */
    override fun qr(text: String): List<BooleanArray> {
        val n = 29
        val random = kotlin.random.Random(text.hashCode())
        fun finder(x: Int, y: Int, fx: Int, fy: Int): Boolean? {
            val dx = x - fx
            val dy = y - fy
            if (dx !in -1..7 || dy !in -1..7) return null
            if (dx == -1 || dy == -1 || dx == 7 || dy == 7) return false
            val ring = minOf(dx, dy, 6 - dx, 6 - dy)
            return ring != 1
        }
        return List(n) { y ->
            BooleanArray(n) { x ->
                finder(x, y, 0, 0) ?: finder(x, y, n - 7, 0) ?: finder(x, y, 0, n - 7) ?: (random.nextInt(100) < 48)
            }
        }
    }
}

/** A computer's Store installer that writes nothing outside the audit's cache. */
internal class AuditDesktopInstaller(override val host: Host, private val dir: File) : io.github.matiyaaa.fuse.ui.shell.store.DesktopInstaller {
    override val arch: String = "x86_64"
    override val folder: String = if (host == Host.WINDOWS) "C:/Users/you/Emulators" else "~/Applications"

    override suspend fun newDownload(fileName: String): io.github.matiyaaa.fuse.ui.shell.store.DownloadSink? {
        dir.mkdirs()
        val file = File(dir, fileName)
        val out = file.outputStream()
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        return object : io.github.matiyaaa.fuse.ui.shell.store.DownloadSink {
            override val path: String = file.absolutePath
            override suspend fun write(bytes: ByteArray, count: Int) {
                out.write(bytes, 0, count)
                digest.update(bytes, 0, count)
            }
            override suspend fun finish(): String {
                out.close()
                return digest.digest().joinToString("") { "%02x".format(it) }
            }
            override suspend fun discard() {
                out.close()
                file.delete()
            }
        }
    }

    override fun freeBytes(): Long = 412L * 1_000_000_000

    override suspend fun install(name: String, file: String, fileName: String, kind: io.github.matiyaaa.fuse.integrations.obtainium.DesktopAssetKind, previous: String?): String =
        "$folder/$name"

    override suspend fun remove(path: String): Boolean = true

    override suspend fun exists(path: String): Boolean = false

    override fun launch(path: String): Boolean = true
}

/** Steam on a Linux computer, closed, with Fuse not yet in its library. */
internal class AuditSteam : io.github.matiyaaa.fuse.ui.shell.platform.SteamIntegration {
    @Volatile private var inLibrary = false
    override val gameMode: Boolean = false
    override suspend fun added(): Boolean = inLibrary
    override suspend fun addFuse(): Result<String> {
        inLibrary = true
        return Result.success("Fuse is in Steam's library, with its art.")
    }
    override suspend fun removeFuse(): Result<String> {
        inLibrary = false
        return Result.success("Fuse is out of Steam's library.")
    }

    // Games: Steam ROM Manager already put the Metroid games there; the rest are Fuse's to add.
    private val games = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()
    @Volatile private var undo: Set<Long>? = null
    override suspend fun gamesInSteam(): Set<Long> = games.toSet()
    override suspend fun planGames(games: List<io.github.matiyaaa.fuse.library.steam.SteamGameEntry>) = games.map { g ->
        io.github.matiyaaa.fuse.library.steam.SteamGamePlanItem(
            g,
            when {
                g.gameId in this.games -> io.github.matiyaaa.fuse.library.steam.SteamGameAction.UNCHANGED
                "Metroid" in g.name -> io.github.matiyaaa.fuse.library.steam.SteamGameAction.ELSEWHERE
                else -> io.github.matiyaaa.fuse.library.steam.SteamGameAction.CREATE
            },
        )
    }
    override suspend fun addGames(games: List<io.github.matiyaaa.fuse.library.steam.SteamGameEntry>, art: Map<Long, io.github.matiyaaa.fuse.library.steam.SteamArt>, collections: Boolean): Result<String> {
        undo = this.games.toSet()
        val new = games.filter { "Metroid" !in it.name }.map { it.gameId }
        this.games += new
        return Result.success(if (new.size == 1) "1 game added. Start Steam to see it." else "${new.size} games added. Start Steam to see them.")
    }
    override suspend fun removeGames(gameIds: Set<Long>?): Result<String> {
        undo = games.toSet()
        if (gameIds == null) games.clear() else games -= gameIds
        return Result.success("Out of Steam's library.")
    }
    override val canUndoGames: Boolean get() = undo != null
    override suspend fun undoGames(): Result<String> {
        val back = undo ?: return Result.failure(IllegalStateException("There's no change of Fuse's to undo."))
        games.clear(); games += back; undo = null
        return Result.success("Steam's library is back as it was before Fuse's last change.")
    }
}

/** Decky Loader installed, the Fuse plugin not yet. Installing succeeds, as the password prompt would. */
internal class AuditDecky : io.github.matiyaaa.fuse.ui.shell.platform.DeckyIntegration {
    @Volatile private var plugin = false
    override suspend fun state() = io.github.matiyaaa.fuse.ui.shell.platform.DeckyState(loader = true, plugin = plugin, pluginVersion = if (plugin) "0.4.2" else null, bundledVersion = "0.4.2", canInstall = true)
    override suspend fun install(): Result<String> {
        plugin = true
        return Result.success("The Fuse plugin is in Decky.")
    }
    override suspend fun remove(): Result<String> {
        plugin = false
        return Result.success("The Fuse plugin is out of Decky.")
    }
    override suspend fun stage(): String? = null
}

/**
 * RetroArch's folders on the audit computer: its downloaded cheats (Online Updater, Update Cheats)
 * for every sample game, by libretro system, and room for the game files Fuse writes.
 */
internal class AuditEmulatorFiles(private val root: File) : io.github.matiyaaa.fuse.ui.shell.store.EmulatorFiles {
    init {
        val cheats = File(root, "cheats")
        val names = AuditSamples.games.map { it.folder to it.title } + io.github.matiyaaa.fuse.ui.shell.screenshots.SampleLibrary.games.map { it.folder to it.file }
        for ((folder, name) in names) {
            for (system in io.github.matiyaaa.fuse.integrations.libretro.LibretroThumbnails.systemsFor(PlatformId(folder))) {
                File(cheats, "$system/$name.cht").apply { parentFile.mkdirs() }.writeText(CHEATS)
            }
        }
        File(root, "retroarch.cfg").writeText("apply_cheats_after_load = \"false\"\n")
        File(root, "duckstation").mkdirs()
        File(root, "duckstation/settings.ini").writeText("[GPU]\nRenderer = Vulkan\nResolutionScale = 3\n\n[Display]\nAspectRatio = Auto (Game Native)\n")
    }

    override suspend fun duckstation(installed: InstalledEmulator): io.github.matiyaaa.fuse.launch.patches.DuckStationHome {
        val d = File(root, "duckstation").absolutePath
        return io.github.matiyaaa.fuse.launch.patches.DuckStationHome(d, "$d/gamesettings", "$d/cheats", "$d/patches", null, null)
    }

    override suspend fun pcsx2(installed: InstalledEmulator): io.github.matiyaaa.fuse.launch.patches.Pcsx2Home? = null

    override suspend fun retroArch(installed: InstalledEmulator) =
        io.github.matiyaaa.fuse.launch.patches.RetroArchHome(File(root, "retroarch.cfg").absolutePath, File(root, "cheats").absolutePath, File(root, "config").absolutePath)

    override suspend fun zipText(zip: String, entry: String): String? = null

    override suspend fun write(path: String, text: String): Boolean {
        if (!path.startsWith(root.absolutePath + "/")) return false
        File(path).apply { parentFile.mkdirs() }.writeText(text)
        return true
    }

    override suspend fun remove(path: String): Boolean = path.startsWith(root.absolutePath + "/") && File(path).delete()

    override suspend fun inspect(source: String): io.github.matiyaaa.fuse.ui.shell.store.SourceListing? = File(source).takeIf { it.isFile }?.let {
        io.github.matiyaaa.fuse.ui.shell.store.SourceListing(it.name, io.github.matiyaaa.fuse.ui.shell.store.SourceForm.FILE, listOf(it.name), it.length())
    }

    override suspend fun sourceText(source: String, entry: String): String? = File(source).takeIf { it.isFile && it.name == entry }?.readText()

    private companion object {
        val CHEATS = listOf("Infinite Lives", "Start With 99 Coins", "Invincibility", "Moon Jump", "Unlock All Levels")
            .mapIndexed { i, d -> "cheat${i}_desc = \"$d\"\ncheat${i}_code = \"7E0DBE:0$i\"\ncheat${i}_enable = false\n" }
            .joinToString("\n", prefix = "cheats = 5\n\n")
    }
}

/** GameBanana as the audit has it: one game, a few mods of different kinds, and a cheat file. */
internal object AuditGameBanana {
    private const val GAMES = """{"_aMetadata": {"_nRecordCount": 2, "_bIsComplete": true}, "_aRecords": [
        {"_idRow": 4401, "_sModelName": "Game", "_sName": "Emberline Saga II"},
        {"_idRow": 4400, "_sModelName": "Game", "_sName": "Emberline Saga"}]}"""
    private const val MODS = """{"_aMetadata": {"_nRecordCount": 4, "_bIsComplete": true}, "_aRecords": [
        {"_idRow": 9101, "_sModelName": "Mod", "_sName": "Treasure Hunter Cheats", "_aRootCategory": {"_sName": "Cheats"}, "_sVersion": "1.3", "_aSubmitter": {"_sName": "Kestrel"}, "_nLikeCount": 212},
        {"_idRow": 9102, "_sModelName": "Mod", "_sName": "Speedrun Practice Codes", "_aRootCategory": {"_sName": "Cheats"}, "_sVersion": "2.0", "_aSubmitter": {"_sName": "Mira"}, "_nLikeCount": 87},
        {"_idRow": 9103, "_sModelName": "Mod", "_sName": "Restored Script", "_aRootCategory": {"_sName": "Game files"}, "_sVersion": "1.0", "_aSubmitter": {"_sName": "Oren"}, "_nLikeCount": 164},
        {"_idRow": 9104, "_sModelName": "Mod", "_sName": "Widescreen Hack", "_aRootCategory": {"_sName": "Other/Misc"}, "_aSubmitter": {"_sName": "Pell"}, "_nLikeCount": 31}]}"""
    private const val MOD = """{"_idRow": 9101, "_sName": "Treasure Hunter Cheats", "_sProfileUrl": "https://gamebanana.com/mods/9101", "_aCategory": {"_sName": "Cheats"}, "_sVersion": "1.3",
        "_sText": "Max gold, every chest marked on the map, and a walk-through-walls toggle for finding the last few.<br>Made for the US release.",
        "_sLicense": "Free to share with credit",
        "_aFiles": [
          {"_idRow": 70001, "_sFile": "treasure_hunter_1.3.zip", "_nFilesize": 4812, "_sDownloadUrl": "https://gamebanana.com/dl/70001", "_sMd5Checksum": "0123456789abcdef0123456789abcdef", "_sAnalysisResult": "ok", "_sAvResult": "clean", "_sVersion": "1.3"},
          {"_idRow": 70000, "_sFile": "treasure_hunter_1.2.7z", "_nFilesize": 4100, "_sDownloadUrl": "https://gamebanana.com/dl/70000", "_sAnalysisResult": "ok", "_sAvResult": "clean", "_sVersion": "1.2"}]}"""

    fun answer(scope: io.ktor.client.engine.mock.MockRequestHandleScope, request: io.ktor.client.request.HttpRequestData): io.ktor.client.request.HttpResponseData? {
        if (request.url.host != "gamebanana.com") return null
        val path = request.url.encodedPath
        val body = when {
            path.endsWith("/Util/Search/Results") -> GAMES
            path.endsWith("/Mod/Index") -> MODS
            path.endsWith("/Mod/9101/ProfilePage") -> MOD
            else -> return null
        }
        return with(scope) {
            respond(body, io.ktor.http.HttpStatusCode.OK, io.ktor.http.headersOf(io.ktor.http.HttpHeaders.ContentType, "application/json"))
        }
    }
}
