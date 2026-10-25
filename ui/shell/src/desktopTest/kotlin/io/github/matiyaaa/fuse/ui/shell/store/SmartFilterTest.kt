package io.github.matiyaaa.fuse.ui.shell.store

import io.github.matiyaaa.fuse.model.GameId
import io.github.matiyaaa.fuse.model.PlatformId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The smart filters hold what their names say, and the play-based ones never share a game. */
class SmartFilterTest {
    private val now = 1_760_000_000_000L
    private val day = 86_400_000L

    private fun game(id: Long, played: Long = 0, last: Long? = null, added: Long = now - 100 * day) =
        GameCard(GameId(id), PlatformId("snes"), "Game $id", "SNES", 0xFF336699, Art(), lastPlayedAt = last, playSeconds = played, addedAt = added)

    private fun SmartFilter.holds(g: GameCard, together: Set<GameId> = emptySet()) = matches(g, now, together)

    @Test
    fun eachHoldsWhatItSays() {
        val never = game(1)
        val tried = game(2, played = 10 * 60, last = now - 3 * day)
        // Launched, but it closed before any time was counted.
        val opened = game(3, played = 0, last = now - day)
        val dusty = game(4, played = 12 * 3600, last = now - 90 * day)
        val current = game(5, played = 12 * 3600, last = now - day)
        // Time brought over from elsewhere, with no date: taken as long ago.
        val imported = game(6, played = 5 * 3600, last = null)
        val fresh = game(7, added = now - 2 * day)

        assertTrue(SmartFilter.UNPLAYED.holds(never))
        assertTrue(SmartFilter.UNPLAYED.holds(fresh))
        assertFalse(SmartFilter.UNPLAYED.holds(opened))
        assertTrue(SmartFilter.BARELY.holds(tried))
        assertTrue(SmartFilter.BARELY.holds(opened))
        assertFalse(SmartFilter.BARELY.holds(never))
        assertTrue(SmartFilter.DUSTY.holds(dusty))
        assertTrue(SmartFilter.DUSTY.holds(imported))
        assertFalse(SmartFilter.DUSTY.holds(current))
        assertTrue(SmartFilter.ADDED.holds(fresh))
        assertFalse(SmartFilter.ADDED.holds(never))
        assertTrue(SmartFilter.TOGETHER.holds(current, together = setOf(current.id)))
        assertFalse(SmartFilter.TOGETHER.holds(never, together = setOf(current.id)))

        // Unplayed, Barely played and Gathering dust never share a game.
        val play = listOf(SmartFilter.UNPLAYED, SmartFilter.BARELY, SmartFilter.DUSTY)
        for (g in listOf(never, tried, opened, dusty, current, imported, fresh)) {
            assertTrue(play.count { it.holds(g) } <= 1, "${g.title} is in more than one")
        }
    }

    @Test
    fun pinnedFiltersReadBackInFusesOrder() {
        assertEquals(listOf(SmartFilter.UNPLAYED, SmartFilter.TOGETHER), SmartFilter.of(listOf("TOGETHER", "UNPLAYED", "SOMETHING_OLD")))
        assertEquals(emptyList(), SmartFilter.of(emptyList()))
    }
}
