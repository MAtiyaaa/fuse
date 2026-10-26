package io.github.matiyaaa.fuse.data

import io.github.matiyaaa.fuse.model.LibrarySourceKind
import io.github.matiyaaa.fuse.model.MediaFillMode
import io.github.matiyaaa.fuse.model.MediaItem
import io.github.matiyaaa.fuse.model.MediaKind
import io.github.matiyaaa.fuse.model.MediaOwner
import io.github.matiyaaa.fuse.model.MediaSource
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Library Health: what it finds, and that its repairs change only what they say. */
class LibraryHealthTest {
    @Test
    fun picturesWhoseFilesAreGoneAreRepairedAndChosenOnesKept() = runBlocking {
        TestDb().use { t ->
            t.data.sources.add("/roms", "ROMs", LibrarySourceKind.ROMS_ROOT)
            t.data.indexer.apply(report(folder("/roms/gba", listOf(scanned("/roms/gba/A.gba"), scanned("/roms/gba/B.gba"), scanned("/roms/gba/C.gba")))), t.now, testCleaner)
            val (a, b, c) = t.data.games.observeAll(includeHidden = true).first().sortedBy { it.displayTitle }.map { MediaOwner.OfGame(it.id) }
            // A: downloaded, with its web address; B: downloaded, no web address; C: the person's own.
            t.data.media.putScraped(a, listOf(MediaItem(MediaKind.BOXART, MediaSource.STEAMGRIDDB, localPath = "/cache/a.png", remoteUrl = "https://example.org/a.png")), MediaFillMode.FILL_MISSING)
            t.data.media.putScraped(b, listOf(MediaItem(MediaKind.BOXART, MediaSource.STEAMGRIDDB, localPath = "/cache/b.png")), MediaFillMode.FILL_MISSING)
            t.data.media.setCustom(c, MediaKind.BOXART, "/home/me/c.png")

            val health = t.data.libraryHealth
            val pictures = health.localPictures()
            assertEquals(3, pictures.size)
            assertEquals(Triple(1, 1, 1), health.plan(pictures))
            assertEquals(2, health.repairGone(pictures))

            val boxA = t.data.media.get(a).boxart!!
            assertNull(boxA.localPath)
            assertEquals("https://example.org/a.png", boxA.remoteUrl)
            assertNull(t.data.media.get(b).boxart)
            assertEquals("/home/me/c.png", t.data.media.get(c).boxart?.localPath)
        }
    }

    @Test
    fun picturesOfGamesNoLongerInTheLibraryAreFoundAndTidied() = runBlocking {
        TestDb().use { t ->
            t.data.sources.add("/roms", "ROMs", LibrarySourceKind.ROMS_ROOT)
            t.data.indexer.apply(report(folder("/roms/gba", listOf(scanned("/roms/gba/A.gba")))), t.now, testCleaner)
            val here = MediaOwner.OfGame(t.data.games.observeAll(includeHidden = true).first().single().id)
            val gone = MediaOwner.OfGame(io.github.matiyaaa.fuse.model.GameId(9_999))
            t.data.media.putScraped(here, listOf(MediaItem(MediaKind.BOXART, MediaSource.STEAMGRIDDB, remoteUrl = "https://example.org/here.png")), MediaFillMode.FILL_MISSING)
            t.data.media.putScraped(gone, listOf(MediaItem(MediaKind.BOXART, MediaSource.STEAMGRIDDB, remoteUrl = "https://example.org/gone.png"), MediaItem(MediaKind.LOGO, MediaSource.STEAMGRIDDB, remoteUrl = "https://example.org/gone-logo.png")), MediaFillMode.FILL_MISSING)

            assertEquals(2, t.data.libraryHealth.orphanPictures())
            assertEquals(2, t.data.libraryHealth.removeOrphanPictures())
            assertEquals(0, t.data.libraryHealth.orphanPictures())
            assertTrue(t.data.media.get(here).boxart != null, "a game in the library keeps its art")
        }
    }

    @Test
    fun theSameFileFoundTwiceIsListedNeverMerged() = runBlocking {
        TestDb().use { t ->
            t.data.sources.add("/roms", "ROMs", LibrarySourceKind.ROMS_ROOT)
            t.data.indexer.apply(report(folder("/roms/gba", listOf(
                scanned("/roms/gba/Golden Sun.gba", sizeBytes = 8_000_000),
                scanned("/roms/gba/copies/Golden Sun.gba", sizeBytes = 8_000_000),
                // The same name at another size is another dump, not the same file.
                scanned("/roms/gba/old/Golden Sun.gba", sizeBytes = 4_000_000),
                scanned("/roms/gba/Advance Wars.gba", sizeBytes = 8_000_000),
            ))), t.now, testCleaner)
            val groups = t.data.libraryHealth.sameFileTwice()
            assertEquals(1, groups.size)
            assertEquals(2, groups.single().size)
            assertEquals(4, t.data.games.observeAll(includeHidden = true).first().size, "nothing is merged")
        }
    }
}
