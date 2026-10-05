package io.github.matiyaaa.fuse.sync

import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.http.takeFrom
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Joining a host without a code (a device asks, someone already in lets it in after checking both
 * show the same number), the host's own profile that only the host computer sees, and calls a
 * tunnel carries counting as from outside.
 */
class JoinTest {
    private lateinit var root: File
    private lateinit var store: HostStore
    private lateinit var host: SyncHost
    private var port = 0
    private val http = SyncClient.defaultClient()
    private val address get() = "127.0.0.1:$port"

    @BeforeTest
    fun start(): Unit = runBlocking {
        root = Files.createTempDirectory("fuse-join").toFile()
        store = HostStore(File(root, "host"), hostName = "Media Server")
        host = SyncHost(store, port = 0, bind = "127.0.0.1", callsPerMinute = 100_000).start()
        port = host.boundPort()
    }

    @AfterTest
    fun stop() {
        host.stop()
        root.deleteRecursively()
    }

    private suspend fun pair(name: String): SyncClient =
        SyncClient(SyncClient.pair(address, host.newPairingCode(), "dev-" + SyncCrypto.token(8), name, "LINUX", http), http)

    @Test
    fun aDeviceAsksAndSomeoneAlreadyInLetsItIn(): Unit = runBlocking {
        val pc = pair("Gaming PC")
        val session = SyncClient.askToJoin(address, "dev-thor-0001", "AYN Thor", "ANDROID", http)
        assertEquals("Media Server", session.ticket.hostName)
        assertNull(SyncClient.joinResult(session, http))
        // The PC sees who asks, with the same number the Thor shows.
        val ask = pc.joins().single()
        assertEquals("AYN Thor", ask.deviceName)
        assertEquals(session.match, ask.match)
        assertTrue(Regex("^\\d{3} \\d{3}$").matches(ask.match))
        pc.answerJoin(ask.id, allow = true)
        val link = assertNotNull(SyncClient.joinResult(session, http))
        assertEquals("dev-thor-0001", link.deviceId)
        // The Thor is in: its signed calls work.
        assertEquals("Media Server", SyncClient(link, http).status().hello.name)
        assertTrue(pc.joins().isEmpty())
        // The answer is given once.
        assertEquals("gone", assertFailsWith<SyncException> { SyncClient.joinResult(session, http) }.code)
    }

    @Test
    fun aDeviceTurnedAwayIsNotIn(): Unit = runBlocking {
        val session = SyncClient.askToJoin(address, "dev-strange-01", "Someone's Phone", "ANDROID", http)
        val ask = host.joinRequests().single()
        assertTrue(host.answerJoin(ask.id, allow = false))
        assertEquals("denied", assertFailsWith<SyncException> { SyncClient.joinResult(session, http) }.code)
        assertNull(store.device("dev-strange-01"))
        // Nothing to answer twice.
        assertFalse(host.answerJoin(ask.id, allow = true))
    }

    @Test
    fun anyDeviceInCanShowACode(): Unit = runBlocking {
        val pc = pair("Gaming PC")
        val code = pc.pairingCode()
        val deck = SyncClient.pair(address, code, "dev-deck-0001", "Steam Deck", "LINUX", http)
        assertEquals("dev-deck-0001", deck.deviceId)
    }

    @Test
    fun theHostsOwnProfileIsTheHostComputersAlone(): Unit = runBlocking {
        val hostFuse = pair("Media Server")
        val deck = pair("Steam Deck")
        val admin = store.adoptOwner(hostFuse.link.deviceId)
        assertEquals("Admin", admin.name)
        assertTrue(admin.hostOnly)
        // Asked again, the same one.
        assertEquals(admin.id, store.adoptOwner(hostFuse.link.deviceId).id)
        assertTrue(hostFuse.profiles().any { it.id == admin.id })
        assertTrue(deck.profiles().none { it.id == admin.id })
        // Hidden is also closed: another device can't open it.
        assertFailsWith<SyncException> { deck.openProfile(admin.id, null) }
        assertNotNull(hostFuse.openProfile(admin.id, null))
        // The people on the host see their own as usual.
        val mo = deck.createProfile(NewProfile("Mo", "fox"))
        assertTrue(hostFuse.profiles().any { it.id == mo.id } && deck.profiles().any { it.id == mo.id })
    }

    @Test
    fun aCallATunnelCarriesIsFromOutside(): Unit = runBlocking {
        suspend fun hub(vararg headers: Pair<String, String>): Int = http.request {
            method = HttpMethod.Get
            url.takeFrom("http://$address/hub")
            headers.forEach { (k, v) -> header(k, v) }
        }.status.value
        assertEquals(200, hub())
        // cloudflared connects from this computer, but says whom it carries.
        assertEquals(403, hub("CF-Connecting-IP" to "203.0.113.9"))
        assertEquals(403, hub("X-Forwarded-For" to "203.0.113.9"))
    }

    @Test
    fun addressesAtHomeArePlainAndOnTheInternetAreHttps() {
        assertEquals("http://192.168.1.20:47311", SyncClient.normalise("192.168.1.20:47311"))
        assertEquals("http://media-server:47311", SyncClient.normalise("media-server:47311"))
        assertEquals("http://media.local:47311", SyncClient.normalise("media.local:47311"))
        assertEquals("https://sync.example.com", SyncClient.normalise("sync.example.com"))
        assertEquals("http://sync.example.com", SyncClient.normalise("http://sync.example.com/"))
    }

    @Test
    fun bothSidesOfAKeyExchangeAgree() {
        val a = SyncCrypto.joinKeys()
        val b = SyncCrypto.joinKeys()
        val pa = SyncCrypto.publicKeyText(a)
        val pb = SyncCrypto.publicKeyText(b)
        assertEquals(SyncCrypto.joinSecret(a, pb), SyncCrypto.joinSecret(b, pa))
        assertNull(SyncCrypto.joinSecret(a, "bm90IGEga2V5"))
        assertEquals(SyncCrypto.joinMatch(pa, pb), SyncCrypto.joinMatch(pa, pb))
    }
}
