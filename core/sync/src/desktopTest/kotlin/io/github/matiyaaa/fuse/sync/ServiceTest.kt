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
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
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
    private class Library(
        val games: MutableMap<String, GameRecord> = HashMap(),
        val settings: MutableMap<String, JsonPrimitive> = HashMap(),
        val collections: MutableMap<String, CollectionRecord> = HashMap(),
        /** When the person chose a setting here; a setting not listed was never chosen (Fuse's default). */
        val chosen: MutableMap<String, Hlc> = HashMap(),
        /** Games this library has whatever the profile's records say, as a real one keeps its games (with nothing set). */
        val installed: List<GameKey> = emptyList(),
    ) : ProfileDataPort {
        var writes = 0

        init {
            installed.forEach { games.putIfAbsent(it.id, GameRecord(it)) }
        }

        override suspend fun read(device: String, clock: HlcClock): ProfileMeta = synchronized(this) {
            ProfileMeta(games = games.toMap(), collections = collections.toMap(), settings = settings.mapValues { (k, v) -> Lww(v as kotlinx.serialization.json.JsonElement, chosen[k] ?: Hlc.ZERO) })
        }

        /** The person changes a game here (as the library's own screens do). */
        fun edit(key: GameKey, change: (GameRecord) -> GameRecord) = synchronized(this) {
            games[key.id] = change(games[key.id] ?: GameRecord(key))
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
            installed.forEach { games.putIfAbsent(it.id, GameRecord(it)) }
            settings.clear()
            meta.settings.forEach { (k, v) -> settings[k] = v.value as JsonPrimitive }
            collections.clear()
            meta.collections.filterValues { it.deleted?.value != true }.forEach { (k, c) -> collections[k] = c }
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

    private fun service(name: String, library: Library, platform: String = "LINUX", home: File? = null): Pair<JvmSyncService, SettingsStore> {
        val settings = SettingsStore(DesktopDatabase.open(null))
        runBlocking { settings.update { it.copy(sync = it.sync.copy(enabled = true, hostPort = port, deviceName = name)) } }
        val files = home?.let { FileSaveEnvironment(platform, it.path.replace('\\', '/'), variables = { null }) } ?: FileSaveEnvironment(platform)
        val svc = JvmSyncService(File(root, name), settings, Secrets(), library, platform, name, "test", NoHostLifetime("test"), scope, files = files, liveLookMs = 300)
        return svc to settings
    }

    @Test
    fun aGameDoesntStartQuietlyWithAnOlderSaveWhileTheNewerOneIsStillComing(): Unit = runBlocking {
        val (pc, _) = service("Gaming PC", Library())
        val (deck, _) = service("Steam Deck", Library())
        val code = assertNotNull(pc.hostHere("Gaming PC", installService = false).getOrThrow().pairingCode)
        deck.connect("127.0.0.1:$port", code).getOrThrow()
        val mo = pc.createProfile("Mo", "fox", null).getOrThrow()
        deck.switchTo(mo.id).getOrThrow()
        pc.switchTo(mo.id).getOrThrow()
        val roms = File(root, "deck-roms").apply { mkdirs() }
        val q = SaveQuery(ct, "gba", File(roms, "ruby.gba").path.replace('\\', '/'), "mgba", title = "Pokemon Ruby")
        File(roms, "ruby.sav").writeText("8 badges")
        deck.afterExit(q, 0, 30 * 60_000)
        // The save's file can't be had from the host for now: the PC knows of it but can't bring it down.
        val hash = SyncCrypto.sha256("8 badges".byteInputStream())
        val onHost = File(root, "Gaming PC").walkTopDown().single { it.isFile && it.name == hash.substring(2) && it.parentFile.name == hash.substring(0, 2) }
        val kept = onHost.readBytes()
        assertTrue(onHost.delete())
        val pcRoms = File(root, "pc-roms").apply { mkdirs() }
        val pq = q.copy(romPath = File(pcRoms, "ruby.gba").path.replace('\\', '/'))
        val gate = assertIs<LaunchGate.Waiting>(pc.beforeLaunch(pq))
        assertEquals(false, gate.offline)
        assertEquals("Steam Deck", gate.from)
        assertFalse(File(pcRoms, "ruby.sav").exists(), "nothing half-written here")
        // The person chose to play with this device's save (none): it starts as it is.
        assertIs<LaunchGate.Go>(pc.beforeLaunch(pq, waitForOthers = false))
        assertFalse(File(pcRoms, "ruby.sav").exists())
        // Played here meanwhile, then sent: never the newest over the Deck's, a conflict to settle instead.
        File(pcRoms, "ruby.sav").writeText("pc: 3 badges")
        pc.afterExit(pq, 0, 60_000)
        onHost.parentFile.mkdirs()
        onHost.writeBytes(kept)
        val conflict = assertIs<LaunchGate.Conflict>(pc.beforeLaunch(pq))
        assertEquals("Steam Deck", conflict.conflict.host.device)
        pc.settle(conflict.conflict, keepHere = false).getOrThrow()
        assertEquals("8 badges", File(pcRoms, "ruby.sav").readText())
        pc.stop()
    }

    @Test
    fun aBlankSaveTheEmulatorMadeAgainNeverStartsTheGameOverTheRealOne(): Unit = runBlocking {
        val (pc, _) = service("Gaming PC", Library())
        val (deck, _) = service("Steam Deck", Library())
        val code = assertNotNull(pc.hostHere("Gaming PC", installService = false).getOrThrow().pairingCode)
        deck.connect("127.0.0.1:$port", code).getOrThrow()
        val mo = pc.createProfile("Mo", "fox", null).getOrThrow()
        deck.switchTo(mo.id).getOrThrow()
        val roms = File(root, "deck-roms").apply { mkdirs() }
        val q = SaveQuery(ct, "gba", File(roms, "ruby.gba").path.replace('\\', '/'), "mgba", title = "Pokemon Ruby")
        val sav = File(roms, "ruby.sav")
        val real = ByteArray(8192) { if (it < 16) (it + 1).toByte() else 0 }
        sav.writeBytes(real)
        deck.afterExit(q, 0, 30 * 60_000)
        // The emulator made its save memory again (all 0xFF): no progress, and never sent.
        sav.writeBytes(ByteArray(8192) { 0xFF.toByte() })
        deck.afterExit(q, 0, 60_000)
        // The next play puts the real save back in place before the game starts, with nothing to choose.
        assertIs<LaunchGate.Go>(deck.beforeLaunch(q))
        assertTrue(sav.readBytes().contentEquals(real), "the real save is in place")
        // The blank one wasn't kept in the history as a copy for safety: it held nothing.
        val versions = deck.versions(q, SaveKind.SAVE)
        assertTrue(versions.none { it.reason == RevisionReason.BEFORE_RESTORE }, versions.toString())
        pc.stop()
    }

    @Test
    fun aTestRunTeachesWhereAGameSavesAndPutsThePersonsOwnSaveThere(): Unit = runBlocking {
        val (pc, pcSettings) = service("Gaming PC", Library())
        val (deck, _) = service("Steam Deck", Library())
        val code = assertNotNull(pc.hostHere("Gaming PC", installService = false).getOrThrow().pairingCode)
        deck.connect("127.0.0.1:$port", code).getOrThrow()
        val mo = pc.createProfile("Mo", "fox", null).getOrThrow()
        deck.switchTo(mo.id).getOrThrow()
        pc.switchTo(mo.id).getOrThrow()
        val deckRoms = File(root, "deck-roms").apply { mkdirs() }
        File(deckRoms, "ruby.sav").writeText("8 badges")
        deck.afterExit(SaveQuery(ct, "gba", File(deckRoms, "ruby.gba").path.replace('\\', '/'), "mgba", title = "Pokemon Ruby"), 0, 30 * 60_000)
        // On the PC the emulator keeps saves in a folder beside its games' folder, which Fuse didn't know.
        val pcRoms = File(root, "pc/gba/roms").apply { mkdirs() }
        val pq = SaveQuery(ct, "gba", File(pcRoms, "ruby.gba").path.replace('\\', '/'), "gba-emu", title = "Pokemon Ruby")
        val watched = pc.startSaveProbe(pq).getOrThrow()
        assertTrue(watched.any { it.endsWith("/pc/gba") }, "$watched")
        // The test run starts with nothing swapped or brought down.
        assertIs<LaunchGate.Go>(pc.beforeLaunch(pq))
        val result = async { withTimeout(20_000) { pc.probeResults.first() } }
        val saves = File(root, "pc/gba/saves")
        File(saves, "ruby.sav").apply { parentFile.mkdirs(); writeText("test save"); setLastModified(System.currentTimeMillis() + 10_000) }
        pc.afterExit(pq, 0, 60_000)
        val found = assertIs<SaveProbeResult.Found>(result.await())
        assertEquals(saves.path.replace('\\', '/'), found.where)
        assertTrue(found.learned)
        assertEquals("Your save from Steam Deck is in place", found.settled)
        // Learned as the emulator's save folder, as if chosen in Settings, Save folders.
        assertEquals(saves.path.replace('\\', '/'), pcSettings.current().sync.saveFolders["gba-emu"])
        // The person's own save is where the game reads it; the test save is kept aside, never the newest.
        assertEquals("8 badges", File(saves, "ruby.sav").readText())
        assertEquals("test save", File(assertNotNull(found.keptAt)).walkTopDown().single { it.isFile }.readText())
        assertEquals("Steam Deck", pc.versions(pq, SaveKind.SAVE).first { it.current }.device)
        // From now on it syncs like any other game.
        assertIs<LaunchGate.Go>(pc.beforeLaunch(pq))
        pc.stop()
    }

    @Test
    fun aGameWhoseSavesAnotherWayMovesIsLeftAloneByFuseOneButItsPlayTimeStillCounts(): Unit = runBlocking {
        val pcLib = Library()
        val (pc, _) = service("Gaming PC", pcLib)
        val (deck, _) = service("Steam Deck", Library())
        val code = assertNotNull(pc.hostHere("Gaming PC", installService = false).getOrThrow().pairingCode)
        deck.connect("127.0.0.1:$port", code).getOrThrow()
        val mo = pc.createProfile("Mo", "fox", null).getOrThrow()
        deck.switchTo(mo.id).getOrThrow()
        pc.switchTo(mo.id).getOrThrow()
        // RomM moves this game's saves on both (RomM, Then Fuse One): Fuse One neither sends nor brings them in.
        deck.savesMovedElsewhere(setOf(ct.id))
        pc.savesMovedElsewhere(setOf(ct.id))
        val roms = File(root, "deck-roms").apply { mkdirs() }
        val q = SaveQuery(ct, "gba", File(roms, "ruby.gba").path.replace('\\', '/'), "mgba", title = "Pokemon Ruby")
        File(roms, "ruby.sav").writeText("8 badges")
        deck.afterExit(q, 0, 30 * 60_000)
        val pcRoms = File(root, "pc-roms").apply { mkdirs() }
        val pq = q.copy(romPath = File(pcRoms, "ruby.gba").path.replace('\\', '/'))
        assertIs<LaunchGate.Go>(pc.beforeLaunch(pq))
        assertFalse(File(pcRoms, "ruby.sav").exists(), "Fuse One didn't move a save RomM moves")
        // The half hour played still counts.
        pc.syncNow().getOrThrow()
        assertEquals(30L * 60, pcLib.gamesNow()[ct.id]?.totalSeconds)
        // Fuse One moving them again (Fuse One alone): the next save goes as ever.
        deck.savesMovedElsewhere(emptySet())
        pc.savesMovedElsewhere(emptySet())
        File(roms, "ruby.sav").writeText("9 badges")
        deck.afterExit(q, 0, 31 * 60_000)
        assertIs<LaunchGate.Go>(pc.beforeLaunch(pq))
        assertEquals("9 badges", File(pcRoms, "ruby.sav").readText())
        pc.stop()
        deck.stop()
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
        // The code "Fuse One is ready" shows is the one another device types: it has to work.
        val ready = pc.hostHere("Gaming PC", installService = false).getOrThrow()
        val code = assertNotNull(ready.pairingCode)
        assertEquals("Gaming PC", deck.connect("127.0.0.1:$port", code).getOrThrow())
        val mo = pc.createProfile("Mo", "fox", null).getOrThrow()
        // The Deck becomes Mo: what it already had joins Mo's profile (nothing is lost).
        deck.switchTo(mo.id).getOrThrow()
        assertEquals(mo.id, deckSettings.current().sync.activeProfile)
        // What the Deck brought goes up behind the switch: the PC switches once it is there.
        deck.syncNow().getOrThrow()
        pc.switchTo(mo.id).getOrThrow()
        // The PC now has Mo's hour of play, favourite and theme.
        assertEquals(3_600L, pcLib.games[ct.id]?.totalSeconds)
        assertEquals(true, pcLib.games[ct.id]?.favorite?.value)
        assertEquals(JsonPrimitive("midnight"), pcLib.settings["appearance.themeId"])
        // Changed on the PC: unfavourited, a new theme.
        pcLib.games[ct.id] = pcLib.games[ct.id]!!.copy(favorite = Lww(false, Hlc.ZERO))
        pcLib.settings["appearance.themeId"] = JsonPrimitive("dawn")
        pcLib.chosen["appearance.themeId"] = Hlc(System.currentTimeMillis(), 0, "pc")
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
    fun twoDevicesJoiningANewProfileKeepTheLookThePersonChose(): Unit = runBlocking {
        // The Deck's theme was chosen a minute ago; the PC's is Fuse's own, never chosen.
        val deckLib = Library(
            settings = hashMapOf("appearance.themeId" to JsonPrimitive("midnight")),
            chosen = hashMapOf("appearance.themeId" to Hlc(System.currentTimeMillis() - 60_000, 0, "deck")),
        )
        val pcLib = Library(settings = hashMapOf("appearance.themeId" to JsonPrimitive("fuse")))
        val (pc, _) = service("Gaming PC", pcLib)
        val (deck, _) = service("Steam Deck", deckLib)
        val code = assertNotNull(pc.hostHere("Gaming PC", installService = false).getOrThrow().pairingCode)
        deck.connect("127.0.0.1:$port", code).getOrThrow()
        val mo = pc.createProfile("Mo", "fox", null).getOrThrow()
        // Both become Mo before the Deck's look has gone up behind its switch.
        deck.switchTo(mo.id).getOrThrow()
        pc.switchTo(mo.id).getOrThrow()
        repeat(2) { deck.syncNow().getOrThrow(); pc.syncNow().getOrThrow() }
        // The theme someone chose is Mo's on both: the PC's own, never chosen, doesn't win over it.
        assertEquals(JsonPrimitive("midnight"), deckLib.settings["appearance.themeId"])
        assertEquals(JsonPrimitive("midnight"), pcLib.settings["appearance.themeId"])
        pc.stop()
        deck.stop()
    }

    @Test
    fun recordsThatComeInBehindASwitchAreLaidInPlaceNeverTakenBack(): Unit = runBlocking {
        val pcLib = Library(installed = listOf(ct))
        val deckLib = Library(installed = listOf(ct))
        val (pc, _) = service("Gaming PC", pcLib)
        val (deck, _) = service("Steam Deck", deckLib)
        val code = assertNotNull(pc.hostHere("Gaming PC", installService = false).getOrThrow().pairingCode)
        deck.connect("127.0.0.1:$port", code).getOrThrow()
        val mo = pc.createProfile("Mo", "fox", null).getOrThrow()
        val sam = pc.createProfile("Sam", "owl", null).getOrThrow()
        deck.syncNow().getOrThrow()
        // Each plays on the Deck once, so from then on a switch there is its own work, the host asked after.
        deck.switchTo(sam.id).getOrThrow()
        deck.switchTo(mo.id).getOrThrow()
        pc.switchTo(mo.id).getOrThrow()
        repeat(2) { deck.syncNow().getOrThrow(); pc.syncNow().getOrThrow() }
        deck.switchTo(sam.id).getOrThrow()
        // Meanwhile Mo makes the game a favourite on the PC.
        pcLib.edit(ct) { it.copy(favorite = Lww(true, Hlc.ZERO)) }
        pc.syncNow().getOrThrow()
        // Back to Mo on the Deck, from what it keeps (no favourite yet): the favourite comes in behind
        // the switch, and is laid in place, never read as the Deck taking it back.
        deck.switchTo(mo.id).getOrThrow()
        withTimeoutOrNull(10_000) { while (deckLib.gamesNow()[ct.id]?.favorite?.value != true) kotlinx.coroutines.delay(50) }
        repeat(2) { deck.syncNow().getOrThrow(); pc.syncNow().getOrThrow() }
        assertEquals(true, deckLib.gamesNow()[ct.id]?.favorite?.value, "the Deck has Mo's favourite")
        assertEquals(true, pcLib.gamesNow()[ct.id]?.favorite?.value, "the PC still has it")
        pc.stop()
        deck.stop()
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
    fun someoneThisDeviceKnowsArrivesAtOnceAndTheHostIsAskedAfter(): Unit = runBlocking {
        val (pc, _) = service("Gaming PC", Library())
        val (deck, deckSettings) = service("Steam Deck", Library())
        val code = assertNotNull(pc.hostHere("Gaming PC", installService = false).getOrThrow().pairingCode)
        deck.connect("127.0.0.1:$port", code).getOrThrow()
        val mo = pc.createProfile("Mo", "fox", null).getOrThrow()
        val sam = pc.createProfile("Sam", "owl", "2468").getOrThrow()
        deck.syncNow().getOrThrow()
        // Each plays here once, so the Deck keeps both their records and Sam's PIN (hashed).
        deck.switchTo(sam.id, "2468").getOrThrow()
        deck.switchTo(mo.id).getOrThrow()
        // From then on a switch is the Deck's own work: quick, whatever the host is doing.
        pc.stop()
        val started = System.nanoTime()
        deck.switchTo(sam.id, "2468").getOrThrow()
        val ms = (System.nanoTime() - started) / 1_000_000
        assertEquals(sam.id, deckSettings.current().sync.activeProfile)
        assertTrue(ms < 1_000, "switching to someone known here took $ms ms")
        // A wrong PIN is still refused here, and the Deck stays with Sam.
        deck.switchTo(mo.id).getOrThrow()
        assertTrue(deck.switchTo(sam.id, "1111").exceptionOrNull()?.message.orEmpty().contains("PIN"))
        assertEquals(mo.id, deckSettings.current().sync.activeProfile)
    }

    @Test
    fun aProfileDeletedElsewhereSendsTheDeviceBackAfterTheSwitch(): Unit = runBlocking {
        val (pc, _) = service("Gaming PC", Library())
        val (deck, deckSettings) = service("Steam Deck", Library())
        val code = assertNotNull(pc.hostHere("Gaming PC", installService = false).getOrThrow().pairingCode)
        deck.connect("127.0.0.1:$port", code).getOrThrow()
        val mo = pc.createProfile("Mo", "fox", null).getOrThrow()
        val sam = pc.createProfile("Sam", "owl", null).getOrThrow()
        deck.syncNow().getOrThrow()
        deck.switchTo(sam.id).getOrThrow()
        deck.switchTo(mo.id).getOrThrow()
        // Sam is deleted on the PC. If the Deck has heard, it refuses; if it hasn't, it switches from
        // what it keeps, the host then says no, and the Deck goes back to Mo.
        pc.deleteProfile(sam.id).getOrThrow()
        val r = deck.switchTo(sam.id)
        if (r.isFailure) {
            assertEquals(mo.id, deckSettings.current().sync.activeProfile)
            pc.stop()
            return@runBlocking
        }
        // A busy machine (a CI runner) takes longer than ten seconds for the host's answer.
        val back = withTimeoutOrNull(30_000) { while (deckSettings.current().sync.activeProfile != mo.id) kotlinx.coroutines.delay(50) }
        assertNotNull(back, "still on ${deckSettings.current().sync.activeProfile}: ${deck.activity.value.take(6).joinToString { it.text }}")
        pc.stop()
    }

    @Test
    fun withTheHostAwayAPinEnteredHereBeforeStillOpensTheProfile(): Unit = runBlocking {
        val (pc, _) = service("Gaming PC", Library())
        val (deck, deckSettings) = service("Steam Deck", Library())
        val code = assertNotNull(pc.hostHere("Gaming PC", installService = false).getOrThrow().pairingCode)
        deck.connect("127.0.0.1:$port", code).getOrThrow()
        val mo = pc.createProfile("Mo", "fox", null).getOrThrow()
        val sam = pc.createProfile("Sam", "owl", "2468").getOrThrow()
        deck.syncNow().getOrThrow()
        // Sam plays on the Deck once while the host is there: the PIN is checked by the host.
        deck.switchTo(sam.id, "2468").getOrThrow()
        deck.switchTo(mo.id).getOrThrow()
        pc.stop()
        // Offline, a wrong PIN is refused and the Deck stays with Mo.
        val wrong = deck.switchTo(sam.id, "1111").exceptionOrNull()
        assertTrue(wrong?.message.orEmpty().contains("PIN"), wrong?.message)
        assertEquals(mo.id, deckSettings.current().sync.activeProfile)
        // The right one opens Sam's profile without the host, and checking it alone does too.
        deck.openProfile(sam.id, "2468").getOrThrow()
        deck.switchTo(sam.id, "2468").getOrThrow()
        assertEquals(sam.id, deckSettings.current().sync.activeProfile)
    }

    @Test
    fun aThreeDsSaveMadeOnAndroidIsInPlaceOnLinuxEvenWhenLinuxCantReadTheGamesId(): Unit = runBlocking {
        // Tomodachi Life: a cartridge image on the Thor (its title id in the header), a compressed
        // .zcci on the PC (the id can't be read from it), and the PC has never played it.
        val tl = GameKey.of("3ds", null, null, "Tomodachi Life")
        val pcHome = File(root, "pc-home")
        File(pcHome, ".local/share/azahar-emu").mkdirs()
        val (pc, _) = service("Gaming PC", Library(), home = pcHome)
        val (thor, thorSettings) = service("AYN Thor", Library(), platform = "ANDROID", home = File(root, "thor-home").apply { mkdirs() })
        val thorUser = File(root, "thor-azahar")
        thorSettings.update { it.copy(sync = it.sync.copy(saveFolders = mapOf("azahar" to thorUser.path.replace('\\', '/')))) }
        val code = assertNotNull(pc.hostHere("Gaming PC", installService = false).getOrThrow().pairingCode)
        thor.connect("127.0.0.1:$port", code).getOrThrow()
        val mo = pc.createProfile("Mo", "fox", null).getOrThrow()
        thor.switchTo(mo.id).getOrThrow()
        pc.switchTo(mo.id).getOrThrow()
        val zero = "00000000000000000000000000000000"
        fun saveIn(user: File) = File(user, "sdmc/Nintendo 3DS/$zero/$zero/title/00040000/00176f00/data/00000001/system.dat")
        // The Thor's cartridge image: "NCSD" at 0x100 and the media id at 0x108, little-endian.
        val thorRom = File(root, "thor-roms/Tomodachi Life (USA).3ds").apply { parentFile.mkdirs() }
        thorRom.writeBytes(ByteArray(0x200).also { b ->
            "NCSD".encodeToByteArray().copyInto(b, 0x100)
            byteArrayOf(0x00, 0x6F, 0x17, 0x00, 0x00, 0x00, 0x04, 0x00).copyInto(b, 0x108)
        })
        saveIn(thorUser).apply { parentFile.mkdirs() }.writeText("thor: island of 40")
        val tq = SaveQuery(tl, "3ds", thorRom.path.replace('\\', '/'), "azahar", title = "Tomodachi Life")
        thor.afterExit(tq, 0, 60 * 60_000)
        // The PC can't read the compressed image's id, and it has never played: the save is still in place before it starts.
        val pcRom = File(root, "pc-roms/Tomodachi Life.zcci").apply { parentFile.mkdirs(); writeBytes(ByteArray(0x200) { 0x5A }) }
        val pq = SaveQuery(tl, "3ds", pcRom.path.replace('\\', '/'), "linux.azahar", title = "Tomodachi Life")
        assertIs<LaunchGate.Go>(pc.beforeLaunch(pq))
        assertEquals("thor: island of 40", saveIn(File(pcHome, ".local/share/azahar-emu")).readText())
        // Played on the PC: it goes back up as the next step, not a conflict, and the Thor takes it.
        saveIn(File(pcHome, ".local/share/azahar-emu")).writeText("pc: island of 41")
        pc.afterExit(pq, 0, 60 * 60_000)
        assertIs<LaunchGate.Go>(thor.beforeLaunch(tq))
        assertEquals("pc: island of 41", saveIn(thorUser).readText())
        pc.stop()
        thor.stop()
    }

    @Test
    fun aThreeDsSaveMadeOnLinuxIsInPlaceOnAndroidEvenWhenAndroidCantReadTheGamesId(): Unit = runBlocking {
        val tl = GameKey.of("3ds", null, null, "Tomodachi Life")
        val pcHome = File(root, "pc-home")
        val pcUser = File(pcHome, ".local/share/azahar-emu").apply { mkdirs() }
        val (pc, _) = service("Gaming PC", Library(), home = pcHome)
        val (thor, thorSettings) = service("AYN Thor", Library(), platform = "ANDROID", home = File(root, "thor-home").apply { mkdirs() })
        // The Thor's Azahar user folder, chosen in Fuse; Azahar made its SD card when it first ran.
        val thorUser = File(root, "thor-azahar").apply { File(this, "sdmc/Nintendo 3DS").mkdirs() }
        thorSettings.update { it.copy(sync = it.sync.copy(saveFolders = mapOf("azahar" to thorUser.path.replace('\\', '/')))) }
        val code = assertNotNull(pc.hostHere("Gaming PC", installService = false).getOrThrow().pairingCode)
        thor.connect("127.0.0.1:$port", code).getOrThrow()
        val mo = pc.createProfile("Mo", "fox", null).getOrThrow()
        thor.switchTo(mo.id).getOrThrow()
        pc.switchTo(mo.id).getOrThrow()
        val zero = "00000000000000000000000000000000"
        fun saveIn(user: File) = File(user, "sdmc/Nintendo 3DS/$zero/$zero/title/00040000/00176f00/data/00000001/system.dat")
        // The PC has the cartridge image (its id readable) and plays first.
        val pcRom = File(root, "pc-roms/Tomodachi Life (USA).cci").apply { parentFile.mkdirs() }
        pcRom.writeBytes(ByteArray(0x200).also { b ->
            "NCSD".encodeToByteArray().copyInto(b, 0x100)
            byteArrayOf(0x00, 0x6F, 0x17, 0x00, 0x00, 0x00, 0x04, 0x00).copyInto(b, 0x108)
        })
        saveIn(pcUser).apply { parentFile.mkdirs() }.writeText("pc: island of 12")
        val pq = SaveQuery(tl, "3ds", pcRom.path.replace('\\', '/'), "linux.azahar", title = "Tomodachi Life")
        pc.afterExit(pq, 0, 60 * 60_000)
        // The Thor has a compressed image and has never played it here.
        val thorRom = File(root, "thor-roms/Tomodachi Life.zcci").apply { parentFile.mkdirs(); writeBytes(ByteArray(0x200) { 0x5A }) }
        val tq = SaveQuery(tl, "3ds", thorRom.path.replace('\\', '/'), "azahar", title = "Tomodachi Life")
        assertIs<LaunchGate.Go>(thor.beforeLaunch(tq))
        assertEquals("pc: island of 12", saveIn(thorUser).readText())
        pc.stop()
        thor.stop()
    }

    @Test
    fun aTitleOnlyCopyCannotAcquireASerialIdentifiedGamesSaveWithoutEvidence(): Unit = runBlocking {
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
        // A similar cleaned title alone does not prove these copies share compatible saves.
        val pcRoms = File(root, "pc-roms").apply { mkdirs() }
        val pq = SaveQuery(ct, "gba", File(pcRoms, "ruby.gba").path.replace('\\', '/'), "mgba", title = "Pokemon Ruby")
        assertIs<LaunchGate.Go>(pc.beforeLaunch(pq))
        assertTrue(!File(pcRoms, "ruby.sav").exists())
        assertTrue(thorLib.aliases[byTitle.id] != pcLib.aliases[ct.id])
        File(pcRoms, "ruby.sav").writeText("pc: 6 badges")
        pc.afterExit(pq, 0, 60_000)
        assertIs<LaunchGate.Go>(thor.beforeLaunch(tq))
        assertEquals("thor: 5 badges", File(thorRoms, "Pokemon Ruby (USA).sav").readText())
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
        // Listening before the save is written, so a quick send is never missed; a slow machine gets time.
        val sending = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { withTimeout(30_000) { thor.notices.first { it is SyncNotice.Sent } } }
        File(thorRoms, "ruby.sav").writeText("thor: saved at the pokemon center")
        // The save leaves while the game still runs (the lid closes now; the game never stops there).
        val sent = sending.await()
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
    fun aGameOpenToJoinReachesTheOtherDevicesWhileItIsPlayedAndNoLonger(): Unit = runBlocking {
        val (pc, _) = service("Gaming PC", Library())
        val (thor, _) = service("AYN Thor", Library(), platform = "ANDROID")
        val (deck, _) = service("Steam Deck", Library())
        val code = assertNotNull(pc.hostHere("Gaming PC", installService = false).getOrThrow().pairingCode)
        thor.connect("127.0.0.1:$port", code).getOrThrow()
        deck.connect("127.0.0.1:$port", assertNotNull(pc.newPairingCode())).getOrThrow()
        val mo = pc.createProfile("Mo", "fox", null).getOrThrow()
        val ana = pc.createProfile("Ana", "owl", null).getOrThrow()
        thor.switchTo(mo.id).getOrThrow()
        deck.switchTo(ana.id).getOrThrow()
        val tq = SaveQuery(ct, "gba", File(root, "thor/ruby.gba").path.replace('\\', '/'), "mgba", title = "Pokemon Ruby")
        val dq = tq.copy(romPath = File(root, "deck/ruby.gba").path.replace('\\', '/'))
        assertTrue(deck.hostedFor(dq).isEmpty(), "nobody hosts it yet")

        // The Deck hears of it as soon as the host does, without asking.
        val heard = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { withTimeout(30_000) { deck.notices.first { it is SyncNotice.Hosting } } }
        val hosting = Hosting("netplay", "retroarch", listOf("192.168.1.31"), 55435)
        thor.hostNext(tq, hosting)
        assertIs<LaunchGate.Go>(thor.beforeLaunch(tq))
        val started = System.currentTimeMillis()
        thor.playing(tq, started)
        val notice = heard.await() as SyncNotice.Hosting
        assertEquals(SyncNotice.Hosting("Mo", "AYN Thor", "Pokemon Ruby"), notice)
        val open = deck.hostedFor(dq).single()
        assertEquals("AYN Thor", open.deviceName)
        assertEquals(hosting, open.hosting)
        assertEquals(listOf(open), deck.together.value)
        // Playing it alone on another device isn't hosting.
        assertTrue(thor.hostedFor(tq).isEmpty(), "a device never sees its own")

        // Once the Thor is done with it, it isn't open any more.
        thor.afterExit(tq, started, System.currentTimeMillis())
        assertTrue(deck.hostedFor(dq).isEmpty())
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
        // Listening before each save is written, so a quick send is never missed; a slow machine gets time.
        val first = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { withTimeout(30_000) { deck.notices.first { it is SyncNotice.Sent } } }
        File(roms, "ruby.sav").writeText("one")
        first.await()
        val second = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { withTimeout(30_000) { deck.notices.first { it is SyncNotice.Sent } } }
        File(roms, "ruby.sav").writeText("two")
        second.await()
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
        deck.setEnabled(false, keepProfiles = false)
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
    fun aSaveMadeElsewhereIsInPlaceWithoutLaunchingTheGame(): Unit = runBlocking {
        val (pc, _) = service("Gaming PC", Library())
        val (deck, _) = service("Steam Deck", Library())
        val code = assertNotNull(pc.hostHere("Gaming PC", installService = false).getOrThrow().pairingCode)
        deck.connect("127.0.0.1:$port", code).getOrThrow()
        val mo = pc.createProfile("Mo", "fox", null).getOrThrow()
        deck.switchTo(mo.id).getOrThrow()
        pc.switchTo(mo.id).getOrThrow()
        // The PC keeps Ruby in a folder that doesn't exist yet.
        val pcRoms = File(root, "pc/Games/GBA")
        val pcQuery = SaveQuery(ct, "gba", File(pcRoms, "Pokemon Ruby.gba").path.replace('\\', '/'), "mgba", title = "Pokemon Ruby")
        pc.saveQueries { id -> pcQuery.takeIf { id == ct.id } }
        // Played on the Deck.
        val roms = File(root, "deck-roms").apply { mkdirs() }
        val q = SaveQuery(ct, "gba", File(roms, "ruby.gba").path.replace('\\', '/'), "mgba", title = "Pokemon Ruby")
        File(roms, "ruby.sav").writeText("made on the deck")
        deck.afterExit(q, 0, 60_000)
        // The PC brings it in by itself, folders and all, and the host knows both are current.
        val target = File(pcRoms, "Pokemon Ruby.sav")
        val until = System.currentTimeMillis() + 20_000
        while (!target.isFile && System.currentTimeMillis() < until) kotlinx.coroutines.delay(50)
        assertEquals("made on the deck", target.readText())
        var conv: Convergence? = null
        while (System.currentTimeMillis() < until) {
            conv = deck.convergence(ct)
            if (conv?.slots?.singleOrNull()?.current == 2) break
            kotlinx.coroutines.delay(50)
        }
        assertEquals(2, conv?.slots?.single()?.current)
        pc.stop()
        deck.stop()
    }

    @Test
    fun aGameThatArrivesLaterGetsItsSaveAtOnce(): Unit = runBlocking {
        val (pc, _) = service("Gaming PC", Library())
        val (deck, _) = service("Steam Deck", Library())
        val code = assertNotNull(pc.hostHere("Gaming PC", installService = false).getOrThrow().pairingCode)
        deck.connect("127.0.0.1:$port", code).getOrThrow()
        val mo = pc.createProfile("Mo", "fox", null).getOrThrow()
        deck.switchTo(mo.id).getOrThrow()
        pc.switchTo(mo.id).getOrThrow()
        // The PC doesn't have Ruby yet when the Deck saves.
        val pcRoms = File(root, "pc/Games/GBA")
        val pcQuery = SaveQuery(ct, "gba", File(pcRoms, "Pokemon Ruby.gba").path.replace('\\', '/'), "mgba", title = "Pokemon Ruby")
        var hasIt = false
        pc.saveQueries { id -> pcQuery.takeIf { id == ct.id && hasIt } }
        val roms = File(root, "deck-roms").apply { mkdirs() }
        val q = SaveQuery(ct, "gba", File(roms, "ruby.gba").path.replace('\\', '/'), "mgba", title = "Pokemon Ruby")
        File(roms, "ruby.sav").writeText("made on the deck")
        deck.afterExit(q, 0, 60_000)
        kotlinx.coroutines.delay(1_500)
        val target = File(pcRoms, "Pokemon Ruby.sav")
        assertTrue(!target.exists(), "no game here, so no save")
        // Downloaded: its save is put in place at once, before it is ever launched here.
        hasIt = true
        pc.gamesArrived(listOf(ct.id))
        val until = System.currentTimeMillis() + 20_000
        while (!target.isFile && System.currentTimeMillis() < until) kotlinx.coroutines.delay(50)
        assertEquals("made on the deck", target.readText())
        pc.stop()
        deck.stop()
    }

    @Test
    fun aDeviceThatDoesntTakeAPersonsSavesNeverGetsThemAndOneThatDoesntSyncThemNeverSendsThem(): Unit = runBlocking {
        val (pc, _) = service("Gaming PC", Library())
        val (deck, _) = service("Steam Deck", Library())
        val code = assertNotNull(pc.hostHere("Gaming PC", installService = false).getOrThrow().pairingCode)
        deck.connect("127.0.0.1:$port", code).getOrThrow()
        val mo = pc.createProfile("Mo", "fox", null).getOrThrow()
        deck.switchTo(mo.id).getOrThrow()
        pc.switchTo(mo.id).getOrThrow()
        val pcRoms = File(root, "pc/Games/GBA").apply { mkdirs() }
        val pcQuery = SaveQuery(ct, "gba", File(pcRoms, "Pokemon Ruby.gba").path.replace('\\', '/'), "mgba", title = "Pokemon Ruby")
        pc.saveQueries { id -> pcQuery.takeIf { id == ct.id } }
        // The PC never takes Mo's saves from elsewhere.
        pc.saveChoices(pullOff = setOf(mo.id), off = emptySet())
        val roms = File(root, "deck-roms").apply { mkdirs() }
        val q = SaveQuery(ct, "gba", File(roms, "ruby.gba").path.replace('\\', '/'), "mgba", title = "Pokemon Ruby")
        File(roms, "ruby.sav").writeText("made on the deck")
        deck.afterExit(q, 0, 60_000)
        kotlinx.coroutines.delay(1_500)
        assertTrue(!File(pcRoms, "Pokemon Ruby.sav").exists(), "the PC doesn't take Mo's save by itself")
        // Nor when the game starts there: it plays with what the PC has.
        File(pcRoms, "Pokemon Ruby.sav").writeText("the pc's own")
        assertIs<LaunchGate.Go>(pc.beforeLaunch(pcQuery))
        assertEquals("the pc's own", File(pcRoms, "Pokemon Ruby.sav").readText())
        // It still sends Mo's saves made on it.
        pc.afterExit(pcQuery, 0, 60_000)
        // (It never took the Deck's, so it doesn't follow on from it: it is kept beside it, not over it.)
        assertTrue(deck.versions(q, SaveKind.SAVE).any { it.device == "Gaming PC" }, "the PC's save reached the host")

        // The Deck stops syncing Mo's saves at all: a new one stays on the Deck.
        deck.saveChoices(pullOff = emptySet(), off = setOf(mo.id))
        val before = deck.versions(q, SaveKind.SAVE).size
        File(roms, "ruby.sav").writeText("kept on the deck")
        deck.afterExit(q, 0, 120_000)
        assertEquals(before, deck.versions(q, SaveKind.SAVE).size, "nothing new went up from the Deck")
        // And when it starts there, nothing from elsewhere replaces it.
        assertIs<LaunchGate.Go>(deck.beforeLaunch(q))
        assertEquals("kept on the deck", File(roms, "ruby.sav").readText())
        pc.stop()
        deck.stop()
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
    fun aHostInAFolderThatHoldsOtherThingsNeverTouchesThem(): Unit = runBlocking {
        val (pc, pcSettings) = service("Gaming PC", Library())
        // The person picks a whole drive for the host's saves.
        val drive = File(root, "media/BigDrive").apply { mkdirs() }
        val photos = File(drive, "Photos/holiday.jpg").apply { parentFile.mkdirs(); writeText("sunset") }
        val notes = File(drive, "notes.txt").apply { writeText("keep") }
        pcSettings.update { it.copy(sync = it.sync.copy(hostDataDir = drive.path)) }
        pc.hostHere("Gaming PC", installService = false).getOrThrow()
        // Its files go in a folder of their own there, and never in the drive itself: not even from
        // Fuse's own start, which runs while the host is being set up.
        val own = File(drive, JvmSyncService.HOST_FOLDER)
        assertEquals(own.path.replace('\\', '/'), pcSettings.current().sync.hostDataDir)
        assertTrue(File(own, "host.json").isFile)
        assertEquals(setOf("Photos", "notes.txt", JvmSyncService.HOST_FOLDER), drive.list()!!.toSet())
        // Moved and then deleted: the drive's own things stay exactly where they were.
        val elsewhere = File(root, "elsewhere")
        pc.moveHostData(elsewhere.path).getOrThrow()
        assertTrue(photos.isFile && notes.isFile)
        pc.deleteHost().getOrThrow()
        assertEquals("sunset", photos.readText())
        assertEquals("keep", notes.readText())
        assertTrue(!File(elsewhere, "host.json").exists())
        pc.stop()
    }

    @Test
    fun recordsTurnedOffOnOneDeviceNeverDeleteTheProfilesCollections(): Unit = runBlocking {
        val pcLib = Library()
        val deckLib = Library(collections = hashMapOf("c1" to CollectionRecord("c1", Lww("RPGs", Hlc.ZERO), members = mapOf(ct.id to Lww(true, Hlc.ZERO)))))
        val (pc, _) = service("Gaming PC", pcLib)
        val (deck, deckSettings) = service("Steam Deck", deckLib)
        val code = assertNotNull(pc.hostHere("Gaming PC", installService = false).getOrThrow().pairingCode)
        deck.connect("127.0.0.1:$port", code).getOrThrow()
        val mo = pc.createProfile("Mo", "fox", null).getOrThrow()
        // The Deck's collection becomes Mo's, and reaches the PC.
        deck.switchTo(mo.id).getOrThrow()
        deck.syncNow().getOrThrow()
        pc.switchTo(mo.id).getOrThrow()
        assertEquals("RPGs", pcLib.collections["c1"]?.name?.value)
        // On the Deck, records stop syncing: its library then reports no collections at all.
        deckSettings.update { it.copy(sync = it.sync.copy(records = false)) }
        kotlinx.coroutines.delay(500)
        synchronized(deckLib) { deckLib.collections.clear() }
        deck.syncNow().getOrThrow()
        pc.syncNow().getOrThrow()
        // Nothing of that reached the profile: the collection is still everyone's.
        assertEquals("RPGs", pcLib.collections["c1"]?.name?.value)
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

    @Test
    fun aPlayTeachesFuseWhereASaveWithAnUnknownIdLives(): Unit = runBlocking {
        // PPSSPP names saves by a game's id, which Fuse doesn't know for this game: its first play here
        // shows which save folder is its, and from then on the save is kept in step.
        val home = File(root, "home").apply { mkdirs() }
        val savedata = File(home, ".config/ppsspp/PSP/SAVEDATA").apply { mkdirs() }
        File(savedata, "NPUH10001DATA/DATA.BIN").apply { parentFile.mkdirs(); writeText("another game") }.setLastModified(1_000_000)
        File(savedata, "NPUH10001DATA").setLastModified(1_000_000)
        val (pc, _) = service("Gaming PC", Library(), home = home)
        pc.hostHere("Gaming PC", installService = false).getOrThrow()
        val game = GameKey.of("psp", null, null, "Patapon")
        val q = SaveQuery(game, "psp", File(root, "Patapon.iso").path.replace('\\', '/'), "ppsspp", title = "Patapon")
        val notices = java.util.Collections.synchronizedList(ArrayList<SyncNotice>())
        val listening = scope.launch { pc.notices.collect { notices += it } }
        assertIs<LaunchGate.Go>(pc.beforeLaunch(q))
        // The game saves into its own folder while it runs.
        File(savedata, "UCUS98711DATA00/DATA.BIN").apply { parentFile.mkdirs(); writeText("level 3") }
        pc.afterExit(q, 0, 60_000)
        val versions = pc.versions(q, SaveKind.SAVE)
        assertEquals(1, versions.size, "the learned save was kept")
        // Next time it is found before the game starts, like any other.
        File(savedata, "UCUS98711DATA00/DATA.BIN").writeText("level 4")
        pc.afterExit(q, 0, 60_000)
        assertEquals(2, pc.versions(q, SaveKind.SAVE).size)
        listening.cancel()
        assertTrue(notices.none { it is SyncNotice.NotSynced }, notices.toString())
        pc.stop()
    }

    @Test
    fun aSaveThatCantBeKeptSaysWhyOnceAndAnUnchangedOneSaysNothing(): Unit = runBlocking {
        val home = File(root, "home").apply { mkdirs() }
        val (pc, _) = service("Gaming PC", Library(), home = home)
        pc.hostHere("Gaming PC", installService = false).getOrThrow()
        val notices = java.util.Collections.synchronizedList(ArrayList<SyncNotice>())
        val listening = scope.launch { pc.notices.collect { notices += it } }
        // DuckStation's folder isn't on this computer: the save can't be kept, and Fuse says so, once.
        val psx = SaveQuery(GameKey.of("psx", null, null, "Vagrant Story"), "psx", File(root, "vs.cue").path, "duckstation", title = "Vagrant Story")
        pc.afterExit(psx, 0, 60_000)
        pc.afterExit(psx, 0, 60_000)
        withTimeout(5_000) { while (notices.none { it is SyncNotice.NotSynced }) kotlinx.coroutines.delay(20) }
        val said = notices.filterIsInstance<SyncNotice.NotSynced>()
        assertEquals(1, said.size, said.toString())
        assertEquals("Vagrant Story", said.single().title)
        // A game whose save didn't change since it was last kept: nothing to say.
        val roms = File(root, "roms").apply { mkdirs() }
        File(roms, "Ruby.sav").writeText("8 badges")
        val gba = SaveQuery(ct, "gba", File(roms, "Ruby.gba").path.replace('\\', '/'), "mgba", title = "Pokemon Ruby")
        pc.afterExit(gba, 0, 60_000)
        pc.afterExit(gba, 0, 60_000)
        // A game that keeps its own saves (a PC game): nothing to say either.
        pc.afterExit(SaveQuery(GameKey.of("win", null, null, "Hades"), "win", "/games/Hades.exe", "steam", title = "Hades"), 0, 60_000)
        kotlinx.coroutines.delay(300)
        listening.cancel()
        assertEquals(1, notices.filterIsInstance<SyncNotice.NotSynced>().size, notices.toString())
        pc.stop()
    }

    /**
     * The host updates itself once a device runs a newer Fuse, a minute or two after it connects: the
     * device shows what it runs now within a minute, not ten, and the host's own answer to Update the
     * Fuse One Host ("already the newest") is what it shows straight away.
     */
    @Test
    fun aDeviceShowsTheFuseTheHostRunsNow(): Unit = runBlocking {
        val hostDir = File(root, "host")
        fun host(version: String) = SyncHost(HostStore(hostDir, hostName = "Gaming PC"), port, version, bind = "127.0.0.1", callsPerMinute = 100_000).start()
        var running = host("0.4.3.1")
        var now = System.currentTimeMillis()
        val settings = SettingsStore(DesktopDatabase.open(null))
        settings.update { it.copy(sync = it.sync.copy(enabled = true, hostPort = port, deviceName = "AYN Thor")) }
        val thor = JvmSyncService(File(root, "thor"), settings, Secrets(), Library(), "ANDROID", "AYN Thor", "0.4.3.4", NoHostLifetime("test"), scope, clock = { now })
        try {
            thor.connect("127.0.0.1:$port", running.newPairingCode()).getOrThrow()
            thor.syncNow().getOrThrow()
            withTimeout(10_000) { while (thor.hostFuse.value != "0.4.3.1") kotlinx.coroutines.delay(20) }

            // Asked to update, the host says it already runs the newest: that is what the device shows.
            running.onUpdateAsked = { HostUpdate(false, "The host already runs Fuse 0.4.3.3, the newest.", "0.4.3.3") }
            assertFalse(thor.updateHost().getOrThrow().started)
            assertEquals("0.4.3.3", thor.hostFuse.value)

            // The host goes back to 0.4.3.1 and then starts again on 0.4.3.4 by itself: a minute later
            // the device knows, while the host's version is older than its own. (A round just as the
            // host starts again may find it gone: the next one asks.)
            suspend fun restartedOn(version: String) {
                running.stop()
                running = host(version)
                now += 61_000
                repeat(3) { thor.syncNow() }
            }
            restartedOn("0.4.3.1")
            assertEquals("0.4.3.1", thor.hostFuse.value)
            restartedOn("0.4.3.4")
            assertEquals("0.4.3.4", thor.hostFuse.value)
            // Up to date, it is asked only every ten minutes again.
            restartedOn("0.4.3.5")
            assertEquals("0.4.3.4", thor.hostFuse.value)
        } finally {
            thor.stop()
            running.stop()
        }
    }
}
