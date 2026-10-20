package io.github.matiyaaa.fuse.launch.online

import java.io.DataInputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext

actual object RpcnNetwork {
    actual suspend fun create(host: String, npid: String, derived: String, email: String): String? =
        createWith(host, Rpcn.PORT, npid, derived, email, ::tls)

    /**
     * Makes the account over [connect]'s socket (TLS to RPCN; a plain one in tests): the server's
     * protocol first, then the request, then its answer.
     */
    internal suspend fun createWith(host: String, port: Int, npid: String, derived: String, email: String, connect: (String, Int) -> Socket): String? =
        withContext(Dispatchers.IO) {
            try {
                runInterruptible {
                    connect(host, port).use { socket ->
                        socket.soTimeout = TIMEOUT_MS
                        val input = DataInputStream(socket.getInputStream().buffered())
                        val out = socket.getOutputStream()
                        val (info, version) = read(input)
                        if (info.type != Rpcn.SERVER_INFO || version.size != 4) return@runInterruptible "RPCN answered in a way Fuse doesn't know."
                        val protocol = (version[0].toInt() and 0xFF) or ((version[1].toInt() and 0xFF) shl 8) or ((version[2].toInt() and 0xFF) shl 16) or ((version[3].toInt() and 0xFF) shl 24)
                        if (protocol != Rpcn.PROTOCOL) return@runInterruptible "RPCN speaks a newer protocol than Fuse. Make the account in RPCS3: Configuration, RPCN."
                        out.write(Rpcn.createRequest(REQUEST_ID, npid, derived, email))
                        out.flush()
                        // Notifications may come first; the answer is the reply to this request.
                        var reply: ByteArray
                        do {
                            val (h, data) = read(input)
                            reply = data
                        } while (!(h.type == Rpcn.REPLY && h.id == REQUEST_ID && h.command == Rpcn.CREATE))
                        if (reply.isEmpty()) "RPCN gave no answer." else Rpcn.creationProblem(reply[0].toInt() and 0xFF)
                    }
                }
            } catch (e: java.net.SocketTimeoutException) {
                "RPCN didn't answer in time. Try again later."
            } catch (e: IOException) {
                "Fuse couldn't reach RPCN. Check this device is online, then try again."
            }
        }

    private fun read(input: DataInputStream): Pair<Rpcn.Header, ByteArray> {
        val head = ByteArray(Rpcn.HEADER)
        input.readFully(head)
        val h = Rpcn.header(head)
        if (h.size < Rpcn.HEADER || h.size > MAX_PACKET) throw IOException("bad packet")
        val data = ByteArray((h.size - Rpcn.HEADER).toInt())
        input.readFully(data)
        return h to data
    }

    /**
     * TLS 1.2 to RPCN, as RPCS3 connects (rpcn_client.cpp: wolfTLSv1_2_client_method with
     * SSL_VERIFY_NONE): RPCS3 itself doesn't check RPCN's certificate, so neither can Fuse, and
     * only the stretched password and the email go over it, as from RPCS3.
     */
    private fun tls(host: String, port: Int): Socket {
        val context = SSLContext.getInstance("TLSv1.2")
        context.init(null, arrayOf<TrustManager>(AsRpcs3), SecureRandom())
        val raw = Socket()
        raw.connect(InetSocketAddress(host, port), TIMEOUT_MS)
        val socket = context.socketFactory.createSocket(raw, host, port, true) as SSLSocket
        socket.enabledProtocols = arrayOf("TLSv1.2")
        socket.startHandshake()
        return socket
    }

    /** RPCS3's own trust for RPCN: none checked (see [tls]). Used for RPCN alone. */
    private object AsRpcs3 : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private const val REQUEST_ID = 1L
    private const val TIMEOUT_MS = 15_000
    private const val MAX_PACKET = 1L shl 20
}
