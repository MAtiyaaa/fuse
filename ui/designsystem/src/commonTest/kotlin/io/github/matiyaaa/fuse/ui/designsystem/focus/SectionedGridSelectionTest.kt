package io.github.matiyaaa.fuse.ui.designsystem.focus

import io.github.matiyaaa.fuse.model.NavAction
import io.github.matiyaaa.fuse.ui.designsystem.input.NavResult
import kotlin.test.Test
import kotlin.test.assertEquals

class SectionedGridSelectionTest {
    // Four columns: seven games on RomM, none here that RomM lacks, three on other devices.
    private val sizes = listOf(7, 0, 3)

    @Test
    fun downFromTheLastRowGoesToTheNextSectionInTheSameColumnSkippingEmptyOnes() {
        val s = SectionedGridSelection(0, 5)
        assertEquals(NavResult.MOVED, s.move(NavAction.DOWN, sizes, 4))
        assertEquals(2, s.section)
        assertEquals(1, s.index)
    }

    @Test
    fun aShortRowBelowTakesItsLastItem() {
        val s = SectionedGridSelection(0, 3)
        // Row two of the first section has three items: column 3 lands on its last.
        s.move(NavAction.DOWN, sizes, 4)
        assertEquals(0 to 6, s.section to s.index)
        s.move(NavAction.DOWN, sizes, 4)
        assertEquals(2 to 2, s.section to s.index)
    }

    @Test
    fun upFromTheFirstRowGoesToTheLastRowAboveAndUpAtTheTopIsLeftToThePage() {
        val s = SectionedGridSelection(2, 1)
        s.move(NavAction.UP, sizes, 4)
        assertEquals(0 to 5, s.section to s.index)
        s.move(NavAction.UP, sizes, 4)
        assertEquals(0 to 1, s.section to s.index)
        assertEquals(NavResult.IGNORED, s.move(NavAction.UP, sizes, 4))
    }

    @Test
    fun clampFindsASectionWithItems() {
        val s = SectionedGridSelection(1, 9)
        s.clamp(sizes)
        assertEquals(2 to 2, s.section to s.index)
        s.clamp(listOf(0, 0, 0))
        assertEquals(0 to 0, s.section to s.index)
    }
}
