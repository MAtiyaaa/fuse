package io.github.matiyaaa.fuse.sync

import com.sun.net.httpserver.HttpServer
import io.github.matiyaaa.fuse.integrations.net.RouteMode
import io.github.matiyaaa.fuse.integrations.net.RoutePicker
import io.github.matiyaaa.fuse.romm.RommClient
import io.github.matiyaaa.fuse.romm.RommCredential
import io.github.matiyaaa.fuse.romm.RommScopes
import io.github.matiyaaa.fuse.sync.romm.JvmRommSaves
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
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** RetroArch's save states go to RomM after a game and come back on another device before it starts. */
class RommStatesTest {
    private val root = Files.createTempDirectory("fuse-romm-states").toFile()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** RomM's states API, as far as Fuse uses it. */
    private class Stored(val id: Long, val romId: Long, val name: String, val emulator: String?, val bytes: ByteArray, val at: String)

    private val stored = CopyOnWriteArrayList<Stored>()
    private val ids = AtomicLong(100)
    private val clock = AtomicLong(0)
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { ex ->
            val path = ex.requestURI.path
            val q = ex.requestURI.rawQuery.orEmpty().split('&').filter { '=' in it }.associate { it.substringBefore('=') to java.net.URLDecoder.decode(it.substringAfter('='), "UTF-8") }
            fun send(code: Int, body: ByteArray) {
                ex.sendResponseHeaders(code, body.size.toLong().coerceAtLeast(1).let { if (body.isEmpty()) -1 else it })
                if (body.isNotEmpty()) ex.responseBody.use { it.write(body) } else ex.close()
            }
            fun json(s: Stored) = """{"id":${s.id},"rom_id":${s.romId},"file_name":"${s.name}","file_size_bytes":${s.bytes.size},"content_hash":"${md5(s.bytes)}","emulator":${s.emulator?.let { "\"$it\"" } ?: "null"},"updated_at":"${s.at}"}"""
            when {
                ex.requestMethod == "GET" && path == "/api/states" ->
                    send(200, stored.filter { it.romId == q["rom_id"]?.toLong() }.joinToString(",", "[", "]", transform = ::json).toByteArray())
                ex.requestMethod == "GET" && path.matches(Regex("/api/states/\\d+/content")) ->
                    stored.firstOrNull { it.id == path.split('/')[3].toLong() }?.let { send(200, it.bytes) } ?: send(404, ByteArray(0))
                ex.requestMethod == "POST" && path == "/api/states/delete" -> {
                    val body = ex.requestBody.readBytes().decodeToString()
                    Regex("\\d+").findAll(body.substringAfter('[')).map { it.value.toLong() }.toSet().let { gone -> stored.removeIf { it.id in gone } }
                    send(200, "[]".toByteArray())
                }
                ex.requestMethod == "POST" && path == "/api/states" -> {
                    val type = ex.requestHeaders.getFirst("Content-Type").orEmpty()
                    val boundary = "--" + type.substringAfter("boundary=").trim('"')
                    val raw = ex.requestBody.readBytes()
                    val text = String(raw, Charsets.ISO_8859_1)
                    val part = text.split(boundary).first { "stateFile" in it }
                    val name = Regex("filename=\"([^\"]*)\"").find(part)!!.groupValues[1]
                    val bytes = part.substringAfter("\r\n\r\n").removeSuffix("\r\n").toByteArray(Charsets.ISO_8859_1)
                    // RomM keeps a name of its own, the time in it.
                    val kept = name.substringBeforeLast('.') + " [2026-10-14 0${clock.get()}-00-00]." + name.substringAfterLast('.')
                    val s = Stored(ids.incrementAndGet(), q["rom_id"]!!.toLong(), kept, q["emulator"], bytes, "2026-10-14T00:00:0${clock.incrementAndGet()}+00:00")
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
        server.stop(0)
        scope.cancel()
        root.deleteRecursively()
    }

    private fun md5(b: ByteArray) = MessageDigest.getInstance("MD5").digest(b).joinToString("") { "%02x".format(it) }

    private val base = "http://127.0.0.1:${server.address.port}"
    private val client = RommClient(
        HttpClient(CIO),
        RoutePicker(base, null, RouteMode.AUTO, scope, System::currentTimeMillis, probe = { true }, lookEveryMs = 0),
        RommCredential.Token("t", RommScopes.READ + RommScopes.SAVES),
    )

    /** A device with its own home, RetroArch on it, and Golden Sun. */
    private inner class Device(name: String) {
        val home = File(root, name).apply { mkdirs() }
        val rom = File(home, "Games/Golden Sun.gba").apply { parentFile.mkdirs(); writeBytes(ByteArray(64)) }
        // RetroArch's own folder, as an installed RetroArch has.
        val states = File(home, ".config/retroarch/states").also { it.parentFile.mkdirs() }
        val query = SaveQuery(GameKey.of("gba", null, null, "Golden Sun"), "gba", rom.path, "linux.retroarch", core = "mgba", title = "Golden Sun")
        val saves = JvmRommSaves(File(home, "fuse"), { FileSaveEnvironment("LINUX", home.path) { null } }, { client }, { base }, name, "linux", "test")
    }

    @Test
    fun statesGoUpAfterAGameAndComeDownOnAnotherDevice() = runBlocking {
        val deck = Device("deck")
        val desk = Device("desk")
        // Played on the Deck: two slots.
        deck.states.mkdirs()
        File(deck.states, "Golden Sun.state").writeBytes(ByteArray(300) { 1 })
        File(deck.states, "Golden Sun.state1").writeBytes(ByteArray(200) { 2 })
        deck.saves.afterExit(deck.query, romId = 1)
        assertEquals(2, stored.size, "both slots sent")
        assertTrue(stored.all { it.emulator == "retroarch.state:mgba" }, "marked with the core that made them")

        // Nothing changed on the Deck: nothing goes again.
        deck.saves.afterExit(deck.query, romId = 1)
        assertEquals(2, stored.size)

        // The desk has none yet: both come down before it starts.
        desk.saves.beforeLaunch(desk.query, romId = 1)
        assertContentEquals(ByteArray(300) { 1 }, File(desk.states, "Golden Sun.state").readBytes())
        assertContentEquals(ByteArray(200) { 2 }, File(desk.states, "Golden Sun.state1").readBytes())

        // Played on the desk: its slot goes up and its earlier copy on RomM goes away.
        File(desk.states, "Golden Sun.state").writeBytes(ByteArray(300) { 9 })
        desk.saves.afterExit(desk.query, romId = 1)
        val slot0 = stored.filter { it.name.startsWith("Golden Sun [") && it.name.endsWith(".state") }
        assertEquals(1, slot0.size, "one copy of a slot on RomM")
        assertContentEquals(ByteArray(300) { 9 }, slot0.single().bytes)

        // Back on the Deck, whose slot didn't change since: the desk's comes down, the Deck's kept aside.
        deck.saves.beforeLaunch(deck.query, romId = 1)
        assertContentEquals(ByteArray(300) { 9 }, File(deck.states, "Golden Sun.state").readBytes())
        assertTrue(File(deck.home, "fuse/kept/RomM").walk().any { it.name == "Golden Sun.state" }, "the Deck's own kept aside")
    }

    @Test
    fun aStateChangedHereIsntReplaced() = runBlocking {
        val deck = Device("deck")
        val desk = Device("desk")
        deck.states.mkdirs()
        File(deck.states, "Golden Sun.state").writeBytes(ByteArray(100) { 1 })
        deck.saves.afterExit(deck.query, romId = 1)
        // The desk made its own state, never agreed with RomM: it stays, and goes up after its game.
        desk.states.mkdirs()
        File(desk.states, "Golden Sun.state").writeBytes(ByteArray(100) { 7 })
        desk.saves.beforeLaunch(desk.query, romId = 1)
        assertContentEquals(ByteArray(100) { 7 }, File(desk.states, "Golden Sun.state").readBytes())
    }

    @Test
    fun aRetroArchSaveGoesToRommToo() = runBlocking {
        // Its .srm, though RetroArch may keep an .rtc beside it: the save itself goes, the clock stays here.
        val deck = Device("deck")
        val saves = File(deck.home, ".config/retroarch/saves").apply { mkdirs() }
        File(saves, "Golden Sun.srm").writeBytes(ByteArray(512) { 5 })
        val spot = deck.saves.spotOf(deck.query)
        assertEquals("Golden Sun.srm", spot?.files?.first()?.path?.substringAfterLast('/'))
    }
}
