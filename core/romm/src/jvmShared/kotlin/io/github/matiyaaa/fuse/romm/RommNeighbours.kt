package io.github.matiyaaa.fuse.romm

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket

private const val CONNECT_MS = 350
private const val RESOLVE_MS = 1_500L

/** Two home networks at most (Wi-Fi and cable): a sweep stays a couple of seconds. */
private const val NETWORKS = 2

/** Interfaces only containers and virtual machines on this device can reach, and tunnels elsewhere. */
private val NOT_HOME = listOf(
    "docker", "br-", "veth", "virbr", "vmnet", "vboxnet", "virtualbox", "vmware", "hyper-v", "vethernet", "wsl", "podman",
    "cni", "flannel", "calico", "kube", "lxc", "lxd", "incus", "tailscale", "zerotier", "wg", "tun", "utun", "ppp", "vpn",
)

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
private val probes = Dispatchers.IO.limitedParallelism(128)

/** Lookups that never answer are left behind rather than waited on. */
private val lookups = CoroutineScope(SupervisorJob() + Dispatchers.IO)

actual suspend fun listening(hosts: List<String>, ports: List<Int>, neighbours: Boolean): List<Pair<String, Int>> = coroutineScope {
    val named = hosts.map { h -> async { resolve(h)?.let { h to it } } }.awaitAll().filterNotNull()
    val known = named.map { it.second.hostAddress }.toSet()
    val around = if (neighbours) homeNeighbours().filter { it !in known } else emptyList()
    val tries = named.flatMap { (h, a) -> ports.map { Triple(h, a.hostAddress, it) } } +
        around.flatMap { ip -> ports.map { Triple(ip, ip, it) } }
    tries.map { (label, ip, port) -> async(probes) { if (accepts(ip, port)) label to port else null } }.awaitAll().filterNotNull()
}

private suspend fun resolve(host: String): InetAddress? {
    val lookup = lookups.async { runCatching { InetAddress.getByName(host) }.getOrNull() }
    return withTimeoutOrNull(RESOLVE_MS) { lookup.await() }?.takeIf { it is Inet4Address && !it.isLoopbackAddress }
}

private fun accepts(ip: String, port: Int): Boolean = try {
    Socket().use { it.connect(InetSocketAddress(ip, port), CONNECT_MS); true }
} catch (e: Exception) {
    false
}

/** Every address on this device's home networks (a /24 around each of its own), this device's too: RomM may run here. */
private suspend fun homeNeighbours(): List<String> = withContext(Dispatchers.IO) {
    val own = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { runCatching { it.isUp && !it.isLoopback && !it.isVirtual }.getOrDefault(false) }
            .filter { nif -> val n = (nif.name + " " + (nif.displayName ?: "")).lowercase(); NOT_HOME.none { n.startsWith(it) || " $it" in n } }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .filter { it.isSiteLocalAddress }
            .mapNotNull { it.hostAddress }
    }.getOrDefault(emptyList())
    neighboursOf(own)
}

/** The addresses in the /24 networks around [own], best network first and [NETWORKS] at most. */
internal fun neighboursOf(own: List<String>): List<String> {
    val networks = own.map { it.substringBeforeLast('.') }.distinct()
        .sortedBy { if (it.startsWith("192.168.")) 0 else if (it.startsWith("10.")) 1 else 2 }
        .take(NETWORKS)
    return networks.flatMap { net -> (1..254).map { "$net.$it" } }
}
