package io.github.matiyaaa.fuse.sync

/** Remove pre-Convergence title bridges before using an offline cached household mapping. */
internal fun safeCachedGameAliases(aliases: Map<String, String>): Map<String, String> {
    fun strong(id: String): Boolean = GameKey.parse(id)?.identity?.let {
        it.startsWith("s.") || it.startsWith("h.") || it.startsWith("r.") || it.startsWith("p.")
    } == true
    val conflicting = aliases.entries.groupBy({ it.value }, { it.key }).filter { (canonical, members) ->
        (members + canonical).distinct().count { GameKey.parse(it)?.identity?.startsWith("s.") == true } > 1
    }.keys
    return aliases.filter { (from, to) ->
        val a = GameKey.parse(from)
        val b = GameKey.parse(to)
        a != null && b != null && a.platform == b.platform &&
            (from == to || (to !in conflicting && strong(from) == strong(to)))
    }
}
