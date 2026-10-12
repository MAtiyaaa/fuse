package io.github.matiyaaa.fuse.ui.designsystem.input

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * A computer's touch screen reaches Compose Desktop as the mouse, so a held pointer dragging a list
 * scrolls it and letting go flings it on ([dragScroll]), while a click, a long press, a finger and
 * whatever inside takes the drag first all behave as before. And a drag never lights up what passes
 * under it ([mouseHover], [InputRouter.pointer]).
 */
@OptIn(ExperimentalTestApi::class, ExperimentalFoundationApi::class)
class DragScrollTest {
    private class Taps {
        var clicked: Int? = null
        var held: Int? = null
    }

    /** A hundred rows of 50 px (the test's density is 1), with clicks and holds, in a 400 px column. */
    @Composable
    private fun Rows(list: LazyListState, taps: Taps, on: Boolean = true, modifier: Modifier = Modifier.dragScroll(list)) {
        CompositionLocalProvider(LocalPointerDragScroll provides on) {
            LazyColumn(state = list, modifier = Modifier.size(300.dp, 400.dp).testTag("list").then(modifier)) {
                items(100) { i ->
                    Box(Modifier.fillMaxWidth().height(50.dp).combinedClickable(onLongClick = { taps.held = i }) { taps.clicked = i })
                }
            }
        }
    }

    private val LazyListState.at get() = firstVisibleItemIndex * 50 + firstVisibleItemScrollOffset

    @Test
    fun aHeldPointerDragsTheListAndLettingGoFlingsItOn() = runComposeUiTest {
        lateinit var list: LazyListState
        val taps = Taps()
        setContent { list = rememberLazyListState(); Rows(list, taps) }
        onNodeWithTag("list").performMouseInput {
            moveTo(Offset(150f, 350f))
            press()
            repeat(20) { moveBy(Offset(0f, -15f), delayMillis = 8) }
        }
        waitForIdle()
        // Under the pointer, the slop aside: 300 px of drag less at most a finger's slop.
        val dragged = list.at
        assertTrue(dragged in 250..300, "dragged to $dragged")
        onNodeWithTag("list").performMouseInput { release() }
        waitForIdle()
        // Let go at speed, it carries on well past where the pointer left it.
        assertTrue(list.at > dragged + 200, "flung from $dragged to ${list.at}")
        // And the row it started on was dragged, not clicked or held.
        assertNull(taps.clicked)
        assertNull(taps.held)
    }

    @Test
    fun aClickAndAStillHoldStillReachTheRow() = runComposeUiTest {
        lateinit var list: LazyListState
        val taps = Taps()
        setContent { list = rememberLazyListState(); Rows(list, taps) }
        onNodeWithTag("list").performMouseInput { click(Offset(150f, 125f)) }
        waitForIdle()
        assertEquals(2, taps.clicked)
        // A press that trembles a little (less than the slop) and stays is a long press: the row's options.
        onNodeWithTag("list").performMouseInput {
            moveTo(Offset(150f, 225f))
            press()
            moveBy(Offset(2f, 3f), delayMillis = 16)
            advanceEventTime(1_000)
            release()
        }
        waitForIdle()
        assertEquals(4, taps.held)
        assertEquals(0, list.at)
    }

    @Test
    fun aFingerScrollsTheListByItselfAndNoFurther() = runComposeUiTest {
        // The same swipe by touch, with and without dragScroll: a finger is the list's own, never moved twice.
        val ends = ArrayList<Int>()
        for (withDrag in listOf(false, true)) {
            lateinit var list: LazyListState
            setContent { list = rememberLazyListState(); Rows(list, Taps(), modifier = if (withDrag) Modifier.dragScroll(list) else Modifier) }
            onNodeWithTag("list").performTouchInput {
                down(Offset(150f, 350f))
                repeat(10) { moveBy(Offset(0f, -20f), delayMillis = 8) }
                up()
            }
            waitForIdle()
            ends += list.at
        }
        assertTrue(ends[0] > 0)
        assertEquals(ends[0], ends[1])
    }

    @Test
    fun whereFingersArriveAsFingersAHeldMouseLeavesTheListAlone() = runComposeUiTest {
        // Android: LocalPointerDragScroll is off, so a mouse drag does what it always did there (nothing).
        lateinit var list: LazyListState
        setContent { list = rememberLazyListState(); Rows(list, Taps(), on = false) }
        onNodeWithTag("list").performMouseInput {
            moveTo(Offset(150f, 350f))
            press()
            repeat(10) { moveBy(Offset(0f, -20f), delayMillis = 8) }
            release()
        }
        waitForIdle()
        assertEquals(0, list.at)
    }

    @Test
    fun aRowAcrossInsideAColumnDownTakesTheDragItsOwnWayOnly() = runComposeUiTest {
        lateinit var column: LazyListState
        lateinit var row: LazyListState
        setContent {
            column = rememberLazyListState()
            row = rememberLazyListState()
            CompositionLocalProvider(LocalPointerDragScroll provides true) {
                LazyColumn(state = column, modifier = Modifier.size(400.dp, 400.dp).testTag("column").dragScroll(column)) {
                    item {
                        LazyRow(state = row, modifier = Modifier.fillMaxWidth().height(100.dp).dragScroll(row, Orientation.Horizontal)) {
                            items(50) { Box(Modifier.width(80.dp).height(100.dp)) }
                        }
                    }
                    items(50) { Box(Modifier.fillMaxWidth().height(50.dp)) }
                }
            }
        }
        // Across, on the row: the row moves, the column doesn't.
        onNodeWithTag("column").performMouseInput {
            moveTo(Offset(300f, 50f))
            press()
            repeat(10) { moveBy(Offset(-20f, 1f), delayMillis = 16) }
            release()
        }
        waitForIdle()
        assertTrue(row.firstVisibleItemIndex > 0 || row.firstVisibleItemScrollOffset > 0)
        assertEquals(0, column.firstVisibleItemIndex + column.firstVisibleItemScrollOffset)
        val rowAt = row.firstVisibleItemIndex to row.firstVisibleItemScrollOffset
        // Down, starting on the row: the column moves, the row doesn't.
        onNodeWithTag("column").performMouseInput {
            moveTo(Offset(200f, 80f))
            press()
            repeat(10) { moveBy(Offset(1f, -20f), delayMillis = 16) }
            release()
        }
        waitForIdle()
        assertTrue(column.firstVisibleItemIndex > 0 || column.firstVisibleItemScrollOffset > 0)
        assertEquals(rowAt, row.firstVisibleItemIndex to row.firstVisibleItemScrollOffset)
    }

    @Test
    fun aPressOnAFlingingListStopsItThereAndClicksNothing() = runComposeUiTest {
        lateinit var list: LazyListState
        val taps = Taps()
        mainClock.autoAdvance = false
        setContent { list = rememberLazyListState(); Rows(list, taps) }
        onNodeWithTag("list").performMouseInput {
            moveTo(Offset(150f, 380f))
            press()
            repeat(12) { moveBy(Offset(0f, -30f), delayMillis = 8) }
            release()
        }
        mainClock.advanceTimeBy(100)
        assertTrue(list.isScrollInProgress, "still flinging")
        onNodeWithTag("list").performMouseInput { click(Offset(150f, 200f)) }
        mainClock.advanceTimeByFrame()
        val stopped = list.at
        mainClock.advanceTimeBy(2_000)
        assertEquals(stopped, list.at)
        assertNull(taps.clicked)
        // Once still, a click is a click again.
        onNodeWithTag("list").performMouseInput { click(Offset(150f, 200f)) }
        mainClock.advanceTimeBy(500)
        assertTrue(taps.clicked != null)
    }

    @Test
    fun rowsPassingUnderADragAreNotHovered() = runComposeUiTest {
        lateinit var list: LazyListState
        val hovered = ArrayList<Int>()
        setContent {
            list = rememberLazyListState()
            // The app's root tells the router what the pointer does, as FuseApp does.
            val router = androidx.compose.runtime.remember { InputRouter(kotlinx.coroutines.MainScope()) }
            CompositionLocalProvider(LocalPointerDragScroll provides true, LocalPointerRouter provides router) {
                Box(
                    Modifier.pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val e = awaitPointerEvent(PointerEventPass.Initial)
                                val ch = e.changes.first()
                                router.pointer(true, ch.position.x, ch.position.y, pressed = e.type == PointerEventType.Press, held = ch.pressed)
                            }
                        }
                    },
                ) {
                    LazyColumn(state = list, modifier = Modifier.size(300.dp, 400.dp).testTag("list").dragScroll(list)) {
                        items(100) { i -> Box(Modifier.fillMaxWidth().height(50.dp).mouseHover { hovered += i }) }
                    }
                }
            }
        }
        onNodeWithTag("list").performMouseInput {
            moveTo(Offset(150f, 300f))
            moveTo(Offset(150f, 375f))
            press()
            repeat(20) { moveBy(Offset(0f, -15f), delayMillis = 8) }
            release()
        }
        waitForIdle()
        // Before the press the pointer pointed at row 7, as a mouse does; nothing after, neither while
        // it was held nor as the list flung on under it.
        assertEquals(listOf(7), hovered)
        assertTrue(list.at > 0)
    }

    @Test
    fun aDragIsStillThePointerInUseButNothingAfterItIsPointedAt() = runTest {
        val router = InputRouter(this)
        router.pointer(mouse = true, x = 10f, y = 10f, pressed = false)
        router.pointer(mouse = true, x = 40f, y = 40f, pressed = false)
        assertTrue(router.mouseJustMoved())
        router.pointer(mouse = true, x = 40f, y = 40f, pressed = true, held = true)
        assertTrue(router.mouseJustMoved())
        // Dragged with the button held: a finger on a computer's touch screen, scrolling.
        router.pointer(mouse = true, x = 40f, y = 200f, pressed = false, held = true)
        assertEquals(InputSource.POINTER, router.lastSource.value)
        assertTrue(!router.mouseJustMoved())
        // Moving again without a button is pointing once more.
        router.pointer(mouse = true, x = 60f, y = 220f, pressed = false)
        assertTrue(router.mouseJustMoved())
    }
}
