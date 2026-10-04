package io.github.matiyaaa.fuse.ui.fuseline

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class FuselineComposeTest {
    @Test
    fun aValueFollowsItsTargetAndArrives() = runComposeUiTest {
        var target by mutableStateOf(0f)
        var seen = -1f
        mainClock.autoAdvance = false
        setContent {
            val v by fuselineFloat(target, tween(200, easing = Curves.Linear))
            seen = v
        }
        mainClock.advanceTimeBy(32)
        assertEquals(0f, seen)
        target = 1f
        mainClock.advanceTimeBy(116)
        assertTrue(seen in 0.2f..0.8f, "half way through: $seen")
        mainClock.advanceTimeBy(400)
        assertEquals(1f, seen)
    }

    @Test
    fun appearLeavesThenRemovesItsContent() = runComposeUiTest {
        var shown by mutableStateOf(true)
        mainClock.autoAdvance = false
        setContent {
            Appear(shown, enter = fadeIn(tween(100)), exit = fadeOut(tween(100))) { BasicText("hello") }
        }
        mainClock.advanceTimeBy(32)
        onNodeWithText("hello").assertExists()
        shown = false
        mainClock.advanceTimeBy(48)
        // Still there while it fades.
        onNodeWithText("hello").assertExists()
        mainClock.advanceTimeBy(300)
        onNodeWithText("hello").assertDoesNotExist()
        shown = true
        mainClock.advanceTimeBy(300)
        onNodeWithText("hello").assertExists()
    }

    @Test
    fun swapShowsBothWhileCrossingThenOnlyTheNew() = runComposeUiTest {
        var page by mutableStateOf("one")
        mainClock.autoAdvance = false
        setContent {
            Swap(page, transitionSpec = { fadeIn(tween(120)) togetherWith fadeOut(tween(120)) }) { p ->
                Box(Modifier.size(40.dp)) { BasicText(p) }
            }
        }
        mainClock.advanceTimeBy(32)
        page = "two"
        mainClock.advanceTimeBy(48)
        onNodeWithText("one").assertExists()
        onNodeWithText("two").assertExists()
        mainClock.advanceTimeBy(500)
        onNodeWithText("one").assertDoesNotExist()
        onNodeWithText("two").assertExists()
    }

    @Test
    fun aKeyedGlideFollowsLayoutButGlidesToANewKey() = runComposeUiTest {
        var at by mutableStateOf(100f)
        var key by mutableStateOf("a")
        var glide: Glide? = null
        mainClock.autoAdvance = false
        setContent { glide = rememberGlide(at.dp, (at + 10f).dp, key) }
        mainClock.advanceTimeBy(32)
        // The same tab scrolled along: the bar stays on it, frame for frame.
        at = 160f
        mainClock.advanceTimeBy(16)
        assertEquals(160f, glide!!.start.value, 0.01f)
        // Another tab: it travels there.
        key = "b"
        at = 300f
        mainClock.advanceTimeBy(16)
        assertTrue(glide!!.start.value < 300f, "on its way: ${glide!!.start}")
        mainClock.advanceTimeBy(1_000)
        assertEquals(300f, glide!!.start.value, 0.2f)
    }

    @Test
    fun aTimelinePlaysAndSkips() = runComposeUiTest {
        val timeline = Timeline(1_000) { track("x") { at(0, 0f); at(1_000, 1f, Curves.Linear) } }
        var player: TimelinePlayer? = null
        var finished = false
        mainClock.autoAdvance = false
        setContent { player = rememberTimelinePlayer(timeline, onFinished = { finished = true }) }
        mainClock.advanceTimeBy(500)
        val mid = player!!["x"]
        assertTrue(mid in 0.3f..0.7f, "half way: $mid")
        player!!.skip()
        mainClock.advanceTimeBy(48)
        assertEquals(1f, player!!["x"])
        assertTrue(finished)
    }
}
