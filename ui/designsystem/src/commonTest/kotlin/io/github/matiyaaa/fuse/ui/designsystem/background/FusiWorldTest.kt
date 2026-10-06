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

    @Test
    fun boLooksLikeHerWithACollarAndNoBow() {
        val frames = listOf(BoFrames.stand0, BoFrames.walk0, BoFrames.walk1, BoFrames.sit0, BoFrames.sit1, BoFrames.sleep, BoFrames.sniff0, BoFrames.sniff1)
        val hers = listOf(FusiFrames.stand0, FusiFrames.walk0, FusiFrames.walk1, FusiFrames.sit0, FusiFrames.sit1, FusiFrames.sleep, FusiFrames.sniff0, FusiFrames.sniff1)
        for ((bo, fusi) in frames.zip(hers)) {
            val art = bo.rows.joinToString("\n")
            assertTrue('b' !in art && 'B' !in art, "no bow:\n$art")
            assertTrue('K' in art && 'g' in art, "a collar with its tag:\n$art")
            assertTrue('c' !in art, "no pink blush:\n$art")
            assertEquals(fusi.width, bo.width)
            assertEquals(fusi.height, bo.height)
        }
    }

    @Test
    fun boStaysOnTheMainScreenAndInsideTheRoom() {
        val clock = floatArrayOf(0f)
        val world = FusiWorld { clock[0] }
        live(world, clock, 900, listOf(0, 1)) {
            assertTrue(world.boAcross in 0f..1f, "Bo wandered off to ${world.boAcross}")
        }
    }

    @Test
    fun whenSheNapsBoNapsBesideHer() {
        val clock = floatArrayOf(0f)
        val world = FusiWorld { clock[0] }
        var napped = false
        // Over half an hour she sleeps at least once; he lies down by the house with her.
        live(world, clock, 1_800, listOf(0)) { if (world.boNapping) napped = true }
        assertTrue(napped, "Bo should nap beside her when she sleeps")
    }

    @Test
    fun drawnStillBoDoesNotMoveEither() {
        val clock = floatArrayOf(0f)
        val world = FusiWorld { clock[0] }
        val at = world.boAcross
        repeat(300) {
            clock[0] += 1f
            world.frame(0, 1920f, 1080f, 2f, still = true)
        }
        assertEquals(at, world.boAcross)
    }
}
