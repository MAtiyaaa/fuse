package io.github.matiyaaa.fuse.sync

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.http.takeFrom
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import java.io.File

/** Calls between the household's hosts: who answers as the host, votes and news of a new host. */
internal object BackupCalls {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** Whether [node] answers, at any of its addresses, as the host of [generation] or newer for household [hostId]. */
    suspend fun answersAsHost(node: HostNode, generation: Long, hostId: String, http: HttpClient): Boolean {
        for (address in listOfNotNull(node.local, node.remote)) {
            val h = SyncClient.hello(address, http) ?: continue
            if (h.hostId == hostId && h.role == Leadership.PRIMARY && h.generation >= generation && (h.node.isEmpty() || h.node == node.nodeId)) return true
        }
        return false
    }

    /** [node]'s hello at the first of its addresses that answers, or null. */
    suspend fun hello(node: HostNode, http: HttpClient): HostHello? {
        for (address in listOfNotNull(node.local, node.remote)) SyncClient.hello(address, http)?.let { return it }
        return null
    }

    /** A call to [node] signed with the hosts' secret: its answer, or null when it didn't answer or refused. */
    suspend fun <B, T> post(node: HostNode, path: String, body: B, inS: KSerializer<B>, outS: KSerializer<T>, leadership: HostLeadership, http: HttpClient): T? {
        val bytes = json.encodeToString(inS, body).toByteArray()
        for (address in listOfNotNull(node.local, node.remote)) {
            try {
                val full = SyncApi.BASE + path
                val time = System.currentTimeMillis()
                val nonce = SyncCrypto.token(18)
                val resp = http.request {
                    method = HttpMethod.Post
                    url.takeFrom(SyncClient.normalise(address) + full)
                    header(HostLeadership.NODE, leadership.nodeId)
                    header(RequestSigning.TIME, time.toString())
                    header(RequestSigning.NONCE, nonce)
                    header(HostLeadership.NODE_SIGNATURE, leadership.signature("POST", full, time, nonce, bytes))
                    contentType(ContentType.Application.Json)
                    setBody(bytes)
                }
                if (!resp.status.isSuccess()) return null
                return json.decodeFromString(outS, resp.bodyAsText())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                continue
            }
        }
        return null
    }
}

/**
 * This computer as one of the household's backup hosts: it keeps a copy of everything the host
 * keeps ([copy]), watches the host, and takes over only as the rules of [HostLeadership] allow:
 * with a majority of the hosts' votes when the host has been away [failoverMs] ([watch]), or when a
 * person says so ([takeOver]). As the new host it brings an older host back ([bringBack]): fenced
 * first, then whatever it took meanwhile taken over (kept as history where it doesn't follow the
 * newest), then made a backup.
 *
 * [store] is the copy, in a folder of its own (made to wait with [HostLeadership.standBy] before its
 * first copy), or the host's own store; [device] is this computer as a device of the household (it
 * copies the household as that device, sealed with its own secret).
 */
class BackupNode(
    val store: HostStore,
    private val device: SyncClient,
    private val http: HttpClient = SyncClient.defaultClient(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val failoverMs: Long = FAILOVER_MS,
    /** Where taking back an older host's changes works: a folder of its own, emptied after. */
    private val scratch: File = File(store.dir.parentFile ?: store.dir, "${store.dir.name}-bringing-back"),
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val leadership get() = store.leadership

    /** When the host last answered this computer as the host. */
    @Volatile var hostSeenAt: Long = clock()
        private set

    /** What one round of [watch] did. */
    enum class Round { COPIED, UP_TO_DATE, HOST_AWAY, ELECTED, NOT_ELECTED, IS_HOST, FOLLOWED }

    /**
     * Takes the household's newest copy from its host: the saves' files first (each checked by its
     * hash), then everything else, so the copy never names a file it doesn't have. False when the
     * host didn't answer or the copy couldn't be opened.
     */
    suspend fun copy(): Boolean {
        if (leadership.isLeader) return false
        val sealed = try {
            device.replica(store.seq())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return false
        }
        hostSeenAt = clock()
        if (sealed.unchanged) return true
        val bundle = withContext(Dispatchers.Default) {
            val opened = SyncCrypto.open(sealed.sealed, device.link.deviceSecret, sealed.salt) ?: return@withContext null
            runCatching { json.decodeFromString(ReplicaBundle.serializer(), Gzip.unpack(opened, MAX_COPY).decodeToString()) }.getOrNull()
        } ?: return false
        for (hash in bundle.objects) {
            if (store.content.has(hash)) continue
            try {
                device.download(hash, store.content)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return false
            }
        }
        withContext(Dispatchers.IO) { store.applyReplica(bundle.files.mapValues { SyncCrypto.decode(it.value) }) }
        return true
    }

    /**
     * One round of watching: the copy brought up to date while the host answers; once it has been
     * away [failoverMs], an election when there are enough hosts for a majority without it.
     */
    suspend fun watch(): Round {
        if (leadership.isLeader) {
            bringBack()
            return Round.IS_HOST
        }
        val l = leadership.current
        // No copy yet: the household's host is whoever this computer's device reaches.
        if (l.leader.isEmpty()) return if (copy()) Round.COPIED else Round.HOST_AWAY
        val leader = l.leaderNode
        if (leader != null && BackupCalls.answersAsHost(leader, l.generation, store.identity.hostId, http)) {
            hostSeenAt = clock()
            val before = store.seq()
            return if (copy()) (if (store.seq() != before) Round.COPIED else Round.UP_TO_DATE) else Round.UP_TO_DATE
        }
        // Another backup may have become the host already: follow it.
        for (node in l.backups.filter { it.nodeId != leadership.nodeId }) {
            val h = BackupCalls.hello(node, http) ?: continue
            if (h.hostId == store.identity.hostId && h.role == Leadership.PRIMARY && h.generation > l.generation) {
                leadership.observe(Leadership(h.generation, h.node, h.hosts, since = clock()))
                return Round.FOLLOWED
            }
        }
        // Backups ask in their order, a little apart, so two rarely ask at once and split the votes.
        val rank = l.backups.indexOfFirst { it.nodeId == leadership.nodeId }.coerceAtLeast(0)
        if (clock() - hostSeenAt < failoverMs + rank * (failoverMs / 4)) return Round.HOST_AWAY
        if (l.hosts.size < 3) return Round.HOST_AWAY
        return if (elect()) Round.ELECTED else Round.NOT_ELECTED
    }

    /**
     * Asks the other hosts for their votes to become the host of the next generation. Becomes it
     * only with a majority (its own vote counted); otherwise nothing changes, and the votes given
     * stay given, so no other backup can win the same generation with them.
     */
    suspend fun elect(): Boolean {
        var generation = maxOf(leadership.current.generation, leadership.highestVote()) + 1
        // A generation whose votes went to another is lost for good: once more past it, then wait.
        repeat(2) {
            val l = leadership.current
            if (!leadership.voteForSelf(generation)) return false
            var votes = 1
            var votedIn = 0L
            val req = VoteRequest(generation, leadership.nodeId, store.seq())
            for (node in l.hosts.filter { it.nodeId != leadership.nodeId }) {
                val answer = BackupCalls.post(node, "/leadership/vote", req, VoteRequest.serializer(), VoteAnswer.serializer(), leadership, http) ?: continue
                if (answer.leadership.generation >= generation) {
                    leadership.observe(answer.leadership)
                    return false
                }
                if (answer.granted) votes++ else votedIn = maxOf(votedIn, answer.votedIn)
            }
            if (votes >= l.majority) {
                val next = leadership.promoteElected(generation, forkSeq = store.seq()) ?: return false
                announce(next)
                return true
            }
            if (votedIn < generation) return false
            generation = votedIn + 1
        }
        return false
    }

    /**
     * Makes this computer the host because a person said so: for a household with too few hosts for
     * a majority without the host, when the host is gone for good. The host, if it comes back, is
     * fenced and its changes taken back ([bringBack]).
     */
    suspend fun takeOver(): Leadership {
        val l = leadership.current
        val next = leadership.promote(l.generation + 1, forkSeq = store.seq())
        announce(next)
        return next
    }

    /** Tells every other host there is a new host ([reconciled]: the one whose changes it has just taken back). */
    suspend fun announce(l: Leadership, reconciled: String? = null) {
        for (node in l.hosts.filter { it.nodeId != leadership.nodeId }) {
            if (reconciled != null && node.nodeId != reconciled) continue
            BackupCalls.post(node, "/leadership/announce", Announcement(l, reconciled), Announcement.serializer(), Announcement.serializer(), leadership, http)
        }
    }

    /**
     * As the host: finds a computer that was the host of an older generation and answers again,
     * fences it, takes everything it took since, and makes it a backup. The names of the computers
     * brought back.
     */
    suspend fun bringBack(): List<String> {
        val l = leadership.current
        if (!leadership.isLeader) return emptyList()
        val back = ArrayList<String>()
        for (node in l.hosts.filter { it.nodeId != leadership.nodeId }) {
            val h = BackupCalls.hello(node, http) ?: continue
            if (h.hostId != store.identity.hostId || h.generation > l.generation) continue
            val older = h.role == Leadership.FENCED || (h.role == Leadership.PRIMARY && h.generation < l.generation)
            if (!older) continue
            // Fenced first: from now on it takes nothing more.
            val news = BackupCalls.post(node, "/leadership/announce", Announcement(l), Announcement.serializer(), Announcement.serializer(), leadership, http) ?: continue
            if (news.role != Leadership.FENCED && news.role != Leadership.BACKUP) continue
            if (news.role == Leadership.FENCED && !takeBack(node)) continue
            BackupCalls.post(node, "/leadership/announce", Announcement(l, reconciled = node.nodeId), Announcement.serializer(), Announcement.serializer(), leadership, http)
            back += node.name
        }
        return back
    }

    /** Copies [node]'s household as this computer's device and takes from it what this host hasn't. */
    private suspend fun takeBack(node: HostNode): Boolean {
        val there = SyncClient(device.link.copy(localAddress = node.local, remoteAddress = node.remote, hosts = emptyList()), http)
        val sealed = try {
            there.replica(-1)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return false
        }
        val bundle = withContext(Dispatchers.Default) {
            val opened = SyncCrypto.open(sealed.sealed, device.link.deviceSecret, sealed.salt) ?: return@withContext null
            runCatching { json.decodeFromString(ReplicaBundle.serializer(), Gzip.unpack(opened, MAX_COPY).decodeToString()) }.getOrNull()
        } ?: return false
        for (hash in bundle.objects) {
            if (store.content.has(hash)) continue
            try {
                there.download(hash, store.content)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A file it can't send: its save is left with it, the rest taken.
            }
        }
        return withContext(Dispatchers.IO) {
            scratch.deleteRecursively()
            try {
                for ((path, b64) in bundle.files) {
                    if (path.split('/').any { it == ".." || it.isEmpty() }) continue
                    File(scratch, path).apply { parentFile?.mkdirs() }.writeBytes(SyncCrypto.decode(b64))
                }
                // Its saves' files are already in this host's own store; only its lists are read from here.
                store.reconcile(HostStore(scratch, clock))
                true
            } finally {
                scratch.deleteRecursively()
            }
        }
    }

    companion object {
        /** How long the host may be away before the backups choose another. */
        const val FAILOVER_MS = 10 * 60_000L

        /** The most a copy of the household may unpack to. */
        const val MAX_COPY = 512L * 1024 * 1024
    }
}
