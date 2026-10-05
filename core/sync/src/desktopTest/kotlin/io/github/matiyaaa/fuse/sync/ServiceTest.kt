package io.github.matiyaaa.fuse.sync

import io.github.matiyaaa.fuse.data.db.DesktopDatabase
import io.github.matiyaaa.fuse.data.settings.SecretStore
import io.github.matiyaaa.fuse.data.settings.SettingsStore
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.net.ServerSocket
import java.nio.file.Files

/** Fuse Sync as the app runs it: a host computer and a handheld, each with its own library and settings. */
class ServiceTest {
    private class Secrets : SecretStore {
        val map = HashMap<String, String>()
        override suspend fun get(key: String) = map[key]
        override suspend fun put(key: String, value: String) { map[key] = value }
        override suspend fun remove(key: String) { map.remove(key) }
    }

    /** A device's library as Fuse Sync sees it: records by game, plus settings. */
    private class Library(val games: MutableMap<String, GameRecord> = HashMap(), val settings: MutableMap<String, JsonPrimitive> = HashMap()) : ProfileDataPort {
        var writes = 0
        override suspend fun read(device: String, clock: HlcClock): ProfileMeta = synchronized(this) {
            ProfileMeta(games = games.toMap(), settings = settings.mapValues { Lww(it.value as kotlinx.serialization.json.JsonElement, Hlc.ZERO) })
        }

        /** The games as they are now (the service writes them from its own thread). */
        fun gamesNow(): Map<String, GameRecord> = synchronized(this) { games.toMap() }

        override suspend fun write(meta: ProfileMeta) = synchronized(this) {
            writes++
            games.clear()
            meta.games.forEach { (k, r) ->
                games[k] = GameRecord(r.key, playSeconds = mapOf("here" to r.totalSeconds), sessions = r.sessions, lastPlayed = r.lastPlayed,
                    favorite = r.favorite?.let { Lww(it.value, Hlc.ZERO) }, hidden = r.hidden?.let { Lww(it.value, Hlc.ZERO) })
            }
            settings.clear()
            meta.settings.forEach { (k, v) -> settings[k] = v.value as JsonPrimitive }
        }
        override suspend fun keyOf(gameId: Long): GameKey? = null

        /** The ids each game here is known by (what a real library sends: serial, then title). */
        var known: List<List<GameKey>> = emptyList()
        var aliases: Map<String, String> = emptyMap()
        override suspend fun candidates(): List<List<GameKey>> = known
        override fun useAliases(aliases: Map<String, String>) { this.aliases = aliases }
    }

    private lateinit var root: File
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val port = ServerSocket(0).use { it.localPort }
    private val ct = GameKey.of("gba", "AGB-AXVE", null, "Pokemon Ruby")

    @BeforeTest fun setUp() { root = Files.createTempDirectory("svc").toFile() }

    @AfterTest fun tearDown() {
        scope.cancel()
        root.deleteRecursively()
    }

    private fun service(name: String, library: Library, platform: String = "LINUX"): Pair<JvmSyncService, SettingsStore> {
        val settings = SettingsStore(DesktopDatabase.open(null))
        runBlocking { settings.update { it.copy(sync = it.sync.copy(enabled = true, hostPort = port, deviceName = name)) } }
        val svc = JvmSyncService(File(root, name), settings, Secrets(), library, platform, name, "test", NoHostLifetime("test"), scope, liveLookMs = 300)
        return svc to settings
    }

    @Test
    fun aHandheldAndAComputerShareOnePerson(): Unit = runBlocking {
        val pcLib = Library()
        val deckLib = Library(
            games = hashMapOf(ct.id to GameRecord(ct, playSeconds = mapOf("x" to 3_600), lastPlayed = 1_000, favorite = Lww(true, Hlc.ZERO))),
            settings = hashMapOf("appearance.themeId" to JsonPrimitive("midnight")),
        )
        val (pc, _) = service("Gaming PC", pcLib)
        val (deck, deckSettings) = service("Steam Deck", deckLib)
        // The code "Fuse Sync is ready" shows is the one another device types: it has to work.
        val ready = pc.hostHere("Gaming PC", installService = false).getOrThrow()
        val code = assertNotNull(ready.pairingCode)
        assertEquals("Gaming PC", deck.connect("127.0.0.1:$port", code).getOrThrow())
        val mo = pc.createProfile("Mo", "fox", null).getOrThrow()
        // The Deck becomes Mo: what it already had joins Mo's profile (nothing is lost).
        deck.switchTo(mo.id).getOrThrow()
        assertEquals(mo.id, deckSettings.current().sync.activeProfile)
        pc.switchTo(mo.id).getOrThrow()
        // The PC now has Mo's hour of play, favourite and theme.
        assertEquals(3_600L, pcLib.games[ct.id]?.totalSeconds)
        assertEquals(true, pcLib.games[ct.id]?.favorite?.value)
        assertEquals(JsonPrimitive("midnight"), pcLib.settings["appearance.themeId"])
        // Changed on the PC: unfavourited, a new theme.
        pcLib.games[ct.id] = pcLib.games[ct.id]!!.copy(favorite = Lww(false, Hlc.ZERO))
        pcLib.settings["appearance.themeId"] = JsonPrimitive("dawn")
        pc.syncNow().getOrThrow()
        deck.syncNow().getOrThrow()
        assertEquals(false, deckLib.games[ct.id]?.favorite?.value)
        assertEquals(JsonPrimitive("dawn"), deckLib.settings["appearance.themeId"])
        // Play time on both, then together.
        val roms = File(root, "deck-roms").apply { mkdirs() }
        val rom = File(roms, "Pokemon Ruby (USA).gba")
        val q = SaveQuery(ct, "gba", rom.path.replace('\\', '/'), "mgba", title = "Pokemon Ruby")
        File(roms, "Pokemon Ruby (USA).sav").writeText("8 badges")
        deck.afterExit(q, 0, 30 * 60_000)
        // The PC's copy of the game is named differently; its save comes down before it starts.
        val pcRoms = File(root, "pc-roms").apply { mkdirs() }
        val pq = q.copy(romPath = File(pcRoms, "ruby.gba").path.replace('\\', '/'))
        assertIs<LaunchGate.Go>(pc.beforeLaunch(pq))
        assertEquals("8 badges", File(pcRoms, "ruby.sav").readText())
        pc.syncNow().getOrThrow()
        assertEquals(3_600L + 30 * 60, pcLib.games[ct.id]?.totalSeconds)
        // The Hub sees all of it: play time by device, the save, who saved it, and where it is kept.
        val report = assertNotNull(pc.report())
        val g = report.games.first { it.game == ct.id }
        assertEquals("Pokemon Ruby", g.name)
        assertEquals(3_600L + 30 * 60, g.playSeconds)
        val save = g.slots.single { it.kind == SaveKind.SAVE }.versions.first()
        assertEquals("Steam Deck", save.device)
        assertTrue(save.current)
        assertTrue(save.files.single().stored.startsWith("objects/"), save.files.single().stored)
        assertEquals("8 badges".length.toLong(), save.bytes)
        // Both play without syncing in between: a conflict, not a lost save.
        File(pcRoms, "ruby.sav").writeText("pc: elite four")
        File(roms, "Pokemon Ruby (USA).sav").writeText("deck: safari zone")
        pc.afterExit(pq, 0, 60_000)
        val gate = deck.beforeLaunch(q)
        assertIs<LaunchGate.Conflict>(gate)
        assertEquals("Gaming PC", gate.conflict.host.device)
        deck.settle(gate.conflict, keepHere = false).getOrThrow()
        assertEquals("pc: elite four", File(roms, "Pokemon Ruby (USA).sav").readText())
        // The Deck's save is in the history.
        assertTrue(deck.versions(q, SaveKind.SAVE).size >= 3)
        // Unlinking keeps everything on the device exactly as it was.
        val before = deckLib.gamesNow()
        deck.unlink().getOrThrow()
        assertEquals(before, deckLib.gamesNow())
        assertEquals("pc: elite four", File(roms, "Pokemon Ruby (USA).sav").readText())
        withTimeout(5_000) { deck.status.first { it is SyncStatus.NotSetUp } }
        pc.stop()
    }

    @Test
    fun withTheHostAwayAProfileWithoutAPinStillSwitches(): Unit = runBlocking {
        val (pc, _) = service("Gaming PC", Library())
        val deckLib = Library(games = hashMapOf(ct.id to GameRecord(ct, playSeconds = mapOf("x" to 600), lastPlayed = 1_000)))
        val (deck, deckSettings) = service("Steam Deck", deckLib)
        val code = assertNotNull(pc.hostHere("Gaming PC", installService = false).getOrThrow().pairingCode)
        deck.connect("127.0.0.1:$port", code).getOrThrow()
        val mo = pc.createProfile("Mo", "fox", null).getOrThrow()
        val sam = pc.createProfile("Sam", "owl", "2468").getOrThrow()
        deck.syncNow().getOrThrow()
        deck.switchTo(mo.id).getOrThrow()
        // The host goes away (the computer sleeps).
        pc.stop()
        // Sam has a PIN only the host can check: that waits, and says why.
        val refused = deck.switchTo(sam.id, "2468").exceptionOrNull()
        assertTrue(refused?.message.orEmpty().contains("PIN"), refused?.message)
        assertEquals(mo.id, deckSettings.current().sync.activeProfile)
        // Back to no one and to Mo again works here alone; nothing of Mo's is lost on the way.
        deck.switchTo(null).getOrThrow()
        deck.switchTo(mo.id).getOrThrow()
        assertEquals(mo.id, deckSettings.current().sync.activeProfile)
        assertEquals(600L, deckLib.gamesNow()[ct.id]?.totalSeconds)
    }

    @Test
    fun aGameKnownBySerialOnOneDeviceAndTitleOnAnotherIsOneGame(): Unit = runBlocking {
        // The Thor's copy has no serial (only its title); the PC's scan read the serial.
        val byTitle = GameKey.of("gba", null, null, "Pokemon Ruby (USA)")
        val thorLib = Library().apply { known = listOf(listOf(byTitle)) }
        val pcLib = Library().apply { known = listOf(listOf(ct, GameKey.of("gba", null, null, "Pokemon Ruby"))) }
        val (pc, _) = service("Gaming PC", pcLib)
        val (thor, _) = service("AYN Thor", thorLib, platform = "ANDROID")
        val code = assertNotNull(pc.hostHere("Gaming PC", installService = false).getOrThrow().pairingCode)
        thor.connect("127.0.0.1:$port", code).getOrThrow()
        val mo = pc.createProfile("Mo", "fox", null).getOrThrow()
        thor.switchTo(mo.id).getOrThrow()
        pc.switchTo(mo.id).getOrThrow()
        // The Thor plays first, by title.
        val thorRoms = File(root, "thor-roms").apply { mkdirs() }
        File(thorRoms, "Pokemon Ruby (USA).sav").writeText("thor: 5 badges")
        val tq = SaveQuery(byTitle, "gba", File(thorRoms, "Pokemon Ruby (USA).gba").path.replace('\\', '/'), "mgba", title = "Pokemon Ruby")
        thor.afterExit(tq, 0, 60_000)
        // Closed on the Thor, opened on the PC: the PC asks by serial and gets the Thor's save.
        val pcRoms = File(root, "pc-roms").apply { mkdirs() }
        val pq = SaveQuery(ct, "gba", File(pcRoms, "ruby.gba").path.replace('\\', '/'), "mgba", title = "Pokemon Ruby")
        assertIs<LaunchGate.Go>(pc.beforeLaunch(pq))
        assertEquals("thor: 5 badges", File(pcRoms, "ruby.sav").readText())
        // Both libraries now call it by the same id.
        assertEquals(thorLib.aliases[byTitle.id], pcLib.aliases[ct.id])
        // And back: played on the PC, the Thor gets it.
        File(pcRoms, "ruby.sav").writeText("pc: 6 badges")
        pc.afterExit(pq, 0, 60_000)
        assertIs<LaunchGate.Go>(thor.beforeLaunch(tq))
        assertEquals("pc: 6 badges", File(thorRoms, "Pokemon Ruby (USA).sav").readText())
        pc.stop()
    }

    @Test
    fun closeTheHandheldMidGameAndPlayOnTheComputer(): Unit = runBlocking {
        val pcLib = Library()
        val thorLib = Library()
        val (pc, _) = service("Gaming PC", pcLib)
        val (thor, _) = service("AYN Thor", thorLib, platform = "ANDROID")
        val code = assertNotNull(pc.hostHere("Gaming PC", installService = false).getOrThrow().pairingCode)
        thor.connect("127.0.0.1:$port", code).getOrThrow()
        val mo = pc.createProfile("Mo", "fox", null).getOrThrow()
        thor.switchTo(mo.id).getOrThrow()
        pc.switchTo(mo.id).getOrThrow()
        val thorRoms = File(root, "thor-roms").apply { mkdirs() }
        val tq = SaveQuery(ct, "gba", File(thorRoms, "ruby.gba").path.replace('\\', '/'), "mgba", title = "Pokemon Ruby")
        val pcRoms = File(root, "pc-roms").apply { mkdirs() }
        val pq = tq.copy(romPath = File(pcRoms, "ruby.gba").path.replace('\\', '/'))
        // The Thor starts the game; while it runs, the game saves.
        assertIs<LaunchGate.Go>(thor.beforeLaunch(tq))
        assertEquals("Pokemon Ruby", thor.nowPlaying.value)
        val started = System.currentTimeMillis()
        thor.playing(tq, started)
        File(thorRoms, "ruby.sav").writeText("thor: saved at the pokemon center")
        // The save leaves while the game still runs (the lid closes now; the game never stops there).
        val sent = withTimeout(10_000) { thor.notices.first { it is SyncNotice.Sent } }
        assertTrue((sent as SyncNotice.Sent).live)
        // The PC sees the Thor on it, and what to expect.
        val busy = assertIs<LaunchGate.Busy>(pc.beforeLaunch(pq))
        assertEquals("AYN Thor", busy.device)
        assertTrue(busy.playing)
        assertNotNull(busy.lastSave)
        assertEquals("AYN Thor", pc.busyWith(pq)?.device)
        // Playing on with the newest save: the Thor's.
        assertIs<LaunchGate.Go>(pc.beforeLaunch(pq, waitForOthers = false))
        assertEquals("thor: saved at the pokemon center", File(pcRoms, "ruby.sav").readText())
        // Once the Thor is done, nobody is on it.
        thor.afterExit(tq, started, System.currentTimeMillis())
        assertEquals(null, thor.nowPlaying.value)
        assertEquals(null, pc.busyWith(pq))
        pc.stop()
    }

    @Test
    fun savedTwiceBeforeEitherWentUpIsOneStepNotAFork(): Unit = runBlocking {
        val pcLib = Library()
        val (pc, _) = service("Gaming PC", pcLib)
        val (deck, _) = service("Steam Deck", Library())
        val code = assertNotNull(pc.hostHere("Gaming PC", installService = false).getOrThrow().pairingCode)
        deck.connect("127.0.0.1:$port", code).getOrThrow()
        val mo = pc.createProfile("Mo", "fox", null).getOrThrow()
        deck.switchTo(mo.id).getOrThrow()
        pc.switchTo(mo.id).getOrThrow()
        val roms = File(root, "deck-roms").apply { mkdirs() }
        val q = SaveQuery(ct, "gba", File(roms, "ruby.gba").path.replace('\\', '/'), "mgba", title = "Pokemon Ruby")
        val started = System.currentTimeMillis()
        deck.playing(q, started)
        File(roms, "ruby.sav").writeText("one")
        withTimeout(10_000) { deck.notices.first { it is SyncNotice.Sent } }
        File(roms, "ruby.sav").writeText("two")
        withTimeout(10_000) { deck.notices.first { it is SyncNotice.Sent } }
        deck.afterExit(q, started, System.currentTimeMillis())
        // On the PC: the newest, no conflict.
        val pcRoms = File(root, "pc-roms").apply { mkdirs() }
        assertIs<LaunchGate.Go>(pc.beforeLaunch(q.copy(romPath = File(pcRoms, "ruby.gba").path.replace('\\', '/'))))
        assertEquals("two", File(pcRoms, "ruby.sav").readText())
        pc.stop()
    }

    @Test
    fun turnedOffADeviceForgetsItsHostButKeepsItsOwn(): Unit = runBlocking {
        val (pc, _) = service("Gaming PC", Library())
        val deckLib = Library(
            games = hashMapOf(ct.id to GameRecord(ct, playSeconds = mapOf("x" to 60), lastPlayed = 1_000)),
            settings = hashMapOf("appearance.themeId" to JsonPrimitive("midnight")),
        )
        val (deck, deckSettings) = service("Steam Deck", deckLib)
        val code = assertNotNull(pc.hostHere("Gaming PC", installService = false).getOrThrow().pairingCode)
        deck.connect("127.0.0.1:$port", code).getOrThrow()
        val mo = pc.createProfile("Mo", "fox", null).getOrThrow()
        deck.switchTo(mo.id).getOrThrow()
        val before = deckLib.gamesNow()
        deck.setEnabled(false)
        // Forgotten: no host, no profiles, no profile in use.
        val s = deckSettings.current().sync
        assertEquals("", s.role)
        assertEquals("", s.activeProfile)
        assertEquals("", s.hostName)
        assertTrue(deck.profiles.value.isEmpty())
        assertIs<SyncStatus.Off>(deck.status.value)
        // Its own stays exactly as it was.
        assertEquals(before, deckLib.gamesNow())
        assertEquals(JsonPrimitive("midnight"), deckLib.settings["appearance.themeId"])
        // On again: a fresh start, set up from nothing.
        deck.setEnabled(true)
        assertIs<SyncStatus.NotSetUp>(deck.status.value)
        pc.stop()
    }

    @Test
    fun theHostsSavesMoveToAnotherFolderAndCanBeDeleted(): Unit = runBlocking {
        val (pc, pcSettings) = service("Gaming PC", Library())
        val (deck, _) = service("Steam Deck", Library())
        val code = assertNotNull(pc.hostHere("Gaming PC", installService = false).getOrThrow().pairingCode)
        deck.connect("127.0.0.1:$port", code).getOrThrow()
        val mo = pc.createProfile("Mo", "fox", null).getOrThrow()
        deck.switchTo(mo.id).getOrThrow()
        pc.switchTo(mo.id).getOrThrow()
        val roms = File(root, "deck-roms").apply { mkdirs() }
        val q = SaveQuery(ct, "gba", File(roms, "ruby.gba").path.replace('\\', '/'), "mgba", title = "Pokemon Ruby")
        File(roms, "ruby.sav").writeText("before the move")
        deck.afterExit(q, 0, 60_000)
        // To a bigger drive: copied, checked, and the host carries on from there.
        val drive = File(root, "big-drive/fuse-saves")
        assertEquals(drive.path, pc.moveHostData(drive.path).getOrThrow())
        assertEquals(drive.path.replace('\\', '/'), pcSettings.current().sync.hostDataDir)
        assertTrue(File(drive, "profiles").exists() || drive.listFiles().orEmpty().isNotEmpty())
        assertTrue(!File(root, "Gaming PC/host").exists())
        // Not into a folder that already has things in it.
        val busy = File(root, "busy").apply { mkdirs(); File(this, "x").writeText("x") }
        assertTrue(pc.moveHostData(busy.path).isFailure)
        // The Deck's save is still there, from the new folder.
        val pcRoms = File(root, "pc-roms").apply { mkdirs() }
        assertIs<LaunchGate.Go>(pc.beforeLaunch(q.copy(romPath = File(pcRoms, "ruby.gba").path.replace('\\', '/')), waitForOthers = false))
        assertEquals("before the move", File(pcRoms, "ruby.sav").readText())
        // Deleted: everyone's saves on this computer go; the Deck keeps its own.
        pc.deleteHost().getOrThrow()
        assertTrue(!drive.exists())
        assertEquals("", pcSettings.current().sync.role)
        assertEquals("before the move", File(roms, "ruby.sav").readText())
        assertTrue(deck.syncNow().isFailure)
        pc.stop()
    }

    @Test
    fun withFuseSyncOffNothingHappens(): Unit = runBlocking {
        val settings = SettingsStore(DesktopDatabase.open(null))
        val lib = Library()
        val svc = JvmSyncService(File(root, "off"), settings, Secrets(), lib, "LINUX", "PC", "test", NoHostLifetime("test"), scope)
        withTimeout(5_000) { svc.status.first { it is SyncStatus.Off } }
        assertIs<LaunchGate.Go>(svc.beforeLaunch(SaveQuery(ct, "gba", "/x.gba", "mgba")))
        svc.afterExit(SaveQuery(ct, "gba", "/x.gba", "mgba"), 0, 1)
        assertEquals(0, lib.writes)
        assertTrue(svc.profiles.value.isEmpty())
    }
}
