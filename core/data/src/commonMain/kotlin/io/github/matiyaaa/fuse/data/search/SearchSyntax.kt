package io.github.matiyaaa.fuse.data.search

import io.github.matiyaaa.fuse.data.TitleText

/** When a game was last played, for `played:`. */
enum class PlayedWhen { EVER, NEVER, TODAY, WEEK, MONTH, YEAR }

/** One narrowing word in a search, like `platform:psx` or `year:1995-1999`. */
sealed interface SearchFilter {
    /** The text the filter was typed as, to show and to take back out. */
    val token: String

    data class Platform(val value: String, override val token: String) : SearchFilter
    data class Year(val from: Int, val to: Int, override val token: String) : SearchFilter
    data class Favorite(val yes: Boolean, override val token: String) : SearchFilter
    data class Played(val time: PlayedWhen, override val token: String) : SearchFilter
    data class Missing(val yes: Boolean, override val token: String) : SearchFilter
    data class Hidden(val yes: Boolean, override val token: String) : SearchFilter
    data class Collection(val value: String, override val token: String) : SearchFilter
    data class Genre(val value: String, override val token: String) : SearchFilter
    data class Developer(val value: String, override val token: String) : SearchFilter
    data class Drive(val value: String, override val token: String) : SearchFilter
}

/** The kinds of filter, each with the word it's typed as and the other words that mean it. */
enum class FilterKey(val word: String, vararg val aliases: String) {
    PLATFORM("platform", "system", "sys", "console"),
    YEAR("year", "released"),
    FAVORITE("favorite", "favourite", "fav"),
    PLAYED("played"),
    MISSING("missing"),
    HIDDEN("hidden"),
    COLLECTION("collection", "col"),
    GENRE("genre"),
    DEVELOPER("developer", "dev", "studio"),
    DRIVE("drive", "disk", "card"),
    ;

    companion object {
        fun of(word: String): FilterKey? {
            val w = word.lowercase()
            return entries.firstOrNull { it.word == w || w in it.aliases }
        }
    }
}

/**
 * A parsed search: the words to look for in names, the filters, filters that couldn't be read
 * (`year:soon`), and a filter still being typed (`platform:` with nothing after it yet).
 */
data class SearchQuery(
    val text: String,
    val filters: List<SearchFilter> = emptyList(),
    val invalid: List<String> = emptyList(),
    /** A filter whose value is still being typed: its key and what's typed so far (maybe empty). */
    val pending: Pair<FilterKey, String>? = null,
) {
    /** The name words, normalised like stored titles. */
    val needle: String get() = TitleText.normalize(text)
    val isEmpty: Boolean get() = needle.isEmpty() && filters.isEmpty()
}

/**
 * The search language. Words are looked for in names; `key:value` narrows (`platform:snes`,
 * `year:1998`, `year:1995-1999`, `year:1990s`, `year:>2000`, `favorite:yes`, `played:week`,
 * `played:no`, `missing:yes`, `collection:"Couch games"`, `genre:rpg`, `developer:capcom`,
 * `drive:sd`). Quotes keep spaces in a value. A word that only looks like a filter (`re:volt`)
 * stays part of the name.
 */
object SearchSyntax {
    fun parse(input: String): SearchQuery {
        val tokens = tokenize(input)
        val words = mutableListOf<String>()
        val filters = mutableListOf<SearchFilter>()
        val invalid = mutableListOf<String>()
        var pending: Pair<FilterKey, String>? = null
        val endsOpen = input.isNotEmpty() && !input.last().isWhitespace()
        tokens.forEachIndexed { i, t ->
            val colon = t.raw.indexOf(':')
            val key = if (colon > 0) FilterKey.of(t.raw.substring(0, colon)) else null
            if (key == null) {
                words += t.value
                return@forEachIndexed
            }
            val value = t.raw.substring(colon + 1).trim('"').trim()
            val last = i == tokens.lastIndex && endsOpen
            if (last) pending = key to value
            if (value.isEmpty()) return@forEachIndexed
            val filter = filter(key, value, t.raw)
            when {
                filter != null -> filters += filter
                // Still being typed: not wrong yet.
                !last -> invalid += t.raw
            }
        }
        return SearchQuery(words.joinToString(" "), filters, invalid, pending)
    }

    /** [input] with its filter still being typed (or a new one at the end) set to [key]:[value], ready for the next word. */
    fun complete(input: String, key: FilterKey, value: String): String {
        val tokens = tokenize(input)
        val endsOpen = input.isNotEmpty() && !input.last().isWhitespace()
        val last = tokens.lastOrNull()
        val keep = if (last != null && endsOpen && last.raw.indexOf(':').let { it > 0 && FilterKey.of(last.raw.substring(0, it)) == key }) {
            tokens.dropLast(1)
        } else {
            tokens
        }
        val quoted = if (value.any { it.isWhitespace() }) "\"$value\"" else value
        return (keep.map { it.raw } + "${key.word}:$quoted").joinToString(" ") + " "
    }

    /** [input] without the filter typed as [token]. */
    fun remove(input: String, token: String): String =
        tokenize(input).filter { it.raw != token }.joinToString(" ") { it.raw }.let { if (it.isEmpty()) it else "$it " }

    private fun filter(key: FilterKey, value: String, token: String): SearchFilter? = when (key) {
        FilterKey.PLATFORM -> SearchFilter.Platform(value, token)
        FilterKey.YEAR -> years(value)?.let { (a, b) -> SearchFilter.Year(a, b, token) }
        FilterKey.FAVORITE -> yesNo(value)?.let { SearchFilter.Favorite(it, token) }
        FilterKey.PLAYED -> played(value)?.let { SearchFilter.Played(it, token) }
        FilterKey.MISSING -> yesNo(value)?.let { SearchFilter.Missing(it, token) }
        FilterKey.HIDDEN -> yesNo(value)?.let { SearchFilter.Hidden(it, token) }
        FilterKey.COLLECTION -> SearchFilter.Collection(value, token)
        FilterKey.GENRE -> SearchFilter.Genre(value, token)
        FilterKey.DEVELOPER -> SearchFilter.Developer(value, token)
        FilterKey.DRIVE -> SearchFilter.Drive(value, token)
    }

    private fun yesNo(value: String): Boolean? = when (value.lowercase()) {
        "yes", "y", "true", "on", "1" -> true
        "no", "n", "false", "off", "0" -> false
        else -> null
    }

    private fun played(value: String): PlayedWhen? = when (value.lowercase()) {
        "yes", "y", "true", "ever", "any" -> PlayedWhen.EVER
        "no", "n", "false", "never" -> PlayedWhen.NEVER
        "today" -> PlayedWhen.TODAY
        "week" -> PlayedWhen.WEEK
        "month" -> PlayedWhen.MONTH
        "year" -> PlayedWhen.YEAR
        else -> null
    }

    private val YEAR = Regex("""\d{4}""")

    /** `1998`, `1995-1999`, `1990s`, `>2000`, `>=2000`, `<1990`, `<=1990`. Years outside 1950..2100 aren't years. */
    internal fun years(value: String): Pair<Int, Int>? {
        val v = value.trim()
        fun year(s: String) = s.takeIf { YEAR.matches(it) }?.toInt()?.takeIf { it in 1950..2100 }
        return when {
            v.startsWith(">=") -> year(v.drop(2))?.let { it to 2100 }
            v.startsWith("<=") -> year(v.drop(2))?.let { 1950 to it }
            v.startsWith(">") -> year(v.drop(1))?.let { it + 1 to 2100 }
            v.startsWith("<") -> year(v.drop(1))?.let { 1950 to it - 1 }
            v.endsWith("s") && v.length == 5 -> year(v.dropLast(1))?.takeIf { it % 10 == 0 }?.let { it to it + 9 }
            '-' in v -> {
                val (a, b) = v.split('-', limit = 2).map(String::trim)
                val from = year(a)
                val to = year(b)
                if (from != null && to != null) minOf(from, to) to maxOf(from, to) else null
            }
            else -> year(v)?.let { it to it }
        }
    }

    /** A word as typed ([raw], quotes kept) and its value without quotes. */
    private class Token(val raw: String, val value: String)

    private fun tokenize(input: String): List<Token> {
        val out = mutableListOf<Token>()
        val raw = StringBuilder()
        var quoted = false
        fun flush() {
            if (raw.isNotEmpty()) out += Token(raw.toString(), raw.toString().replace("\"", ""))
            raw.clear()
        }
        for (ch in input) {
            when {
                ch == '"' -> {
                    quoted = !quoted
                    raw.append(ch)
                }
                ch.isWhitespace() && !quoted -> flush()
                else -> raw.append(ch)
            }
        }
        flush()
        return out
    }
}
