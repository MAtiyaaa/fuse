package io.github.matiyaaa.fuse.ui.shell.home

import io.github.matiyaaa.fuse.model.HomeWidget
import io.github.matiyaaa.fuse.model.WidgetKind
import kotlin.test.Test
import kotlin.test.assertEquals

class HomeArrangeTest {
    private fun w(id: String, order: Int, kind: WidgetKind = WidgetKind.CONTINUE_PLAYING) = HomeWidget(id, kind, order)

    private fun ids(list: List<HomeWidget>) = list.sortedBy { it.order }.map { it.id }

    @Test
    fun aShelfMovesDownAndUp() {
        val widgets = listOf(w("a", 0), w("b", 1), w("c", 2), w("d", 3))
        val blocks = listOf(listOf("a"), listOf("b"), listOf("c"), listOf("d"))
        assertEquals(listOf("b", "c", "a", "d"), ids(HomeArrange.moveBlock(widgets, blocks, 0, 2)))
        assertEquals(listOf("d", "a", "b", "c"), ids(HomeArrange.moveBlock(widgets, blocks, 3, 0)))
        // Renumbered from zero in the new order.
        assertEquals(listOf(0, 1, 2, 3), HomeArrange.moveBlock(widgets, blocks, 0, 2).sortedBy { it.order }.map { it.order })
    }

    @Test
    fun aShelfOfSeveralWidgetsMovesAsOne() {
        // "At a glance" holds two widgets; the shelf after it is one.
        val widgets = listOf(w("clock", 0), w("week", 1), w("games", 2))
        val blocks = listOf(listOf("clock", "week"), listOf("games"))
        assertEquals(listOf("games", "clock", "week"), ids(HomeArrange.moveBlock(widgets, blocks, 0, 1)))
    }

    @Test
    fun hiddenWidgetsStayBehindTheOneTheyFollowed() {
        // "x" shows nothing right now; it follows "a" wherever "a" goes, and "head" keeps the start.
        val widgets = listOf(w("head", 0), w("a", 1), w("x", 2), w("b", 3), w("c", 4))
        val blocks = listOf(listOf("a"), listOf("b"), listOf("c"))
        assertEquals(listOf("head", "b", "c", "a", "x"), ids(HomeArrange.moveBlock(widgets, blocks, 0, 2)))
    }

    @Test
    fun theDpadAndTouchAgree() {
        // One step down with the D-pad is a drag onto the next shelf.
        val widgets = listOf(w("a", 0), w("b", 1), w("c", 2))
        val blocks = listOf(listOf("a"), listOf("b"), listOf("c"))
        assertEquals(listOf("b", "a", "c"), ids(HomeArrange.moveBlock(widgets, blocks, 0, 1)))
        assertEquals(widgets, HomeArrange.moveBlock(widgets, blocks, 1, 1))
        assertEquals(widgets, HomeArrange.moveBlock(widgets, blocks, 0, 5))
    }

    @Test
    fun aChannelMovesAmongTheShownOnes() {
        val widgets = listOf(w("a", 0), w("hidden", 1), w("b", 2), w("c", 3))
        assertEquals(listOf("b", "c", "a", "hidden"), ids(HomeArrange.moveOne(widgets, listOf("a", "b", "c"), "a", 2)))
    }
}
