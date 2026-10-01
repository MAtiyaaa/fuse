package io.github.matiyaaa.fuse.integrations.match

/**
 * Turns a game title into a comparable form: lower case, accents folded, bracket tags removed
 * ("(USA)", "[!]"), "&" read as "and", apostrophes dropped, other punctuation (": " and " - "
 * included) turned into spaces, a leading or No-Intro style trailing article removed ("The Legend
 * of Zelda" and "Legend of Zelda, The" both become "legend of zelda"), and Roman numerals of two or
 * more letters turned into digits ("II" = "2"). A single trailing "V" also counts ("Grand Theft
 * Auto V"); a single "X" or "I" does not, because "Mega Man X" is not "Mega Man 10".
 */
object TitleNormalizer {

    // Android's regex engine (ICU) rejects "[^]]", which Java accepts, so every bracket is escaped.
    private val brackets = Regex("\\([^)]*\\)|\\[[^\\]]*\\]|\\{[^}]*\\}")
    private val trailingArticle = Regex(",\\s*(the|a|an)(?=\\s*($|[-:;]))")
    private val apostrophes = Regex("['’`´]")
    private val nonAlphanumeric = Regex("[^a-z0-9]+")
    private val articles = setOf("the", "a", "an")
    private val romanDigits = mapOf('i' to 1, 'v' to 5, 'x' to 10)

    private val folding: Map<Char, String> = buildMap {
        "àáâãäåā".forEach { put(it, "a") }
        "çćč".forEach { put(it, "c") }
        "èéêëēėę".forEach { put(it, "e") }
        "ìíîïī".forEach { put(it, "i") }
        "ñń".forEach { put(it, "n") }
        "òóôõöøō".forEach { put(it, "o") }
        "ùúûüū".forEach { put(it, "u") }
        "ýÿ".forEach { put(it, "y") }
        "šś".forEach { put(it, "s") }
        "žźż".forEach { put(it, "z") }
        put('æ', "ae")
        put('œ', "oe")
        put('ß', "ss")
    }

    /** The normalised title; tokens are separated by single spaces. */
    fun normalize(title: String): String = tokens(title).joinToString(" ")

    /** The normalised title as tokens. */
    fun tokens(title: String): List<String> {
        var t = fold(title.lowercase())
        t = brackets.replace(t, " ")
        t = trailingArticle.replace(t, "")
        t = t.replace("&", " and ")
        t = apostrophes.replace(t, "")
        t = nonAlphanumeric.replace(t, " ").trim()
        if (t.isEmpty()) return emptyList()
        val words = t.split(' ').filter { it.isNotEmpty() }.toMutableList()
        if (words.size > 1 && words[0] in articles) words.removeAt(0)
        return words.mapIndexed { i, w -> romanToDigits(w, isLast = i == words.lastIndex) ?: w }
    }

    private fun fold(s: String): String {
        if (s.all { it.code < 128 }) return s
        val sb = StringBuilder(s.length)
        for (c in s) sb.append(folding[c] ?: c.toString())
        return sb.toString()
    }

    /** "ii" -> "2", "xiv" -> "14" (1..39); a lone "v" only as the last word. Null when not a numeral. */
    internal fun romanToDigits(word: String, isLast: Boolean): String? {
        if (word.isEmpty() || word.any { it !in romanDigits }) return null
        if (word.length == 1 && !(word == "v" && isLast)) return null
        var total = 0
        var i = 0
        while (i < word.length) {
            val v = romanDigits.getValue(word[i])
            val next = if (i + 1 < word.length) romanDigits.getValue(word[i + 1]) else 0
            if (v < next) {
                // Only the subtractive pairs Roman numerals allow.
                if (!(v == 1 && (next == 5 || next == 10))) return null
                total += next - v
                i += 2
            } else {
                total += v
                i++
            }
        }
        // Reject non-canonical spellings ("iiii", "vv", "il") by round-tripping.
        return if (total in 1..39 && toRoman(total) == word) total.toString() else null
    }

    private fun toRoman(n: Int): String {
        val values = listOf(10 to "x", 9 to "ix", 5 to "v", 4 to "iv", 1 to "i")
        var rest = n
        return buildString {
            for ((v, s) in values) while (rest >= v) {
                append(s)
                rest -= v
            }
        }
    }
}

/** String similarity measures used by [TitleMatcher]. All return 0..1. */
object Similarity {

    /** Jaro-Winkler similarity with the usual 0.1 prefix scale over up to four characters. */
    fun jaroWinkler(a: String, b: String): Double {
        if (a == b) return 1.0
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val window = maxOf(0, maxOf(a.length, b.length) / 2 - 1)
        val aMatched = BooleanArray(a.length)
        val bMatched = BooleanArray(b.length)
        var matches = 0
        for (i in a.indices) {
            val from = maxOf(0, i - window)
            val to = minOf(b.length - 1, i + window)
            for (j in from..to) {
                if (!bMatched[j] && a[i] == b[j]) {
                    aMatched[i] = true
                    bMatched[j] = true
                    matches++
                    break
                }
            }
        }
        if (matches == 0) return 0.0
        var transpositions = 0
        var k = 0
        for (i in a.indices) {
            if (!aMatched[i]) continue
            while (!bMatched[k]) k++
            if (a[i] != b[k]) transpositions++
            k++
        }
        val m = matches.toDouble()
        val jaro = (m / a.length + m / b.length + (m - transpositions / 2.0) / m) / 3.0
        var prefix = 0
        while (prefix < minOf(4, a.length, b.length) && a[prefix] == b[prefix]) prefix++
        return jaro + prefix * 0.1 * (1 - jaro)
    }

    /** Levenshtein edit distance. */
    fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        var curr = IntArray(b.length + 1)
        for (i in 1..a.length) {
            curr[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                curr[j] = minOf(curr[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
            }
            val t = prev
            prev = curr
            curr = t
        }
        return prev[b.length]
    }

    /** 1 - distance / longer length. */
    fun levenshteinRatio(a: String, b: String): Double {
        val longer = maxOf(a.length, b.length)
        return if (longer == 0) 1.0 else 1.0 - levenshtein(a, b).toDouble() / longer
    }

    /** Levenshtein ratio of the tokens sorted alphabetically (word order does not matter). */
    fun tokenSortRatio(a: List<String>, b: List<String>): Double =
        levenshteinRatio(a.sorted().joinToString(" "), b.sorted().joinToString(" "))
}

/**
 * How the words of two normalised titles ([TitleNormalizer.tokens]) line up, for
 * [TitleMatcher.titleSimilarity]. Words are compared whole. A typo is forgiven only in words of five
 * letters or more that differ by one letter or one swap of neighbouring letters ("metriod" and
 * "metroid"); a word with letters added is another word ("dredge" and "dredgers", "zelda" and
 * "zeldas"). Two neighbouring words also match the same two written together ("fire red" and
 * "firered"). Numbers must be equal.
 */
object TitleWords {
    /** Articles and small words. */
    val filler: Set<String> = setOf("the", "a", "an", "of", "and", "in", "to", "for", "on")

    /** Words that name an edition of a game rather than the game. */
    val edition: Set<String> = setOf(
        "edition", "version", "remastered", "remaster", "deluxe", "complete", "definitive", "hd", "goty",
        "enhanced", "ultimate", "collection", "anniversary",
    )

    // Phrases whose words are all weak ("Director's Cut" loses its apostrophe when normalised).
    private val phrases = listOf(listOf("game", "of", "the", "year"), listOf("directors", "cut"), listOf("director", "cut"))
    private val digits = Regex("\\d+")

    /** Which of [tokens] are weak: [filler], [edition] words and edition phrases ("game of the year"). */
    fun weak(tokens: List<String>): BooleanArray {
        val out = BooleanArray(tokens.size) { tokens[it] in filler || tokens[it] in edition }
        for (p in phrases) for (i in 0..tokens.size - p.size) {
            if (p.indices.all { tokens[i + it] == p[it] }) p.indices.forEach { out[i + it] = true }
        }
        return out
    }

    /** Every number in [tokens] without leading zeros; one inside a word counts too ("x4" has 4). */
    fun numbers(tokens: List<String>): Set<String> =
        tokens.flatMapTo(HashSet()) { t -> digits.findAll(t).map { it.value.trimStart('0').ifEmpty { "0" } } }

    /** One substituted letter or one swap of neighbouring letters, in letter-only words of five or more. */
    fun isTypo(a: String, b: String): Boolean {
        if (a.length != b.length || a.length < 5 || a == b) return false
        if (!a.all { it.isLetter() } || !b.all { it.isLetter() }) return false
        val diff = a.indices.filter { a[it] != b[it] }
        return when (diff.size) {
            1 -> true
            2 -> diff[1] == diff[0] + 1 && a[diff[0]] == b[diff[1]] && a[diff[1]] == b[diff[0]]
            else -> false
        }
    }

    /** [a] and [b] word by word: which words found a partner, how many by a typo, and whether the order changed. */
    fun align(a: List<String>, b: List<String>): WordAlignment {
        val inA = BooleanArray(a.size)
        val inB = BooleanArray(b.size)
        val pairs = ArrayList<Pair<Int, Int>>()
        fun pair(i: Int, j: Int) {
            inA[i] = true
            inB[j] = true
            pairs += i to j
        }
        // Equal words, keeping the order where the title allows it.
        var last = -1
        for (i in a.indices) {
            val j = (last + 1 until b.size).firstOrNull { !inB[it] && b[it] == a[i] }
                ?: b.indices.firstOrNull { !inB[it] && b[it] == a[i] }
                ?: continue
            pair(i, j)
            last = j
        }
        // Two words written as one ("fire red" and "firered"), either way round.
        for (i in 0 until a.size - 1) {
            if (inA[i] || inA[i + 1]) continue
            val j = b.indices.firstOrNull { !inB[it] && b[it] == a[i] + a[i + 1] } ?: continue
            pair(i, j)
            pair(i + 1, j)
        }
        for (j in 0 until b.size - 1) {
            if (inB[j] || inB[j + 1]) continue
            val i = a.indices.firstOrNull { !inA[it] && a[it] == b[j] + b[j + 1] } ?: continue
            pair(i, j)
            pair(i, j + 1)
        }
        var typos = 0
        for (i in a.indices) {
            if (inA[i]) continue
            val j = b.indices.firstOrNull { !inB[it] && isTypo(a[i], b[it]) } ?: continue
            pair(i, j)
            typos++
        }
        val order = pairs.sortedWith(compareBy({ it.first }, { it.second })).map { it.second }
        val reordered = order.zipWithNext().any { (x, y) -> y < x }
        return WordAlignment(inA, inB, typos, reordered)
    }
}

/** The result of [TitleWords.align]. [matchedA] and [matchedB] mark the words that found a partner. */
class WordAlignment(
    val matchedA: BooleanArray,
    val matchedB: BooleanArray,
    val typos: Int,
    val reordered: Boolean,
)
