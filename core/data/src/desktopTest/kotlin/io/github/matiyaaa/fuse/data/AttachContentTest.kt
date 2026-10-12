package io.github.matiyaaa.fuse.data

import io.github.matiyaaa.fuse.model.ContentKind
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A file Fuse took for a game of its own, that the person says is an update of another: it joins
 * that game's content with its play time, stays there through every rescan, never shows among
 * removed games, and can be made a game again.
 */
class AttachContentTest {
    private val game = scanned("/roms/switch/Zelda.nsp", platform = "switch")
    private val update = scanned("/roms/switch/Zelda patch v2.nsp", platform = "switch")
    private val other = scanned("/roms/gba/Metroid.gba", platform = "gba")

    @Test
    fun anUpdateJoinsItsGameAndStaysThroughRescans() = runBlocking {
        TestDb().use { t ->
            val d = t.data
            val scan = report(folder("/roms/switch", listOf(game, update), platform = "switch"), folder("/roms/gba", listOf(other)))
            d.indexer.apply(scan, now = 1_000, cleaner = testCleaner)
            val gameId = assertNotNull(d.games.idByPath(game.path))
            val updateId = assertNotNull(d.games.idByPath(update.path))
            val otherId = assertNotNull(d.games.idByPath(other.path))
            d.games.setFavorite(updateId, true)

            // Never across systems, and never onto itself.
            assertFalse(d.games.attachAsContent(updateId, otherId, ContentKind.UPDATE))
            assertFalse(d.games.attachAsContent(updateId, updateId, ContentKind.UPDATE))

            assertTrue(d.games.attachAsContent(updateId, gameId, ContentKind.UPDATE))
            val joined = assertNotNull(d.games.get(gameId))
            assertEquals(listOf(update.path), joined.content.filter { it.kind == ContentKind.UPDATE }.map { it.path })
            assertTrue(joined.favorite, "what the person did with it comes along")
            assertEquals(1, d.games.summary(gameId)?.updateCount)
            // It isn't a game of its own any more, and it isn't something the person removed.
            val listed = d.games.observeAll(includeHidden = false).first().map { it.id }
            assertFalse(updateId in listed)
            assertTrue(d.games.observeRemoved().first().none { it.id == updateId })
            // Joined twice is refused: it is already part of a game.
            assertFalse(d.games.attachAsContent(updateId, gameId, ContentKind.DLC))

            // A rescan finds the same files: the update stays with its game.
            d.indexer.apply(scan, now = 2_000, cleaner = testCleaner)
            val after = assertNotNull(d.games.get(gameId))
            assertEquals(listOf(update.path), after.content.filter { it.kind == ContentKind.UPDATE }.map { it.path })
            assertFalse(updateId in d.games.observeAll(includeHidden = false).first().map { it.id })
            assertEquals(listOf(Triple(updateId, update.title, ContentKind.UPDATE)), d.games.userParts(gameId))

            // And back: a game of its own again, its game's content without it.
            assertEquals(gameId, d.games.detachContent(updateId))
            assertTrue(updateId in d.games.observeAll(includeHidden = false).first().map { it.id })
            assertTrue(assertNotNull(d.games.get(gameId)).content.none { it.path == update.path })
            assertEquals(0, d.games.summary(gameId)?.updateCount)
            assertNull(d.games.detachContent(updateId), "nothing left to undo")
        }
    }
}
