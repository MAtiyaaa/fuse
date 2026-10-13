package io.github.matiyaaa.fuse.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Pinning a game makes sure Home shows its pinned games, in whichever style Home has. */
class HomeShowingTest {
    @Test
    fun networkShowsAHiddenRowAgainOrAddsIt() {
        val hidden = HomeLayoutConfig(mode = HomeMode.FLOW, widgets = listOf(HomeWidget("p", WidgetKind.PINNED_GAMES, 0, visible = false)))
        assertTrue(hidden.showing(WidgetKind.PINNED_GAMES).widgets.single().visible)
        val none = HomeLayoutConfig(mode = HomeMode.FLOW, widgets = listOf(HomeWidget("c", WidgetKind.CONTINUE_PLAYING, 0)))
        assertEquals(listOf(WidgetKind.CONTINUE_PLAYING, WidgetKind.PINNED_GAMES), none.showing(WidgetKind.PINNED_GAMES).widgets.map { it.kind })
    }

    @Test
    fun theBoardGetsItOnItsFirstPageOnce() {
        val board = HomeLayoutConfig(mode = HomeMode.CHANNELS, board = listOf(HomeWidget("c", WidgetKind.CLOCK, 0)))
        val once = board.showing(WidgetKind.PINNED_GAMES)
        assertEquals(listOf(WidgetKind.CLOCK, WidgetKind.PINNED_GAMES), once.boardWidgets(0).map { it.kind })
        assertEquals(once, once.showing(WidgetKind.PINNED_GAMES))
    }
}
