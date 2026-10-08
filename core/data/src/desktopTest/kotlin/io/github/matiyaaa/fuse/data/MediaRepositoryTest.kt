package io.github.matiyaaa.fuse.data

import io.github.matiyaaa.fuse.model.MediaFillMode
import io.github.matiyaaa.fuse.model.MediaItem
import io.github.matiyaaa.fuse.model.MediaKind
import io.github.matiyaaa.fuse.model.MediaOwner
import io.github.matiyaaa.fuse.model.MediaSource
import io.github.matiyaaa.fuse.model.PlatformId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class MediaRepositoryTest {
    private fun sgdb(kind: MediaKind, url: String) = MediaItem(kind, MediaSource.STEAMGRIDDB, remoteUrl = url)

    @Test
    fun systemArtGoesBackToFusesOwnAndComesBackExactlyOnUndo() = runBlocking {
        TestDb().use { t ->
            val media = t.data.media
            val owner = MediaOwner.OfPlatform(PlatformId("snes"))
            media.setCustom(owner, MediaKind.ICON, "/user/icon.png", focusX = 0.3f)
            media.putScraped(owner, listOf(MediaItem(MediaKind.LOGO, MediaSource.ART_PACK, remoteUrl = "pack/logo")), MediaFillMode.FILL_MISSING)
            val kept = media.rows(owner)
            assertEquals(2, kept.size)
            media.clearOwner(owner)
            assertNull(media.get(owner).icon)
            assertNull(media.get(owner).logo)
            media.putBack(owner, kept)
            val back = media.get(owner)
            assertEquals("/user/icon.png", back.icon?.localPath)
            assertEquals(0.3f, back.icon?.focusX)
            assertEquals("pack/logo", back.logo?.remoteUrl)
        }
    }

    @Test
    fun scrapersNeverTouchUserMedia() = runBlocking {
        TestDb().use { t ->
            val media = t.data.media
            val owner = MediaOwner.OfPlatform(PlatformId("gba"))
            media.setCustom(owner, MediaKind.BOXART, "/user/box.png")

            // Fill Missing: only kinds with nothing stored.
            val filled = media.putScraped(
                owner,
                listOf(sgdb(MediaKind.BOXART, "b1"), sgdb(MediaKind.HERO, "h1"), sgdb(MediaKind.LOGO, "l1")),
                MediaFillMode.FILL_MISSING,
            )
            assertEquals(2, filled)
            var set = media.get(owner)
            assertEquals(listOf(MediaSource.USER), set.all(MediaKind.BOXART).map { it.source })
            assertEquals("h1", set.hero?.remoteUrl)

            // Fill Missing respects the selected kinds too.
            assertEquals(0, media.putScraped(owner, listOf(sgdb(MediaKind.GRID, "g1")), MediaFillMode.FILL_MISSING, setOf(MediaKind.ICON)))

            // Replace Selected: only selected kinds, never USER items.
            media.putScraped(
                owner,
                listOf(sgdb(MediaKind.HERO, "h2"), sgdb(MediaKind.BOXART, "b2"), sgdb(MediaKind.LOGO, "l2")),
                MediaFillMode.REPLACE_SELECTED,
                selectedKinds = setOf(MediaKind.HERO, MediaKind.BOXART),
            )
            set = media.get(owner)
            assertEquals(listOf("h2"), set.all(MediaKind.HERO).map { it.remoteUrl })
            assertEquals(listOf("l1"), set.all(MediaKind.LOGO).map { it.remoteUrl })
            assertEquals(MediaSource.USER, set.boxart?.source, "custom art still wins")
            assertEquals(listOf(MediaSource.USER, MediaSource.STEAMGRIDDB), set.all(MediaKind.BOXART).map { it.source })

            // Replace All: every returned kind, still never USER.
            media.putScraped(owner, listOf(sgdb(MediaKind.BOXART, "b3"), sgdb(MediaKind.LOGO, "l3")), MediaFillMode.REPLACE_ALL)
            set = media.get(owner)
            assertEquals(listOf("/user/box.png", null), set.all(MediaKind.BOXART).map { it.localPath })
            assertEquals(listOf(null, "b3"), set.all(MediaKind.BOXART).map { it.remoteUrl })
            assertEquals(listOf("l3"), set.all(MediaKind.LOGO).map { it.remoteUrl })
            assertEquals("h2", set.hero?.remoteUrl, "kinds the provider did not return are kept")

            assertFailsWith<IllegalArgumentException> {
                media.putScraped(owner, listOf(MediaItem(MediaKind.ICON, MediaSource.USER, "/x")), MediaFillMode.REPLACE_ALL)
            }

            // Only explicit user actions remove USER media.
            media.resetCustom(owner, MediaKind.BOXART)
            assertEquals("b3", media.get(owner).boxart?.remoteUrl)

            media.adjust(owner, MediaKind.HERO, focusX = 0.2f, focusY = 1.7f, zoom = 1.5f)
            val hero = media.observe(owner).first().hero!!
            assertEquals(0.2f, hero.focusX)
            assertEquals(1f, hero.focusY)
            assertEquals(1.5f, hero.zoom)

            media.setCustom(owner, MediaKind.HERO, "/user/hero.png")
            assertEquals(2, media.reset(owner, MediaKind.HERO))
            assertNull(media.get(owner).hero)
        }
    }

    @Test
    fun everySourceRoundTrips() = runBlocking {
        TestDb().use { t ->
            val owner = MediaOwner.OfPlatform(PlatformId("snes"))
            val scraped = MediaSource.entries.filter { it != MediaSource.USER }
            t.data.media.putScraped(owner, scraped.map { MediaItem(MediaKind.SCREENSHOT, it, remoteUrl = it.name) }, MediaFillMode.REPLACE_ALL)
            assertEquals(scraped, t.data.media.get(owner).all(MediaKind.SCREENSHOT).map { it.source })
            assertEquals(MediaSource.ART_PACK, t.data.media.get(owner).all(MediaKind.SCREENSHOT).single { it.remoteUrl == "ART_PACK" }.source)
        }
    }

    @Test
    fun customMediaReplacesPreviousCustomOfSameKind() = runBlocking {
        TestDb().use { t ->
            val owner = MediaOwner.OfApp("org.example.app")
            t.data.media.setCustom(owner, MediaKind.ICON, "/a.png")
            t.data.media.setCustom(owner, MediaKind.ICON, "/b.png")
            assertEquals(listOf("/b.png"), t.data.media.get(owner).all(MediaKind.ICON).map { it.localPath })
        }
    }

    @Test
    fun missingKindQueriesFeedBulkFill() = runBlocking {
        TestDb().use { t ->
            val d = t.data
            d.indexer.apply(
                report(
                    folder("/roms/gba", listOf(scanned("/roms/gba/A.gba"), scanned("/roms/gba/B.gba"))),
                    folder("/roms/snes", listOf(scanned("/roms/snes/C.sfc", platform = "snes")), platform = "snes"),
                ),
                1_000,
                testCleaner,
            )
            val a = d.games.idByPath("/roms/gba/A.gba")!!
            val b = d.games.idByPath("/roms/gba/B.gba")!!
            val c = d.games.idByPath("/roms/snes/C.sfc")!!
            d.media.putScraped(MediaOwner.OfGame(a), listOf(sgdb(MediaKind.HERO, "h"), sgdb(MediaKind.LOGO, "l")), MediaFillMode.FILL_MISSING)
            d.media.setCustom(MediaOwner.OfGame(b), MediaKind.HERO, "/hero.png")

            val kinds = setOf(MediaKind.HERO, MediaKind.LOGO)
            assertEquals(mapOf(b to setOf(MediaKind.LOGO), c to kinds), d.media.gamesMissing(kinds))
            assertEquals(mapOf(b to setOf(MediaKind.LOGO)), d.media.gamesMissing(kinds, PlatformId("gba")))
            assertEquals(mapOf(MediaKind.HERO to 1, MediaKind.LOGO to 2), d.media.missingCounts(kinds))

            val batch = d.media.mediaFor(listOf(MediaOwner.OfGame(a), MediaOwner.OfGame(b), MediaOwner.OfGame(c)))
            assertEquals(2, batch.getValue(MediaOwner.OfGame(a)).items.size)
            assertEquals(1, batch.getValue(MediaOwner.OfGame(b)).items.size)
            assertEquals(0, batch.getValue(MediaOwner.OfGame(c)).items.size)
            assertEquals(batch, d.media.observeFor(batch.keys).first())
        }
    }
}
