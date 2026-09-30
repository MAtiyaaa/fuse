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

    /**
     * Jaccard overlap of the token sets where near-identical words (typos, Jaro-Winkler >= 0.88,
     * both longer than three letters) count by their similarity. Numbers must match exactly.
     */
    fun softTokenSet(a: List<String>, b: List<String>): Double {
        val sa = a.distinct()
        val sb = b.distinct()
        if (sa.isEmpty() && sb.isEmpty()) return 1.0
        val used = BooleanArray(sb.size)
        var weight = 0.0
        var matched = 0
        for (t in sa) {
            var best = -1
            var bestScore = 0.0
            for (j in sb.indices) {
                if (used[j]) continue
                val u = sb[j]
                val score = when {
                    t == u -> 1.0
                    t.length <= 3 || u.length <= 3 || t.any { it.isDigit() } || u.any { it.isDigit() } -> 0.0
                    else -> jaroWinkler(t, u)
                }
                if (score > bestScore) {
                    bestScore = score
                    best = j
                }
            }
            if (best >= 0 && bestScore >= 0.88) {
                used[best] = true
                weight += bestScore
                matched++
            }
        }
        val union = sa.size + sb.size - matched
        return if (union == 0) 1.0 else weight / union
    }
}
