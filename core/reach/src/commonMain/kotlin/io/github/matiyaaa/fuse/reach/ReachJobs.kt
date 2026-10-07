package io.github.matiyaaa.fuse.reach

import io.github.matiyaaa.fuse.romm.RommFile
import io.github.matiyaaa.fuse.transfer.TransferHandler
import io.github.matiyaaa.fuse.transfer.TransferItem
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The source name a reach download carries in Fuse's transfers. */
const val REACH_SOURCE = "reach"

/**
 * One file of a game being brought here: its place inside the game, what it must be (size and
 * hashes, from the copy the game was chosen from), and RomM's file when RomM has it too.
 */
@Serializable
data class ReachFile(
    val path: String,
    val size: Long,
    val sha1: String? = null,
    val md5: String? = null,
    val romm: RommFile? = null,
)

/** Somewhere a game can come from: another device of the household, or a RomM server. */
@Serializable
data class ReachSource(
    /** [PEER] or [ROMM]. */
    val kind: String,
    /** The device's id (a peer), or the server's (RomM). */
    val id: String,
    /** As the person reads it: "Gaming PC", "RomM". */
    val name: String = "",
    /** RomM's id for the game. */
    val romId: Long = 0,
) {
    val key: String get() = "$kind:$id"

    companion object {
        const val PEER = "peer"
        const val ROMM = "romm"
    }
}

/**
 * A game brought here from wherever is best, kept with its transfer. Its files are fixed when it
 * is queued, from one copy (so the game is the same whichever source sends it); each source is
 * one that has exactly those files. [folder] is a game that is a folder of its own: gathered
 * beside it and put in place in one step. Otherwise its files go into the system's folder, the
 * file that names the game ([files]' first) last of all, so a scan never finds half a game.
 */
@Serializable
data class ReachJob(
    /** The household's id for the game. */
    val game: String,
    val title: String,
    val platform: String,
    val folder: Boolean,
    val files: List<ReachFile>,
    val sources: List<ReachSource>,
    /** A request from another device this answers: settled once it is here (or can't be). */
    val command: String? = null,
    /** Bytes a second each source managed last time, by [ReachSource.key]: the faster one goes first. */
    val speeds: Map<String, Long> = emptyMap(),
    /** Which source each partial file came from, so a part is only carried on from the same bytes. */
    val partFrom: Map<String, String> = emptyMap(),
    /** Files already checked and gathered. */
    val done: List<String> = emptyList(),
) {
    val totalBytes: Long get() = files.sumOf { it.size }

    fun encode(): String = reachJson.encodeToString(serializer(), this)

    companion object {
        fun decode(text: String): ReachJob = reachJson.decodeFromString(serializer(), text)
    }
}

internal val reachJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

/** How a source can be reached right now, best first: the order Fuse tries them in. */
enum class Reach {
    /** Another device on the home network, answering directly. */
    PEER_LOCAL,

    /** RomM over its home address. */
    ROMM_LOCAL,

    /** Another device, through the Fuse Sync host. */
    PEER_RELAY,

    /** RomM over its address from outside. */
    ROMM_REMOTE,

    /** Off, away, or not reachable at all now. */
    NONE,
}

/** A source and how it can be reached now. */
data class Candidate(val source: ReachSource, val reach: Reach, val speed: Long = 0)

/**
 * The order to try sources in: by how they are reached (another device at home, RomM at home,
 * another device through the host, RomM from outside), and within one, the faster first by what
 * each managed last time. Sources that can't be reached are left out.
 */
object SourceRanking {
    fun order(candidates: List<Candidate>): List<Candidate> =
        candidates.filter { it.reach != Reach.NONE }.sortedWith(compareBy<Candidate> { it.reach.ordinal }.thenByDescending { it.speed })
}

/** What a reach download needs from the app. */
interface ReachTransferHost {
    /** The household's devices: fetching from them, and whether each is online. */
    val peers: io.github.matiyaaa.fuse.sync.PeerBytes?

    /** For fetching from RomM. */
    val http: io.ktor.client.HttpClient

    /** RomM's client for [server], signed in; null when Fuse RomM is off or signed out. */
    fun romm(server: String): io.github.matiyaaa.fuse.romm.RommClient?

    /** Whether [device] has been seen lately. */
    fun online(device: String): Boolean

    /** Whether games may pass through the Fuse Sync host. */
    val relay: Boolean

    /**
     * The hashes [source] now gives [path] of [game] (it reads files as they are asked for), or
     * null while it hasn't yet. Asks the household again first.
     */
    suspend fun hashesFrom(source: String, game: String, path: String): Pair<String?, String?>?

    /** The game is here, whole and checked: Fuse looks at it and adds it to the library. */
    suspend fun landed(job: ReachJob, item: TransferItem, paths: List<String>)

    /** It can't be done (it was asked by another device): that device hears why. */
    suspend fun failed(job: ReachJob, why: String) {}
}

/** Fuse's reach downloads, for Fuse's transfers. */
expect fun reachHandlers(host: ReachTransferHost): List<TransferHandler>
