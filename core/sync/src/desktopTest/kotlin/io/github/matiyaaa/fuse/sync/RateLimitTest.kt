package io.github.matiyaaa.fuse.sync

import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Devices behind one address (a tunnel, one public address at home) each have their own allowance of calls. */
class RateLimitTest {
    private lateinit var root: File
    private lateinit var host: SyncHost
    private val http = SyncClient.defaultClient()

    @BeforeTest
    fun start(): Unit = runBlocking {
        root = Files.createTempDirectory("fuse-rate").toFile()
        host = SyncHost(HostStore(File(root, "host"), hostName = "Media Server"), port = 0, bind = "127.0.0.1", callsPerMinute = 80).start()
    }

    @AfterTest
    fun stop() {
        host.stop()
        root.deleteRecursively()
    }

    @Test
    fun aBusyDeviceDoesNotTurnTheOthersAway(): Unit = runBlocking {
        val address = "127.0.0.1:${host.boundPort()}"
        val pc = SyncClient(SyncClient.pair(address, host.newPairingCode(), "dev-11111111", "PC", "LINUX", http), http)
        val htpc = SyncClient(SyncClient.pair(address, host.newPairingCode(), "dev-22222222", "HTPC", "LINUX", http), http)
        // The PC uses up its allowance for this minute.
        val refused = (0 until 120).map { runCatching { pc.devices() }.exceptionOrNull() }.filterNotNull()
        assertTrue(refused.any { (it as? SyncException)?.code == "rate" }, "a device over its allowance is told so")
        // The HTPC, at the same address, still gets its answers.
        assertEquals(2, htpc.devices().size)
    }
}
