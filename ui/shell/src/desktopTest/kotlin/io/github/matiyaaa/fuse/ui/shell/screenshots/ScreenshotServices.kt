package io.github.matiyaaa.fuse.ui.shell.screenshots

import io.github.matiyaaa.fuse.data.FuseData
import io.github.matiyaaa.fuse.data.settings.SecretStore
import io.github.matiyaaa.fuse.integrations.github.ReleasePlatform
import io.github.matiyaaa.fuse.launch.ResolvedLaunch
import io.github.matiyaaa.fuse.library.FuseFileSystem
import io.github.matiyaaa.fuse.model.CapabilityProfile
import io.github.matiyaaa.fuse.model.CartridgeRoute
import io.github.matiyaaa.fuse.model.CartridgeStatus
import io.github.matiyaaa.fuse.model.ConnectionState
import io.github.matiyaaa.fuse.model.DisplayInfo
import io.github.matiyaaa.fuse.model.EmulatorId
import io.github.matiyaaa.fuse.model.Host
import io.github.matiyaaa.fuse.model.InstalledEmulator
import io.github.matiyaaa.fuse.model.PerformanceMetric
import io.github.matiyaaa.fuse.model.PlatformId
import io.github.matiyaaa.fuse.model.ReleaseAsset
import io.github.matiyaaa.fuse.model.SystemStatus
import io.github.matiyaaa.fuse.ui.designsystem.sound.UiSounds
import io.github.matiyaaa.fuse.ui.shell.platform.Haptics
import io.github.matiyaaa.fuse.ui.shell.platform.HomeRole
import io.github.matiyaaa.fuse.ui.shell.platform.PlatformFeatures
import io.github.matiyaaa.fuse.ui.shell.platform.PlatformUi
import io.github.matiyaaa.fuse.ui.shell.platform.QuickControls
import io.github.matiyaaa.fuse.ui.shell.platform.StorageAccess
import io.github.matiyaaa.fuse.ui.shell.platform.StorageState
import io.github.matiyaaa.fuse.ui.shell.platform.VideoPreview
import io.github.matiyaaa.fuse.ui.shell.store.AppsProvider
import io.github.matiyaaa.fuse.ui.shell.store.CartridgeBridge
import io.github.matiyaaa.fuse.ui.shell.store.DeviceLocations
import io.github.matiyaaa.fuse.ui.shell.store.EmulatorDetector
import io.github.matiyaaa.fuse.ui.shell.store.FuseServices
import io.github.matiyaaa.fuse.ui.shell.store.GameLauncher
import io.github.matiyaaa.fuse.ui.shell.store.JavaFileSystem
import io.github.matiyaaa.fuse.ui.shell.store.LocationHint
import io.github.matiyaaa.fuse.ui.shell.store.MemorySecrets
import io.github.matiyaaa.fuse.ui.shell.store.ReleaseInstaller
import io.github.matiyaaa.fuse.ui.shell.store.RunResult
import io.github.matiyaaa.fuse.integrations.FuseHttp
import io.github.matiyaaa.fuse.integrations.FuseHttpConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The services behind the README screenshots: a real temporary library folder and SQLite database,
 * the emulators a typical Linux handheld setup would have installed (reported, never started), and
 * either no network or, with [live], the real one, so Fuse can fetch art the way it does on a
 * device. Nothing here runs a game.
 */
internal class ScreenshotServices(override val data: FuseData, private val cache: File, live: Boolean = false) : FuseServices {
    init {
        // Screenshots show what the scene sets up, not an art search Fuse started by itself.
        kotlinx.coroutines.runBlocking { data.settings.update { it.copy(scraping = it.scraping.copy(autoFill = false)) } }
    }

    override val host = Host.LINUX
    override val appVersion = "0.0.1"
    override val fs: FuseFileSystem = JavaFileSystem()
    override val secrets: SecretStore = MemorySecrets()
    // OkHttp follows the JVM's proxy settings (https.proxyHost), as a sandboxed or corporate network needs.
    override val http: HttpClient =
        if (live) FuseHttp.client(OkHttp.create(), FuseHttpConfig(appVersion = appVersion)) else HttpClient(MockEngine { respondError(HttpStatusCode.NotFound) })
    override val cacheDir: String = cache.absolutePath

    override val emulators = object : EmulatorDetector {
        override suspend fun detect() = InstalledEmulators
        override val homeDir: String = cache.absolutePath
    }

    /** Starting a game only records the request; the play history of the sample library is written directly. */
    override val launcher = object : GameLauncher {
        override suspend fun run(launch: ResolvedLaunch, displayId: Int?): RunResult = RunResult.Started()
        override suspend fun openApp(appId: String): RunResult = RunResult.Started()
    }

    override val cartridge = object : CartridgeBridge {
        override suspend fun read() = CartridgeStatus(installed = false)
        override fun open(route: CartridgeRoute, link: String) = false
    }

    override val installer = object : ReleaseInstaller {
        override val platform = ReleasePlatform.LINUX_X86_64
        override suspend fun install(asset: ReleaseAsset, onProgress: (Float) -> Unit) = Result.failure<Unit>(UnsupportedOperationException())
    }

    override val apps: AppsProvider? = null

    override val locations = object : DeviceLocations {
        override suspend fun libraryCandidates() = emptyList<LocationHint>()
        override suspend fun biosRoots() = emptyList<String>()
    }

    override fun writeCacheFile(relativePath: String, content: String): String? {
        require(!relativePath.contains("..")) { "Cache paths never leave the cache" }
        val file = File(cache, relativePath)
        file.parentFile.mkdirs()
        file.writeText(content)
        return file.absolutePath
    }

    override fun utcOffsetMillis() = 0L

    companion object {
        private fun emulator(id: String, name: String, path: String, vararg platforms: String) = InstalledEmulator(
            id = EmulatorId(id),
            name = name,
            host = Host.LINUX,
            appId = path,
            platforms = platforms.map(::PlatformId).toSet(),
            detectedVia = "PATH",
        )

        val InstalledEmulators = listOf(
            emulator("linux.retroarch", "RetroArch", "/usr/bin/retroarch", "snes", "genesis"),
            emulator("linux.mupen64plus", "Mupen64Plus (m64p)", "/usr/bin/m64p", "n64"),
            emulator("linux.duckstation", "DuckStation", "/usr/bin/duckstation-qt", "psx"),
            emulator("linux.pcsx2", "PCSX2", "/usr/bin/pcsx2-qt", "ps2"),
            emulator("linux.dolphin", "Dolphin", "/usr/bin/dolphin-emu", "ngc", "wii"),
            emulator("linux.flycast", "Flycast", "/usr/bin/flycast", "dc"),
            emulator("linux.mgba", "mGBA", "/usr/bin/mgba-qt", "gb", "gbc", "gba"),
            emulator("linux.melonds", "melonDS", "/usr/bin/melonDS", "nds"),
            emulator("linux.ryujinx", "Ryujinx", "/usr/bin/Ryujinx", "switch"),
        )
    }
}

/**
 * A Linux handheld at 1280 x 720 dp (a 1080p screen at 1.5x): battery, Wi-Fi, brightness and volume
 * are available, as the desktop app reports them on such a device.
 */
internal object ScreenshotPlatform : PlatformUi {
    override val host = Host.LINUX
    override val features = PlatformFeatures(
        brightness = true,
        volume = true,
        wifiSettings = true,
        bluetoothSettings = true,
        canExit = true,
        windowModes = true,
    )
    override val device = CapabilityProfile(
        cpuCores = 8, totalRamMb = 16_384, isLowRamDevice = false, maxRefreshRate = 60f,
        screenWidthPx = 1920, screenHeightPx = 1080, densityDpi = 240, displayCount = 1,
    )
    override val status: StateFlow<SystemStatus> = MutableStateFlow(
        SystemStatus(
            batteryPercent = 78,
            charging = false,
            batteryMinutes = 200,
            wifi = ConnectionState.CONNECTED,
            wifiStrength = 3,
            bluetooth = ConnectionState.ON,
            network = ConnectionState.CONNECTED,
        ),
    )
    override val displays: StateFlow<List<DisplayInfo>> = MutableStateFlow(emptyList())
    override val performance: StateFlow<List<PerformanceMetric>> = MutableStateFlow(emptyList())
    override val sounds: UiSounds = UiSounds.Silent
    override val haptics: Haptics = Haptics.None
    override val homeRole: HomeRole? = null
    override val storage = object : StorageAccess {
        override val state: StateFlow<StorageState> = MutableStateFlow(StorageState.NOT_NEEDED)
        override fun request() = Unit
        override suspend fun pickFolder(title: String): String? = null
        override suspend fun pickImage(title: String): String? = null
        override fun refresh() = Unit
    }
    override val quick = object : QuickControls {
        override val brightness: StateFlow<Float?> = MutableStateFlow(0.72f)
        override val volume: StateFlow<Float?> = MutableStateFlow(0.55f)
        override fun setBrightness(value: Float) = Unit
        override fun setVolume(value: Float) = Unit
        override fun openWifi() = Unit
        override fun openBluetooth() = Unit
        override fun openDisplaySettings() = Unit
        override fun openSoundSettings() = Unit
        override fun openSystemSettings() = Unit
        override fun openControllerSettings() = Unit
    }
    override val video: VideoPreview? = null
    override val appVersion = "0.0.1"
    override fun openUrl(url: String) = Unit
    override fun restart() = Unit
    override fun exit() = Unit
}
