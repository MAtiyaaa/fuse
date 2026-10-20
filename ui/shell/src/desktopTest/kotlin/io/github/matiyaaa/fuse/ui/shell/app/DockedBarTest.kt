package io.github.matiyaaa.fuse.ui.shell.app

import io.github.matiyaaa.fuse.model.NavAction
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The top line is reached the way it lies: up to it along the top, down to it when a theme docks it under the page. */
class DockedBarTest {
    @Test
    fun alongTheTopUpReachesItAndDownLeavesIt() {
        assertTrue(reachesBar(NavAction.UP, docked = false))
        assertFalse(reachesBar(NavAction.DOWN, docked = false))
        assertTrue(leavesBar(NavAction.DOWN, docked = false))
        assertFalse(leavesBar(NavAction.UP, docked = false))
    }

    @Test
    fun dockedUnderThePageDownReachesItAndUpLeavesIt() {
        assertTrue(reachesBar(NavAction.DOWN, docked = true))
        assertFalse(reachesBar(NavAction.UP, docked = true))
        assertTrue(leavesBar(NavAction.UP, docked = true))
        assertFalse(leavesBar(NavAction.DOWN, docked = true))
        assertFalse(reachesBar(NavAction.LEFT, docked = true) || leavesBar(NavAction.RIGHT, docked = true))
    }
}
