package io.github.matiyaaa.fuse.desktop

import io.github.matiyaaa.fuse.desktop.services.DesktopLauncher
import kotlinx.coroutines.future.await
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

/** A game started through a launcher that hands it on lasts as long as the game, not the launcher. */
class ProcessFamilyTest {
    @Test
    fun whatALauncherStartedIsWaitedForAfterItLeaves(): Unit = runBlocking {
        if (System.getProperty("os.name").lowercase().contains("win")) return@runBlocking
        // The launcher hands over at once and leaves; the "game" goes on for a second and a half.
        val process = ProcessBuilder("sh", "-c", "sleep 1.5 & sleep 0.2").start()
        val family = DesktopLauncher.ProcessFamily(process)
        val started = System.currentTimeMillis()
        process.onExit().await()
        val launcherLeft = System.currentTimeMillis() - started
        assertTrue(launcherLeft < 1_200, "the launcher itself leaves early ($launcherLeft ms)")
        family.awaitRest()
        val over = System.currentTimeMillis() - started
        assertTrue(over >= 1_400, "the game was waited for ($over ms)")
        assertTrue(family.rest().isEmpty())
    }

    @Test
    fun aProcessThatStartsNothingEndsWithItself(): Unit = runBlocking {
        if (System.getProperty("os.name").lowercase().contains("win")) return@runBlocking
        val process = ProcessBuilder("sh", "-c", "sleep 0.3").start()
        val family = DesktopLauncher.ProcessFamily(process)
        process.onExit().await()
        val started = System.currentTimeMillis()
        family.awaitRest()
        assertTrue(System.currentTimeMillis() - started < 500)
    }

    @Test
    fun somethingThatLeftTheFamilyWhileTheGameRanIsNotWaitedFor(): Unit = runBlocking {
        if (System.getProperty("os.name").lowercase().contains("win")) return@runBlocking
        // After the handover, the game starts a helper that leaves the family (a daemon) and lives on.
        val process = ProcessBuilder("sh", "-c", "sleep 0.4; (sleep 4 & sleep 0.3); sleep 0.5").start()
        val family = DesktopLauncher.ProcessFamily(process, handoverMs = 200, slowPollMs = 20)
        process.onExit().await()
        val ended = System.currentTimeMillis()
        family.awaitRest()
        assertTrue(System.currentTimeMillis() - ended < 1_500, "the helper was not waited for")
    }
}
