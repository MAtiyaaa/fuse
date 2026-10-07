package io.github.matiyaaa.fuse.reach

import io.github.matiyaaa.fuse.romm.RommClient
import io.github.matiyaaa.fuse.sync.OpenTicket
import io.github.matiyaaa.fuse.sync.PeerBytes
import io.github.matiyaaa.fuse.sync.PeerEndpoint
import io.github.matiyaaa.fuse.sync.PeerTicket
import io.github.matiyaaa.fuse.transfer.DeviceAway
import io.github.matiyaaa.fuse.transfer.TransferDirection
import io.github.matiyaaa.fuse.transfer.TransferInterrupted
import io.github.matiyaaa.fuse.transfer.TransferIo
import io.github.matiyaaa.fuse.transfer.TransferItem
import io.github.matiyaaa.fuse.transfer.TransferKind
import io.github.matiyaaa.fuse.transfer.TransferPhase
import io.github.matiyaaa.fuse.transfer.TransferPlace
import io.ktor.client.HttpClient
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * A game brought here from wherever is best: the order sources are tried in, carrying on from
 * another source with the same bytes, wrong bytes refused, nothing a scan could see until the
 * game is whole, and a game whose only sources are away waiting for them.
 */
class ReachHandlerTest {
    private lateinit var root: File

    @BeforeTest fun start() { root = Files.createTempDirectory("reach").toFile() }

    @AfterTest fun stop() { root.deleteRecursively() }

    private fun sha1(b: ByteArray) = MessageDigest.getInstance("SHA-1").digest(b).joinToString("") { "%02x".format(it) }

    /** A device with files, which can stop part way, send the wrong bytes, or be away. */
    private class FakePeer(val files: Map<String, ByteArray>, val local: Boolean = true) {
        var online = true
        var dropAfter: Long? = null
        var corrupt = false
        val asked = ArrayList<Long>()
    }

    private inner class Host(val devices: Map<String, FakePeer>, override val relay: Boolean = true, val hashLater: Map<String, String> = emptyMap()) : ReachTransferHost {
        val landed = ArrayList<List<String>>()
        override val http: HttpClient get() = error("no RomM in these tests")
        override fun romm(server: String): RommClient? = null
        override fun online(device: String) = devices[device]?.online == true
        override suspend fun hashesFrom(source: String, game: String, path: String): Pair<String?, String?>? = hashLater[path]?.let { it to null }
        override suspend fun landed(job: ReachJob, item: TransferItem, paths: List<String>) { landed += paths }
        override val peers: PeerBytes = object : PeerBytes {
            override suspend fun openTicket(source: String, game: String, files: List<String>) =
                OpenTicket(PeerTicket("t", "me", source, game, files, Long.MAX_VALUE, PeerEndpoint(listOf("x"), 1)), "key")

            override suspend fun reachable(ticket: PeerTicket) = this@Host.devices.getValue(ticket.source).local

            override suspend fun fetch(open: OpenTicket, file: String, offset: Long, length: Long, direct: Boolean, write: suspend (ByteArray, Int, Long?) -> Unit): Long {
                val p = this@Host.devices.getValue(open.ticket.source)
                p.asked += offset
                val all = p.files.getValue(file)
                val end = minOf(all.size.toLong(), offset + length)
                var at = offset
                while (at < end) {
                    val stop = p.dropAfter
                    if (stop != null && at >= stop) throw IOException("dropped")
                    val n = minOf(4096L, end - at, (stop ?: Long.MAX_VALUE) - at).toInt().coerceAtLeast(1)
                    val chunk = all.copyOfRange(at.toInt(), at.toInt() + n)
                    if (p.corrupt) chunk[0] = (chunk[0] + 1).toByte()
                    write(chunk, n, all.size.toLong())
                    at += n
                }
                return end - offset
            }
        }
    }

    private class Io(val work: File) : TransferIo {
        var notes = ""
        val phases = ArrayList<TransferPhase>()
        override val workDir: String get() = work.path
        override suspend fun resolve(place: TransferPlace): String = place.path
        override fun progress(done: Long, total: Long?) = Unit
        override fun phase(phase: TransferPhase) { phases += phase }
        override suspend fun note(payload: String) { notes = payload }
        override suspend fun throttle(bytes: Int) = Unit
    }

    private fun item(job: ReachJob, place: File) = TransferItem(
        id = "1", key = "reach:${job.game}", source = REACH_SOURCE, direction = TransferDirection.DOWNLOAD, kind = TransferKind.GAME,
        title = job.title, place = TransferPlace(place.path), payload = job.encode(),
    )

    private val disc = Random(1).nextBytes(200_000)
    private val cue = "FILE \"Crash.bin\" BINARY".toByteArray()

    private fun job(sources: List<ReachSource>, folder: Boolean = false, hashed: Boolean = true) = ReachJob(
        game = "g:crash", title = "Crash", platform = "psx", folder = folder,
        files = listOf(
            ReachFile("Crash.cue", cue.size.toLong(), sha1 = if (hashed) sha1(cue) else null),
            ReachFile("Crash.bin", disc.size.toLong(), sha1 = if (hashed) sha1(disc) else null),
        ),
        sources = sources,
    )

    private val files = mapOf("Crash.cue" to cue, "Crash.bin" to disc)
    private val pc = ReachSource(ReachSource.PEER, "pc", "Gaming PC")
    private val deck = ReachSource(ReachSource.PEER, "deck", "Deck")

    @Test
    fun `sources go in order, a device at home, RomM at home, a device through the host, RomM from outside`() {
        val s = { k: String -> ReachSource(ReachSource.PEER, k) }
        val order = SourceRanking.order(
            listOf(
                Candidate(s("romm-away"), Reach.ROMM_REMOTE, 9_000),
                Candidate(s("relay"), Reach.PEER_RELAY),
                Candidate(s("off"), Reach.NONE, 99_999),
                Candidate(s("romm-home"), Reach.ROMM_LOCAL),
                Candidate(s("slow-peer"), Reach.PEER_LOCAL, 10),
                Candidate(s("fast-peer"), Reach.PEER_LOCAL, 50_000),
            ),
        ).map { it.source.id }
        assertEquals(listOf("fast-peer", "slow-peer", "romm-home", "relay", "romm-away"), order)
    }

    @Test
    fun `a dropped device gives way to another with the same bytes, which carries on the same part`() = runBlocking {
        val first = FakePeer(files).apply { dropAfter = 120_000 }
        val second = FakePeer(files, local = false)
        val host = Host(mapOf("pc" to first, "deck" to second))
        val dest = File(root, "roms/psx").apply { mkdirs() }
        val j = job(listOf(pc, deck))
        ReachHandler(host).run(item(j, dest), Io(root))
        assertContentEquals(disc, File(dest, "Crash.bin").readBytes())
        assertContentEquals(cue, File(dest, "Crash.cue").readBytes())
        assertTrue(120_000L in second.asked, "the Deck carried on from where the PC stopped, not from the start")
        // The file that names the game is put in place last.
        assertTrue(host.landed.single().last().endsWith("Crash.cue"))
    }

    @Test
    fun `wrong bytes are refused and the next source is used`() = runBlocking {
        val bad = FakePeer(files).apply { corrupt = true }
        val good = FakePeer(files, local = false)
        val host = Host(mapOf("pc" to bad, "deck" to good))
        val dest = File(root, "roms/psx").apply { mkdirs() }
        ReachHandler(host).run(item(job(listOf(pc, deck)), dest), Io(root))
        assertContentEquals(disc, File(dest, "Crash.bin").readBytes())
    }

    @Test
    fun `nothing a scan could see is there until the whole game is`() = runBlocking {
        val drops = FakePeer(files).apply { dropAfter = 50_000 }
        val host = Host(mapOf("pc" to drops))
        val parent = File(root, "roms/ps2").apply { mkdirs() }
        val dest = File(parent, "Crash")
        val j = job(listOf(pc), folder = true)
        assertFailsWith<TransferInterrupted> { ReachHandler(host).run(item(j, dest), Io(root)) }
        assertTrue(parent.list()!!.all { it.startsWith(".") }, "only hidden partial files: ${parent.list()!!.toList()}")
        assertTrue(!dest.exists())
        // Back again: the game appears whole, in one step.
        drops.dropAfter = null
        ReachHandler(host).run(item(j, dest), Io(root))
        assertContentEquals(disc, File(dest, "Crash.bin").readBytes())
        assertTrue(parent.list()!!.toList() == listOf("Crash"))
    }

    @Test
    fun `a game whose only sources are away waits for them`() = runBlocking {
        val off = FakePeer(files).apply { online = false }
        val host = Host(mapOf("pc" to off))
        val dest = File(root, "roms/psx").apply { mkdirs() }
        val e = assertFailsWith<DeviceAway> { ReachHandler(host).run(item(job(listOf(pc)), dest), Io(root)) }
        assertEquals("Gaming PC", e.label)
    }

    @Test
    fun `a file not yet read on the other device is checked by the hashes it gives once it has`() = runBlocking {
        val host = Host(mapOf("pc" to FakePeer(files)), hashLater = mapOf("Crash.bin" to sha1(disc), "Crash.cue" to sha1(cue)))
        val dest = File(root, "roms/psx").apply { mkdirs() }
        ReachHandler(host).run(item(job(listOf(pc), hashed = false), dest), Io(root))
        assertContentEquals(disc, File(dest, "Crash.bin").readBytes())

        // Hashes that disagree: nothing is kept.
        val wrong = Host(mapOf("pc" to FakePeer(files)), hashLater = mapOf("Crash.bin" to "00", "Crash.cue" to sha1(cue)))
        val other = File(root, "roms/other").apply { mkdirs() }
        assertFailsWith<TransferInterrupted> { ReachHandler(wrong).run(item(job(listOf(pc), hashed = false), other), Io(root)) }
        assertTrue(!File(other, "Crash.bin").exists())
    }

    @Test
    fun `cancelling leaves nothing behind`() = runBlocking {
        val drops = FakePeer(files).apply { dropAfter = 50_000 }
        val host = Host(mapOf("pc" to drops))
        val parent = File(root, "roms/ps2").apply { mkdirs() }
        val dest = File(parent, "Crash")
        val j = job(listOf(pc), folder = true)
        val io = Io(root)
        assertFailsWith<TransferInterrupted> { ReachHandler(host).run(item(j, dest), io) }
        ReachHandler(host).discard(item(j, dest), io)
        assertTrue(parent.list()!!.isEmpty(), parent.list()!!.toList().toString())
    }
}
