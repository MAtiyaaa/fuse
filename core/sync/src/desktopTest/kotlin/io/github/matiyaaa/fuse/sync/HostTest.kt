package io.github.matiyaaa.fuse.sync

import kotlinx.serialization.json.Json
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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
                assertEquals(hello, Json.decodeFromString(HostHello.serializer(), String(p.data, 0, p.length)))
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
}
