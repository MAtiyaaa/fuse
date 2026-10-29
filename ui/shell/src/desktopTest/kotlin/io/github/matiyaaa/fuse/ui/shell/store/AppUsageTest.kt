package io.github.matiyaaa.fuse.ui.shell.store

import kotlin.test.Test
import kotlin.test.assertEquals

/** Time in front from an app's comings and goings, as Android's usage statistics give them. */
class AppUsageTest {
    @Test
    fun onlyTheTimeInFrontCounts() {
        // Played ten minutes, away in another app for twenty, back for five.
        val moves = listOf(1_000L to true, 601_000L to false, 1_801_000L to true, 2_101_000L to false)
        assertEquals(900_000, timeInFront(moves, 0, 3_000_000))
    }

    @Test
    fun aScreenOpeningBeforeTheLastClosesIsOneStretch() {
        // A second screen of the same app came up before the first went (a menu, a game's own launcher).
        val moves = listOf(0L to true, 300_000L to true, 300_100L to false, 900_000L to false)
        assertEquals(900_000, timeInFront(moves, 0, 1_000_000))
    }

    @Test
    fun theEdgesOfTheTimeAreTheEdgesOfWhatCounts() {
        // In front already when the session began, and still in front at its end.
        assertEquals(400_000, timeInFront(listOf(400_000L to false), 0, 1_000_000))
        assertEquals(300_000, timeInFront(listOf(700_000L to true), 0, 1_000_000))
        // A move outside the time is held to its edge.
        assertEquals(1_000_000, timeInFront(listOf(-5_000L to true), 0, 1_000_000))
        // Nothing seen: nothing counted (the caller keeps its own count then).
        assertEquals(0, timeInFront(emptyList(), 0, 1_000_000))
        assertEquals(0, timeInFront(listOf(10L to true), 100, 100))
    }
}
