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

/** Trophies earned on the person's devices travel as history: the first time each was earned, never an unlock elsewhere. */
class TrophyHistoryTest {
    private val key = GameKey.of("ps3", "BLUS30443", null, "Demon's Souls")

    @Test
    fun theFirstTimeEachWasEarnedIsKeptWhereverItWas() {
        val deck = GameRecord(key, trophies = mapOf(
            "ps3:1" to TrophyUnlock("ps3", "1", "First", 2_000, "deck", "Steam Deck"),
            "ps3:2" to TrophyUnlock("ps3", "2", "Second", 5_000, "deck", "Steam Deck"),
        ))
        val pc = GameRecord(key, trophies = mapOf(
            "ps3:1" to TrophyUnlock("ps3", "1", "First", 1_000, "pc", "Gaming PC"),
            "ps3:3" to TrophyUnlock("ps3", "3", "Third", 9_000, "pc", "Gaming PC"),
        ))
        val merged = deck.merge(pc)
        assertEquals(setOf("ps3:1", "ps3:2", "ps3:3"), merged.trophies.keys)
        assertEquals("pc", merged.trophies.getValue("ps3:1").device, "the earlier one")
        assertEquals(merged, pc.merge(deck), "the same either way round")
    }

    @Test
    fun aTrophyEarnedHereGoesUpOnceAndOnlyWhenItIsNews() {
        val clock = HlcClock("deck") { 1_000L }
        val base = ProfileMeta().withGame(GameRecord(key, trophies = mapOf("ps3:1" to TrophyUnlock("ps3", "1", unlockedAt = 2_000, device = "pc"))))
        val local = ProfileMeta().withGame(GameRecord(key, trophies = mapOf(
            "ps3:1" to TrophyUnlock("ps3", "1", unlockedAt = 3_000, device = "deck"),
            "ps3:2" to TrophyUnlock("ps3", "2", unlockedAt = 4_000, device = "deck"),
        )))
        val change = ProfileDiff.changes(base, local, "deck", clock)
        assertEquals(setOf("ps3:2"), change.games.getValue(key.id).trophies.keys, "the household already knew of the first")
        assertTrue(ProfileDiff.changes(local, local, "deck", clock).games.isEmpty())
    }
}
