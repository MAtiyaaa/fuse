package io.github.matiyaaa.fuse.ui.shell.store

import io.github.matiyaaa.fuse.data.FuseData
import io.github.matiyaaa.fuse.data.db.DesktopDatabase
import io.github.matiyaaa.fuse.sync.TrophyUnlock
import io.github.matiyaaa.fuse.ui.shell.store.impl.TrophyHistory
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class TrophyHistoryStoreTest {
    private val dir = Files.createTempDirectory("trophies").toFile()

    @AfterTest
    fun tearDown() { dir.deleteRecursively() }

    @Test
    fun eachTrophyIsKeptOnceTheFirstTimeAndCopiesOfAGameShareTheirHistory(): Unit = runBlocking {
        val data = FuseData(DesktopDatabase.open(File(dir, "t.db").absolutePath))
        TrophyHistory.add(data, 1, listOf(TrophyUnlock("ps3", "1", "First", 5_000, "deck", "Steam Deck")))
        TrophyHistory.add(data, 1, listOf(TrophyUnlock("ps3", "1", "First", 9_000, "deck", "Steam Deck")))
        TrophyHistory.add(data, 2, listOf(TrophyUnlock("ps3", "1", "First", 3_000, "pc", "Gaming PC"), TrophyUnlock("ps3", "2", "Second", 4_000, "pc", "Gaming PC")))
        assertEquals(5_000, TrophyHistory.read(data, 1).single().unlockedAt, "the later one changes nothing")
        val both = TrophyHistory.of(data, listOf(1, 2))
        assertEquals(setOf("ps3:1", "ps3:2"), both.keys)
        assertEquals("pc", both.getValue("ps3:1").device)
    }
}
