package io.github.matiyaaa.fuse.sync.syncthing

import io.github.matiyaaa.fuse.sync.SaveKind
import io.github.matiyaaa.fuse.sync.SaveQuery
import kotlinx.coroutines.flow.StateFlow

/**
 * Syncthing, for people who already run it: Fuse shares the save folders of the emulators they
 * use through their own Syncthing, keeps them in step around each game, and asks when Syncthing
 * kept two versions of a save. Folder-aware, not game-aware: saves match between devices only
 * where the files are named the same, which is why Fuse Sync is the one Fuse recommends.
 */
interface SyncthingService {
    val state: StateFlow<SyncthingState>
    val devices: StateFlow<List<SyncthingDevice>>
    val pendingDevices: StateFlow<List<SyncthingPendingDevice>>
    val folders: StateFlow<List<SyncthingFolder>>

    /** How to get Syncthing here, when it isn't found. */
    val install: SyncthingInstall

    /** Where to find one game for each emulator and system in the library, for planning folders another device offers. */
    fun useLibrary(samples: suspend () -> List<SaveQuery>)

    /** Turns Fuse's use of Syncthing on or off (Syncthing itself keeps running either way). */
    suspend fun setEnabled(enabled: Boolean)

    /** Looks for Syncthing on this device and connects when it can (its own key, on a computer). */
    suspend fun find(): SyncthingState

    /** Connects to Syncthing at [address] with its API [apiKey], kept in the secret store. */
    suspend fun connect(address: String, apiKey: String): Result<SyncthingState>

    /** Starts the Syncthing app where Fuse can (Android). True when asked. */
    fun startApp(): Boolean

    /** Adds the device with [id] (Syncthing's device ID), named [name], and shares Fuse's folders with it. */
    suspend fun addDevice(id: String, name: String): Result<Unit>

    suspend fun removeDevice(id: String): Result<Unit>

    /**
     * The save folders of the emulators in [samples] (one game for each emulator and system in the
     * library), as Fuse would share them: one folder each for an emulator's saves, states and
     * memory cards, with an id every device uses for the same folder.
     */
    suspend fun plan(samples: List<SaveQuery>): List<SyncthingPlanFolder>

    /** [plan] for the library given to [useLibrary]. */
    suspend fun planLibrary(): List<SyncthingPlanFolder>

    /** Shares [folders] with every device added, keeping old versions when [keepVersions]. Returns how many were added. */
    suspend fun share(folders: List<SyncthingPlanFolder>, keepVersions: Boolean): Result<Int>

    /** Stops sharing a Fuse folder (the files stay). */
    suspend fun unshare(folderId: String): Result<Unit>

    /** Asks Syncthing again for its devices and folders. */
    suspend fun refresh()

    /**
     * Before [query]'s game starts: the folders holding its saves are looked over and brought up
     * to date (briefly, while another device is connected), then checked for two versions.
     */
    suspend fun beforeLaunch(query: SaveQuery): SyncthingGate

    /** After it closed: its folders are looked over at once, so the new save goes out. */
    suspend fun afterExit(query: SaveQuery)

    /** Settles [conflict]: keeps this device's save ([keepThis]) or the other device's; the other is kept as an old version. */
    suspend fun resolve(conflict: SyncthingConflict, keepThis: Boolean): Result<Unit>

    /** Forgets the connection (the key) and stops. */
    suspend fun disconnect()

    companion object {
        /** Folder ids Fuse makes start with this, so every device knows them as Fuse's. */
        const val PREFIX = "fuse-"

        /** A device ID as Syncthing writes it: eight groups of seven letters and digits 2 to 7. */
        val DEVICE_ID = Regex("^[A-Z2-7]{7}(-[A-Z2-7]{7}){7}$")

        /** A device ID however it was typed or pasted: upper case, grouped. Null when it can't be one. */
        fun normaliseDeviceId(input: String): String? {
            val raw = input.uppercase().filter { it in 'A'..'Z' || it in '2'..'7' }
            if (raw.length != 56) return null
            return raw.chunked(7).joinToString("-")
        }

        /**
         * A save Syncthing kept beside another when both changed: `name.sync-conflict-YYYYMMDD-HHMMSS-DEVICE.ext`.
         * Returns the original name and the short device id, or null for any other file.
         */
        fun conflictOf(fileName: String): Pair<String, String?>? {
            val m = CONFLICT.find(fileName) ?: return null
            return fileName.removeRange(m.range) to m.groupValues[1].ifEmpty { null }
        }

        private val CONFLICT = Regex("""\.sync-conflict-\d{8}-\d{6}(?:-([A-Z2-7]{7}))?""")
    }
}

/** Where things stand with Syncthing here. */
sealed interface SyncthingState {
    data object Off : SyncthingState

    data object Looking : SyncthingState

    /** Not found running here; [installed] when the app is there but not answering (Android). */
    data class NotFound(val installed: Boolean) : SyncthingState

    /** It answers at [address], but Fuse needs its API key ([refused] when the one given was wrong). */
    data class NeedsKey(val address: String, val refused: Boolean = false) : SyncthingState

    data class Connected(val address: String, val version: String, val deviceId: String, val deviceName: String) : SyncthingState

    /** Was connected, and isn't answering now. */
    data class Unreachable(val address: String, val reason: String) : SyncthingState
}

/** How to get Syncthing on this device. */
data class SyncthingInstall(val name: String, val url: String, val note: String, val canStart: Boolean)

data class SyncthingDevice(
    val id: String,
    val name: String,
    val connected: Boolean,
    val address: String?,
    val paused: Boolean = false,
    /** This device itself. */
    val self: Boolean = false,
)

/** A device that asked to connect, not added yet. */
data class SyncthingPendingDevice(val id: String, val name: String, val address: String?)

data class SyncthingFolder(
    val id: String,
    val label: String,
    val path: String,
    /** Syncthing's own word: idle, scanning, syncing, error... */
    val state: String,
    val needBytes: Long,
    val globalBytes: Long,
    val devices: List<String>,
    val keepsVersions: Boolean,
    val error: String? = null,
    val paused: Boolean = false,
) {
    /** One of the folders Fuse shared. */
    val isFuses: Boolean get() = id.startsWith(SyncthingService.PREFIX)
    val upToDate: Boolean get() = needBytes == 0L && state == "idle"
}

/** A save folder Fuse would share: an emulator's saves, states or memory cards here. */
data class SyncthingPlanFolder(
    val id: String,
    val label: String,
    val path: String,
    val emulator: String,
    val kind: SaveKind,
    /** Already shared through Syncthing (by Fuse or by hand). */
    val shared: Boolean = false,
    /** Why it can't be shared, when it can't (beside the games themselves, or in another app's private storage). */
    val blocked: String? = null,
)

/** What to do before a game starts. */
sealed interface SyncthingGate {
    /** Go ahead; [note] when something is worth saying (Syncthing not running, still catching up). */
    data class Go(val note: String? = null) : SyncthingGate

    /** Syncthing kept two versions of this game's save: ask which. */
    data class Conflict(val conflicts: List<SyncthingConflict>) : SyncthingGate
}

/** Two versions of one save file: this device's at [path], the other device's at [conflictPath]. */
data class SyncthingConflict(
    val path: String,
    val conflictPath: String,
    val name: String,
    /** The other device's name, when Syncthing knows it. */
    val device: String?,
    val thisModified: Long,
    val otherModified: Long,
)

/** What only the device can do for Syncthing: say how to get it, find its app, and start it. */
interface SyncthingPlatform {
    /** LINUX, WINDOWS, MACOS or ANDROID. */
    val host: String

    /** How to get Syncthing here. */
    val install: SyncthingInstall

    /** The Syncthing app installed here, by package (Android); null when none or not knowable. */
    fun installedApp(): String? = null

    /** Starts the Syncthing app (Android); true when asked. */
    fun startApp(): Boolean = false
}
