package io.github.matiyaaa.fuse.sync

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer

/**
 * Fuse Sync's wire format: what the host and its devices say to each other over HTTP(S), as JSON.
 * Every call but [HostHello] and pairing is signed by the device (see [RequestSigning]); a profile's
 * data is only ever reached through a profile the device has opened ([ProfileTicket]).
 */
object SyncApi {
    const val VERSION = 1
    const val BASE = "/sync/v1"

    /** The UDP port hosts answer discovery on, and the port they serve on by default. */
    const val DISCOVERY_PORT = 47310
    const val DEFAULT_PORT = 47311

    /** Where a device answers other devices for its games (the next free port when taken). */
    const val PEER_PORT = 47312
    const val PEER_BASE = "/peer/v1"

    /** The household's games (0.3.8): lists, requests between devices, passing games through. */
    const val FEATURE_HOUSEHOLD = "household"

    /** The most a piece of a game passed through the host carries (a tunnel refuses big uploads). */
    const val RELAY_PIECE = 32L * 1024 * 1024
}

/** Which way a device reaches its host: at home, or from outside. */
enum class Route { LOCAL, REMOTE }

/** What anyone on the network may learn about a host: enough to find it and pair with it. */
@Serializable
data class HostHello(
    val hostId: String,
    val name: String,
    val version: Int = SyncApi.VERSION,
    val port: Int = SyncApi.DEFAULT_PORT,
    val fuseVersion: String = "",
    /** Where it can be reached on the home network, best first (in discovery answers). */
    val addresses: List<String> = emptyList(),
    /** Its address from outside home (a tunnel's https name), shared with every device; empty when none. */
    val outside: String = "",
    /** It has an account, so a device can join with its username and password when nobody is at a screen. */
    val account: Boolean = false,
    /** What this host offers beyond saves and records ([SyncApi.FEATURE_HOUSEHOLD]); a device asks only for what is listed. */
    val features: List<String> = emptyList(),
)

/** A device asking to join: the code shown on the host, and who the device is. */
@Serializable
data class PairRequest(val code: String, val deviceId: String, val deviceName: String, val platform: String)

/**
 * The host's answer to pairing: the device's secret, sealed with a key only the pairing code
 * gives (so it never crosses the network readable, even on plain HTTP at home).
 */
@Serializable
data class PairResponse(val hostId: String, val hostName: String, val sealedSecret: String, val salt: String)

/** A person on the host, as devices see them: never their PIN. */
@Serializable
data class ProfileInfo(
    val id: String,
    val name: String,
    val avatar: String,
    val protected: Boolean,
    val createdAt: Long,
    /** Bytes of saves and states this profile keeps on the host (shared files counted once). */
    val storageBytes: Long = 0,
    val devices: List<String> = emptyList(),
    /** The host computer's own profile (Admin): only the host's own Fuse sees it or plays as it. */
    val hostOnly: Boolean = false,
)

/**
 * A profile to make. [pinHash] carries a PIN already kept as [SyncCrypto]'s salted hash (a profile
 * made on a device without a host, brought to one), so the PIN itself never needs typing again.
 */
@Serializable
data class NewProfile(val name: String, val avatar: String, val pin: String? = null, val pinHash: String? = null)

/** The order profiles show in, everywhere: their ids, first to last. */
@Serializable
data class ProfileOrder(val ids: List<String>)

@Serializable
data class ProfileChange(val name: String? = null, val avatar: String? = null, val pin: String? = null, val removePin: Boolean = false, val currentPin: String? = null)

/** Opening a profile: its PIN when it has one. */
@Serializable
data class UnlockRequest(val pin: String? = null)

/** The host's leave to use a profile from this device, until the device is unlinked or the PIN changes. */
@Serializable
data class ProfileTicket(val profile: String, val ticket: String)

/** One device as the host knows it. */
@Serializable
data class DeviceInfo(
    val id: String,
    val name: String,
    val platform: String,
    val profile: String? = null,
    val lastSeen: Long = 0,
    val lastSync: Long = 0,
    /** LOCAL, REMOTE or empty. */
    val connection: String = "",
    val revoked: Boolean = false,
)

@Serializable
data class DeviceChange(val name: String? = null, val profile: String? = null, val connection: String? = null)

/** The host's state for a Fuse Sync Hub: health, storage and its people and devices. */
@Serializable
data class HostStatus(
    val hello: HostHello,
    val profiles: List<ProfileInfo>,
    val devices: List<DeviceInfo>,
    val storageBytes: Long,
    val objectCount: Int,
    val revisionCount: Int,
    val journalSeq: Long,
    val startedAt: Long,
    val freeBytes: Long = -1,
)

/** A device's change to a profile's records, merged on the host, which answers with the whole. */
@Serializable
data class MetaPush(val meta: ProfileMeta)

@Serializable
data class MetaState(val meta: ProfileMeta, val seq: Long)

/** A new revision offered to the host. Its files must already be on the host (see [MissingBlobs]). */
@Serializable
data class RevisionPush(val revision: SaveRevision)

/**
 * The host's answer to a new revision: kept (and now the newest), or a conflict, with what the
 * host has, so the device can show both.
 */
@Serializable
data class RevisionResult(val accepted: Boolean, val head: SaveRevision?, val conflict: Boolean = false)

@Serializable
data class MissingBlobs(val hashes: List<String>)

/** One thing that happened on the host, in order: devices catch up from the last [seq] they saw. */
@Serializable
data class JournalEvent(
    val seq: Long,
    val type: String,
    val profile: String? = null,
    val game: String? = null,
    val kind: SaveKind? = null,
    val revision: String? = null,
    val device: String? = null,
    val at: Long = 0,
) {
    companion object {
        const val REVISION = "revision"
        const val META = "meta"
        const val PROFILE = "profile"
        const val DEVICE = "device"
        /** A device asked to join: devices that may let it in look at the requests. */
        const val JOIN = "join"
    }
}

/**
 * A device asking to join without a code: who it is, and its half of a key exchange (an EC P-256
 * public key). The host answers with its own half; both sides then show the same six-digit
 * number, so whoever lets the device in can see it is this device, and the device's secret
 * travels sealed with a key only the two of them have.
 */
@Serializable
data class JoinRequest(val deviceId: String, val deviceName: String, val platform: String, val publicKey: String)

@Serializable
data class JoinTicket(
    val id: String,
    val hostId: String,
    val hostName: String,
    val publicKey: String,
    /** With an account on the host: how its password is stretched, so the device can prove it knows it. */
    val accountSalt: String? = null,
    val accountIterations: Int = 0,
)

/**
 * Joining with the host's account: [proof] is an HMAC, keyed by the stretched password, of the
 * secret only this device and the host share for this request. The password never travels.
 */
@Serializable
data class JoinWithAccount(val username: String, val proof: String)

/** The host's account, set on the host: [password] null keeps the one it has (a new username only). */
@Serializable
data class HostAccountChange(val username: String, val password: String? = null)

/** The host's address from outside home, set on the host (empty clears it). */
@Serializable
data class OutsideChange(val address: String)

/** Where a request to join stands: [WAITING], [ALLOWED] (with the sealed secret), [DENIED] or [GONE] (ran out). */
@Serializable
data class JoinState(val state: String, val sealedSecret: String? = null, val salt: String? = null) {
    companion object {
        const val WAITING = "waiting"
        const val ALLOWED = "allowed"
        const val DENIED = "denied"
        const val GONE = "gone"
    }
}

/** A request to join, as a device that may let it in sees it: who asks, and the number both show. */
@Serializable
data class JoinAsk(val id: String, val deviceName: String, val platform: String, val match: String, val at: Long)

@Serializable
data class JoinAnswer(val allow: Boolean)

/** Every id a device may know each game by (serial, title), most trusted first: one list per game. */
@Serializable
data class GameClaims(val games: List<List<String>>)

/** The one id for each game in [GameClaims], in the same order. */
@Serializable
data class ResolvedGames(val ids: List<String>)

/**
 * A device playing a game, or done with it and still sending its save: so another device about to
 * start the same game knows to wait a moment, or that its newest save may not have reached the host.
 */
@Serializable
data class Presence(
    val deviceId: String,
    val deviceName: String,
    val profile: String,
    val game: String,
    val title: String = "",
    val state: String,
    /** When the device last said so (it repeats it while playing). */
    val at: Long,
    /** When it started playing. */
    val since: Long,
) {
    companion object {
        const val PLAYING = "playing"
        const val SENDING = "sending"
    }
}

/** What a device says about itself: [state] null when it is done (nothing playing, nothing waiting). */
@Serializable
data class PresenceNote(val profile: String, val game: String, val title: String = "", val state: String? = null, val since: Long = 0)

@Serializable
data class JournalPage(
    val events: List<JournalEvent>,
    val seq: Long,
    /** What is waiting for this device besides the journal (see [HouseholdHost]): never kept, only told. */
    val wake: List<String> = emptyList(),
)

/** Every slot's newest revision for a profile, for a device starting fresh or catching up in full. */
@Serializable
data class Heads(val heads: List<SaveRevision>, val seq: Long)

@Serializable
data class ApiError(val error: String, val code: String = "")

/**
 * Everything the host keeps for one profile, for the Hub: play time (in all, and by device), every
 * game with its saves and save states, each with every version, which device saved it, how big it
 * is, and where each of its files is kept on the host.
 */
@Serializable
data class ProfileReport(
    val profile: ProfileInfo,
    val playSeconds: Long,
    /** Play time by device id. */
    val devicePlay: Map<String, Long>,
    val devices: List<DeviceInfo>,
    val games: List<GameReport>,
    /** Bytes the host keeps for this profile's saves, every version, files shared between versions counted once. */
    val savesBytes: Long,
    /** Where the host keeps its files, on the host computer. */
    val storePath: String = "",
)

@Serializable
data class GameReport(
    val game: String,
    val name: String,
    val platform: String,
    val playSeconds: Long,
    val devicePlay: Map<String, Long>,
    val sessions: Int,
    val lastPlayed: Long?,
    val favorite: Boolean,
    val slots: List<SlotReport>,
)

/** One kind of save for a game ([SaveKind.SAVE], a state, a card) and its versions, newest first. */
@Serializable
data class SlotReport(
    val kind: SaveKind,
    val format: String,
    /** Bytes kept for every version, files shared between versions counted once. */
    val bytes: Long,
    val versions: List<VersionReport>,
)

@Serializable
data class VersionReport(
    val id: String,
    val deviceId: String,
    val device: String,
    val at: Long,
    val playSeconds: Long,
    val reason: RevisionReason,
    val current: Boolean,
    val bytes: Long,
    val files: List<FileReport>,
    /** Kept for good by the person. */
    val kept: Boolean = false,
)

/** A file of a save: its name in the save, its size, and where the host keeps it (relative to [ProfileReport.storePath]). */
@Serializable
data class FileReport(val path: String, val bytes: Long, val stored: String)

/**
 * The games a household plays as one save (everyone on the host shares it). Their saves are kept
 * under [SHARED_SAVES] rather than under each person; play time stays each person's own.
 */
@Serializable
data class SharedGames(val games: List<String> = emptyList())

/** Makes [game] one save for everyone (starting from [from]'s, when given) or each person's own again. */
@Serializable
data class SharedChange(val game: String, val shared: Boolean, val from: String? = null)

/** Where the saves of games played as one save are kept on the host, beside the people's own. */
const val SHARED_SAVES = "@shared"

/** Every id a game is known by, to its one id across devices (as the host and each device keep it). */
val GameAliasesSerializer: KSerializer<Map<String, String>> = MapSerializer(String.serializer(), String.serializer())

/**
 * Where one device stands with one save, as it reports it after looking (0.3.6 hosts keep these;
 * an older host simply doesn't take them, and devices carry on). "Applied" means only what it says:
 * the save is in the emulator's folder on that device, or is that device's own.
 */
@Serializable
enum class AppliedState {
    /** The host's newest is in place here (or was made here). */
    CURRENT,

    /** This device played since it last agreed and the host moved on too: kept for the person to choose. */
    CONFLICT,

    /** The newest is for an emulator this device plays the game with differently: it stays on the host. */
    INCOMPATIBLE,

    /** The save's folder isn't reachable here (a card out, a private folder). */
    UNAVAILABLE,

    /** An older one is here; the newest is on its way. */
    BEHIND,
}

/** One device's report on one save: which revision is in place, and how things stand. */
@Serializable
data class AppliedNote(val profile: String, val game: String, val kind: SaveKind, val revision: String?, val state: AppliedState, val at: Long = 0)

@Serializable
data class AppliedNotes(val notes: List<AppliedNote>)

/** One device's place with one save, for "5 of 6 devices current". */
@Serializable
data class DeviceSlot(val device: String, val name: String, val platform: String = "", val state: AppliedState? = null, val revision: String? = null, val at: Long = 0, val lastSeen: Long = 0)

/** Every device's place with one save of one game. [state] null for a device that never reported it (it may not have the game). */
@Serializable
data class SlotDevices(val game: String, val kind: SaveKind, val head: String?, val headDevice: String? = null, val devices: List<DeviceSlot>) {
    /** Devices that reported this save at all: the ones that play the game. */
    val playing: List<DeviceSlot> get() = devices.filter { it.state != null }
    val current: Int get() = playing.count { it.state == AppliedState.CURRENT }
}

@Serializable
data class Convergence(val slots: List<SlotDevices>)


/** Where a service the household uses is (RomM or Jellyfin): its addresses and how they're tried. */
@Serializable
data class ServiceAddress(val local: String = "", val remote: String = "", val mode: String = "AUTO")

/**
 * The household's RomM and Jellyfin as the host keeps them for its devices: addresses as they are,
 * sign-ins only ever [sealed] (AES-GCM, a key stretched from the asking device's own secret with
 * [salt]) as a JSON map of `"romm"` and `"jellyfin:<profile>"` to each sign-in, holding only the
 * profiles that device may open. [shared] says a device has shared its sign-ins; [declined] lists
 * the devices that were asked and said no, so the next one to update asks instead.
 */
@Serializable
data class HouseholdServices(
    val romm: ServiceAddress? = null,
    val jellyfin: ServiceAddress? = null,
    val sealed: String? = null,
    val salt: String = "",
    val shared: Boolean = false,
    val declined: List<String> = emptyList(),
)

/** What a device shares, its sign-ins sealed with its own secret as in [HouseholdServices]; or that it said no. */
@Serializable
data class ServicesShare(
    val romm: ServiceAddress? = null,
    val jellyfin: ServiceAddress? = null,
    val sealed: String? = null,
    val salt: String = "",
    val declined: Boolean = false,
)

/** One Jellyfin sign-in, inside a sealed map. */
@Serializable
data class JellyfinLogin(val username: String, val password: String)
