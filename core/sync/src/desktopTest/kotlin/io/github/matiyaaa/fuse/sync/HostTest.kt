package io.github.matiyaaa.fuse.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.file.Files

class HostTest {
    @Test
    fun theHostAnswersWhoIsThereAndNothingElse() {
        val port = DatagramSocket(0).use { it.localPort }
        val hello = HostHello("h1", "Gaming PC", port = 47311)
        val responder = Discovery.answer({ hello }, port)
        try {
            DatagramSocket().use { s ->
                s.soTimeout = 2_000
                val q = Discovery.QUESTION.toByteArray()
                s.send(DatagramPacket(q, q.size, InetAddress.getLoopbackAddress(), port))
                val buf = ByteArray(1024)
                val p = DatagramPacket(buf, buf.size)
                s.receive(p)
                val answer = Json.decodeFromString(HostHello.serializer(), String(p.data, 0, p.length))
                assertEquals(hello, answer.copy(addresses = emptyList()))
                // It says every address it has on the home network, so a device can find one that answers.
                assertEquals(LanAddresses.list(), answer.addresses)
                // Anything else gets no answer.
                val other = "hello?".toByteArray()
                s.send(DatagramPacket(other, other.size, InetAddress.getLoopbackAddress(), port))
                s.soTimeout = 300
                assertTrue(runCatching { s.receive(DatagramPacket(buf, buf.size)) }.isFailure)
            }
        } finally {
            responder.stop()
        }
    }

    @Test
    fun aDeviceTriesEveryAddressAHostHasHomeNetworksFirst() {
        // Answered from a Docker bridge: the address it answered from first, then the ones it listed.
        val found = FoundHost(HostHello("h1", "Media Server", port = 47311, addresses = listOf("192.168.1.20", "172.19.0.1")), "172.19.0.1:47311")
        assertEquals(listOf("172.19.0.1:47311", "192.168.1.20:47311"), found.candidates)
        assertTrue(LanAddresses.rank("192.168.1.20") < LanAddresses.rank("10.0.0.5"))
        assertTrue(LanAddresses.rank("10.0.0.5") < LanAddresses.rank("172.19.0.1"))
        assertTrue(LanAddresses.list().none { it.startsWith("127.") || it.startsWith("169.254.") })
        assertTrue("firewall" in JvmSyncService.unreachableWords("http://172.19.0.1:47311"))
        assertTrue("172.19.0.1:47311" in JvmSyncService.unreachableWords("http://172.19.0.1:47311"))
    }

    @Test
    fun compactingKeepsWhatRetentionSaysAndFreesTheRest() {
        val dir = Files.createTempDirectory("host").toFile()
        var now = 1_000_000_000L
        try {
            val store = HostStore(dir, clock = { now })
            val p = store.createProfile(NewProfile("Mo", "fox")).id
            val game = GameKey.of("snes", null, null, "Zelda").id
            var parent: String? = null
            repeat(30) { i ->
                val h = store.content.put("save $i".toByteArray())
                val r = SaveRevision("rev-%04d-x".format(i), p, game, SaveKind.SAVE, parent, "d", "Deck", Hlc(now, 0, "d"), SaveManifest("f", listOf(SaveFile("z.srm", h, 6))))
                assertTrue(store.push(p, "d", r).accepted)
                parent = r.id
                now += 3_600_000
            }
            val pinned = store.revisions(p).last().id
            store.pin(p, pinned, true)
            val freed = store.compact(Retention(recent = 5, days = 0, weeks = 0))
            assertTrue(freed > 0)
            val left = store.revisions(p)
            assertEquals(6, left.size)
            assertTrue(left.any { it.id == pinned })
            // A restart reads the compacted history back.
            assertEquals(6, HostStore(dir, clock = { now }).revisions(p).size)
            // Files of revisions let go are gone; the rest are there.
            assertTrue(left.all { r -> r.manifest.files.all { store.content.has(it.hash) } })
            assertFalse(store.content.has(SyncCrypto.sha256("save 3".toByteArray())))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun aCutShortLineAfterACrashIsSkipped() {
        val dir = Files.createTempDirectory("host").toFile()
        try {
            val store = HostStore(dir)
            val p = store.createProfile(NewProfile("Mo", "fox")).id
            val h = store.content.put("x".toByteArray())
            store.push(p, "d", SaveRevision("rev-0001-xx", p, "snes:t.z", SaveKind.SAVE, null, "d", "Deck", Hlc(1, 0, "d"), SaveManifest("f", listOf(SaveFile("z.srm", h, 1)))))
            File(dir, "profiles/$p/revisions.jsonl").appendText("{\"id\":\"rev-00")
            File(dir, "journal.jsonl").appendText("{\"seq\":")
            val again = HostStore(dir)
            assertEquals(1, again.revisions(p).size)
            assertTrue(again.seq() >= 2)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun theHubPageShowsTheHostToThisComputerOnly() {
        val dir = Files.createTempDirectory("hub").toFile()
        val port = java.net.ServerSocket(0).use { it.localPort }
        val host = HeadlessHost.serve(File(dir, "host"), port, "Mo's <PC>", "0.3.0")!!
        try {
            val page = java.net.URI("http://127.0.0.1:$port/hub").toURL().readText()
            assertTrue("Mo&#39;s &lt;PC&gt;" in page, "the name is shown, escaped")
            assertTrue("No profiles yet" in page)
            assertFalse("<PC>" in page)
        } finally {
            host.close()
            dir.deleteRecursively()
        }
    }
}
