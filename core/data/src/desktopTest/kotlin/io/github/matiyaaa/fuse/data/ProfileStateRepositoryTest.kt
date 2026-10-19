package io.github.matiyaaa.fuse.data

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A person's state written over the library (Fuse One laying a profile in place) never undoes what
 * they did while it was being worked out: [io.github.matiyaaa.fuse.data.repo.ProfileStateRepository.write]
 * decides what changed since in the same transaction as the write. Fuse One's sync store test met it:
 * a game added to a collection while a profile was laid in place went up with no games in it.
 */
class ProfileStateRepositoryTest {
    @Test
    fun whatThePersonDidWhileAWriteWasWorkedOutStays() = runBlocking {
        TestDb().use { t ->
            val d = t.data
            d.indexer.apply(report(folder("/roms/gba", listOf("Wars", "Sun", "Ruby").map { scanned("/roms/gba/$it.gba") })), t.now, testCleaner)
            val (wars, sun, ruby) = listOf("Wars", "Sun", "Ruby").map { d.games.idByPath("/roms/gba/$it.gba")!! }
            val strategy = d.collections.create("Strategy")
            // What the write is worked out from: the profile brings in a name for Wars.
            val before = d.profileState.read()
            val incoming = before.copy(games = before.games.map { if (it.id == wars.value) it.copy(customTitle = "Wars!") else it })
            // Meanwhile the person adds Wars to Strategy, makes Sun a favourite, and a game of Ruby ends.
            d.collections.addGames(strategy, listOf(wars))
            d.games.setFavorite(sun, true)
            d.playSessions.end(d.playSessions.start(ruby, null, t.now), t.now + 60_000)

            d.profileState.write(incoming, since = before)
            val after = d.profileState.read()
            assertEquals("Wars!", after.games.single { it.id == wars.value }.customTitle, "what the profile brought is in place")
            assertEquals(listOf(wars.value), after.collections.single { it.name == "Strategy" }.games, "the game added to the collection")
            assertTrue(after.games.single { it.id == sun.value }.favorite, "the favourite")
            assertEquals(1, after.sessions.count { it.gameId == ruby.value }, "the session that ended")

            // A write with nothing to go by (no read before it) puts exactly what it is given.
            d.profileState.write(incoming)
            val restored = d.profileState.read()
            assertEquals(emptyList(), restored.collections.single { it.name == "Strategy" }.games)
            assertFalse(restored.games.single { it.id == sun.value }.favorite)
            assertEquals(0, restored.sessions.count { it.gameId == ruby.value })
        }
    }
}
