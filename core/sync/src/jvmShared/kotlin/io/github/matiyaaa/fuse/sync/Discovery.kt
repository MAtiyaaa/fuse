package io.github.matiyaaa.fuse.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException

/**
 * A host found on the network: who it is, the address it answered from, and every address it
 * says it has (the one it answered from first). A computer with Docker or virtual machines can
 * answer from an address no other device reaches, so a device tries them in turn.
 */
data class FoundHost(val hello: HostHello, val address: String) {
    val candidates: List<String>
        get() = (listOf(address) + hello.addresses.map { "$it:${hello.port}" }).distinct()
}

/**
 * Finding a Fuse Sync Host at home without typing an address, the way Jellyfin and Steam find
 * theirs: a device broadcasts one short question on the local network and every host answers with
 * its name, id and port. Nothing about anyone's data crosses in either direction, and a host only
 * answers the question (it never sends anything unasked). Broadcasts don't leave the home network,
 * so a host away from home is reached by its address from outside instead.
 */
object Discovery {
    const val QUESTION = "FUSE_SYNC_WHO_IS_THERE v1"
    private val json = Json { ignoreUnknownKeys = true }

    /** Asks the network for hosts, collecting answers for [waitMs]. */
    suspend fun find(waitMs: Int = 1_500, port: Int = SyncApi.DISCOVERY_PORT): List<FoundHost> = withContext(Dispatchers.IO) {
        val found = LinkedHashMap<String, FoundHost>()
        runCatching {
            DatagramSocket().use { socket ->
                socket.broadcast = true
                socket.soTimeout = 250
                val question = QUESTION.toByteArray()
                for (target in broadcastAddresses()) {
                    runCatching { socket.send(DatagramPacket(question, question.size, target, port)) }
                }
                val until = System.currentTimeMillis() + waitMs
                val buf = ByteArray(2048)
                while (System.currentTimeMillis() < until) {
                    val packet = DatagramPacket(buf, buf.size)
                    try {
                        socket.receive(packet)
                    } catch (e: SocketTimeoutException) {
                        continue
                    }
                    val hello = runCatching { json.decodeFromString(HostHello.serializer(), String(packet.data, 0, packet.length)) }.getOrNull() ?: continue
                    val host = packet.address.hostAddress ?: continue
                    found.getOrPut(hello.hostId) { FoundHost(hello, "$host:${hello.port}") }
                }
            }
        }
        found.values.toList()
    }

    /** The limited broadcast address and each interface's own, so every home subnet hears. */
    private fun broadcastAddresses(): List<InetAddress> {
        val all = LinkedHashSet<InetAddress>()
        all += InetAddress.getByName("255.255.255.255")
        runCatching {
            for (nif in NetworkInterface.getNetworkInterfaces()) {
                if (!nif.isUp || nif.isLoopback) continue
                for (a in nif.interfaceAddresses) if (a.address is Inet4Address) a.broadcast?.let { all += it }
            }
        }
        return all.toList()
    }

    /**
     * Answers the question for a host until [stop] is called: replies to the asker only, with
     * [hello]. Returns a handle to stop it.
     */
    fun answer(hello: () -> HostHello, port: Int = SyncApi.DISCOVERY_PORT): Responder = Responder(hello, port).also { it.start() }

    class Responder internal constructor(private val hello: () -> HostHello, private val port: Int) {
        @Volatile private var socket: DatagramSocket? = null
        private var thread: Thread? = null

        internal fun start() {
            // The host's port, not the socket's own (unbound, it has none).
            val listenOn = InetSocketAddress(port)
            val s = runCatching { DatagramSocket(null as java.net.SocketAddress?).apply { reuseAddress = true; bind(listenOn) } }.getOrNull() ?: return
            socket = s
            thread = Thread({
                val buf = ByteArray(256)
                while (!s.isClosed) {
                    val packet = DatagramPacket(buf, buf.size)
                    try {
                        s.receive(packet)
                    } catch (e: Exception) {
                        if (s.isClosed) break else continue
                    }
                    if (String(packet.data, 0, packet.length).trim() != QUESTION) continue
                    val answer = json.encodeToString(HostHello.serializer(), hello().copy(addresses = LanAddresses.list())).toByteArray()
                    runCatching { s.send(DatagramPacket(answer, answer.size, packet.socketAddress)) }
                }
            }, "fuse-sync-discovery").apply {
                isDaemon = true
                start()
            }
        }

        val listening: Boolean get() = socket?.isClosed == false

        fun stop() {
            socket?.close()
            socket = null
        }
    }
}
