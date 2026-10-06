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

/** A request to join on its way: the host asked, and the number this device shows. */
data class JoinWaiting(val hostName: String, val match: String, val account: Boolean = false)

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
    /** Its address from outside home, shared with every device ("" when none). */
    val outside: String = "",
    /** The username of its account, for joining and the Hub from away; null when it has none. */
    val accountName: String? = null,
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

    /**
     * Another device is playing this game, or just stopped and is still sending its save: the
     * person can wait for it or play here with the newest save the host has ([lastSave], when it
     * came from that device).
     */
    data class Busy(val device: String, val title: String, val playing: Boolean, val at: Long, val lastSave: Long?) : LaunchGate
}

/** Something Fuse Sync did that the person would want to know, shown as a quiet toast. */
sealed interface SyncNotice {
    /** A save went up to the host ([live] while still playing). */
    data class Sent(val title: String, val kind: SaveKind, val live: Boolean) : SyncNotice

    /** The newest save could not be used here (another emulator's, or another format), and stays on the host. */
    data class CantUse(val title: String, val kind: SaveKind, val from: String, val why: String) : SyncNotice

    /** After a game, nothing of its save could be kept to send: [why] says why, and what to do. */
    data class NotSynced(val title: String, val kind: SaveKind, val why: String) : SyncNotice

    /** This device's profiles went to [hostName] as they were ([count] of them), joining it. */
    data class Brought(val count: Int, val hostName: String) : SyncNotice
}

/**
 * Joining a host while this device has profiles of its own, and the host has people too: who is
 * who. [suggested] pairs a profile here with one on the host of the same name (case and spaces
 * aside). The host's own profile (Admin) is never among [host].
 */
data class ProfileMerge(
    val hostName: String,
    val here: List<ProfileInfo>,
    val host: List<ProfileInfo>,
    val suggested: Map<String, String>,
)

/** What becomes of one profile of this device's when it joins a host. */
sealed interface MergeChoice {
    /** The same person as [hostProfile] there: records and saves join theirs ([pin] when theirs has one). */
    data class Same(val hostProfile: String, val pin: String? = null) : MergeChoice

    /** Someone new to the host: it goes up as it is, PIN and all. */
    data object Add : MergeChoice

    /** Left out: removed from this device (its saves kept as plain files in Fuse Sync's kept folder). */
    data object LeaveOut : MergeChoice
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
    /** Kept for good by the person. */
    val kept: Boolean = false,
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

    /** Every id each game here may be known by on any device (its serial, its title), most trusted first. */
    suspend fun candidates(): List<List<GameKey>> = emptyList()

    /** The one id the household knows each game by, from any of its ids: records are read and written by it. */
    fun useAliases(aliases: Map<String, String>) {}
}

/**
 * Fuse Sync by Fuse on this device: setting it up (host or connect), its profiles, and the work
 * around a game (the save in place before, captured after). Everything degrades to working alone:
 * when the host can't be reached, nothing waits on it and nothing is lost.
 */
interface SyncService {
    /**
     * How this device plays a game, by the household's id for it, or null when it doesn't have
     * it. Set by the app, so a save made on another device can be put in place here in the
     * background (never while that game is being played).
     */
    fun saveQueries(provider: suspend (gameId: String) -> SaveQuery?) {}

    /** Every device's place with [game]'s saves for the person playing here ("5 of 6 devices current"); null when the host can't say. */
    suspend fun convergence(game: GameKey): Convergence? = null

    /**
     * This device's own choice of whose saves move here: [pullOff] profiles' saves are never
     * brought in (this device still sends theirs), [off] profiles' saves never move either way.
     * Records and play time are unaffected, and so are saves the household plays together.
     */
    fun saveChoices(pullOff: Set<String>, off: Set<String>) {}

    /**
     * The household's RomM and Jellyfin from the host: addresses, and the sign-ins this device may
     * have, opened ("romm", and "jellyfin:<profile>" for the profiles it may open). Null without a
     * host, or from a host before 0.3.6.3.
     */
    suspend fun householdServices(): HouseholdShared? = null

    /** Shares this device's RomM and Jellyfin with the household, or says no ([declined]); false when it can't. */
    suspend fun shareServices(romm: ServiceAddress?, jellyfin: ServiceAddress?, signIns: Map<String, String>, declined: Boolean = false): Boolean = false

    val status: StateFlow<SyncStatus>

    /** Goes up each time a round with the host ends (what another device sent since is in). */
    val rounds: StateFlow<Int> get() = NoRounds

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
     * stays exactly as it is, and on again picks up. With [keepProfiles], the people who played here
     * stay as this device's own profiles (their records and saves with them); otherwise they go too.
     */
    suspend fun setEnabled(enabled: Boolean, keepProfiles: Boolean = true)

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

    /**
     * Asks the host at [address] (else [remoteAddress]) to let this device in without a code.
     * The answer is the number this device shows; someone lets it in on the host, or on any
     * device already connected, after checking theirs shows the same.
     */
    suspend fun askToJoin(address: String, remoteAddress: String? = null): Result<JoinWaiting> = Result.failure(UnsupportedOperationException("Fuse Sync isn't part of this build."))

    /** Waits for the request [askToJoin] made: the host's name once let in, a failure when turned away or out of time. */
    suspend fun awaitJoin(): Result<String> = Result.failure(UnsupportedOperationException("Fuse Sync isn't part of this build."))

    /** Gives up on a request to join. */
    fun cancelJoin() {}

    /** Joins the host asked with [askToJoin] using its account, when nobody is at a screen. The host's name. */
    suspend fun joinWithAccount(username: String, password: String): Result<String> = Result.failure(UnsupportedOperationException("Fuse Sync isn't part of this build."))

    /** On the host: its account for joining and the Hub from away ([password] null keeps the one it has). */
    suspend fun setHostAccount(username: String, password: String?): Result<Unit> = Result.failure(UnsupportedOperationException("This device isn't a host."))

    suspend fun clearHostAccount(): Result<Unit> = Result.failure(UnsupportedOperationException("This device isn't a host."))

    /** On the host: its address from outside home, shared with every device (empty clears it). */
    suspend fun setOutsideAddress(address: String): Result<Unit> = Result.failure(UnsupportedOperationException("This device isn't a host."))

    /** Devices asking to join right now, for this device to let in (on the host, and every device already connected). */
    val joinRequests: StateFlow<List<JoinAsk>> get() = NO_JOIN_REQUESTS

    suspend fun answerJoin(id: String, allow: Boolean): Result<Unit> = Result.failure(UnsupportedOperationException("Fuse Sync isn't set up."))

    /**
     * Profiles work without a host: made here, they are this device's own (PINs kept as a salted
     * hash), and go to a host when this device joins one. With a host, they are the host's.
     */
    suspend fun createProfile(name: String, avatar: String, pin: String?): Result<ProfileInfo>

    /** The profiles in the order of [ids], here and (with a host) on every device. */
    suspend fun reorderProfiles(ids: List<String>): Result<Unit> = Result.failure(UnsupportedOperationException("Profiles aren't part of this build."))

    /**
     * Set while joining a host that has people of its own and this device has profiles: the person
     * says who is who ([bringProfiles]), or stops joining ([cancelMerge]). Null otherwise.
     */
    val merge: StateFlow<ProfileMerge?> get() = NO_MERGE

    /** Joins the host [merge] is about, with each profile here as [choices] says (unnamed ones go up as new). */
    suspend fun bringProfiles(choices: Map<String, MergeChoice>): Result<Unit> = Result.failure(UnsupportedOperationException("Nothing to bring."))

    /** Stops joining the host [merge] is about: this device keeps its profiles and isn't linked. */
    suspend fun cancelMerge(): Result<Unit> = Result.success(Unit)
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

    /**
     * Before a game starts: its save brought up to date, or a conflict to ask about. With
     * [waitForOthers], another device still playing or sending it is asked about ([LaunchGate.Busy]).
     */
    suspend fun beforeLaunch(query: SaveQuery, waitForOthers: Boolean = true): LaunchGate

    /**
     * The game started: its save is watched while it runs and sent as soon as the game writes it
     * (so closing a handheld mid-game, or playing on and on, never leaves the newest save behind),
     * and other devices learn it is being played.
     */
    suspend fun playing(query: SaveQuery, startedAt: Long) {}

    /** Another device on [query]'s game (playing it, or still sending its save); null when none is, or the host can't say. */
    suspend fun busyWith(query: SaveQuery): LaunchGate.Busy? = null

    /** Look at the playing game's save now and send it if it changed (the screen is going off). */
    suspend fun sendWhilePlaying() {}

    /**
     * The game whose save is being kept in step right now (from just before it starts until its
     * save is sent after it stops), or null. Android keeps Fuse awake enough for it meanwhile.
     */
    val nowPlaying: StateFlow<String?> get() = NO_PLAYING

    /** Quiet news for the interface: a save sent, one that can't be used here. */
    val notices: kotlinx.coroutines.flow.SharedFlow<SyncNotice> get() = NO_NOTICES

    /** The person settled [conflict]: [keepHere] keeps this device's, else the host's comes down. */
    suspend fun settle(conflict: SaveConflict, keepHere: Boolean): Result<Unit>

    /** After a game: its session counted and its save captured, sent when it can be. */
    suspend fun afterExit(query: SaveQuery, startedAt: Long, endedAt: Long)

    /** Everything the host keeps for the profile in use (play time, every save and version), for the Hub; null offline. */
    suspend fun report(): ProfileReport? = null

    /** Games played as one save by everyone on the host (their saves aren't anyone's own). */
    val sharedGames: StateFlow<Set<String>> get() = NO_SHARED_GAMES

    /**
     * Makes [game] one save everyone plays together, or each person's own again. Made shared with
     * [fromMine], the person playing's newest save of it becomes the shared one; otherwise everyone
     * starts it together. Each person's own saves stay in their history either way.
     */
    suspend fun setShared(game: GameKey, shared: Boolean, fromMine: Boolean): Result<Unit> = Result.failure(UnsupportedOperationException("Fuse Sync isn't set up."))

    /**
     * Where each emulator in [samples] keeps its saves on this device, with the folders the person
     * chose (Settings, Save folders). The same for Fuse Sync and Syncthing, and works while off.
     */
    suspend fun saveFolders(samples: List<SaveQuery>): List<EmulatorSaves> = emptyList()

    suspend fun versions(query: SaveQuery, kind: SaveKind): List<SaveVersion>
    suspend fun restore(query: SaveQuery, kind: SaveKind, version: String): Result<Unit>
    suspend fun keepVersion(version: String, keep: Boolean): Result<Unit>

    /** Records changed here (a favourite, a collection, a setting): they go up soon. */
    fun changed()

    /**
     * Leaves the host: everything here stays exactly as it is; this device just stops syncing. With
     * [keepProfiles], the people who played here stay as this device's own profiles.
     */
    suspend fun unlink(keepProfiles: Boolean = true): Result<Unit>

    /**
     * On the host: deletes it (everyone's saves and profiles kept on this computer, and the
     * background service). This device keeps its own library, settings and Home as plain Fuse; other
     * devices keep everything they have and see the host gone.
     */
    suspend fun deleteHost(): Result<Unit> = Result.failure(UnsupportedOperationException("This device isn't a host."))

    /** On the host: moves everyone's saves and profiles to [to] (an empty folder), copied and checked first. The new path. */
    suspend fun moveHostData(to: String): Result<String> = Result.failure(UnsupportedOperationException("This device isn't a host."))

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

private val NO_PLAYING: StateFlow<String?> = kotlinx.coroutines.flow.MutableStateFlow(null)

private val NO_NOTICES: kotlinx.coroutines.flow.SharedFlow<SyncNotice> = kotlinx.coroutines.flow.MutableSharedFlow()

private val NO_JOIN_REQUESTS: StateFlow<List<JoinAsk>> = kotlinx.coroutines.flow.MutableStateFlow(emptyList())

private val NO_MERGE: StateFlow<ProfileMerge?> = kotlinx.coroutines.flow.MutableStateFlow(null)

private val NO_SHARED_GAMES: StateFlow<Set<String>> = kotlinx.coroutines.flow.MutableStateFlow(emptySet())



/** The household's services as a device sees them: what the host keeps, with this device's sign-ins opened. */
data class HouseholdShared(val services: HouseholdServices, val signIns: Map<String, String>)

private val NoRounds: StateFlow<Int> = kotlinx.coroutines.flow.MutableStateFlow(0)
