package io.github.matiyaaa.fuse.transfer

import io.github.matiyaaa.fuse.model.StorageVolume
import io.github.matiyaaa.fuse.model.VolumeKind
import io.github.matiyaaa.fuse.model.VolumeRef
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TransferTest {
    private val root = Files.createTempDirectory("fuse-transfer").toFile()
    private val scopes = ArrayList<CoroutineScope>()

    @AfterTest
    fun cleanUp() {
        scopes.forEach { it.cancel() }
        root.deleteRecursively()
    }

    private fun item(id: String, direction: TransferDirection = TransferDirection.DOWNLOAD, status: TransferStatus = TransferStatus.QUEUED, order: Long = 0) =
        TransferItem(id = id, key = "k:$id", source = "test", direction = direction, kind = TransferKind.GAME, title = id, status = status, order = order)

    @Test
    fun `downloads and uploads have their own limits`() {
        val items = (1..7).map { item("d$it", order = it.toLong()) } + (1..7).map { item("u$it", TransferDirection.UPLOAD, order = it.toLong()) }
        val next = TransferScheduler.next(items, TransferSettings(maxDownloads = 5, maxUploads = 5), 0)
        assertEquals(listOf("d1", "d2", "d3", "d4", "d5", "u1", "u2", "u3", "u4", "u5"), next)
        // Never more than five, whatever a setting says.
        assertEquals(5, TransferScheduler.next(items, TransferSettings(maxDownloads = 50, maxUploads = 0), 0).count { it.startsWith("d") })
        assertEquals(1, TransferScheduler.next(items, TransferSettings(maxDownloads = 50, maxUploads = 0), 0).count { it.startsWith("u") })
    }

    @Test
    fun `running transfers use up the room and paused ones never start`() {
        val items = listOf(
            item("a", status = TransferStatus.ACTIVE, order = 1),
            item("b", status = TransferStatus.PAUSED, order = 2),
            item("c", order = 3),
            item("d", order = 4),
        )
        assertEquals(listOf("c"), TransferScheduler.next(items, TransferSettings(maxDownloads = 2), 0))
    }

    @Test
    fun `a waiting transfer starts again once its time comes, unless held by play`() {
        val net = item("n", status = TransferStatus.WAITING).copy(waiting = WaitReason.NETWORK, retryAt = 1_000)
        val play = item("p", status = TransferStatus.WAITING).copy(waiting = WaitReason.PLAYING)
        assertEquals(emptyList(), TransferScheduler.next(listOf(net, play), TransferSettings(), 500))
        assertEquals(listOf("n"), TransferScheduler.next(listOf(net, play), TransferSettings(), 1_000))
        // A held direction starts nothing.
        assertEquals(emptyList(), TransferScheduler.next(listOf(item("q")), TransferSettings(), 0) { WaitReason.PLAYING })
    }

    @Test
    fun `moving in the queue`() {
        val items = listOf(item("a", order = 1), item("b", order = 2), item("c", order = 3), item("x", TransferDirection.UPLOAD, order = 4))
        fun after(orders: Map<String, Long>) = items.map { t -> orders[t.id]?.let { t.copy(order = it) } ?: t }.filter { !it.upload }.sortedBy { it.order }.map { it.id }
        assertEquals(listOf("a", "c", "b"), after(TransferScheduler.move(items, "c", -1)))
        assertEquals(listOf("c", "a", "b"), after(TransferScheduler.move(items, "c", toTop = true)))
        assertEquals(listOf("b", "a", "c"), after(TransferScheduler.move(items, "a", 1)))
        assertTrue(TransferScheduler.move(items, "a", -1).isEmpty())
    }

    @Test
    fun `speed is smoothed and time left follows it`() {
        val m = SpeedMeter()
        m.sample(0, 0)
        val s1 = m.sample(1_000_000, 1_000)
        assertEquals(1_000_000, s1)
        // A one second stall halves the estimate at most, never to zero.
        val s2 = m.sample(1_000_000, 2_000)
        assertTrue(s2 in 1..999_999, "speed $s2")
        assertEquals(10L, SpeedMeter.eta(10_000_000, 1_000_000))
        assertNull(SpeedMeter.eta(100, 0))
    }

    @Test
    fun `a drive back under another path is followed by its id`() {
        val sd = StorageVolume("uuid:AAAA", "SD card", listOf("/run/media/me/SD"), VolumeKind.SD_CARD, removable = true)
        val place = TransferPlaces.of("/run/media/me/SD/roms/psx/Game.chd", listOf(sd), 0)
        assertEquals("roms/psx/Game.chd", place.volume?.relativePath)
        val moved = sd.copy(mountPaths = listOf("/media/SD"))
        assertEquals("/media/SD/roms/psx/Game.chd", TransferPlaces.resolve(place, listOf(moved)))
        // Gone: never a path that happens to exist somewhere else.
        val other = StorageVolume("uuid:BBBB", "Other", listOf("/run/media/me/SD"))
        assertNull(TransferPlaces.resolve(place, listOf(other)))
    }

    // ------------------------------------------------------------------ the engine, end to end

    private val payload = ByteArray(300_000) { (it * 31 % 251).toByte() }

    /** A server that serves [payload] with ranges, and can be told to cut a response short. */
    private inner class Server {
        val requests = AtomicInteger()
        @Volatile var cutAfter: Int? = null
        @Volatile var status: HttpStatusCode? = null
        @Volatile var ignoreRange = false
        val ranges = ArrayList<String?>()

        val client = HttpClient(MockEngine { req ->
            requests.incrementAndGet()
            status?.let { return@MockEngine respond("", it) }
            val range = req.headers[HttpHeaders.Range]
            synchronized(ranges) { ranges += range }
            val from = if (ignoreRange) 0 else range?.removePrefix("bytes=")?.substringBefore('-')?.toInt() ?: 0
            var body = payload.copyOfRange(from, payload.size)
            val cut = cutAfter
            if (cut != null) {
                cutAfter = null
                body = body.copyOfRange(0, minOf(cut, body.size))
                // Says the full length but sends less: the connection "drops".
                return@MockEngine respond(
                    body,
                    if (range != null && !ignoreRange) HttpStatusCode.PartialContent else HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentRange to listOf("bytes $from-${payload.size - 1}/${payload.size}")),
                )
            }
            respond(
                body,
                if (range != null && !ignoreRange) HttpStatusCode.PartialContent else HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentLength to listOf(body.size.toString()), HttpHeaders.ContentRange to listOf("bytes $from-${payload.size - 1}/${payload.size}")),
            )
        })
    }

    /** Downloads [payload] to the place in its payload, checked by size and SHA-256. */
    private inner class FileHandler(val server: Server, val volumes: () -> List<StorageVolume> = { emptyList() }) : TransferHandler {
        override val source = "test"
        val done = AtomicInteger()

        override suspend fun run(item: TransferItem, io: TransferIo) {
            val dest = File(io.resolve(item.place!!))
            val part = TransferFiles.partFor(dest)
            RangedDownload.fetch(server.client, { "https://server/file" }, part, payload.size.toLong(), io)
            io.phase(TransferPhase.VERIFYING)
            TransferFiles.verify(part, payload.size.toLong(), ExpectedHash("sha256", sha256(payload)))
            io.phase(TransferPhase.PLACING)
            TransferFiles.place(part, dest)
        }

        override suspend fun discard(item: TransferItem, io: TransferIo) {
            item.place?.let { TransferFiles.partFor(File(it.path)).delete() }
        }

        override suspend fun done(item: TransferItem) {
            done.incrementAndGet()
        }
    }

    private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private fun manager(dir: File = File(root, "state"), volumes: () -> List<StorageVolume> = { emptyList() }): TransferManager {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scopes += scope
        return TransferManager(dir, scope, volumes = { volumes() }).also { it.start() }
    }

    private fun download(name: String, place: TransferPlace = TransferPlace(File(root, "games/$name").path)) =
        TransferItem(id = "", key = "file:$name", source = "test", direction = TransferDirection.DOWNLOAD, kind = TransferKind.GAME, title = name, place = place, totalBytes = payload.size.toLong())

    private suspend fun TransferManager.awaitStatus(id: String, status: TransferStatus, timeoutMs: Long = 10_000): TransferItem {
        val until = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < until) {
            items.value.firstOrNull { it.id == id && it.status == status }?.let { return it }
            delay(10)
        }
        error("$id never reached $status: ${items.value.firstOrNull { it.id == id }}")
    }

    @Test
    fun `a download is checked, then put in place, and nothing half-done is left`() = runBlocking<Unit> {
        val server = Server()
        val m = manager()
        val handler = FileHandler(server)
        m.register(handler)
        val id = m.enqueue(download("Game.bin"))
        m.awaitStatus(id, TransferStatus.DONE)
        assertContentEquals(payload, File(root, "games/Game.bin").readBytes())
        assertFalse(File(root, "games/.Game.bin.fuse-part").exists())
        delay(50)
        assertEquals(1, handler.done.get())
    }

    /**
     * On a computer Fuse hands the transfers the UI's own scope. A transfer that reads a big file
     * (an upload hashing a disc image) must leave that thread free, or Fuse freezes until it ends.
     */
    @Test
    fun `a transfer never runs on the thread that draws Fuse`() = runBlocking<Unit> {
        val ui = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "fuse-ui") }
        val scope = CoroutineScope(SupervisorJob() + ui.asCoroutineDispatcher())
        scopes += scope
        val ranOn = java.util.concurrent.atomic.AtomicReference<String>()
        val release = java.util.concurrent.CountDownLatch(1)
        val m = TransferManager(File(root, "ui-state"), scope, volumes = { emptyList() }).also { it.start() }
        m.register(object : TransferHandler {
            override val source = "test"
            override suspend fun run(item: TransferItem, io: TransferIo) {
                ranOn.set(Thread.currentThread().name)
                // Blocking work, like hashing a file.
                release.await()
            }
        })
        val id = m.enqueue(download("Big.iso"))
        val until = System.currentTimeMillis() + 10_000
        while (ranOn.get() == null && System.currentTimeMillis() < until) delay(10)
        // While the transfer blocks, the UI thread still answers straight away.
        val reply = kotlinx.coroutines.CompletableDeferred<Boolean>()
        scope.launch { reply.complete(true) }
        val answered = kotlinx.coroutines.withTimeoutOrNull(1_000) { reply.await() }
        release.countDown()
        assertEquals(true, answered, "The UI thread was blocked by the transfer")
        assertNotNull(ranOn.get())
        assertFalse(ranOn.get() == "fuse-ui")
        m.awaitStatus(id, TransferStatus.DONE)
        ui.shutdown()
    }

    @Test
    fun `the same thing queued twice is one transfer`() = runBlocking<Unit> {
        val m = manager()
        val a = m.enqueue(download("One.bin"))
        val b = m.enqueue(download("One.bin"))
        assertEquals(a, b)
        assertEquals(1, m.items.value.size)
    }

    @Test
    fun `a dropped connection carries on from where it stopped`() = runBlocking<Unit> {
        val server = Server().apply { cutAfter = 100_000 }
        val m = manager()
        m.register(FileHandler(server))
        val id = m.enqueue(download("Resume.bin"))
        // It waits on the network a moment, then carries on with a range from 100,000.
        m.awaitStatus(id, TransferStatus.DONE, timeoutMs = 20_000)
        assertContentEquals(payload, File(root, "games/Resume.bin").readBytes())
        assertTrue(server.ranges.contains("bytes=100000-"), "ranges asked: ${server.ranges}")
    }

    @Test
    fun `a server that ignores the range starts the file over rather than mixing it`() = runBlocking<Unit> {
        val server = Server().apply { cutAfter = 50_000 }
        val m = manager()
        m.register(FileHandler(server))
        val id = m.enqueue(download("Whole.bin"))
        server.ignoreRange = true
        m.awaitStatus(id, TransferStatus.DONE, timeoutMs = 20_000)
        assertContentEquals(payload, File(root, "games/Whole.bin").readBytes())
    }

    @Test
    fun `refused sign-in fails without retrying`() = runBlocking<Unit> {
        val server = Server().apply { status = HttpStatusCode.Unauthorized }
        val m = manager()
        m.register(FileHandler(server))
        val id = m.enqueue(download("Denied.bin"))
        val failed = m.awaitStatus(id, TransferStatus.FAILED)
        assertFalse(failed.retryable)
        assertEquals(1, server.requests.get())
    }

    /**
     * Starting the queue sends what was left running last time back to it. A transfer added right
     * after the start may already be running when that happens: it must never be queued again and
     * run a second time. Many times over, since it is a matter of timing.
     */
    @Test
    fun `a transfer added as the queue starts runs once`() = runBlocking<Unit> {
        repeat(60) { n ->
            val server = Server().apply { status = HttpStatusCode.Unauthorized }
            val m = manager(File(root, "state$n"))
            m.register(FileHandler(server))
            val id = m.enqueue(download("Once$n.bin"))
            m.awaitStatus(id, TransferStatus.FAILED)
            delay(30)
            assertEquals(1, server.requests.get(), "run $n asked the server ${server.requests.get()} times")
        }
    }

    @Test
    fun `a server error waits and tries again`() = runBlocking<Unit> {
        val server = Server().apply { status = HttpStatusCode.InternalServerError }
        val m = manager()
        m.register(FileHandler(server))
        val id = m.enqueue(download("Later.bin"))
        val waiting = m.awaitStatus(id, TransferStatus.WAITING)
        assertEquals(WaitReason.NETWORK, waiting.waiting)
        server.status = null
        m.awaitStatus(id, TransferStatus.DONE, timeoutMs = 20_000)
    }

    @Test
    fun `a missing drive holds the transfer and names the drive`() = runBlocking<Unit> {
        val sd = StorageVolume("uuid:SD1", "SD card", listOf(File(root, "sd").path), VolumeKind.SD_CARD, removable = true)
        File(root, "sd").mkdirs()
        var mounted = emptyList<StorageVolume>()
        val other = StorageVolume("uuid:INT", "Internal", listOf(File(root, "int").path), VolumeKind.INTERNAL)
        mounted = listOf(other)
        val m = manager(volumes = { mounted })
        m.register(FileHandler(Server()))
        val place = TransferPlace(File(root, "sd/roms/Card.bin").path, VolumeRef("uuid:SD1", "SD card", VolumeKind.SD_CARD, true, "roms/Card.bin"))
        val id = m.enqueue(download("Card.bin", place))
        val waiting = m.awaitStatus(id, TransferStatus.WAITING)
        assertEquals(WaitReason.DRIVE, waiting.waiting)
        assertEquals("SD card", waiting.waitingFor)
        // Back, under another path.
        File(root, "sd2").mkdirs()
        mounted = listOf(other, sd.copy(mountPaths = listOf(File(root, "sd2").path)))
        m.drivesChanged()
        m.awaitStatus(id, TransferStatus.DONE)
        assertContentEquals(payload, File(root, "sd2/roms/Card.bin").readBytes())
    }

    @Test
    fun `the queue survives a restart, and a running transfer carries on`() = runBlocking<Unit> {
        val dir = File(root, "persist")
        val first = manager(dir)
        // No handler yet: it stays queued, as after a crash before anything ran.
        val id = first.enqueue(download("Later.bin"))
        val again = manager(dir)
        assertNotNull(again.items.value.firstOrNull { it.id == id })
        again.register(FileHandler(Server()))
        again.awaitStatus(id, TransferStatus.DONE)
    }

    @Test
    fun `pause keeps what was done and resume finishes it`() = runBlocking<Unit> {
        val server = Server()
        val m = manager()
        // Slow enough to pause mid-way.
        m.configure(TransferSettings(bandwidthLimit = 400_000))
        m.register(FileHandler(server))
        val id = m.enqueue(download("Paused.bin"))
        m.awaitStatus(id, TransferStatus.ACTIVE)
        delay(300)
        m.pause(id).join()
        val paused = m.awaitStatus(id, TransferStatus.PAUSED)
        val part = File(root, "games/.Paused.bin.fuse-part")
        assertTrue(part.exists())
        assertTrue(paused.doneBytes > 0 || part.length() > 0)
        m.configure(TransferSettings())
        m.resume(id).join()
        m.awaitStatus(id, TransferStatus.DONE)
        assertContentEquals(payload, File(root, "games/Paused.bin").readBytes())
    }

    @Test
    fun `cancel removes the partial file`() = runBlocking<Unit> {
        val m = manager()
        m.configure(TransferSettings(bandwidthLimit = 200_000))
        m.register(FileHandler(Server()))
        val id = m.enqueue(download("Cancelled.bin"))
        m.awaitStatus(id, TransferStatus.ACTIVE)
        delay(300)
        m.cancel(id).join()
        m.awaitStatus(id, TransferStatus.CANCELLED)
        assertFalse(File(root, "games/.Cancelled.bin.fuse-part").exists())
        assertFalse(File(root, "games/Cancelled.bin").exists())
    }

    @Test
    fun `while playing, a paused direction waits and comes back by itself`() = runBlocking<Unit> {
        val m = manager()
        m.configure(TransferSettings(downloadsWhilePlaying = WhilePlaying.PAUSE))
        m.conditions(TransferConditions(playing = true))
        delay(50)
        m.register(FileHandler(Server()))
        val id = m.enqueue(download("Play.bin"))
        delay(200)
        val held = m.items.value.first { it.id == id }
        assertTrue(held.status == TransferStatus.QUEUED || (held.status == TransferStatus.WAITING && held.waiting == WaitReason.PLAYING), "$held")
        assertFalse(File(root, "games/Play.bin").exists())
        m.conditions(TransferConditions(playing = false))
        m.awaitStatus(id, TransferStatus.DONE)
    }

    @Test
    fun `many at once respect the limit`() = runBlocking<Unit> {
        val m = manager()
        m.configure(TransferSettings(maxDownloads = 2, bandwidthLimit = 2_000_000))
        m.register(FileHandler(Server()))
        val ids = (1..6).map { m.enqueue(download("Many$it.bin")) }
        var most = 0
        val until = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < until && m.items.value.any { it.status != TransferStatus.DONE }) {
            most = maxOf(most, m.items.value.count { it.status == TransferStatus.ACTIVE })
            delay(5)
        }
        assertTrue(most <= 2, "at most two at once, saw $most")
        ids.forEach { assertEquals(TransferStatus.DONE, m.items.value.first { t -> t.id == it }.status) }
    }

    @Test
    fun `a wrong size is never kept`() {
        val f = File(root, "x.part").apply { writeBytes(ByteArray(10)) }
        val e = runCatching { TransferFiles.verify(f, 11, null) }.exceptionOrNull()
        assertTrue(e is TransferFailure)
        assertFalse(f.exists())
    }

    @Test
    fun `placing never replaces a file the person already has`() {
        val existing = File(root, "bios/scph1001.bin").apply { parentFile.mkdirs(); writeText("mine") }
        val incoming = File(root, "in.part").apply { writeText("server") }
        val e = runCatching { TransferFiles.place(incoming, existing) }.exceptionOrNull()
        assertTrue(e is TransferFailure)
        assertEquals("mine", existing.readText())
    }
}
