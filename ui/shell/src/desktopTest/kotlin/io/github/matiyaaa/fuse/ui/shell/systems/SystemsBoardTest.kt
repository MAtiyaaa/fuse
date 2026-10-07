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
        assertTrue(c.boardWidgets(0).all { it.boardSize == SystemsBoard.CARD })
    }

    @Test
    fun aNewSystemJoinsTheFirstPageAndArrangedOnesStayWhereTheyAre() {
        val arranged = HomeLayoutConfig(
            board = listOf(SystemsBoard.tile("psx").copy(width = 6, height = 4), SystemsBoard.tile("snes")),
            pages = listOf(HomePage("page2", listOf(SystemsBoard.tile("gba")))),
            grain = SystemsBoard.GRAIN,
        )
        val c = SystemsBoard.withNew(arranged, listOf("snes", "psx", "gba", "ps5"))
        assertEquals(listOf("psx", "snes", "ps5"), c.boardWidgets(0).map { it.target })
        assertEquals(6, c.boardWidgets(0).first().boardSize.width)
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
        assertEquals(5, SystemsBoard.cards(narrow = false, small = true, width = 731.dp))
        // Its lower screen, small and wider than tall: three.
        assertEquals(3, SystemsBoard.cards(narrow = false, small = true, width = 470.dp))
        // A TV keeps the board's own size: three across 1080p at TV density, six across a full 1080p.
        assertEquals(3, SystemsBoard.cards(narrow = false, small = false, width = 960.dp))
        assertEquals(6, SystemsBoard.cards(narrow = false, small = false, width = 1920.dp))
        // A phone held upright: two.
        assertEquals(2, SystemsBoard.cards(narrow = true, small = true, width = 400.dp))
    }

    @Test
    fun smallerSystemsFitOneOrTwoMoreInARowAndTheGridHasThreeColumnsToACard() {
        // Six cards across: eighteen columns, so a small square is two thirds of a card.
        assertEquals(18, SystemsBoard.columns(6, 0))
        // Smaller: seven across. Smallest: eight. Never more than that.
        assertEquals(21, SystemsBoard.columns(6, 1))
        assertEquals(24, SystemsBoard.columns(6, 2))
        assertEquals(24, SystemsBoard.columns(6, 9))
    }

    @Test
    fun aSystemIsASmallSquareOrWholeCardsAndResizesByThem() {
        fun size(w: Int, h: Int) = io.github.matiyaaa.fuse.model.BoardSize(w, h)
        // Anything narrower than a card is the small square; wider ones whole cards; heights whole cards.
        assertEquals(SystemsBoard.SMALL, SystemsBoard.allowed(size(1, 1), 18))
        assertEquals(SystemsBoard.CARD, SystemsBoard.allowed(size(4, 2), 18))
        assertEquals(size(6, 4), SystemsBoard.allowed(size(5, 4), 18))
        assertEquals(size(12, 6), SystemsBoard.allowed(size(30, 30), 18))
        // The controller: a card narrows to the small square and back, and grows a card at a time.
        val card = io.github.matiyaaa.fuse.ui.shell.home.BoardRect(0, 0, 3, 2)
        assertEquals(2, SystemsBoard.step(card, io.github.matiyaaa.fuse.model.NavAction.LEFT, 18)!!.width)
        assertEquals(null, SystemsBoard.step(card.copy(width = 2), io.github.matiyaaa.fuse.model.NavAction.LEFT, 18))
        assertEquals(3, SystemsBoard.step(card.copy(width = 2), io.github.matiyaaa.fuse.model.NavAction.RIGHT, 18)!!.width)
        assertEquals(6, SystemsBoard.step(card, io.github.matiyaaa.fuse.model.NavAction.RIGHT, 18)!!.width)
        assertEquals(4, SystemsBoard.step(card, io.github.matiyaaa.fuse.model.NavAction.DOWN, 18)!!.height)
        assertEquals(null, SystemsBoard.step(card, io.github.matiyaaa.fuse.model.NavAction.UP, 18))
    }

    @Test
    fun anArrangementFromBeforeHalfCardsKeepsEachSystemsSizeInCards() {
        val old = HomeLayoutConfig(
            board = listOf(
                SystemsBoard.tile("psx").copy(width = 2, height = 2, spots = mapOf(5 to io.github.matiyaaa.fuse.model.GridSpot(1, 0))),
                SystemsBoard.tile("snes").copy(width = 1, height = 1),
                SystemsBoard.tile("gba").copy(width = null, height = null),
            ),
        )
        val now = SystemsBoard.migrate(old)
        assertEquals(SystemsBoard.GRAIN, now.grain)
        assertEquals(listOf(6, 3, 3), now.boardWidgets(0).map { it.boardSize.width })
        assertEquals(listOf(4, 2, 2), now.boardWidgets(0).map { it.boardSize.height })
        // Places follow from the order now.
        assertTrue(now.boardWidgets(0).all { it.spots.isEmpty() })
        // Once is enough.
        assertTrue(SystemsBoard.migrate(now) === now)
    }

    @Test
    fun theLowerScreensLookStartsFromTheOrderWithEverySystemOneCard() {
        val main = HomeLayoutConfig(
            board = listOf(SystemsBoard.tile("psx").copy(width = 2, height = 6), SystemsBoard.tile("snes")),
            pages = listOf(HomePage("page2", listOf(SystemsBoard.tile("gba").copy(width = 6)))),
            grain = SystemsBoard.GRAIN,
        )
        val flipped = SystemsBoard.flippedFrom(main)
        assertEquals(listOf("psx", "snes"), flipped.boardWidgets(0).map { it.target })
        assertEquals(listOf("gba"), flipped.boardWidgets(1).map { it.target })
        assertTrue((flipped.boardWidgets(0) + flipped.boardWidgets(1)).all { it.boardSize == SystemsBoard.CARD })
    }
}
