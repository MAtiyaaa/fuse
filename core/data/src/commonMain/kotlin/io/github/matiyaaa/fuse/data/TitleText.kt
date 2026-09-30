package io.github.matiyaaa.fuse.data

import io.github.matiyaaa.fuse.model.GameTitles

/** Derived title columns: normalised search text and sort key. */
object TitleText {
    private val fold: Map<Char, String> = buildMap {
        fun map(chars: String, to: String) = chars.forEach { put(it, to) }
        map("àáâãäåāăą", "a")
        map("çćĉċč", "c")
        map("ďđð", "d")
        map("èéêëēĕėęě", "e")
        map("ĝğġģ", "g")
        map("ĥħ", "h")
        map("ìíîïĩīĭįı", "i")
        map("ĵ", "j")
        map("ķ", "k")
        map("ĺļľŀł", "l")
        map("ñńņňŉ", "n")
        map("òóôõöøōŏő", "o")
        map("ŕŗř", "r")
        map("śŝşšș", "s")
        map("ţťŧț", "t")
        map("ùúûüũūŭůűų", "u")
        map("ŵ", "w")
        map("ýÿŷ", "y")
        map("źżž", "z")
        map("æ", "ae")
        map("œ", "oe")
        map("ß", "ss")
        map("þ", "th")
        // Apostrophes vanish so "Assassin's" matches "assassins".
        map("'’`", "")
    }

    /**
     * Lower-case, accent-folded text with every run of non-alphanumerics collapsed to one space.
     * Queries are normalised the same way, so they never contain LIKE wildcards.
     */
    fun normalize(text: String): String {
        val out = StringBuilder(text.length)
        var pendingSpace = false
        for (ch in text.lowercase()) {
            val mapped = fold[ch]
            when {
                mapped != null -> if (mapped.isNotEmpty()) {
                    if (pendingSpace && out.isNotEmpty()) out.append(' ')
                    pendingSpace = false
                    out.append(mapped)
                }
                ch.isLetterOrDigit() -> {
                    if (pendingSpace && out.isNotEmpty()) out.append(' ')
                    pendingSpace = false
                    out.append(ch)
                }
                else -> pendingSpace = true
            }
        }
        return out.toString()
    }

    /**
     * Search column: the display title, followed by the on-disk title when it differs, so renamed
     * games are still found by their file name. Prefix ranking uses the display title.
     */
    fun searchColumn(titles: GameTitles): String {
        val display = normalize(titles.display)
        val original = normalize(titles.original)
        return if (original.isEmpty() || display.contains(original)) display else "$display | $original"
    }

    /** Sort column, ignoring case and leading articles. */
    fun sortColumn(titles: GameTitles): String = titles.sortKey
}
