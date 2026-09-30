package io.github.matiyaaa.fuse.library.series

/** A game as the series detector sees it: its shown title and the series its details name, if any. */
data class SeriesInput<K>(val key: K, val title: String, val franchise: String? = null)

/** A series found in the library. [fromDetails] is true when it came from game details (IGDB, RomM). */
data class DetectedSeries<K>(val name: String, val members: List<K>, val fromDetails: Boolean)

/**
 * Finds game series in a library for automatic collections.
 *
 * Games whose details name a series (IGDB franchises, RomM) are grouped by it when two or more share
 * it. Games without one are grouped by the start of their titles when three or more share it:
 * "Super Mario World", "Super Mario 64" and "Super Mario Sunshine" make "Super Mario". A shared
 * start of two or three words wins over one word, a start never ends on a small word ("Legend of"
 * waits for "Legend of Zelda"), and single common words ("Super", "Dragon") are never a series.
 */
object SeriesDetector {
    const val MIN_FROM_DETAILS = 2
    const val MIN_FROM_TITLES = 3
    private const val LONGEST_PREFIX = 3

    /** Words that never end a series name. */
    private val joiners = setOf("of", "the", "and", "a", "an", "in", "on", "to", "for", "de", "no", "la", "le", "vs", "&")

    /** Words too common to be a series on their own. */
    private val common = setOf(
        "super", "new", "final", "star", "dragon", "world", "legend", "legends", "tales", "battle", "war", "wars",
        "space", "dead", "dark", "death", "big", "little", "ultimate", "pro", "sports", "real", "grand", "great",
        "mega", "hyper", "ultra", "power", "magic", "mystery", "shadow", "night", "call", "dance", "game", "games",
        "classic", "collection", "deluxe", "edition", "puzzle", "racing", "soccer", "football", "golf", "tennis",
        "my", "your", "our", "dr", "mr", "ms", "adventure", "adventures", "quest", "story", "kingdom", "hero",
        "heroes", "king", "prince", "princess", "castle", "city", "street", "fighter", "fighting", "black", "white",
        "red", "blue", "green", "yellow", "gold", "silver", "crystal", "blood", "fire", "ice", "sky", "sea", "moon",
        "sun", "planet", "galaxy", "time", "last", "first", "one", "two", "three",
    )

    fun <K> detect(games: List<SeriesInput<K>>): List<DetectedSeries<K>> {
        val out = ArrayList<DetectedSeries<K>>()
        val grouped = HashSet<K>()

        // 1. Series named in game details.
        games.filter { !it.franchise.isNullOrBlank() }
            .groupBy { normalizeName(it.franchise!!) }
            .filter { (_, members) -> members.size >= MIN_FROM_DETAILS }
            .forEach { (_, members) ->
                val name = members.groupingBy { it.franchise!!.trim() }.eachCount().maxByOrNull { it.value }!!.key
                out += DetectedSeries(name, members.map { it.key }, fromDetails = true)
                grouped += members.map { it.key }
            }

        // 2. Shared title starts, for the rest.
        val rest = games.filter { it.key !in grouped }
        val words = rest.associate { it.key to words(it.title) }
        val counts = HashMap<String, Int>()
        for (w in words.values) for (k in 1..minOf(LONGEST_PREFIX, w.size)) counts.merge(w.take(k).joinToString(" "), 1, Int::plus)
        fun qualifies(prefix: List<String>): Boolean {
            if (prefix.last() in joiners || prefix.first() in joiners) return false
            if (prefix.size == 1 && (prefix[0] in common || prefix[0].length < 4 || prefix[0].all(Char::isDigit))) return false
            if (prefix.any { it.all(Char::isDigit) }) return false
            return (counts[prefix.joinToString(" ")] ?: 0) >= MIN_FROM_TITLES
        }
        // The shortest start of two or more words that is shared, else a distinctive single word.
        val assigned = rest.mapNotNull { g ->
            val w = words.getValue(g.key)
            val prefix = (2..minOf(LONGEST_PREFIX, w.size)).map { w.take(it) }.firstOrNull(::qualifies)
                ?: w.take(1).takeIf { it.isNotEmpty() && qualifies(it) }
            prefix?.let { g to it.joinToString(" ") }
        }.groupBy({ it.second }, { it.first })
        for ((prefix, members) in assigned) {
            if (members.size < MIN_FROM_TITLES) continue
            val name = displayName(prefix, members.map { it.title })
            // A title group named like a details series joins it.
            val match = out.indexOfFirst { normalizeName(it.name) == normalizeName(name) }
            if (match >= 0) {
                out[match] = out[match].copy(members = out[match].members + members.map { it.key })
            } else {
                out += DetectedSeries(name, members.map { it.key }, fromDetails = false)
            }
        }
        return out.sortedBy { it.name.lowercase() }
    }

    /** Lower-case words of a title, without "the" in front, punctuation or bracketed tags. */
    internal fun words(title: String): List<String> {
        val plain = title
            .replace(Regex("""\([^)]*\)|\[[^\]]*\]"""), " ")
            .lowercase()
            .replace(Regex("""[:\-\u2013\u2014_/.,!?'\u2019"]"""), " ")
        val w = plain.split(Regex("""\s+""")).filter { it.isNotEmpty() }
        return if (w.firstOrNull() == "the") w.drop(1) else w
    }

    /** How the first member spells the shared start, with "The" when every member has it. */
    private fun displayName(prefix: String, titles: List<String>): String {
        val count = prefix.split(' ').size
        val first = titles.first()
        val original = first
            .replace(Regex("""[:\-\u2013\u2014_/.,!?"]"""), " ")
            .split(Regex("""\s+"""))
            .filter { it.isNotEmpty() }
        val the = titles.all { it.trimStart().startsWith("The ", ignoreCase = true) }
        val body = (if (original.firstOrNull()?.equals("the", ignoreCase = true) == true) original.drop(1) else original).take(count)
        return (if (the) listOf("The") + body else body).joinToString(" ")
    }

    private fun normalizeName(name: String): String = words(name).joinToString(" ")
}
