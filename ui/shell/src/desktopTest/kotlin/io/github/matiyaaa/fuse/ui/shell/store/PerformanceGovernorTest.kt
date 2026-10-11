package io.github.matiyaaa.fuse.ui.shell.store

import io.github.matiyaaa.fuse.model.GameId
import io.github.matiyaaa.fuse.ui.fuseline.DecorationRate
import io.github.matiyaaa.fuse.ui.shell.store.impl.PerformanceGovernor
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Fuse Engine's Performance Governor: each state from what Fuse sees, and what it lets run. */
@OptIn(ExperimentalCoroutinesApi::class)
class PerformanceGovernorTest {
    @Test
    fun aGameHoldsOptionalWorkUntilItEnds() = runTest {
        val playing = MutableStateFlow<GameId?>(null)
        val g = PerformanceGovernor(backgroundScope, playing, { testScheduler.currentTime }, { null })
        assertEquals(EngineState.ACTIVE, g.current())
        assertEquals(DecorationRate.FULL, g.policy.motion)

        playing.value = GameId(7)
        assertEquals(EngineState.PLAYING, g.current())
        assertFalse(g.policy.artwork)
        assertFalse(g.policy.detection)
        val art = async { g.await { it.artwork }; true }
        runCurrent()
        assertFalse(art.isCompleted, "waits while the game runs")
        playing.value = null
        runCurrent()
        assertTrue(art.await())
    }

    @Test
    fun behindAnotherWindowAndIdleOnScreen() = runTest {
        var since: Long? = 0L
        val g = PerformanceGovernor(backgroundScope, MutableStateFlow(null), { testScheduler.currentTime }, { since })
        g.start()
        g.onForeground(false)
        assertEquals(EngineState.BACKGROUND, g.current())
        assertEquals(DecorationRate.PAUSED, g.policy.motion)
        assertTrue(g.policy.artwork, "art and details may carry on behind another window")

        g.onForeground(true)
        since = 200_000_000_000L
        advanceTimeBy(5_001)
        assertEquals(EngineState.IDLE, g.current())
        assertEquals(DecorationRate.REDUCED, g.policy.motion)
        since = 1_000_000L
        advanceTimeBy(5_001)
        assertEquals(EngineState.ACTIVE, g.current())
    }

    @Test
    fun wakingFromSleepSettlesBeforeOptionalWorkComesBack() = runTest {
        var jump = 0L
        val g = PerformanceGovernor(backgroundScope, MutableStateFlow(null), { testScheduler.currentTime + jump }, { null })
        g.start()
        advanceTimeBy(5_001)
        assertEquals(EngineState.ACTIVE, g.current())
        // The device slept an hour: the wall clock ran on while Fuse's ticks didn't.
        jump = 3_600_000L
        advanceTimeBy(5_001)
        assertEquals(EngineState.RESUMING, g.current())
        assertFalse(g.policy.detection)
        advanceTimeBy(8_001)
        assertEquals(EngineState.ACTIVE, g.current())

        g.onSuspend()
        assertEquals(EngineState.SUSPENDED, g.current())
        g.woke()
        assertEquals(EngineState.RESUMING, g.current())
    }
}
