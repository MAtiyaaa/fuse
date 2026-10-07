package io.github.matiyaaa.fuse.sync

import io.github.matiyaaa.fuse.data.settings.SyncSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The household's games: each device's list reaching the others, leave from the host to fetch a
 * game from another device, the bytes coming straight from it or passed through the host (and
 * never kept there), requests to a device that is away waiting until it is back, and every
 * device's transfers seen from the others.
 */
class HouseholdGamesTest {
    private lateinit var root: File
    private lateinit var host: SyncHost
    private val http = SyncClient.defaultClient()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val joined = ArrayList<JvmHousehold>()

    @BeforeTest
    fun start(): Unit = runBlocking {
        root = Files.createTempDirectory("household-games").toFile()
        host = SyncHost(HostStore(File(root, "host"), hostName = "Gaming PC"), port = 0, bind = "127.0.0.1", callsPerMinute = 100_000).start()
    }

    @AfterTest
    fun stop() {
        // Each device's server for other devices stops with it, so none is left running into the next test.
        joined.forEach { it.off() }
        scope.cancel()
        host.stop()
        root.deleteRecursively()
    }

    private inner class Device(val name: String, val reachableAt: String = "127.0.0.1", val settings: SyncSettings = SyncSettings()) {
        lateinit var client: SyncClient
        lateinit var household: JvmHousehold
        val performed = ArrayList<DeviceCommand>()
        var games: List<SharedGame> = emptyList()

        suspend fun join(): Device {
            val id = "dev-" + SyncCrypto.token(8)
            val link = SyncClient.pair("127.0.0.1:${host.boundPort()}", host.newPairingCode(), id, name, "LINUX", http)
            client = SyncClient(link, http)
            household = JvmHousehold(File(root, "hh-$name"), scope, { client }, { link.deviceId }, { settings }, peerPort = 0, bindAddresses = { listOf(reachableAt) })
            household.attach(object : HouseholdLocal {
                override suspend fun games() = this@Device.games
                override suspend fun perform(command: DeviceCommand): CommandResult {
                    performed += command
                    return CommandResult(DeviceCommand.DONE, "Here now")
                }
                override fun transfers() = listOf(RemoteTransfer(key = "romm:rom:7", title = "Mario", status = "ACTIVE", done = 43, total = 100))
            })
            joined += household
            return this
        }

        /** Answers the host as Fuse Sync's loop would, until stopped. */
        fun listen() = scope.launch {
            var since = 0L
            while (isActive) {
                val page = runCatching { client.events(since, waitSeconds = 2) }.getOrNull() ?: continue
                since = page.seq
                household.woken(page.wake)
            }
        }
    }

    /** A game of two files in a folder, on [device]'s disk. */
    private fun folderGame(device: String, game: String, bytes: Int = 300_000): SharedGame {
        val dir = File(root, "$device/roms/psx/$game").apply { mkdirs() }
        val disc = File(dir, "disc.bin").apply { writeBytes(Random(game.hashCode()).nextBytes(bytes)) }
        val cue = File(dir, "disc.cue").apply { writeText("FILE \"disc.bin\" BINARY") }
        val entry = LibraryEntry(
            game = "g:$game", title = game, platform = "psx", sizeBytes = disc.length() + cue.length(), folder = true, name = game,
            files = listOf(PeerFile("disc.cue", cue.length()), PeerFile("disc.bin", disc.length())),
        )
        return SharedGame(entry, mapOf("disc.cue" to cue.path, "disc.bin" to disc.path))
    }

    private suspend fun fetchAll(from: Device, to: Device, game: String, file: String, direct: Boolean): ByteArray {
        val open = to.household.openTicket(from.client.link.deviceId, game, listOf(file))
        val out = ByteArrayOutputStream()
        var offset = 0L
        while (true) {
            var size: Long? = null
            val got = to.household.fetch(open, file, offset, 100_000, direct) { buf, n, total -> size = total; out.write(buf, 0, n) }
            offset += got
            if (got == 0L || (size != null && offset >= size!!)) break
        }
        return out.toByteArray()
    }

    @Test
    fun `a device's games reach the others, and only while it shares them`() = runBlocking<Unit> {
        val pc = Device("PC").join()
        val thor = Device("Thor").join()
        pc.games = listOf(folderGame("PC", "Crash"))
        pc.household.connected()
        thor.household.connected()
        assertTrue(pc.household.supported.value, "a 0.3.8 host offers the household's games")
        pc.household.publish()
        thor.household.refresh()
        val lib = assertNotNull(thor.household.libraries.value.singleOrNull())
        assertEquals("PC", lib.name)
        assertEquals(listOf("Crash"), lib.entries.map { it.title })
        assertNotNull(lib.endpoint, "a sharing device says where it answers")

        // Hashes arrive as the files are read.
        withTimeout(10_000) {
            while (thor.household.libraries.value.single().entries.single().fingerprint == null) {
                delay(200)
                pc.household.publish()
                thor.household.refresh()
            }
        }

        // A device that stops sharing lists nothing.
        val off = Device("Deck", settings = SyncSettings(shareLibrary = false)).join()
        off.games = listOf(folderGame("Deck", "Spyro"))
        off.household.connected()
        off.household.publish()
        thor.household.refresh()
        assertTrue(thor.household.libraries.value.first { it.name == "Deck" }.entries.isEmpty())

        // A device that leaves the household leaves no list behind.
        pc.client.unlinkSelf()
        thor.household.refresh()
        assertFalse(thor.household.libraries.value.any { it.name == "PC" })
        // What the Thor knew survives a restart (the household's games show while the host is away).
        val again = JvmHousehold(File(root, "hh-Thor"), scope, { null }, { "x" }, { SyncSettings() })
        assertEquals(thor.household.libraries.value.map { it.device }, again.libraries.value.map { it.device })
    }

    @Test
    fun `a game comes straight from the device, whole and the same`() = runBlocking<Unit> {
        val pc = Device("PC").join()
        val thor = Device("Thor").join()
        val crash = folderGame("PC", "Crash")
        pc.games = listOf(crash)
        pc.household.connected()
        thor.household.connected()
        pc.household.publish()
        thor.household.refresh()

        val open = thor.household.openTicket(pc.client.link.deviceId, "g:Crash", listOf("disc.bin"))
        assertTrue(thor.household.reachable(open.ticket), "the PC answers on the home network")
        val bytes = fetchAll(pc, thor, "g:Crash", "disc.bin", direct = true)
        assertContentEquals(File(crash.paths.getValue("disc.bin")).readBytes(), bytes)
    }

    @Test
    fun `leave covers only that game's files, for that device, and a request can't be played again`() = runBlocking<Unit> {
        val pc = Device("PC").join()
        val thor = Device("Thor").join()
        pc.games = listOf(folderGame("PC", "Crash"))
        pc.household.connected()
        thor.household.connected()
        pc.household.publish()

        // Not a file of the game, not a game it has, not from itself.
        assertFailsWith<SyncException> { thor.household.openTicket(pc.client.link.deviceId, "g:Crash", listOf("../../etc/passwd")) }
        assertFailsWith<SyncException> { thor.household.openTicket(pc.client.link.deviceId, "g:Spyro", listOf("disc.bin")) }
        assertFailsWith<SyncException> { pc.household.openTicket(pc.client.link.deviceId, "g:Crash", listOf("disc.bin")) }

        val open = thor.household.openTicket(pc.client.link.deviceId, "g:Crash", listOf("disc.bin"))
        val server = PeerServer({ pc.client.link.deviceId }, { pc.client.link.deviceSecret }, { _, _ -> null })
        val t = open.ticket
        val now = System.currentTimeMillis()
        val nonce = SyncCrypto.token(18)
        val sig = PeerSigning.sign(open.key, t.game, "disc.bin", 0, 10, now, nonce)
        assertTrue(server.allows(t, t.game, "disc.bin", 0, 10, now, nonce, sig))
        assertFalse(server.allows(t, t.game, "disc.bin", 0, 10, now, nonce, sig), "the same request twice is refused")
        val n2 = SyncCrypto.token(18)
        assertFalse(server.allows(t, t.game, "disc.bin", 0, 999, now, n2, PeerSigning.sign(open.key, t.game, "disc.bin", 0, 10, now, n2)), "a changed request breaks the signature")
        val n3 = SyncCrypto.token(18)
        assertFalse(server.allows(t, t.game, "disc.cue", 0, 10, now, n3, PeerSigning.sign(open.key, t.game, "disc.cue", 0, 10, now, n3)), "a file the leave doesn't cover")
        val n4 = SyncCrypto.token(18)
        assertFalse(server.allows(t.copy(requester = "someone-else"), t.game, "disc.bin", 0, 10, now, n4, PeerSigning.sign(open.key, t.game, "disc.bin", 0, 10, now, n4)), "a changed ticket")
        val n5 = SyncCrypto.token(18)
        val late = now + 2 * SyncHost.TICKET_MS
        assertFalse(PeerServer({ pc.client.link.deviceId }, { pc.client.link.deviceSecret }, { _, _ -> null }, clock = { late }).allows(t, t.game, "disc.bin", 0, 10, late, n5, PeerSigning.sign(open.key, t.game, "disc.bin", 0, 10, late, n5)), "a leave that ran out")
        // The key itself never travels: the ticket the source sees has no sealed key.
        assertEquals("", t.sealedKey)
    }

    @Test
    fun `through the host when the device can't be reached, and nothing is kept there`() = runBlocking<Unit> {
        // The PC says it is at an address nobody can reach (a device away from home).
        val pc = Device("PC", reachableAt = "203.0.113.9").join()
        val thor = Device("Thor").join()
        val crash = folderGame("PC", "Crash", bytes = 1_200_000)
        pc.games = listOf(crash)
        pc.household.connected()
        thor.household.connected()
        pc.household.publish()
        val listening = pc.listen()

        val open = thor.household.openTicket(pc.client.link.deviceId, "g:Crash", listOf("disc.bin"))
        assertFalse(thor.household.reachable(open.ticket))
        val bytes = fetchAll(pc, thor, "g:Crash", "disc.bin", direct = false)
        listening.cancel()
        assertContentEquals(File(crash.paths.getValue("disc.bin")).readBytes(), bytes)
        val biggest = File(root, "host").walkTopDown().filter { it.isFile }.maxOfOrNull { it.length() } ?: 0
        assertTrue(biggest < 200_000, "the host keeps no piece of the game ($biggest bytes in its biggest file)")
    }

    @Test
    fun `the host passes a game on straight from a device it reaches`() = runBlocking<Unit> {
        val pc = Device("PC").join()
        val thor = Device("Thor").join()
        val crash = folderGame("PC", "Crash")
        pc.games = listOf(crash)
        pc.household.connected()
        thor.household.connected()
        pc.household.publish()
        // No one listens on the PC's side: the host reaches it itself (the Thor away, the PC at home).
        val bytes = fetchAll(pc, thor, "g:Crash", "disc.bin", direct = false)
        assertContentEquals(File(crash.paths.getValue("disc.bin")).readBytes(), bytes)
    }

    @Test
    fun `a request to a device that is away waits for it, and the asker sees how it went`() = runBlocking<Unit> {
        val pc = Device("PC").join()
        val thor = Device("Thor").join()
        pc.household.connected()
        val made = pc.household.ask(DeviceCommand(target = thor.client.link.deviceId, type = DeviceCommand.FETCH, game = "g:Crash", title = "Crash")).getOrThrow()
        assertEquals(DeviceCommand.PENDING, made.state)
        // Asking again is the same request.
        assertEquals(made.id, pc.household.ask(DeviceCommand(target = thor.client.link.deviceId, type = DeviceCommand.FETCH, game = "g:Crash", title = "Crash")).getOrThrow().id)
        assertTrue(thor.performed.isEmpty())

        // The Thor comes back.
        thor.household.connected()
        assertEquals(listOf("g:Crash"), thor.performed.map { it.game })
        pc.household.woken(listOf(HouseholdHost.WAKE_COMMANDS))
        withTimeout(5_000) { while (pc.household.commands.value.firstOrNull { it.id == made.id }?.state != DeviceCommand.DONE) delay(50) }
        // Done once: coming back again does nothing more.
        thor.household.connected()
        assertEquals(1, thor.performed.size)
    }

    @Test
    fun `every device's transfers are seen from the others`() = runBlocking<Unit> {
        val pc = Device("PC").join()
        val thor = Device("Thor").join()
        pc.household.connected()
        thor.household.connected()
        thor.household.transfersChanged()
        pc.household.watchTransfers(true)
        withTimeout(10_000) { while (pc.household.transfers.value.none { it.name == "Thor" }) delay(100) }
        val t = pc.household.transfers.value.single { it.name == "Thor" }.items.single()
        assertEquals("Mario", t.title)
        assertEquals(43, t.done)
        pc.household.watchTransfers(false)
    }
}
