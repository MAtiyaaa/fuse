package io.github.matiyaaa.fuse.ui.shell.app

import io.github.matiyaaa.fuse.library.PlatformCatalog
import io.github.matiyaaa.fuse.model.BiosState
import io.github.matiyaaa.fuse.model.BiosStatus
import io.github.matiyaaa.fuse.model.GameId
import io.github.matiyaaa.fuse.model.LibraryLayout
import io.github.matiyaaa.fuse.model.PlatformId
import io.github.matiyaaa.fuse.ui.shell.store.Art
import io.github.matiyaaa.fuse.ui.shell.store.GameCard
import io.github.matiyaaa.fuse.ui.shell.store.PlatformCard
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** A game's background: its own, else its system's, never the previous game's. */
class RoomsTest {
    private val gba = PlatformCatalog.byId(PlatformId("gba"))!!
    private fun system(hero: String?) = PlatformCard(gba, 3, Art(hero = hero), null, false, 0, BiosStatus(BiosState.READY), LibraryLayout.ICON, emptyList())
    private fun game(hero: String? = null, grid: String? = null) =
        GameCard(GameId(1), gba.id, "Advance Wars", "GBA", gba.accent, Art(hero = hero, grid = grid))

    @Test
    fun ownBackgroundFirstThenTheSystems() {
        assertEquals("own.png", game(hero = "own.png").room(system("gba.png")).model)
        assertEquals("gba.png", game(grid = "wide.png").room(system("gba.png")).model)
        // Two games without their own art share the system's image, so the room stays put.
        assertEquals(game().room(system("gba.png")).model, game().copy(id = GameId(2)).room(system("gba.png")).model)
        assertEquals("wide.png", game(grid = "wide.png").room(system(null)).model)
        assertNull(game().room(null).model)
    }
}
