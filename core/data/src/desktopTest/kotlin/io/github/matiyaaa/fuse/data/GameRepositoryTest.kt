package io.github.matiyaaa.fuse.data

import io.github.matiyaaa.fuse.data.repo.GameSummary
import io.github.matiyaaa.fuse.model.ExternalLinks
import io.github.matiyaaa.fuse.model.GameMetadata
import io.github.matiyaaa.fuse.model.MetadataSource
import io.github.matiyaaa.fuse.model.PlatformId
import io.github.matiyaaa.fuse.model.SortOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals

class GameRepositoryTest {
    private val paths = listOf("/roms/gba/Alpha.gba", "/roms/gba/Bravo.gba", "/roms/snes/Charlie.sfc")

    private suspend fun TestDb.seed() = data.indexer.apply(
        report(
            folder("/roms/gba", paths.take(2).map { scanned(it) }),
            folder("/roms/snes", listOf(scanned(paths[2], platform = "snes")), platform = "snes"),
        ),
        1_000,
        testCleaner,
    ).addedIds

    @Test
    fun listsReactToChanges() = runBlocking {
        TestDb().use { t ->
            val (a, b, _) = t.seed()
            val emissions = Channel<List<GameSummary>>(Channel.UNLIMITED)
            val job = launch(Dispatchers.Default) { t.data.games.observeFavorites().collect { emissions.send(it) } }
            assertEquals(emptyList(), withTimeout(5_000) { emissions.receive() })
            t.data.games.setFavorite(b, true)
            assertEquals(listOf(b), withTimeout(5_000) { emissions.receive() }.map { it.id })
            job.cancel()

            assertEquals(mapOf(PlatformId("gba") to 2, PlatformId("snes") to 1), t.data.games.platformCounts().first())
            t.data.games.setHidden(a, true)
            assertEquals(listOf(b), t.data.games.observeByPlatform(PlatformId("gba")).first().map { it.id })
            assertEquals(listOf(a, b), t.data.games.observeByPlatform(PlatformId("gba"), includeHidden = true).first().map { it.id })
            assertEquals(listOf(a), t.data.games.observeHidden().first().map { it.id })
            t.data.games.setPinned(b, true)
            assertEquals(listOf(b), t.data.games.observePinned().first().map { it.id })
        }
    }

    @Test
    fun sortOrders() = runBlocking {
        TestDb().use { t ->
            val (a, b, c) = t.seed()
            val games = t.data.games
            games.applyMetadata(a, GameMetadata(releaseYear = 2004), onlyFillEmpty = false)
            games.applyMetadata(c, GameMetadata(releaseYear = 1991), onlyFillEmpty = false)
            t.data.playSessions.end(t.data.playSessions.start(b, null, 5_000), 65_000)
            t.data.playSessions.end(t.data.playSessions.start(c, null, 70_000), 80_000)

            suspend fun order(sort: SortOrder) = games.observeAll(sort).first().map { it.id }
            assertEquals(listOf(a, b, c), order(SortOrder.TITLE))
            assertEquals(listOf(c, b, a), order(SortOrder.RECENTLY_PLAYED))
            assertEquals(listOf(b, c, a), order(SortOrder.MOST_PLAYED))
            assertEquals(listOf(c, a, b), order(SortOrder.RELEASE_YEAR))
            assertEquals(listOf(c, b), games.observeRecentlyPlayed(5).first().map { it.id })
            assertEquals(listOf(a), games.observeUnplayed().first().map { it.id })
            assertEquals(3, games.observeRecentlyAdded(10).first().size)
        }
    }

    @Test
    fun metadataFillsButNeverOverwritesUserEdits() = runBlocking {
        TestDb().use { t ->
            val (a, b, _) = t.seed()
            val games = t.data.games
            games.applyMetadata(a, GameMetadata(description = "RomM text", genres = listOf("RPG"), source = MetadataSource.ROMM), "Alpha Quest", onlyFillEmpty = false)
            assertEquals("Alpha Quest", games.get(a)!!.displayTitle)

            games.applyMetadata(a, GameMetadata(description = "IGDB text", developer = "Dev", source = MetadataSource.IGDB), "Other", onlyFillEmpty = true)
            var meta = games.get(a)!!.metadata
            assertEquals("RomM text", meta.description)
            assertEquals("Dev", meta.developer)
            assertEquals("Alpha Quest", games.get(a)!!.displayTitle)

            games.applyMetadata(a, GameMetadata(description = "IGDB text", source = MetadataSource.IGDB), "Better Title", onlyFillEmpty = false)
            assertEquals("IGDB text", games.get(a)!!.metadata.description)
            assertEquals("Better Title", games.get(a)!!.displayTitle)
            games.rename(a, "Mine")
            games.applyMetadata(a, GameMetadata(), "Scraper Title", onlyFillEmpty = false)
            assertEquals("Mine", games.get(a)!!.displayTitle, "the custom title always wins")
            games.rename(a, null)
            assertEquals("Scraper Title", games.get(a)!!.displayTitle)

            // Metadata the user edited is only ever filled by scrapers.
            games.applyMetadata(b, GameMetadata(description = "Mine", source = MetadataSource.USER), onlyFillEmpty = false)
            games.applyMetadata(b, GameMetadata(description = "Scraped", publisher = "Pub", source = MetadataSource.IGDB), onlyFillEmpty = false)
            meta = games.get(b)!!.metadata
            assertEquals("Mine", meta.description)
            assertEquals("Pub", meta.publisher)
            assertEquals(MetadataSource.USER, meta.source)

            val links = games.updateLinks(b) { it.copy(igdbId = 9) }
            assertEquals(ExternalLinks(igdbId = 9), links)
            games.updateLinks(b) { it.copy(retroAchievementsGameId = 77) }
            assertEquals(ExternalLinks(retroAchievementsGameId = 77, igdbId = 9), games.get(b)!!.links)
            assertEquals(mapOf(77L to listOf(b)), games.idsForRetroAchievements(listOf(77, 78)))
        }
    }
}
