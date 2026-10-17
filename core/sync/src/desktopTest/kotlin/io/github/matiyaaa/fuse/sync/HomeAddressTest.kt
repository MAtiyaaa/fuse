package io.github.matiyaaa.fuse.sync

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * A device that joined from outside (through the host's tunnel) knew only that address, so it went
 * the long way even on the same Wi-Fi as the host: "Online, from outside" at home, and every game
 * sent through it slow. The host says where it answers at home; once that answers as this host, the
 * device goes there.
 */
class HomeAddressTest {
    private lateinit var root: File
    private lateinit var host: SyncHost
    private val http = SyncClient.defaultClient()

    @BeforeTest
    fun start(): Unit = runBlocking {
        root = Files.createTempDirectory("home-address").toFile()
        host = SyncHost(HostStore(File(root, "host"), hostName = "Media Server"), port = 0, bind = "127.0.0.1", callsPerMinute = 100_000).start()
    }

    @AfterTest
    fun stop() {
        host.stop()
        http.close()
        root.deleteRecursively()
    }

    private suspend fun joinedFromOutside(): SyncClient {
        val port = host.boundPort()
        val link = SyncClient.pair("127.0.0.1:$port", host.newPairingCode(), "dev-thor", "AYN Thor", "ANDROID", http)
        // Its only address is the one from outside (here the same host by another name).
        return SyncClient(link.copy(localAddress = null, remoteAddress = "http://localhost:$port"), http)
    }

    private fun told(c: SyncClient, home: String) = HostHello(
        hostId = c.link.hostId, name = "Media Server", node = "node-1",
        hosts = listOf(HostNode("node-1", "Media Server", local = home)),
    )

    @Test
    fun aDeviceThatJoinedFromOutsideGoesHomeWhereTheHostSaysItAnswers() = runBlocking<Unit> {
        val c = joinedFromOutside()
        c.devices()
        assertEquals(Route.REMOTE, c.route)
        c.learn(told(c, "127.0.0.1:${host.boundPort()}"))
        // The wait for the host's news is out the long way when home answers, and ends after it.
        val now = c.events(0).seq
        val waiting = async { c.events(now, waitSeconds = 3) }
        withTimeout(10_000) { while (c.link.localAddress == null || c.route != Route.LOCAL) delay(50) }
        assertEquals(SyncClient.normalise("127.0.0.1:${host.boundPort()}"), c.link.localAddress)
        waiting.await()
        assertEquals(Route.LOCAL, c.route, "a call that went outside before home answered doesn't take this device back out")
        c.devices()
        assertEquals(Route.LOCAL, c.route)
    }

    @Test
    fun anAddressThatIsntThisHostIsNeverTaken() = runBlocking<Unit> {
        val c = joinedFromOutside()
        val other = SyncHost(HostStore(File(root, "other"), hostName = "Someone Else"), port = 0, bind = "127.0.0.1", callsPerMinute = 100_000).start()
        try {
            c.learn(told(c, "127.0.0.1:${other.boundPort()}"))
            c.devices()
            delay(1_500)
            assertNull(c.link.localAddress)
            assertEquals(Route.REMOTE, c.route)
        } finally {
            other.stop()
        }
    }
}
