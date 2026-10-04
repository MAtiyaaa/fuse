package io.github.matiyaaa.fuse.jellyfin

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException

/**
 * Jellyfin's own discovery: "who is JellyfinServer?" broadcast to UDP port 7359 on every network
 * this device is on, and each server's answer (its name, address and id) collected for a moment.
 * [hold] keeps whatever the platform needs open meanwhile (Android's multicast lock).
 */
class UdpDiscovery(private val hold: () -> AutoCloseable? = { null }) : ServerDiscovery {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override suspend fun discover(timeoutMs: Long): List<DiscoveredServer> = withContext(Dispatchers.IO) {
        val held = runCatching { hold() }.getOrNull()
        try {
            DatagramSocket().use { socket ->
                socket.broadcast = true
                socket.soTimeout = 250
                val message = "who is JellyfinServer?".toByteArray()
                for (target in broadcastAddresses()) {
                    runCatching { socket.send(DatagramPacket(message, message.size, target, PORT)) }
                }
                val found = LinkedHashMap<String, DiscoveredServer>()
                val end = System.currentTimeMillis() + timeoutMs
                val buffer = ByteArray(2048)
                while (System.currentTimeMillis() < end) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        socket.receive(packet)
                    } catch (_: SocketTimeoutException) {
                        continue
                    }
                    val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
                    val reply = runCatching { json.decodeFromString(DiscoveryReplyDto.serializer(), text) }.getOrNull() ?: continue
                    val reported = reply.address?.trimEnd('/') ?: continue
                    // A server in Docker reports its container's address (172.18.0.3), which nothing
                    // else can reach: the address the answer came from is the one that works.
                    val address = reachable(reported, packet.address)
                    found[reply.id ?: address] = DiscoveredServer(reply.name ?: "Jellyfin", address, reply.id)
                }
                found.values.toList()
            }
        } catch (_: Exception) {
            emptyList()
        } finally {
            runCatching { held?.close() }
        }
    }

    private fun broadcastAddresses(): List<InetAddress> {
        val out = LinkedHashSet<InetAddress>()
        runCatching {
            for (nif in NetworkInterface.getNetworkInterfaces()) {
                if (!nif.isUp || nif.isLoopback) continue
                for (a in nif.interfaceAddresses) a.broadcast?.let { out += it }
            }
        }
        out += InetAddress.getByName("255.255.255.255")
        return out.toList()
    }

    companion object {
        const val PORT = 7359

        /** [reported], with its host swapped for [sender]'s when they differ (scheme, port and path kept). */
        internal fun reachable(reported: String, sender: InetAddress?): String {
            val host = sender?.hostAddress?.substringBefore('%') ?: return reported
            val shown = if (':' in host) "[$host]" else host
            val m = Regex("""^(https?://)(\[[^\]]+\]|[^:/]+)(.*)$""", RegexOption.IGNORE_CASE).find(reported) ?: return "http://$shown:8096"
            return m.groupValues[1] + shown + m.groupValues[3]
        }
    }
}
