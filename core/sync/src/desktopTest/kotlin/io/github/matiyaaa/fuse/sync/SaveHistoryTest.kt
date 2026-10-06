package io.github.matiyaaa.fuse.sync

import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A save's history over a real host: restoring an older version sticks, copies kept for safety
 * (before a restore, the other side of a conflict) never become the newest, keeping a version for
 * good never changes which one is newest, and a save waiting to go is never dropped because the
 * host was briefly unable to take it.
 */
class SaveHistoryTest {
    private lateinit var root: File
    private lateinit var host: SyncHost
    private var port = 0
    private val http = SyncClient.defaultClient()
    private val game = GameKey.of("snes", null, null, "Chrono Trigger")

    @BeforeTest
    fun start(): Unit = runBlocking {
        root = Files.createTempDirectory("fuse-history").toFile()
        host = SyncHost(HostStore(File(root, "host"), hostName = "Gaming PC"), port = 0, bind = "127.0.0.1", callsPerMinute = 100_000).start()
        port = host.boundPort()
    }

    @AfterTest
    fun stop() {
        host.stop()
        root.deleteRecursively()
    }

    private suspend fun pairDevice(name: String): Pair<SyncClient, SyncDevice> {
        val code = host.newPairingCode()
        val id = "dev-" + SyncCrypto.token(8)
        val link = SyncClient.pair("127.0.0.1:$port", code, id, name, "LINUX", http)
        return SyncClient(link, http) to SyncDevice(File(root, name), id, name)
    }

    private fun slot(dir: File, vararg files: Pair<String, String>): LocalSlot {
        dir.mkdirs()
        for ((p, text) in files) File(dir, p).apply { parentFile.mkdirs(); writeText(text) }
        val all = dir.walkTopDown().filter { it.isFile && !it.name.startsWith(".") }.map { LocalFile(it.relativeTo(dir).path.replace('\\', '/'), it) }.toList()
        return LocalSlot(game, SaveKind.SAVE, "retroarch.srm", dir, all)
    }

    private fun SyncDevice.text(r: SaveRevision) = store.fileOf(r.manifest.files.single().hash).readText()

    private suspend fun SyncClient.newest(p: String) = revisions(p, game.id, SaveKind.SAVE).first { it.canBeNewest }

    @Test
    fun aRestoredSaveStaysRestored(): Unit = runBlocking {
        val (client, deck) = pairDevice("Deck")
        val p = client.createProfile(NewProfile("Mo", "fox")).id
        val saves = File(root, "deck-saves")
        val first = assertNotNull(deck.capture(p, slot(saves, "CT.srm" to "chapter 1"), 100))
        deck.flush(client)
        File(saves, "CT.srm").writeText("chapter 9")
        deck.capture(p, slot(saves), 900)
        deck.flush(client)

        // Back to chapter 1, as the save's history does it.
        val old = client.revisions(p, game.id, SaveKind.SAVE).first { it.id == first.id }
        deck.restore(client, p, slot(saves), old)
        deck.flush(client)
        assertEquals("chapter 1", File(saves, "CT.srm").readText())
        assertEquals("chapter 1", deck.text(client.newest(p)))

        // The next game starts on it, here and on any other device.
        assertIs<PrepareResult.Ready>(deck.prepare(client, p, slot(saves)))
        assertEquals("chapter 1", File(saves, "CT.srm").readText())
        val (pcClient, pc) = pairDevice("PC")
        val pcSaves = File(root, "pc-saves")
        pc.prepare(pcClient, p, slot(pcSaves))
        assertEquals("chapter 1", File(pcSaves, "CT.srm").readText())
        // Chapter 9 is still in the history.
        assertTrue(client.revisions(p, game.id, SaveKind.SAVE).any { deck.text(it) == "chapter 9" })
    }

    @Test
    fun aCopyKeptBeforeTakingAnotherSaveIsNeverTheNewest(): Unit = runBlocking {
        val (deckClient, deck) = pairDevice("Deck")
        val (pcClient, pc) = pairDevice("PC")
        val p = deckClient.createProfile(NewProfile("Mo", "fox")).id
        val deckSaves = File(root, "deck")
        val pcSaves = File(root, "pc")
        deck.capture(p, slot(deckSaves, "CT.srm" to "start"), 10)
        deck.flush(deckClient)
        pc.prepare(pcClient, p, slot(pcSaves))
        // The PC has its own unsent change, then takes the Deck's newer save anyway.
        File(deckSaves, "CT.srm").writeText("deck: chapter 3")
        deck.capture(p, slot(deckSaves), 300)
        deck.flush(deckClient)
        File(pcSaves, "CT.srm").writeText("pc: chapter 2")
        val remote = deckClient.newest(p)
        pc.takeRemote(pcClient, p, slot(pcSaves), remote)
        pc.flush(pcClient)
        assertEquals("deck: chapter 3", File(pcSaves, "CT.srm").readText())
        // The PC's own is kept on the host, as history only.
        val history = pcClient.revisions(p, game.id, SaveKind.SAVE)
        val kept = history.first { pc.text(it) == "pc: chapter 2" }
        assertEquals(RevisionReason.BEFORE_RESTORE, kept.reason)
        assertEquals("deck: chapter 3", deck.text(pcClient.newest(p)))
        assertIs<PrepareResult.Ready>(deck.prepare(deckClient, p, slot(deckSaves)))
        assertEquals("deck: chapter 3", File(deckSaves, "CT.srm").readText())
    }

    @Test
    fun keepingAVersionForGoodNeverMakesItTheNewest(): Unit = runBlocking {
        val (deckClient, deck) = pairDevice("Deck")
        val (pcClient, pc) = pairDevice("PC")
        val p = deckClient.createProfile(NewProfile("Mo", "fox")).id
        val deckSaves = File(root, "deck")
        val pcSaves = File(root, "pc")
        deck.capture(p, slot(deckSaves, "CT.srm" to "start"), 10)
        deck.flush(deckClient)
        pc.prepare(pcClient, p, slot(pcSaves))
        File(deckSaves, "CT.srm").writeText("deck wins")
        deck.capture(p, slot(deckSaves), 60)
        deck.flush(deckClient)
        // The PC played from the old one too: its save is the other side of a conflict.
        File(pcSaves, "CT.srm").writeText("pc conflict")
        pc.capture(p, slot(pcSaves), 50)
        pc.flush(pcClient)
        val copy = pcClient.revisions(p, game.id, SaveKind.SAVE).first { it.reason == RevisionReason.CONFLICT_COPY }
        // Kept for good, then not: it stays the other side of a conflict, and the newest stays the newest.
        pcClient.pin(p, copy.id, true)
        assertTrue(pcClient.revisions(p, game.id, SaveKind.SAVE).first { it.id == copy.id }.kept)
        assertEquals("deck wins", deck.text(deckClient.newest(p)))
        pcClient.pin(p, copy.id, false)
        val after = pcClient.revisions(p, game.id, SaveKind.SAVE).first { it.id == copy.id }
        assertEquals(RevisionReason.CONFLICT_COPY, after.reason)
        assertEquals("deck wins", deck.text(deckClient.newest(p)))
        assertIs<PrepareResult.Ready>(deck.prepare(deckClient, p, slot(deckSaves)))
        assertEquals("deck wins", File(deckSaves, "CT.srm").readText())
    }

    @Test
    fun aSaveWaitingToGoSurvivesTheHostSayingNotNow(): Unit = runBlocking {
        val (client, deck) = pairDevice("Deck")
        val p = client.createProfile(NewProfile("Mo", "fox", pin = "2468")).id
        client.openProfile(p, "2468")
        val saves = File(root, "deck")
        deck.capture(p, slot(saves, "CT.srm" to "precious"), 10)
        // The PIN changes on another device: this one can't send until it opens the profile again.
        val (other, _) = pairDevice("PC")
        other.openProfile(p, "2468")
        other.changeProfile(p, ProfileChange(pin = "1357", currentPin = "2468"))
        assertEquals("locked", assertFailsWith<SyncException> { deck.flush(client) }.code)
        assertEquals(1, deck.pendingCount, "the save must still be waiting")
        // Opened again: it goes.
        client.openProfile(p, "1357")
        deck.flush(client)
        assertEquals(0, deck.pendingCount)
        assertEquals("precious", deck.text(client.newest(p)))
    }

    @Test
    fun savesQueuedOutOfOrderStillArriveAsOneLine(): Unit = runBlocking {
        val (client, deck) = pairDevice("Deck")
        val p = client.createProfile(NewProfile("Mo", "fox")).id
        val saves = File(root, "deck")
        // Offline: played (an ordinary save), then a launch-priority save made from it.
        val a = assertNotNull(deck.capture(p, slot(saves, "CT.srm" to "a"), 10))
        File(saves, "CT.srm").writeText("b")
        val b = assertNotNull(deck.capture(p, slot(saves), 20, Priority.LAUNCH))
        assertEquals(a.id, b.parent)
        deck.flush(client)
        // Sent in the order they were made: both are in the line, the second is the newest.
        val history = client.revisions(p, game.id, SaveKind.SAVE)
        assertTrue(history.none { it.reason == RevisionReason.CONFLICT_COPY })
        assertEquals("b", deck.text(client.newest(p)))
    }

    @Test
    fun aProxyAnsweringForAHostThatIsDownIsOffline(): Unit = runBlocking {
        val (client, deck) = pairDevice("Deck")
        val p = client.createProfile(NewProfile("Mo", "fox")).id
        // A reverse proxy (or a tunnel) in front of a host that is off answers with its own error page.
        val proxy = java.net.ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))
        val answering = Thread {
            while (!proxy.isClosed) {
                val socket = runCatching { proxy.accept() }.getOrNull() ?: break
                socket.use { s ->
                    val input = s.getInputStream().bufferedReader()
                    var line = input.readLine()
                    var length = 0
                    while (!line.isNullOrEmpty()) {
                        if (line.startsWith("Content-Length:", ignoreCase = true)) length = line.substringAfter(':').trim().toInt()
                        line = input.readLine()
                    }
                    repeat(length) { input.read() }
                    val body = "<html>Bad gateway</html>"
                    s.getOutputStream().write("HTTP/1.1 502 Bad Gateway\r\nContent-Type: text/html\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body".toByteArray())
                }
            }
        }.apply { isDaemon = true; start() }
        try {
            val away = SyncClient(client.link.copy(localAddress = null, remoteAddress = "http://127.0.0.1:${proxy.localPort}"), http)
            deck.capture(p, slot(File(root, "deck"), "CT.srm" to "x"), 10)
            val e = assertFailsWith<SyncException> { deck.flush(away) }
            assertEquals("offline", e.code)
            assertEquals(1, deck.pendingCount)
            // At home the host's own address is tried first, then the proxy: still offline, nothing dropped.
            val both = SyncClient(client.link.copy(localAddress = "127.0.0.1:1", remoteAddress = "http://127.0.0.1:${proxy.localPort}"), http)
            assertEquals("offline", assertFailsWith<SyncException> { both.profiles() }.code)
        } finally {
            proxy.close()
            answering.join(2_000)
        }
    }

    @Test
    fun swappingPeopleNeverDeletesAFileItDidNotKeep(): Unit = runBlocking {
        val (client, deck) = pairDevice("Deck")
        val mo = client.createProfile(NewProfile("Mo", "fox")).id
        val sam = client.createProfile(NewProfile("Sam", "owl")).id
        val saves = File(root, "deck").apply { mkdirs() }
        File(saves, "CT.srm").writeText("mo")
        // A file the save names but Fuse Sync can't keep (an unsafe name) is left where it is.
        val odd = File(saves, "odd:name.srm").apply { writeText("keep me") }
        val withOdd = LocalSlot(game, SaveKind.SAVE, "retroarch.srm", saves, listOf(LocalFile("CT.srm", File(saves, "CT.srm")), LocalFile("odd:name.srm", odd)))
        deck.handover(mo, withOdd)
        deck.handover(sam, withOdd)
        assertTrue(odd.isFile, "a file that wasn't kept must never be deleted")
        assertNull(File(saves, "CT.srm").takeIf { it.exists() })
    }

    @Test
    fun savesThatNeverReachedTheHostOutliveForgettingIt(): Unit = runBlocking {
        val (client, deck) = pairDevice("Deck")
        val p = client.createProfile(NewProfile("Mo", "fox")).id
        val saves = File(root, "deck")
        deck.capture(p, slot(saves, "CT.srm" to "sent"), 10)
        deck.flush(client)
        // Played offline, then Fuse Sync is turned off before the host is back.
        File(saves, "CT.srm").writeText("only here")
        deck.capture(p, slot(saves), 20, title = "Chrono Trigger")
        val kept = File(root, "kept")
        assertEquals(1, deck.exportUnsent(kept))
        val file = kept.walkTopDown().single { it.isFile }
        assertEquals("only here", file.readText())
        assertEquals("CT.srm", file.name)
        assertTrue(file.path.contains("Chrono Trigger"))
    }
}
