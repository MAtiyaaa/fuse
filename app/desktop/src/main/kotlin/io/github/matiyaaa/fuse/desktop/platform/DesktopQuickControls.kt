package io.github.matiyaaa.fuse.desktop.platform

import io.github.matiyaaa.fuse.desktop.system.DesktopOs
import io.github.matiyaaa.fuse.desktop.system.Processes
import io.github.matiyaaa.fuse.ui.shell.platform.QuickControls
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

/**
 * Quick controls backed by the system's own tools, each only when present. Linux: volume through
 * PipeWire (`wpctl`) or PulseAudio (`pactl`), backlight through `brightnessctl`, and settings panels
 * through GNOME Settings or KDE System Settings. macOS: volume through `osascript` and System
 * Settings panes. Windows: the Settings app's pages. Missing tools leave the control out (null value).
 */
internal class DesktopQuickControls(private val scope: CoroutineScope, private val os: DesktopOs = DesktopOs.current) : QuickControls {
    private val linux = os == DesktopOs.LINUX
    private val wpctl = if (linux) Processes.which("wpctl") else null
    private val pactl = if (linux) Processes.which("pactl") else null
    private val brightnessctl = if (linux) Processes.which("brightnessctl") else null
    private val gnomeSettings = if (linux) Processes.which("gnome-control-center") else null
    private val kdeSettings = if (linux) Processes.which("systemsettings") ?: Processes.which("systemsettings5") else null
    private val osascript = "/usr/bin/osascript".takeIf { os == DesktopOs.MACOS && File(it).canExecute() }

    val volumeAvailable: Boolean get() = wpctl != null || pactl != null || osascript != null
    val brightnessAvailable: Boolean get() = brightnessctl != null
    val settingsAvailable: Boolean get() = gnomeSettings != null || kdeSettings != null || os != DesktopOs.LINUX

    private val _brightness = MutableStateFlow<Float?>(null)
    private val _volume = MutableStateFlow<Float?>(null)
    override val brightness: StateFlow<Float?> = _brightness.asStateFlow()
    override val volume: StateFlow<Float?> = _volume.asStateFlow()

    // Slider drags produce many values; only the latest one is applied.
    private val volumeWrites = Channel<Float>(Channel.CONFLATED)
    private val brightnessWrites = Channel<Float>(Channel.CONFLATED)

    init {
        scope.launch(Dispatchers.IO) { for (v in volumeWrites) writeVolume(v) }
        scope.launch(Dispatchers.IO) { for (v in brightnessWrites) writeBrightness(v) }
        refresh()
    }

    /** Reads the current values again (on start and when Fuse returns to the front). */
    fun refresh() {
        scope.launch(Dispatchers.IO) {
            if (volumeAvailable) _volume.value = readVolume()
            if (brightnessAvailable) _brightness.value = readBrightness()
        }
    }

    override fun setVolume(value: Float) {
        if (!volumeAvailable) return
        val v = value.coerceIn(0f, 1f)
        _volume.value = v
        volumeWrites.trySend(v)
    }

    override fun setBrightness(value: Float) {
        if (!brightnessAvailable || _brightness.value == null) return
        // Never all the way to black: the screen must stay readable.
        val v = value.coerceIn(0.05f, 1f)
        _brightness.value = v
        brightnessWrites.trySend(v)
    }

    private fun readVolume(): Float? {
        osascript?.let { tool ->
            val out = Processes.run(listOf(tool, "-e", "output volume of (get volume settings)"), timeoutMs = 3_000)
            return out?.takeIf { it.exitCode == 0 }?.stdout?.trim()?.toIntOrNull()?.let { (it / 100f).coerceIn(0f, 1f) }
        }
        wpctl?.let { tool ->
            val out = Processes.run(listOf(tool, "get-volume", "@DEFAULT_AUDIO_SINK@"), timeoutMs = 3_000)
            if (out?.exitCode == 0) {
                // "Volume: 0.45" or "Volume: 0.45 [MUTED]"
                Regex("""Volume:\s*([0-9.]+)""").find(out.stdout)?.groupValues?.get(1)?.toFloatOrNull()?.let { return it.coerceIn(0f, 1f) }
            }
        }
        pactl?.let { tool ->
            val out = Processes.run(listOf(tool, "get-sink-volume", "@DEFAULT_SINK@"), timeoutMs = 3_000)
            if (out?.exitCode == 0) {
                Regex("""(\d+)%""").find(out.stdout)?.groupValues?.get(1)?.toIntOrNull()?.let { return (it / 100f).coerceIn(0f, 1f) }
            }
        }
        return null
    }

    private fun writeVolume(v: Float) {
        val pct = (v * 100).toInt()
        osascript?.let { tool ->
            Processes.run(listOf(tool, "-e", "set volume output volume $pct"), timeoutMs = 3_000)
            return
        }
        wpctl?.let { tool ->
            val out = Processes.run(listOf(tool, "set-volume", "-l", "1.0", "@DEFAULT_AUDIO_SINK@", String.format(Locale.ROOT, "%.2f", v)), timeoutMs = 3_000)
            if (out?.exitCode == 0) return
        }
        pactl?.let { tool -> Processes.run(listOf(tool, "set-sink-volume", "@DEFAULT_SINK@", "$pct%"), timeoutMs = 3_000) }
    }

    private fun readBrightness(): Float? {
        val tool = brightnessctl ?: return null
        // Machine format: device,class,current,percent,max
        val out = Processes.run(listOf(tool, "-m", "-c", "backlight", "info"), timeoutMs = 3_000) ?: return null
        if (out.exitCode != 0) return null
        val fields = out.stdout.lineSequence().firstOrNull()?.split(',') ?: return null
        val current = fields.getOrNull(2)?.toFloatOrNull() ?: return null
        val max = fields.getOrNull(4)?.toFloatOrNull()?.takeIf { it > 0 } ?: return null
        return (current / max).coerceIn(0f, 1f)
    }

    private fun writeBrightness(v: Float) {
        val tool = brightnessctl ?: return
        val out = Processes.run(listOf(tool, "-q", "-c", "backlight", "set", "${(v * 100).toInt()}%"), timeoutMs = 3_000)
        // Without permission to change the backlight, show the real value again.
        if (out?.exitCode != 0) _brightness.value = readBrightness()
    }

    override fun openWifi() = openPanel(gnome = "wifi", kde = "kcm_networkmanagement", windows = "network-wifi", mac = "com.apple.wifi-settings-extension")
    override fun openBluetooth() = openPanel(gnome = "bluetooth", kde = "kcm_bluetooth", windows = "bluetooth", mac = "com.apple.BluetoothSettings")
    override fun openDisplaySettings() = openPanel(gnome = "display", kde = "kcm_kscreen", windows = "display", mac = "com.apple.Displays-Settings.extension")
    override fun openSoundSettings() = openPanel(gnome = "sound", kde = "kcm_pulseaudio", windows = "sound", mac = "com.apple.Sound-Settings.extension")
    override fun openSystemSettings() = openPanel(gnome = null, kde = null, windows = "", mac = "")

    /** Plasma 6 has a game controller page; GNOME has none, so its settings open at the start. */
    override fun openControllerSettings() =
        openPanel(gnome = null, kde = "kcm_gamecontroller", windows = "devices", mac = "com.apple.Game-Controller-Settings.extension")

    /**
     * Opens a settings page: `ms-settings:<page>` on Windows, `x-apple.systempreferences:<pane>` on
     * macOS (an unknown pane opens System Settings at its start), else GNOME or KDE.
     */
    private fun openPanel(gnome: String?, kde: String?, windows: String, mac: String) {
        when (os) {
            DesktopOs.WINDOWS -> {
                scope.launch(Dispatchers.IO) { Processes.spawn(listOf("explorer.exe", "ms-settings:$windows")) }
                return
            }
            DesktopOs.MACOS -> {
                scope.launch(Dispatchers.IO) { Processes.spawn(listOf("/usr/bin/open", "x-apple.systempreferences:$mac")) }
                return
            }
            DesktopOs.LINUX -> Unit
        }
        val onKde = (System.getenv("XDG_CURRENT_DESKTOP") ?: "").contains("KDE", ignoreCase = true)
        val argv = when {
            kdeSettings != null && (onKde || gnomeSettings == null) -> listOfNotNull(kdeSettings, kde)
            gnomeSettings != null -> listOfNotNull(gnomeSettings, gnome)
            else -> return
        }
        scope.launch(Dispatchers.IO) { Processes.spawn(argv) }
    }
}
