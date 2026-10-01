package io.github.matiyaaa.fuse.ui.shell.app

import io.github.matiyaaa.fuse.model.Destination
import io.github.matiyaaa.fuse.model.GameId
import io.github.matiyaaa.fuse.model.PlatformId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The top line marks where you are: Settings or Search while one is open, never the tab under it. */
class HudPageTest {
    private val home = Route.Root(Destination.HOME)

    @Test
    fun aTabsOwnPagesKeepTheTab() {
        assertNull(hudPage(listOf(home)))
        assertNull(hudPage(listOf(home, Route.GameInfo(GameId(1)))))
        assertNull(hudPage(listOf(Route.Root(Destination.SYSTEMS), Route.PlatformGames(PlatformId("snes")))))
    }

    @Test
    fun settingsAndEverythingOpenedFromItMarkSettings() {
        assertEquals(HudButton.SETTINGS, hudPage(listOf(home, Route.Settings())))
        assertEquals(HudButton.SETTINGS, hudPage(listOf(home, Route.Settings("appearance"), Route.Themes)))
        assertEquals(HudButton.SETTINGS, hudPage(listOf(home, Route.Settings("library"), Route.PickFile(FilePurpose.GAME))))
        assertEquals(HudButton.SETTINGS, hudPage(listOf(home, Route.Controls)))
    }

    @Test
    fun searchAndWhatItOpenedMarkSearch() {
        assertEquals(HudButton.SEARCH, hudPage(listOf(home, Route.Search)))
        assertEquals(HudButton.SEARCH, hudPage(listOf(home, Route.Search, Route.GameInfo(GameId(7)))))
    }
}
