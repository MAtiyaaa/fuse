package io.github.matiyaaa.fuse.ui.shell.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import io.github.matiyaaa.fuse.ui.designsystem.focus.dragReorder
import io.github.matiyaaa.fuse.ui.designsystem.focus.rememberDragReorderState
import io.github.matiyaaa.fuse.ui.designsystem.focus.reorderItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Hold and drag by touch, on a grid laid out like Systems (padding on every side). */
@OptIn(ExperimentalTestApi::class)
class DragReorderTest {
    private class Events {
        val dropped = mutableListOf<Pair<Any, Int>>()
        val held = mutableListOf<Any>()
        val clicked = mutableListOf<String>()
    }

    private fun ComposeUiTest.grid(events: Events, items: MutableList<String>) {
        setContent {
            val state = rememberDragReorderState()
            val grid = rememberLazyGridState()
            val shown = state.arrange(items) { it }
            Box(Modifier.size(600.dp, 500.dp)) {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    state = grid,
                    modifier = Modifier.dragReorder(
                        state,
                        visibleKeys = { grid.layoutInfo.visibleItemsInfo.map { it.key } },
                        scrollBy = { grid.scrollBy(it) },
                        longPressMs = 300,
                        onHoldReleased = { events.held += it },
                        onDrop = { key, to ->
                            events.dropped += key to to
                            val from = items.indexOf(key)
                            items.add(to, items.removeAt(from))
                        },
                    ),
                    contentPadding = PaddingValues(start = 40.dp, end = 40.dp, top = 16.dp, bottom = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    itemsIndexed(shown, key = { _, k -> k }) { _, k ->
                        Box(
                            Modifier
                                .animateItem(placementSpec = null)
                                .reorderItem(state, k)
                                .fillMaxWidth()
                                .aspectRatio(1.45f)
                                .background(Color.Gray)
                                .testTag(k)
                                .clickable { events.clicked += k },
                        )
                    }
                }
            }
        }
    }

    private fun ComposeUiTest.centre(tag: String): Offset {
        val b = onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
        return b.center
    }

    @Test
    fun holdAndDragMovesTheItem() = runComposeUiTest {
        val events = Events()
        val items = mutableStateListOf("a", "b", "c", "d", "e")
        grid(events, items)
        waitForIdle()
        val from = centre("a")
        val to = centre("c")
        onRoot().performTouchInput {
            down(from)
            advanceEventTime(450)
            moveTo(from + Offset(20f, 0f))
            advanceEventTime(50)
            moveTo((from + to) / 2f)
            advanceEventTime(50)
            moveTo(to)
            advanceEventTime(50)
            moveTo(to + Offset(1f, 0f))
            advanceEventTime(50)
            up()
        }
        waitForIdle()
        assertEquals(listOf<Pair<Any, Int>>("a" to 2), events.dropped)
        assertEquals(listOf("b", "c", "a", "d", "e"), items.toList())
        assertTrue(events.clicked.isEmpty(), "a drag is not a click")
    }

    @Test
    fun aHoldWithoutMovingIsALongPress() = runComposeUiTest {
        val events = Events()
        val items = mutableStateListOf("a", "b", "c")
        grid(events, items)
        waitForIdle()
        onRoot().performTouchInput {
            down(centre("b"))
            advanceEventTime(500)
            up()
        }
        waitForIdle()
        assertEquals(listOf<Any>("b"), events.held)
        assertTrue(events.dropped.isEmpty())
        assertTrue(events.clicked.isEmpty(), "a hold is not a click")
    }

    @Test
    fun aTapIsStillATap() = runComposeUiTest {
        val events = Events()
        val items = mutableStateListOf("a", "b", "c")
        grid(events, items)
        waitForIdle()
        onRoot().performTouchInput {
            down(centre("c"))
            advanceEventTime(60)
            up()
        }
        waitForIdle()
        assertEquals(listOf("c"), events.clicked)
        assertTrue(events.held.isEmpty() && events.dropped.isEmpty())
    }

    @Test
    fun aHoldAfterADropStillOpensOptions() = runComposeUiTest {
        val events = Events()
        val items = mutableStateListOf("a", "b", "c", "d")
        grid(events, items)
        waitForIdle()
        // A held item keeps frames coming (it may scroll the list), so the clock is stepped by hand.
        mainClock.autoAdvance = false
        val from = centre("a")
        val to = centre("c")
        onRoot().performTouchInput { down(from) }
        mainClock.advanceTimeBy(600)
        onRoot().performTouchInput { moveTo((from + to) / 2f) }
        mainClock.advanceTimeBy(100)
        onRoot().performTouchInput { moveTo(to) }
        mainClock.advanceTimeBy(300)
        onRoot().performTouchInput { up() }
        mainClock.advanceTimeBy(1_000)
        assertEquals(listOf("b", "c", "a", "d"), items.toList())
        onRoot().performTouchInput { down(centre("b")) }
        mainClock.advanceTimeBy(700)
        onRoot().performTouchInput { up() }
        mainClock.advanceTimeBy(300)
        assertEquals(listOf<Any>("b"), events.held)
    }
}
