package io.github.matiyaaa.fuse.sync

import com.sun.net.httpserver.HttpServer
import io.github.matiyaaa.fuse.integrations.net.RouteMode
import io.github.matiyaaa.fuse.integrations.net.RoutePicker
import io.github.matiyaaa.fuse.romm.RommClient
import io.github.matiyaaa.fuse.romm.RommCredential
import io.github.matiyaaa.fuse.romm.RommScopes
import io.github.matiyaaa.fuse.sync.romm.JvmRommSaves
import io.github.matiyaaa.fuse.sync.romm.RommPack
import io.github.matiyaaa.fuse.sync.romm.RommSaveGate
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * RomM's save sync for saves of folders, by person, and while RomM is away: a PSP save folder goes up
 * as one zip, RomM hashes it as it hashes zips, and it comes down whole on another device.
 */
class RommFolderSavesTest {
    private val root = Files.createTempDirectory("fuse-romm-folders").toFile()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** RomM's saves API, as far as Fuse uses it, hashing as RomM 5.4 does. */
    private class Stored(val id: Long, val romId: Long, val name: String, val slot: String?, val emulator: String?, val bytes: ByteArray, val at: String)

    private val stored = CopyOnWriteArrayList<Stored>()
    private val ids = AtomicLong(100)
    private val clock = AtomicLong(0)

    /** RomM away: every request answered 503. */
    private val away = AtomicBoolean(false)

    private fun md5(b: ByteArray) = MessageDigest.getInstance("MD5").digest(b).joinToString("") { "%02x".format(it) }

    /** As RomM's hash_save_content: a zip by its contents, anything else by its bytes. */
    private fun rommHash(b: ByteArray) = if (b.size > 4 && b[0] == 'P'.code.toByte() && b[1] == 'K'.code.toByte()) RommPack.contentHash(b) else md5(b)

    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { ex ->
            val path = ex.requestURI.path
            val q = ex.requestURI.rawQuery.orEmpty().split('&').filter { '=' in it }.associate { it.substringBefore('=') to java.net.URLDecoder.decode(it.substringAfter('='), "UTF-8") }
            fun send(code: Int, body: ByteArray) {
                ex.sendResponseHeaders(code, if (body.isEmpty()) -1 else body.size.toLong())
                if (body.isNotEmpty()) ex.responseBody.use { it.write(body) } else ex.close()
            }
            fun json(s: Stored) = """{"id":${s.id},"rom_id":${s.romId},"file_name":"${s.name}","file_size_bytes":${s.bytes.size},"content_hash":"${rommHash(s.bytes)}","slot":${s.slot?.let { "\"$it\"" } ?: "null"},"emulator":${s.emulator?.let { "\"$it\"" } ?: "null"},"updated_at":"${s.at}"}"""
            when {
                away.get() -> send(503, ByteArray(0))
                ex.requestMethod == "POST" && path == "/api/devices" -> send(200, """{"device_id":"dev-${ids.incrementAndGet()}"}""".toByteArray())
                ex.requestMethod == "GET" && path == "/api/devices" -> send(200, "[]".toByteArray())
                ex.requestMethod == "GET" && path == "/api/saves" ->
                    send(200, stored.filter { it.romId == q["rom_id"]?.toLong() }.reversed().joinToString(",", "[", "]", transform = ::json).toByteArray())
                ex.requestMethod == "GET" && path.matches(Regex("/api/saves/\\d+/content")) ->
                    stored.firstOrNull { it.id == path.split('/')[3].toLong() }?.let { send(200, it.bytes) } ?: send(404, ByteArray(0))
                ex.requestMethod == "POST" && path.matches(Regex("/api/saves/\\d+/downloaded")) -> send(200, "{}".toByteArray())
                ex.requestMethod == "POST" && path == "/api/saves" -> {
                    val type = ex.requestHeaders.getFirst("Content-Type").orEmpty()
                    val boundary = "--" + type.substringAfter("boundary=").trim('"')
                    val text = String(ex.requestBody.readBytes(), Charsets.ISO_8859_1)
                    val part = text.split(boundary).first { "saveFile" in it }
                    val name = Regex("filename=\"([^\"]*)\"").find(part)!!.groupValues[1]
                    val bytes = part.substringAfter("\r\n\r\n").removeSuffix("\r\n").toByteArray(Charsets.ISO_8859_1)
                    val s = Stored(ids.incrementAndGet(), q["rom_id"]!!.toLong(), name, q["slot"], q["emulator"], bytes, "2026-10-18T00:00:0${clock.incrementAndGet()}+00:00")
                    stored += s
                    send(200, json(s).toByteArray())
                }
                else -> send(404, ByteArray(0))
            }
        }
        start()
    }

    @AfterTest
    fun cleanUp() {
        http.close()
        server.stop(0)
        scope.cancel()
        root.deleteRecursively()
    }

    private val base = "http://127.0.0.1:${server.address.port}"
    /** Closed after each test: a CIO client holds a thread of its own until then. */
    private val http = HttpClient(CIO)
    private val client = RommClient(
        http,
        RoutePicker(base, null, RouteMode.AUTO, scope, System::currentTimeMillis, probe = { true }, lookEveryMs = 0),
        RommCredential.Token("t", RommScopes.READ + RommScopes.SAVES),
    )

    /** A device with PPSSPP (its memory stick in its config folder, as installed on Linux) and a PSP game. */
    private inner class Device(val name: String, val person: String? = null) {
        val home = File(root, name).apply { mkdirs() }
        val rom = File(home, "Games/Some Game.iso").apply { parentFile.mkdirs(); writeBytes(ByteArray(64)) }
        val savedata = File(home, ".config/ppsspp/PSP/SAVEDATA").apply { mkdirs() }
        val query = SaveQuery(GameKey.of("psp", "ULUS10041", null, "Some Game"), "psp", rom.path, "linux.ppsspp", serial = "ULUS-10041", title = "Some Game")
        fun saves() = JvmRommSaves(File(home, "fuse"), { FileSaveEnvironment("LINUX", home.path) { null } }, { client }, { base }, name, "linux", "test", person = { person })

        fun play(data: String) {
            File(savedata, "ULUS10041DATA00").mkdirs()
            File(savedata, "ULUS10041DATA00/DATA.BIN").writeText(data)
            File(savedata, "ULUS10041DATA00/PARAM.SFO").writeText("param $data")
        }
    }

    @Test
    fun aSaveFolderGoesUpAsOneZipAndComesDownWhole() = runBlocking {
        val deck = Device("deck")
        val desk = Device("desk")
        deck.play("chapter 3")
        deck.saves().afterExit(deck.query, romId = 1)
        val up = stored.single()
        assertEquals("Some Game.zip", up.name)
        assertEquals("PSP Save Data", up.slot, "named for what it is on the console")
        assertEquals("psp.savedata", up.emulator)
        // Nothing changed: nothing goes again.
        deck.saves().afterExit(deck.query, romId = 1)
        assertEquals(1, stored.size)

        // The desk has none yet: the whole folder comes down before it starts.
        assertIs<RommSaveGate.Go>(desk.saves().beforeLaunch(desk.query, romId = 1))
        assertEquals("chapter 3", File(desk.savedata, "ULUS10041DATA00/DATA.BIN").readText())
        assertEquals("param chapter 3", File(desk.savedata, "ULUS10041DATA00/PARAM.SFO").readText())

        // Played on the desk: it goes up, and the Deck (unchanged since) takes it, keeping its own aside.
        desk.play("chapter 4")
        desk.saves().afterExit(desk.query, romId = 1)
        assertEquals(2, stored.size)
        deck.saves().beforeLaunch(deck.query, romId = 1)
        assertEquals("chapter 4", File(deck.savedata, "ULUS10041DATA00/DATA.BIN").readText())
        assertTrue(File(deck.home, "fuse/kept/RomM").walk().any { it.isFile && it.readText() == "chapter 3" }, "the Deck's own kept aside")
    }

    @Test
    fun bothChangedSinceTheyAgreedIsAskedNeverReplaced() = runBlocking {
        val deck = Device("deck")
        val desk = Device("desk")
        deck.play("one")
        deck.saves().afterExit(deck.query, romId = 1)
        desk.saves().beforeLaunch(desk.query, romId = 1)
        // Both played since: the desk's goes up first; the Deck is asked, its save left as it is.
        desk.play("desk two")
        desk.saves().afterExit(desk.query, romId = 1)
        deck.play("deck two")
        assertIs<RommSaveGate.Conflict>(deck.saves().beforeLaunch(deck.query, romId = 1))
        assertEquals("deck two", File(deck.savedata, "ULUS10041DATA00/DATA.BIN").readText())
    }

    @Test
    fun aSaveThatCouldntGoWaitsAndGoesWhenRommAnswersEvenAfterARestart() = runBlocking {
        val deck = Device("deck")
        deck.play("offline")
        away.set(true)
        deck.saves().afterExit(deck.query, romId = 1)
        assertEquals(0, stored.size)
        assertEquals(1, deck.saves().waiting, "waiting, kept across a restart")
        away.set(false)
        assertEquals(1, deck.saves().sendWaiting())
        assertEquals(1, stored.size)
        assertEquals(0, deck.saves().waiting)
        assertEquals(RommPack.contentHash(stored.single().bytes), RommPack.contentHash(Slots.of(deck.query.game, SaveSpot(SaveKind.SAVE, "psp.savedata", root = deck.savedata.path, folders = listOf("ULUS10041DATA00")))))
    }

    @Test
    fun eachPersonsSavesAreTheirOwn() = runBlocking {
        val moDeck = Device("deck", person = "Mo")
        moDeck.play("Mo's")
        moDeck.saves().afterExit(moDeck.query, romId = 1)
        assertEquals("PSP Save Data (Mo)", stored.single().slot)
        // Sam, on another device: Mo's save isn't theirs.
        val samDesk = Device("desk", person = "Sam")
        samDesk.saves().beforeLaunch(samDesk.query, romId = 1)
        assertTrue(!File(samDesk.savedata, "ULUS10041DATA00").exists())
        // Mo, on that device: theirs comes down.
        val moDesk = Device("desk", person = "Mo")
        moDesk.saves().beforeLaunch(moDesk.query, romId = 1)
        assertEquals("Mo's", File(moDesk.savedata, "ULUS10041DATA00/DATA.BIN").readText())
    }
}
