package io.github.matiyaaa.fuse.sync

import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * This computer's addresses on the home network, best first, for other devices to reach its host.
 * A computer often has more than its Wi-Fi or cable: Docker, WSL, Hyper-V and virtual machines
 * make networks of their own (`172.19.0.1` and the like) that no other device can reach, and VPNs
 * add theirs. Those made for containers and virtual machines are left out; VPNs come last, since
 * they can still reach the host from outside.
 */
object LanAddresses {
    /** Interfaces only containers and virtual machines on this computer can reach. */
    private val PRIVATE_TO_THIS_COMPUTER = listOf(
        "docker", "br-", "veth", "virbr", "vmnet", "vboxnet", "virtualbox", "vmware", "hyper-v", "vethernet",
        "wsl", "podman", "cni", "flannel", "calico", "kube", "lxc", "lxd", "incus", "default switch", "npcap", "teredo", "isatap",
    )

    /** Interfaces that tunnel elsewhere: kept, after the home network's own. */
    private val TUNNELS = listOf("tailscale", "zerotier", "zt", "wg", "wireguard", "tun", "tap", "utun", "ppp", "vpn", "nordlynx", "proton", "mullvad")

    fun list(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
            .flatMap { nif -> nif.inetAddresses.toList().filterIsInstance<Inet4Address>().map { nif to it } }
            .mapNotNull { (nif, a) ->
                val names = (nif.name + " " + (nif.displayName ?: "")).lowercase()
                val ip = a.hostAddress ?: return@mapNotNull null
                when {
                    a.isLinkLocalAddress || a.isLoopbackAddress -> null
                    nif.isVirtual || PRIVATE_TO_THIS_COMPUTER.any { it in names } -> null
                    else -> Candidate(ip, tunnel = TUNNELS.any { names.startsWith(it) || " $it" in names })
                }
            }
            .sortedWith(compareBy({ it.tunnel }, { rank(it.ip) }))
            .map { it.ip }
            .distinct()
    }.getOrDefault(emptyList())

    private data class Candidate(val ip: String, val tunnel: Boolean)

    /** Home networks first (192.168, then 10), then the 172.16 range containers favour, then the rest. */
    internal fun rank(ip: String): Int = when {
        ip.startsWith("192.168.") -> 0
        ip.startsWith("10.") -> 1
        ip.startsWith("172.") && ip.split('.').getOrNull(1)?.toIntOrNull() in 16..31 -> 2
        else -> 3
    }
}
