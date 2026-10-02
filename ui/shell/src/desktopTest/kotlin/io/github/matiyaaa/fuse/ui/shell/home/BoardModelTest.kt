package io.github.matiyaaa.fuse.ui.shell.home

import io.github.matiyaaa.fuse.model.BoardSize
import io.github.matiyaaa.fuse.model.HomeLayoutConfig
import io.github.matiyaaa.fuse.model.HomeWidget
import io.github.matiyaaa.fuse.model.WidgetKind
import io.github.matiyaaa.fuse.model.isRow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BoardModelTest {
    @Test
    fun aHomeLeftAsItCameGetsTheDefaultBoard() {
        assertEquals(HomeLayoutConfig.DefaultBoard, HomeLayoutConfig().boardWidgets())
        // The list Home came with before boards had sizes counts as untouched too.
        val legacy = listOf(
            WidgetKind.CONTINUE_PLAYING, WidgetKind.SYSTEMS, WidgetKind.RECENTLY_ADDED, WidgetKind.FAVORITES,
            WidgetKind.RECENT_ACHIEVEMENTS, WidgetKind.PLAYTIME_WEEK, WidgetKind.CARTRIDGE_DOWNLOADS, WidgetKind.PINNED_APPS,
        ).mapIndexed { i, k -> HomeWidget(k.name.lowercase(), k, i) }
        assertEquals(HomeLayoutConfig.DefaultBoard, HomeLayoutConfig(widgets = legacy).boardWidgets())
    }

    @Test
    fun aBoardArrangedBeforeSizesKeepsItsOrderAndDropsHiddenWidgets() {
        val old = listOf(
            HomeWidget("clock", WidgetKind.CLOCK, 2),
            HomeWidget("systems", WidgetKind.SYSTEMS, 0),
            HomeWidget("storage", WidgetKind.STORAGE, 1, visible = false),
        )
        val board = HomeLayoutConfig(widgets = old).boardWidgets()
        assertEquals(listOf("systems", "clock"), board.map { it.id })
        assertEquals(listOf(0, 1), board.map { it.order })
    }

    @Test
    fun aSavedBoardIsUsedAsItIs() {
        val saved = listOf(HomeWidget("clock", WidgetKind.CLOCK, 0, width = 2, height = 2))
        assertEquals(saved, HomeLayoutConfig(board = saved).boardWidgets())
    }

    @Test
    fun sizesFallBackToTheKindsAndStayOnTheBoard() {
        assertEquals(BoardSize(2, 2), HomeWidget("c", WidgetKind.CONTINUE_PLAYING, 0).boardSize)
        assertEquals(BoardSize(1, 1), HomeWidget("c", WidgetKind.CLOCK, 0).boardSize)
        assertEquals(BoardSize(4, 3), HomeWidget("c", WidgetKind.CLOCK, 0, width = 9, height = 7).boardSize)
        assertEquals(BoardSize(2, 3), BoardSize(4, 3).fit(columns = 2))
    }

    @Test
    fun flowShowsOnlyRows() {
        assertTrue(HomeLayoutConfig.DefaultFlow.all { it.kind.isRow })
        assertTrue(!WidgetKind.CLOCK.isRow && !WidgetKind.STORAGE.isRow && !WidgetKind.PLAYTIME_WEEK.isRow)
    }
}
