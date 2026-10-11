package io.github.matiyaaa.fuse.link

import io.github.matiyaaa.fuse.data.FuseData
import io.github.matiyaaa.fuse.data.db.DesktopDatabase
import io.github.matiyaaa.fuse.ui.shell.store.createFuseStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.Socket
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The Decky bridge on a real loopback port, spoken to as the plugin would (and as anything else here would). */
class DeckyBridgeTest {
    private lateinit var cache: File
    private lateinit var home: File
    private lateinit var scope: CoroutineScope

    @BeforeTest
    fun setUp() {
        cache = Files.createTempDirectory("fuse-decky-cache").toFile()
        home = Files.createTempDirectory("fuse-decky-home").toFile()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
        cache.deleteRecursively()
        home.deleteRecursively()
    }

    private fun http(port: Int, method: String, path: String, token: String?): Pair<Int, String> {
        Socket("127.0.0.1", port).use { s ->
            s.soTimeout = 10_000
            val request = buildString {
                append("$method $path HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nConnection: close\r\n")
                if (token != null) append("Authorization: Bearer $token\r\n")
                if (method != "GET") append("Content-Length: 0\r\n")
                append("\r\n")
            }
            s.getOutputStream().apply { write(request.encodeToByteArray()); flush() }
            val raw = s.getInputStream().readBytes().decodeToString()
            val status = raw.lineSequence().first().split(' ')[1].toInt()
            var body = raw.substringAfter("\r\n\r\n")
            if (raw.substringBefore("\r\n\r\n").contains("chunked", ignoreCase = true)) body = body.lines().filterIndexed { i, _ -> i % 2 == 1 }.joinToString("")
            return status to body
        }
    }

    @Test
    fun onlyThePluginWithTheTokenIsAnsweredAndTheFileGoesWithTheBridge(): Unit = runBlocking {
        val store = createFuseStore(FakeServices(FuseData(DesktopDatabase.open(File(cache, "fuse.db").absolutePath)), cache), scope)
        val file = DeckyBridge.discoveryFile(home.path, xdgData = null)
        val bridge = DeckyBridge(store, "0.4.2", file)
        val port = bridge.start()
        try {
            assertTrue(port > 0)
            // The plugin finds the port and token in a file only this user can read.
            val found = Json.parseToJsonElement(file.readText()).jsonObject
            assertEquals(port, found.getValue("port").jsonPrimitive.int)
            assertEquals(DeckyBridge.PROTOCOL, found.getValue("protocol").jsonPrimitive.int)
            val perms = Files.getPosixFilePermissions(file.toPath())
            assertEquals(setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE), perms)
            val token = found.getValue("token").jsonPrimitive.content

            // Without the token, or with another, nothing is answered.
            assertEquals(401, http(port, "GET", "/v1/hello", null).first)
            assertEquals(401, http(port, "GET", "/v1/hello", "0".repeat(token.length)).first)
            assertEquals(401, http(port, "POST", "/v1/emulators/uninstall-all", null).first)

            val (ok, hello) = http(port, "GET", "/v1/hello", token)
            assertEquals(200, ok)
            assertEquals("Fuse", Json.parseToJsonElement(hello).jsonObject.getValue("app").jsonPrimitive.content)
            val (_, status) = http(port, "GET", "/v1/status", token)
            assertTrue("fuseOne" in Json.parseToJsonElement(status).jsonObject)

            // A Steam game that isn't installed here can't be added; a bad id is refused.
            val (_, add) = http(port, "POST", "/v1/steam/400/add", token)
            assertEquals(0, Json.parseToJsonElement(add).jsonObject.getValue("added").jsonPrimitive.int)
            assertEquals(400, http(port, "POST", "/v1/steam/nope/add", token).first)
            val (_, steam) = http(port, "GET", "/v1/steam/400", token)
            assertEquals("false", Json.parseToJsonElement(steam).jsonObject.getValue("inFuse").jsonPrimitive.content)
        } finally {
            bridge.close()
        }
        // Gone with the bridge: a plugin never talks to a Fuse that isn't there.
        assertFalse(file.exists())
    }
}
