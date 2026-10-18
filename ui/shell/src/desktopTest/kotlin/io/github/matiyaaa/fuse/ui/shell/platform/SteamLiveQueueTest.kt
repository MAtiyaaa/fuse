package io.github.matiyaaa.fuse.ui.shell.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** Changes waiting for the Fuse Decky plugin: made and reported, or taken back when it doesn't answer. */
class SteamLiveQueueTest {
    private var clock = 1_000_000L
    private val queue = SteamLiveQueue { clock }
    private fun add(game: Long) = SteamLiveChange(op = SteamLiveChange.Op.ADD, gameId = game, name = "Game $game", exe = "\"/a/Fuse\"", startDir = "\"/a\"", launchOptions = "--run $game")

    @Test
    fun thePluginIsThereOnlyWhileItKeepsAsking(): Unit = runBlocking {
        assertFalse(queue.connected)
        queue.take()
        assertTrue(queue.connected)
        clock += SteamLiveQueue.CONNECTED_MS
        assertFalse(queue.connected)
    }

    @Test
    fun aMadeChangeComesBackWithItsEntryAndIsRemembered(): Unit = runBlocking {
        val offered = async { queue.offer(listOf(add(12), add(15)), waitMs = 10_000) }
        val waiting = withTimeout(5_000) {
            var got = queue.take()
            while (got.size < 2) { delay(10); got = queue.take() }
            got
        }
        assertEquals(listOf(12L, 15L), waiting.map { it.gameId })
        assertTrue(queue.done(waiting[0].id, SteamLiveResult(true, appId = 3_000_000_001L)))
        assertTrue(queue.done(waiting[1].id, SteamLiveResult(false, message = "Steam said no")))
        assertEquals(listOf(true, false), offered.await().map { it.ok })
        // Only what was made is remembered, until Steam closes and its file says it.
        assertEquals(mapOf(12L to 3_000_000_001L), queue.made())
        assertFalse(queue.done(waiting[0].id, SteamLiveResult(true)))
        queue.offer(listOf(SteamLiveChange(op = SteamLiveChange.Op.REMOVE, gameId = 12, appId = 3_000_000_001L)), waitMs = 1).let { assertFalse(it.single().ok) }
        queue.steamClosed()
        assertEquals(emptyMap(), queue.made())
    }

    @Test
    fun aChangeThePluginDoesntMakeInTimeIsTakenBack(): Unit = runBlocking {
        val result = queue.offer(listOf(add(7)), waitMs = 50)
        assertFalse(result.single().ok)
        // Nothing is left for the plugin to make late, so the usual way can't double it.
        assertEquals(emptyList(), queue.take())
    }

    @Test
    fun noticesWaitForSteamOnlyWhileThePluginIsThere(): Unit = runBlocking {
        queue.notify("Saves sent")
        assertEquals(emptyList(), queue.takeNotices())
        queue.take()
        queue.notify("Saves sent")
        queue.notify("Download finished")
        queue.notify("Saves sent")
        // Each once, the latest last.
        assertEquals(listOf("Download finished", "Saves sent"), queue.takeNotices())
        assertEquals(emptyList(), queue.takeNotices())
        repeat(SteamLiveQueue.MAX_NOTICES + 2) { queue.notify("Notice $it") }
        assertEquals(SteamLiveQueue.MAX_NOTICES, queue.takeNotices().size)
    }

    @Test
    fun aRemovalForgetsTheEntry(): Unit = runBlocking {
        val adding = async { queue.offer(listOf(add(3)), waitMs = 10_000) }
        val a = withTimeout(5_000) { var got = queue.take(); while (got.isEmpty()) { delay(10); got = queue.take() }; got.single() }
        queue.done(a.id, SteamLiveResult(true, appId = 99))
        adding.await()
        val removing = async { queue.offer(listOf(SteamLiveChange(op = SteamLiveChange.Op.REMOVE, gameId = 3, appId = 99)), waitMs = 10_000) }
        val r = withTimeout(5_000) { var got = queue.take(); while (got.isEmpty()) { delay(10); got = queue.take() }; got.single() }
        queue.done(r.id, SteamLiveResult(true))
        assertTrue(removing.await().single().ok)
        assertEquals(emptyMap(), queue.made())
    }
}
