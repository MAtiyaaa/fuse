package io.github.matiyaaa.fuse.data

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TitleCleanupRepositoryTest {
    @Test
    fun previewApplyAndUndo() = runBlocking {
        TestDb().use { t ->
            val d = t.data
            val paths = listOf("/roms/gba/Metroid Fusion (USA).gba", "/roms/gba/Golden Sun (Europe) (En,Fr).gba", "/roms/gba/Tetris.gba")
            d.indexer.apply(report(folder("/roms/gba", paths.map { scanned(it) })), 1_000, testCleaner, useCleanedForNew = false)
            val (metroid, goldenSun, tetris) = paths.map { d.games.idByPath(it)!! }
            d.games.rename(goldenSun, "GS")

            val preview = d.titleCleanup.preview(testCleaner)
            assertEquals(listOf("Metroid Fusion (USA)" to "Metroid Fusion"), preview.map { it.before to it.after })
            assertEquals(listOf(metroid), preview.map { it.gameId })

            assertEquals(3, d.titleCleanup.apply(testCleaner))
            assertTrue(d.titleCleanup.observeCanUndo().first())
            assertEquals("Metroid Fusion", d.games.get(metroid)!!.displayTitle)
            assertEquals("GS", d.games.get(goldenSun)!!.displayTitle, "custom titles always win")
            assertEquals(listOf(metroid), d.games.search("metroid fusion").map { it.id })
            assertEquals(0, d.titleCleanup.apply(testCleaner), "nothing left to change, nothing recorded")

            assertEquals(3, d.titleCleanup.undoLast())
            val restored = d.games.get(metroid)!!
            assertEquals("Metroid Fusion (USA)", restored.displayTitle)
            assertEquals(paths[0], restored.location.path, "files are never renamed")
            assertFalse(d.games.get(tetris)!!.titles.useCleaned)
            assertEquals("GS", d.games.get(goldenSun)!!.displayTitle)
            assertFalse(d.titleCleanup.observeCanUndo().first())
            assertNull(d.titleCleanup.undoLast())
        }
    }
}
