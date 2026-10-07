package io.github.matiyaaa.fuse.sync

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

// The household's games: what each device has, how a game gets from one device to another, and
// what a device asks another to do. Games never rest on the host: it keeps each device's list of
// games, passes a game's bytes through while two devices can't reach each other, and keeps the
// requests a device asks of another until that device is back.

/** One file of a game, by its place inside the game ("Disc 1.chd", "update/patch.nsp"). */
@Serializable
data class PeerFile(
    val path: String,
    val size: Long,
    /** Lowercase hex, once this device has read the file through; null until then. */
    val sha1: String? = null,
    val md5: String? = null,
)

/**
 * A game on one device, as the household sees it. [game] is the household's id for it (the one
 * every device agrees on, see [GameClaims]); [files] are relative to the game, the file that names
 * the game first. [folder] is true for a game that is a folder of its own.
 */
@Serializable
data class LibraryEntry(
    val game: String,
    /** Every id the game is known by on this device (its serial, its title): another device may know it by one of these. */
    val ids: List<String> = emptyList(),
    val title: String,
    val platform: String,
    val sizeBytes: Long,
    /** When it came to this device (its first scan, or its download). */
    val addedAt: Long = 0,
    /** How it came: "romm", the name of the device it was sent from, or null when it was simply found here. */
    val arrivedFrom: String? = null,
    val folder: Boolean = false,
    /** The game's own file or folder name, kept when it is placed on another device. */
    val name: String = "",
    val files: List<PeerFile> = emptyList(),
    val serial: String? = null,
    val rommRomId: Long? = null,
    /** Art and details already found for it here, so another device finds the same. */
    val steamGridDbId: Long? = null,
    val igdbId: Long? = null,
    val summary: String? = null,
    val releaseYear: Int? = null,
    val developer: String? = null,
    val genres: List<String> = emptyList(),
) {
    /** Every file has its hashes: the copy can be compared with others. */
    val hashed: Boolean get() = files.isNotEmpty() && files.all { it.sha1 != null }

    /** The copy's fingerprint, the same on every device with the same files; null until hashed. */
    val fingerprint: String? get() = if (!hashed) null else files.sortedBy { it.path }.joinToString("|") { "${it.path}:${it.sha1}" }
}

/** Where a device answers other devices for its games, on its home network. */
@Serializable
data class PeerEndpoint(val addresses: List<String> = emptyList(), val port: Int = 0)

/** A device's whole list, as it sends it and as others receive it. [version] changes whenever the list does. */
@Serializable
data class DeviceLibrary(
    val device: String,
    val name: String = "",
    val platform: String = "",
    val version: String = "",
    val entries: List<LibraryEntry> = emptyList(),
    val endpoint: PeerEndpoint? = null,
    val updatedAt: Long = 0,
    /** This device takes games other devices send it. */
    val accepts: Boolean = true,
)

/** What a device already has of the others' lists, by device and version. */
@Serializable
data class LibrariesKnown(val have: Map<String, String> = emptyMap())

/** The lists that changed since [LibrariesKnown], and every device that has one at all (the rest went). */
@Serializable
data class LibrariesPage(
    val changed: List<DeviceLibrary> = emptyList(),
    val devices: List<String> = emptyList(),
    /** When the host last heard from each device (every device, sharing or not). */
    val seen: Map<String, Long> = emptyMap(),
)

/** Asking the host for leave to fetch [files] of [game] from [source]. */
@Serializable
data class TicketRequest(val source: String, val game: String, val files: List<String>)

/**
 * The host's leave for [requester] to fetch [files] of [game] from [source] until [until]. Its key
 * is HMAC(source's secret, [message]): the source works it out from its own secret, and the
 * requester gets it from the host sealed with the requester's own secret ([sealedKey], [salt]), so
 * the key never crosses the network in the clear. Each request to the source is signed with it,
 * with a time and a nonce, so a request overheard on the network can't be played again.
 * [endpoint] is where to try the source directly.
 */
@Serializable
data class PeerTicket(
    val id: String,
    val requester: String,
    val source: String,
    val game: String,
    val files: List<String>,
    val until: Long,
    val endpoint: PeerEndpoint? = null,
    /** The host can pass the bytes through when the source can't be reached directly. */
    val relay: Boolean = true,
    val sealedKey: String = "",
    val salt: String = "",
) {
    /** What the key covers. */
    fun message(): String = listOf("fuse-peer-v1", id, requester, source, game, files.joinToString("\u0000"), until.toString()).joinToString("\n")

    /** The ticket as it travels to the source: without the sealed key. */
    fun claims(): PeerTicket = copy(sealedKey = "", salt = "")
}

/** The host asking a device to send a piece of a game through it: [ticket] says who may have it. */
@Serializable
data class RelayAsk(val id: String, val ticket: PeerTicket, val file: String, val offset: Long, val length: Long = 0, val at: Long = 0)

/** Something one device asks another to do, kept on the host until the other has done it. */
@Serializable
data class DeviceCommand(
    val id: String = "",
    val from: String = "",
    val fromName: String = "",
    val target: String,
    val type: String,
    /** The household's id for the game it is about. */
    val game: String? = null,
    val title: String = "",
    val platform: String? = null,
    /** For [TRANSFER]: which transfer (its key), and [action] to take. */
    val key: String? = null,
    val action: String? = null,
    val at: Long = 0,
    val state: String = PENDING,
    val message: String? = null,
    val doneAt: Long = 0,
) {
    val open: Boolean get() = state == PENDING || state == DELIVERED

    companion object {
        /** Bring [game] to the target (from wherever is best). */
        const val FETCH = "fetch"

        /** Send [game], which the target has, to RomM. */
        const val ROMM_UPLOAD = "romm-upload"

        /** Pause, resume, cancel or move one of the target's transfers. */
        const val TRANSFER = "transfer"

        const val PENDING = "pending"
        /** The target has it and is working on it. */
        const val DELIVERED = "delivered"
        const val DONE = "done"
        const val FAILED = "failed"
        const val CANCELLED = "cancelled"
    }
}

@Serializable
data class CommandAck(val state: String, val message: String? = null)

@Serializable
data class Commands(val commands: List<DeviceCommand> = emptyList())

/** One transfer on a device, as other devices see it. */
@Serializable
data class RemoteTransfer(
    val key: String,
    val title: String,
    val detail: String = "",
    val platform: String? = null,
    val kind: String = "GAME",
    val upload: Boolean = false,
    val status: String,
    val phase: String? = null,
    val waiting: String? = null,
    val done: Long = 0,
    val total: Long? = null,
    val speed: Long = 0,
    val order: Long = 0,
    val error: String? = null,
    /** The household's id for its game, when it is one. */
    val game: String? = null,
    val target: String = "",
)

/** A device's transfers as it last told the host. */
@Serializable
data class TransferSnapshot(val device: String, val name: String = "", val at: Long = 0, val items: List<RemoteTransfer> = emptyList())

@Serializable
data class TransferSnapshots(val devices: List<TransferSnapshot> = emptyList())

/** What [Household] asks of the app on this device. */
interface HouseholdLocal {
    /** Every game here to share, without hashes (Fuse Sync adds them as it reads each file). */
    suspend fun games(): List<SharedGame>

    /** What another device asked of this one: done (or not) by the app. */
    suspend fun perform(command: DeviceCommand): CommandResult

    /** This device's transfers now, for the other devices' Downloads. */
    fun transfers(): List<RemoteTransfer> = emptyList()

    /** This device is sending a game to another (direct or through the host): Android keeps Fuse awake meanwhile. */
    fun sending(what: String?) {}
}

/** A game here to share, with where its files are on this device ([paths] by [PeerFile.path]). */
data class SharedGame(val entry: LibraryEntry, val paths: Map<String, String>)

/** How a command went. [state] is one of [DeviceCommand]'s. */
data class CommandResult(val state: String, val message: String? = null)

/**
 * The household's games through Fuse Sync, on this device: the other devices' lists, leave to fetch
 * a game from one, what this device asked of others and others of it, and every device's
 * transfers. Without a host (or with a host before 0.3.8) [supported] is false and nothing happens.
 */
interface Household {
    val supported: StateFlow<Boolean>
    val libraries: StateFlow<List<DeviceLibrary>>

    /** This device's own list as it last went up, with the hashes read so far. */
    val mine: StateFlow<List<LibraryEntry>>

    /** When the host last heard from each device, as fresh as the last look (every half minute while linked). */
    val seen: StateFlow<Map<String, Long>>

    /** Requests still open, from and to any device (so "Waiting for Thor" shows everywhere). */
    val commands: StateFlow<List<DeviceCommand>>
    val transfers: StateFlow<List<TransferSnapshot>>

    /** This device's own id, as the others know it. */
    val self: String

    /** The app supplies this device's games and acts on requests. */
    fun attach(local: HouseholdLocal)

    /** This device's games changed (a scan, a download): its list goes up soon. */
    fun libraryChanged()

    /** Reads the others' lists now. */
    suspend fun refresh()

    suspend fun ticket(source: String, game: String, files: List<String>): PeerTicket

    suspend fun ask(command: DeviceCommand): Result<DeviceCommand>

    suspend fun cancel(command: String): Result<Unit>

    /** Says how a request this device was working on went (a game it was asked for arrived, or couldn't). */
    suspend fun settle(command: String, state: String, message: String? = null) {}

    /** While true (Downloads is open), the other devices' transfers are kept fresh. */
    fun watchTransfers(on: Boolean)

    /** This device's transfers changed: they go up soon, at most every couple of seconds. */
    fun transfersChanged()

    object None : Household {
        override val supported: StateFlow<Boolean> = MutableStateFlow(false)
        override val libraries: StateFlow<List<DeviceLibrary>> = MutableStateFlow(emptyList())
        override val mine: StateFlow<List<LibraryEntry>> = MutableStateFlow(emptyList())
        override val seen: StateFlow<Map<String, Long>> = MutableStateFlow(emptyMap())
        override val commands: StateFlow<List<DeviceCommand>> = MutableStateFlow(emptyList())
        override val transfers: StateFlow<List<TransferSnapshot>> = MutableStateFlow(emptyList())
        override val self: String = ""
        override fun attach(local: HouseholdLocal) = Unit
        override fun libraryChanged() = Unit
        override suspend fun refresh() = Unit
        override suspend fun ticket(source: String, game: String, files: List<String>): PeerTicket = throw UnsupportedOperationException("Fuse Sync isn't set up.")
        override suspend fun ask(command: DeviceCommand): Result<DeviceCommand> = Result.failure(UnsupportedOperationException("Fuse Sync isn't set up."))
        override suspend fun cancel(command: String): Result<Unit> = Result.failure(UnsupportedOperationException("Fuse Sync isn't set up."))
        override fun watchTransfers(on: Boolean) = Unit
        override fun transfersChanged() = Unit
    }
}

/** Moving a game's bytes from another device: directly when it answers, through the host otherwise. */
interface PeerBytes {
    /** Leave from the host to fetch [files] of [game] from [source], with its key opened. */
    suspend fun openTicket(source: String, game: String, files: List<String>): OpenTicket

    /** Whether [ticket]'s source answers directly on the home network, quickly. */
    suspend fun reachable(ticket: PeerTicket): Boolean

    /**
     * [length] bytes of [file] from [offset], straight from the device ([direct]) or through the
     * host. [write] gets them as they come (a buffer, how much of it, and the file's whole size when
     * the answer says it). Returns how many bytes came.
     */
    suspend fun fetch(open: OpenTicket, file: String, offset: Long, length: Long, direct: Boolean, write: suspend (ByteArray, Int, Long?) -> Unit): Long
}

/** A ticket with its key, ready to sign requests with. */
class OpenTicket(val ticket: PeerTicket, val key: String)

/** A device seen this recently is online. */
const val ONLINE_MS: Long = 2 * 60_000L
