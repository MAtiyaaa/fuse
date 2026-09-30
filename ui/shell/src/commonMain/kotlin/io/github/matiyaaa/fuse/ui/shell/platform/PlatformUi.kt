package io.github.matiyaaa.fuse.ui.shell.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.matiyaaa.fuse.model.CapabilityProfile
import io.github.matiyaaa.fuse.model.DisplayInfo
import io.github.matiyaaa.fuse.model.Host
import io.github.matiyaaa.fuse.model.PerformanceMetric
import io.github.matiyaaa.fuse.model.SystemStatus
import io.github.matiyaaa.fuse.ui.designsystem.sound.UiSounds
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * What the interface can ask of the operating system. Android and Linux each implement it; a feature
 * the platform lacks is reported in [features] and its screens and menu entries are hidden rather
 * than shown broken.
 */
interface PlatformUi {
    val host: Host
    val features: PlatformFeatures
    val device: CapabilityProfile
    val status: StateFlow<SystemStatus>
    val displays: StateFlow<List<DisplayInfo>>
    val performance: StateFlow<List<PerformanceMetric>>
    val sounds: UiSounds
    val haptics: Haptics
    val homeRole: HomeRole?
    val storage: StorageAccess
    val quick: QuickControls
    val video: VideoPreview?
    /** Plays the menu music the user chose; null where Fuse can't play audio files. */
    val music: MenuMusicPlayer? get() = null
    val appVersion: String

    /** Window and login controls on desktop Linux; null where the system manages Fuse's window. */
    val windowControls: WindowControls? get() = null

    /**
     * Recent second-screen events (companion started or closed and why, refused displays, display
     * changes), oldest first, for a status row in Settings. Empty where there is no companion screen.
     */
    val secondScreenLog: StateFlow<List<String>> get() = NoSecondScreenLog

    fun openUrl(url: String)
    fun restart()

    /** Leaves Fuse. Not offered while Fuse is the Home app (Home has nowhere to exit to). */
    fun exit()

    /** Text on the system clipboard, or null when it is empty, not text or can't be read. */
    suspend fun readClipboardText(): String? = null

    /** The last crash Fuse recorded (time, version, thread, stack trace), or null when there is none. */
    fun lastCrashReport(): String? = null

    /** Forgets the recorded crash, after the user has seen or shared it. */
    fun clearCrashReport() {}
}

private val NoSecondScreenLog: StateFlow<List<String>> = MutableStateFlow(emptyList())

data class PlatformFeatures(
    val homeRole: Boolean = false,
    val androidApps: Boolean = false,
    val secondScreen: Boolean = false,
    val launchOnOtherDisplay: Boolean = false,
    val overlay: Boolean = false,
    val videoPreview: Boolean = false,
    val brightness: Boolean = false,
    val volume: Boolean = false,
    val wifiSettings: Boolean = false,
    val bluetoothSettings: Boolean = false,
    val canExit: Boolean = true,
    val windowModes: Boolean = false,
)

interface Haptics {
    fun tick()
    fun confirm()
    fun reject()

    object None : Haptics {
        override fun tick() = Unit
        override fun confirm() = Unit
        override fun reject() = Unit
    }
}

interface HomeRole {
    val isHome: StateFlow<Boolean>
    /** Shows the system dialog (Android RoleManager). Never loops if the user declines. */
    fun request()
    fun openHomeSettings()
    /** Removes Fuse as a Home candidate entirely. */
    fun disable()
}

enum class StorageState { GRANTED, LIMITED, DENIED, NOT_NEEDED }

interface StorageAccess {
    val state: StateFlow<StorageState>
    /** Explains why, then opens the system screen for "All files access" (Android) or does nothing. */
    fun request()
    /** Opens the platform folder picker; returns a filesystem path Fuse can read, or null. */
    suspend fun pickFolder(title: String): String?

    /** Opens the platform image picker; returns a readable path (copied into Fuse's storage if needed), or null. */
    suspend fun pickImage(title: String): String?

    /** Opens a picker for an audio file and copies it into Fuse's storage; returns that copy, or null. */
    suspend fun pickAudio(title: String): PickedFile? = null
    fun refresh()
}

/**
 * Console-style quick controls. Wi-Fi and Bluetooth open the system panels (apps can't toggle them
 * on modern Android); brightness applies to Fuse's own window unless system brightness permission
 * was granted.
 */
interface QuickControls {
    val brightness: StateFlow<Float?>
    val volume: StateFlow<Float?>
    fun setBrightness(value: Float)
    fun setVolume(value: Float)
    fun openWifi()
    fun openBluetooth()
    fun openDisplaySettings()
    fun openSoundSettings()
    fun openSystemSettings()
    fun openControllerSettings()
}

/** Muted gameplay previews behind the interface (Android Media3). */
interface VideoPreview {
    @Composable
    fun Player(source: String, playing: Boolean, modifier: Modifier, onFirstFrame: () -> Unit)
}

enum class WindowStyle { FULLSCREEN, BORDERLESS, WINDOWED }

/**
 * Desktop window and startup controls. [mode] is snapshot state, so reading it in composition
 * follows changes made elsewhere (F11, Alt+Enter).
 */
interface WindowControls {
    val mode: WindowStyle
    fun setMode(mode: WindowStyle)

    /** True when Fuse runs from its AppImage or an installed package, so a login entry has a stable path. */
    val autostartAvailable: Boolean
    fun isAutostart(): Boolean

    /** Writes or removes the login entry. Only on the user's request. */
    fun setAutostart(enabled: Boolean): Result<Unit>
}

/** A file the user picked, as Fuse stored it, with the name it had. */
data class PickedFile(val path: String, val name: String)

/**
 * Loops one song under Fuse's menus. Fuse says what it wants ([setSong], [setVolume], [setPlaying]);
 * the platform also keeps it quiet while Fuse is in the background, and fades in and out.
 */
interface MenuMusicPlayer {
    /** The file to loop, or null for silence. Changing songs while music plays crossfades. */
    fun setSong(path: String?)

    /** 0..1 */
    fun setVolume(volume: Float)

    /** False fades the music out (a game is starting or running); true fades it back in. */
    fun setPlaying(playing: Boolean)
}
