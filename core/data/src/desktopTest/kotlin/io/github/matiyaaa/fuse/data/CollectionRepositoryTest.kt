package io.github.matiyaaa.fuse.data

import io.github.matiyaaa.fuse.data.repo.CollectionKey
import io.github.matiyaaa.fuse.model.CollectionKind
import io.github.matiyaaa.fuse.model.GameMetadata
import io.github.matiyaaa.fuse.model.PlatformId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CollectionRepositoryTest {
    private val day = 86_400_000L

    @Test
    fun smartAndManualCollections() = runBlocking {
        TestDb().use { t ->
            val d = t.data
            d.indexer.apply(
                report(
                    folder("/roms/gba", listOf(scanned("/roms/gba/Alpha.gba"), scanned("/roms/gba/Bravo.gba"))),
                    folder(
                        "/roms/snes",
                        listOf(scanned("/roms/snes/Charlie.sfc", platform = "snes"), scanned("/roms/snes/Delta.sfc", platform = "snes")),
                        platform = "snes",
                    ),
                    folder("/roms/nes", listOf(scanned("/roms/nes/Echo.nes", platform = "nes")), platform = "nes"),
                ),
                t.now - 100 * day,
                testCleaner,
            )
            val (a, b, c, dd, e) = listOf("/roms/gba/Alpha.gba", "/roms/gba/Bravo.gba", "/roms/snes/Charlie.sfc", "/roms/snes/Delta.sfc", "/roms/nes/Echo.nes")
                .map { d.games.idByPath(it)!! }

            d.games.setFavorite(a, true)
            d.games.setHidden(e, true)
            d.playSessions.end(d.playSessions.start(b, null, t.now - 2 * day), t.now - 2 * day + 60_000)
            d.playSessions.end(d.playSessions.start(c, null, t.now - 30 * day), t.now - 30 * day + 60_000)
            d.games.applyMetadata(a, GameMetadata(genres = listOf("Action", "Platformer")), onlyFillEmpty = false)
            d.games.applyMetadata(c, GameMetadata(genres = listOf("action")), onlyFillEmpty = false)

            val names = mapOf("gba" to "Game Boy Advance", "snes" to "Super Nintendo")
            val all = d.collections.observeCollections(completed = setOf(dd, e)) { names[it.value] ?: it.value }.first()
            val counts = all.associate { it.key to it.gameCount }
            assertEquals(1, counts[CollectionKey.Favorites])
            assertEquals(1, counts[CollectionKey.Playing])
            assertEquals(4, counts[CollectionKey.RecentlyAdded])
            assertEquals(1, counts[CollectionKey.Completed], "hidden games are not counted")
            assertEquals(2, counts[CollectionKey.Unplayed])
            assertEquals(2, counts[CollectionKey.Platform(PlatformId("gba"))])
            assertEquals(2, counts[CollectionKey.Platform(PlatformId("snes"))])
            assertEquals(null, counts[CollectionKey.Platform(PlatformId("nes"))], "only hidden games there")
            assertEquals(2, counts[CollectionKey.Genre("Action")], "genres group case-insensitively")
            assertEquals(1, counts[CollectionKey.Genre("Platformer")])
            assertTrue(d.collections.observeCollections().first().none { it.kind == CollectionKind.COMPLETED })

            assertEquals(listOf(b), d.collections.observeGames(CollectionKey.Playing).first().map { it.id })
            assertEquals(listOf(a, dd), d.collections.observeGames(CollectionKey.Unplayed).first().map { it.id })
            assertEquals(listOf(a, c), d.collections.observeGames(CollectionKey.Genre("ACTION")).first().map { it.id })
            assertEquals(listOf(dd), d.collections.observeGames(CollectionKey.Completed, setOf(dd, e)).first().map { it.id })
            assertEquals(listOf(c, dd), d.collections.observeGames(CollectionKey.Platform(PlatformId("snes"))).first().map { it.id })

            // Manual collections keep the user's order and never own the games.
            val rpg = d.collections.create("  Weekend  ")
            d.collections.addGames(rpg, listOf(c, a, c))
            assertEquals(listOf(c, a), d.collections.observeGames(CollectionKey.Manual(rpg)).first().map { it.id })
            d.collections.reorderGames(rpg, listOf(a))
            assertEquals(listOf(a, c), d.collections.observeGames(CollectionKey.Manual(rpg)).first().map { it.id })
            assertEquals(setOf(rpg), d.collections.observeCollectionsOf(a).first())
            val manual = d.collections.observeManual().first().single()
            assertEquals("Weekend", manual.name)
            assertEquals(2, manual.gameCount)
            d.collections.removeGames(rpg, listOf(c))
            assertEquals(listOf(a), d.collections.observeGames(CollectionKey.Manual(rpg)).first().map { it.id })
            d.collections.delete(rpg)
            assertTrue(d.collections.observeManual().first().isEmpty())
            assertEquals(4, d.games.observeAll().first().size)
        }
    }
}
