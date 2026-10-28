package io.github.matiyaaa.fuse.ui.shell.audit

import io.github.matiyaaa.fuse.model.GameId
import io.github.matiyaaa.fuse.model.GameJournal
import io.github.matiyaaa.fuse.model.HomeLayoutConfig
import io.github.matiyaaa.fuse.model.HomeMode
import io.github.matiyaaa.fuse.model.HomeWidget
import io.github.matiyaaa.fuse.model.PlayStatus
import io.github.matiyaaa.fuse.model.WidgetKind
import io.github.matiyaaa.fuse.model.PadButton
import io.github.matiyaaa.fuse.ui.shell.store.FuseStore
import io.github.matiyaaa.fuse.ui.shell.store.LibraryOps
import io.github.matiyaaa.fuse.ui.shell.store.ResumeInfo
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

private const val HOUR = 60 * 60_000L
private const val DAY = 24 * HOUR

/** What 0.4.6 adds: Continue on a game's page, and the Journal. */
internal fun AuditDriver.fuse046Screens() {
    scenario("continue", "a game left mid-play") {
        useLibrary()
        val base = libraryStore
        val now = System.currentTimeMillis()
        // Left two hours ago on the Steam Deck, which DuckStation here carries on from.
        val library = object : LibraryOps by base.library {
            override suspend fun resumePoint(id: GameId) = ResumeInfo("DuckStation", now - 2 * HOUR, "Steam Deck")
        }
        show(object : FuseStore by base { override val library: LibraryOps = library })
        openGame("Last Ferry Home")
        shoot("Continue leads, Start fresh beside it", 1_400)
    }
    scenario("journal", "a game's Journal") {
        withJournals {
            useLibrary()
            openGame("Last Ferry Home")
            shoot("its status beside the heart", 1_200)
            // Play, its emulator, the heart, then the Journal.
            tap(PadButton.DPAD_RIGHT, 3)
            tap(PadButton.A)
            waitFor("Where you are")
            shoot("the Journal sheet", 1_200)
            focusText("Your rating")
            tap(PadButton.A)
            waitFor("Your rating now")
            shoot("rating it in half stars", 1_200)
            tap(PadButton.B)
            tap(PadButton.B)
            // The Journal card among the details, with its stars and a glimpse of the note.
            tap(PadButton.DPAD_DOWN, 3)
            shoot("the Journal card among the details", 1_400)
        }
    }
    scenario("journal", "the Library's Journal") {
        withJournals {
            useLibrary(prefs = { it.copy(libraryOpensOn = "INSTALLED") })
            libraryTab()
            waitFor("Journal")
            libraryFilter(4)
            shoot("every status, playing first", 1_400)
        }
    }
    scenario("journal", "Up next on Home") {
        withJournals {
            val board = listOf(HomeWidget("upnext", WidgetKind.UP_NEXT, 0, width = 4, height = 1), HomeWidget("upnext2", WidgetKind.UP_NEXT, 1, width = 2, height = 2))
            useLibrary { it.copy(home = HomeLayoutConfig(mode = HomeMode.CHANNELS, board = board)) }
            settle(1_500)
            tap(PadButton.DPAD_LEFT)
            shoot("a strip and a square", 1_500)
        }
    }
}

/**
 * A library with a Journal: one game being played (rated, with a note), two wanted, one beaten and
 * one completed; cleared again afterwards, so other scenarios see the library as it was.
 */
private fun AuditDriver.withJournals(block: () -> Unit) {
    val now = System.currentTimeMillis()
    val games = runBlocking { libraryData.games.observeAll().first() }.associateBy { it.displayTitle }
    fun id(title: String) = games[title]?.id ?: throw NotCovered("No $title in the audit library")
    val journals = mapOf(
        id("Last Ferry Home") to GameJournal(PlayStatus.PLAYING, now - 4 * DAY, 9, "Stuck at the lighthouse puzzle. Try the bell before the lamp next time."),
        id("Glasswing Requiem") to GameJournal(PlayStatus.WANT, now - 2 * DAY),
        id("Lumen Drift") to GameJournal(PlayStatus.WANT, now - 20 * DAY, 0, "A friend says the second act is the best part."),
        id("Emberline Saga") to GameJournal(PlayStatus.BEATEN, now - 60 * DAY, 10),
        id("Pixel Pilgrims") to GameJournal(PlayStatus.COMPLETED, now - 120 * DAY, 8),
    )
    runBlocking { for ((g, j) in journals) libraryData.games.setJournal(g, j) }
    try {
        block()
    } finally {
        runBlocking { for (g in journals.keys) libraryData.games.setJournal(g, GameJournal()) }
    }
}
