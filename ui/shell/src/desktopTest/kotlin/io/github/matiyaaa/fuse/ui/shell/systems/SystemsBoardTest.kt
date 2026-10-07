package io.github.matiyaaa.fuse.ui.shell.systems

import io.github.matiyaaa.fuse.model.HomeLayoutConfig
import io.github.matiyaaa.fuse.model.HomePage
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The Systems page arranged like Home: new systems join, taken-off ones wait hidden, and its order is everyone's. */
class SystemsBoardTest {
    @Test
    fun aFirstVisitPutsEverySystemOnTheFirstPageInOrder() {
        val c = SystemsBoard.withNew(HomeLayoutConfig(), listOf("snes", "psx", "ps5"))
        assertEquals(listOf("snes", "psx", "ps5"), c.boardWidgets(0).map { it.target })
        assertTrue(c.boardWidgets(0).all { it.boardSize.width == 1 && it.boardSize.height == 1 })
    }

    @Test
    fun aNewSystemJoinsTheFirstPageAndArrangedOnesStayWhereTheyAre() {
        val arranged = HomeLayoutConfig(
            board = listOf(SystemsBoard.tile("psx").copy(width = 2, height = 2), SystemsBoard.tile("snes")),
            pages = listOf(HomePage("page2", listOf(SystemsBoard.tile("gba")))),
        )
        val c = SystemsBoard.withNew(arranged, listOf("snes", "psx", "gba", "ps5"))
        assertEquals(listOf("psx", "snes", "ps5"), c.boardWidgets(0).map { it.target })
        assertEquals(2, c.boardWidgets(0).first().boardSize.width)
        assertEquals(listOf("gba"), c.boardWidgets(1).map { it.target })
        // Nothing new: the very same arrangement.
        assertTrue(SystemsBoard.withNew(c, listOf("snes")) === c)
    }

    @Test
    fun aSystemTakenOffWaitsHiddenAndComesBackOnTheChosenPage() {
        val c = SystemsBoard.withNew(HomeLayoutConfig(), listOf("snes", "psx")).addPage()
        val off = c.withBoard(0, SystemsBoard.takeOff(c.boardWidgets(0), "system.psx"))
        // Hidden, not gone: it doesn't come back as new.
        assertEquals(off, SystemsBoard.withNew(off, listOf("snes", "psx")))
        assertEquals(listOf("psx"), SystemsBoard.hidden(off).map { it.target })
        val back = SystemsBoard.putBack(off, 1, SystemsBoard.hidden(off).single())
        assertEquals(listOf("snes"), back.boardWidgets(0).map { it.target })
        assertEquals(listOf("psx"), back.boardWidgets(1).map { it.target })
        assertTrue(back.boardWidgets(1).single().visible)
    }

    @Test
    fun theBoardsOrderIsTheSystemsOrderEverywhere() {
        val c = HomeLayoutConfig(
            board = listOf(SystemsBoard.tile("psx"), SystemsBoard.tile("snes")),
            pages = listOf(HomePage("page2", listOf(SystemsBoard.tile("gba")))),
        )
        assertEquals(listOf("psx", "snes", "gba", "n64"), SystemsBoard.order(c, listOf("snes", "n64", "gba")))
    }

    @Test
    fun aHandheldKeepsItsSystemsTheSizeTheyWereAndATvKeepsItsOwn() {
        // A Thor's upper screen (1080p at 6 inches): five across, as the grid always had there.
        assertEquals(5, SystemsBoard.columns(narrow = false, small = true, width = 731.dp))
        // Its lower screen, small and wider than tall: three.
        assertEquals(3, SystemsBoard.columns(narrow = false, small = true, width = 470.dp))
        // A TV keeps the board's own size: three across 1080p at TV density, six across a full 1080p.
        assertEquals(3, SystemsBoard.columns(narrow = false, small = false, width = 960.dp))
        assertEquals(6, SystemsBoard.columns(narrow = false, small = false, width = 1920.dp))
        // A phone held upright: two.
        assertEquals(2, SystemsBoard.columns(narrow = true, small = true, width = 400.dp))
    }
}
