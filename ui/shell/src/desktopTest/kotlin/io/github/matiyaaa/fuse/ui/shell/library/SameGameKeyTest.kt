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

    @Test
    fun aNameOnSeveralSystemsIsTold() {
        fun card(id: Long, title: String, system: String) = io.github.matiyaaa.fuse.ui.shell.store.GameCard(
            id = io.github.matiyaaa.fuse.model.GameId(id), platformId = io.github.matiyaaa.fuse.model.PlatformId(system),
            title = title, platformShort = system.uppercase(), accent = 0xFF5B8DEF, art = io.github.matiyaaa.fuse.ui.shell.store.Art.None,
        )
        val shared = namesOnSeveralSystems(
            listOf(card(1, "Deadpool", "ps3"), card(2, "Deadpool", "ps4"), card(3, "Deadpool", "xbox360"), card(4, "Psychonauts", "ps2"), card(5, "Okami", "ps2")),
        )
        assertEquals(setOf(sameTitleKey("Deadpool")), shared)
        // Two copies on one system are one system's game: nothing to tell apart by system.
        assertEquals(emptySet(), namesOnSeveralSystems(listOf(card(1, "Okami", "ps2"), card(2, "Okami (USA)", "ps2"))))
    }
}
