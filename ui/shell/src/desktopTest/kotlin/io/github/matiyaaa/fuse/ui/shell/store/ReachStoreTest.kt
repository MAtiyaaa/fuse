package io.github.matiyaaa.fuse.ui.shell.store

import io.github.matiyaaa.fuse.data.FuseData
import io.github.matiyaaa.fuse.data.db.DesktopDatabase
import io.github.matiyaaa.fuse.model.GameId
import io.github.matiyaaa.fuse.model.LibrarySourceKind
import io.github.matiyaaa.fuse.model.PlatformId
import io.github.matiyaaa.fuse.model.ScanPhase
import io.github.matiyaaa.fuse.sync.JvmSyncService
import io.github.matiyaaa.fuse.sync.NoHostLifetime
import io.github.matiyaaa.fuse.sync.SyncService
import io.github.matiyaaa.fuse.transfer.TransferStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.net.ServerSocket
import java.nio.file.Files
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Fuse Library through the app's own store: a computer hosting and a handheld connected to
 * it, each with its own library. Each sees the other's games it doesn't have (one game once,
 * whatever each device calls its file), brings one over from the other device, sees where every
 * game is and whether the copies are the same, and asks the other device to fetch a game.
 */
class ReachStoreTest {
    private lateinit var root: File
    private lateinit var scope: CoroutineScope
    private val port = ServerSocket(0).use { it.localPort }

    @BeforeTest
    fun setUp() {
        root = Files.createTempDirectory("fuse-reach-store").toFile()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @AfterTest
    fun tearDown() {
        // The host and each device's server for the others stop too, so none runs on into the next test.
        made.forEach { runCatching { it.store.sync.service?.stop() } }
        scope.cancel()
        root.deleteRecursively()
    }

    private class Device(val name: String, val store: FuseStore, val services: FakeServices, val roms: File) {
        val sync: SyncService get() = assertNotNull(store.sync.service)
    }

    private fun bytesOf(title: String) = Random(title.lowercase().filter { it.isLetter() }.take(8).hashCode()).nextBytes(40_000)

    private val made = ArrayList<Device>()

    private suspend fun device(name: String, files: List<String>): Device {
        val dir = File(root, name).apply { mkdirs() }
        val roms = File(dir, "roms/gba").apply { mkdirs() }
        files.forEach { File(roms, it).writeBytes(bytesOf(it.substringBefore('('))) }
        val cache = File(dir, "cache").apply { mkdirs() }
        val data = FuseData(DesktopDatabase.open(File(dir, "fuse.db").absolutePath))
        data.settings.update { it.copy(sync = it.sync.copy(enabled = true, hostPort = port, deviceName = name)) }
        lateinit var services: FakeServices
        services = FakeServices(data, cache, sync = { port, scope ->
            JvmSyncService(File(dir, "sync"), data.settings, services.secrets, port, "LINUX", name, "test", NoHostLifetime("test"), scope)
        })
        val store = createFuseStore(services, scope)
        store.sources.add(File(dir, "roms").absolutePath, LibrarySourceKind.ROMS_ROOT)
        withTimeout(20_000) {
            store.library.platforms.first { systems -> systems.sumOf { it.gameCount } == files.size }
            store.sources.scan.first { it.phase == ScanPhase.DONE }
        }
        return Device(name, store, services, roms).also { made += it }
    }

    private suspend fun eventually(what: String, seconds: Long = 30, check: suspend () -> Boolean) {
        withTimeout(seconds * 1000) {
            while (!check()) delay(100)
        }
        assertTrue(check(), what)
    }

    private suspend fun linked(): Pair<Device, Device> {
        val pc = device("Gaming PC", listOf("Advance Wars (USA).gba", "Golden Sun (USA).gba"))
        val deck = device("Thor", listOf("golden sun (Europe).gba", "Metroid Fusion (USA).gba"))
        pc.sync.hostHere("Gaming PC", installService = false).getOrThrow()
        val code = assertNotNull(pc.sync.newPairingCode())
        deck.sync.connect("127.0.0.1:$port", code).getOrThrow()
        eventually("both see the household's games") { pc.store.reach.state.value.supported && deck.store.reach.state.value.supported }
        return pc to deck
    }

    @Test
    fun eachDeviceSeesTheOthersGamesItDoesntHave(): Unit = runBlocking {
        val (pc, deck) = linked()
        eventually("the Thor sees the PC's game it doesn't have") { deck.store.reach.games(null).first().map { it.card.title.substringBefore(" (") } == listOf("Advance Wars") }
        eventually("the PC sees the Thor's") { pc.store.reach.games(null).first().map { it.card.title.substringBefore(" (") } == listOf("Metroid Fusion") }
        val wars = deck.store.reach.games(null).first().single()
        assertEquals(listOf("Gaming PC"), wars.holders)
        assertNotNull(wars.id.householdOnly, "shown with an id of its own")
        assertNull(wars.id.rommOnly, "never taken for a RomM game")
        assertEquals(listOf(PlatformId("gba")), deck.store.reach.systems.value.map { it.platform })
        // Its page: the game as Fuse knows it.
        val detail = withTimeout(10_000) { deck.store.library.game(wars.id).first { it != null } }
        assertEquals(PlatformId("gba"), detail!!.game.platformId)
    }

    @Test
    fun aGameComesOverWholeAndJoinsTheLibrary(): Unit = runBlocking {
        val (pc, deck) = linked()
        eventually("listed") { deck.store.reach.games(null).first().isNotEmpty() }
        val wars = deck.store.reach.games(null).first().single()
        // Every copy read through first, so the game can be checked file by file.
        eventually("the PC's files are read") { deck.store.reach.games(null).first().isNotEmpty() && pc.sync.household.mine.value.all { it.hashed } }
        assertNull(deck.store.reach.download(wars.id))
        eventually("downloaded", seconds = 60) {
            deck.store.transfers.rows.value.any { it.item.source == "reach" && it.item.status == TransferStatus.DONE }
        }
        val landed = File(deck.roms, "Advance Wars (USA).gba")
        assertContentEquals(bytesOf("Advance Wars "), landed.readBytes())
        eventually("in the Thor's library, and off its Fuse Library") {
            deck.store.library.games(GameQuery(platform = PlatformId("gba"))).first().any { it.title.startsWith("Advance Wars") } &&
                deck.store.reach.games(null).first().isEmpty()
        }
        // Where it is now: here (downloaded from the PC) and on the PC, the same bytes.
        val here = deck.store.library.games(GameQuery(platform = PlatformId("gba"))).first().first { it.title.startsWith("Advance Wars") }
        eventually("both copies checked", seconds = 60) {
            val a = deck.store.reach.availability(here.id).first()
            a != null && a.here?.check == CopyCheck.VERIFIED && a.devices.singleOrNull()?.check == CopyCheck.VERIFIED
        }
        val a = assertNotNull(deck.store.reach.availability(here.id).first())
        assertTrue(a.here!!.downloaded)
        assertEquals("Gaming PC", a.here!!.from)
        assertEquals("Gaming PC", a.devices.single().name)
    }

    @Test
    fun aDeviceIsAskedToFetchAGameAndDoes(): Unit = runBlocking {
        val (pc, deck) = linked()
        eventually("listed", seconds = 60) { pc.store.reach.games(null).first().isNotEmpty() && deck.sync.household.mine.value.all { it.hashed } }
        val metroid = deck.store.library.games(GameQuery(platform = PlatformId("gba"))).first().first { it.title.startsWith("Metroid") }
        val targets = deck.store.reach.sendTargets(metroid.id)
        assertEquals(SendState.HAS_IT, targets.first { it.device == deck.sync.household.self }.state)
        assertEquals(SendState.READY, targets.first { it.name == "Gaming PC" }.state)
        assertNull(deck.store.reach.sendTo(metroid.id, targets.first { it.name == "Gaming PC" }.device))
        eventually("the PC fetched it", seconds = 60) { File(pc.roms, "Metroid Fusion (USA).gba").isFile }
        assertContentEquals(bytesOf("Metroid Fusion "), File(pc.roms, "Metroid Fusion (USA).gba").readBytes())
        eventually("the Thor hears it is done") { deck.store.reach.requests.value.any { it.state == io.github.matiyaaa.fuse.sync.DeviceCommand.DONE } }
    }

    @Test
    fun idsForOtherDevicesGamesNeverMeetRommsOrTheLibrarys() {
        val h = householdGameId(1)
        assertEquals(1, h.householdOnly)
        assertNull(h.rommOnly)
        assertEquals(42, rommGameId(42).rommOnly)
        assertNull(rommGameId(42).householdOnly)
        assertNull(GameId(7).householdOnly)
        assertNull(GameId(7).rommOnly)
    }
}
