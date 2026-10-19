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

    /** Steam on a computer: Fuse in Steam's library, for Game Mode. Null where there is no Steam (Android). */
    val steam: SteamIntegration? get() = null

    /** Decky Loader and the Fuse Decky plugin, where they can run (Linux); null elsewhere. */
    val decky: DeckyIntegration? get() = null

    /** Reads game manuals (PDF and CBZ) on this device; null where it can't. */
    val manuals: ManualReader? get() = null

    /**
     * Recent second-screen events (companion started or closed and why, refused displays, display
     * changes), oldest first, for a status row in Settings. Empty where there is no companion screen.
     */
    val secondScreenLog: StateFlow<List<String>> get() = NoSecondScreenLog

    /** What Fuse itself does to the battery and the heat now, for System health's Power and heat. */
    val power: StateFlow<PowerFacts> get() = NoPowerFacts

    fun openUrl(url: String)
    fun restart()

    /**
     * What the system lets Fuse do while it isn't on screen (Android's battery optimization and
     * background restriction); null where nothing limits it. Read on every access: it changes in
     * the system's settings.
     */
    val background: BackgroundLimits? get() = null

    /** Opens the system's own screen where [background]'s limits are lifted. */
    fun openBackgroundSettings() = Unit

    /**
     * Keeps the device from sleeping while Fuse works in the background ([StayAwake.cpu]: art and
     * details being found, downloads, uploads), and the screen on while the standby screen shows over
     * that work ([StayAwake.screen]), so leaving Fuse alone never stops it. [StayAwake.NONE] lets go.
     */
    fun stayAwake(awake: StayAwake) = Unit

    /**
     * How Fuse is being drawn ("OpenGL", "Metal", "Without the graphics card"), or null where it
     * isn't known. Drawing without the graphics card (a computer whose driver Fuse can't use) makes
     * every moving frame costly, so Fuse keeps its motion light there.
     */
    val drawing: kotlinx.coroutines.flow.StateFlow<DrawingInfo?> get() = NoDrawingInfo

    /**
     * Erase Fuse: everything Fuse keeps (its library, settings, profiles, art, caches and Fuse Sync)
     * goes, and Fuse starts again as new. Game files, emulators and the saves in the emulators'
     * folders are never touched. False where it can't be done.
     */
    fun eraseAndRestart(): Boolean = false

    /** Leaves Fuse. Not offered while Fuse is the Home app (Home has nowhere to exit to). */
    fun exit()

    /**
     * The game Fuse was started to play (`--play <id>`, from a game's entry in Steam), or null. Fuse
     * starts it as soon as it is ready and closes again once it ends.
     */
    val startupPlay: Long? get() = null

    /**
     * Games another start of Fuse handed to this one to play (Fast Start from a Steam entry while
     * Fuse is open), each with [PlayRequest.ended] to call once the game has ended and its saves are
     * kept: the start that handed it over waits for that, so Steam sees the game for as long as it runs.
     */
    val playRequests: kotlinx.coroutines.flow.Flow<PlayRequest> get() = kotlinx.coroutines.flow.emptyFlow()

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

/**
 * Fuse and Steam on a computer. On a Steam Deck (or any SteamOS or gamescope setup) Game Mode only
 * starts what is in Steam's library, so Fuse can put itself there as a non-Steam game, set to open
 * full screen. Steam reads its list when it starts, so it must be closed while Fuse writes it.
 */
interface SteamIntegration {
    /** Fuse runs inside SteamOS's Game Mode (or another gamescope session) right now. */
    val gameMode: Boolean

    /** Whether a Steam user here has Fuse in their library already. */
    suspend fun added(): Boolean

    /** Steam is installed on this device (a Steam folder was found), so there is a library to join. */
    suspend fun installed(): Boolean = true

    /** Someone signed in to Steam here at least once, so Steam has a library Fuse can be added to. */
    suspend fun hasUser(): Boolean = true

    /** Fuse runs from a program Steam could start (its AppImage or installed copy), not a development run. */
    val canBeAdded: Boolean get() = true

    /** Adds Fuse (or brings its entry up to date) for every Steam user here; what happened, in words. */
    suspend fun addFuse(): Result<String>

    /** Safe setup lifecycle: close Steam cleanly, update the shortcut, then restart it. */
    suspend fun addForSetup(): Result<String> = addFuse()

    /** Repairs only positively identified Fuse entries; existing custom artwork is retained. */
    suspend fun rebuild(): Result<String> = addFuse()

    /** Steam is open right now (its library can't be written safely until it closes). */
    suspend fun steamRunning(): Boolean = false

    /** Steam is running a game right now: nothing may close Steam. */
    suspend fun gameRunning(): Boolean = false

    /**
     * Adds Fuse the next time Steam closes, for people who don't want Fuse to close it: Fuse looks
     * while it runs and writes the entry once Steam is gone, without starting Steam again.
     */
    suspend fun addWhenSteamCloses(): Result<String> = Result.failure(UnsupportedOperationException("Not on this system"))

    /** A queued [addWhenSteamCloses] still waiting for Steam to close. */
    val waitingForSteam: Boolean get() = false

    /**
     * Closing Steam closes Fuse too (Game Mode, or Fuse started from its Steam entry). Restarting
     * Steam then hands the change to a helper that opens Fuse again afterwards, so say so first.
     */
    val closesFuse: Boolean get() = false

    /** Takes Fuse's own entry (and only Fuse's) out of Steam's library; its art stays for the person to delete. */
    suspend fun removeFuse(): Result<String> = Result.failure(UnsupportedOperationException("Not on this system"))
    /** The games Fuse put in Steam's library here, by Fuse's game id, for any Steam user. */
    suspend fun gamesInSteam(): Set<Long> = emptySet()

    /** Fuse's Direct entries in Steam here: each entry's id in Steam to its game, for counting their time. */
    suspend fun directGames(): Map<Long, Long> = emptyMap()

    /** How each of Fuse's games in Steam here starts: Fast Start, an older entry through Fuse's window, or Direct. */
    suspend fun gameStarts(): Map<Long, io.github.matiyaaa.fuse.library.steam.SteamStart> = emptyMap()

    /**
     * Steam here can have Direct entries, which start a game's emulator themselves with Fuse's mark
     * kept in their launch options (Linux, where Steam reads them through the shell). Elsewhere every
     * entry starts with Fast Start.
     */
    val directEntries: Boolean get() = false

    /**
     * Changes to Steam's library that the Fuse Decky plugin makes through Steam's running client
     * (bridge protocol 2), so Steam needn't close or restart; null where there is no plugin to do it.
     */
    val live: SteamLiveQueue? get() = null

    /** The plugin is there right now, so a change goes into Steam at once with Steam open. */
    val changesLive: Boolean get() = live?.connected == true

    /**
     * What putting [games] in Steam's library would do, game by game, over this computer's Steam
     * users, before anything is written. Null where Steam's library can't be read.
     */
    suspend fun planGames(games: List<io.github.matiyaaa.fuse.library.steam.SteamGameEntry>): List<io.github.matiyaaa.fuse.library.steam.SteamGamePlanItem>? = null

    /**
     * Puts [games] in Steam's library for every Steam user here, each starting with Fast Start or,
     * where it carries a [io.github.matiyaaa.fuse.library.steam.SteamDirect] and [directEntries] allows,
     * straight in its emulator, with its pictures from [art] and, with [collections], in a Steam
     * collection for its system. Steam is never closed for it: while it is open, the games go in when
     * it next closes.
     */
    suspend fun addGames(
        games: List<io.github.matiyaaa.fuse.library.steam.SteamGameEntry>,
        art: Map<Long, io.github.matiyaaa.fuse.library.steam.SteamArt>,
        collections: Boolean,
    ): Result<String> = Result.failure(UnsupportedOperationException("Not on this system"))

    /**
     * Like [addGames], but restarts Steam to put them in now rather than waiting for it to close:
     * never while a game runs. With [closesFuse], Fuse closes with Steam and opens again after.
     */
    suspend fun addGamesRestartingSteam(
        games: List<io.github.matiyaaa.fuse.library.steam.SteamGameEntry>,
        art: Map<Long, io.github.matiyaaa.fuse.library.steam.SteamArt>,
        collections: Boolean,
    ): Result<String> = addGames(games, art, collections)

    /** Games waiting for Steam to close before they go into its library. */
    val waitingForSteamGames: Boolean get() = false

    /** Takes Fuse's entries for [gameIds] (null: all of Fuse's games) out of Steam's library; nobody else's. */
    suspend fun removeGames(gameIds: Set<Long>?): Result<String> = Result.failure(UnsupportedOperationException("Not on this system"))

    /**
     * What is wrong with Fuse's game entries: games no longer in [library], an older Fuse, doubles,
     * older entries that open Fuse's window first, and Direct entries whose emulator is gone.
     */
    suspend fun gamesHealth(
        library: Set<Long>,
        wanted: Map<Long, io.github.matiyaaa.fuse.library.steam.SteamGameEntry> = emptyMap(),
        unavailable: Set<Long> = emptySet(),
    ): List<io.github.matiyaaa.fuse.library.steam.SteamHealthIssue> = emptyList()

    /**
     * Puts Fuse's game entries right: those for games no longer in [library] and doubles go, and
     * [moved] (entries that start an older Fuse, an older way or a missing emulator) are written again
     * as each game starts now, keeping what the person changed.
     */
    suspend fun repairGames(
        library: Set<Long>,
        moved: List<io.github.matiyaaa.fuse.library.steam.SteamGameEntry>,
        art: Map<Long, io.github.matiyaaa.fuse.library.steam.SteamArt>,
    ): Result<String> = Result.failure(UnsupportedOperationException("Not on this system"))

    /**
     * Shortcuts other tools (Steam ROM Manager, EmuDeck, a script, the person) made here that play
     * one of Fuse's games, found by the game's own file in their command ([files], each to its game).
     */
    suspend fun learnShortcuts(files: Map<String, Long>): List<io.github.matiyaaa.fuse.library.steam.SteamLearned> = emptyList()

    /**
     * How the person's own shortcuts here start each system's games
     * ([io.github.matiyaaa.fuse.library.steam.SteamLearn.habits]), learned from those that play one
     * of Fuse's games ([files], each to its game; [systems], each game's system).
     */
    suspend fun habits(files: Map<String, Long>, systems: Map<Long, String>): List<io.github.matiyaaa.fuse.library.steam.SteamHabit> = emptyList()

    /**
     * Starts Fuse's entry for [gameId] through Steam itself, as if it were chosen in Steam's library
     * (with Steam's overlay and the controller layout the person gave that entry), through the Fuse
     * Decky plugin. Fails where the plugin isn't there or the game has no entry.
     */
    suspend fun playFromSteam(gameId: Long): Result<String> = Result.failure(UnsupportedOperationException("Not on this system"))

    /** The person's own collections in Steam (not Fuse's), for matching them to Fuse's. */
    suspend fun theirCollections(): List<io.github.matiyaaa.fuse.library.steam.SteamCollection> = emptyList()

    /** What the person chose for their collections beside Fuse's. */
    suspend fun collectionChoices(): io.github.matiyaaa.fuse.library.steam.SteamCollectionChoices = io.github.matiyaaa.fuse.library.steam.SteamCollectionChoices()

    /**
     * How Fuse's collection [fuseName] and the person's [theirs] (by id) live together: apart, Fuse's
     * games put into theirs, or theirs renamed [fuseName] with Fuse's games in it. Theirs never goes.
     */
    suspend fun chooseCollection(fuseName: String, theirs: String, how: io.github.matiyaaa.fuse.library.steam.SteamCollectionWay): Result<String> = Result.failure(UnsupportedOperationException("Not on this system"))

    /**
     * Runs while Fuse does: each time Steam closes, Fuse's collections Steam Cloud dropped meanwhile
     * are put back ([onPutBack] gets how many), as written before; ones the person deleted stay gone.
     */
    suspend fun keepCollections(onPutBack: (Int) -> Unit) = Unit

    /** How each of [games]'s entries starts once written: what Steam runs, its start folder and its launch options. */
    suspend fun entryStarts(games: List<io.github.matiyaaa.fuse.library.steam.SteamGameEntry>): Map<Long, Triple<String, String, String>> = emptyMap()

    /**
     * Makes each of [takes] (a shortcut [learnShortcuts] found, and Fuse's entry for its game) Fuse's,
     * in its place, keeping its name, art and collections. Steam must be closed; Undo puts it back.
     */
    suspend fun takeOver(
        takes: List<Pair<io.github.matiyaaa.fuse.library.steam.SteamLearned, io.github.matiyaaa.fuse.library.steam.SteamGameEntry>>,
    ): Result<String> = Result.failure(UnsupportedOperationException("Not on this system"))

    /** Fuse's last change to Steam's games can be undone. */
    val canUndoGames: Boolean get() = false

    /** Puts Steam's library back as it was before Fuse's last change to its games. */
    suspend fun undoGames(): Result<String> = Result.failure(UnsupportedOperationException("Not on this system"))

    /** Fuse's recent changes to Steam's games that Undo can take back, newest first. */
    suspend fun undoSteps(): List<SteamUndoStep> = emptyList()

    /** Puts Steam's library back as it was before change [stepId]; every change after it is undone too. */
    suspend fun undoTo(stepId: Long): Result<String> = Result.failure(UnsupportedOperationException("Not on this system"))
}

/** One of Fuse's changes to Steam's games, made at [at], that Undo can take back: [what] it did, in words. */
data class SteamUndoStep(val id: Long, val what: String, val at: Long)

/** What Decky Loader and the Fuse Decky plugin look like on this device. */
data class DeckyState(
    /** Decky Loader is installed. */
    val loader: Boolean,
    /** The Fuse Decky plugin is installed, at [pluginVersion] when it says. */
    val plugin: Boolean,
    val pluginVersion: String? = null,
    /** The plugin this Fuse carries. */
    val bundledVersion: String,
    /** The system can ask for the person's password to put the plugin in place (pkexec). */
    val canInstall: Boolean,
    /** The plugin adds Fuse's entries to Steam's game menu (off once the person turns them off). */
    val menuEntries: Boolean = true,
) {
    val upToDate: Boolean get() = plugin && pluginVersion == bundledVersion
}

/**
 * The Fuse Decky plugin's installation. Decky keeps its plugins in a folder only the system
 * account can write, so installing always goes through a password prompt the person answers
 * themselves; Fuse never runs a privileged command silently. Where that isn't possible (no prompt
 * can show, no password set), [stage] gives the plugin as a ZIP for Decky's own Install from ZIP.
 */
interface DeckyIntegration {
    suspend fun state(): DeckyState

    /** Installs or updates the plugin and reloads Decky so it loads; what happened, in words. */
    suspend fun install(): Result<String>

    /** Takes the plugin out of Decky. Nothing of Fuse's (its library, play time, saves) changes. */
    suspend fun remove(): Result<String>

    /**
     * Lets the plugin add Fuse's entries to Steam's game menu, or keeps it from patching that menu
     * at all, from the next time Decky loads it. False when it couldn't be changed.
     */
    suspend fun setMenuEntries(on: Boolean): Boolean = false

    /** The plugin as a ZIP in Fuse's own folder, for Decky's Install from ZIP; null when it can't be made. */
    suspend fun stage(): String?

    /** Where Decky Loader's own installer and instructions are, for a device without it. */
    val loaderInstructions: String get() = "https://decky.xyz"
}

/** A file the user picked to open: its name and its bytes. */
class OpenedFile(val name: String, val bytes: ByteArray)

private val NoSecondScreenLog: StateFlow<List<String>> = MutableStateFlow(emptyList())

/**
 * What Fuse does to the battery and the heat right now, as far as the platform can say: what it is
 * keeping the device awake for ([holding], empty when it lets the device sleep), the screen's rate
 * as Fuse asks for it ([refresh]), and whether its rounds on the network wait while the device
 * sleeps ([pausesAsleep]).
 */
data class PowerFacts(
    val holding: Set<PowerHold> = emptySet(),
    val refresh: RefreshFact? = null,
    val pausesAsleep: Boolean = false,
)

/** Why Fuse keeps the device awake. */
enum class PowerHold { TRANSFERS, ART }

/** The rate Fuse asks of the screen: [FULL] while things move for you, [CALM] (about 60 Hz) while only the background does, [RESTING] when it asks nothing. */
data class RefreshFact(val state: State, val fullHz: Float?, val calmHz: Float?) {
    enum class State { FULL, CALM, RESTING }
}

private val NoPowerFacts: StateFlow<PowerFacts> = MutableStateFlow(PowerFacts())

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
    /** The screen turns with the device, and Fuse can choose how (Android). */
    val rotation: Boolean = false,
    /** Games can have frame generation through lsfg-vk here (Linux), once it is set up. */
    val frameGeneration: Boolean = false,
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

    /** Something to know arrived (a notice). */
    fun notice() = tick()

    /** Something finished well: a download, a profile ready, Fuse up and running. */
    fun success() = confirm()

    /** How strong, 0 to 1 (Settings, Input, Vibration); 0 is off. */
    fun setStrength(level: Float) = Unit

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

    /**
     * Opens the folder picker for an emulator's saves. On Android it also takes folders another app
     * shares through the picker (Eden's own data folder, a card): those come back as a `tree:` link
     * Fuse Sync reads and writes through the system, with the access kept. Elsewhere, [pickFolder].
     */
    suspend fun pickSaveFolder(title: String): String? = pickFolder(title)

    /** Opens the platform image picker; returns a readable path (copied into Fuse's storage if needed), or null. */
    suspend fun pickImage(title: String): String?

    /** Opens a picker for an audio file and copies it into Fuse's storage; returns that copy, or null. */
    suspend fun pickAudio(title: String): PickedFile? = null
    /** A save file or archive, copied into temporary app storage where document access needs it. */
    suspend fun pickSave(title: String): String? = null
    /** An IPS, UPS, BPS or xdelta patch, readable by Fuse (copied into temporary app storage where document access needs it). */
    suspend fun pickPatch(title: String): String? = null
    /** A file of one of [extensions] (described as [label]), read where it is; null where Fuse can't open files that way. */
    suspend fun pickFile(title: String, label: String, extensions: List<String>): String? = null
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
 * Everything the menu music should be right now: the file to play ([song], null for silence), how
 * loud (0..1) and whether it may be heard ([playing] false while a game starts or runs, or at volume
 * 0). [loop] false plays the song once and then reports it through [MenuMusicPlayer.onSongEnded], so
 * shuffle can pick the next one.
 */
data class MusicState(val song: String?, val volume: Float, val playing: Boolean, val loop: Boolean = true)

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

    /**
     * Called with a song's path when it played to its end without looping ([MusicState.loop] false).
     * May be called on any thread.
     */
    fun onSongEnded(listener: ((String) -> Unit)?) = Unit
}

/**
 * How the window is drawn: [name] for people, whether the graphics card does it, and [details]
 * (label and value: the driver, the display session, the window against the screen, and when on
 * the processor, why), for Settings and the diagnostics report.
 */
data class DrawingInfo(val name: String, val gpu: Boolean, val details: List<Pair<String, String>> = emptyList())

private val NoDrawingInfo: kotlinx.coroutines.flow.StateFlow<DrawingInfo?> = kotlinx.coroutines.flow.MutableStateFlow(null)

/** What Fuse asks of the system's sleep while it works: see [PlatformUi.stayAwake]. */
data class StayAwake(val cpu: Boolean, val screen: Boolean, val finding: Boolean = false) {
    companion object {
        val NONE = StayAwake(cpu = false, screen = false)

        /** How long after the work ends the device is let go. */
        const val RELEASE_AFTER_MS = 5_000L

        /**
         * Awake while anything moves or waits its turn ([transfers]) or art and details are being
         * found ([finding]). The screen stays on only under the standby screen, which is made to be
         * left on: anywhere else the system may turn the screen off and the work carries on.
         */
        fun plan(transfers: Int, finding: Boolean, standby: Boolean): StayAwake {
            // [transfers] counts what moves: one waiting its turn starts within moments of a place,
            // and one that can't start (its source signed out) held the locks for as long as it stayed.
            val working = transfers > 0 || finding
            return StayAwake(cpu = working, screen = working && standby, finding = finding)
        }

        /**
         * Whether art is being found: a fill still going. One that finished stays shown (what it
         * added, what needs a choice) and doesn't count. Counted while shown, the locks were held for
         * good after the first fill following a scan: the processor never slept and Wi-Fi never
         * saved power, and a handheld ran hot and drained.
         */
        fun finding(fill: io.github.matiyaaa.fuse.ui.shell.store.FillProgress?, systemArt: io.github.matiyaaa.fuse.ui.shell.store.FillProgress?): Boolean =
            fill?.finished == false || systemArt?.finished == false
    }
}

/**
 * What the system does to Fuse in the background: [optimized] when battery optimization may stop it
 * soon after it leaves the screen (Android's Doze and app standby), [restricted] when the person or
 * the system restricted its background use outright. Either can hold a save back until Fuse is open
 * again; nothing is lost, it just goes later.
 */
data class BackgroundLimits(val optimized: Boolean, val restricted: Boolean) {
    val limited: Boolean get() = optimized || restricted
}

/** A game handed over to play here: [ended] once it has ended and everything after it is done (or it couldn't start). */
class PlayRequest(val gameId: Long, val ended: () -> Unit)
