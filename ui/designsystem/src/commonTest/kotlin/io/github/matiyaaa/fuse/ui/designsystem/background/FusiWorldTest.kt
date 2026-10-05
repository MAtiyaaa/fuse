package io.github.matiyaaa.fuse.ui.designsystem.background

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FusiWorldTest {
    /** Steps her through [seconds] at 30 frames a second, the screens in [screens] drawing her. */
    private fun live(world: FusiWorld, clock: FloatArray, seconds: Int, screens: List<Int>, each: () -> Unit = {}) {
        repeat(seconds * 30) {
            clock[0] += 1f / 30f
            for (s in screens) world.frame(s, if (s == 0) 1920f else 1080f, if (s == 0) 1080f else 1240f, 2f, still = false)
            each()
        }
    }

    @Test
    fun onOneScreenSheStaysThereAndInsideTheRoom() {
        val clock = floatArrayOf(0f)
        val world = FusiWorld { clock[0] }
        live(world, clock, 600, listOf(0)) {
            assertEquals(0, world.onScreen)
            assertTrue(world.across in -0.1f..1.1f, "she wandered off to ${world.across}")
        }
    }

    @Test
    fun withTwoScreensSheVisitsBoth() {
        val clock = floatArrayOf(0f)
        val world = FusiWorld { clock[0] }
        val visited = HashSet<Int>()
        live(world, clock, 900, listOf(0, 1)) { visited += world.onScreen }
        assertEquals(setOf(0, 1), visited, "she should hop down and back up over fifteen minutes")
    }

    @Test
    fun whenTheScreenBelowGoesAwaySheComesBackUp() {
        val clock = floatArrayOf(0f)
        val world = FusiWorld { clock[0] }
        // Until she is below, then only the main screen draws.
        var t = 0
        while (world.onScreen != 1 && t < 3_600) {
            live(world, clock, 1, listOf(0, 1))
            t++
        }
        assertEquals(1, world.onScreen)
        live(world, clock, 5, listOf(0))
        assertEquals(0, world.onScreen)
    }

    @Test
    fun drawnStillSheDoesNotMove() {
        val clock = floatArrayOf(0f)
        val world = FusiWorld { clock[0] }
        val at = world.across
        repeat(300) {
            clock[0] += 1f
            world.frame(0, 1920f, 1080f, 2f, still = true)
        }
        assertEquals(at, world.across)
    }
}
