package io.github.matiyaaa.fuse.desktop.system

import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** One Fuse at a time: a second start hands over to the first, or takes its place. */
class SingleInstanceTest {
    private val folder = Files.createTempDirectory("fuse-instance").toFile()
    private val held = ArrayList<SingleInstance>()

    private fun start(steam: String? = null, replace: Boolean = false): Pair<SingleInstance, SingleInstance.Decision> {
        val guard = SingleInstance(folder)
        return guard to guard.claim(steam, replace).also { held += guard }
    }

    @AfterTest
    fun cleanUp() {
        held.forEach { it.release() }
        folder.deleteRecursively()
    }

    @Test
    fun theFirstStartHoldsTheLock() {
        assertEquals(SingleInstance.Decision.First, start().second)
    }

    @Test
    fun aSecondStartBringsTheFirstToTheFront() {
        val (first, _) = start()
        val shown = CountDownLatch(1)
        first.onShow = { shown.countDown(); true }
        first.onQuit = { error("the first Fuse was asked to close") }
        assertEquals(SingleInstance.Decision.Handed, start().second)
        assertTrue(shown.await(2, TimeUnit.SECONDS))
    }

    @Test
    fun aFuseWhoseWindowIsGoneOrStuckIsReplacedRatherThanAskedToShow() {
        // Steam's Exit Game left it running with no window that answers: the next start from the
        // same entry must not leave the screen empty.
        val (first, _) = start(steam = "12345")
        first.onShow = { false }
        first.onQuit = { first.release() }
        assertIs<SingleInstance.Decision.Replaced>(start(steam = "12345").second)
    }

    @Test
    fun aStartFromSteamTakesThePlaceOfOneStartedElsewhere() {
        val (first, _) = start()
        first.onQuit = { first.release() }
        val (_, decision) = start(steam = "12345")
        assertIs<SingleInstance.Decision.Replaced>(decision)
        assertFalse(decision.ended)
    }

    @Test
    fun fuseStartingItselfAgainTakesThePlace() {
        val (first, _) = start(steam = "12345")
        first.onQuit = { first.release() }
        assertIs<SingleInstance.Decision.Replaced>(start(steam = "12345", replace = true).second)
    }

    @Test
    fun steamStartingTheSameEntryTwiceKeepsTheFirst() {
        val (first, _) = start(steam = "12345")
        first.onShow = { true }
        first.onQuit = { error("the first Fuse was asked to close") }
        assertEquals(SingleInstance.Decision.Handed, start(steam = "12345").second)
    }

    @Test
    fun aClosedFuseLeavesTheLockFree() {
        val (first, _) = start()
        first.release()
        assertEquals(SingleInstance.Decision.First, start().second)
    }

    @Test
    fun whoTakesThePlace() {
        assertFalse(SingleInstance.shouldReplace(null, null))
        assertFalse(SingleInstance.shouldReplace(null, "1"))
        assertTrue(SingleInstance.shouldReplace("1", null))
        assertTrue(SingleInstance.shouldReplace("2", "1"))
        assertFalse(SingleInstance.shouldReplace("1", "1"))
    }

    @Test
    fun aWrongSecretIsIgnored() {
        val (first, _) = start()
        var asked = false
        first.onShow = { asked = true; true }
        val holder = SingleInstance.Holder.read(java.io.File(folder, "instance.info"))!!
        assertFalse(SingleInstance.ask(holder.copy(secret = "not-it"), SingleInstance.SHOW))
        assertFalse(asked)
    }
}
