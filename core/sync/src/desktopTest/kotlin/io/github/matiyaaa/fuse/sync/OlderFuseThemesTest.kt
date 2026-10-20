package io.github.matiyaaa.fuse.sync

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A Fuse from before 0.4.3.2 sends Fuse for a theme it hasn't got (Twin, from 0.4.3.1, on a 0.4.3
 * Deck): the host leaves that out, so the person's theme stays chosen on every device. A theme that
 * Fuse has, or anything else it sends, still counts.
 */
class OlderFuseThemesTest {
    private fun theme(id: String, at: Long, device: String) = OlderFuseThemes.THEME to Lww<kotlinx.serialization.json.JsonElement>(JsonPrimitive(id), Hlc(at, 0, device))
    private fun meta(vararg settings: Pair<String, Lww<kotlinx.serialization.json.JsonElement>>) = ProfileMeta(settings = settings.toMap())

    @Test
    fun whichFuseHasWhichTheme() {
        assertTrue(OlderFuseThemes.has("0.4.3", "glass", emptySet()))
        assertTrue(OlderFuseThemes.has("", "fusi", emptySet()), "built in before 0.4.2 too")
        assertFalse(OlderFuseThemes.has("0.4.3", "twin", emptySet()))
        assertFalse(OlderFuseThemes.has("0.4.2.27", "viewfinder", emptySet()))
        assertTrue(OlderFuseThemes.has("0.4.3.1", "twin", emptySet()))
        assertTrue(OlderFuseThemes.has("0.4.2.13", "trio", emptySet()))
        assertFalse(OlderFuseThemes.has("0.4.2.14", "trio", emptySet()), "retired in 0.4.2.14")
        assertFalse(OlderFuseThemes.has("0.4.2.11", "fii", emptySet()), "not yet in 0.4.2.11")
        assertTrue(OlderFuseThemes.has("0.4.2", "my-theme", setOf("my-theme")), "an added theme comes with the profile")
        assertFalse(OlderFuseThemes.has("0.4.3.1", "aurora", emptySet()), "a newer Fuse's theme")
    }

    @Test
    fun fuseFromAFuseWithoutTheThemeIsLeftOut() {
        val current = meta(theme("twin", 1_000, "thor"))
        val sent = meta(theme("fuse", 2_000, "deck"), "appearance.textScale" to Lww(JsonPrimitive(1.2f), Hlc(2_000, 0, "deck")))
        val kept = OlderFuseThemes.keep(current, sent, "0.4.3")
        assertFalse(OlderFuseThemes.THEME in kept.settings)
        assertTrue("appearance.textScale" in kept.settings, "everything else it sent counts")
        assertEquals("twin", (current.merge(kept).settings.getValue(OlderFuseThemes.THEME).value as JsonPrimitive).content)
    }

    @Test
    fun aRealChoiceStillCounts() {
        val current = meta(theme("twin", 1_000, "thor"))
        // A Fuse that has Twin chose Fuse: the person did.
        assertEquals(current.settings.size, OlderFuseThemes.keep(current, meta(theme("fuse", 2_000, "pc")), "0.4.3.1").settings.size)
        // From 0.4.3.2 on, Fuse never sends Fuse for a theme it hasn't got.
        assertTrue(OlderFuseThemes.THEME in OlderFuseThemes.keep(current, meta(theme("fuse", 2_000, "pc")), "0.4.3.2").settings)
        // Another theme chosen on an old Fuse is a choice too.
        assertTrue(OlderFuseThemes.THEME in OlderFuseThemes.keep(current, meta(theme("glass", 2_000, "deck")), "0.4.3").settings)
        // Fuse over a theme every Fuse has.
        assertTrue(OlderFuseThemes.THEME in OlderFuseThemes.keep(meta(theme("glass", 1_000, "thor")), meta(theme("fuse", 2_000, "deck")), "0.4.3").settings)
        // A theme added to the profile goes with it, so an old Fuse has it.
        val added = OlderFuseThemes.CUSTOM to Lww<kotlinx.serialization.json.JsonElement>(
            JsonArray(listOf(JsonObject(mapOf("id" to JsonPrimitive("my-theme"), "json" to JsonPrimitive("{}"))))), Hlc(1_000, 0, "thor"),
        )
        assertTrue(OlderFuseThemes.THEME in OlderFuseThemes.keep(meta(theme("my-theme", 1_000, "thor"), added), meta(theme("fuse", 2_000, "deck")), "0.4.3").settings)
    }

    private lateinit var root: File
    private lateinit var host: SyncHost
    private val http = SyncClient.defaultClient()

    @BeforeTest
    fun start() {
        root = Files.createTempDirectory("fuse-older-themes").toFile()
        host = SyncHost(HostStore(File(root, "host"), hostName = "Gaming PC"), port = 0, bind = "127.0.0.1", callsPerMinute = 100_000).start()
    }

    @AfterTest
    fun stop() {
        host.stop()
        root.deleteRecursively()
        fuseAppVersion = ""
    }

    /** A device paired as Fuse [version], which every later call from this test says too. */
    private suspend fun device(id: String, name: String, version: String): SyncClient {
        fuseAppVersion = version
        return SyncClient(SyncClient.pair("127.0.0.1:${host.boundPort()}", host.newPairingCode(), id, name, "LINUX", http), http)
    }

    @Test
    fun theHostKeepsTheThemeAnOlderDeckSendsFuseOver(): Unit = runBlocking {
        val thor = device("dev-thor0001", "AYN Thor", "0.4.3.4")
        val deck = device("dev-deck0001", "Steam Deck", "0.4.3")
        fuseAppVersion = "0.4.3.4"
        val me = thor.createProfile(NewProfile("Me", "cat")).id
        thor.pushMeta(me, meta(theme("twin", 1_000, "dev-thor0001")))

        // The Deck's Fuse hasn't got Twin: it shows Fuse, saves it, and sends it, with a favourite.
        fuseAppVersion = "0.4.3"
        val key = GameKey.of("gba", "AGB-BGSE", null, "Golden Sun")
        val after = deck.pushMeta(me, ProfileMeta(
            games = mapOf(key.id to GameRecord(key, favorite = Lww(true, Hlc(2_000, 0, "dev-deck0001")))),
            settings = mapOf(theme("fuse", 2_000, "dev-deck0001")),
        ))
        assertEquals("twin", (after.meta.settings.getValue(OlderFuseThemes.THEME).value as JsonPrimitive).content, "the person's theme stays")
        assertEquals(true, after.meta.games.getValue(key.id).favorite?.value, "the rest of what the Deck sent counts")

        // Once the Deck runs a Fuse with Twin, Fuse chosen there is a choice like any other.
        fuseAppVersion = "0.4.3.4"
        deck.status()
        val chosen = deck.pushMeta(me, meta(theme("fuse", 3_000, "dev-deck0001")))
        assertEquals("fuse", (chosen.meta.settings.getValue(OlderFuseThemes.THEME).value as JsonPrimitive).content)
    }
}
