package io.github.matiyaaa.fuse.launch.online

import java.io.DataInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * An RPCN account made as RPCS3's client makes one, against a fake RPCN speaking its packets
 * (rpcs3/Emu/NP/rpcn_client.cpp): the server's protocol first, then the request, then its answer.
 */
class RpcnAccountTest {
    private val server = ServerSocket(0, 5, InetAddress.getLoopbackAddress())
    private var received: ByteArray? = null

    @AfterTest
    fun close() = server.close()

    /** A fake RPCN that says [protocol], sends a notification first, then answers [code]. */
    private fun rpcn(protocol: Int, code: Int) {
        thread(isDaemon = true) {
            runCatching {
                server.accept().use { s ->
                    val out = s.getOutputStream()
                    out.write(packet(Rpcn.SERVER_INFO, 0, 0, le(protocol.toLong(), 4)))
                    out.flush()
                    val input = DataInputStream(s.getInputStream())
                    val head = ByteArray(Rpcn.HEADER).also { input.readFully(it) }
                    val h = Rpcn.header(head)
                    val data = ByteArray((h.size - Rpcn.HEADER).toInt()).also { input.readFully(it) }
                    received = head + data
                    // Something else first (a notification), then the reply to this request.
                    out.write(packet(2, 7, 0, byteArrayOf(1, 2, 3)))
                    out.write(packet(Rpcn.REPLY, Rpcn.CREATE, h.id, byteArrayOf(code.toByte())))
                    out.flush()
                }
            }
        }
    }

    private fun le(v: Long, n: Int) = ByteArray(n) { i -> ((v ushr (8 * i)) and 0xFF).toByte() }

    private fun packet(type: Int, command: Int, id: Long, data: ByteArray): ByteArray =
        byteArrayOf(type.toByte()) + le(command.toLong(), 2) + le((Rpcn.HEADER + data.size).toLong(), 4) + le(id, 8) + data

    private fun create(): String? = runBlocking {
        RpcnNetwork.createWith("127.0.0.1", server.localPort, "mo_plays", "DERIVED", "mo@example.com") { h, p -> Socket(h, p) }
    }

    @Test
    fun anAccountIsMadeWithTheRequestRpcs3Sends() {
        rpcn(Rpcn.PROTOCOL, 0)
        assertNull(create())
        val sent = received!!
        val h = Rpcn.header(sent)
        assertEquals(Rpcn.REQUEST to Rpcn.CREATE, h.type to h.command)
        assertEquals(sent.size.toLong(), h.size)
        val fields = sent.copyOfRange(Rpcn.HEADER, sent.size).decodeToString().split('\u0000')
        assertEquals(listOf("mo_plays", "DERIVED", "mo_plays", Rpcn.AVATAR, "mo@example.com", ""), fields)
    }

    @Test
    fun rpcnsRefusalIsSaidInWords() {
        rpcn(Rpcn.PROTOCOL, 11)
        assertEquals("There's already an RPCN account with that user name.", create())
    }

    @Test
    fun aNewerProtocolIsLeftToRpcs3() {
        rpcn(Rpcn.PROTOCOL + 1, 0)
        assertTrue(create()!!.contains("RPCS3"))
        assertNull(received, "nothing is sent to a server Fuse doesn't speak to")
    }

    @Test
    fun emailsAreCheckedAsRpcs3ChecksThem() {
        assertTrue(Rpcn.validEmail("mo.plays+rpcn@example.co.uk"))
        assertTrue(!Rpcn.validEmail("not an email"))
        assertTrue(!Rpcn.validEmail("mo@"))
    }
}
