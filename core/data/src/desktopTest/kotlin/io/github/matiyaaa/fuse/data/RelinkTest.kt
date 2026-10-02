package io.github.matiyaaa.fuse.data

import io.github.matiyaaa.fuse.model.ChildContent
import io.github.matiyaaa.fuse.model.ContentKind
import io.github.matiyaaa.fuse.model.Disc
import io.github.matiyaaa.fuse.model.LibrarySourceKind
import io.github.matiyaaa.fuse.model.VolumeKind
import io.github.matiyaaa.fuse.model.VolumeRef
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RelinkTest {
    private val old = "/run/media/me/GAMES/ROMs"
    private val new = "/media/me/GAMES/ROMs"
    private val card = VolumeRef("uuid:SD", "SD card", VolumeKind.SD_CARD, removable = true, relativePath = "ROMs")

    private suspend fun TestDb.library(): Pair<io.github.matiyaaa.fuse.model.LibrarySourceId, io.github.matiyaaa.fuse.model.GameId> {
        val source = data.sources.add(old, "ROMs", LibrarySourceKind.ROMS_ROOT)
        val mgs = scanned("$old/psx/MGS.m3u", platform = "psx", source = source.value).copy(
            discs = listOf(Disc(1, "Disc 1", "$old/psx/MGS (Disc 1).chd"), Disc(2, "Disc 2", "$old/psx/MGS (Disc 2).chd")),
            content = listOf(ChildContent(ContentKind.MANUAL, "Manual", "$old/psx/MGS.pdf", false)),
        )
        data.indexer.apply(report(folder("$old/psx", listOf(mgs), platform = "psx")), now, testCleaner)
        val id = data.games.observeAll().first().single().id
        data.games.rename(id, "Metal Gear Solid")
        data.games.setFavorite(id, true)
        return source to id
    }

    @Test
    fun aMovedDriveTakesEveryPathAndKeepsEveryEdit() = TestDb().use { t ->
        runBlocking {
            val (source, id) = t.library()
            assertTrue(t.data.sources.relink(source, old, new, card))

            val moved = t.data.sources.get(source)!!
            assertEquals(new, moved.path)
            assertEquals(card, moved.volume)
            val game = t.data.games.get(id)!!
            assertEquals("$new/psx/MGS.m3u", game.location.path)
            assertEquals("$new/psx/MGS.m3u", game.location.launchPath)
            assertEquals(listOf("$new/psx/MGS (Disc 1).chd", "$new/psx/MGS (Disc 2).chd"), game.discs.map { it.path })
            assertEquals("$new/psx/MGS.pdf", game.content.single().path)
            assertEquals("Metal Gear Solid", game.displayTitle)
            assertTrue(game.favorite)

            // A scan at the new path finds the same game, not a second one.
            val again = scanned("$new/psx/MGS.m3u", platform = "psx", source = source.value).copy(
                discs = game.discs,
                content = game.content,
            )
            val delta = t.data.indexer.apply(report(folder("$new/psx", listOf(again), platform = "psx")), t.now, testCleaner)
            assertEquals(0, delta.added)
            assertEquals(0, delta.missing)
            assertEquals(1, t.data.games.observeAll().first().size)
        }
    }

    @Test
    fun relinkingOntoGamesThatAlreadyExistChangesNothing() = TestDb().use { t ->
        runBlocking {
            val (source, id) = t.library()
            // The new path was also added and scanned as a folder of its own.
            val other = t.data.sources.add(new, "ROMs (2)", LibrarySourceKind.ROMS_ROOT)
            t.data.indexer.apply(report(folder("$new/psx", listOf(scanned("$new/psx/MGS.m3u", platform = "psx", source = other.value)), platform = "psx")), t.now, testCleaner)

            assertFalse(t.data.sources.relink(source, old, new, card))
            assertEquals(old, t.data.sources.get(source)!!.path)
            assertEquals("$old/psx/MGS.m3u", t.data.games.get(id)!!.location.path)
        }
    }

    @Test
    fun siblingsSharingANamePrefixStayPut() = TestDb().use { t ->
        runBlocking {
            val (source, _) = t.library()
            val sibling = t.data.sources.add("${old}2", "ROMs2", LibrarySourceKind.ROMS_ROOT)
            t.data.indexer.apply(report(folder("${old}2/gba", listOf(scanned("${old}2/gba/a.gba", source = sibling.value)))), t.now, testCleaner)
            assertTrue(t.data.sources.relink(source, old, new, card))
            val paths = t.data.games.paths().map { it.second }.toSet()
            assertTrue("${old}2/gba/a.gba" in paths, "$paths")
        }
    }
}
