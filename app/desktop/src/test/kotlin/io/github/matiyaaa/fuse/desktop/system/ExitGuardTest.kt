package io.github.matiyaaa.fuse.desktop.system

import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExitGuardTest {
    /** A process that runs until it is ended: another Java, waiting on its input. */
    private fun longRunning(): Process {
        val java = File(System.getProperty("java.home"), "bin/java").path
        return ProcessBuilder(java, "-Xshare:auto", "-cp", System.getProperty("java.class.path"), Waits::class.java.name)
            .redirectErrorStream(true).start()
    }

    @Test fun whatFuseStartedToPlayEndsWhenItQuits() {
        val game = longRunning()
        try {
            assertFalse(game.waitFor(300, TimeUnit.MILLISECONDS), "the stand-in game should still run")
            ExitGuard.played(game)
            ExitGuard.endPlayed()
            assertTrue(game.waitFor(5, TimeUnit.SECONDS), "the game Fuse started should have ended")
        } finally {
            game.destroyForcibly()
        }
    }

    @Test fun aProcessFuseDidNotStartToPlayIsLeftRunning() {
        val helper = longRunning()
        try {
            ExitGuard.endPlayed()
            assertFalse(helper.waitFor(300, TimeUnit.MILLISECONDS), "a helper Fuse didn't start to play must outlive it")
        } finally {
            helper.destroyForcibly()
        }
    }
}

/** Waits until it is ended. */
object Waits {
    @JvmStatic fun main(args: Array<String>) { Thread.sleep(60_000) }
}
