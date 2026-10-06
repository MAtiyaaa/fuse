package io.github.matiyaaa.fuse.integrations.stream

import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException

/**
 * What a streaming host (Sunshine, Apollo, or GeForce Experience) says about itself on its open
 * GameStream port, without pairing: its name, its id (Moonlight knows the computer by it), its
 * network card when it shares it, and whether it is streaming something now.
 */
data class StreamServerInfo(
    val name: String,
    val uniqueId: String,
    /** The network card's address for Wake-on-LAN, when the host shares it (some only tell paired clients). */
    val mac: String?,
    val localAddress: String?,
    /** The app streaming now (0 when none). */
    val runningApp: Int,
    val busy: Boolean,
    /** "Sunshine" style servers report a version; GeForce Experience a "MJOLNIR" state. */
    val version: String?,
)

/**
 * The GameStream protocol's open part, as Moonlight reads it (NvHTTP.getComputerDetails): HTTP on
 * port 47989, `serverinfo` answered as XML. Fuse only reads it; it never pairs, never changes the
 * host and never touches Sunshine's settings. Moonlight does the streaming and its own pairing.
 */
class GameStream(private val http: HttpClient) {
    /** The host at [address] (a name or IP, optionally with a port), or null when it doesn't answer in [timeoutMs]. */
    suspend fun serverInfo(address: String, timeoutMs: Long = 2_500): StreamServerInfo? {
        val url = "http://${hostPort(address)}/serverinfo?uniqueid=0123456789ABCDEF&uuid=${randomHex(32)}"
        return try {
            val resp = http.get(url) { timeout { requestTimeoutMillis = timeoutMs; connectTimeoutMillis = timeoutMs } }
            if (!resp.status.isSuccess()) null else parse(resp.bodyAsText())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        const val HTTP_PORT = 47989

        /** [address] with GameStream's port when it names none (an IPv6 address in brackets keeps its own). */
        fun hostPort(address: String): String {
            val a = address.trim().removePrefix("http://").removePrefix("https://").trimEnd('/')
            val hasPort = if (a.startsWith("[")) a.contains("]:") else a.count { it == ':' } == 1
            return if (hasPort) a else "$a:$HTTP_PORT"
        }

        /** Reads a `serverinfo` answer; null when it isn't one (no `uniqueid`). */
        fun parse(xml: String): StreamServerInfo? {
            fun tag(name: String): String? = Regex("<$name>(.*?)</$name>", RegexOption.DOT_MATCHES_ALL).find(xml)?.groupValues?.get(1)?.trim()
            val id = tag("uniqueid")?.takeIf { it.isNotEmpty() } ?: return null
            val mac = tag("mac")?.takeIf { WakeOnLan.isMac(it) && it.replace(Regex("[:-]"), "").any { c -> c != '0' } }
            val state = tag("state").orEmpty()
            return StreamServerInfo(
                name = tag("hostname")?.takeIf { it.isNotEmpty() } ?: "Computer",
                uniqueId = id,
                mac = mac,
                localAddress = tag("LocalIP"),
                runningApp = tag("currentgame")?.toIntOrNull() ?: 0,
                busy = state.endsWith("_SERVER_BUSY"),
                version = tag("appversion"),
            )
        }

        private fun randomHex(n: Int): String = buildString { repeat(n) { append("0123456789abcdef"[kotlin.random.Random.nextInt(16)]) } }
    }
}

/** Wake-on-LAN's magic packet: six 0xFF, then the network card's address sixteen times. */
object WakeOnLan {
    private val MAC = Regex("^([0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}$")

    fun isMac(text: String): Boolean = MAC.matches(text.trim())

    /** The packet for [mac], or null when it isn't a network card's address. */
    fun packet(mac: String): ByteArray? {
        if (!isMac(mac)) return null
        val bytes = mac.trim().split(':', '-').map { it.toInt(16).toByte() }
        return ByteArray(6) { 0xFF.toByte() } + ByteArray(16 * 6) { bytes[it % 6] }
    }

    /** The ports a magic packet goes to, each of them, as Moonlight sends it (WakeOnLanSender). */
    val PORTS = listOf(9, 7, 47998, 47999, 48000, 48002, 48010)
}
