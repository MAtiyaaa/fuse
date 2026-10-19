package io.github.matiyaaa.fuse.ui.shell.game

import kotlin.test.Test
import kotlin.test.assertEquals

/** The words the line under a game's title uses. */
class GameHeaderTest {
    @Test
    fun aGenreLosesTheScrapersAbbreviation() {
        assertEquals("Role-playing", shortGenre("Role-playing (RPG)"))
        assertEquals("Turn-based strategy", shortGenre("Turn-based strategy (TBS)"))
        assertEquals("Adventure", shortGenre("Adventure"))
        // Nothing before the bracket: the genre stays as it is.
        assertEquals("(RPG)", shortGenre("(RPG)"))
    }

    @Test
    fun howLongToBeatIsRoundedAsPlayTimeIsWritten() {
        assertEquals("23 h", beatText(23 * 3600L + 600))
        assertEquals("1 h 30 min", beatText(90 * 60L + 40))
    }
}
