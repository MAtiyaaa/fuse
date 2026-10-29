package io.github.matiyaaa.fuse.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Journal across a profile's devices: the latest status, rating and note win, wherever they were set. */
class JournalSyncTest {
    private val game = GameKey.of("snes", null, null, "Chrono Trigger")

    @Test
    fun theLatestJournalWinsInAnyOrder() {
        // Beaten on the Deck at 10, then marked Completed on the PC at 20 (offline from each other).
        val deck = ProfileMeta().withGame(GameRecord(game, status = Lww("BEATEN", Hlc(10, 0, "deck")), statusAt = 10, rating = Lww(8, Hlc(10, 0, "deck"))))
        val pc = ProfileMeta().withGame(GameRecord(game, status = Lww("COMPLETED", Hlc(20, 0, "pc")), statusAt = 20, note = Lww("Every ending.", Hlc(20, 0, "pc"))))
        val one = deck.merge(pc)
        assertEquals(one, pc.merge(deck))
        val g = one.game(game)
        assertEquals("COMPLETED", g.status?.value)
        assertEquals(20L, g.statusAt)
        // Each part of it is its own: the Deck's rating and the PC's note both stand.
        assertEquals(8, g.rating?.value)
        assertEquals("Every ending.", g.note?.value)
    }

    @Test
    fun aJournalChangedHereGoesUpWithWhenItWasSet() {
        val base = ProfileMeta().withGame(GameRecord(game, status = Lww("WANT", Hlc(5, 0, "pc")), statusAt = 5))
        val local = ProfileMeta().withGame(GameRecord(game, status = Lww("PLAYING", Hlc.ZERO), statusAt = 40, rating = Lww(7, Hlc.ZERO)))
        val changes = ProfileDiff.changes(base, local, "deck", HlcClock("deck") { 50 })
        val g = changes.game(game)
        assertEquals("PLAYING", g.status?.value)
        assertEquals(40L, g.statusAt)
        assertEquals(7, g.rating?.value)
        assertNull(g.note)
        // Nothing changed: nothing goes.
        assertTrue(ProfileDiff.isEmpty(ProfileDiff.changes(base.merge(changes), base.merge(changes), "deck", HlcClock("deck") { 60 })))
    }

    @Test
    fun anotherProfileOfTheSamePersonOnlyFillsWhatIsMissing() {
        val mine = ProfileMeta().withGame(GameRecord(game, status = Lww("BEATEN", Hlc(30, 0, "pc")), statusAt = 30))
        val old = ProfileMeta().withGame(GameRecord(game, status = Lww("DROPPED", Hlc(90, 0, "old")), statusAt = 90, note = Lww("Later.", Hlc(90, 0, "old"))))
            .stampedAt(Hlc(1, 0, "old"))
        val g = mine.merge(old).game(game)
        assertEquals("BEATEN", g.status?.value)
        assertEquals("Later.", g.note?.value)
        assertEquals(Hlc(30, 0, "pc"), (mine.merge(old)).latestRevision())
    }
}
