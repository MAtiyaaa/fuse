package io.github.matiyaaa.fuse.data

import io.github.matiyaaa.fuse.data.search.SearchRank
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The faster ranking (names split once, typos bounded by letters before any comparing) gives every
 * name and needle exactly the score the first one did. [Reference] is that first ranking, kept as
 * it shipped.
 */
class SearchRankEquivalenceTest {
    private val words = listOf(
        "the", "legend", "of", "zelda", "metal", "gear", "solid", "pokemon", "red", "version", "castlevania",
        "symphony", "night", "doom", "eternal", "final", "fantasy", "vii", "super", "mario", "kart", "64",
        "2", "3", "ii", "star", "fox", "sonic", "hedgehog", "street", "fighter", "alpha", "tetris", "fez",
        "shadow", "knight", "rocket", "league", "puzzle", "island", "racer", "soccer", "dungeon", "quest",
        "a", "x", "ab", "zz", "kingdom", "hearts", "chrono", "trigger", "earthbound", "okami", "persona",
        "jet", "set", "radio", "future", "ico", "pac", "man", "galaga", "mega", "man", "zero", "battle",
        "monogatari", "ゼルダ", "伝説", "дум", "élan", "naïve",
    )
    private val letters = "abcdefghijklmnopqrstuvwxyz0123456789 ゼдé"

    @Test
    fun everyScoreIsTheSame() {
        val rnd = Random(4517)
        repeat(400_000) {
            val name = name(rnd)
            val needle = needle(rnd, name)
            assertEquals(Reference.score(name, needle), SearchRank.score(name, needle), "\"$name\" for \"$needle\"")
        }
    }

    @Test
    fun oneNeedleServesManyNames() {
        // A needle keeps its working rows between names; reusing it changes nothing.
        val rnd = Random(91)
        repeat(2_000) {
            val needleText = needle(rnd, name(rnd))
            val needle = SearchRank.Needle(needleText)
            repeat(100) {
                val name = name(rnd)
                assertEquals(Reference.score(name, needleText), SearchRank.score(SearchRank.Name(name), needle), "\"$name\" for \"$needleText\"")
            }
        }
    }

    private fun name(rnd: Random): String = when (rnd.nextInt(10)) {
        0 -> ""
        1 -> randomText(rnd, rnd.nextInt(1, 30))
        // Normalised text has single spaces, but nothing stops a name from having two.
        2 -> (0 until rnd.nextInt(1, 6)).joinToString(if (rnd.nextBoolean()) " " else "  ") { words.random(rnd) }
        else -> (0 until rnd.nextInt(1, 7)).joinToString(" ") { words.random(rnd) }
    }

    private fun needle(rnd: Random, name: String): String {
        val base = when (rnd.nextInt(8)) {
            0 -> name
            1 -> name.take(rnd.nextInt(0, name.length + 1))
            2 -> name.split(' ').shuffled(rnd).take(rnd.nextInt(1, 3)).joinToString(" ")
            3 -> randomText(rnd, rnd.nextInt(0, 12))
            4 -> name.split(' ').filter { it.isNotEmpty() }.joinToString("") { it.take(1) }
            5 -> words.random(rnd) + " " + words.random(rnd)
            else -> name.split(' ').random(rnd)
        }
        var s = base
        repeat(rnd.nextInt(0, 4)) { s = typo(rnd, s) }
        return s
    }

    private fun typo(rnd: Random, s: String): String {
        if (s.isEmpty()) return letters.random(rnd).toString()
        val i = rnd.nextInt(s.length)
        return when (rnd.nextInt(4)) {
            0 -> s.removeRange(i, i + 1)
            1 -> s.substring(0, i) + letters.random(rnd) + s.substring(i)
            2 -> s.substring(0, i) + letters.random(rnd) + s.substring(i + 1)
            else -> if (i + 1 < s.length) s.substring(0, i) + s[i + 1] + s[i] + s.substring(i + 2) else s
        }
    }

    private fun randomText(rnd: Random, length: Int) = (0 until length).map { letters.random(rnd) }.joinToString("")

    /** The ranking as it first shipped. */
    private object Reference {
        fun score(name: String, needle: String): Int? {
            if (needle.isEmpty()) return 0
            if (name.isEmpty()) return null
            if (name == needle) return SearchRank.EXACT
            if (name.startsWith(needle)) return SearchRank.PREFIX - (name.length - needle.length).coerceAtMost(150)
            val words = name.split(' ')
            val parts = needle.split(' ')
            if (parts.all { p -> words.any { it.startsWith(p) } }) return SearchRank.WORD_PREFIX - (words.size).coerceAtMost(100)
            if (parts.all { it in name }) return SearchRank.CONTAINS - (name.length / 4).coerceAtMost(100)
            if (parts.size == 1 && needle.length >= 2 && words.size >= needle.length) {
                val initials = words.joinToString("") { it.take(1) }
                if (initials.startsWith(needle)) return SearchRank.INITIALS
            }
            var typos = 0
            for (p in parts) {
                if (p.length < 4) {
                    if (words.none { it.startsWith(p) }) return null
                    continue
                }
                val allowed = if (p.length >= 8) 2 else 1
                val best = words.minOf { w -> listOf(w, w.take(p.length), w.take(p.length + 1)).distinct().minOf { SearchRank.distance(p, it, allowed) } }
                if (best > allowed) return null
                typos += best
            }
            return SearchRank.FUZZY - typos * 20
        }
    }
}
