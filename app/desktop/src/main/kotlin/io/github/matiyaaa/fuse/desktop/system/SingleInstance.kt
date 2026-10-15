package io.github.matiyaaa.fuse.desktop.system

import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.StandardOpenOption
import java.security.SecureRandom

/**
 * One Fuse at a time for each person. The first to start holds a lock in Fuse's data folder and
 * listens on the loopback for the ones after it; what is written beside the lock (its port, a
 * secret only this person can read, its process and the Steam entry it came from) is how a later
 * start finds it.
 *
 * A later start either hands over to it ([Decision.Handed]: the one already running comes to the
 * front, and the new one leaves) or takes its place ([Decision.Replaced]: the one running is asked
 * to close, and is ended if it doesn't). It takes its place when it is Fuse starting itself again
 * ([REPLACE]), or when Steam started it for another entry or the running Fuse wasn't Steam's: Steam
 * follows the Fuse it started, so in Game Mode that one has to be the one on screen.
 */
class SingleInstance(private val folder: File) {
    sealed interface Decision {
        /** No other Fuse: this one holds the lock. */
        data object First : Decision

        /** Another Fuse was asked to come to the front; this start leaves. */
        data object Handed : Decision

        /** Another Fuse was closed (or ended) and this one holds the lock now. */
        data class Replaced(val ended: Boolean) : Decision

        /** The lock couldn't be held (an odd file system, or a Fuse that wouldn't close): this one carries on alone. */
        data class Unguarded(val why: String) : Decision
    }

    /**
     * What this Fuse does when a later start asks it to come to the front, or to close. [onShow]
     * says whether it could: false (no window, or a window that doesn't answer) has the later start
     * take its place instead of leaving with nothing on screen.
     */
    @Volatile var onShow: () -> Boolean = { false }
    @Volatile var onQuit: () -> Unit = {}

    private var channel: FileChannel? = null
    private var lock: FileLock? = null
    private var server: ServerSocket? = null

    /**
     * Takes the lock or deals with the Fuse holding it. [steamGameId] is the Steam entry this start
     * came from (Steam's `SteamGameId`), [replace] whether it is Fuse starting itself again.
     */
    fun claim(steamGameId: String?, replace: Boolean): Decision {
        folder.mkdirs()
        val lockFile = File(folder, LOCK)
        val ch = try {
            FileChannel.open(lockFile.toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
        } catch (e: Exception) {
            return Decision.Unguarded("the lock file couldn't be opened (${e.message})")
        }
        val held = tryLock(ch)
        if (held != null) return hold(ch, held, steamGameId, Decision.First)
        val other = Holder.read(File(folder, INFO))
        val takeOver = replace || shouldReplace(steamGameId, other?.steamGameId)
        if (!takeOver && other != null && ask(other, SHOW)) {
            ch.close()
            return Decision.Handed
        }
        // Its place is taken: asked to close, then ended if it is still there.
        if (other != null) ask(other, QUIT)
        tryLock(ch, waitMs = QUIT_WAIT_MS)?.let { return hold(ch, it, steamGameId, Decision.Replaced(ended = false)) }
        val ended = other?.pid?.takeIf { it != ProcessHandle.current().pid() }?.let { pid ->
            runCatching { ProcessHandle.of(pid).map { it.destroyForcibly() }.orElse(false) }.getOrDefault(false)
        } ?: false
        tryLock(ch, waitMs = END_WAIT_MS)?.let { return hold(ch, it, steamGameId, Decision.Replaced(ended = ended)) }
        ch.close()
        return Decision.Unguarded("another Fuse kept the lock")
    }

    private fun tryLock(ch: FileChannel, waitMs: Long = 0): FileLock? {
        val until = System.currentTimeMillis() + waitMs
        while (true) {
            val got = try {
                ch.tryLock()
            } catch (e: java.nio.channels.OverlappingFileLockException) {
                null
            } catch (e: java.io.IOException) {
                null
            }
            if (got != null || System.currentTimeMillis() >= until) return got
            Thread.sleep(POLL_MS)
        }
    }

    private fun hold(ch: FileChannel, held: FileLock, steamGameId: String?, decision: Decision): Decision {
        channel = ch
        lock = held
        val secret = ByteArray(16).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }
        val socket = try {
            ServerSocket().apply { bind(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 4) }
        } catch (e: Exception) {
            // Held but not reachable: later starts still find the lock taken and the process to end.
            null
        }
        server = socket
        Holder(socket?.localPort ?: 0, secret, ProcessHandle.current().pid(), steamGameId).write(File(folder, INFO))
        socket?.let { listen(it, secret) }
        return decision
    }

    private fun listen(socket: ServerSocket, secret: String) {
        Thread({
            while (!socket.isClosed) {
                val client = try {
                    socket.accept()
                } catch (e: Exception) {
                    return@Thread
                }
                runCatching {
                    client.use { c ->
                        c.soTimeout = ANSWER_MS
                        val line = BufferedReader(InputStreamReader(c.getInputStream(), Charsets.UTF_8)).readLine().orEmpty()
                        val (key, command) = line.substringBefore(' ') to line.substringAfter(' ', "")
                        if (key != secret) return@use
                        // Coming to the front is promised only once done: a Fuse whose window is
                        // gone or stuck says nothing, and the start that asked takes its place.
                        if (command == SHOW && !onShow()) return@use
                        c.getOutputStream().write("ok\n".toByteArray())
                        c.getOutputStream().flush()
                        if (command == QUIT) onQuit()
                    }
                }
            }
        }, "fuse-single-instance").apply {
            isDaemon = true
            start()
        }
    }

    /** Lets go of the lock, as Fuse closes, so a start right after doesn't wait. */
    fun release() {
        runCatching { server?.close() }
        runCatching { lock?.release() }
        runCatching { channel?.close() }
        server = null
        lock = null
        channel = null
    }

    /** What the Fuse holding the lock left beside it. */
    internal data class Holder(val port: Int, val secret: String, val pid: Long?, val steamGameId: String?) {
        fun write(file: File) {
            val tmp = File(file.parentFile, ".${file.name}.tmp")
            tmp.writeText("port=$port\nsecret=$secret\npid=${pid ?: ""}\nsteam=${steamGameId.orEmpty()}\n")
            // The secret is this person's alone.
            tmp.setReadable(false, false)
            tmp.setReadable(true, true)
            tmp.setWritable(false, false)
            tmp.setWritable(true, true)
            if (!tmp.renameTo(file)) {
                file.writeText(tmp.readText())
                tmp.delete()
            }
        }

        companion object {
            fun read(file: File): Holder? {
                val values = runCatching { file.readLines() }.getOrNull()?.mapNotNull { line ->
                    line.indexOf('=').takeIf { it > 0 }?.let { line.substring(0, it) to line.substring(it + 1) }
                }?.toMap() ?: return null
                return Holder(
                    port = values["port"]?.toIntOrNull() ?: 0,
                    secret = values["secret"] ?: return null,
                    pid = values["pid"]?.toLongOrNull(),
                    steamGameId = values["steam"]?.takeIf { it.isNotBlank() },
                )
            }
        }
    }

    companion object {
        /** Passed by a Fuse that starts itself again, so the new one doesn't hand back to the old. */
        const val REPLACE = "--replace"

        /**
         * Whether a start from [mine] takes the place of a Fuse started from [theirs] (Steam entry
         * ids, or null outside Steam). A start from Steam does, unless the one running came from the
         * same entry (Steam started it twice: the first stays, and Steam keeps following it).
         */
        fun shouldReplace(mine: String?, theirs: String?): Boolean = mine != null && mine != theirs

        /** Sends [command] to [holder]; whether it answered. */
        internal fun ask(holder: Holder, command: String): Boolean {
            if (holder.port <= 0) return false
            return try {
                Socket().use { s ->
                    s.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), holder.port), CONNECT_MS)
                    s.soTimeout = ANSWER_MS
                    s.getOutputStream().write("${holder.secret} $command\n".toByteArray())
                    s.getOutputStream().flush()
                    BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8)).readLine() == "ok"
                }
            } catch (e: Exception) {
                false
            }
        }

        internal const val SHOW = "show"
        internal const val QUIT = "quit"
        private const val LOCK = "instance.lock"
        private const val INFO = "instance.info"
        private const val CONNECT_MS = 1_000
        private const val ANSWER_MS = 2_000
        private const val POLL_MS = 100L
        private const val QUIT_WAIT_MS = 6_000L
        private const val END_WAIT_MS = 3_000L
    }
}
