package io.github.matiyaaa.fuse.sync

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondOutputStream
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/** Reading and naming pieces of a game's files, the same way for a device's server and the host passing them on. */
object PeerFiles {
    fun query(game: String, file: String, offset: Long, length: Long): String =
        "game=" + enc(game) + "&file=" + enc(file) + "&offset=$offset&length=$length"

    /** `address:port`, with an IPv6 address in brackets. */
    fun hostPort(address: String, port: Int): String = if (':' in address && !address.startsWith("[")) "[$address]:$port" else "$address:$port"

    /** [file] from [offset], at most [length] bytes, as a stream (closed by the reader). */
    fun open(file: File, offset: Long, length: Long): InputStream {
        val raf = RandomAccessFile(file, "r")
        raf.seek(offset)
        var left = length
        return object : InputStream() {
            override fun read(): Int {
                if (left <= 0) return -1
                val b = raf.read()
                if (b >= 0) left--
                return b
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (left <= 0) return -1
                val n = raf.read(b, off, minOf(len.toLong(), left).toInt())
                if (n > 0) left -= n
                return n
            }

            override fun close() = raf.close()
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}

/**
 * This device's games, served to the other devices of the household on the home network while
 * Fuse runs. A request names a game and one of its files as the device listed them; the file is
 * found by that listing ([resolve]), never by a path from the request, so nothing outside a shared
 * game can be read. Each request carries a ticket from the host and is signed with the ticket's key
 * (see [PeerSigning]); a request seen before, out of time, or for anything the ticket doesn't
 * cover is refused.
 */
class PeerServer(
    private val self: () -> String,
    /** This device's secret with its host; null while it has none. */
    private val secret: () -> String?,
    /** Where [file] of [game] is on this device, if it shares that game and that file. */
    private val resolve: suspend (game: String, file: String) -> File?,
    /** A device is fetching from here (its name, or null when done), so Android keeps Fuse awake. */
    private val serving: (String?) -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis,
    private val bind: String = "0.0.0.0",
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private var server: EmbeddedServer<*, *>? = null
    private val nonces = ConcurrentHashMap<String, Long>()
    private val active = java.util.concurrent.atomic.AtomicInteger(0)

    @Volatile var port: Int = 0
        private set

    /** Starts on [preferred], else the next free port, else any. The port it serves on. */
    suspend fun start(preferred: Int = SyncApi.PEER_PORT): Int {
        if (server != null) return port
        for (candidate in listOf(preferred, preferred + 1, preferred + 2, 0)) {
            // The server binds its port only after starting, and a port another program holds
            // would fail there, out of reach: so a port is taken only once it is known to be free.
            val free = freePort(candidate) ?: continue
            val s = runCatching { embeddedServer(CIO, port = free, host = bind) { routing { api() } }.also { it.start(wait = false) } }.getOrNull() ?: continue
            val bound = runCatching { withTimeoutOrNull(5_000) { s.engine.resolvedConnectors().firstOrNull()?.port } }.getOrNull()
            if (bound == null || bound != free) {
                runCatching { s.stop(0, 200) }
                continue
            }
            server = s
            port = bound
            return port
        }
        return 0
    }

    /** [candidate] (or, for 0, a port the system picks) if nothing listens on it here, else null. */
    private fun freePort(candidate: Int): Int? = runCatching {
        java.net.ServerSocket().use { socket ->
            // Not shared: on Windows a shared address would bind even where another program listens.
            socket.reuseAddress = false
            socket.bind(java.net.InetSocketAddress(bind, candidate))
            socket.localPort
        }
    }.getOrNull()

    fun stop() {
        server?.stop(200, 1_000)
        server = null
        port = 0
    }

    /**
     * Whether [ticket] lets its holder have [file] of [game] from here now, and [signature] over the
     * request, [time] and [nonce] was made with its key. A nonce is taken only when all else holds.
     */
    fun allows(ticket: PeerTicket, game: String, file: String, offset: Long, length: Long, time: Long, nonce: String, signature: String): Boolean {
        val mine = secret() ?: return false
        val now = clock()
        if (ticket.source != self() || ticket.game != game || file !in ticket.files || ticket.until < now) return false
        if (kotlin.math.abs(now - time) > RequestSigning.WINDOW_MS || nonce.length !in 16..64) return false
        val expected = PeerSigning.sign(PeerSigning.key(mine, ticket), game, file, offset, length, time, nonce)
        if (!SyncCrypto.constantEquals(expected, signature)) return false
        if (nonces.putIfAbsent("${ticket.id}/$nonce", now) != null) return false
        if (nonces.size > 20_000) nonces.entries.removeIf { now - it.value > RequestSigning.WINDOW_MS * 2 }
        return true
    }

    private fun io.ktor.server.routing.Route.api() {
        get(SyncApi.PEER_BASE + "/hello") {
            call.respondText("{\"device\":\"${self()}\"}", ContentType.Application.Json)
        }
        get(SyncApi.PEER_BASE + "/file") {
            val h = call.request.headers
            val q = call.request.queryParameters
            val ticket = h[PeerSigning.TICKET]?.let { PeerSigning.fromHeader(it, json) }
            val game = q["game"].orEmpty()
            val file = q["file"].orEmpty()
            val time = h[RequestSigning.TIME]?.toLongOrNull()
            val nonce = h[RequestSigning.NONCE].orEmpty()
            val sig = h[RequestSigning.SIGNATURE].orEmpty()
            val askedOffset = q["offset"]?.toLongOrNull() ?: 0L
            val askedLength = q["length"]?.toLongOrNull() ?: Long.MAX_VALUE
            if (ticket == null || time == null || !allows(ticket, game, file, askedOffset, askedLength, time, nonce, sig)) {
                return@get call.respondText("{\"error\":\"Not allowed.\",\"code\":\"forbidden\"}", ContentType.Application.Json, HttpStatusCode.Forbidden)
            }
            val local = resolve(game, file)?.takeIf { it.isFile }
                ?: return@get call.respondText("{\"error\":\"That file isn't here any more.\",\"code\":\"gone\"}", ContentType.Application.Json, HttpStatusCode.Gone)
            val size = local.length()
            val offset = askedOffset.coerceIn(0, size)
            val length = askedLength.coerceIn(0, size - offset)
            call.response.headers.append(SyncHost.PEER_SIZE, size.toString())
            call.response.headers.append(SyncHost.PEER_OFFSET, offset.toString())
            if (active.incrementAndGet() == 1) serving(ticket.requester)
            try {
                call.respondOutputStream(ContentType.Application.OctetStream) {
                    withContext(Dispatchers.IO) { PeerFiles.open(local, offset, length).use { it.copyTo(this@respondOutputStream, 64 * 1024) } }
                }
            } finally {
                if (active.decrementAndGet() == 0) serving(null)
            }
        }
    }
}
