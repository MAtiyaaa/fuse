package io.github.matiyaaa.fuse.ui.shell.store.impl

import io.github.matiyaaa.fuse.data.repo.GameSummary
import io.github.matiyaaa.fuse.model.GameId
import io.github.matiyaaa.fuse.model.GameTitles
import io.github.matiyaaa.fuse.model.PlatformId
import kotlin.test.Test
import kotlin.test.assertEquals

/** A system's play as the Systems page tells it: time in all, games played, the last and the most played. */
class SystemPickTest {
    private fun game(id: Long, title: String, lastPlayedAt: Long? = null, tracked: Long = 0, imported: Long = 0) = GameSummary(
        id = GameId(id), platformId = PlatformId("ps2"), titles = GameTitles(original = title),
        favorite = false, hidden = false, pinned = false, missing = false, removed = false,
        addedAt = 0, lastPlayedAt = lastPlayedAt, trackedSeconds = tracked, importedSeconds = imported, sessions = 0,
        releaseYear = null, dlcCount = 0, updateCount = 0, discCount = 0,
    )

    @Test
    fun theTimeIsEveryGamesAndTheFewAreTheLastAndTheMostPlayed() {
        val games = listOf(
            game(1, "Okami", lastPlayedAt = 300, tracked = 3_600),
            game(2, "Shadow of the Colossus", lastPlayedAt = 100, tracked = 36_000, imported = 7_200),
            game(3, "Ico"),
            game(4, "Gran Turismo 4", lastPlayedAt = 200, tracked = 60),
            // Time from another app, never started here.
            game(5, "Katamari Damacy", imported = 1_800),
        )
        val pick = systemPick(games, shown = 2)
        assertEquals(3_600L + 43_200 + 60 + 1_800, pick.seconds)
        assertEquals(3, pick.played, "those started here")
        assertEquals(listOf("Okami", "Gran Turismo 4"), pick.recent.map { it.displayTitle }, "newest first")
        assertEquals(listOf("Shadow of the Colossus", "Okami"), pick.most.map { it.displayTitle }, "its time with what came from elsewhere")
    }

    @Test
    fun aSystemNoOneHasPlayedHasNothingToShow() {
        val pick = systemPick(listOf(game(1, "Ico"), game(2, "Okami")), shown = 4)
        assertEquals(SystemPick(0, 0, emptyList(), emptyList()), pick)
    }
}
