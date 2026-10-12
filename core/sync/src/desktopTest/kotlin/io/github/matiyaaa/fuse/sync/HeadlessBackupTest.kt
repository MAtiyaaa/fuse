package io.github.matiyaaa.fuse.sync

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.net.ServerSocket
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Two hosts with no screen, as in Docker: one made the other's backup with a pairing code, accepted, copying, taking over. */
class HeadlessBackupTest {
    private lateinit var root: File
    private val open = ArrayList<AutoCloseable>()

    @BeforeTest fun setUp() { root = Files.createTempDirectory("headless-backup").toFile() }

    @AfterTest
    fun tearDown() {
        open.forEach { runCatching { it.close() } }
        root.deleteRecursively()
    }

    private fun freePort() = ServerSocket(0).use { it.localPort }

    @Test
    fun aHeadlessHostBacksUpAnotherAndTakesOver(): Unit = runBlocking {
        val mainDir = File(root, "main")
        val mainPort = freePort()
        val main = assertNotNull(HeadlessHost.serve(mainDir, mainPort, "Mini PC", "test")).also { open += it }
        val mainAdmin = assertNotNull(HostAdmin.of(mainDir, mainPort))
        val code = mainAdmin.pairingCode()

        // backup-of, with the host stopped: it pairs as a device of the household and keeps the link.
        val nasDir = File(root, "nas")
        val link = HeadlessHost.becomeBackupOf(nasDir, "127.0.0.1:$mainPort", code, "NAS")
        assertEquals("Mini PC", link.hostName)
        assertTrue(assertFailsWith<IllegalArgumentException> { HeadlessHost.becomeBackupOf(nasDir, "127.0.0.1:$mainPort", code, "NAS") }.message!!.contains("already"))
        assertTrue(assertFailsWith<IllegalArgumentException> { HeadlessHost.becomeBackupOf(mainDir, "127.0.0.1:$mainPort", code, "Mini PC") }.message!!.contains("own"))

        val nasPort = freePort()
        open += assertNotNull(HeadlessHost.serve(nasDir, nasPort, "NAS", "test", keepEveryMs = 200))
        val offer = withTimeout(15_000) {
            var o: HostNode? = null
            while (o == null) { o = mainAdmin.status().backupOffers.firstOrNull { it.name == "NAS" }; if (o == null) delay(100) }
            o
        }
        // The Hub shows the offer, and takes Accept only with its own token.
        val hub = java.net.URI("http://127.0.0.1:$mainPort/hub").toURL().readText()
        assertTrue("Backup Hosts" in hub && "NAS" in hub)
        val forged = (java.net.URI("http://127.0.0.1:$mainPort/hub/backups/accept").toURL().openConnection() as java.net.HttpURLConnection).run {
            requestMethod = "POST"; doOutput = true; instanceFollowRedirects = false
            outputStream.use { it.write("device=${offer.deviceId}&token=nope".toByteArray()) }
            responseCode
        }
        assertEquals(403, forged)
        mainAdmin.acceptBackup(offer.deviceId!!)

        // Accepted: the NAS copies the household and waits as a backup of the same household.
        val hello = withTimeout(15_000) {
            var h: HostHello? = null
            while (h == null || h.hostId != link.hostId) { h = SyncClient.hello("127.0.0.1:$nasPort"); if (h?.hostId != link.hostId) delay(100) }
            h
        }
        assertEquals(Leadership.BACKUP, hello.role)
        assertEquals("Mini PC", hello.name)
        assertEquals(2, mainAdmin.status().leadership?.hosts?.size)

        // The main host is gone for good: a person makes the NAS the host.
        main.close()
        val nasAdmin = assertNotNull(HostAdmin.of(nasDir, nasPort))
        val l = nasAdmin.takeOver()
        assertEquals(1, l.generation)
        assertEquals(Leadership.PRIMARY, assertNotNull(SyncClient.hello("127.0.0.1:$nasPort")).role)
    }
}
