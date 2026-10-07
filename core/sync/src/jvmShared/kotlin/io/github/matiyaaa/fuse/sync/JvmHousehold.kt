package io.github.matiyaaa.fuse.sync

import io.github.matiyaaa.fuse.data.settings.SyncSettings
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.jvm.javaio.toInputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/** A device's own list of others' lists, kept so the household's games show while the host is away. */
@Serializable
private data class KeptLibraries(val libraries: List<DeviceLibrary> = emptyList(), val commands: List<DeviceCommand> = emptyList())

/**
 * The household's games on a JVM device (see [Household]): this device's list sent to the host
 * (with each file's hashes, read in the background a file at a time), the others' lists kept here
 * (so they show offline), requests from and to other devices, pieces of games sent through the
 * host, and the server other devices fetch from on the home network.
 */
class JvmHousehold(
    private val dir: File,
    private val scope: CoroutineScope,
    private val client: () -> SyncClient?,
    private val selfId: () -> String,
    private val config: () -> SyncSettings,
    /** A game is being played here: hashing and sending wait meanwhile. */
    private val busy: () -> Boolean = { false },
    private val clock: () -> Long = System::currentTimeMillis,
    /** Where this device's server listens (tests use a free port). */
    private val peerPort: Int = SyncApi.PEER_PORT,
    private val bindAddresses: () -> List<String> = { LanAddresses.list() },
) : Household, PeerBytes {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val _supported = MutableStateFlow(false)
    private val _libraries = MutableStateFlow<List<DeviceLibrary>>(emptyList())
    private val _commands = MutableStateFlow<List<DeviceCommand>>(emptyList())
    private val _transfers = MutableStateFlow<List<TransferSnapshot>>(emptyList())
    override val supported: StateFlow<Boolean> = _supported.asStateFlow()
    override val libraries: StateFlow<List<DeviceLibrary>> = _libraries.asStateFlow()
    private val _mine = MutableStateFlow<List<LibraryEntry>>(emptyList())
    override val mine: StateFlow<List<LibraryEntry>> = _mine.asStateFlow()
    private val _seen = MutableStateFlow<Map<String, Long>>(emptyMap())
    override val seen: StateFlow<Map<String, Long>> = _seen.asStateFlow()
    private var lookJob: Job? = null
    override val commands: StateFlow<List<DeviceCommand>> = _commands.asStateFlow()
    override val transfers: StateFlow<List<TransferSnapshot>> = _transfers.asStateFlow()
    override val self: String get() = selfId()

    @Volatile private var local: HouseholdLocal? = null
    private val keptFile = File(dir, "household.json")
    private val hashes = HashCache(File(dir, "hashes.json"))

    /** This device's games as last listed, with where each file is. */
    @Volatile private var shared: Map<String, SharedGame> = emptyMap()
    @Volatile private var published: String = ""

    private val server = PeerServer(
        self = selfId,
        secret = { client()?.link?.deviceSecret },
        resolve = { game, file -> resolve(game, file) },
        serving = { who -> local?.sending(who?.let(::nameOf)) },
        clock = clock,
    )

    @Volatile private var httpClient: HttpClient? = null
    private val http: HttpClient
        get() = httpClient ?: synchronized(this) {
            httpClient ?: SyncHttp.client {
                install(HttpTimeout) { connectTimeoutMillis = 1_500; socketTimeoutMillis = 60_000; requestTimeoutMillis = 30 * 60_000 }
                expectSuccess = false
            }.also { httpClient = it }
        }

    init {
        runCatching { json.decodeFromString(KeptLibraries.serializer(), keptFile.readText()) }.getOrNull()?.let {
            _libraries.value = it.libraries
            _commands.value = it.commands
        }
    }

    override fun attach(local: HouseholdLocal) {
        this.local = local
        libraryChanged()
    }

    private fun nameOf(device: String): String = _libraries.value.firstOrNull { it.device == device }?.name ?: "another device"

    // ---------------------------------------------------------------- with the host

    /**
     * Linked (again): checks the host offers the household's games, starts this device's server,
     * and catches up on everything. Called by Fuse Sync whenever it reaches its host.
     */
    suspend fun connected() {
        val c = client() ?: return off()
        val hello = runCatching { c.helloNow() }.getOrNull() ?: return
        val ok = SyncApi.FEATURE_HOUSEHOLD in hello.features
        _supported.value = ok
        if (!ok) return off()
        // The server for other devices starts with the first list of games (see publish), so a
        // device with nothing to share yet doesn't keep one open.
        libraryChanged()
        runCatching { refresh() }
        // Who is around, kept fresh: a device that went away shows so within a minute.
        if (lookJob?.isActive != true) lookJob = scope.launch(Dispatchers.IO) {
            while (isActive && _supported.value) {
                delay(LOOK_MS)
                runCatching { refresh() }
            }
        }
        runCatching { refreshCommands() }
        runCatching { takeInbox() }
        runCatching { sendPieces() }
    }

    /** Unlinked, or Fuse Sync off: nothing more is served. What was kept stays for a later link. */
    fun off() {
        _supported.value = false
        lookJob?.cancel()
        server.stop()
        // Its client for other devices goes too: each one keeps a network thread of its own.
        synchronized(this) { httpClient?.close(); httpClient = null }
    }

    /** The host said these are waiting (see [JournalPage.wake]). */
    fun woken(what: Collection<String>) {
        if (what.isEmpty() || !_supported.value) return
        scope.launch(Dispatchers.IO) {
            if (HouseholdHost.WAKE_RELAY in what) runCatching { sendPieces() }
            if (HouseholdHost.WAKE_COMMANDS in what) {
                runCatching { takeInbox() }
                runCatching { refreshCommands() }
            }
            if (HouseholdHost.WAKE_LIBRARY in what) runCatching { refresh() }
        }
    }

    // ---------------------------------------------------------------- this device's list

    private var publishJob: Job? = null
    private var hashJob: Job? = null
    private val publishing = Mutex()

    override fun libraryChanged() {
        publishJob?.cancel()
        publishJob = scope.launch(Dispatchers.IO) {
            delay(PUBLISH_DELAY_MS)
            runCatching { publish() }
            startHashing()
        }
    }

    /**
     * Lists this device's games (or none, when it doesn't share) for the household. [rebuild] asks
     * the app for its games again; otherwise the last list goes up with the hashes read since.
     */
    suspend fun publish(rebuild: Boolean = true) = publishing.withLock {
        val l = local ?: return
        val c = client() ?: return
        if (!_supported.value) return
        val cfg = config()
        val games = when {
            !cfg.shareLibrary -> emptyList()
            rebuild || shared.isEmpty() -> runCatching { l.games() }.getOrDefault(emptyList())
            else -> shared.values.toList()
        }
        shared = games.associateBy { it.entry.game }
        if (cfg.shareLibrary) runCatching { server.start(peerPort) } else server.stop()
        val entries = games.map { g -> withHashes(g) }
        _mine.value = entries
        val endpoint = if (cfg.shareLibrary && server.port > 0) PeerEndpoint(bindAddresses(), server.port) else null
        val body = DeviceLibrary(device = selfId(), entries = entries, endpoint = endpoint, accepts = cfg.acceptSends)
        val version = SyncCrypto.sha256(json.encodeToString(DeviceLibrary.serializer(), body).toByteArray()).take(24)
        if (version == published) return
        c.publishLibrary(body.copy(version = version))
        published = version
    }

    private fun withHashes(g: SharedGame): LibraryEntry =
        g.entry.copy(files = g.entry.files.map { f -> g.paths[f.path]?.let { hashes.known(File(it)) }?.let { (sha1, md5) -> f.copy(sha1 = sha1, md5 = md5) } ?: f })

    /** Reads the files not yet hashed, a file at a time, pausing while a game is played; lists again as it goes. */
    private fun startHashing() {
        if (hashJob?.isActive == true) return
        hashJob = scope.launch(Dispatchers.IO) {
            var since = clock()
            // Games listed while this runs are read too: it goes round until every shared file has
            // been read once (a file that can't be read isn't tried again until the next change).
            val tried = HashSet<String>()
            fun unread(path: String) = path !in tried && File(path).let { it.isFile && hashes.known(it) == null }
            while (true) {
                val pending = shared.values.toList().filter { g -> g.paths.values.any(::unread) }
                if (pending.isEmpty()) break
                for (g in pending) {
                    for (path in g.paths.values) {
                        while (busy()) delay(5_000)
                        if (!isActive) return@launch
                        if (!unread(path)) continue
                        tried += path
                        runCatching { hashes.compute(File(path)) }
                        yield()
                    }
                    if (clock() - since > REPUBLISH_MS) {
                        hashes.save()
                        runCatching { publish(rebuild = false) }
                        since = clock()
                    }
                }
            }
            hashes.save()
            runCatching { publish(rebuild = false) }
        }
    }

    private suspend fun resolve(game: String, file: String): File? {
        val g = shared[game] ?: return null
        if (file !in g.entry.files.map { it.path }) return null
        return g.paths[file]?.let(::File)?.also(::hashSoon)
    }

    private val urgent = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /**
     * A file another device is fetching is read for its hashes straight away (if it wasn't yet), so
     * the device fetching it can check what it got without waiting for the slow pass over everything.
     */
    private fun hashSoon(f: File) {
        if (hashes.known(f) != null || !urgent.add(f.path)) return
        scope.launch(Dispatchers.IO) {
            try {
                runCatching { hashes.compute(f) }
                hashes.save()
                runCatching { publish(rebuild = false) }
            } finally {
                urgent.remove(f.path)
            }
        }
    }

    // ---------------------------------------------------------------- the others' lists

    override suspend fun refresh() {
        val c = client() ?: return
        if (!_supported.value) return
        val have = _libraries.value.associate { it.device to it.version }
        val page = c.libraries(have)
        val present = page.devices.toSet()
        val byDevice = _libraries.value.associateBy { it.device }.toMutableMap()
        byDevice.keys.retainAll(present)
        for (l in page.changed) byDevice[l.device] = l
        if (page.seen.isNotEmpty()) _seen.value = page.seen
        _libraries.value = byDevice.values.sortedBy { it.name.lowercase() }
        keep()
    }

    private fun keep() = runCatching {
        dir.mkdirs()
        writeAtomically(keptFile, json.encodeToString(KeptLibraries.serializer(), KeptLibraries(_libraries.value, _commands.value)).toByteArray())
    }

    // ---------------------------------------------------------------- requests

    override suspend fun ask(command: DeviceCommand): Result<DeviceCommand> = runCatching {
        val c = client() ?: throw SyncException("This device isn't linked to a host.", "no-link", 0)
        if (!_supported.value) throw SyncException("Update Fuse on the host computer to send games between devices.", "unsupported", 0)
        val made = c.ask(command)
        _commands.update { list -> listOf(made) + list.filter { it.id != made.id } }
        keep()
        made
    }

    override suspend fun cancel(command: String): Result<Unit> = runCatching {
        val c = client() ?: throw SyncException("This device isn't linked to a host.", "no-link", 0)
        val done = c.cancelCommand(command)
        _commands.update { list -> list.map { if (it.id == done.id) done else it } }
        keep()
    }

    private val commandsLock = Mutex()

    /**
     * The household's requests as the host has them now. One at a time: each wake starts one, and
     * an older answer arriving after a newer one would put a request back as it was (a game that
     * came over would look still on its way).
     */
    private suspend fun refreshCommands() {
        commandsLock.withLock {
            val c = client() ?: return
            _commands.value = c.commands()
            keep()
        }
    }

    private val inboxLock = Mutex()

    /** Does what other devices asked of this one, each once, and says how it went. */
    private suspend fun takeInbox() = inboxLock.withLock {
        val c = client() ?: return
        val l = local ?: return
        for (cmd in c.inbox()) {
            if (cmd.state != DeviceCommand.PENDING && cmd.state != DeviceCommand.DELIVERED) continue
            val result = try {
                l.perform(cmd)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                CommandResult(DeviceCommand.FAILED, e.message ?: "It couldn't be done.")
            }
            runCatching { c.ack(cmd.id, CommandAck(result.state, result.message)) }
        }
    }

    /** Settles a request this device was working on (a game it was asked for arrived, or couldn't). */
    override suspend fun settle(command: String, state: String, message: String?) {
        runCatching { client()?.ack(command, CommandAck(state, message)) }
    }

    // ---------------------------------------------------------------- pieces through the host

    private val relayLock = Mutex()

    /** Sends the pieces of games the host asked for, when this device lets games pass through it. */
    private suspend fun sendPieces() = relayLock.withLock {
        val c = client() ?: return
        if (!config().relay || !config().shareLibrary) return
        for (ask in c.relayAsks()) {
            if (ask.ticket.source != selfId()) continue
            val f = resolve(ask.ticket.game, ask.file)?.takeIf { it.isFile } ?: continue
            if (ask.file !in ask.ticket.files) continue
            val size = f.length()
            val offset = ask.offset.coerceIn(0, size)
            val length = ask.length.takeIf { it > 0 }?.coerceAtMost(size - offset) ?: (size - offset).coerceAtMost(SyncApi.RELAY_PIECE)
            scope.launch(Dispatchers.IO) {
                local?.sending(nameOf(ask.ticket.requester))
                try {
                    c.relaySend(ask.id, length) { PeerFiles.open(f, offset, length) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // The one asking tries again (or another way).
                } finally {
                    local?.sending(null)
                }
            }
        }
    }

    // ---------------------------------------------------------------- fetching from others

    override suspend fun ticket(source: String, game: String, files: List<String>): PeerTicket = openTicket(source, game, files).ticket

    override suspend fun openTicket(source: String, game: String, files: List<String>): OpenTicket {
        val c = client() ?: throw SyncException("This device isn't linked to a host.", "no-link", 0)
        val t = c.ticket(TicketRequest(source, game, files))
        val key = SyncCrypto.open(t.sealedKey, c.link.deviceSecret, t.salt)?.decodeToString()
            ?: throw SyncException("The host's leave couldn't be opened.", "seal", 0)
        return OpenTicket(t.claims(), key)
    }

    override suspend fun reachable(ticket: PeerTicket): Boolean {
        val endpoint = ticket.endpoint?.takeIf { it.port > 0 } ?: return false
        return endpoint.addresses.take(4).any { address -> answers(address, endpoint.port, ticket.source) }
    }

    private val reached = java.util.concurrent.ConcurrentHashMap<String, String>()

    private suspend fun answers(address: String, port: Int, device: String): Boolean = runCatching {
        val resp = http.get("http://" + PeerFiles.hostPort(address, port) + SyncApi.PEER_BASE + "/hello")
        val ok = resp.status == HttpStatusCode.OK && resp.bodyAsText().contains("\"$device\"")
        if (ok) reached[device] = address
        ok
    }.getOrDefault(false)

    override suspend fun fetch(open: OpenTicket, file: String, offset: Long, length: Long, direct: Boolean, write: suspend (ByteArray, Int, Long?) -> Unit): Long =
        fetchStream(open, file, offset, length, direct) { input, size ->
            val buf = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                if (n == 0) continue
                write(buf, n, size)
                total += n
            }
            total
        }

    /** As [fetch], reading the piece as a stream. */
    suspend fun fetchStream(open: OpenTicket, file: String, offset: Long, length: Long, direct: Boolean, sink: suspend (InputStream, Long?) -> Long): Long {
        val t = open.ticket
        if (!direct) {
            val c = client() ?: throw SyncException("This device isn't linked to a host.", "no-link", 0)
            return c.relay(t.id, file, offset, length.coerceAtMost(SyncApi.RELAY_PIECE), sink)
        }
        val endpoint = t.endpoint ?: throw SyncException("That device can't be reached directly.", "unreachable", 0)
        val address = reached[t.source] ?: endpoint.addresses.firstOrNull() ?: throw SyncException("That device can't be reached directly.", "unreachable", 0)
        val time = clock()
        val nonce = SyncCrypto.token(18)
        val url = "http://" + PeerFiles.hostPort(address, endpoint.port) + SyncApi.PEER_BASE + "/file?" + PeerFiles.query(t.game, file, offset, length)
        return http.prepareGet(url) {
            header(PeerSigning.TICKET, PeerSigning.header(t, json))
            header(RequestSigning.TIME, time.toString())
            header(RequestSigning.NONCE, nonce)
            header(RequestSigning.SIGNATURE, PeerSigning.sign(open.key, t.game, file, offset, length, time, nonce))
        }.execute { resp ->
            when (resp.status) {
                HttpStatusCode.OK -> Unit
                HttpStatusCode.Forbidden -> throw SyncException("That device refused: ask the host again.", "ticket", 403)
                HttpStatusCode.Gone -> throw SyncException("That device doesn't have this file any more.", "gone", 410)
                else -> throw java.io.IOException("That device answered ${resp.status.value}.")
            }
            val size = resp.headers[SyncHost.PEER_SIZE]?.toLongOrNull()
            withContext(Dispatchers.IO) { resp.bodyAsChannel().toInputStream().use { sink(it, size) } }
        }
    }

    // ---------------------------------------------------------------- every device's transfers

    private var watchJob: Job? = null
    private var postJob: Job? = null
    @Volatile private var lastPost = 0L

    override fun watchTransfers(on: Boolean) {
        watchJob?.cancel()
        if (!on) return
        watchJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                if (_supported.value) runCatching { client()?.transfers()?.let { _transfers.value = it } }
                delay(WATCH_MS)
            }
        }
    }

    override fun transfersChanged() {
        if (postJob?.isActive == true) return
        postJob = scope.launch(Dispatchers.IO) {
            val wait = (POST_EVERY_MS - (clock() - lastPost)).coerceAtLeast(0)
            delay(wait)
            lastPost = clock()
            val c = client() ?: return@launch
            if (!_supported.value) return@launch
            val items = local?.transfers().orEmpty()
            runCatching { c.postTransfers(TransferSnapshot(selfId(), items = items)) }
        }
    }

    companion object {
        const val PUBLISH_DELAY_MS = 3_000L
        const val REPUBLISH_MS = 60_000L
        const val WATCH_MS = 2_000L
        const val POST_EVERY_MS = 2_000L
        const val LOOK_MS = 30_000L
    }
}

/**
 * Each file's SHA-1 and MD5, read once in one pass and remembered by its path, size and time, so a
 * file is read again only when it changed.
 */
internal class HashCache(private val file: File) {
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = MapSerializer(String.serializer(), String.serializer())
    private val map: MutableMap<String, String> = java.util.concurrent.ConcurrentHashMap(
        runCatching { json.decodeFromString(serializer, file.readText()) }.getOrDefault(emptyMap()),
    )
    @Volatile private var dirty = false

    private fun keyOf(f: File) = "${f.absolutePath}|${f.length()}|${f.lastModified()}"

    fun known(f: File): Pair<String, String>? = map[keyOf(f)]?.split(':')?.takeIf { it.size == 2 }?.let { it[0] to it[1] }

    fun compute(f: File): Pair<String, String> {
        val sha1 = MessageDigest.getInstance("SHA-1")
        val md5 = MessageDigest.getInstance("MD5")
        f.inputStream().use { input ->
            val buf = ByteArray(1 shl 20)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                sha1.update(buf, 0, n)
                md5.update(buf, 0, n)
            }
        }
        val out = hex(sha1.digest()) to hex(md5.digest())
        map[keyOf(f)] = "${out.first}:${out.second}"
        dirty = true
        return out
    }

    fun save() {
        if (!dirty) return
        dirty = false
        // Entries for files that changed or went are dropped as the file is written again.
        val live = map.filterKeys { k -> File(k.substringBefore('|')).exists() }
        runCatching {
            file.parentFile?.mkdirs()
            writeAtomically(file, json.encodeToString(serializer, live).toByteArray())
        }
    }

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
}
