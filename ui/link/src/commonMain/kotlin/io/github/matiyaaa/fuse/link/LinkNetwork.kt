package io.github.matiyaaa.fuse.link

/**
 * Who may talk to Phone Link: only devices on the local network, and only through an address, never
 * a domain name (so a web page elsewhere can't reach the device through DNS tricks).
 */
internal object LinkNetwork {

    /** True for loopback, private (10/8, 172.16/12, 192.168/16), link-local, and IPv6 local ranges. */
    fun isLocal(address: String): Boolean {
        val a = address.trim().removePrefix("/").substringBefore('%').removeSurrounding("[", "]")
        ipv4(a.removePrefix("::ffff:").removePrefix("::FFFF:"))?.let { (b0, b1) ->
            return b0 == 10 || b0 == 127 || (b0 == 172 && b1 in 16..31) || (b0 == 192 && b1 == 168) || (b0 == 169 && b1 == 254)
        }
        if (!a.contains(':')) return false
        val lower = a.lowercase()
        if (lower == "::1" || lower == "0:0:0:0:0:0:0:1") return true
        val first = lower.substringBefore(':').ifEmpty { "0" }.toIntOrNull(16) ?: return false
        return (first and 0xfe00) == 0xfc00 || (first and 0xffc0) == 0xfe80
    }

    /**
     * True when the Host header names this device by address ("192.168.1.20:47300", "[fe80::1]",
     * "localhost"); a domain name is refused.
     */
    fun isAddressHost(host: String?): Boolean {
        if (host.isNullOrBlank()) return false
        val h = host.trim()
        val name = if (h.startsWith("[")) h.substringAfter('[').substringBefore(']') else h.substringBeforeLast(':').takeIf { h.count { it == ':' } == 1 } ?: h
        if (name.equals("localhost", ignoreCase = true)) return true
        if (ipv4(name) != null) return true
        return name.contains(':') && name.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it == ':' || it == '.' || it == '%' }
    }

    /** The first two octets of a dotted IPv4 address, or null. */
    private fun ipv4(a: String): Pair<Int, Int>? {
        val parts = a.split('.')
        if (parts.size != 4) return null
        val n = parts.map { p -> p.toIntOrNull()?.takeIf { p.isNotEmpty() && p.length <= 3 && it in 0..255 } ?: return null }
        return n[0] to n[1]
    }
}
