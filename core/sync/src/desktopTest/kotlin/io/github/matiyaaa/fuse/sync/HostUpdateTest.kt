package io.github.matiyaaa.fuse.sync

import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Any device of the household asks the host to update; the host says what it does about it. */
class HostUpdateTest {
    private lateinit var root: File
    private lateinit var host: SyncHost
    private val http = SyncClient.defaultClient()
    private var port = 0
    private val address get() = "127.0.0.1:$port"

    @BeforeTest
    fun start(): Unit = runBlocking {
        root = Files.createTempDirectory("fuse-host-update").toFile()
        host = SyncHost(HostStore(File(root, "host"), hostName = "Gaming PC"), port = 0, bind = "127.0.0.1", callsPerMinute = 100_000).start()
        port = host.boundPort()
    }

    @AfterTest
    fun stop() {
        host.stop()
        root.deleteRecursively()
    }

    private suspend fun device(): SyncClient =
        SyncClient(SyncClient.pair(address, host.newPairingCode(), "dev-12345678", "Thor", "ANDROID", http), http)

    @Test
    fun aHostRunningAsAServiceUpdatesWhenADeviceAsks(): Unit = runBlocking {
        var asked = 0
        host.onUpdateAsked = { asked++; HostUpdate(started = true, message = "Updating", version = "9.9.9") }
        val answer = device().updateHost()
        assertTrue(answer.started)
        assertEquals("9.9.9", answer.version)
        assertEquals(1, asked)
    }

    @Test
    fun aHostInsideFuseSaysItUpdatesWithThatFuse(): Unit = runBlocking {
        val answer = device().updateHost()
        assertFalse(answer.started)
        assertTrue("inside Fuse" in answer.message, answer.message)
    }

    @Test
    fun aStrangerCannotAskTheHostToUpdate(): Unit = runBlocking {
        var asked = 0
        host.onUpdateAsked = { asked++; HostUpdate(true, "Updating") }
        val real = device().link
        val stranger = SyncClient(real.copy(deviceId = "dev-00000000", deviceSecret = "not-a-secret"), http)
        assertTrue(runCatching { stranger.updateHost() }.isFailure)
        assertEquals(0, asked)
    }
}
