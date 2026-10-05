package io.github.matiyaaa.fuse.sync

import kotlinx.coroutines.flow.StateFlow

/** Where Fuse Sync stands on this device, for the status line, the Sync tab and Settings. */
sealed interface SyncStatus {
    /** Fuse Sync is off (or was never set up): no profiles, no Sync tab, nothing in the background. */
    data object Off : SyncStatus

    /** Turned on, waiting to be set up (host or connect). */
    data object NotSetUp : SyncStatus

    /** Reaching the host. */
    data class Connecting(val hostName: String) : SyncStatus

    /** In touch with the host, at home ([route] LOCAL) or from outside (REMOTE); [working] while sending or fetching. */
    data class Online(val hostName: String, val route: Route, val working: Boolean = false, val pending: Int = 0) : SyncStatus

    /** The host isn't answering: everything works here, and [pending] changes wait to go. */
    data class Offline(val hostName: String, val pending: Int, val since: Long) : SyncStatus

    /** Something needs the person: [reason] says what (this device was unlinked, a PIN changed...). */
    data class NeedsAttention(val hostName: String, val reason: String, val code: String) : SyncStatus
}

/** A host on this network, found by asking. */
data class NearbyHost(val name: String, val hostId: String, val address: String)

/** One thing Fuse Sync did, for the Sync tab's recent activity. */
data class SyncActivity(val at: Long, val text: String, val game: String? = null, val kind: String = "")

/** This device as a host: whether it serves, who is connected, and a pairing code while one is shown. */
data class HostView(
    val name: String,
    val running: Boolean,
    val port: Int,
    val addresses: List<String>,
    val pairingCode: String?,
    val status: HostStatus?,
    /** Whether it starts with the computer, without Fuse open (see [HostLifetime]). */
    val service: ServiceState,
)

/** How a host keeps running when Fuse is closed, and after a restart. */
data class ServiceState(
    val installed: Boolean,
    val running: Boolean,
    /** What it is, in a few words ("Starts with this computer, before anyone signs in"). */
    val description: String,
    /** Anything the person must know: needs an administrator, battery limits on Android... */
    val caveat: String? = null,
    val supported: Boolean = true,
)

/** What happened before a game started, for the interface. */
sealed interface LaunchGate {
    /** Go ahead (with the newest save in place, or nothing to sync). [note] says anything worth a toast. */
    data class Go(val note: String? = null) : LaunchGate

    /** Both played since they last agreed: ask the person, with what each side has. */
    data class Conflict(val conflict: SaveConflict) : LaunchGate
}

/** A save conflict, as the person sees it. */
data class SaveConflict(
    val game: GameKey,
    val title: String,
    val kind: SaveKind,
    val here: SaveSide,
    val host: SaveSide,
    internal val local: SaveRevision,
    internal val remote: SaveRevision,
    internal val query: SaveQuery,
) {
    companion object {
        /** A conflict to show without a host behind it (previews and the interface's own audit). */
        fun forPreview(game: GameKey, title: String, kind: SaveKind, here: SaveSide, host: SaveSide): SaveConflict {
            fun rev(side: SaveSide) = SaveRevision(
                id = side.device, profile = "", game = game.id, kind = kind, parent = null, device = side.device, deviceName = side.device,
                at = Hlc(side.at, 0, side.device), manifest = SaveManifest("", emptyList()), playSeconds = side.playSeconds,
            )
            return SaveConflict(game, title, kind, here, host, rev(here), rev(host), SaveQuery(game, game.platform, "", ""))
        }
    }
}

/** One side of a conflict: where it was saved, when, how long it had been played, and how big it is. */
data class SaveSide(val device: String, val at: Long, val playSeconds: Long, val bytes: Long, val files: Int)

/** A version of a save, for the history screen. */
data class SaveVersion(
    val id: String,
    val device: String,
    val at: Long,
    val playSeconds: Long,
    val bytes: Long,
    val reason: RevisionReason,
    val current: Boolean,
)

/**
 * The person's data on this device, as Fuse Sync reads and writes it: their records for every
 * game Fuse has here (by [GameKey]), their collections and their settings. The app implements it
 * over its library and settings; switching profiles is reading one person's out and writing the
 * next one's in. Nothing about the library itself (which games are here, their files) is a
 * person's, so switching never adds or removes a game.
 */
interface ProfileDataPort {
    /** What is here now, as records: play time as this device counted it, favourites, sessions... */
    suspend fun read(device: String, clock: HlcClock): ProfileMeta

    /** Puts [meta] in place: play time, Last Played, favourites, hidden, pinned, collections, settings. */
    suspend fun write(meta: ProfileMeta)

    /** The game a launch is for, as Fuse Sync knows games. */
    suspend fun keyOf(gameId: Long): GameKey?
}

/**
 * Fuse Sync by Fuse on this device: setting it up (host or connect), its profiles, and the work
 * around a game (the save in place before, captured after). Everything degrades to working alone:
 * when the host can't be reached, nothing waits on it and nothing is lost.
 */
interface SyncService {
    val status: StateFlow<SyncStatus>
    val profiles: StateFlow<List<ProfileInfo>>

    /** The profile in use here, or null (Fuse Sync off, or no one chosen yet). */
    val activeProfile: StateFlow<ProfileInfo?>
    val activity: StateFlow<List<SyncActivity>>
    val host: StateFlow<HostView?>
    val devices: StateFlow<List<DeviceInfo>>

    /** What this platform can do: host for real (a computer), or only connect (Android). */
    val canHost: Boolean

    /** This device's own name (the system's), until the person names it for Fuse Sync. */
    val defaultName: String

    /** How a host here would keep running without Fuse (before this device is one), for setting up. */
    fun lifetimeState(): ServiceState

    /**
     * Turns Fuse Sync on or off here. Off, nothing runs and nothing shows; what is on this device
     * stays exactly as it is (the profile in use simply stops syncing), and on again picks up.
     */
    suspend fun setEnabled(enabled: Boolean)

    /**
     * Stops this device being the host: its server and its background service. Its data stays on
     * disk (nothing is deleted), so hosting again here carries on where it was.
     */
    suspend fun stopHosting(): Result<Unit>

    suspend fun discover(): List<NearbyHost>

    /** Connects to the host at [address] with the [code] it shows. */
    suspend fun connect(address: String, code: String, remoteAddress: String? = null): Result<String>

    /**
     * Makes this device the host: its store, its server, its answer to discovery, and (when asked)
     * the service that keeps it running without Fuse. This device also connects to itself.
     */
    suspend fun hostHere(name: String, installService: Boolean): Result<HostView>

    suspend fun installService(): Result<ServiceState>
    suspend fun removeService(): Result<ServiceState>

    /** A code to add a device (on the host only). */
    suspend fun newPairingCode(): String?

    suspend fun createProfile(name: String, avatar: String, pin: String?): Result<ProfileInfo>
    suspend fun changeProfile(id: String, change: ProfileChange): Result<ProfileInfo>
    suspend fun deleteProfile(id: String): Result<Unit>

    /** Opens [id] with its [pin] when it has one, without switching to it. */
    suspend fun openProfile(id: String, pin: String?): Result<Unit>

    /**
     * Switches this device to [id]: the profile in use is kept (sent when it can be), then the next
     * one's records and settings come into place. Null leaves profiles (the device's own state stays).
     */
    suspend fun switchTo(id: String?, pin: String? = null): Result<Unit>

    suspend fun syncNow(): Result<Unit>

    /** Before a game starts: its save brought up to date, or a conflict to ask about. */
    suspend fun beforeLaunch(query: SaveQuery): LaunchGate

    /** The person settled [conflict]: [keepHere] keeps this device's, else the host's comes down. */
    suspend fun settle(conflict: SaveConflict, keepHere: Boolean): Result<Unit>

    /** After a game: its session counted and its save captured, sent when it can be. */
    suspend fun afterExit(query: SaveQuery, startedAt: Long, endedAt: Long)

    /** Everything the host keeps for the profile in use (play time, every save and version), for the Hub; null offline. */
    suspend fun report(): ProfileReport? = null

    suspend fun versions(query: SaveQuery, kind: SaveKind): List<SaveVersion>
    suspend fun restore(query: SaveQuery, kind: SaveKind, version: String): Result<Unit>
    suspend fun keepVersion(version: String, keep: Boolean): Result<Unit>

    /** Records changed here (a favourite, a collection, a setting): they go up soon. */
    fun changed()

    /** Leaves the host: everything here stays exactly as it is; this device just stops syncing. */
    suspend fun unlink(): Result<Unit>

    /** Renames another device (host only) or unlinks it. */
    suspend fun renameDevice(id: String, name: String): Result<Unit>
    suspend fun revokeDevice(id: String): Result<Unit>

    fun stop()
}

/**
 * How a host keeps running without Fuse open, per system: a systemd user service with lingering
 * on Linux, a LaunchAgent on macOS, a task at startup on Windows. Android can't promise this (it
 * stops background work to save battery), so there it says so instead.
 */
interface HostLifetime {
    val supported: Boolean
    fun state(): ServiceState
    fun install(): Result<ServiceState>
    fun remove(): Result<ServiceState>
}

/** A platform that can't keep a host running on its own. */
class NoHostLifetime(private val why: String) : HostLifetime {
    override val supported = false
    override fun state() = ServiceState(installed = false, running = false, description = "Runs only while Fuse is open", caveat = why, supported = false)
    override fun install(): Result<ServiceState> = Result.failure(UnsupportedOperationException(why))
    override fun remove(): Result<ServiceState> = Result.success(state())
}
