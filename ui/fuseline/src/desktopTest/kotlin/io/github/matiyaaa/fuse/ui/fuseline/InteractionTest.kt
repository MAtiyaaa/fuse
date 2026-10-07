package io.github.matiyaaa.fuse.ui.fuseline

import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Real interaction sequences through a whole composition, the way Fuse's tabs are driven: the
 * keyboard and a controller's D-pad (both arrive as keys), the mouse and touch on the tab row, all
 * mixed and faster than a transition takes, with the window resized in the middle; and the same tabs
 * shown on two screens of different density at once. Frame by frame, no page ever jumps, the page in
 * front is always the one chosen last, and everything settles to that one page with nothing left over.
 */
@OptIn(ExperimentalTestApi::class)
class InteractionTest {
    private val tabs = listOf("Home", "Library", "Systems", "Apps", "Addons")

    /** Each state's position this frame. */
    private fun positions(t: MotionTransition<String>) = t.parts.associate { it.state to it.position }

    /** No state moves more than [step] of a page's travel between two frames: a jump would be a whole one. */
    private fun assertContinuous(before: Map<String, Float>, after: Map<String, Float>, what: String, step: Float = 0.5f) {
        for ((state, p) in after) {
            val was = before[state] ?: continue
            assertTrue(abs(p - was) <= step, "$what: $state jumped $was -> $p")
        }
    }

    /** The transition, the tab chosen, and every state's position at every frame drawn (input moves time on too). */
    private class Tabs(val transition: () -> MotionTransition<String>?, val chosen: () -> Int, val frames: List<Map<String, Float>>)

    private fun ComposeUiTest.tabsOnScreen(width: () -> Int): Tabs {
        var selected by mutableIntStateOf(0)
        var shown: MotionTransition<String>? = null
        val focus = FocusRequester()
        val frames = ArrayList<Map<String, Float>>()
        setContent {
            Column(
                Modifier.width(width().dp).focusRequester(focus).focusable().onKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                    when (e.key) {
                        Key.DirectionRight, Key.Tab -> { selected = (selected + 1).coerceAtMost(tabs.lastIndex); true }
                        Key.DirectionLeft -> { selected = (selected - 1).coerceAtLeast(0); true }
                        else -> false
                    }
                },
            ) {
                Row(Modifier.fillMaxWidth().height(40.dp)) {
                    tabs.forEachIndexed { i, name -> Box(Modifier.size(80.dp, 40.dp).testTag("tab.$name").clickable { selected = i }) }
                }
                val t = rememberMotionTransition(tabs[selected], Spring(1f, 900f), order = { tabs.indexOf(it) })
                shown = t
                androidx.compose.runtime.LaunchedEffect(t) {
                    while (true) androidx.compose.runtime.withFrameNanos { frames += positions(t) }
                }
                MotionTransitionLayout(t, Modifier.fillMaxWidth().height(300.dp), distance = 24.dp) { name ->
                    Box(Modifier.fillMaxWidth().height(300.dp).testTag("page.$name"))
                }
            }
        }
        mainClock.advanceTimeByFrame()
        focus.requestFocus()
        mainClock.advanceTimeByFrame()
        return Tabs({ shown }, { selected }, frames)
    }

    @Test
    fun rapidTabSwitchingFromEveryInputNeverJumpsAndSettlesOnTheLastChoice() = runComposeUiTest {
        mainClock.autoAdvance = false
        var width by mutableIntStateOf(900)
        val tabs = tabsOnScreen { width }
        val root = onNodeWithTag("tab.Home")
        fun frame(what: String) {
            mainClock.advanceTimeByFrame()
            // The chosen page is always the one the transition is heading for.
            assertEquals(this@InteractionTest.tabs[tabs.chosen()], tabs.transition()!!.targetState, what)
        }
        // Keyboard, then a controller's D-pad (the same keys), a press every two frames.
        repeat(3) { root.performKeyInput { pressKey(Key.DirectionRight) }; frame("keyboard"); frame("keyboard") }
        repeat(2) { root.performKeyInput { pressKey(Key.DirectionLeft) }; frame("d-pad back"); frame("d-pad back") }
        // The mouse and touch on the tab row, mid-flight, faster than a transition lasts.
        onNodeWithTag("tab.Addons").performMouseInput { click() }; frame("mouse")
        onNodeWithTag("tab.Home").performTouchInput { click() }; frame("touch")
        // The window is resized while pages are still travelling.
        width = 600
        frame("resize"); frame("resize")
        onNodeWithTag("tab.Systems").performMouseInput { click() }; frame("mouse after resize")
        width = 1000
        repeat(3) { root.performKeyInput { pressKey(Key.DirectionRight) }; frame("keys after resize") }
        // Then it settles: one page, the last chosen, in place.
        repeat(120) { frame("settling") }
        // Frame by frame through all of it (the frames the input itself moved time over too), no page jumped.
        assertTrue(tabs.frames.size > 140, "every frame seen: ${tabs.frames.size}")
        for (i in 1 until tabs.frames.size) assertContinuous(tabs.frames[i - 1], tabs.frames[i], "frame $i")
        val t = tabs.transition()!!
        assertEquals(listOf(this@InteractionTest.tabs[tabs.chosen()]), t.parts.map { it.state })
        assertEquals(0f, t.parts.single().position)
        assertEquals(1f, t.parts.single().presenceValue)
        assertEquals(0f, onNodeWithTag("page.${t.targetState}").fetchSemanticsNode().boundsInRoot.left)
    }

    @Test
    fun twoScreensOfDifferentDensityMoveTogetherEachInItsOwnPixels() = runComposeUiTest {
        mainClock.autoAdvance = false
        var tab by mutableStateOf("Home")
        val seen = arrayOfNulls<MotionTransition<String>>(2)
        setContent {
            Row {
                for ((i, density) in listOf(1f, 2f).withIndex()) {
                    CompositionLocalProvider(LocalDensity provides Density(density)) {
                        val t = rememberMotionTransition(tab, Spring(1f, 900f), order = { tabs.indexOf(it) })
                        seen[i] = t
                        MotionTransitionLayout(t, Modifier.size(200.dp, 100.dp), distance = 24.dp) { name ->
                            Box(Modifier.size(200.dp, 100.dp).testTag("screen$i.$name"))
                        }
                    }
                }
            }
        }
        mainClock.advanceTimeByFrame()
        tab = "Systems"
        // The change composes on one frame and its motion starts on the next: a few frames in.
        repeat(4) { mainClock.advanceTimeByFrame() }
        // Both screens are at the same point of the same motion...
        val a = seen[0]!!.partOf("Systems")!!.position
        val b = seen[1]!!.partOf("Systems")!!.position
        assertEquals(a, b)
        assertTrue(a > 0f && a < 1f, "on its way: $a")
        // ...and each draws it in its own pixels: twice the density, twice the pixels travelled.
        // The second screen starts where the first (200 pixels wide at density 1) ends.
        val moved0 = onNodeWithTag("screen0.Systems").fetchSemanticsNode().boundsInRoot.left
        val moved1 = onNodeWithTag("screen1.Systems").fetchSemanticsNode().boundsInRoot.left - 200f
        assertEquals(a * 24f, moved0, 0.5f)
        assertEquals(moved0 * 2f, moved1, 1f)
        // Changed back while both travel: both reverse from where they are.
        tab = "Home"
        mainClock.advanceTimeByFrame()
        assertEquals(seen[0]!!.partOf("Home")!!.position, seen[1]!!.partOf("Home")!!.position)
        mainClock.advanceTimeBy(2_000)
        for (t in seen) assertEquals(listOf("Home"), t!!.parts.map { it.state })
    }
}
