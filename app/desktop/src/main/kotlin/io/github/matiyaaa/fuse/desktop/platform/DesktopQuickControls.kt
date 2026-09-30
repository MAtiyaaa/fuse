package io.github.matiyaaa.fuse.desktop.platform

import io.github.matiyaaa.fuse.desktop.system.Processes
import io.github.matiyaaa.fuse.ui.shell.platform.QuickControls
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Quick controls backed by the desktop's own tools, each only when present: volume through PipeWire
 * (`wpctl`) or PulseAudio (`pactl`), backlight through `brightnessctl`, and settings panels through
 * GNOME Settings or KDE System Settings. Missing tools leave the control out (null value).
 */
internal class DesktopQuickControls(private val scope: CoroutineScope) : QuickControls {
    private val wpctl = Processes.which("wpctl")
    private val pactl = Processes.which("pactl")
    private val brightnessctl = Processes.which("brightnessctl")
    private val gnomeSettings = Processes.which("gnome-control-center")
    private val kdeSettings = Processes.which("systemsettings") ?: Processes.which("systemsettings5")

    val volumeAvailable: Boolean get() = wpctl != null || pactl != null
    val brightnessAvailable: Boolean get() = brightnessctl != null
    val settingsAvailable: Boolean get() = gnomeSettings != null || kdeSettings != null

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

    override fun openWifi() = openPanel(gnome = "wifi", kde = "kcm_networkmanagement")
    override fun openBluetooth() = openPanel(gnome = "bluetooth", kde = "kcm_bluetooth")
    override fun openDisplaySettings() = openPanel(gnome = "display", kde = "kcm_kscreen")
    override fun openSoundSettings() = openPanel(gnome = "sound", kde = "kcm_pulseaudio")
    override fun openSystemSettings() = openPanel(gnome = null, kde = null)

    /** Plasma 6 has a game controller page; GNOME has none, so its settings open at the start. */
    override fun openControllerSettings() = openPanel(gnome = null, kde = "kcm_gamecontroller")

    private fun openPanel(gnome: String?, kde: String?) {
        val onKde = (System.getenv("XDG_CURRENT_DESKTOP") ?: "").contains("KDE", ignoreCase = true)
        val argv = when {
            kdeSettings != null && (onKde || gnomeSettings == null) -> listOfNotNull(kdeSettings, kde)
            gnomeSettings != null -> listOfNotNull(gnomeSettings, gnome)
            else -> return
        }
        scope.launch(Dispatchers.IO) { Processes.spawn(argv) }
    }
}
