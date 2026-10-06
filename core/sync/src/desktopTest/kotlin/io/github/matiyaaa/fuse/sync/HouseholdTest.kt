package io.github.matiyaaa.fuse.sync

import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Fuse Sync as a household: six devices on one host, a save made on any one of them reaching
 * every other that has the game, wherever each keeps it, with the host knowing who is current.
 */
class HouseholdTest {
    private lateinit var root: File
    private lateinit var host: SyncHost
    private val http = SyncClient.defaultClient()

    @BeforeTest
    fun start(): Unit = runBlocking {
        root = Files.createTempDirectory("household").toFile()
        host = SyncHost(HostStore(File(root, "host"), hostName = "Gaming PC"), port = 0, bind = "127.0.0.1", callsPerMinute = 100_000).start()
    }

    @AfterTest
    fun stop() {
        host.stop()
        root.deleteRecursively()
    }

    private inner class Device(val name: String, val platform: String) {
        lateinit var client: SyncClient
        lateinit var device: SyncDevice

        suspend fun join(): Device {
            val id = "dev-" + SyncCrypto.token(8)
            val link = SyncClient.pair("127.0.0.1:${host.boundPort()}", host.newPairingCode(), id, name, platform, http)
            client = SyncClient(link, http)
            device = SyncDevice(File(root, "state-$name"), id, name)
            return this
        }

        /** Where this device's emulator keeps the save: a folder per device, not made yet. */
        val saves = File(root, "$name/Emulation/saves/${if (platform == "WINDOWS") "Nested/Deeper" else "gba"}")

        fun slot(game: GameKey): LocalSlot {
            val file = File(saves, "Pokemon Ruby.srm")
            return LocalSlot(game, SaveKind.SAVE, "sram", saves, listOf(LocalFile("save.srm", file)), targets = mapOf("save.srm" to file))
        }

        val file get() = File(saves, "Pokemon Ruby.srm")

        suspend fun converge(profile: String, game: GameKey): PrepareResult? {
            val slot = slot(game)
            val r = device.converge(client, profile, slot)
            if (r != null) device.appliedNote(profile, slot, r, System.currentTimeMillis())?.let { client.noteApplied(listOf(it)) }
            return r
        }

        suspend fun play(profile: String, game: GameKey, bytes: ByteArray, seconds: Long) {
            file.parentFile.mkdirs()
            file.writeBytes(bytes)
            assertNotNull(device.capture(profile, slot(game), seconds))
            device.flush(client)
        }
    }

    private val ruby = GameKey.of("gba", null, null, "Pokemon Ruby")

    @Test
    fun `one save reaches every device that has the game, and the host knows who is current`() = runBlocking<Unit> {
        val deck = Device("Deck", "LINUX").join()
        val pc = Device("PC", "WINDOWS").join()
        val thor = Device("Thor", "ANDROID").join()
        val phone = Device("Phone", "ANDROID").join()
        val laptop = Device("Laptop", "MACOS").join()
        val tv = Device("TV", "LINUX").join()
        val p = deck.client.createProfile(NewProfile("Mo", "fox")).id

        // Played on the Deck.
        val v1 = ByteArray(0x8000) { (it % 7).toByte() }
        deck.play(p, ruby, v1, 600)

        // Fan-out: four devices that have the game take it, each creating its own (missing) folders.
        for (d in listOf(pc, thor, phone, laptop)) {
            assertIs<PrepareResult.Updated>(d.converge(p, ruby))
            assertTrue(d.file.readBytes().contentEquals(v1), "${d.name} has the save")
        }
        // The TV doesn't have the game: it never reports it, and isn't counted.
        val conv = assertNotNull(deck.client.convergence(p, ruby.id)).slots.single()
        assertEquals(5, conv.playing.size)
        assertEquals(5, conv.current)
        assertNull(conv.devices.single { it.name == "TV" }.state)

        // Played on the PC: the others are behind until each looks again.
        val v2 = ByteArray(0x8000) { (it % 11).toByte() }
        pc.play(p, ruby, v2, 900)
        val behind = assertNotNull(pc.client.convergence(p, ruby.id)).slots.single()
        assertEquals(1, behind.current)
        assertEquals(AppliedState.BEHIND, behind.devices.single { it.name == "Deck" }.state)

        // The Thor was offline: it catches up later, the rest now.
        for (d in listOf(deck, phone, laptop)) assertIs<PrepareResult.Updated>(d.converge(p, ruby))
        assertEquals(4, assertNotNull(pc.client.convergence(p, ruby.id)).slots.single().current)
        assertIs<PrepareResult.Updated>(thor.converge(p, ruby))
        for (d in listOf(deck, pc, thor, phone, laptop)) assertTrue(d.file.readBytes().contentEquals(v2), "${d.name} has the newest")
        assertEquals(5, assertNotNull(thor.client.convergence(p, ruby.id)).slots.single().current)

        // Nothing ping-pongs: looking again changes nothing and sends nothing.
        for (d in listOf(deck, pc, thor, phone, laptop)) {
            assertIs<PrepareResult.Ready>(d.converge(p, ruby))
            assertNull(d.device.capture(p, d.slot(ruby), 900))
        }

        // A device that joins now gets it too.
        val handheld = Device("New handheld", "ANDROID").join()
        assertIs<PrepareResult.Updated>(handheld.converge(p, ruby))
        assertTrue(handheld.file.readBytes().contentEquals(v2))
    }

    @Test
    fun `two devices that both played keep both saves, and nothing is overwritten in the background`() = runBlocking<Unit> {
        val deck = Device("Deck", "LINUX").join()
        val thor = Device("Thor", "ANDROID").join()
        val pc = Device("PC", "WINDOWS").join()
        val p = deck.client.createProfile(NewProfile("Mo", "fox")).id
        val base = ByteArray(64) { 1 }
        deck.play(p, ruby, base, 60)
        assertIs<PrepareResult.Updated>(thor.converge(p, ruby))

        // Thor plays offline; meanwhile the Deck plays and sends.
        thor.file.writeBytes(ByteArray(64) { 2 })
        deck.play(p, ruby, ByteArray(64) { 3 }, 120)

        // In the background the Thor sees a conflict and leaves its own save exactly as it is.
        assertIs<PrepareResult.Conflict>(thor.converge(p, ruby))
        assertTrue(thor.file.readBytes().all { it == 2.toByte() })
        val conv = assertNotNull(deck.client.convergence(p, ruby.id)).slots.single()
        assertEquals(AppliedState.CONFLICT, conv.devices.single { it.name == "Thor" }.state)
        // A device that didn't play takes the newest quietly.
        assertIs<PrepareResult.Updated>(pc.converge(p, ruby))
        assertTrue(pc.file.readBytes().all { it == 3.toByte() })
    }

    @Test
    fun `another person's save in the folder is never replaced in the background`() = runBlocking<Unit> {
        val deck = Device("Deck", "LINUX").join()
        val couch = Device("Couch", "LINUX").join()
        val mo = deck.client.createProfile(NewProfile("Mo", "fox")).id
        val sam = deck.client.createProfile(NewProfile("Sam", "cat")).id
        // Sam played last on the couch: Sam's save is in the folder there.
        couch.play(sam, ruby, ByteArray(32) { 5 }, 30)
        couch.device.handover(sam, couch.slot(ruby))
        // Mo saves on the Deck; the couch doesn't put Mo's save over Sam's.
        deck.play(mo, ruby, ByteArray(32) { 6 }, 30)
        assertNull(couch.converge(mo, ruby))
        assertTrue(couch.file.readBytes().all { it == 5.toByte() })
    }
}
