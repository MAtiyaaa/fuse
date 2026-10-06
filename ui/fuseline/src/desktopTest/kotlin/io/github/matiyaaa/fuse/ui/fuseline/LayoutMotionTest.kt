package io.github.matiyaaa.fuse.ui.fuseline

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Layout motion and shared elements in real compositions: old bounds to new, a destination that
 * keeps moving (chased, never restarted), a parent scrolling (followed exactly in parent space),
 * sizes, nesting, a window resized mid-flight, and an element travelling between two screens while
 * its destination moves, reverses and changes rapidly.
 */
@OptIn(ExperimentalTestApi::class)
class LayoutMotionTest {
    private fun SemanticsNodeInteraction.bounds(): Rect = fetchSemanticsNode().boundsInRoot

    /** Frame by frame, the left edge never jumps by more than [maxStep] pixels. */
    private fun steps(xs: List<Float>, maxStep: Float, what: String) {
        for (i in 1 until xs.size) assertTrue(abs(xs[i] - xs[i - 1]) <= maxStep, "$what: jumped ${xs[i - 1]} -> ${xs[i]} at frame $i")
    }

    @Test
    fun anElementMovesFromItsOldPlaceAndChasesAMovingTarget() = runComposeUiTest {
        var x by mutableIntStateOf(0)
        mainClock.autoAdvance = false
        setContent {
            Box(Modifier.size(1000.dp)) {
                Box(Modifier.offset { IntOffset(x, 0) }.motionBounds(Spring(1f, 300f)).size(40.dp).testTag("t"))
            }
        }
        mainClock.advanceTimeByFrame()
        assertEquals(0f, onNodeWithTag("t").bounds().left, "the first place is taken at once")
        x = 600
        val xs = ArrayList<Float>()
        repeat(10) { mainClock.advanceTimeByFrame(); xs += onNodeWithTag("t").bounds().left }
        assertTrue(xs.last() in 1f..599f, "on its way: ${xs.last()}")
        // The target moves mid-flight (layout changing again): chased from here, no restart.
        x = 200
        repeat(60) { mainClock.advanceTimeByFrame(); xs += onNodeWithTag("t").bounds().left }
        steps(xs, 80f, "chase")
        assertEquals(200f, xs.last(), 0.5f)
    }

    @Test
    fun inParentSpaceAScrollingParentCarriesItAtOnce() = runComposeUiTest {
        var scroll by mutableIntStateOf(0)
        var own by mutableIntStateOf(0)
        mainClock.autoAdvance = false
        setContent {
            Box(Modifier.size(1000.dp)) {
                Box(Modifier.offset { IntOffset(0, -scroll) }.size(800.dp)) {
                    Box(Modifier.offset { IntOffset(own, 0) }.motionBounds(Spring(1f, 300f), MotionSpace.PARENT).size(40.dp).testTag("p"))
                    Box(Modifier.offset { IntOffset(own, 100) }.motionBounds(Spring(1f, 300f), MotionSpace.ROOT).size(40.dp).testTag("r"))
                }
            }
        }
        mainClock.advanceTimeByFrame()
        scroll = 300
        repeat(3) { mainClock.advanceTimeByFrame() }
        // Parent space: exactly with the scroll. Root space: travels to it.
        assertEquals(-300f, onNodeWithTag("p").bounds().top, 0.5f)
        assertTrue(onNodeWithTag("r").bounds().top > -200f + 0.5f, "root space animates the parent's move")
        // Its own move animates in either space.
        own = 400
        repeat(4) { mainClock.advanceTimeByFrame() }
        assertTrue(onNodeWithTag("p").bounds().left in 1f..399f)
        mainClock.advanceTimeBy(3_000)
        assertEquals(400f, onNodeWithTag("p").bounds().left, 0.5f)
        assertEquals(-200f, onNodeWithTag("r").bounds().top, 0.5f)
    }

    @Test
    fun sizesGrowAndNestedMotionsAddUp() = runComposeUiTest {
        var big by mutableStateOf(false)
        var shift by mutableIntStateOf(0)
        mainClock.autoAdvance = false
        setContent {
            Box(Modifier.size(1000.dp)) {
                Box(Modifier.offset { IntOffset(shift, 0) }.motionBounds(Spring(1f, 300f), MotionSpace.ROOT).size(500.dp)) {
                    Box(Modifier.offset { IntOffset(shift, 0) }.motionBounds(Spring(1f, 300f)).motionBounds(Spring(1f, 300f), animateSize = true).size(if (big) 150.dp else 50.dp).testTag("n"))
                }
            }
        }
        mainClock.advanceTimeByFrame()
        big = true
        shift = 100
        // The change composes, the motions start on the next frame, and move on the one after.
        repeat(4) { mainClock.advanceTimeByFrame() }
        val mid = onNodeWithTag("n").bounds()
        assertTrue(mid.width in 51f..149f, "growing: ${mid.width}")
        assertTrue(mid.left in 1f..199f, "parent and child both on their way: ${mid.left}")
        mainClock.advanceTimeBy(3_000)
        val end = onNodeWithTag("n").bounds()
        assertEquals(150f, end.width, 0.5f)
        assertEquals(200f, end.left, 0.5f)
    }

    @Test
    fun aWindowResizedMidFlightIsChased() = runComposeUiTest {
        // Within the test window's 1024 pixels, so the box is the width asked.
        var width by mutableIntStateOf(900)
        mainClock.autoAdvance = false
        setContent {
            Box(Modifier.size(1200.dp)) {
                Box(Modifier.width(width.dp).size(width.dp, 200.dp)) {
                    Box(Modifier.align(Alignment.TopEnd).motionBounds(Spring(1f, 300f)).size(40.dp).testTag("w"))
                }
            }
        }
        mainClock.advanceTimeByFrame()
        assertEquals(860f, onNodeWithTag("w").bounds().left)
        width = 500
        val xs = ArrayList<Float>()
        repeat(4) { mainClock.advanceTimeByFrame(); xs += onNodeWithTag("w").bounds().left }
        width = 1000 // resized again while it travels
        repeat(80) { mainClock.advanceTimeByFrame(); xs += onNodeWithTag("w").bounds().left }
        steps(xs, 120f, "resize")
        assertEquals(960f, xs.last(), 0.5f)
    }

    // ------------------------------------------------------------------ shared elements

    @Test
    fun aSharedElementTravelsChasesAMovingDestinationAndReverses() = runComposeUiTest {
        var detail by mutableStateOf(false)
        var drift by mutableIntStateOf(0)
        val shared = SharedMotion(Spring(1f, 300f))
        mainClock.autoAdvance = false
        setContent {
            Box(Modifier.size(1000.dp)) {
                if (!detail) {
                    Box(Modifier.offset(20.dp, 20.dp).sharedMotion(shared, "game", corner = 8.dp).size(60.dp).testTag("tile"))
                } else {
                    Box(Modifier.offset { IntOffset(400 + drift, 300) }.sharedMotion(shared, "game", corner = 0.dp).size(300.dp).testTag("hero"))
                }
            }
        }
        mainClock.advanceTimeByFrame()
        detail = true
        mainClock.advanceTimeByFrame()
        // It appears exactly where the tile was.
        val start = onNodeWithTag("hero").bounds()
        assertEquals(20f, start.left, 1f)
        assertEquals(60f, start.width, 1f)
        val lefts = ArrayList<Float>()
        repeat(8) { mainClock.advanceTimeByFrame(); lefts += onNodeWithTag("hero").bounds().left }
        // Its destination moves (layout settling, a scroll): chased, never missed.
        drift = 150
        repeat(80) { mainClock.advanceTimeByFrame(); lefts += onNodeWithTag("hero").bounds().left }
        steps(lefts, 100f, "travel")
        val end = onNodeWithTag("hero").bounds()
        assertEquals(550f, end.left, 1f)
        assertEquals(300f, end.width, 1f)
        // And back: the tile travels from where the hero is shown now.
        detail = false
        mainClock.advanceTimeByFrame()
        val back = onNodeWithTag("tile").bounds()
        assertEquals(550f, back.left, 2f)
        mainClock.advanceTimeBy(3_000)
        assertEquals(20f, onNodeWithTag("tile").bounds().left, 1f)
    }

    @Test
    fun rapidChangesHandOverFromWhereItIsShown() = runComposeUiTest {
        var which by mutableIntStateOf(0)
        val shared = SharedMotion(Spring(1f, 300f))
        val spots = listOf(0 to 0, 600 to 0, 300 to 500)
        mainClock.autoAdvance = false
        setContent {
            Box(Modifier.size(1000.dp)) {
                val (x, y) = spots[which]
                androidx.compose.runtime.key(which) {
                    Box(Modifier.offset(x.dp, y.dp).sharedMotion(shared, "k").size(50.dp).testTag("s$which"))
                }
            }
        }
        mainClock.advanceTimeByFrame()
        var shown = onNodeWithTag("s0").bounds().left
        for (step in 1..9) {
            which = step % 3
            mainClock.advanceTimeByFrame()
            // It starts where the last one was shown, give or take the one frame of travel in between.
            val now = onNodeWithTag("s$which").bounds().left
            assertTrue(abs(now - shown) <= 100f, "step $step starts at $now, the last one was shown at $shown")
            repeat(3) { mainClock.advanceTimeByFrame() }
            shown = onNodeWithTag("s$which").bounds().left
        }
        mainClock.advanceTimeBy(3_000)
        assertEquals(spots[9 % 3].first.toFloat(), onNodeWithTag("s0").bounds().left, 1f)
    }
}
