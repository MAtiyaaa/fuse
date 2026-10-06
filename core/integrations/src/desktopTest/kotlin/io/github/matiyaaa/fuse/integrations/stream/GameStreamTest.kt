package io.github.matiyaaa.fuse.integrations.stream

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What a Sunshine host says on its open port, and waking it. */
class GameStreamTest {
    private val sunshine = """
        <?xml version="1.0" encoding="utf-8"?>
        <root status_code="200">
          <hostname>GAMING-PC</hostname>
          <appversion>7.1.431.-1</appversion>
          <uniqueid>A1B2C3D4-0000-1111-2222-333344445555</uniqueid>
          <HttpsPort>47984</HttpsPort>
          <mac>00:11:22:AA:BB:CC</mac>
          <LocalIP>192.168.1.20</LocalIP>
          <PairStatus>0</PairStatus>
          <currentgame>0</currentgame>
          <state>SUNSHINE_SERVER_FREE</state>
        </root>
    """.trimIndent()

    @Test
    fun `a host's own answer is read, and a hidden network card is no network card`() {
        val info = assertNotNull(GameStream.parse(sunshine))
        assertEquals("GAMING-PC", info.name)
        assertEquals("A1B2C3D4-0000-1111-2222-333344445555", info.uniqueId)
        assertEquals("00:11:22:AA:BB:CC", info.mac)
        assertEquals(false, info.busy)
        // Unpaired, some hosts send zeros: that can't wake anything.
        assertNull(GameStream.parse(sunshine.replace("00:11:22:AA:BB:CC", "00:00:00:00:00:00"))?.mac)
        assertTrue(GameStream.parse(sunshine.replace("SUNSHINE_SERVER_FREE", "SUNSHINE_SERVER_BUSY"))!!.busy)
        assertNull(GameStream.parse("<html>not a host</html>"))
    }

    @Test
    fun `addresses get GameStream's port unless they name one`() {
        assertEquals("192.168.1.20:47989", GameStream.hostPort("192.168.1.20"))
        assertEquals("pc.local:47990", GameStream.hostPort("http://pc.local:47990/"))
        assertEquals("[fe80::1]:47989", GameStream.hostPort("[fe80::1]"))
        assertEquals("[fe80::1]:5000", GameStream.hostPort("[fe80::1]:5000"))
    }

    @Test
    fun `the magic packet is six 0xFF and the card's address sixteen times`() {
        val p = assertNotNull(WakeOnLan.packet("00-11-22-aa-bb-cc"))
        assertEquals(102, p.size)
        assertContentEquals(ByteArray(6) { 0xFF.toByte() }, p.copyOfRange(0, 6))
        assertContentEquals(byteArrayOf(0x00, 0x11, 0x22, 0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte()), p.copyOfRange(96, 102))
        assertNull(WakeOnLan.packet("not a mac"))
    }

    @Test
    fun `asking a host reads serverinfo, and a host that isn't there is just not there`() = runBlocking<Unit> {
        val http = HttpClient(MockEngine { req ->
            if (req.url.host == "192.168.1.20" && req.url.port == 47989 && req.url.encodedPath == "/serverinfo") respond(sunshine)
            else respond("", HttpStatusCode.NotFound)
        }) { install(HttpTimeout) }
        assertEquals("GAMING-PC", GameStream(http).serverInfo("192.168.1.20")?.name)
        assertNull(GameStream(http).serverInfo("192.168.1.99"))
    }
}
