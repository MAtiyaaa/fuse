package io.github.matiyaaa.fuse.ui.shell.library

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** All's shelves size their tiles to the screen: a row of games on a phone, never giant ones. */
class ShelfTileTest {
    @Test
    fun aPhoneHeldUprightShowsARowOfGamesAndTheEdgeOfTheNext() {
        val phone = shelfTile(393.dp, 16.dp, 136.dp)
        assertTrue(phone < 110.dp && phone >= 84.dp, "$phone")
        // Three whole tiles and part of a fourth, past the gutter.
        val shown = (393.dp - 40.dp) / (phone + 16.dp)
        assertTrue(shown > 3f && shown < 4f, "$shown")
    }

    @Test
    fun aWideScreenKeepsTheThemesOwnTileAndNarrowOnesNeverGoTooSmall() {
        assertEquals(136.dp, shelfTile(2560.dp, 16.dp, 136.dp))
        assertEquals(84.dp, shelfTile(200.dp, 16.dp, 136.dp))
        assertEquals(84.dp, shelfTile(393.dp, 16.dp, 60.dp), "never under the smallest, even with a tiny theme tile")
    }
}
