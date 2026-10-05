package io.github.matiyaaa.fuse.sync

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.http.takeFrom
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A real host on a real port and devices talking to it over HTTP, as at home: pairing, profiles
 * with PINs, records merging, saves going up and coming down, conflicts kept and settled, and the
 * host refusing what it must refuse.
 */
class EndToEndTest {
    private lateinit var root: File
    private lateinit var host: SyncHost
    private var port = 0
    private val http = SyncClient.defaultClient()

    @BeforeTest
    fun start(): Unit = runBlocking {
        root = Files.createTempDirectory("fuse-sync").toFile()
        host = SyncHost(HostStore(File(root, "host"), hostName = "Gaming PC"), port = 0, bind = "127.0.0.1", callsPerMinute = 100_000).start()
        port = host.boundPort()
    }

    @AfterTest
    fun stop() {
        host.stop()
        root.deleteRecursively()
    }

    private val address get() = "127.0.0.1:$port"

    private suspend fun pairDevice(name: String): Pair<SyncClient, SyncDevice> {
        val code = host.newPairingCode()
        val id = "dev-" + SyncCrypto.token(8)
        val link = SyncClient.pair(address, code, id, name, "LINUX", http)
        return SyncClient(link, http) to SyncDevice(File(root, name), id, name)
    }

    private fun slot(dir: File, game: GameKey, vararg files: Pair<String, String>): LocalSlot {
        dir.mkdirs()
        for ((p, text) in files) File(dir, p).apply { parentFile.mkdirs(); writeText(text) }
        val all = dir.walkTopDown().filter { it.isFile && !it.name.startsWith(".") }.map { LocalFile(it.relativeTo(dir).path.replace('\\', '/'), it) }.toList()
        return LocalSlot(game, SaveKind.SAVE, "retroarch.srm", dir, all)
    }

    private fun slotOf(dir: File, game: GameKey) = slot(dir, game)

    @Test
    fun helloAndPairing(): Unit = runBlocking {
        val hello = assertNotNull(SyncClient.hello(address, http))
        assertEquals("Gaming PC", hello.name)
        // No code being shown: pairing is refused.
        val noCode = assertFailsWith<SyncException> { SyncClient.pair(address, "AAAAAAAA", "dev-12345678", "Deck", "LINUX", http) }
        assertEquals("no-pairing", noCode.code)
        val code = host.newPairingCode()
        val wrong = assertFailsWith<SyncException> { SyncClient.pair(address, "BBBBBBBB", "dev-12345678", "Deck", "LINUX", http) }
        assertEquals("wrong-code", wrong.code)
        val link = SyncClient.pair(address, code, "dev-12345678", "Deck", "LINUX", http)
        assertEquals(hello.hostId, link.hostId)
        // A code works once.
        assertEquals("no-pairing", assertFailsWith<SyncException> { SyncClient.pair(address, code, "dev-87654321", "PC", "WINDOWS", http) }.code)
        val client = SyncClient(link, http)
        assertEquals("Gaming PC", client.status().hello.name)
        assertEquals(Route.LOCAL, client.route)
    }

    @Test
    fun fiveWrongCodesEndPairing(): Unit = runBlocking {
        val code = host.newPairingCode()
        repeat(5) { runCatching { SyncClient.pair(address, "WRONG" + it, "dev-12345678", "Deck", "LINUX", http) } }
        assertEquals("no-pairing", assertFailsWith<SyncException> { SyncClient.pair(address, code, "dev-12345678", "Deck", "LINUX", http) }.code)
    }

    @Test
    fun profilesWithPinsStayClosedToOtherDevices(): Unit = runBlocking {
        val (deck, _) = pairDevice("Deck")
        val (pc, _) = pairDevice("PC")
        val mo = deck.createProfile(NewProfile("Mo", "fox", pin = "2468"))
        val sam = deck.createProfile(NewProfile("Sam", "owl"))
        assertTrue(mo.protected)
        assertEquals(setOf("Mo", "Sam"), pc.profiles().map { it.name }.toSet())
        // The PC hasn't opened Mo's profile: nothing of Mo's reaches it.
        assertEquals("locked", assertFailsWith<SyncException> { pc.meta(mo.id) }.code)
        assertEquals("wrong-pin", assertFailsWith<SyncException> { pc.openProfile(mo.id, "1111") }.code)
        pc.openProfile(mo.id, "2468")
        pc.meta(mo.id)
        // A profile without a PIN is open to every paired device.
        pc.meta(sam.id)
        // Changing the PIN asks for the current one, and closes the profile on other devices.
        assertFailsWith<SyncException> { pc.changeProfile(mo.id, ProfileChange(pin = "9999", currentPin = "0000")) }
        deck.changeProfile(mo.id, ProfileChange(pin = "1357", currentPin = "2468"))
        assertEquals("locked", assertFailsWith<SyncException> { pc.meta(mo.id) }.code)
        deck.meta(mo.id)
        // Names are unique.
        assertFailsWith<SyncException> { pc.createProfile(NewProfile("mo", "cat")) }
    }

    @Test
    fun wrongPinsSlowDown(): Unit = runBlocking {
        val (deck, _) = pairDevice("Deck")
        val (pc, _) = pairDevice("PC")
        val mo = deck.createProfile(NewProfile("Mo", "fox", pin = "2468"))
        repeat(3) { runCatching { pc.openProfile(mo.id, "0000") } }
        // After three wrong tries even the right PIN waits.
        assertEquals("wait", assertFailsWith<SyncException> { pc.openProfile(mo.id, "2468") }.code)
    }

    @Test
    fun recordsMergeThroughTheHost(): Unit = runBlocking {
        val (deckClient, deck) = pairDevice("Deck")
        val (pcClient, pc) = pairDevice("PC")
        val p = deckClient.createProfile(NewProfile("Mo", "fox")).id
        val game = GameKey.of("snes", null, null, "Chrono Trigger")
        // Both play offline: 30 and 20 minutes.
        deck.played(p, game, SessionEntry("s1", deck.deviceId, 0, 30 * 60_000))
        pc.played(p, game, SessionEntry("s2", pc.deviceId, 10_000_000, 10_000_000 + 20 * 60_000))
        deck.flush(deckClient)
        pc.flush(pcClient)
        assertEquals(50 * 60L, deck.pullMeta(deckClient, p).game(game).totalSeconds)
        assertEquals(50 * 60L, pc.pullMeta(pcClient, p).game(game).totalSeconds)
        // Sending again changes nothing.
        deck.flush(deckClient)
        assertEquals(50 * 60L, pc.pullMeta(pcClient, p).game(game).totalSeconds)
    }

    @Test
    fun aSaveFollowsThePersonToTheOtherDevice(): Unit = runBlocking {
        val (deckClient, deck) = pairDevice("Deck")
        val (pcClient, pc) = pairDevice("PC")
        val p = deckClient.createProfile(NewProfile("Mo", "fox")).id
        val game = GameKey.of("snes", null, null, "Chrono Trigger")
        val deckSaves = File(root, "deck-saves")
        val pcSaves = File(root, "pc-saves")
        val rev = assertNotNull(deck.capture(p, slot(deckSaves, game, "Chrono Trigger.srm" to "level 12"), playSeconds = 1800))
        deck.flush(deckClient)
        // Nothing changed: no new revision.
        assertEquals(null, deck.capture(p, slotOf(deckSaves, game), 1800))
        val result = pc.prepare(pcClient, p, slotOf(pcSaves, game))
        assertIs<PrepareResult.Updated>(result)
        assertEquals("level 12", File(pcSaves, "Chrono Trigger.srm").readText())
        // Nothing half-written is left beside it.
        assertTrue(pcSaves.listFiles()!!.none { it.name.startsWith(".") })
        // Played on the PC, then back on the Deck.
        File(pcSaves, "Chrono Trigger.srm").writeText("level 20")
        pc.capture(p, slotOf(pcSaves, game), 3600)
        pc.flush(pcClient)
        assertIs<PrepareResult.Updated>(deck.prepare(deckClient, p, slotOf(deckSaves, game)))
        assertEquals("level 20", File(deckSaves, "Chrono Trigger.srm").readText())
        // And now both agree.
        assertIs<PrepareResult.Ready>(deck.prepare(deckClient, p, slotOf(deckSaves, game)))
        val history = deckClient.revisions(p, game.id, SaveKind.SAVE)
        assertTrue(history.size >= 2)
        assertTrue(history.any { it.id == rev.id })
    }

    @Test
    fun bothPlayedIsAConflictAndNothingIsLost(): Unit = runBlocking {
        val (deckClient, deck) = pairDevice("Deck")
        val (pcClient, pc) = pairDevice("PC")
        val p = deckClient.createProfile(NewProfile("Mo", "fox")).id
        val game = GameKey.of("gba", "AGB-BPEE", null, "Emerald")
        val deckSaves = File(root, "deck-saves")
        val pcSaves = File(root, "pc-saves")
        deck.capture(p, slot(deckSaves, game, "Emerald.sav" to "start"), 60)
        deck.flush(deckClient)
        pc.prepare(pcClient, p, slotOf(pcSaves, game))
        // Both play from the same save without syncing in between.
        File(deckSaves, "Emerald.sav").writeText("deck badge 3")
        File(pcSaves, "Emerald.sav").writeText("pc badge 2")
        deck.capture(p, slotOf(deckSaves, game), 600)
        deck.flush(deckClient)
        pc.capture(p, slotOf(pcSaves, game), 500)
        // The PC's push is kept as a conflict copy, not the newest.
        pc.flush(pcClient)
        val conflict = pc.prepare(pcClient, p, slotOf(pcSaves, game))
        assertIs<PrepareResult.Conflict>(conflict)
        assertEquals("Deck", conflict.remote.deviceName)
        // The PC's own save is still in place, untouched.
        assertEquals("pc badge 2", File(pcSaves, "Emerald.sav").readText())
        // The host kept both.
        val history = pcClient.revisions(p, game.id, SaveKind.SAVE)
        assertTrue(history.any { it.reason == RevisionReason.CONFLICT_COPY })
        // The person keeps the PC's: it becomes the newest everywhere.
        pc.keepLocal(pcClient, p, slotOf(pcSaves, game), conflict.remote, 500)
        assertIs<PrepareResult.Updated>(deck.prepare(deckClient, p, slotOf(deckSaves, game)))
        assertEquals("pc badge 2", File(deckSaves, "Emerald.sav").readText())
        // The Deck's save from before is still in the history, with the BEFORE_RESTORE copy too once sent.
        deck.flush(deckClient)
        val all = deckClient.revisions(p, game.id, SaveKind.SAVE)
        assertTrue(all.any { r -> r.manifest.files.any { f -> deck.store.has(f.hash) } })
        assertTrue(all.size >= 4)
    }

    @Test
    fun offlineTheGamePlaysAndSyncsLater(): Unit = runBlocking {
        val (deckClient, deck) = pairDevice("Deck")
        val p = deckClient.createProfile(NewProfile("Mo", "fox")).id
        val game = GameKey.of("snes", null, null, "Earthbound")
        val saves = File(root, "deck-saves")
        val away = SyncClient(deckClient.link.copy(localAddress = "127.0.0.1:1"), http)
        assertIs<PrepareResult.Offline>(deck.prepare(away, p, slot(saves, game, "Earthbound.srm" to "a")))
        deck.capture(p, slotOf(saves, game), 100)
        deck.played(p, game, SessionEntry("s1", deck.deviceId, 0, 60_000))
        assertFailsWith<SyncException> { deck.flush(away) }
        assertEquals(2, deck.pendingCount)
        // Back home: everything goes up.
        deck.flush(deckClient)
        assertEquals(0, deck.pendingCount)
        assertEquals(1, deckClient.revisions(p, game.id).size)
    }

    @Test
    fun aMissingDriveIsNeverADeletion(): Unit = runBlocking {
        val (deckClient, deck) = pairDevice("Deck")
        val p = deckClient.createProfile(NewProfile("Mo", "fox")).id
        val game = GameKey.of("snes", null, null, "Earthbound")
        val sd = File(root, "sdcard/saves")
        deck.capture(p, slot(sd, game, "Earthbound.srm" to "a"), 100)
        deck.flush(deckClient)
        // The card is out: no capture, no prepare touches anything.
        val gone = LocalSlot(game, SaveKind.SAVE, "retroarch.srm", sd, emptyList(), available = false)
        assertEquals(null, deck.capture(p, gone, 100))
        assertIs<PrepareResult.Unavailable>(deck.prepare(deckClient, p, gone))
        // An empty folder isn't an empty save either.
        File(sd, "Earthbound.srm").delete()
        assertEquals(null, deck.capture(p, slotOf(sd, game), 100))
        assertEquals(1, deckClient.revisions(p, game.id).size)
    }

    @Test
    fun theHostRefusesWhatItMust(): Unit = runBlocking {
        val (deckClient, deck) = pairDevice("Deck")
        val p = deckClient.createProfile(NewProfile("Mo", "fox")).id
        val link = deckClient.link
        // Unsigned.
        val unsigned = http.request { method = HttpMethod.Get; url.takeFrom("http://$address${SyncApi.BASE}/profiles") }
        assertEquals(401, unsigned.status.value)
        // A replayed call.
        val time = System.currentTimeMillis()
        val nonce = SyncCrypto.token(18)
        val path = "${SyncApi.BASE}/profiles"
        val sig = RequestSigning.sign(link.deviceSecret, "GET", path, time, nonce, ByteArray(0))
        suspend fun send() = http.request {
            method = HttpMethod.Get
            url.takeFrom("http://$address$path")
            header(RequestSigning.DEVICE, link.deviceId)
            header(RequestSigning.TIME, time.toString())
            header(RequestSigning.NONCE, nonce)
            header(RequestSigning.SIGNATURE, sig)
        }
        assertEquals(200, send().status.value)
        val replay = send()
        assertEquals(401, replay.status.value)
        assertTrue("replay" in replay.bodyAsText())
        // A body changed after signing.
        val n2 = SyncCrypto.token(18)
        val good = """{"name":"Eve","avatar":"cat"}"""
        val sig2 = RequestSigning.sign(link.deviceSecret, "POST", path, time, n2, good.toByteArray())
        val tampered = http.request {
            method = HttpMethod.Post
            url.takeFrom("http://$address$path")
            header(RequestSigning.DEVICE, link.deviceId)
            header(RequestSigning.TIME, time.toString())
            header(RequestSigning.NONCE, n2)
            header(RequestSigning.SIGNATURE, sig2)
            setBody("""{"name":"Mallory","avatar":"cat"}""")
        }
        assertEquals(401, tampered.status.value)
        // An old call (a clock far off, or a captured call played back later).
        val old = SyncClient(link, http, clock = { System.currentTimeMillis() - 60 * 60_000 })
        assertEquals("clock", assertFailsWith<SyncException> { old.profiles() }.code)
        // A revision naming a file outside the save, or files the host doesn't have.
        val game = GameKey.of("snes", null, null, "Zelda")
        val bad = SaveRevision("rev-bad-0001", p, game.id, SaveKind.SAVE, null, link.deviceId, "Deck", Hlc(1, 0, "x"),
            SaveManifest("retroarch.srm", listOf(SaveFile("../../.bashrc", "a".repeat(64), 1))))
        assertFailsWith<SyncException> { deckClient.push(p, bad, deck.store) }
        val missing = bad.copy(id = "rev-bad-0002", manifest = SaveManifest("retroarch.srm", listOf(SaveFile("Zelda.srm", "b".repeat(64), 1))))
        assertFailsWith<Exception> { deckClient.push(p, missing, deck.store) }
        // A damaged upload: the bytes don't match the name.
        val tmp = File(root, "damaged").apply { writeText("not what it says") }
        val damaged = assertFailsWith<SyncException> { deckClient.upload("c".repeat(64), tmp) }
        assertEquals("integrity", damaged.code)
        assertFalse(host.store.content.has("c".repeat(64)))
        // A revoked device is out at once.
        deckClient.unlinkSelf()
        assertEquals("revoked", assertFailsWith<SyncException> { deckClient.profiles() }.code)
    }

    @Test
    fun aNewHostStartsWhereTheOldOneLeftOff(): Unit = runBlocking {
        val (deckClient, deck) = pairDevice("Deck")
        val p = deckClient.createProfile(NewProfile("Mo", "fox", pin = "2468")).id
        val game = GameKey.of("snes", null, null, "Zelda")
        deck.capture(p, slot(File(root, "s"), game, "Zelda.srm" to "x"), 10)
        deck.played(p, game, SessionEntry("s1", deck.deviceId, 0, 120_000))
        deck.flush(deckClient)
        host.stop()
        // The host restarts (a reboot): everything is still there, and the same device still signs in.
        host = SyncHost(HostStore(File(root, "host")), port = 0, bind = "127.0.0.1").start()
        port = host.boundPort()
        val again = SyncClient(deckClient.link.copy(localAddress = address), http)
        assertEquals(listOf("Mo"), again.profiles().map { it.name })
        assertEquals(1, again.revisions(p, game.id).size)
        assertEquals(120L, again.meta(p).meta.game(game).totalSeconds)
    }

    @Test
    fun manyDevicesAndManySaves(): Unit = runBlocking {
        // Fifty devices across five profiles, each pushing saves for twenty games, over HTTP.
        val owner = pairDevice("Owner").first
        val profiles = (1..5).map { owner.createProfile(NewProfile("P$it", "fox")).id }
        val devices = (1..50).map { pairDevice("Device $it") }
        devices.forEachIndexed { i, (client, device) ->
            val p = profiles[i % profiles.size]
            for (g in 1..20) {
                val game = GameKey.of("snes", null, null, "Game $g")
                device.capture(p, slot(File(root, "d$i/g$g"), game, "save.srm" to "device $i game $g"), 60)
            }
            device.played(p, GameKey.of("snes", null, null, "Game 1"), SessionEntry("s-$i", device.deviceId, 0, 60_000))
            device.flush(client)
        }
        val status = owner.status()
        assertEquals(51, status.devices.size)
        assertEquals(1000, status.revisionCount)
        // Ten devices share each profile; their minutes on Game 1 all count.
        val meta = owner.meta(profiles[0]).meta
        assertEquals(10 * 60L, meta.game(GameKey.of("snes", null, null, "Game 1")).totalSeconds)
        // Each profile only sees its own saves.
        assertTrue(owner.revisions(profiles[1]).all { it.profile == profiles[1] })
        assertEquals(200, owner.revisions(profiles[1]).size)
    }
}
