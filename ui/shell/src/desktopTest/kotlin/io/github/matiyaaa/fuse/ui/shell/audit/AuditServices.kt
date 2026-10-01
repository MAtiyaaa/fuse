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
import io.github.matiyaaa.fuse.ui.shell.platform.MenuMusicPlayer
import io.github.matiyaaa.fuse.ui.shell.platform.HomeRole
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
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first

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
    /** Windows and macOS: no app list, and emulators the user can point Fuse at. */
    private val desktop = host == Host.WINDOWS || host == Host.MACOS

    override val fs: FuseFileSystem = object : FuseFileSystem by base.fs {
        override suspend fun list(path: String): List<FsEntry> {
            controls.beforeList()
            return base.fs.list(path)
        }
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
) : PlatformUi by ScreenshotPlatform {
    override val device: CapabilityProfile = ScreenshotPlatform.device.copy(
        screenWidthPx = size.widthPx,
        screenHeightPx = size.heightPx,
        densityDpi = (size.density * 160).toInt(),
    )
    override val performance: StateFlow<List<PerformanceMetric>> = MutableStateFlow(metrics)

    /** A silent player, so the Sound settings show what a device with audio shows. */
    override val music: MenuMusicPlayer = object : MenuMusicPlayer {
        override fun setSong(path: String?) = Unit
        override fun setVolume(volume: Float) = Unit
        override fun setPlaying(playing: Boolean) = Unit
    }

    companion object {
        val SampleMetrics = listOf(
            PerformanceMetric("fps", "Fuse frame rate", "60 fps", 1f, source = "Compose frame clock"),
            PerformanceMetric("mem", "Memory", "412 MB of 16 GB", 0.025f, source = "JVM"),
            PerformanceMetric("cpu", "CPU temperature", "48 °C", 0.48f, source = "thermal zone 0"),
            PerformanceMetric("battery", "Battery", "78%", 0.78f, source = "power supply"),
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
