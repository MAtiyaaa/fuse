package io.github.matiyaaa.fuse.desktop.system

import io.github.matiyaaa.fuse.desktop.Log
import java.awt.EventQueue
import kotlin.test.Test
import kotlin.test.assertTrue

/** A stalled interface thread is written into Fuse's log with what it was doing. */
class UiWatchdogTest {
    @Test
    fun aStallIsLoggedWithTheStackThatHeldIt() {
        val dir = kotlin.io.path.createTempDirectory("fuse-logs").toFile()
        Log.toFile(dir)
        val dog = UiWatchdog(beatMs = 50, stallMs = 300)
        dog.start()
        Thread.sleep(200)
        EventQueue.invokeAndWait { holdTheInterface() }
        Thread.sleep(300)
        dog.stop()
        val log = java.io.File(dir, "fuse.log").readText()
        assertTrue("the interface thread has been busy for" in log, log)
        assertTrue("holdTheInterface" in log, log)
        assertTrue("answering again" in log, log)
        dir.deleteRecursively()
    }

    private fun holdTheInterface() = Thread.sleep(900)
}
