package io.github.matiyaaa.fuse.ui.shell.store.impl

import io.github.matiyaaa.fuse.data.repo.GameSummary
import io.github.matiyaaa.fuse.model.GameId
import io.github.matiyaaa.fuse.model.GameTitles
import io.github.matiyaaa.fuse.model.PlatformId
import kotlin.test.Test
import kotlin.test.assertEquals

class MissingVersionsTest {
    private fun game(id: Long, title: String, platform: String = "switch", missing: Boolean = false, removed: Boolean = false) = GameSummary(
        id = GameId(id), platformId = PlatformId(platform), titles = GameTitles(original = title),
        favorite = false, hidden = false, pinned = false, missing = missing, removed = removed,
        addedAt = 0, lastPlayedAt = null, trackedSeconds = 0, importedSeconds = 0, sessions = 0,
        releaseYear = null, dlcCount = 0, updateCount = 0, discCount = 0,
    )

    @Test
    fun aVersionWhoseGameIsHereIsNotMissing() {
        val gone = game(1, "Super Mario Odyssey [0100000000010000][v0]", missing = true)
        val here = game(2, "Super Mario Odyssey [0100000000010000][v262144]")
        assertEquals(emptyList(), stillMissing(listOf(gone), listOf(gone, here)))
    }

    @Test
    fun aGameNotHereUnderAnyVersionIsStillMissing() {
        val gone = game(1, "Metroid Dread", missing = true)
        val other = game(2, "Metroid Prime Remastered")
        val elsewhere = game(3, "Metroid Dread", platform = "wiiu")
        val removed = game(4, "Metroid Dread", removed = true)
        assertEquals(listOf(gone), stillMissing(listOf(gone), listOf(gone, other, elsewhere, removed)))
    }
}
