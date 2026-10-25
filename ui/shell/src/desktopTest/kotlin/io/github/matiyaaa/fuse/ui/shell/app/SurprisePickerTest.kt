package io.github.matiyaaa.fuse.ui.shell.app

import io.github.matiyaaa.fuse.model.GameId
import io.github.matiyaaa.fuse.model.PlatformId
import io.github.matiyaaa.fuse.ui.shell.store.Art
import io.github.matiyaaa.fuse.ui.shell.store.GameCard
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Surprise Me favours the backlog, never lands on a game it can't start, and never twice in a row. */
class SurprisePickerTest {
    private val now = 1_760_000_000_000L
    private val day = 86_400_000L

    private fun game(id: Long, played: Long = 0, last: Long? = null, added: Long = 0, missing: Boolean = false) =
        GameCard(GameId(id), PlatformId("snes"), "Game $id", "SNES", 0xFF336699, Art(), lastPlayedAt = last, playSeconds = played, addedAt = added, missing = missing)

    @Test
    fun theBacklogWeighsMost() {
        val never = game(1)
        val barely = game(2, played = 10 * 60, last = now - 20 * day)
        val forgotten = game(3, played = 40 * 3600, last = now - 90 * day)
        val current = game(4, played = 40 * 3600, last = now - day)
        assertEquals(6, SurprisePicker.weight(never, now))
        assertEquals(4, SurprisePicker.weight(barely, now))
        assertEquals(2, SurprisePicker.weight(forgotten, now))
        assertEquals(1, SurprisePicker.weight(current, now))
        // Over many draws the never-played game comes up most and the one being played least.
        val counts = HashMap<GameId, Int>()
        val random = Random(7)
        repeat(13_000) { SurprisePicker.pick(listOf(never, barely, forgotten, current), now, random)!!.let { counts[it.id] = (counts[it.id] ?: 0) + 1 } }
        assertTrue(counts[never.id]!! > counts[barely.id]!!)
        assertTrue(counts[barely.id]!! > counts[forgotten.id]!!)
        assertTrue(counts[forgotten.id]!! > counts[current.id]!!)
    }

    @Test
    fun neverAGameItCantStartNorTheSameOneAgain() {
        val gone = game(1, missing = true)
        val a = game(2)
        val b = game(3)
        val random = Random(1)
        repeat(50) {
            val first = SurprisePicker.pick(listOf(gone, a, b), now, random)!!
            assertNotEquals(gone.id, first.id)
            val second = SurprisePicker.pick(listOf(gone, a, b), now, random, exclude = setOf(first.id))!!
            assertNotEquals(first.id, second.id)
        }
        // With every one already shown, it starts again rather than giving up.
        assertTrue(SurprisePicker.pick(listOf(a, b), now, random, exclude = setOf(a.id, b.id)) != null)
        assertNull(SurprisePicker.pick(listOf(gone), now, random))
    }

    @Test
    fun itSaysWhy() {
        assertEquals("Never played, added 3 weeks ago", SurprisePicker.why(game(1, added = now - 21 * day), now))
        assertEquals("Played for 10 min, last 2 weeks ago", SurprisePicker.why(game(2, played = 10 * 60, last = now - 20 * day), now))
        assertEquals("Not played for 3 months", SurprisePicker.why(game(3, played = 40 * 3600, last = now - 95 * day), now))
        assertEquals("40 h played", SurprisePicker.why(game(4, played = 40 * 3600, last = now - day), now))
    }

    @Test
    fun theReelStopsOnThePickInAFullRow() {
        val games = (1L..5L).map { game(it) }
        val reel = SurprisePicker.reel(games, games[2], Random(3))
        assertEquals(SurprisePicker.REEL_LENGTH, reel.size)
        assertEquals(games[2].id, reel[SurprisePicker.LANDING].id)
        // Covers after it too, so it stops in a full row.
        assertTrue(reel.size > SurprisePicker.LANDING + 2)
    }
}
