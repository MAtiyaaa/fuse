package io.github.matiyaaa.fuse.ui.shell.library

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/** One game is one tile in the All views, whichever source names it and however. */
class SameGameKeyTest {
    @Test
    fun theSameGameFromTwoSourcesIsOneGame() {
        assertEquals(sameTitleKey("Mario & Sonic at the Olympic Games"), sameTitleKey("Mario and Sonic at the Olympic Games"))
        assertEquals(sameTitleKey("Mario Kart 8 Deluxe"), sameTitleKey("Mario Kart 8 Deluxe [0100152000022000][v131072]"))
        assertEquals(sameTitleKey("Red Dead Redemption"), sameTitleKey("Red Dead Redemption v0"))
        assertEquals(sameTitleKey("Legend of Zelda, The"), sameTitleKey("The Legend of Zelda"))
        assertEquals(sameTitleKey("Metroid Fusion"), sameTitleKey("Metroid Fusion (USA)"))
    }

    @Test
    fun otherGamesStayApart() {
        assertNotEquals(sameTitleKey("Mario Kart 8"), sameTitleKey("Mario Kart 8 Deluxe"))
        assertNotEquals(sameTitleKey("Red Dead Redemption"), sameTitleKey("Red Dead Redemption 2"))
        // A name that is nothing but brackets still has a key.
        assertNotEquals("", sameTitleKey("[BIOS]"))
    }
}
