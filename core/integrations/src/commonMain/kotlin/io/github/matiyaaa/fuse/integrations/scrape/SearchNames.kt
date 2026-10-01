package io.github.matiyaaa.fuse.integrations.scrape

import io.github.matiyaaa.fuse.model.ScrapeQuery

/**
 * The names a game is searched by, best first, for when its title finds nothing sure: the title,
 * the other names it is known by ([ScrapeQuery.alsoKnownAs]), then spellings of the title that
 * providers' searches treat differently: without its subtitle, with "&" and "and" swapped, with
 * Roman numerals as digits and digits as Roman numerals, without a leading "The" (or with a No-Intro
 * ", The" moved to the front), and without accents. Names that differ only in case, spacing or
 * punctuation are asked once.
 */
object SearchNames {
    private val spaces = Regex("\\s+")
    private val subtitle = Regex("\\s*(:|\\s-\\s)\\s*")
    private val ampersand = Regex("\\s*&\\s*")
    private val andWord = Regex("\\s+and\\s+", RegexOption.IGNORE_CASE)
    private val trailingThe = Regex(",\\s*the$", RegexOption.IGNORE_CASE)
    private val leadingThe = Regex("^the\\s+", RegexOption.IGNORE_CASE)
    private val nonAlphanumeric = Regex("[^a-z0-9]+")

    // A lone "X" is a name, not a ten ("Mega Man X"); a lone "V" counts only at the end ("Grand Theft Auto V").
    private val roman = listOf("II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X", "XI", "XII", "XIII", "XIV", "XV")
    private val romanValue = roman.withIndex().associate { (i, r) -> r to (i + 2).toString() } - "X"
    private val digitRoman = romanValue.entries.associate { (r, d) -> d to r }

    private val folding: Map<Char, Char> = buildMap {
        "àáâãäåā".forEach { put(it, 'a') }
        "ÀÁÂÃÄÅĀ".forEach { put(it, 'A') }
        "çćč".forEach { put(it, 'c') }
        "ÇĆČ".forEach { put(it, 'C') }
        "èéêëēėę".forEach { put(it, 'e') }
        "ÈÉÊËĒĖĘ".forEach { put(it, 'E') }
        "ìíîïī".forEach { put(it, 'i') }
        "ÌÍÎÏĪ".forEach { put(it, 'I') }
        "ñń".forEach { put(it, 'n') }
        "ÑŃ".forEach { put(it, 'N') }
        "òóôõöøō".forEach { put(it, 'o') }
        "ÒÓÔÕÖØŌ".forEach { put(it, 'O') }
        "ùúûüū".forEach { put(it, 'u') }
        "ÙÚÛÜŪ".forEach { put(it, 'U') }
        "ýÿ".forEach { put(it, 'y') }
    }

    /** Up to [max] names for [query], its title first. */
    fun of(query: ScrapeQuery, max: Int = MAX): List<String> {
        val out = LinkedHashMap<String, String>()
        fun add(name: String?) {
            val n = name?.let { spaces.replace(it, " ").trim() } ?: return
            if (n.length < 2) return
            val key = key(n)
            if (key.isNotEmpty()) out.getOrPut(key) { n }
        }
        add(query.title)
        query.alsoKnownAs.forEach(::add)
        variants(query.title).forEach(::add)
        return out.values.take(max.coerceAtLeast(1))
    }

    /** Other spellings of [title], most useful first. */
    internal fun variants(title: String): List<String> = buildList {
        val t = spaces.replace(title, " ").trim()
        // A subtitle is where searches most often go wrong ("Castlevania: Aria of Sorrow").
        subtitle.find(t)?.let { m ->
            val head = t.substring(0, m.range.first).trim()
            if (head.length >= 3 && head.any { it.isLetter() }) add(head)
        }
        if ('&' in t) add(ampersand.replace(t, " and "))
        if (andWord.containsMatchIn(t)) add(andWord.replace(t, " & "))
        val words = t.split(' ')
        fun numeral(i: Int) = words[i].takeIf { it != "V" || i == words.lastIndex }?.let { romanValue[it] }
        fun digits(i: Int) = words[i].takeIf { it != "5" || i == words.lastIndex }?.let { digitRoman[it] }
        if (words.indices.any { numeral(it) != null }) add(words.indices.joinToString(" ") { numeral(it) ?: words[it] })
        if (words.indices.any { digits(it) != null }) add(words.indices.joinToString(" ") { digits(it) ?: words[it] })
        when {
            trailingThe.containsMatchIn(t) -> add("The " + trailingThe.replace(t, "").trim())
            leadingThe.containsMatchIn(t) -> add(leadingThe.replace(t, ""))
        }
        if (t.any { it in folding }) add(t.map { folding[it] ?: it }.joinToString(""))
    }

    /** What counts as the same search: lower case, spaces for everything but ASCII letters and digits. */
    private fun key(name: String): String = nonAlphanumeric.replace(name.lowercase(), " ").trim()

    /** Names asked per provider for one game. */
    const val MAX = 3
}
