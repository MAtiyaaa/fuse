package io.github.matiyaaa.fuse.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * What only this computer knows about itself as a host, never copied to a backup: who it is, the
 * votes it gave (one per generation, kept before it answers, so a restart can't make it vote
 * twice), whether it was set up to wait as a backup, and the newer generation that fenced it.
 */
@Serializable
internal data class NodeState(
    val nodeId: String,
    /** Set up as a backup: it waits for the household's copy and never makes itself the host. */
    val standby: Boolean = false,
    /** The newer generation it learned of while it thought it was the host: it takes no changes since. */
    val fencedBy: Long = 0,
    /** Who it voted for, by generation. */
    val votes: Map<String, String> = emptyMap(),
)

/**
 * This computer's place among the household's hosts: the household's [Leadership] (copied to every
 * backup, as `hosts.json`), this computer's own [NodeState] (`node.json`, never copied) and the
 * secret the hosts sign their calls to each other with (`hosts.secret`, copied sealed).
 *
 * The rules that keep two hosts from both taking changes:
 * - a generation only grows, and every call between hosts and from devices says the newest it knows;
 * - a computer that learns of a newer generation than its own while it was the host is fenced: it
 *   refuses every change until the new host has taken what it took meanwhile ([becomeBackup]);
 * - a backup becomes the host of a generation only with the votes of a majority of the hosts, and
 *   each host votes once per generation, for a backup whose copy is at least as new as its own and
 *   only while it can't reach the host itself ([grant]); with too few hosts for a majority without
 *   the host, only a person makes a backup the host ([promote] with `byPerson`).
 */
class HostLeadership(private val dir: File, private val clock: () -> Long = System::currentTimeMillis, private val name: () -> String = { "Fuse Sync Host" }) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val lock = Any()
    private val hostsFile = File(dir, HOSTS_FILE)
    private val nodeFile = File(dir, NODE_FILE)
    private val secretFile = File(dir, SECRET_FILE)

    @Volatile private var node: NodeState
    @Volatile private var state: Leadership

    init {
        dir.mkdirs()
        node = runCatching { json.decodeFromString(NodeState.serializer(), nodeFile.readText()) }.getOrNull()
            ?: NodeState(SyncCrypto.token(9).replace(Regex("[^A-Za-z0-9]"), "x")).also { save(it) }
        state = read() ?: Leadership()
    }

    private fun read(): Leadership? = if (hostsFile.isFile) runCatching { json.decodeFromString(Leadership.serializer(), hostsFile.readText()) }.getOrNull() else null

    private fun save(n: NodeState) {
        writeAtomically(nodeFile, json.encodeToString(NodeState.serializer(), n).toByteArray())
        node = n
    }

    private fun save(l: Leadership) {
        writeAtomically(hostsFile, json.encodeToString(Leadership.serializer(), l).toByteArray())
        state = l
    }

    val nodeId: String get() = node.nodeId

    /** The household's hosts as this computer knows them. A host from before backups is the host of generation 0. */
    val current: Leadership
        get() = synchronized(lock) {
            if (state.leader.isEmpty() && !node.standby) Leadership(0, nodeId, listOf(HostNode(nodeId, name())), since = 0) else state
        }

    val role: String
        get() = synchronized(lock) {
            when {
                node.fencedBy > 0 -> Leadership.FENCED
                node.standby && state.leader != nodeId -> Leadership.BACKUP
                current.leader == nodeId -> Leadership.PRIMARY
                else -> Leadership.BACKUP
            }
        }

    val isLeader: Boolean get() = role == Leadership.PRIMARY

    /** This computer was made the household's host (it is named so in the household's record), not just a host on its own. */
    val chosenAsHost: Boolean get() = synchronized(lock) { state.leader == nodeId }

    /** The secret the hosts sign their calls to each other with, made by the first host. */
    val secret: String
        get() = synchronized(lock) {
            if (!secretFile.isFile) writeAtomically(secretFile, SyncCrypto.token(32).toByteArray())
            secretFile.readText().trim()
        }

    /** Makes this computer one that waits as a backup (before its first copy of the household). */
    fun standBy() = synchronized(lock) { if (!node.standby) save(node.copy(standby = true)) }

    /** This host's own addresses, kept in the list devices learn the hosts from. */
    fun noteOwnAddress(local: String?, remote: String?) = synchronized(lock) {
        if (!isLeader) return@synchronized
        val l = current
        val me = l.hosts.firstOrNull { it.nodeId == nodeId } ?: HostNode(nodeId, name())
        val updated = me.copy(name = name(), local = local ?: me.local, remote = remote ?: me.remote)
        if (updated != me || l.hosts.none { it.nodeId == nodeId }) save(l.copy(hosts = listOf(updated) + l.hosts.filter { it.nodeId != nodeId }))
    }

    /**
     * Learns of [other]: a newer generation is taken as it is. When this computer was the host of
     * an older one it is fenced from now on. True when this computer was fenced by it.
     */
    fun observe(other: Leadership): Boolean = synchronized(lock) {
        if (other.generation <= current.generation || other.leader.isEmpty()) return@synchronized false
        val wasLeader = role == Leadership.PRIMARY
        if (wasLeader && other.leader != nodeId) save(node.copy(fencedBy = other.generation))
        save(other)
        wasLeader && other.leader != nodeId
    }

    /**
     * Learns that a newer generation than its own exists (a device said so) without knowing its
     * host yet: when this computer is the host, it is fenced. True when it was.
     */
    fun observeGeneration(generation: Long): Boolean = synchronized(lock) {
        if (generation <= current.generation || role != Leadership.PRIMARY) return@synchronized false
        save(node.copy(fencedBy = generation))
        true
    }

    /**
     * Answers a backup's request for this computer's vote. Given once per generation, never for an
     * older generation than this computer knows, only while this computer can't reach the host
     * itself ([hostReachable]), and only to a backup whose copy is at least as new as this
     * computer's own ([mySeq]). The vote is kept before the answer goes.
     */
    fun grant(req: VoteRequest, hostReachable: Boolean, mySeq: Long): VoteAnswer = synchronized(lock) {
        val l = current
        fun no(reason: String) = VoteAnswer(false, l, reason, highestVote())
        if (req.candidate !in l.hosts.map { it.nodeId }) return@synchronized no("not-a-host")
        if (req.generation <= l.generation) return@synchronized no("old-generation")
        val earlier = node.votes[req.generation.toString()]
        if (earlier != null && earlier != req.candidate) return@synchronized no("voted")
        if (role == Leadership.PRIMARY && req.candidate != nodeId) return@synchronized no("host-is-here")
        if (hostReachable) return@synchronized no("host-reachable")
        if (req.candidateSeq < mySeq) return@synchronized no("behind")
        save(node.copy(votes = (node.votes + (req.generation.toString() to req.candidate)).entries.sortedBy { it.key.toLong() }.takeLast(MAX_VOTES).associate { it.key to it.value }))
        VoteAnswer(true, l, votedIn = highestVote())
    }

    /** The newest generation this computer has voted in (0 when none). */
    fun highestVote(): Long = synchronized(lock) { node.votes.keys.mapNotNull { it.toLongOrNull() }.maxOrNull() ?: 0 }

    /** Votes this computer for itself in [generation]; false when it already voted for another. */
    fun voteForSelf(generation: Long): Boolean = synchronized(lock) {
        val earlier = node.votes[generation.toString()]
        if (earlier != null && earlier != nodeId) return@synchronized false
        save(node.copy(votes = node.votes + (generation.toString() to nodeId)))
        true
    }

    /**
     * Makes this computer the host of [generation] (one past the newest it knows, unless a newer is
     * given): with a majority's votes, or because a person said so. [forkSeq] is its copy's journal
     * position, from where an older host's changes are taken back.
     */
    fun promote(generation: Long = current.generation + 1, forkSeq: Long): Leadership = synchronized(lock) {
        val l = current
        require(generation > l.generation) { "A new host needs a newer generation" }
        val me = l.hosts.firstOrNull { it.nodeId == nodeId } ?: HostNode(nodeId, name())
        val next = Leadership(generation, nodeId, listOf(me) + l.hosts.filter { it.nodeId != nodeId }, since = clock(), forkSeq = forkSeq)
        save(node.copy(standby = false, fencedBy = 0))
        save(next)
        next
    }

    /**
     * [promote] after an election, unless this computer has since voted in a newer generation (for
     * another backup, which may be winning it): then nothing changes and null comes back. Checked
     * under the same lock as votes, so a computer never becomes the host of a generation older than
     * one it helped elect.
     */
    fun promoteElected(generation: Long, forkSeq: Long): Leadership? = synchronized(lock) {
        if (highestVote() > generation || node.votes[generation.toString()] != nodeId || generation <= current.generation) return@synchronized null
        promote(generation, forkSeq)
    }

    /** Adds [backup] to the household's hosts (only the host does). */
    fun addBackup(backup: HostNode): Leadership = synchronized(lock) {
        check(isLeader) { "Only the host adds backups" }
        val l = current
        val next = l.copy(hosts = l.hosts.filter { it.nodeId != backup.nodeId && (backup.deviceId == null || it.deviceId != backup.deviceId) } + backup)
        save(next)
        next
    }

    /** Takes a backup out of the household's hosts (only the host does). */
    fun removeBackup(nodeId: String): Leadership = synchronized(lock) {
        check(isLeader) { "Only the host removes backups" }
        require(nodeId != this.nodeId) { "The host isn't its own backup" }
        val l = current
        val next = l.copy(hosts = l.hosts.filter { it.nodeId != nodeId })
        save(next)
        next
    }

    /**
     * After the new host took what this computer took meanwhile (or for a backup that copied the
     * household): this computer is one of the new host's backups.
     */
    fun becomeBackup(of: Leadership) = synchronized(lock) {
        require(of.leader != nodeId) { "Not a backup of itself" }
        save(node.copy(fencedBy = 0, standby = true))
        if (of.generation >= state.generation) save(of)
    }

    /** The newest [Leadership] in [files] (a copy from the host), taken when it is at least as new as this computer's. */
    internal fun adoptCopied(bytes: ByteArray) = synchronized(lock) {
        val copied = runCatching { json.decodeFromString(Leadership.serializer(), bytes.decodeToString()) }.getOrNull() ?: return@synchronized
        if (copied.generation >= state.generation) save(copied)
    }

    internal fun signature(method: String, path: String, time: Long, nonce: String, body: ByteArray): String =
        RequestSigning.sign(secret, method, path, time, nonce, body)

    companion object {
        const val HOSTS_FILE = "hosts.json"
        const val NODE_FILE = "node.json"
        const val SECRET_FILE = "hosts.secret"
        private const val MAX_VOTES = 64

        /** Headers a host signs its calls to another host with. */
        const val NODE = "X-Fuse-Node"
        const val NODE_SIGNATURE = "X-Fuse-Node-Signature"

        /** The newest generation a device knows, sent with every call. */
        const val GENERATION = "X-Fuse-Generation"
    }
}
