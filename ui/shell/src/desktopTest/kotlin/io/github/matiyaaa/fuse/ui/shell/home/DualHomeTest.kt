package io.github.matiyaaa.fuse.ui.shell.home

import androidx.compose.ui.unit.dp
import io.github.matiyaaa.fuse.library.PlatformCatalog
import io.github.matiyaaa.fuse.model.BiosState
import io.github.matiyaaa.fuse.model.BiosStatus
import io.github.matiyaaa.fuse.model.GameId
import io.github.matiyaaa.fuse.model.LibraryLayout
import io.github.matiyaaa.fuse.model.PlatformId
import io.github.matiyaaa.fuse.ui.shell.store.Art
import io.github.matiyaaa.fuse.ui.shell.store.GameCard
import io.github.matiyaaa.fuse.ui.shell.store.HomeFeed
import io.github.matiyaaa.fuse.ui.shell.store.PlatformCard
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Home on the touch screen below: its shelves, and how many rows of icons each zoom gives. */
class DualHomeTest {
    private val gba = PlatformCatalog.byId(PlatformId("gba"))!!
    private val snes = PlatformCatalog.byId(PlatformId("snes"))!!
    private fun game(id: Long) = GameCard(GameId(id), gba.id, "Game $id", "GBA", gba.accent, Art())
    private fun system(p: io.github.matiyaaa.fuse.model.Platform, games: Int) =
        PlatformCard(p, games, Art(), null, false, 0, BiosStatus(BiosState.READY), LibraryLayout.ICON, emptyList())

    @Test
    fun theShelvesAreThoseWithGamesThenAllThenEachSystem() {
        val feed = HomeFeed(
            continuePlaying = listOf(game(1), game(2)),
            recentlyPlayed = listOf(game(2), game(3)),
            recentlyAdded = listOf(game(4)),
            systems = listOf(system(gba, 3), system(snes, 0)),
        )
        val shelves = dualShelves(feed)
        assertEquals(listOf(RECENT, "new", "all", "system:gba"), shelves.map { it.key })
        // A game both still going and played lately is on Recent once.
        assertEquals(listOf(1L, 2L, 3L), shelves.first().games!!.map { it.id.value })
        // All and a system are read from the library when chosen.
        assertEquals(null, shelves.first { it.key == "all" }.games)
        assertEquals(gba.id, shelves.last().platform)
        assertTrue(dualShelves(HomeFeed()).isEmpty())
    }

    @Test
    fun eachZoomIsADifferentNumberOfRowsWhereTheScreenAllowsIt() {
        // The AYN Thor's lower screen: one row of large icons, two, three.
        assertEquals(listOf(1, 2, 3), (0..2).map { rowsFor(220.dp, it) })
        // A tall screen: never more than six, nor icons smaller than a thumb.
        assertEquals(listOf(4, 5, 6), (0..2).map { rowsFor(600.dp, it) })
        // Too short for two rows: one, whatever the zoom.
        assertEquals(listOf(1, 1, 1), (0..2).map { rowsFor(100.dp, it) })
    }
}
