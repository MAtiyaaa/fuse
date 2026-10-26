package io.github.matiyaaa.fuse.data.search

/**
 * How well a name matches what was typed, higher first; null when it doesn't. Both sides are
 * normalised text ([io.github.matiyaaa.fuse.data.TitleText.normalize]). In order: the whole name,
 * the start of the name, the start of words, anywhere, the first letters of its words ("mgs" for
 * Metal Gear Solid), then names with a small typo ("zelad", "pokmon").
 */
object SearchRank {
    const val EXACT = 1000
    const val PREFIX = 800
    const val WORD_PREFIX = 600
    const val CONTAINS = 400
    const val INITIALS = 300
    const val FUZZY = 200

    fun score(name: String, needle: String): Int? = score(Name(name), Needle(needle))

    /**
     * A name taken apart once (its words, their first letters, which letters each holds), so a
     * library's names are split when it is read and not again on every key typed.
     */
    class Name(val text: String) {
        internal val words: Array<String> = text.split(' ').toTypedArray()
        internal val initials: String = words.joinToString("") { it.take(1) }
        internal val wordLetters = LongArray(words.size) { letters(words[it]) }
        internal val allLetters = wordLetters.fold(0L) { all, w -> all or w }
    }

    /**
     * What was typed, taken apart once for a whole search. It keeps the rows the typo check works
     * in, so it serves one search at a time.
     */
    class Needle(val text: String) {
        internal val parts: Array<String> = text.split(' ').toTypedArray()
        internal val partLetters: Array<LongArray> = Array(parts.size) { i -> LongArray(parts[i].length) { letter(parts[i][it]) } }
        private val width = (parts.maxOfOrNull { it.length } ?: 0) + 4
        internal var prev2 = IntArray(width)
        internal var prev = IntArray(width)
        internal var cur = IntArray(width)
    }

    fun score(name: Name, needle: Needle): Int? {
        val text = name.text
        val typed = needle.text
        if (typed.isEmpty()) return 0
        if (text.isEmpty()) return null
        if (text == typed) return EXACT
        // Shorter names that start with it come first: "Doom" before "Doom Eternal".
        if (text.startsWith(typed)) return PREFIX - (text.length - typed.length).coerceAtMost(150)
        val words = name.words
        val parts = needle.parts
        if (parts.all { p -> words.any { it.startsWith(p) } }) return WORD_PREFIX - (words.size).coerceAtMost(100)
        if (parts.all { it in text }) return CONTAINS - (text.length / 4).coerceAtMost(100)
        if (parts.size == 1 && typed.length >= 2 && words.size >= typed.length && name.initials.startsWith(typed)) return INITIALS
        var typos = 0
        for (i in parts.indices) {
            val p = parts[i]
            // Very short words are too easy to mistake for others.
            if (p.length < 4) {
                if (words.none { it.startsWith(p) }) return null
                continue
            }
            val allowed = if (p.length >= 8) 2 else 1
            // Each letter of the part found in no word costs a typo of its own: too many and no
            // word can be near enough, which settles most names before any comparing.
            val letters = needle.partLetters[i]
            if (absent(letters, name.allLetters) > allowed) return null
            var best = allowed + 1
            for (k in words.indices) {
                if (absent(letters, name.wordLetters[k]) >= best) continue
                best = minOf(best, nearest(needle, p, words[k], allowed))
                if (best == 0) break
            }
            if (best > allowed) return null
            typos += best
        }
        return FUZZY - typos * 20
    }

    /**
     * The fewest typos between [p] and [w], its first `p.length` letters or its first
     * `p.length + 1` (so a name still being typed counts too), or anything above [limit]. One
     * table answers all three: the distance to each start of [w] is a column of the last row.
     */
    private fun nearest(needle: Needle, p: String, w: String, limit: Int): Int {
        val n = p.length
        val m = w.length
        // Columns past n + limit are more than limit away from all of p, so the table stops there.
        val cols = minOf(m, n + limit)
        var prev2 = needle.prev2
        var prev = needle.prev
        var cur = needle.cur
        for (j in 0..cols) prev[j] = j
        for (i in 1..n) {
            cur[0] = i
            var rowMin = i
            val a = p[i - 1]
            for (j in 1..cols) {
                val b = w[j - 1]
                var v = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a == b) 0 else 1)
                if (i > 1 && j > 1 && a == w[j - 2] && p[i - 2] == b) v = minOf(v, prev2[j - 2] + 1)
                cur[j] = v
                if (v < rowMin) rowMin = v
            }
            // Every distance in a later row is at least the smallest in this one.
            if (rowMin > limit) return limit + 1
            val t = prev2
            prev2 = prev
            prev = cur
            cur = t
        }
        var best = minOf(prev[minOf(m, n)], prev[minOf(m, n + 1)])
        if (m <= cols) best = minOf(best, prev[m])
        return minOf(best, limit + 1)
    }

    /**
     * Optimal string alignment distance between [a] and [b] (a swap of two neighbours counts as
     * one), or anything above [limit] as soon as it is clear the distance is larger.
     */
    internal fun distance(a: String, b: String, limit: Int): Int {
        if (kotlin.math.abs(a.length - b.length) > limit) return limit + 1
        val n = a.length
        val m = b.length
        var prev2 = IntArray(m + 1)
        var prev = IntArray(m + 1) { it }
        var cur = IntArray(m + 1)
        for (i in 1..n) {
            cur[0] = i
            var rowMin = cur[0]
            for (j in 1..m) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                var v = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
                if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) v = minOf(v, prev2[j - 2] + 1)
                cur[j] = v
                if (v < rowMin) rowMin = v
            }
            if (rowMin > limit) return limit + 1
            val t = prev2
            prev2 = prev
            prev = cur
            cur = t
        }
        return prev[m]
    }

    /**
     * A letter as one of 64 bits: a to z and 0 to 9 each their own, anything else shared. Two
     * letters sharing a bit only make [absent] count fewer, never more, so it stays a lower bound.
     */
    private fun letter(c: Char): Long = when (c) {
        in 'a'..'z' -> 1L shl (c - 'a')
        in '0'..'9' -> 1L shl (26 + (c - '0'))
        else -> 1L shl (36 + c.code % 28)
    }

    private fun letters(s: String): Long {
        var bits = 0L
        for (c in s) bits = bits or letter(c)
        return bits
    }

    /** How many of a part's letters (by bit, counted with repeats) are in none of [held]. */
    private fun absent(letters: LongArray, held: Long): Int {
        var n = 0
        for (bit in letters) if (bit and held == 0L) n++
        return n
    }
}
