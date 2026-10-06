package io.github.matiyaaa.fuse.sync

import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.util.zip.GZIPInputStream
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The Hub opens at once however much the host keeps: the page lists every game, and a game's
 * versions and files come only when it is opened. A Refresh with nothing new is a 304, and a big
 * page goes gzipped.
 */
class HubTest {
    private lateinit var root: File
    private lateinit var host: SyncHost
    private var port = 0
    private val http = SyncClient.defaultClient()

    @BeforeTest
    fun start(): Unit = runBlocking {
        root = Files.createTempDirectory("fuse-hub").toFile()
        host = SyncHost(HostStore(File(root, "host"), hostName = "Gaming PC"), port = 0, bind = "127.0.0.1", callsPerMinute = 100_000).start()
        port = host.boundPort()
    }

    @AfterTest
    fun stop() {
        host.stop()
        root.deleteRecursively()
    }

    private fun get(path: String, headers: Map<String, String> = emptyMap()): Triple<Int, Map<String, String>, String> {
        val c = URI("http://127.0.0.1:$port$path").toURL().openConnection() as HttpURLConnection
        headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
        val code = c.responseCode
        val heads = c.headerFields.filterKeys { it != null }.mapValues { it.value.joinToString(",") }.mapKeys { it.key.lowercase() }
        val raw = (if (code >= 400) c.errorStream else c.inputStream)?.readBytes() ?: ByteArray(0)
        val body = if (heads["content-encoding"] == "gzip") GZIPInputStream(raw.inputStream()).readBytes() else raw
        return Triple(code, heads, body.decodeToString())
    }

    @Test
    fun theHubListsEveryGameAndBringsAGamesVersionsWhenOpened(): Unit = runBlocking {
        val code = host.newPairingCode()
        val id = "dev-" + SyncCrypto.token(8)
        val link = SyncClient.pair("127.0.0.1:$port", code, id, "Steam Deck", "LINUX", http)
        val client = SyncClient(link, http)
        val device = SyncDevice(File(root, "deck"), id, "Steam Deck")
        val mo = client.createProfile(NewProfile("Mo", "fox")).id
        val games = (1..300).associate { i ->
            val key = GameKey.of("snes", "SNS-$i", null, "Game $i")
            key.id to GameRecord(key, playSeconds = mapOf(id to i * 60L), lastPlayed = i.toLong())
        }
        client.pushMeta(mo, ProfileMeta(games = games))
        // One game with saves: three versions of a two-file save.
        val key = GameKey.of("snes", "SNS-7", null, "Game 7")
        val saves = File(root, "saves").apply { mkdirs() }
        for (n in 1..3) {
            File(saves, "Game 7.srm").writeText("save $n")
            File(saves, "Game 7.rtc").writeText("clock $n")
            val files = saves.listFiles()!!.map { LocalFile(it.name, it) }
            assertNotNull(device.capture(mo, LocalSlot(key, SaveKind.SAVE, "retroarch.srm", saves, files), n * 100L))
            device.flush(client)
        }

        val (status, heads, page) = get("/hub", mapOf("Accept-Encoding" to "gzip"))
        assertEquals(200, status)
        assertEquals("gzip", heads["content-encoding"], "a page this size goes gzipped")
        assertEquals(300, Regex("<details class=\"game\"").findAll(page).count(), "every game is listed")
        assertFalse("Game 7.srm" in page, "no game's files are on the first page")
        assertTrue(page.length < 300 * 1_000, "the first page stays small: ${page.length} characters")

        // Opening Game 7 brings its versions and their files.
        val (gameStatus, _, body) = get("/hub/game?p=${mo}&g=${java.net.URLEncoder.encode(key.id, Charsets.UTF_8)}")
        assertEquals(200, gameStatus)
        assertTrue("Game 7.srm" in body && "Game 7.rtc" in body)
        assertEquals(3, Regex("<tr").findAll(body).count() - 1, "three versions under one heading row")
        // Without scripting, the same as a page of its own.
        assertTrue("<!doctype html>" in get("/hub/game?p=${mo}&g=${java.net.URLEncoder.encode(key.id, Charsets.UTF_8)}&page=1").third)
        assertEquals(404, get("/hub/game?p=$mo&g=nothing").first)

        // Refresh with nothing new: 304, no page sent again.
        val tag = assertNotNull(heads["etag"])
        assertEquals(304, get("/hub", mapOf("If-None-Match" to tag)).first)
    }
}
