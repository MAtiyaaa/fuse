package io.github.matiyaaa.fuse.desktop.platform

import io.github.matiyaaa.fuse.ui.shell.platform.SteamLiveChange
import io.github.matiyaaa.fuse.ui.shell.platform.SteamLiveQueue
import io.github.matiyaaa.fuse.ui.shell.platform.SteamLogo
import java.io.DataInputStream
import java.io.File
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Steam's library changed through Steam's own debug port, with no plugin: against a fake Steam that
 * serves the page list and the DevTools socket as Steam's client does, and answers each script as
 * Steam's client would.
 */
class SteamCefTest {
    private val dir = File(System.getProperty("java.io.tmpdir"), "fuse-steamcef-${System.nanoTime()}").apply { mkdirs() }
    private val steam = FakeSteam()

    @AfterTest
    fun cleanUp() {
        steam.close()
        dir.deleteRecursively()
    }

    private fun page(title: String, url: String, socket: String = "ws://127.0.0.1:1/devtools/page/x") = SteamCef.Page(title, url, socket)

    @Test
    fun theSharedPageIsFoundAsDeckyLoaderFindsIt() {
        val pages = listOf(
            page("Steam Big Picture Mode", "https://steamloopback.host/routes/library"),
            page("SharedJSContext", "https://steamloopback.host/index.html"),
        )
        assertEquals("SharedJSContext", SteamCef.pick(pages)?.title)
        assertEquals("Steam", SteamCef.pick(listOf(page("Steam", "https://steamloopback.host/routes/home")))?.title)
        assertNull(SteamCef.pick(listOf(page("Some web page", "https://example.com/"))))
        // Only a debugger on this computer is ever reached.
        assertTrue(SteamCef.loopback("ws://127.0.0.1:8080/devtools/page/1"))
        assertFalse(SteamCef.loopback("ws://192.168.1.4:8080/devtools/page/1"))
        assertFalse(SteamCef.loopback("wss://127.0.0.1:8080/devtools/page/1"))
    }

    @Test
    fun anAnswerIsItsValueOrTheErrorInItsOwnWords() {
        fun parse(s: String) = Json.parseToJsonElement(s).jsonObject
        assertEquals(JsonPrimitive(42), SteamCef.outcome(parse("""{"id":1,"result":{"result":{"type":"number","value":42}}}""")).getOrThrow())
        val thrown = SteamCef.outcome(parse("""{"id":1,"result":{"result":{"type":"object"},"exceptionDetails":{"text":"Uncaught","exception":{"description":"Error: Steam didn't make the entry\n    at <anonymous>:4:9"}}}}"""))
        assertEquals("Steam didn't make the entry", thrown.exceptionOrNull()?.message)
        assertEquals("Not allowed", SteamCef.outcome(parse("""{"id":1,"error":{"code":-32000,"message":"Not allowed"}}""")).exceptionOrNull()?.message)
    }

    @Test
    fun aGameGoesIntoTheRunningSteamWithItsPicturesAndLogoPlace(): Unit = runBlocking {
        val queue = SteamLiveQueue(System::currentTimeMillis)
        val client = DesktopSteamClient({ listOf(dir) }, queue, SteamCef("http://127.0.0.1:${steam.port}"))
        val capsule = File(dir, "capsule.png").apply { writeBytes(byteArrayOf(-119, 80, 78, 71, 1, 2, 3)) }
        val logo = File(dir, "logo.png").apply { writeBytes(byteArrayOf(-119, 80, 78, 71, 4, 5)) }
        val page = assertNotNull(SteamCef("http://127.0.0.1:${steam.port}").shared())
        assertEquals("SharedJSContext", page.title)
        val name = "Zelda \"Ocarina\"\nof Time </script>"
        val offered = async {
            queue.offer(
                listOf(
                    SteamLiveChange(
                        op = SteamLiveChange.Op.ADD, gameId = 12, name = name, exe = "\"/opt/Fuse.AppImage\"", startDir = "\"/opt\"",
                        launchOptions = "--fuse-game 12", art = mapOf("capsule" to capsule.path, "logo" to logo.path), logo = SteamLogo.FUSE,
                    ),
                ),
                waitMs = 10_000,
            )
        }
        withTimeout(10_000) { while (!steam.calls.any { "AddShortcut" in it }) { client.round(page); delay(20) } }
        val result = offered.await().single()
        assertTrue(result.ok, result.message)
        assertEquals(FakeSteam.NEW_APP, result.appId)
        assertEquals(mapOf(12L to FakeSteam.NEW_APP), queue.made())
        // The name reaches Steam whole, as a string, however it is written.
        val add = steam.calls.first { "AddShortcut" in it }
        assertTrue(Json.encodeToString(kotlinx.serialization.json.JsonElement.serializer(), JsonPrimitive(name)) in add, add)
        // Each picture as Steam's asset type, then the logo's place.
        val art = steam.calls.filter { "SetCustomArtworkForApp" in it }
        assertEquals(2, art.size)
        assertTrue(art.any { "SetCustomArtworkForApp(${FakeSteam.NEW_APP}, \"${Base64.getEncoder().encodeToString(capsule.readBytes())}\", \"png\", 0)" in it })
        assertTrue(art.any { ", \"png\", 2)" in it })
        assertTrue(steam.calls.any { "SetCustomLogoPositionForApp(${FakeSteam.NEW_APP}" in it && "BottomLeft" in it })
        // Made once: the plugin (or a second round) gets nothing more to make.
        assertEquals(emptyList(), queue.take())
    }

    @Test
    fun whatSteamRefusesIsReportedInItsWordsAndFuseGoesTheUsualWay(): Unit = runBlocking {
        val queue = SteamLiveQueue(System::currentTimeMillis)
        val cef = SteamCef("http://127.0.0.1:${steam.port}")
        val client = DesktopSteamClient({ listOf(dir) }, queue, cef)
        val page = assertNotNull(cef.shared())
        val offered = async { queue.offer(listOf(SteamLiveChange(op = SteamLiveChange.Op.RUN, gameId = 3, appId = 99)), waitMs = 10_000) }
        withTimeout(10_000) { while (!steam.calls.any { "RunGame" in it }) { client.round(page); delay(20) } }
        val result = offered.await().single()
        assertFalse(result.ok)
        assertEquals("Steam doesn't have this entry yet", result.message)
    }

    private fun client(roots: List<File>, decky: Boolean = false, choice: File? = File(dir, "fuse-data/steam-client-port")) =
        DesktopSteamClient({ roots }, SteamLiveQueue(System::currentTimeMillis), SteamCef("http://127.0.0.1:1"), choice = choice, deckyLoader = { decky })

    @Test
    fun thePortIsThePersonsChoiceAndSteamTakesItWhenItStarts(): Unit = runBlocking {
        val second = File(dir, "flatpak").apply { mkdirs() }
        val client = client(listOf(dir, second))
        assertFalse(client.allowed())
        val on = client.allow(true)
        assertTrue(on.isSuccess)
        assertTrue(File(dir, SteamCef.FLAG).isFile && File(second, SteamCef.FLAG).isFile)
        assertEquals(SteamCef.MARK, File(dir, SteamCef.FLAG).readText(), "Fuse marks the flag it writes")
        assertTrue("next time it starts" in on.getOrThrow(), on.getOrThrow())
        assertTrue(client.allowed())
        assertFalse(client.listening(), "nothing listens on that port")
        assertTrue(client.allow(false).isSuccess)
        assertFalse(File(dir, SteamCef.FLAG).exists() || File(second, SteamCef.FLAG).exists(), "Fuse takes away the flags it wrote")
        assertFalse(client.allowed())
        assertTrue(DesktopSteamClient({ emptyList() }, SteamLiveQueue(System::currentTimeMillis)).allow(true).isFailure, "no Steam here")
    }

    @Test
    fun aFlagDeckysInstallerMadeStaysWhenFuseTurnsItOff(): Unit = runBlocking {
        // Decky Loader's installer touches the file: it is there, empty, before Fuse is asked.
        val flag = File(dir, SteamCef.FLAG).apply { writeText("") }
        val client = client(listOf(dir))
        assertTrue(client.allow(true).isSuccess)
        assertEquals("", flag.readText(), "Fuse leaves a flag it didn't write as it is")
        val off = client.allow(false).getOrThrow()
        assertTrue(flag.isFile, "the flag Decky's installer made is still there")
        assertTrue("To close it, remove ${flag.path}" in off, off)
        assertFalse(client.allowed(), "off is off for Fuse, though Steam's port stays open")
    }

    @Test
    fun withDeckyLoaderInstalledEvenFusesOwnFlagStays(): Unit = runBlocking {
        val client = client(listOf(dir), decky = true)
        assertTrue(client.allow(true).isSuccess)
        val flag = File(dir, SteamCef.FLAG)
        assertEquals(SteamCef.MARK, flag.readText())
        val off = client.allow(false).getOrThrow()
        assertTrue(flag.isFile, "Decky Loader uses the port, so the flag stays whoever wrote it")
        assertTrue("for Decky Loader" in off, off)
        assertFalse(client.allowed())
    }

    @Test
    fun theChoiceIsKeptAcrossRunsAndAnOlderFusesFlagReadsAsOn(): Unit = runBlocking {
        val choice = File(dir, "fuse-data/steam-client-port")
        // Turned on by 0.4.3.1 or 0.4.3.2: an empty flag, and no choice kept yet.
        File(dir, SteamCef.FLAG).writeText("")
        assertTrue(client(listOf(dir), choice = choice).allowed(), "a flag from before the choice was kept reads as on")
        assertTrue(client(listOf(dir), choice = choice).allow(false).isSuccess)
        assertTrue(File(dir, SteamCef.FLAG).isFile, "an unmarked flag is never taken away")
        assertFalse(client(listOf(dir), choice = choice).allowed(), "a new run reads the kept choice, not the flag")
        assertTrue(client(listOf(dir), choice = choice).allow(true).isSuccess)
        assertTrue(client(listOf(dir), choice = choice).allowed())
    }

    @Test
    fun deckyLoaderIsFoundByItsProgramOrItsService(): Unit = runBlocking {
        val home = File(dir, "home").apply { mkdirs() }
        val systemd = File(dir, "systemd").apply { mkdirs() }
        assertFalse(deckyLoaderInstalled(home, systemd))
        File(home, "homebrew/services").mkdirs()
        File(home, "homebrew/services/PluginLoader").writeText("")
        assertTrue(deckyLoaderInstalled(home, systemd))
        File(home, "homebrew/services/PluginLoader").delete()
        File(systemd, "plugin_loader.service").writeText("")
        assertTrue(deckyLoaderInstalled(home, systemd))
    }
}

/**
 * Steam's client as its debug port serves it: `GET /json` lists its pages (a decoy first), and each
 * page's socket answers `Runtime.evaluate` as Steam's client would for the calls Fuse makes. Every
 * script it is sent is kept in [calls].
 */
private class FakeSteam : AutoCloseable {
    private val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
    val port: Int get() = server.localPort
    val calls = CopyOnWriteArrayList<String>()

    init {
        thread(isDaemon = true, name = "fake-steam") {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                thread(isDaemon = true) { runCatching { serve(socket) }; runCatching { socket.close() } }
            }
        }
    }

    private fun serve(socket: Socket) {
        val input = DataInputStream(socket.getInputStream().buffered())
        val out = socket.getOutputStream()
        val head = generateSequence { readLine(input) }.takeWhile { it.isNotEmpty() }.toList()
        val path = head.firstOrNull()?.split(' ')?.getOrNull(1).orEmpty()
        val key = head.firstOrNull { it.startsWith("Sec-WebSocket-Key:", ignoreCase = true) }?.substringAfter(':')?.trim()
        if (key == null) {
            val body = if (path == "/json") pages() else "[]"
            out.write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.toByteArray().size}\r\nConnection: close\r\n\r\n$body".toByteArray())
            out.flush()
            return
        }
        val accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray()))
        out.write("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: $accept\r\n\r\n".toByteArray())
        out.flush()
        while (true) {
            val (opcode, payload) = readMessage(input) ?: return
            if (opcode == 8) {
                frame(out, 8, ByteArray(0))
                return
            }
            if (opcode != 1) continue
            val call = Json.parseToJsonElement(String(payload)).jsonObject
            val id = call["id"]?.jsonPrimitive?.longOrNull ?: continue
            val expression = (call["params"] as? JsonObject)?.get("expression")?.jsonPrimitive?.content.orEmpty()
            calls += expression
            frame(out, 1, answer(id, expression).toByteArray())
        }
    }

    private fun pages() = """[
        {"title":"Steam Big Picture Mode","url":"https://steamloopback.host/routes/library","webSocketDebuggerUrl":"ws://127.0.0.1:$port/devtools/page/BPM"},
        {"title":"SharedJSContext","url":"https://steamloopback.host/index.html","webSocketDebuggerUrl":"ws://127.0.0.1:$port/devtools/page/SHARED"},
        {"title":"Elsewhere","url":"https://example.com/","webSocketDebuggerUrl":"ws://10.0.0.9:$port/devtools/page/AWAY"}
    ]"""

    private fun answer(id: Long, expression: String): String = when {
        "RunGame" in expression ->
            """{"id":$id,"result":{"result":{"type":"object","subtype":"error"},"exceptionDetails":{"text":"Uncaught","exception":{"description":"Error: Steam doesn't have this entry yet\n    at <anonymous>:6:47"}}}}"""
        "AddShortcut" in expression -> """{"id":$id,"result":{"result":{"type":"number","value":$NEW_APP}}}"""
        else -> """{"id":$id,"result":{"result":{"type":"boolean","value":true}}}"""
    }

    private fun readLine(input: DataInputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(c.toChar())
        }
    }

    /** One whole message from the client (masked frames, joined across continuations). */
    private fun readMessage(input: DataInputStream): Pair<Int, ByteArray>? {
        var opcode = -1
        val data = java.io.ByteArrayOutputStream()
        while (true) {
            val b0 = input.read().takeIf { it >= 0 } ?: return null
            val b1 = input.readUnsignedByte()
            var length = (b1 and 0x7f).toLong()
            if (length == 126L) length = input.readUnsignedShort().toLong() else if (length == 127L) length = input.readLong()
            val mask = if (b1 and 0x80 != 0) ByteArray(4).also { input.readFully(it) } else null
            val payload = ByteArray(length.toInt()).also { input.readFully(it) }
            if (mask != null) for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
            if (opcode == -1) opcode = b0 and 0x0f
            data.write(payload)
            if (b0 and 0x80 != 0) return opcode to data.toByteArray()
        }
    }

    private fun frame(out: OutputStream, opcode: Int, payload: ByteArray) {
        out.write(0x80 or opcode)
        when {
            payload.size < 126 -> out.write(payload.size)
            payload.size < 65_536 -> { out.write(126); out.write(payload.size shr 8); out.write(payload.size and 0xff) }
            else -> { out.write(127); for (shift in 56 downTo 0 step 8) out.write(((payload.size.toLong() shr shift) and 0xff).toInt()) }
        }
        out.write(payload)
        out.flush()
    }

    override fun close() = server.close()

    companion object {
        /** The id Steam gives the new entry: unsigned 32-bit, above what an Int holds. */
        const val NEW_APP = 3_000_000_123L
    }
}
