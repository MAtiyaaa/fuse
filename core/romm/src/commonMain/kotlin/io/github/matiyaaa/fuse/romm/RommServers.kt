package io.github.matiyaaa.fuse.romm

/**
 * Which RomM server Fuse is set up for, as the key everything kept for one server goes under: its
 * mirror, the changes and saves waiting to go to it, what Fuse last agreed with it about each save.
 * RomM doesn't name itself, so a server is known by its addresses: one that keeps an address it had
 * (a Local address with an Outside one added, Auto turned on) is the same server and keeps its key;
 * addresses Fuse had for another server give that server's key back; new ones get a new key. What
 * was kept for one server is then never sent to, or taken for, another.
 */
object RommServers {
    /** The key Fuse used before servers had keys of their own; kept by the server set up then. */
    const val FIRST = "main"

    /** An address as it is compared: no spaces, no trailing slash, any case. */
    fun normalise(address: String): String = address.trim().trimEnd('/').lowercase()

    /**
     * The key after the addresses went from [before] to [now], [current] being the key in use, and
     * [known] each address's key from earlier; with [known] as it is after (the addresses in use
     * now under the key returned). No address now keeps [current], as does a key that never had an
     * address (the first server set up).
     */
    fun after(current: String, before: List<String>, now: List<String>, known: Map<String, String>): Pair<String, Map<String, String>> {
        val next = now.map(::normalise).filter { it.isNotEmpty() }.distinct()
        val had = before.map(::normalise).filter { it.isNotEmpty() }
        val remembered = known + had.associateWith { current }
        if (next.isEmpty()) return current to remembered
        val key = when {
            next.any { it in had } -> current
            else -> next.firstNotNullOfOrNull { remembered[it] }
                ?: if (current in remembered.values) fresh(next.first(), remembered.values.toSet() + current) else current
        }
        return key to (remembered + next.associateWith { key })
    }

    /** A new key made from [address], never one of [taken]. */
    private fun fresh(address: String, taken: Set<String>): String {
        // FNV-1a, 64 bits: the same address gives the same key on every device.
        var h = -0x340d631b7bdddcdbL
        for (b in address.encodeToByteArray()) {
            h = h xor (b.toLong() and 0xff)
            h *= 0x100000001b3L
        }
        val base = "server-" + h.toULong().toString(16)
        return generateSequence(1) { it + 1 }.map { if (it == 1) base else "$base-$it" }.first { it !in taken }
    }
}
