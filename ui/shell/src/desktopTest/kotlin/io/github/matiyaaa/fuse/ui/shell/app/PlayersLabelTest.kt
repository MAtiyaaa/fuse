package io.github.matiyaaa.fuse.ui.shell.app

import io.github.matiyaaa.fuse.ui.shell.game.playersLabel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlayersLabelTest {
    @Test
    fun oneIsSingular() = assertEquals("1 player", playersLabel("1"))

    @Test
    fun countsAndRangesArePlural() {
        assertEquals("2 players", playersLabel("2"))
        assertEquals("1-4 players", playersLabel(" 1-4 "))
    }

    @Test
    fun wordsAreKept() = assertEquals("Single player", playersLabel("Single player"))

    @Test
    fun blankIsLeftOut() = assertNull(playersLabel("  "))
}
