package io.github.matiyaaa.fuse.romm

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** A RomM server that answered on this network: the address to keep and the version it said. */
data class FoundRomm(val address: String, val version: String)

/**
 * Finds RomM servers on this network, the way Fuse Sync and Jellyfin find theirs. RomM announces
 * nothing to listen for, so Fuse asks the likely places instead: first the computers already known to
 * run a server for Fuse (Jellyfin's or Fuse Sync's host, since RomM often lives beside them), then
 * RomM's usual names, then every neighbour on this network. Whatever accepts a connection on RomM's
 * usual ports is asked for RomM's heartbeat, and only what answers as RomM is kept.
 */
object RommDiscovery {
    /** RomM's own port, then the ones a reverse proxy puts it on. */
    val PORTS = listOf(8080, 80, 443)

    /** Names a RomM server is often given on a home network. */
    val NAMES = listOf("romm.local", "romm.lan", "romm.home", "romm")

    private const val ASKING_AT_ONCE = 8

    /**
     * The RomM servers found, the [hints]' first. [listen] lists what accepts a connection (see
     * [listening]); [ask] asks an address for RomM's heartbeat and gives the version, or null.
     */
    suspend fun find(
        hints: List<String>,
        listen: suspend (List<String>, List<Int>) -> List<Pair<String, Int>> = { hosts, ports -> listening(hosts, ports, neighbours = true) },
        ask: suspend (String) -> String?,
    ): List<FoundRomm> = coroutineScope {
        val hosts = (hints.mapNotNull(::hostOf) + NAMES).distinct()
        val open = listen(hosts, PORTS)
        val gate = Semaphore(ASKING_AT_ONCE)
        open.map { (host, port) ->
            async { gate.withPermit { addressFor(host, port).let { a -> ask(a)?.let { v -> Triple(host, a, v) } } } }
        }.awaitAll().filterNotNull()
            // One server answering on two ports (RomM and its proxy) is one server.
            .distinctBy { it.first }
            .map { FoundRomm(it.second, it.third) }
    }

    /** The host part of an address as typed: "http://192.168.1.20:8096/jf" is 192.168.1.20. */
    fun hostOf(address: String): String? {
        val rest = address.trim().substringAfter("://").substringBefore('/').substringBefore('?').substringAfterLast('@')
        val host = if (rest.startsWith("[")) rest.substringBefore(']').removePrefix("[") else rest.substringBefore(':')
        return host.trim().lowercase().takeIf { it.isNotEmpty() && it != "localhost" && !it.startsWith("127.") }
    }

    /** How Fuse writes a found server's address: no port for the web's own, http on the home network. */
    fun addressFor(host: String, port: Int): String = when (port) {
        443 -> "https://$host"
        80 -> "http://$host"
        else -> "http://$host:$port"
    }
}

/**
 * Hosts and ports that accept a connection: [hosts] first (names resolved), then, when [neighbours],
 * every address on this device's home networks that isn't one of them. Never sends anything.
 */
expect suspend fun listening(hosts: List<String>, ports: List<Int>, neighbours: Boolean): List<Pair<String, Int>>
