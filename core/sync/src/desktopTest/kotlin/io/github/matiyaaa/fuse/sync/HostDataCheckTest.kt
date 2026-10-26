package io.github.matiyaaa.fuse.sync

import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.ServerSocket
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A host whose data folder another program took over (its owner changed under it) says what is
 * wrong and how to fix it, to the person and to every device, and starts by itself once it is given
 * back: before, it failed on its admin token at every start, a few seconds apart, and a running host
 * answered each call with an empty 500 that read as something other than the host answering.
 */
class HostDataCheckTest {
    private lateinit var root: File

    @BeforeTest
    fun make() {
        root = Files.createTempDirectory("fuse-host-data").toFile()
    }

    @AfterTest
    fun clean() {
        root.deleteRecursively()
    }

    private fun freePort() = ServerSocket(0).use { it.localPort }

    @Test
    fun aFolderTheHostCanUseHasNothingToSay() {
        val dir = File(root, "host").apply { mkdirs() }
        File(dir, "admin.token").writeText("token")
        assertNull(HostDataCheck.problem(dir))
        // A new host makes its own folder.
        assertNull(HostDataCheck.problem(File(root, "new/host")))
        // A file where the folder should be is named.
        val file = File(root, "taken").apply { writeText("not a folder") }
        assertTrue(HostDataCheck.problem(file)!!.contains("is a file, not a folder"))
    }

    @Test
    fun waitingTellsEveryDeviceWhatIsWrongThenStartsOnceItIsFixed() = runBlocking {
        val port = freePort()
        val problem = "The host can't read admin.token in its data folder (/data/admin.token). It belongs to 13019, and the host runs as mediaserver (1000)."
        val checks = java.util.concurrent.atomic.AtomicInteger()
        val logs = CopyOnWriteArrayList<String>()
        val waiting = thread {
            HeadlessHost.waitForData(
                File(root, "host"), port, log = { logs += it },
                // Wrong for the first several looks, then given back.
                check = { if (checks.incrementAndGet() < 20) problem else null },
                pauseMs = 150,
            )
        }
        repeat(100) { if (HostAdmin.portFree(port)) Thread.sleep(20) }
        // Meanwhile a device asking the host hears the host's own error, with the fix, not "something else answered".
        val link = HostLink(hostId = "h", hostName = "Media Server", deviceId = "dev-1", deviceSecret = SyncCrypto.token(32), localAddress = "127.0.0.1:$port")
        val error = assertFailsWith<SyncException> { SyncClient(link).status() }
        assertEquals(HostDataCheck.CODE, error.code, error.message)
        assertEquals(problem, error.message)
        assertEquals(503, error.status)
        waiting.join(10_000)
        assertTrue(!waiting.isAlive, "the host kept waiting after its folder was fixed")
        // Said once (not at every look), then that it starts; and the port is free for the host itself.
        assertEquals(listOf(problem, "The host can use its data folder again: starting"), logs.toList())
        assertTrue(HostAdmin.portFree(port))
    }

    @Test
    fun aRunningHostWhoseFolderIsTakenAnswersWithTheFix() = runBlocking {
        val dir = File(root, "host")
        val host = SyncHost(HostStore(dir, hostName = "Media Server"), port = 0, bind = "127.0.0.1", callsPerMinute = 100_000).start()
        try {
            val address = "127.0.0.1:${host.boundPort()}"
            val http = SyncClient.defaultClient()
            val link = SyncClient.pair(address, host.newPairingCode(), "dev-" + SyncCrypto.token(8), "Thor", "ANDROID", http)
            val device = SyncClient(link, http)
            val mo = device.createProfile(NewProfile("Mo", "fox"))
            // Another program takes the folder: here, a file put where it was, so no write lands.
            dir.deleteRecursively()
            dir.writeText("taken")
            val error = assertFailsWith<SyncException> { device.orderProfiles(listOf(mo.id)) }
            assertEquals(HostDataCheck.CODE, error.code)
            assertTrue(error.message!!.contains(dir.path), error.message)
            http.close()
        } finally {
            host.stop()
        }
    }
}
