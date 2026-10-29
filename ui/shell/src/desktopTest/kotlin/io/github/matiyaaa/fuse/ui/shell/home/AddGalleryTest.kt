package io.github.matiyaaa.fuse.ui.shell.home

import io.github.matiyaaa.fuse.model.HomeWidget
import io.github.matiyaaa.fuse.model.WidgetKind
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import kotlin.test.Test
import kotlin.test.assertEquals

/** The widget gallery lays its items out as its grid does, so the D-pad moves through them as they sit. */
class AddGalleryTest {
    private fun add(kind: WidgetKind) = Addable("add.$kind", kind.name, FuseIcons.Plus, HomeWidget(kind.name.lowercase(), kind, 0), section = widgetSection(kind))

    @Test
    fun headingsStartLinesAndItemsFillThemByWidth() {
        val items = listOf(WidgetKind.FAVORITES, WidgetKind.SPOTLIGHT, WidgetKind.CURRENT_GAME, WidgetKind.CLOCK, WidgetKind.STORAGE).map(::add)
        // Two wide, two wide, two wide under "Your games"; then a heading, and two small on one line.
        val spots = placeInLines(items, listOf(2, 2, 2, 1, 1), columns = 4)
        assertEquals(listOf(0 to 0, 0 to 2, 1 to 0, 2 to 0, 2 to 1), spots.map { it.line to it.column })
    }

    @Test
    fun everyWidgetHasAHeadingAndWordsUnderIt() {
        for (k in WidgetKind.entries) {
            assert(widgetBlurb(k).isNotBlank()) { "$k has no words" }
            assert(widgetSection(k).isNotBlank()) { "$k has no heading" }
            assert('\u2014' !in widgetBlurb(k)) { "$k's words have a dash" }
        }
        assertEquals("Films and music", widgetSection(WidgetKind.JELLYFIN_CONTINUE))
        assertEquals("This device", widgetSection(WidgetKind.CLOCK))
    }
}
