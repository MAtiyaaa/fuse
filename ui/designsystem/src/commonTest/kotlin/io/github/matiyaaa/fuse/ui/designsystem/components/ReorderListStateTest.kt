package io.github.matiyaaa.fuse.ui.designsystem.components

import io.github.matiyaaa.fuse.model.NavAction
import io.github.matiyaaa.fuse.ui.designsystem.input.InputSource
import io.github.matiyaaa.fuse.ui.designsystem.input.NavEvent
import io.github.matiyaaa.fuse.ui.designsystem.input.NavResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReorderListStateTest {
    private val home: (String) -> Boolean = { it == "home" }
    private fun ev(a: NavAction) = NavEvent(a, 0, InputSource.GAMEPAD)

    @Test
    fun aCarriedRowMovesWithTheDpadAndIsSavedWhenPutDown() {
        val s = ReorderListState(listOf("home", "library", "systems", "apps"))
        s.selection.index = 3
        val saved = mutableListOf<List<String>>()
        assertEquals(NavResult.ACTIVATED, s.handle(ev(NavAction.SELECT), home, { saved += it }, {}))
        assertTrue(s.carrying)
        assertEquals(NavResult.MOVED, s.handle(ev(NavAction.UP), home, { saved += it }, {}))
        assertEquals(NavResult.MOVED, s.handle(ev(NavAction.UP), home, { saved += it }, {}))
        // Home keeps its place: the carried row stops below it.
        assertEquals(NavResult.BLOCKED, s.handle(ev(NavAction.UP), home, { saved += it }, {}))
        assertEquals(listOf("home", "apps", "library", "systems"), s.order)
        assertTrue(saved.isEmpty())
        s.handle(ev(NavAction.SELECT), home, { saved += it }, {})
        assertFalse(s.carrying)
        assertEquals(listOf(listOf("home", "apps", "library", "systems")), saved)
    }

    @Test
    fun backPutsACarriedRowWhereItWas() {
        val s = ReorderListState(listOf("a", "b", "c"))
        s.selection.index = 0
        s.handle(ev(NavAction.SELECT), { false }, {}, {})
        s.handle(ev(NavAction.DOWN), { false }, {}, {})
        s.handle(ev(NavAction.DOWN), { false }, {}, {})
        assertEquals(listOf("b", "c", "a"), s.order)
        assertEquals(NavResult.CONSUMED, s.handle(ev(NavAction.BACK), { false }, {}, {}))
        assertEquals(listOf("a", "b", "c"), s.order)
        assertEquals(0, s.selection.index)
        assertFalse(s.carrying)
    }

    @Test
    fun aLockedRowIsNeverPickedUpAndNothingLandsAboveIt() {
        val s = ReorderListState(listOf("home", "a", "b"))
        s.selection.index = 0
        assertEquals(NavResult.BLOCKED, s.handle(ev(NavAction.SELECT), home, {}, {}))
        assertFalse(s.carrying)
        // A touch drop at the very top lands just under Home.
        assertEquals(listOf("home", "b", "a"), s.place("b", 0, home))
        assertNull(s.place("home", 2, home))
    }

    @Test
    fun theDoneButtonFollowsTheLastRowAndCloses() {
        val s = ReorderListState(listOf("a", "b"))
        var done = 0
        s.handle(ev(NavAction.DOWN), { false }, {}, { done++ })
        assertEquals(NavResult.MOVED, s.handle(ev(NavAction.DOWN), { false }, {}, { done++ }))
        assertEquals(2, s.selection.index)
        assertEquals(NavResult.BLOCKED, s.handle(ev(NavAction.DOWN), { false }, {}, { done++ }))
        s.handle(ev(NavAction.SELECT), { false }, {}, { done++ })
        assertEquals(1, done)
    }

    @Test
    fun anOrderFromOutsideIsTakenOnlyWhileNothingIsCarried() {
        val s = ReorderListState(listOf("a", "b"))
        s.sync(listOf("b", "a"))
        assertEquals(listOf("b", "a"), s.order)
        s.pickUp { false }
        s.sync(listOf("a", "b"))
        assertEquals(listOf("b", "a"), s.order)
    }
}
