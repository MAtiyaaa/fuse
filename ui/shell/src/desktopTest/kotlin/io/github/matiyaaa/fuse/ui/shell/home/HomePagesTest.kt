package io.github.matiyaaa.fuse.ui.shell.home

import io.github.matiyaaa.fuse.model.HomeLayoutConfig
import io.github.matiyaaa.fuse.model.HomeMode
import io.github.matiyaaa.fuse.model.HomeWidget
import io.github.matiyaaa.fuse.model.WidgetKind
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HomePagesTest {
    private fun widget(k: WidgetKind, i: Int = 0) = HomeWidget(k.name.lowercase(), k, i)

    @Test
    fun oneHomeIsOnePage() {
        val home = HomeLayoutConfig(mode = HomeMode.CHANNELS)
        assertEquals(1, home.pageCount)
        assertEquals(HomeLayoutConfig.DefaultBoard, home.boardWidgets(0))
        assertTrue(home.boardWidgets(3).isEmpty())
        assertNull(home.storedBoard(0))
    }

    @Test
    fun pagesAreAddedEditedAndRemovedOnTheirOwn() {
        var home = HomeLayoutConfig(mode = HomeMode.CHANNELS).addPage().addPage()
        assertEquals(3, home.pageCount)
        assertEquals(listOf("page2", "page3"), home.pages.map { it.id })
        home = home.withBoard(1, listOf(widget(WidgetKind.CLOCK)))
        home = home.withBoard(2, listOf(widget(WidgetKind.STORAGE)))
        // The first page is untouched by the others.
        assertEquals(HomeLayoutConfig.DefaultBoard, home.boardWidgets(0))
        assertEquals(listOf(WidgetKind.CLOCK), home.boardWidgets(1).map { it.kind })
        home = home.movePage(2, 1)
        assertEquals(listOf(WidgetKind.STORAGE), home.boardWidgets(1).map { it.kind })
        home = home.removePage(1)
        assertEquals(2, home.pageCount)
        assertEquals(listOf(WidgetKind.CLOCK), home.boardWidgets(1).map { it.kind })
        // The first page can't be removed, and a page added after a removal gets an unused id.
        assertEquals(home, home.removePage(0))
        assertEquals(setOf("page2", "page3"), home.addPage().pages.map { it.id }.toSet())
    }

    @Test
    fun aHomeSavedBeforePagesReadsAsOnePage() {
        val json = Json { ignoreUnknownKeys = true }
        val old = """{"mode":"CHANNELS","board":[{"id":"clock","kind":"CLOCK","order":0}]}"""
        val home = json.decodeFromString(HomeLayoutConfig.serializer(), old)
        assertEquals(1, home.pageCount)
        assertEquals(listOf(WidgetKind.CLOCK), home.boardWidgets(0).map { it.kind })
    }
}
