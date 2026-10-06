package io.github.matiyaaa.fuse.sync

import io.github.matiyaaa.fuse.data.db.DesktopDatabase
import io.github.matiyaaa.fuse.data.settings.SecretStore
import io.github.matiyaaa.fuse.data.settings.SettingsStore
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.net.ServerSocket
import java.nio.file.Files

/**
 * Profiles without a host: made, switched and kept apart on one device, then taken to a host in
 * every way a household would (a host with nobody yet, one with people, the same person on two
 * devices, people leaving a host and coming back), with nothing lost on the way.
 */
class LocalProfilesTest {
    private class Secrets : SecretStore {
        val map = HashMap<String, String>()
        override suspend fun get(key: String) = map[key]
        override suspend fun put(key: String, value: String) { map[key] = value }
        override suspend fun remove(key: String) { map.remove(key) }
    }

    /** A device's library as Fuse Sync sees it: records by game. */
    private class Library(val games: MutableMap<String, GameRecord> = HashMap()) : ProfileDataPort {
        override suspend fun read(device: String, clock: HlcClock): ProfileMeta = synchronized(this) { ProfileMeta(games = games.toMap()) }

        fun now(): Map<String, GameRecord> = synchronized(this) { games.toMap() }

        override suspend fun write(meta: ProfileMeta) = synchronized(this) {
            games.clear()
            meta.games.forEach { (k, r) ->
                games[k] = GameRecord(r.key, playSeconds = mapOf("here" to r.totalSeconds), sessions = r.sessions, lastPlayed = r.lastPlayed, favorite = r.favorite?.let { Lww(it.value, Hlc.ZERO) })
            }
        }

        override suspend fun keyOf(gameId: Long): GameKey? = null
    }

    private lateinit var root: File
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val port = ServerSocket(0).use { it.localPort }
    private val ruby = GameKey.of("gba", "AGB-AXVE", null, "Pokemon Ruby")

    @BeforeTest fun setUp() { root = Files.createTempDirectory("local").toFile() }

    @AfterTest fun tearDown() {
        scope.cancel()
        root.deleteRecursively()
    }

    private class Device(val svc: JvmSyncService, val settings: SettingsStore, val lib: Library, val roms: File) {
        val rom get() = File(roms, "Pokemon Ruby.gba")
        val save get() = File(roms, "Pokemon Ruby.sav")
    }

    private fun device(name: String, enabled: Boolean, lib: Library = Library()): Device {
        val settings = SettingsStore(DesktopDatabase.open(null))
        runBlocking { settings.update { it.copy(sync = it.sync.copy(enabled = enabled, hostPort = port, deviceName = name)) } }
        val svc = JvmSyncService(File(root, name), settings, Secrets(), lib, "LINUX", name, "test", NoHostLifetime("test"), scope, liveLookMs = 300)
        return Device(svc, settings, lib, File(root, "$name-roms").apply { mkdirs() })
    }

    private fun Device.query() = SaveQuery(ruby, "gba", rom.path.replace('\\', '/'), "mgba", title = "Pokemon Ruby")

    /** [who] plays here: their save goes in place first, then what they saved is kept. */
    private suspend fun Device.play(save: String?, seconds: Long = 600) {
        assertIs<LaunchGate.Go>(svc.beforeLaunch(query()))
        if (save != null) this.save.writeText(save)
        svc.afterExit(query(), 0, seconds * 1000)
    }

    private suspend fun Device.active(): String = settings.current().sync.activeProfile

    @Test
    fun profilesWorkWithoutAHostAndKeepEachPersonsSaveApart(): Unit = runBlocking {
        val deck = device("Steam Deck", enabled = false, Library(hashMapOf(ruby.id to GameRecord(ruby, playSeconds = mapOf("x" to 1_200), lastPlayed = 1))))
        withTimeout(5_000) { deck.svc.status.first { it is SyncStatus.Off } }
        val mo = deck.svc.createProfile("Mo", "fox", null).getOrThrow()
        val sam = deck.svc.createProfile("Sam", "owl", "2468").getOrThrow()
        assertEquals(listOf("Mo", "Sam"), deck.svc.profiles.value.map { it.name })
        assertTrue(sam.protected)
        // Fuse Sync stays off: profiles need no host.
        assertIs<SyncStatus.Off>(deck.svc.status.value)
        assertTrue(deck.svc.createProfile("mo", "cat", null).isFailure, "names are one per person, case aside")
        assertTrue(deck.svc.createProfile("Kid", "cat", "12").isFailure, "a PIN is 4 or more")

        // The first person takes what this device already had: its hour of play is Mo's.
        deck.svc.switchTo(mo.id).getOrThrow()
        assertEquals(mo.id, deck.active())
        assertEquals(1_200L, deck.lib.now()[ruby.id]?.totalSeconds)
        deck.play("mo: 3 badges")

        // Sam's PIN is checked here, and a wrong one changes nothing.
        assertEquals("wrong-pin", (deck.svc.switchTo(sam.id, "1111").exceptionOrNull() as? SyncException)?.code)
        assertEquals(mo.id, deck.active())
        deck.svc.switchTo(sam.id, "2468").getOrThrow()
        // Sam starts their own game: Mo's save is put away, and Mo's play time isn't Sam's.
        assertIs<LaunchGate.Go>(deck.svc.beforeLaunch(deck.query()))
        assertFalse(deck.save.exists(), "Mo's save is parked, not Sam's to play")
        assertNull(deck.lib.now()[ruby.id])
        deck.save.writeText("sam: 1 badge")
        deck.svc.afterExit(deck.query(), 0, 60_000)

        // Back to Mo: their save and records come back exactly.
        deck.svc.switchTo(mo.id).getOrThrow()
        assertIs<LaunchGate.Go>(deck.svc.beforeLaunch(deck.query()))
        assertEquals("mo: 3 badges", deck.save.readText())
        assertEquals(1_800L, deck.lib.now()[ruby.id]?.totalSeconds)

        // Reordered, renamed and given a new PIN, all here.
        deck.svc.reorderProfiles(listOf(sam.id, mo.id)).getOrThrow()
        assertEquals(listOf("Sam", "Mo"), deck.svc.profiles.value.map { it.name })
        assertTrue(deck.svc.changeProfile(sam.id, ProfileChange(pin = "9999", currentPin = "0000")).isFailure, "the current PIN is asked for")
        deck.svc.changeProfile(sam.id, ProfileChange(name = "Samira", pin = "9999", currentPin = "2468")).getOrThrow()
        deck.svc.openProfile(sam.id, "9999").getOrThrow()

        // Everything survives a restart.
        deck.svc.stop()
        val again = JvmSyncService(File(root, "Steam Deck"), deck.settings, Secrets(), deck.lib, "LINUX", "Steam Deck", "test", NoHostLifetime("test"), scope)
        withTimeout(5_000) { again.profiles.first { it.size == 2 } }
        assertEquals(listOf("Samira", "Mo"), again.profiles.value.map { it.name })
        assertEquals(mo.id, again.activeProfile.value?.id)

        // Deleting Sam keeps their save, as plain files in the kept folder.
        again.switchTo(sam.id, "9999").getOrThrow()
        again.deleteProfile(sam.id).getOrThrow()
        assertEquals(listOf("Mo"), again.profiles.value.map { it.name })
        assertEquals("", deck.active())
        val kept = File(root, "Steam Deck/kept").walkTopDown().filter { it.isFile }.map { it.readText() }.toList()
        assertTrue("sam: 1 badge" in kept, kept.toString())
        again.stop()
    }

    @Test
    fun joiningAHostWithNobodyYetBringsEveryProfileAsItIs(): Unit = runBlocking {
        val pc = device("Gaming PC", enabled = true)
        val deck = device("Steam Deck", enabled = false)
        val mo = deck.svc.createProfile("Mo", "fox", "1357").getOrThrow()
        val sam = deck.svc.createProfile("Sam", "owl", null).getOrThrow()
        deck.svc.switchTo(mo.id, "1357").getOrThrow()
        deck.play("mo: elite four", seconds = 900)
        deck.svc.switchTo(sam.id).getOrThrow()
        deck.play("sam: route 1", seconds = 120)
        deck.svc.switchTo(mo.id, "1357").getOrThrow()

        // The host has only its Admin, which never counts: everyone goes up at once, nothing asked.
        val code = assertNotNull(pc.svc.hostHere("Gaming PC", installService = false).getOrThrow().pairingCode)
        deck.svc.connect("127.0.0.1:$port", code).getOrThrow()
        assertNull(deck.svc.merge.value)
        val onHost = withTimeout(10_000) { pc.svc.profiles.first { list -> list.count { !it.hostOnly } == 2 } }.filterNot { it.hostOnly }
        assertEquals(setOf("Mo", "Sam"), onHost.map { it.name }.toSet())
        val hostMo = onHost.first { it.name == "Mo" }
        assertTrue(hostMo.protected, "Mo's PIN went up with them")
        // Mo is still the one playing here, by the host's id now.
        assertEquals(hostMo.id, deck.active())
        assertEquals(hostMo.id, deck.svc.activeProfile.value?.id)
        deck.svc.syncNow().getOrThrow()

        // On the PC, Mo's PIN opens Mo, and their play time and save are there.
        assertTrue(pc.svc.switchTo(hostMo.id, "0000").isFailure)
        pc.svc.switchTo(hostMo.id, "1357").getOrThrow()
        assertEquals(900L, pc.lib.now()[ruby.id]?.totalSeconds)
        assertIs<LaunchGate.Go>(pc.svc.beforeLaunch(pc.query()))
        assertEquals("mo: elite four", pc.save.readText())
        // Sam's save went up as Sam's.
        val hostSam = onHost.first { it.name == "Sam" }
        pc.svc.switchTo(hostSam.id).getOrThrow()
        assertIs<LaunchGate.Go>(pc.svc.beforeLaunch(pc.query()))
        assertEquals("sam: route 1", pc.save.readText())
        pc.svc.stop()
    }

    @Test
    fun joiningAHostWithPeopleAsksWhoIsWhoAndTheSamePersonsTimeAddsUp(): Unit = runBlocking {
        val pc = device("Gaming PC", enabled = true)
        val code = assertNotNull(pc.svc.hostHere("Gaming PC", installService = false).getOrThrow().pairingCode)
        val hostMo = pc.svc.createProfile("Mo", "fox", "2468").getOrThrow()
        pc.svc.switchTo(hostMo.id, "2468").getOrThrow()
        pc.play("pc: gym 2", seconds = 600)
        pc.svc.syncNow().getOrThrow()

        val deck = device("Steam Deck", enabled = false)
        val mo = deck.svc.createProfile("mo ", "cat", null).getOrThrow()
        val kid = deck.svc.createProfile("Kid", "owl", null).getOrThrow()
        val guest = deck.svc.createProfile("Guest", "bee", null).getOrThrow()
        deck.svc.switchTo(guest.id).getOrThrow()
        deck.play("guest: just looking", seconds = 30)
        deck.svc.switchTo(mo.id).getOrThrow()
        deck.play("deck: gym 3", seconds = 300)

        deck.svc.connect("127.0.0.1:$port", code).getOrThrow()
        // The host has Mo: Fuse asks, suggesting the same person by name (case and spaces aside).
        val offer = assertNotNull(deck.svc.merge.value)
        assertEquals("Gaming PC", offer.hostName)
        assertEquals(listOf("Mo"), offer.host.map { it.name }, "the host's Admin never counts")
        assertEquals(hostMo.id, offer.suggested[mo.id])
        assertNull(offer.suggested[kid.id])
        // Until it is settled, nothing is linked and the profiles here keep working.
        assertTrue(deck.svc.profiles.value.any { it.id == kid.id })

        val choices = mapOf(mo.id to MergeChoice.Same(hostMo.id, "0000"), kid.id to MergeChoice.Add, guest.id to MergeChoice.LeaveOut)
        // Joining Mo asks for the host's Mo PIN; a wrong one changes nothing.
        assertEquals("wrong-pin", (deck.svc.bringProfiles(choices).exceptionOrNull() as? SyncException)?.code)
        assertNotNull(deck.svc.merge.value)
        assertEquals(1, pc.svc.profiles.value.count { !it.hostOnly })
        deck.svc.bringProfiles(choices + (mo.id to MergeChoice.Same(hostMo.id, "2468"))).getOrThrow()
        assertNull(deck.svc.merge.value)
        assertEquals(hostMo.id, deck.active())
        deck.svc.syncNow().getOrThrow()
        pc.svc.syncNow().getOrThrow()
        val people = withTimeout(10_000) { pc.svc.profiles.first { list -> list.count { !it.hostOnly } == 2 } }
        assertEquals(setOf("Mo", "Kid"), people.filterNot { it.hostOnly }.map { it.name }.toSet(), "Guest was left out")

        // Mo's time from both devices adds up; the PC's save stays the newest and the Deck's is a choice.
        assertEquals(900L, pc.lib.now()[ruby.id]?.totalSeconds)
        val gate = deck.svc.beforeLaunch(deck.query())
        assertIs<LaunchGate.Conflict>(gate)
        deck.svc.settle(gate.conflict, keepHere = false).getOrThrow()
        assertEquals("pc: gym 2", deck.save.readText())
        // Guest's save left with them, as plain files.
        val kept = File(root, "Steam Deck/kept").walkTopDown().filter { it.isFile }.map { it.readText() }.toList()
        assertTrue("guest: just looking" in kept, kept.toString())
        pc.svc.stop()
    }

    @Test
    fun leavingAHostKeepsThePeopleWhoPlayedHereAndComingBackJoinsThemUp(): Unit = runBlocking {
        val pc = device("Gaming PC", enabled = true)
        val code = assertNotNull(pc.svc.hostHere("Gaming PC", installService = false).getOrThrow().pairingCode)
        val mo = pc.svc.createProfile("Mo", "fox", "2468").getOrThrow()
        pc.svc.createProfile("Sam", "owl", null).getOrThrow()
        val deck = device("Steam Deck", enabled = false)
        deck.svc.connect("127.0.0.1:$port", code).getOrThrow()
        deck.svc.switchTo(mo.id, "2468").getOrThrow()
        deck.play("mo: on the deck", seconds = 300)
        deck.svc.syncNow().getOrThrow()

        // Fuse Sync off: Mo played here, so Mo stays (PIN and all); Sam never did, so Sam isn't here.
        deck.svc.setEnabled(false)
        assertIs<SyncStatus.Off>(deck.svc.status.value)
        assertEquals(listOf("Mo"), deck.svc.profiles.value.map { it.name })
        assertTrue(deck.svc.profiles.value.single().protected)
        assertEquals(mo.id, deck.active())
        assertEquals(300L, deck.lib.now()[ruby.id]?.totalSeconds)
        deck.svc.switchTo(null).getOrThrow()
        assertTrue(deck.svc.switchTo(mo.id, "1111").isFailure)
        deck.svc.switchTo(mo.id, "2468").getOrThrow()
        deck.play("mo: offline for a while", seconds = 200)

        // Back to the same host: Mo is suggested as the same person (same id), and their time joins.
        deck.svc.setEnabled(true)
        val next = assertNotNull(pc.svc.newPairingCode())
        deck.svc.connect("127.0.0.1:$port", next).getOrThrow()
        val offer = assertNotNull(deck.svc.merge.value)
        assertEquals(mo.id, offer.suggested[mo.id])
        deck.svc.bringProfiles(mapOf(mo.id to MergeChoice.Same(mo.id, "2468"))).getOrThrow()
        deck.svc.syncNow().getOrThrow()
        assertEquals(mo.id, deck.active())
        pc.svc.switchTo(mo.id, "2468").getOrThrow()
        assertEquals(500L, pc.lib.now()[ruby.id]?.totalSeconds)

        // Turning off with "forget them too": no profiles stay.
        deck.svc.setEnabled(false, keepProfiles = false)
        assertTrue(deck.svc.profiles.value.isEmpty())
        assertEquals("", deck.active())
        pc.svc.stop()
    }

    @Test
    fun stoppingJoiningKeepsTheProfilesHereAndTheHostForgetsTheDevice(): Unit = runBlocking {
        val pc = device("Gaming PC", enabled = true)
        val code = assertNotNull(pc.svc.hostHere("Gaming PC", installService = false).getOrThrow().pairingCode)
        pc.svc.createProfile("Ana", "fox", null).getOrThrow()
        val deck = device("Steam Deck", enabled = false)
        val mo = deck.svc.createProfile("Mo", "cat", null).getOrThrow()
        deck.svc.connect("127.0.0.1:$port", code).getOrThrow()
        assertNotNull(deck.svc.merge.value)
        withTimeout(10_000) { pc.svc.devices.first { list -> list.any { it.name == "Steam Deck" && !it.revoked } } }
        deck.svc.cancelMerge().getOrThrow()
        assertNull(deck.svc.merge.value)
        assertEquals(listOf(mo.id), deck.svc.profiles.value.map { it.id })
        assertTrue(deck.svc.status.value is SyncStatus.Off || deck.svc.status.value is SyncStatus.NotSetUp)
        // The host lets go of the device that stopped joining.
        withTimeout(10_000) { pc.svc.devices.first { list -> list.none { it.name == "Steam Deck" && !it.revoked } } }
        pc.svc.stop()
    }

    @Test
    fun makingThisTheHostWithProfilesKeepsPlayingAsYourself(): Unit = runBlocking {
        val pc = device("Gaming PC", enabled = false)
        val mo = pc.svc.createProfile("Mo", "fox", null).getOrThrow()
        pc.svc.switchTo(mo.id).getOrThrow()
        pc.svc.hostHere("Gaming PC", installService = false).getOrThrow()
        val people = pc.svc.profiles.value
        assertTrue(people.any { it.hostOnly }, "the host has its Admin")
        val hostMo = people.first { it.name == "Mo" }
        assertEquals(hostMo.id, pc.active(), "this computer keeps playing as Mo, not Admin")
        // The order is the household's: a reorder here is every device's.
        val admin = people.first { it.hostOnly }
        pc.svc.reorderProfiles(listOf(hostMo.id, admin.id)).getOrThrow()
        assertEquals(listOf(hostMo.id, admin.id), pc.svc.profiles.value.map { it.id })
        pc.svc.reorderProfiles(listOf(admin.id, hostMo.id)).getOrThrow()
        assertEquals(listOf(admin.id, hostMo.id), pc.svc.profiles.value.map { it.id })
        pc.svc.stop()
    }
}
