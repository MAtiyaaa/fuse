package io.github.matiyaaa.fuse.ui.shell.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import io.github.matiyaaa.fuse.model.CapabilityProfile
import io.github.matiyaaa.fuse.model.DisplayInfo
import io.github.matiyaaa.fuse.model.Host
import io.github.matiyaaa.fuse.model.PerformanceMetric
import io.github.matiyaaa.fuse.model.SystemStatus
import io.github.matiyaaa.fuse.ui.designsystem.sound.UiSounds
import kotlinx.coroutines.flow.Flow
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

    /** Screenshots and recordings of Fuse's own screen; null where Fuse can't capture it (desktop for now). */
    val capture: ScreenCapture? get() = null

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

    /** Puts [text] on the system clipboard; false where that isn't possible. */
    suspend fun writeClipboardText(text: String): Boolean = false

    /**
     * Saves [bytes] as a file named [name] where the user chooses (the system's save dialog, or
     * Android's document picker). Returns where it went, in words for people, or null when the
     * user cancelled or this system can't save files.
     */
    suspend fun saveFile(name: String, mimeType: String, bytes: ByteArray): String? = null

    /**
     * Lets the user pick a file to open (a backup to restore), of [mimeTypes] or with one of
     * [extensions]. Its name and content, at most [maxBytes]; null when cancelled, too large or unreadable.
     */
    suspend fun openFile(mimeTypes: List<String>, extensions: List<String>, maxBytes: Long): OpenedFile? = null

    /** The last crash Fuse recorded (time, version, thread, stack trace), or null when there is none. */
    fun lastCrashReport(): String? = null

    /** Forgets the recorded crash, after the user has seen or shared it. */
    fun clearCrashReport() {}
}

/** A file the user picked to open: its name and its bytes. */
class OpenedFile(val name: String, val bytes: ByteArray)

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
    /** Cartridge runs here (Android, and Linux next to it); off on Windows and macOS. */
    val cartridge: Boolean = true,
)

interface Haptics {
    fun tick()
    fun confirm()
    fun reject()

    /** An item lifted by touch, to be moved. */
    fun lift() = confirm()

    /** A moved item taking a new place among the others while held. */
    fun slot() = tick()

    /** A moved item put down. */
    fun drop() = tick()

    object None : Haptics {
        override fun tick() = Unit
        override fun confirm() = Unit
        override fun reject() = Unit
    }
}

/**
 * Screenshots and recordings of what Fuse shows, saved where the system keeps pictures and videos.
 * Only Fuse's own screen is captured, never another app.
 */
interface ScreenCapture {
    /** Where screenshots go, for people ("Pictures/Fuse"). */
    val picturesPlace: String

    /** Where recordings go, for people ("Movies/Fuse"). */
    val videosPlace: String

    /** Saves what Fuse shows right now as a picture called [name]. */
    suspend fun screenshot(name: String): CaptureResult

    /**
     * Gets what a recording needs: the system's permission to record the screen (asked every time),
     * and with [withSound] the permission to capture Fuse's own sound.
     */
    suspend fun prepareRecording(withSound: Boolean): RecordingReady

    /** Starts recording into [name] after [prepareRecording] said it may. */
    fun startRecording(name: String)

    /** Stops and saves the recording; null when none was running. */
    suspend fun stopRecording(): CaptureResult?

    /** Recordings stopped by something else (the notification, the system), already saved. */
    val stoppedElsewhere: Flow<CaptureResult>

    /** Fuse left the screen (a game started, Home was pressed); only Fuse is ever captured. */
    val leftFuse: Flow<Unit>

    /** Free space where captures are saved, in bytes; null when unknown. */
    fun freeBytes(): Long? = null
}

enum class RecordingReady {
    READY,

    /** It may record, but without sound: the permission for sound was refused. */
    READY_SILENT,

    /** The user said no to recording the screen. */
    REFUSED,

    /** Recording can't start here (no encoder, already in use). */
    UNAVAILABLE,
}

/** A saved capture. */
data class CaptureResult(
    /** Where it was saved, for people ("Pictures/Fuse"). */
    val place: String,
    val preview: ImageBitmap? = null,
    val video: Boolean = false,
    /** Why nothing was saved; null when it was. */
    val failure: String? = null,
)

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

    /** The second screen's brightness (Fuse's window there), or null where Fuse can't set it. */
    val secondBrightness: StateFlow<Float?> get() = NO_VALUE
    fun setSecondBrightness(value: Float) = Unit

    /**
     * True when [setBrightness] changes the screen itself (Android's "Modify system settings" was
     * allowed), so it holds in games too; false when it only brightens Fuse's own window.
     */
    val systemBrightness: StateFlow<Boolean> get() = NOT_ALLOWED

    /** Asks to change the screen's brightness itself (opens the system page that allows it), where that exists. */
    val canAskSystemBrightness: Boolean get() = false
    fun askSystemBrightness() = Unit
}

private val NO_VALUE: StateFlow<Float?> = kotlinx.coroutines.flow.MutableStateFlow(null)
private val NOT_ALLOWED: StateFlow<Boolean> = kotlinx.coroutines.flow.MutableStateFlow(false)

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
 * Everything the menu music should be right now: the file to loop ([song], null for silence), how
 * loud (0..1) and whether it may be heard ([playing] false while a game starts or runs).
 */
data class MusicState(val song: String?, val volume: Float, val playing: Boolean)

/**
 * Loops one song under Fuse's menus. Fuse says what it wants as a whole ([apply]); the platform also
 * keeps it quiet while Fuse is in the background, and fades in and out.
 *
 * [apply] is idempotent and self-healing: the player compares the state with what it is really
 * doing (a song open and healthy, the volume set, playing or not) and repairs any difference. The
 * same state applied twice after a player error starts the song again, so nothing ever has to be
 * switched off and on to bring the music back. Volume changes reach the song that is playing at once.
 */
interface MenuMusicPlayer {
    fun apply(state: MusicState)
}
