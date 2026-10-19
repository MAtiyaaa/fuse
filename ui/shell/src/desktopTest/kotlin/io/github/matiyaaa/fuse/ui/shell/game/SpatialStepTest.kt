package io.github.matiyaaa.fuse.ui.shell.game

import androidx.compose.ui.geometry.Rect
import io.github.matiyaaa.fuse.model.Destination
import io.github.matiyaaa.fuse.model.GameId
import io.github.matiyaaa.fuse.model.NavAction
import io.github.matiyaaa.fuse.ui.shell.app.Route
import io.github.matiyaaa.fuse.ui.shell.app.spotlightKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The game page's buttons when they wrap, and what the second screen follows. */
class SpatialStepTest {
    // Play and the emulator on the first line; More wrapped under Play.
    private val wrapped = mapOf(
        0 to Rect(0f, 0f, 300f, 60f),
        1 to Rect(312f, 0f, 520f, 48f),
        2 to Rect(0f, 72f, 48f, 120f),
    )

    @Test
    fun downFromPlayReachesTheButtonUnderIt() {
        assertEquals(2, spatialStep(wrapped, 0, NavAction.DOWN))
        assertEquals(2, spatialStep(wrapped, 1, NavAction.DOWN))
        assertEquals(0, spatialStep(wrapped, 2, NavAction.UP))
    }

    @Test
    fun sidewaysStaysOnItsLineAndTheLastLineLeavesDownToThePage() {
        assertEquals(1, spatialStep(wrapped, 0, NavAction.RIGHT))
        assertNull(spatialStep(wrapped, 2, NavAction.RIGHT))
        assertNull(spatialStep(wrapped, 2, NavAction.DOWN))
    }

    @Test
    fun oneLineIsLeftToTheRowsOwnOrder() {
        val line = mapOf(0 to Rect(0f, 0f, 300f, 60f), 1 to Rect(312f, 0f, 520f, 60f))
        assertNull(spatialStep(line, 0, NavAction.RIGHT))
        assertNull(spatialStep(line, 0, NavAction.DOWN))
    }

    @Test
    fun aStopWrappedUnderAnotherIsReachedByDownAndUp() {
        val time = mapOf(0 to Rect(0f, 400f, 620f, 480f), 1 to Rect(0f, 488f, 260f, 540f))
        assertEquals(1, spatialStep(time, 0, NavAction.DOWN))
        assertEquals(0, spatialStep(time, 1, NavAction.UP))
        // Down from the lower one, and Up from the upper, leave the row.
        assertNull(spatialStep(time, 1, NavAction.DOWN))
        assertNull(spatialStep(time, 0, NavAction.UP))
    }

    @Test
    fun theSecondScreenFollowsOnlyThePageShowingAndNeverAHeldRoom() {
        val game = GameId(7)
        val page = Route.GameInfo(game)
        val home = Route.Root(Destination.HOME)
        assertEquals(game, spotlightKey(game, page, page, held = false))
        // Back on Home on the clock: the game's room is kept behind, the second screen lets go.
        assertNull(spotlightKey(game, home, home, held = true))
        // A page that set no room of its own.
        assertNull(spotlightKey(game, page, Route.Settings(), held = false))
    }
}
