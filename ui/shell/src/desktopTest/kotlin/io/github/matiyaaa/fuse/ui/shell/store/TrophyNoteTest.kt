package io.github.matiyaaa.fuse.ui.shell.store

import io.github.matiyaaa.fuse.sync.TrophyUnlock
import io.github.matiyaaa.fuse.ui.shell.store.impl.TrophyNote
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The trophy note's text: read back as written, whatever else the person put in it, and merged earliest first. */
class TrophyNoteTest {
    private val a = TrophyUnlock("ps3", "1", "First Treasure", 1_700_000_000_000, "deck", "Steam Deck", "rpcs3")
    private val b = TrophyUnlock("ps3", "2", "Line\nBreak", 1_600_000_000_000, "pc", "Gaming PC", "rpcs3")

    @Test
    fun whatIsWrittenIsReadBackTheSameWhicheverOrder() {
        val text = TrophyNote.write(listOf(a, b))
        assertEquals(listOf(b, a), TrophyNote.read(text), "oldest first")
        assertEquals(text, TrophyNote.write(listOf(b, a)))
        // A title can't break the note's lines.
        assertTrue("- Line Break, 2020-09-13 on Gaming PC\n" in text, text)
    }

    @Test
    fun onlyTheBlockIsReadAndALineThatIsntAnUnlockIsPassedOver() {
        val text = "My own words above.\n\n```fuse-trophies\n" +
            """{"source":"ps3","id":"1","title":"First Treasure","unlockedAt":1700000000000,"device":"deck"}""" + "\n" +
            "not json\n" +
            """{"source":"ps3","id":"","unlockedAt":5,"device":"x"}""" + "\n" +
            """{"source":"ps3","id":"3","unlockedAt":0,"device":"x"}""" + "\n" +
            """{"source":"ps3","id":"4","unlockedAt":42,"device":"x","later":"a field from a newer Fuse"}""" + "\n" +
            "```\n```fuse-trophies\n" + """{"source":"ps3","id":"9","unlockedAt":7,"device":"x"}""" + "\n```\n"
        assertEquals(listOf("1", "4"), TrophyNote.read(text).map { it.id })
        assertEquals(emptyList(), TrophyNote.read("Just words, no trophies."))
    }

    @Test
    fun mergingKeepsEachTrophysFirstTime() {
        val later = a.copy(unlockedAt = a.unlockedAt + 1, device = "pc")
        val merged = TrophyNote.merge(listOf(later), listOf(a, b))
        assertEquals(setOf("ps3:1", "ps3:2"), merged.keys)
        assertEquals(a, merged.getValue("ps3:1"))
        assertEquals(merged, TrophyNote.merge(listOf(a, b), listOf(later)), "the order doesn't matter")
    }
}
