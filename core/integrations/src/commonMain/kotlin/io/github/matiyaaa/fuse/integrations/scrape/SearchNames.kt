package io.github.matiyaaa.fuse.integrations.scrape

import io.github.matiyaaa.fuse.integrations.match.TitleWords
import io.github.matiyaaa.fuse.model.ScrapeQuery

/**
 * The names a game is searched by, best first, for when its title finds nothing sure: the title,
 * the other names it is known by ([ScrapeQuery.alsoKnownAs]), then spellings of the title that
 * providers' searches treat differently: without its subtitle, with "&" and "and" swapped, with
 * Roman numerals as digits and digits as Roman numerals, without a leading "The" (or with a No-Intro
 * ", The" moved to the front), and without accents. Names that differ only in case, spacing or
 * punctuation are asked once.
 *
 * Keyword searches come last ([keywords]): the title without numbers and codes, then its longest
 * words. A search that misses an oddly written name usually still finds the game by these, and
 * what they return is still scored against the game's real names.
 */
object SearchNames {
    /** The searches for one game: [names] first, then [keywords] when the names found nothing sure. */
    data class Plan(val names: List<String>, val keywords: List<String>) {
        val all: List<String> get() = names + keywords
    }

    private val spaces = Regex("\\s+")
    private val wordBreaks = Regex("[\\s_:;,/()\\[\\]{}]+")
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

    /** Up to [max] names for [query], its title first and keyword searches last. */
    fun of(query: ScrapeQuery, max: Int = MAX): List<String> = plan(query, max).all

    /**
     * Up to [max] searches for [query], split into names and keywords. Up to [KEYWORDS] keyword
     * searches (never more than half of the places after the title) take the last places, so they
     * are reached even when the game has many names.
     */
    fun plan(query: ScrapeQuery, max: Int = MAX): Plan {
        val limit = max.coerceAtLeast(1)
        val names = distinct(listOf(query.title) + query.alsoKnownAs + variants(query.title))
        val keywords = distinct(keywords(query.title))
        fun fresh(kept: List<String>) = keywords.filter { k -> kept.none { key(it) == key(k) } }
        val reserved = minOf(fresh(names.take(limit)).size, KEYWORDS, (limit - 1) / 2)
        val kept = names.take(limit - reserved)
        return Plan(kept, fresh(kept).take(limit - kept.size))
    }

    /** [names] cleaned up, each search asked once. */
    private fun distinct(names: List<String>): List<String> {
        val out = LinkedHashMap<String, String>()
        for (name in names) {
            val n = spaces.replace(name, " ").trim()
            if (n.length < 2) continue
            val key = key(n)
            if (key.isNotEmpty()) out.getOrPut(key) { n }
        }
        return out.values.toList()
    }

    /**
     * Keyword searches for [title]: the title without numbers and codes ("12 Contra" -> "Contra",
     * "Crash Bandicoot SCUS-94900" -> "Crash Bandicoot"), then its three longest words that are not
     * small or edition words ("The Legend of Zelda: Ocarina of Time" -> "Legend Zelda Ocarina",
     * "Dredge Deluxe Edition" -> "Dredge"). Only searches that leave something out are listed.
     */
    internal fun keywords(title: String): List<String> {
        val words = wordBreaks.split(title).map { it.trim('.', '-', '\'', '!', '?', '&', '+') }.filter { it.isNotEmpty() }
        // Digits make a number, a serial or a version; a Roman numeral of two letters or more is a number too.
        val plain = words.filterNot { w -> w.any { it.isDigit() } || (w.length >= 2 && w in romanValue) }
        val meaningful = plain.filter { it.lowercase() !in TitleWords.filler }
        val strong = meaningful.filter { it.length >= 3 && it.lowercase() !in TitleWords.edition }
        val longest = strong.withIndex().sortedByDescending { it.value.length }.take(3).sortedBy { it.index }.map { it.value }
        return buildList {
            if (plain.size < words.size && strong.isNotEmpty()) add(plain.joinToString(" "))
            if (longest.isNotEmpty() && longest.size < meaningful.size) add(longest.joinToString(" "))
        }
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

    /** Searches per provider for one game, keywords included. */
    const val MAX = 5

    /** Keyword searches per provider for one game, at most. */
    const val KEYWORDS = 2
}
