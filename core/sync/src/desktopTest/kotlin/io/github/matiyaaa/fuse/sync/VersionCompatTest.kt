package io.github.matiyaaa.fuse.sync

import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Devices and hosts on different Fuse releases: each says its protocol and version, the host keeps
 * what each device runs, devices from before 0.4.2 (which say nothing) carry on as before, and a
 * pairing that couldn't work is refused with a reason instead of half working.
 */
class VersionCompatTest {
    private lateinit var root: File
    private lateinit var host: SyncHost
    private val http = SyncClient.defaultClient()
    private var port = 0
    private val address get() = "127.0.0.1:$port"

    @BeforeTest
    fun start(): Unit = runBlocking {
        root = Files.createTempDirectory("fuse-compat").toFile()
        host = SyncHost(HostStore(File(root, "host"), hostName = "Gaming PC"), port = 0, bind = "127.0.0.1", callsPerMinute = 100_000).start()
        port = host.boundPort()
    }

    @AfterTest
    fun stop() {
        host.stop()
        root.deleteRecursively()
        fuseAppVersion = ""
    }

    @Test
    fun theHostKeepsWhichFuseEachDeviceRuns(): Unit = runBlocking {
        fuseAppVersion = "0.4.2"
        val link = SyncClient.pair(address, host.newPairingCode(), "dev-12345678", "Deck", "LINUX", http)
        val client = SyncClient(link, http)
        client.status()
        val deck = client.devices().single { it.id == "dev-12345678" }
        assertEquals("0.4.2", deck.fuseVersion)
        assertEquals(SyncApi.VERSION, deck.protocol)
        // The host says the oldest protocol it still serves.
        assertEquals(SyncApi.MIN_VERSION, SyncClient.hello(address, http)!!.minVersion)
    }

    @Test
    fun aDeviceFromBefore0_4_2IsServedAsBefore(): Unit = runBlocking {
        // A pairing with no version headers, as 0.4.1 and older send it.
        val code = host.newPairingCode()
        val resp = http.request {
            method = HttpMethod.Post
            url("http://$address${SyncApi.BASE}/pair")
            contentType(ContentType.Application.Json)
            setBody("""{"code":"$code","deviceId":"dev-87654321","deviceName":"Old Phone","platform":"ANDROID"}""")
        }
        assertEquals(200, resp.status.value, resp.bodyAsText())
        val old = host.status().devices.single { it.id == "dev-87654321" }
        assertEquals("", old.fuseVersion)
        assertEquals(0, old.protocol)
    }

    @Test
    fun aPairingThatCouldNotWorkIsRefusedWithAReason(): Unit = runBlocking {
        val code = host.newPairingCode()
        suspend fun pairSaying(protocol: Int, min: Int): Pair<Int, ApiError> {
            val resp = http.request {
                method = HttpMethod.Post
                url("http://$address${SyncApi.BASE}/pair")
                header(SyncApi.PROTOCOL_HEADER, protocol.toString())
                header(SyncApi.MIN_PROTOCOL_HEADER, min.toString())
                contentType(ContentType.Application.Json)
                setBody("""{"code":"$code","deviceId":"dev-11112222","deviceName":"Laptop","platform":"WINDOWS"}""")
            }
            return resp.status.value to Json { ignoreUnknownKeys = true }.decodeFromString(ApiError.serializer(), resp.bodyAsText())
        }
        // A device that only speaks protocols newer than this host's.
        val (status, newer) = pairSaying(SyncApi.VERSION + 1, SyncApi.VERSION + 1)
        assertEquals(426, status)
        assertEquals("host-too-old", newer.code)
        assertTrue("Update Fuse on the host" in newer.error)
        // A device older than the oldest this host serves.
        val (_, older) = pairSaying(SyncApi.MIN_VERSION - 1, SyncApi.MIN_VERSION - 1)
        assertEquals("device-too-old", older.code)
        // Nothing was paired, and the code still works for a device that can.
        assertFalse(host.status().devices.any { it.id == "dev-11112222" })
        SyncClient.pair(address, code, "dev-11112222", "Laptop", "WINDOWS", http)
    }

    @Test
    fun versionsCompareByTheirNumbers() {
        assertTrue(SyncApi.older("0.4.1", "0.4.2"))
        assertTrue(SyncApi.older("0.4.9", "0.4.10"))
        assertFalse(SyncApi.older("0.4.2", "0.4.2"))
        assertFalse(SyncApi.older("1.0", "0.9.9"))
        assertEquals(null, SyncApi.incompatible(SyncApi.VERSION, SyncApi.MIN_VERSION))
    }
}
