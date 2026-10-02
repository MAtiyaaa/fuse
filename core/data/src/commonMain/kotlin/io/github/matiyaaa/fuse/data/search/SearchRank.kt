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

    fun score(name: String, needle: String): Int? {
        if (needle.isEmpty()) return 0
        if (name.isEmpty()) return null
        if (name == needle) return EXACT
        // Shorter names that start with it come first: "Doom" before "Doom Eternal".
        if (name.startsWith(needle)) return PREFIX - (name.length - needle.length).coerceAtMost(150)
        val words = name.split(' ')
        val parts = needle.split(' ')
        if (parts.all { p -> words.any { it.startsWith(p) } }) return WORD_PREFIX - (words.size).coerceAtMost(100)
        if (parts.all { it in name }) return CONTAINS - (name.length / 4).coerceAtMost(100)
        if (parts.size == 1 && needle.length >= 2 && words.size >= needle.length) {
            val initials = words.joinToString("") { it.take(1) }
            if (initials.startsWith(needle)) return INITIALS
        }
        var typos = 0
        for (p in parts) {
            // Very short words are too easy to mistake for others.
            if (p.length < 4) {
                if (words.none { it.startsWith(p) }) return null
                continue
            }
            val allowed = if (p.length >= 8) 2 else 1
            // Against the whole word and its start, so a name still being typed counts too.
            val best = words.minOf { w -> listOf(w, w.take(p.length), w.take(p.length + 1)).distinct().minOf { distance(p, it, allowed) } }
            if (best > allowed) return null
            typos += best
        }
        return FUZZY - typos * 20
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
}
