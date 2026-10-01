package io.github.matiyaaa.fuse.ui.shell.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
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
import io.github.matiyaaa.fuse.ui.designsystem.focus.reorderHandle
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

    /**
     * Like Systems: the screen reads an immutable list that the store replaces after each drop, and
     * its drop works from the list it read in that composition.
     */
    private fun ComposeUiTest.screenGrid(events: Events, list: MutableState<List<String>>) {
        setContent {
            val state = rememberDragReorderState()
            val grid = rememberLazyGridState()
            val current = list.value
            val shown = state.arrange(current) { it }
            Box(Modifier.size(600.dp, 500.dp)) {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    state = grid,
                    modifier = Modifier.dragReorder(
                        state,
                        visibleKeys = { grid.layoutInfo.visibleItemsInfo.map { it.key } },
                        scrollBy = { grid.scrollBy(it) },
                        longPressMs = 300,
                        onDrop = { key, to ->
                            events.dropped += key to to
                            val from = current.indexOf(key)
                            if (from >= 0) list.value = current.toMutableList().apply { add(to, removeAt(from)) }
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
                                .testTag(k),
                        )
                    }
                }
            }
        }
    }

    private fun ComposeUiTest.drag(from: Offset, to: Offset) {
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
    }

    @Test
    fun aSecondDragWorksFromTheListAsItIsNow() = runComposeUiTest {
        val events = Events()
        val list = mutableStateOf(listOf("a", "b", "c", "d", "e"))
        screenGrid(events, list)
        waitForIdle()
        drag(centre("a"), centre("d"))
        assertEquals(listOf("b", "c", "d", "a", "e"), list.value)
        // Before the fix the drop still saw the first list and put "a" back where it began.
        drag(centre("d"), centre("b"))
        assertEquals(listOf("d", "b", "c", "a", "e"), list.value)
        drag(centre("a"), centre("d"))
        assertEquals(listOf("a", "d", "b", "c", "e"), list.value)
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

    /** A list of rows, each lifted only by the strip along its top (like a Home shelf's title). */
    private fun ComposeUiTest.shelves(events: Events, items: MutableList<String>, armed: String? = null) {
        setContent {
            val state = rememberDragReorderState()
            val list = rememberLazyListState()
            val shown = state.arrange(items) { it }
            androidx.compose.runtime.LaunchedEffect(Unit) { if (armed != null) state.arm(armed) }
            Box(Modifier.size(400.dp, 600.dp)) {
                LazyColumn(
                    state = list,
                    modifier = Modifier.dragReorder(
                        state,
                        visibleKeys = { list.layoutInfo.visibleItemsInfo.map { it.key } },
                        scrollBy = { list.scrollBy(it) },
                        longPressMs = 300,
                        requireHandle = true,
                        onHoldReleased = { events.held += it },
                        onDrop = { key, to ->
                            events.dropped += key to to
                            items.add(to, items.removeAt(items.indexOf(key)))
                        },
                    ),
                ) {
                    items(shown, key = { it }) { k ->
                        Column(Modifier.animateItem(placementSpec = null).reorderItem(state, k).fillMaxWidth()) {
                            Box(Modifier.fillMaxWidth().height(30.dp).background(Color.DarkGray).reorderHandle(state, k).testTag("h-$k"))
                            Box(Modifier.fillMaxWidth().height(90.dp).background(Color.Gray).testTag(k))
                        }
                    }
                }
            }
        }
    }

    private fun ComposeUiTest.drag(from: Offset, to: Offset, holdMs: Long) {
        mainClock.autoAdvance = false
        onRoot().performTouchInput { down(from) }
        mainClock.advanceTimeBy(holdMs)
        onRoot().performTouchInput { moveTo(from + Offset(0f, 20f)) }
        mainClock.advanceTimeBy(50)
        onRoot().performTouchInput { moveTo((from + to) / 2f) }
        mainClock.advanceTimeBy(50)
        onRoot().performTouchInput { moveTo(to) }
        mainClock.advanceTimeBy(300)
        onRoot().performTouchInput { up() }
        mainClock.advanceTimeBy(1_000)
    }

    @Test
    fun aListWithHandlesLiftsOnlyByTheHandle() = runComposeUiTest {
        val events = Events()
        val items = mutableStateListOf("a", "b", "c", "d")
        shelves(events, items)
        waitForIdle()
        // Held by its body, a row stays put: that hold belongs to what is inside the row.
        drag(centre("a"), centre("c"), holdMs = 600)
        assertEquals(listOf("a", "b", "c", "d"), items.toList())
        assertTrue(events.dropped.isEmpty() && events.held.isEmpty())
        // Held by its handle, it moves.
        drag(centre("h-a"), centre("c"), holdMs = 600)
        assertEquals(listOf("b", "c", "a", "d"), items.toList())
    }

    @Test
    fun anArmedItemMovesWithoutTheHold() = runComposeUiTest {
        val events = Events()
        val items = mutableStateListOf("a", "b", "c", "d")
        shelves(events, items, armed = "a")
        waitForIdle()
        // "Move this shelf" armed it: the first touch on any part of it drags straight away.
        drag(centre("a"), centre("c"), holdMs = 16)
        assertEquals(listOf<Pair<Any, Int>>("a" to 2), events.dropped)
    }
}
