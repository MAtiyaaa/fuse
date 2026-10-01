package io.github.matiyaaa.fuse.data

import io.github.matiyaaa.fuse.data.db.DesktopDatabase
import io.github.matiyaaa.fuse.data.db.FuseDatabase
import io.github.matiyaaa.fuse.data.repo.IndexDelta
import io.github.matiyaaa.fuse.model.ChildContent
import io.github.matiyaaa.fuse.model.ContentKind
import io.github.matiyaaa.fuse.model.EmulatorId
import io.github.matiyaaa.fuse.model.ExternalLinks
import io.github.matiyaaa.fuse.model.FolderInterpretation
import io.github.matiyaaa.fuse.model.FolderPolicy
import io.github.matiyaaa.fuse.model.GameMetadata
import io.github.matiyaaa.fuse.model.LibrarySourceId
import io.github.matiyaaa.fuse.model.LocationKind
import io.github.matiyaaa.fuse.model.MediaFillMode
import io.github.matiyaaa.fuse.model.MediaItem
import io.github.matiyaaa.fuse.model.MediaKind
import io.github.matiyaaa.fuse.model.MediaOwner
import io.github.matiyaaa.fuse.model.MediaSource
import io.github.matiyaaa.fuse.model.MetadataSource
import io.github.matiyaaa.fuse.model.PlatformId
import io.github.matiyaaa.fuse.model.ScannedGame
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LibraryIndexerTest {
    private val metroid = scanned("/roms/gba/Metroid Fusion (USA).gba", sizeBytes = 100)
    private val goldenSun = scanned("/roms/gba/Golden Sun (Europe).gba")

    @Test
    fun rescansNeverLoseUserData() = runBlocking {
        TestDb().use { t ->
            val d = t.data
            val first = d.indexer.apply(report(folder("/roms/gba", listOf(metroid, goldenSun))), now = 1_000, cleaner = testCleaner)
            assertEquals(2, first.added)
            val id = d.games.idByPath(metroid.path)!!

            // Everything the user (or a scraper) owns.
            d.games.rename(id, "My Metroid")
            d.games.setFavorite(id, true)
            d.games.setPinned(id, true)
            d.games.setEmulatorOverride(id, EmulatorId("mgba"))
            d.games.setFolderPolicyOverride(id, FolderPolicy.FILE)
            d.games.setLinks(id, ExternalLinks(retroAchievementsGameId = 5, steamGridDbGameId = 7))
            d.games.applyMetadata(id, GameMetadata(description = "Samus", genres = listOf("Action"), source = MetadataSource.IGDB), onlyFillEmpty = false)
            d.media.setCustom(MediaOwner.OfGame(id), MediaKind.BOXART, "/custom/box.png")
            val session = d.playSessions.start(id, EmulatorId("mgba"), now = 2_000)
            d.playSessions.end(session, now = 2_000 + 600_000)
            d.playSessions.importPlaytime(id, 3_600, "Daijisho")

            // File changed, Golden Sun deleted from disk.
            val second = d.indexer.apply(
                report(folder("/roms/gba", listOf(metroid.copy(sizeBytes = 200, modifiedAt = 5)))),
                now = 3_000,
                cleaner = testCleaner,
            )
            assertEquals(IndexDelta(updated = 1, missing = 1), second)

            val game = d.games.get(id)!!
            assertEquals("My Metroid", game.displayTitle)
            assertEquals("Metroid Fusion", game.titles.cleaned)
            assertEquals(200, game.location.sizeBytes)
            assertTrue(game.favorite)
            assertEquals(EmulatorId("mgba"), game.emulatorOverride)
            assertEquals(FolderPolicy.FILE, game.folderPolicyOverride)
            assertEquals(ExternalLinks(retroAchievementsGameId = 5, steamGridDbGameId = 7), game.links)
            assertEquals("Samus", game.metadata.description)
            assertEquals(600, game.play.trackedSeconds)
            assertEquals(3_600, game.play.importedSeconds)
            assertEquals("Daijisho", game.play.importedSource)
            assertEquals(1, game.play.sessions)
            assertTrue(d.games.summary(id)!!.pinned)
            assertEquals(MediaSource.USER, d.media.get(MediaOwner.OfGame(id)).boxart?.source)

            // Missing is a flag, never a deletion.
            val goldenId = d.games.idByPath(goldenSun.path)!!
            assertEquals(listOf(goldenId), d.games.observeMissing().first().map { it.id })
            assertEquals(listOf(id), d.games.observeAll().first().map { it.id })
            assertEquals(3_000, d.games.observe(goldenId).first()!!.missingSince)

            // Nothing changed: nothing written.
            val same = d.indexer.apply(report(folder("/roms/gba", listOf(metroid.copy(sizeBytes = 200, modifiedAt = 5)))), 3_500, testCleaner)
            assertTrue(same.isEmpty)

            // An incomplete scan never marks anything missing.
            val partial = d.indexer.apply(report(folder("/roms/gba", emptyList(), complete = false)), 3_600, testCleaner)
            assertTrue(partial.isEmpty)

            // The file comes back: same record, same id.
            d.games.setFavorite(goldenId, true)
            val back = d.indexer.apply(
                report(folder("/roms/gba", listOf(metroid.copy(sizeBytes = 200, modifiedAt = 5), goldenSun))),
                now = 4_000,
                cleaner = testCleaner,
            )
            assertEquals(IndexDelta(restored = 1), back)
            val restored = d.games.observe(goldenId).first()!!
            assertFalse(restored.missing)
            assertNull(restored.missingSince)
            assertTrue(restored.game.favorite)
            assertEquals(10L, d.folderState.lastModified("/roms/gba"))
        }
    }

    @Test
    fun aSystemTheUserChoseSurvivesRescans() = runBlocking {
        TestDb().use { t ->
            val d = t.data
            d.indexer.apply(report(folder("/roms/gba", listOf(metroid, goldenSun))), 1_000, testCleaner)
            val id = d.games.idByPath(metroid.path)!!
            d.games.setEmulatorOverride(id, EmulatorId("mgba"))
            d.games.setPlatformOverride(id, PlatformId("gbc"))
            d.games.get(id)!!.let { g ->
                assertEquals(PlatformId("gbc"), g.platformId)
                assertEquals(PlatformId("gbc"), g.platformOverride)
                assertEquals(PlatformId("gba"), g.scannedPlatformId)
                // The emulator was chosen for the old system.
                assertNull(g.emulatorOverride)
            }
            // A changed file is rewritten, and still keeps the user's system.
            d.indexer.apply(report(folder("/roms/gba", listOf(metroid.copy(sizeBytes = 999), goldenSun))), 2_000, testCleaner)
            assertEquals(PlatformId("gbc"), d.games.get(id)!!.platformId)
            assertEquals(999L, d.games.get(id)!!.location.sizeBytes)
            d.games.setPlatformOverride(id, null)
            assertEquals(PlatformId("gba"), d.games.get(id)!!.platformId)
        }
    }

    @Test
    fun removedGamesStayRemovedAcrossRescans() = runBlocking {
        TestDb().use { t ->
            val d = t.data
            d.indexer.apply(report(folder("/roms/gba", listOf(metroid, goldenSun))), 1_000, testCleaner)
            val id = d.games.idByPath(metroid.path)!!
            d.games.removeFromFuse(id)
            d.indexer.apply(report(folder("/roms/gba", listOf(metroid.copy(sizeBytes = 999), goldenSun))), 2_000, testCleaner)
            assertEquals(listOf(goldenSun.path), d.games.observeAll(includeHidden = true).first().map { d.games.get(it.id)!!.location.path })
            assertEquals(listOf(id), d.games.observeRemoved().first().map { it.id })
            d.games.restoreToFuse(id)
            assertEquals(2, d.games.observeAll().first().size)
        }
    }

    @Test
    fun localMediaOnlyFillsEmptyKinds() = runBlocking {
        TestDb().use { t ->
            val d = t.data
            val withMedia = metroid.copy(localMedia = mapOf(MediaKind.BOXART to "/media/box.png", MediaKind.HERO to "/media/hero.png"))
            d.indexer.apply(report(folder("/roms/gba", listOf(withMedia))), 1_000, testCleaner)
            val owner = MediaOwner.OfGame(d.games.idByPath(metroid.path)!!)
            assertEquals(MediaSource.LOCAL_FOLDER, d.media.get(owner).boxart?.source)

            d.media.setCustom(owner, MediaKind.BOXART, "/custom/box.png")
            d.media.putScraped(owner, listOf(MediaItem(MediaKind.LOGO, MediaSource.STEAMGRIDDB, remoteUrl = "https://x/logo.png")), MediaFillMode.FILL_MISSING)

            val moved = withMedia.copy(localMedia = mapOf(MediaKind.BOXART to "/media2/box.png", MediaKind.LOGO to "/media2/logo.png"))
            d.indexer.apply(report(folder("/roms/gba", listOf(moved))), 2_000, testCleaner)
            val media = d.media.get(owner)
            assertEquals("/custom/box.png", media.boxart?.localPath)
            assertEquals("/media2/box.png", media.all(MediaKind.BOXART).single { it.source == MediaSource.LOCAL_FOLDER }.localPath)
            assertEquals(listOf(MediaSource.STEAMGRIDDB), media.all(MediaKind.LOGO).map { it.source })
            assertEquals("/media/hero.png", media.hero?.localPath)
        }
    }

    @Test
    fun rommSwitchGameWithDlcIsPersistedAndReloaded() = runBlocking {
        val dir = createTempDirectory("fuse-switch").toFile()
        val dbPath = File(dir, "fuse.db").path
        val root = "/library/roms/switch/The Legend of Zelda - Tears of the Kingdom"
        val content = listOf(
            ChildContent(ContentKind.GAME, "base.nsp", "$root/base.nsp", false, 16_000_000_000),
            ChildContent(ContentKind.UPDATE, "update", "$root/update", true, 1_000_000_000),
            ChildContent(ContentKind.DLC, "Expansion Pass.nsp", "$root/dlc/Expansion Pass.nsp", false, 2_000_000),
            ChildContent(ContentKind.DLC, "Bonus Outfit.nsp", "$root/dlc/Bonus Outfit.nsp", false, 1_000),
            ChildContent(ContentKind.MANUAL, "manual.pdf", "$root/manual/manual.pdf", false, 5_000),
        )
        val zelda = ScannedGame(
            platformId = PlatformId("switch"),
            sourceId = LibrarySourceId(1),
            path = root,
            kind = LocationKind.FOLDER,
            launchPath = "$root/base.nsp",
            title = "The Legend of Zelda - Tears of the Kingdom",
            content = content,
            sizeBytes = 17_002_006_000,
            interpretation = FolderInterpretation.MULTI_FILE_GAME,
        )
        val scan = folder("/library/roms/switch", listOf(zelda), platform = "switch")

        val id = DesktopDatabase.openDriver(dbPath).use { driver ->
            val d = FuseData(FuseDatabase(driver))
            d.indexer.apply(report(scan), 1_000, testCleaner)
            val id = d.games.idByPath(root)!!
            d.games.rename(id, "Zelda TOTK")
            id
        }

        DesktopDatabase.openDriver(dbPath).use { driver ->
            val d = FuseData(FuseDatabase(driver))
            val game = d.games.get(id)!!
            assertEquals(content, game.content)
            assertEquals(LocationKind.FOLDER, game.location.kind)
            assertEquals(FolderInterpretation.MULTI_FILE_GAME, game.location.interpretation)
            assertEquals("$root/base.nsp", game.location.launchPath)
            val summary = d.games.summary(id)!!
            assertEquals(2, summary.dlcCount)
            assertEquals(1, summary.updateCount)

            // A new DLC downloaded through Cartridge: children change, the custom title stays.
            val dlc3 = ChildContent(ContentKind.DLC, "Extra.nsp", "$root/dlc/Extra.nsp", false, 3_000)
            val delta = d.indexer.apply(report(scan.copy(games = listOf(zelda.copy(content = content + dlc3)))), 2_000, testCleaner)
            assertEquals(1, delta.updated)
            val updated = d.games.getAll(listOf(id)).single()
            assertEquals(content + dlc3, updated.content)
            assertEquals("Zelda TOTK", updated.displayTitle)
            assertEquals(3, d.games.summary(id)!!.dlcCount)
        }
        dir.deleteRecursively()
        Unit
    }

    @Test
    fun childrenAreDeletedWithTheirGameOnlyWhenForgotten() = runBlocking {
        TestDb().use { t ->
            val d = t.data
            val disc = metroid.copy(discs = listOf(io.github.matiyaaa.fuse.model.Disc(1, "Disc 1", "/d1"), io.github.matiyaaa.fuse.model.Disc(2, "Disc 2", "/d2")))
            d.indexer.apply(report(folder("/roms/gba", listOf(disc))), 1_000, testCleaner)
            val id = d.games.idByPath(metroid.path)!!
            assertEquals(2, d.games.get(id)!!.discs.size)
            assertFalse(d.games.forgetMissing(id), "present games cannot be forgotten")
            d.indexer.apply(report(folder("/roms/gba", emptyList())), 2_000, testCleaner)
            assertTrue(d.games.forgetMissing(id))
            assertNull(d.games.get(id))
            assertEquals(listOf("0"), t.driver.strings("SELECT COUNT(*) FROM game_disc"))
        }
    }
}
