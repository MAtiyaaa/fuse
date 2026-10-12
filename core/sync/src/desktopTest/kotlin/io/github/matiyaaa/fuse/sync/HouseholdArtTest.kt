package io.github.matiyaaa.fuse.sync

import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A cover fetched once for the household: one device gives it to the host, the others take it from there. */
class HouseholdArtTest {
    private lateinit var root: File
    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(2_000) { it.toByte() }
    private val key = SyncCrypto.sha256("https://cdn2.steamgriddb.com/grid/abc.png".toByteArray())

    @BeforeTest fun setUp() { root = Files.createTempDirectory("household-art").toFile() }
    @AfterTest fun tearDown() { root.deleteRecursively() }

    @Test
    fun picturesAreKeptByTheirAddressAndOnlyPictures() {
        var now = 1_000L
        val art = HouseholdArt(File(root, "art"), maxBytes = 5_000, clock = { now })
        assertNull(art.get(key))
        assertTrue(art.put(key, png))
        assertContentEquals(png, art.get(key))
        assertFalse(art.put(SyncCrypto.sha256("x".toByteArray()), "<html>not a picture</html>".toByteArray()), "only pictures")
        assertFalse(art.put("../escape", png), "only keys")
        // Over the limit, the picture asked for longest ago goes first.
        now = 2_000L
        val other = SyncCrypto.sha256("https://images.igdb.com/a.jpg".toByteArray())
        assertTrue(art.put(other, png))
        now = 3_000L
        art.get(other)
        val third = SyncCrypto.sha256("https://cdn.thegamesdb.net/b.png".toByteArray())
        assertTrue(art.put(third, png))
        assertNull(art.get(key), "the oldest went")
        assertTrue(art.get(other) != null && art.get(third) != null)
    }

    @Test
    fun oneDeviceFetchesItAndTheOthersTakeItFromTheHost(): Unit = runBlocking {
        val http = SyncClient.defaultClient()
        val host = SyncHost(HostStore(File(root, "host"), hostName = "Gaming PC"), port = 0, bind = "127.0.0.1", callsPerMinute = 100_000).start()
        try {
            val address = "127.0.0.1:${host.boundPort()}"
            val deck = SyncClient(SyncClient.pair(address, host.newPairingCode(), "dev-11111111", "Deck", "LINUX", http), http)
            val tv = SyncClient(SyncClient.pair(address, host.newPairingCode(), "dev-22222222", "TV", "ANDROID", http), http)
            assertNull(tv.artwork(key), "nobody fetched it yet")
            deck.shareArtwork(key, png)
            assertContentEquals(png, tv.artwork(key))
            // The art is the host's cache, never part of the household's copies.
            assertTrue(host.store.replicaFiles().keys.none { it.startsWith("art/") })
            assertEquals(1, File(root, "host/art").listFiles()!!.count { !it.name.startsWith(".") })
        } finally {
            host.stop()
        }
    }
}
