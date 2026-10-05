package io.github.matiyaaa.fuse.desktop.platform

import io.github.matiyaaa.fuse.desktop.BuildInfo
import io.github.matiyaaa.fuse.desktop.FuseDirs
import io.github.matiyaaa.fuse.desktop.Log
import io.github.matiyaaa.fuse.desktop.WindowMode
import io.github.matiyaaa.fuse.desktop.services.UpdateHandoff
import io.github.matiyaaa.fuse.desktop.system.DesktopOs
import io.github.matiyaaa.fuse.desktop.system.Processes
import io.github.matiyaaa.fuse.model.CapabilityProfile
import io.github.matiyaaa.fuse.model.DisplayInfo
import io.github.matiyaaa.fuse.model.Host
import io.github.matiyaaa.fuse.model.PerformanceMetric
import io.github.matiyaaa.fuse.model.SystemStatus
import io.github.matiyaaa.fuse.ui.designsystem.sound.UiSounds
import io.github.matiyaaa.fuse.ui.shell.platform.Haptics
import io.github.matiyaaa.fuse.ui.shell.platform.HomeRole
import io.github.matiyaaa.fuse.ui.shell.platform.PlatformFeatures
import io.github.matiyaaa.fuse.ui.shell.platform.PlatformUi
import io.github.matiyaaa.fuse.ui.shell.platform.QuickControls
import io.github.matiyaaa.fuse.ui.shell.platform.StorageAccess
import io.github.matiyaaa.fuse.ui.shell.platform.VideoPreview
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.awt.Desktop
import java.awt.GraphicsEnvironment
import java.awt.Toolkit
import java.io.File
import java.lang.management.ManagementFactory
import java.net.URI
import io.github.matiyaaa.fuse.ui.shell.platform.WindowStyle
import io.github.matiyaaa.fuse.ui.shell.platform.WindowControls

/** Window-level actions [DesktopPlatformUi] needs from Main. */
interface WindowActions : DialogHost {
    fun exitApplication()

    /** Fullscreen, borderless or windowed right now. */
    val currentWindowMode: WindowMode

    /** Switches and remembers the window mode. */
    fun changeWindowMode(mode: WindowMode)
}

/**
 * [PlatformUi] for Linux, Windows and macOS. Status, displays and performance numbers come from
 * sysfs, procfs and AWT on Linux, and from the system's own tools elsewhere; anything the system
 * doesn't report is left out. Android-only features (Home role, launching on another display, video
 * previews) are reported as unavailable so their screens stay hidden, and Cartridge only exists on
 * Linux.
 */
class DesktopPlatformUi(
    private val dirs: FuseDirs,
    private val scope: CoroutineScope,
    private val window: WindowActions,
    private val args: List<String>,
) : PlatformUi, AutoCloseable {
    private val os = DesktopOs.current
    override val host: Host = os.host
    override val appVersion: String = BuildInfo.VERSION

    private val quickControls = DesktopQuickControls(scope)
    private val desktopSounds = DesktopSounds().apply { setProfile(io.github.matiyaaa.fuse.model.SoundProfile.SOFT) }

    override val features: PlatformFeatures = PlatformFeatures(
        homeRole = false,
        androidApps = false,
        secondScreen = false,
        launchOnOtherDisplay = false,
        overlay = false,
        videoPreview = false,
        brightness = quickControls.brightnessAvailable,
        volume = quickControls.volumeAvailable,
        wifiSettings = quickControls.settingsAvailable,
        bluetoothSettings = quickControls.settingsAvailable,
        canExit = true,
        windowModes = true,
        cartridge = os == DesktopOs.LINUX,
    )

    override val device: CapabilityProfile = measureDevice()

    private val _status = MutableStateFlow(SystemStatus())
    override val status: StateFlow<SystemStatus> = _status.asStateFlow()

    private val _displays = MutableStateFlow(readDisplays())
    override val displays: StateFlow<List<DisplayInfo>> = _displays.asStateFlow()

    private val _performance = MutableStateFlow<List<PerformanceMetric>>(emptyList())
    override val performance: StateFlow<List<PerformanceMetric>> = _performance.asStateFlow()

    override val sounds: UiSounds get() = desktopSounds
    override val haptics: Haptics = Haptics.None
    override val homeRole: HomeRole? = null
    private val files = DesktopStorage(dirs, window)
    override val storage: StorageAccess = files

    override suspend fun saveFile(name: String, mimeType: String, bytes: ByteArray): String? = files.saveFile(name, bytes)

    override suspend fun openFile(mimeTypes: List<String>, extensions: List<String>, maxBytes: Long): io.github.matiyaaa.fuse.ui.shell.platform.OpenedFile? =
        files.openFile("Open", "Fuse files", extensions, maxBytes)
    override val quick: QuickControls = quickControls

    /** No desktop video player is bundled yet, so previews are off rather than half working. */
    override val video: VideoPreview? = null
    override val music: DesktopMenuMusic = DesktopMenuMusic()

    init {
        // Status and displays: every 10 s while anything shows them.
        scope.launch {
            _status.subscriptionCount.map { it > 0 }.distinctUntilChanged().collectLatest { active ->
                while (active) {
                    _status.value = kotlinx.coroutines.withContext(Dispatchers.IO) { StatusReader.read() }
                    _displays.value = kotlinx.coroutines.withContext(Dispatchers.IO) { readDisplays() }
                    delay(STATUS_INTERVAL_MS)
                }
            }
        }
        // Performance numbers: every 2 s, only while the panel is open.
        scope.launch {
            val reader = PerformanceReader()
            _performance.subscriptionCount.map { it > 0 }.distinctUntilChanged().collectLatest { active ->
                if (!active) return@collectLatest
                kotlinx.coroutines.withContext(Dispatchers.IO) { reader.read() } // primes the CPU delta
                while (true) {
                    delay(PERF_INTERVAL_MS)
                    _performance.value = kotlinx.coroutines.withContext(Dispatchers.IO) { reader.read() }
                }
            }
        }
    }

    /** Fuse came back to the front: refresh values that may have changed while a game ran. */
    fun onForeground() {
        quickControls.refresh()
        scope.launch(Dispatchers.IO) { _status.value = StatusReader.read() }
    }

    override fun openUrl(url: String) {
        if (!url.startsWith("https://") && !url.startsWith("http://")) return
        scope.launch(Dispatchers.IO) {
            val xdgOpen = Processes.which("xdg-open")
            if (xdgOpen != null && Processes.spawn(listOf(xdgOpen, url)) != null) return@launch
            try {
                if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                    Desktop.getDesktop().browse(URI(url))
                }
            } catch (e: Exception) {
                Log.warn("could not open a browser", e)
            }
        }
    }

    /**
     * Starts a new Fuse and leaves this one. Prefers an AppImage this session just installed, then
     * the AppImage Fuse runs from, the packaged launcher, and finally the same java command line.
     */
    override fun eraseAndRestart(): Boolean {
        // Erased as the next start begins, before anything opens the database.
        val marker = java.io.File(dirs.home.ifBlank { System.getProperty("java.io.tmpdir") }, ERASE_MARKER)
        if (runCatching { marker.writeText(BuildInfo.VERSION) }.isFailure) return false
        if (restartCommand() == null) {
            // Can't start itself again here: closes, and erases when opened next.
            window.exitApplication()
            return true
        }
        restart()
        return true
    }

    override fun restart() {
        val command = restartCommand()
        if (command == null) {
            Log.warn("restart is not possible from this environment")
            return
        }
        if (Processes.spawn(command) == null) return
        window.exitApplication()
    }

    private fun restartCommand(): List<String>? {
        val passArgs = args.filter { it.startsWith("--") }
        UpdateHandoff.installedFuseAppImage?.takeIf { Processes.isExecutable(it) }?.let { return listOf(it) + passArgs }
        System.getenv("APPIMAGE")?.takeIf { Processes.isExecutable(it) }?.let { return listOf(it) + passArgs }
        System.getProperty("jpackage.app-path")?.takeIf { Processes.isExecutable(it) }?.let { return listOf(it) + passArgs }
        val java = ProcessHandle.current().info().command().orElse(null) ?: return null
        val classPath = System.getProperty("java.class.path")?.takeIf { it.isNotBlank() } ?: return null
        val jvmArgs = ManagementFactory.getRuntimeMXBean().inputArguments
        return listOf(java) + jvmArgs + listOf("-cp", classPath, MAIN_CLASS) + args
    }

    override fun exit() = window.exitApplication()

    private val crashLog = io.github.matiyaaa.fuse.desktop.CrashLog(File(dirs.data, "crash"))

    override suspend fun readClipboardText(): String? = kotlinx.coroutines.withContext(Dispatchers.IO) {
        try {
            val clipboard = Toolkit.getDefaultToolkit().systemClipboard
            if (!clipboard.isDataFlavorAvailable(java.awt.datatransfer.DataFlavor.stringFlavor)) {
                null
            } else {
                (clipboard.getData(java.awt.datatransfer.DataFlavor.stringFlavor) as? String)?.takeIf { it.isNotEmpty() }
            }
        } catch (e: Exception) {
            // Headless, no clipboard owner, or the owner went away while reading.
            null
        }
    }

    override suspend fun writeClipboardText(text: String): Boolean = kotlinx.coroutines.withContext(Dispatchers.IO) {
        try {
            Toolkit.getDefaultToolkit().systemClipboard.setContents(java.awt.datatransfer.StringSelection(text), null)
            true
        } catch (e: Exception) {
            false
        }
    }

    override fun lastCrashReport(): String? = crashLog.read()

    override fun clearCrashReport() = crashLog.clear()

    override val steam: io.github.matiyaaa.fuse.ui.shell.platform.SteamIntegration =
        DesktopSteam(io.github.matiyaaa.fuse.desktop.services.KnownFolders(dirs.home, os))

    override val windowControls: WindowControls = object : WindowControls {
        override val mode: WindowStyle get() = when (windowMode) {
            WindowMode.FULLSCREEN -> WindowStyle.FULLSCREEN
            WindowMode.BORDERLESS -> WindowStyle.BORDERLESS
            WindowMode.WINDOWED -> WindowStyle.WINDOWED
        }

        override fun setMode(mode: WindowStyle) = setWindowMode(
            when (mode) {
                WindowStyle.FULLSCREEN -> WindowMode.FULLSCREEN
                WindowStyle.BORDERLESS -> WindowMode.BORDERLESS
                WindowStyle.WINDOWED -> WindowMode.WINDOWED
            },
        )

        override val autostartAvailable: Boolean get() = autostartSupported
        override fun isAutostart(): Boolean = isAutostartEnabled()
        override fun setAutostart(enabled: Boolean): Result<Unit> = this@DesktopPlatformUi.setAutostart(enabled)
    }

    // Desktop-only controls behind [window] (F11 and the command line flags also change the mode).

    val windowMode: WindowMode get() = window.currentWindowMode

    fun setWindowMode(mode: WindowMode) = window.changeWindowMode(mode)

    /** True when Fuse runs from an AppImage or package, so a login entry has a stable path. */
    val autostartSupported: Boolean get() = Autostart.supported()

    fun isAutostartEnabled(): Boolean = Autostart.isEnabled(dirs)

    /** Writes or removes `~/.config/autostart/fuse.desktop`. Only on the user's request. */
    fun setAutostart(enabled: Boolean): Result<Unit> = if (enabled) Autostart.enable(dirs) else Autostart.disable(dirs)

    override fun close() {
        desktopSounds.close()
        music.close()
    }

    private fun measureDevice(): CapabilityProfile {
        val ram = totalRamMb() ?: (Runtime.getRuntime().maxMemory() / (1024 * 1024))
        val headless = GraphicsEnvironment.isHeadless()
        val screens = if (headless) emptyArray() else GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices
        val primary = if (headless) null else GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice
        val mode = primary?.displayMode
        // AWT reports 0 when the refresh rate is unknown; 60 Hz is then the assumption.
        val refresh = screens.maxOfOrNull { it.displayMode.refreshRate }?.takeIf { it > 0 }?.toFloat() ?: 60f
        val dpi = try {
            if (headless) 96 else Toolkit.getDefaultToolkit().screenResolution
        } catch (e: Exception) {
            96
        }
        return CapabilityProfile(
            cpuCores = Runtime.getRuntime().availableProcessors(),
            totalRamMb = ram,
            isLowRamDevice = ram in 1..2_048,
            maxRefreshRate = refresh,
            screenWidthPx = mode?.width ?: 1920,
            screenHeightPx = mode?.height ?: 1080,
            densityDpi = dpi,
            displayCount = screens.size.coerceAtLeast(1),
        )
    }

    private fun readDisplays(): List<DisplayInfo> {
        if (GraphicsEnvironment.isHeadless()) return emptyList()
        return try {
            val ge = GraphicsEnvironment.getLocalGraphicsEnvironment()
            val primary = ge.defaultScreenDevice
            ge.screenDevices.mapIndexed { i, d ->
                val mode = d.displayMode
                DisplayInfo(
                    id = i,
                    name = "Screen ${i + 1}",
                    widthPx = mode.width,
                    heightPx = mode.height,
                    refreshRate = mode.refreshRate.toFloat(),
                    isPrimary = d == primary,
                    isPresentation = false,
                    isOn = true,
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private companion object {
        const val STATUS_INTERVAL_MS = 10_000L
        const val PERF_INTERVAL_MS = 2_000L
        const val MAIN_CLASS = "io.github.matiyaaa.fuse.desktop.MainKt"
    }
}

/** Left in the home folder by Erase Fuse; the next start erases Fuse's folders, then removes it. */
internal const val ERASE_MARKER = ".fuse-erase"
