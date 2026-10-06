package io.github.matiyaaa.fuse.romm

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.github.matiyaaa.fuse.data.db.FuseDatabase
import io.github.matiyaaa.fuse.integrations.net.NetRoute
import io.github.matiyaaa.fuse.integrations.net.RouteMode
import io.github.matiyaaa.fuse.integrations.net.RoutePicker
import io.github.matiyaaa.fuse.library.PlatformCatalog
import io.github.matiyaaa.fuse.model.BiosFile
import io.github.matiyaaa.fuse.model.BiosRequirement
import io.github.matiyaaa.fuse.model.BiosState
import io.github.matiyaaa.fuse.model.BiosStatus
import io.github.matiyaaa.fuse.model.ContentKind
import io.github.matiyaaa.fuse.transfer.TransferDirection
import io.github.matiyaaa.fuse.transfer.TransferItem
import io.github.matiyaaa.fuse.transfer.TransferKind
import io.github.matiyaaa.fuse.transfer.TransferManager
import io.github.matiyaaa.fuse.transfer.TransferPlace
import io.github.matiyaaa.fuse.transfer.TransferStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import io.ktor.client.request.request
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RommTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val root = Files.createTempDirectory("fuse-romm").toFile()

    @AfterTest
    fun cleanUp() {
        scope.cancel()
        root.deleteRecursively()
    }

    private fun db(): FuseDatabase = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).let { FuseDatabase.Schema.create(it); FuseDatabase(it) }

    private fun server(): FakeRomm = FakeRomm().apply {
        games += FakeRomm.Game(1, 10, "gba", "Metroid Fusion", "Metroid Fusion (USA).gba", listOf(Triple(11L, "Metroid Fusion (USA).gba", ByteArray(5000) { 1 })))
        games += FakeRomm.Game(
            2, 20, "psx", "Final Fantasy VII", "Final Fantasy VII (USA)",
            listOf(
                Triple(21L, "Final Fantasy VII (USA) (Disc 1).chd", ByteArray(3000) { 2 }),
                Triple(22L, "Final Fantasy VII (USA) (Disc 2).chd", ByteArray(3000) { 3 }),
                Triple(23L, "Final Fantasy VII (USA) (Disc 3).chd", ByteArray(3000) { 4 }),
            ),
        )
    }

    private fun client(fake: FakeRomm, token: String = "good", local: String? = "http://home.lan", remote: String? = "https://romm.example.com", mode: RouteMode = RouteMode.AUTO): RommClient {
        val routes = RoutePicker(local, remote, mode, scope, System::currentTimeMillis, probe = { base ->
            runCatching { fake.client.get(base + "/api/heartbeat") }.isSuccess && java.net.URI(base).host !in fake.down
        }, lookEveryMs = 0)
        return RommClient(fake.client, routes, RommCredential.Token(token, fake.scopes[token].orEmpty()), "0.3.6")
    }

    private suspend fun io.ktor.client.HttpClient.get(url: String): io.ktor.client.statement.HttpResponse = request(url) { method = io.ktor.http.HttpMethod.Get }.also { if (it.status.value >= 400) error("down") }

    // ------------------------------------------------------------------ reading RomM

    @Test
    fun `a game with missing and odd fields is still read`() {
        val rom = RommParse.rom(RommParse.element("""{"id":7,"name":null,"fs_name":"Tetris (World).gb","fs_name_no_ext":"Tetris (World)","files":null,"regions":null,"metadatum":null,"created_at":"2025-06-01T10:00:00"}""")!!)!!
        assertEquals("Tetris (World)", rom.name)
        assertEquals(emptyList(), rom.files)
        assertTrue(rom.createdAt > 0)
        // An old server answers a plain list.
        assertEquals(1, RommParse.page(RommParse.element("""[{"id":1,"fs_name":"a.gb"}]""")).items.size)
    }

    @Test
    fun `a file's folder inside its game is kept, never anything above it`() {
        assertEquals("", RommParse.innerPath("switch/Zelda", "switch", "Zelda"))
        assertEquals("update", RommParse.innerPath("switch/Zelda/update", "switch", "Zelda"))
        assertEquals("dlc/Extra", RommParse.innerPath("switch\\Zelda\\dlc\\Extra", "switch", "Zelda"))
        assertEquals("", RommParse.innerPath("elsewhere/../../etc", "switch", "Zelda"))
    }

    @Test
    fun `capabilities come from the server's own OpenAPI, else its version`() = runBlocking<Unit> {
        val fake = server()
        val c = client(fake)
        val caps = c.detect()
        assertTrue(caps.deviceAuth && caps.pairCodes && caps.chunkedUploads && caps.tokenScans && caps.identifiers)
        fake.openApi = false
        fake.version = "3.10.0"
        val old = c.detect()
        assertFalse(old.chunkedUploads)
        assertFalse(old.deviceAuth)
    }

    // ------------------------------------------------------------------ routes

    @Test
    fun `auto uses home when it answers, outside when it doesn't, and home again once it is back`() = runBlocking<Unit> {
        val fake = server()
        val c = client(fake)
        c.heartbeat()
        assertEquals(NetRoute.LOCAL, c.route)
        fake.down += "home.lan"
        c.heartbeat()
        assertEquals(NetRoute.REMOTE, c.route)
        // Away: outside is tried first, so no call waits on home.
        val before = fake.calls.size
        c.heartbeat()
        assertTrue(fake.calls.drop(before).none { it.contains("home.lan") })
        assertEquals("https://romm.example.com", c.routes.bases().first().second)
        // Home comes back: noticed on the side, and calls go home again.
        fake.down -= "home.lan"
        // Calls keep coming, as they do: one of them looks at home again on the side.
        val until = System.currentTimeMillis() + 3000
        while (c.route != NetRoute.LOCAL && System.currentTimeMillis() < until) {
            c.routes.bases()
            delay(20)
        }
        assertEquals(NetRoute.LOCAL, c.route)
    }

    @Test
    fun `local and remote modes use only their own address`() {
        val fake = server()
        assertEquals(listOf("http://home.lan"), client(fake, mode = RouteMode.LOCAL).routes.bases().map { it.second })
        assertEquals(listOf("https://romm.example.com"), client(fake, mode = RouteMode.REMOTE).routes.bases().map { it.second })
    }

    @Test
    fun `addresses typed without a scheme get the right one`() {
        assertEquals("http://192.168.1.20:8080", RoutePicker.clean("192.168.1.20:8080/"))
        assertEquals("http://romm.local", RoutePicker.clean("romm.local"))
        assertEquals("https://romm.example.com", RoutePicker.clean("romm.example.com"))
        assertEquals("http://nas:8080", RoutePicker.clean("nas:8080"))
    }

    // ------------------------------------------------------------------ signing in

    @Test
    fun `pairing waits for approval and brings back a read-only token`() = runBlocking<Unit> {
        val fake = server()
        val c = client(fake)
        c.detect()
        val code = c.startPairing("device-1", "AYN Thor", "android", RommScopes.READ)
        assertEquals("ABCD2345", code.userCode)
        assertTrue(code.verificationUrl.endsWith("/pair/device?user_code=ABCD2345"))
        assertEquals(PairingState.Waiting, c.pollPairing(code))
        fake.pairingApproved = true
        val approved = c.pollPairing(code) as PairingState.Approved
        assertEquals("readonly", approved.credential.token)
        assertFalse(RommScopes.ROMS_WRITE in approved.credential.scopes)
    }

    @Test
    fun `a pairing code made in RomM exchanges for its token`() = runBlocking<Unit> {
        val token = client(server()).exchangeCode("abcd-2345")
        assertEquals("good", token.token)
    }

    @Test
    fun `a read-only token knows it can't upload`() {
        val fake = server()
        assertFalse(client(fake, token = "readonly").may(RommScopes.ROMS_WRITE))
        assertTrue(client(fake, token = "good").may(RommScopes.ROMS_WRITE))
    }

    // ------------------------------------------------------------------ the mirror

    @Test
    fun `the mirror fills, then brings only what changed, marks new games and keeps removed ones`() = runBlocking<Unit> {
        val fake = server()
        val c = client(fake)
        c.detect()
        val db = db()
        var now = 1_000_000L
        val mirror = RommMirror(db, { now })
        val first = mirror.sync(c, "main", pageSize = 1)
        assertEquals(2, first.added)
        assertEquals(2, mirror.count("main"))
        // Nothing is new on the first sync: it is the library.
        assertEquals(emptyList(), mirror.newGames("main"))
        // The library is read without files; a game's come the first time it is opened.
        assertEquals(0, mirror.rom("main", 2)!!.files.size)
        val ff7 = mirror.withFiles(c, "main", 2)!!
        assertEquals(3, ff7.files.size)
        assertEquals(listOf("RPG"), ff7.genres)
        assertEquals(2000, ff7.year)

        // A new game, a changed one, a removed one.
        fake.games += FakeRomm.Game(3, 10, "gba", "Golden Sun", "Golden Sun (USA).gba", listOf(Triple(31L, "Golden Sun (USA).gba", ByteArray(10))), updated = "2026-02-01T00:00:00+00:00", created = "2026-02-01T00:00:00+00:00")
        fake.games.removeIf { it.id == 1L }
        now += 60_000
        val calls = fake.calls.size
        val second = mirror.sync(c, "main", pageSize = 50)
        assertFalse(second.full)
        assertTrue(fake.calls.drop(calls).any { it.startsWith("GET /api/roms") }, "asked for changes")
        assertEquals(1, second.added)
        assertEquals(1, second.removed)
        assertEquals(listOf(3L), mirror.newGames("main").map { it.id })
        // Removed from the server: kept in the mirror, only marked gone.
        assertNull(mirror.onPlatform("main", "gba").firstOrNull { it.id == 1L })
        assertNotNull(db.rommQueries.rom("main", 1).executeAsOneOrNull())
        mirror.seenNew("main")
        assertEquals(emptyList(), mirror.newGames("main"))
    }

    @Test
    fun `a server that is away leaves the mirror as it was`() = runBlocking<Unit> {
        val fake = server()
        val c = client(fake)
        c.detect()
        val mirror = RommMirror(db(), System::currentTimeMillis)
        mirror.sync(c, "main")
        fake.down += "home.lan"
        fake.down += "romm.example.com"
        assertTrue(runCatching { mirror.sync(c, "main") }.isFailure)
        assertEquals(2, mirror.count("main"))
    }

    @Test
    fun `a page the server is too slow to put together is asked for again in smaller ones`() = runBlocking<Unit> {
        val fake = server()
        fake.slowAbove = 40
        val c = client(fake)
        c.detect()
        val mirror = RommMirror(db(), System::currentTimeMillis)
        val result = mirror.sync(c, "main", pageSize = 250)
        assertEquals(2, result.total)
        val limits = fake.calls.filter { it.startsWith("GET /api/roms?") }.mapNotNull { Regex("limit=(\\d+)").find(it)?.groupValues?.get(1)?.toInt() }
        assertEquals(listOf(250, 125, 62, 31), limits)
    }

    @Test
    fun `a slow answer is slow, not a server that is away`() = runBlocking<Unit> {
        val fake = server()
        fake.slowAbove = 0
        val c = client(fake)
        c.detect()
        val e = assertFailsWith<RommException> { c.roms(0, 50) }
        assertEquals(RommClient.SLOW, e.code)
    }

    @Test
    fun `a sync that stops part way carries on from the page it reached`() = runBlocking<Unit> {
        val fake = FakeRomm()
        for (i in 1L..6L) fake.games += FakeRomm.Game(i, 10, "gba", "Game $i", "Game $i (USA).gba", listOf(Triple(100 + i, "Game $i (USA).gba", ByteArray(10) { i.toByte() })))
        fake.dropFrom = 2
        val c = client(fake)
        c.detect()
        val db = db()
        var now = 1_000_000L
        val mirror = RommMirror(db, { now })
        assertFailsWith<RommException> { mirror.sync(c, "main", pageSize = 2) }
        assertEquals(2, mirror.count("main"))
        // Fuse opened again later: the read carries on from game 3, not from the start.
        fake.dropFrom = Int.MAX_VALUE
        now += 60_000
        val calls = fake.calls.size
        val result = mirror.sync(c, "main", pageSize = 2)
        val offsets = fake.calls.drop(calls).filter { it.startsWith("GET /api/roms?") }.mapNotNull { Regex("offset=(\\d+)").find(it)?.groupValues?.get(1)?.toInt() }
        assertEquals(listOf(2, 4), offsets)
        assertEquals(6, result.total)
        assertEquals(6, mirror.count("main"))
        // Still the first read of the library: nothing in it is "new".
        assertEquals(emptyList(), mirror.newGames("main"))
        // Finished: the next one only asks for what changed.
        val again = mirror.sync(c, "main", pageSize = 2)
        assertFalse(again.full)
    }

    @Test
    fun `a game's files are kept once brought, through later reads of the library`() = runBlocking<Unit> {
        val fake = server()
        val c = client(fake)
        c.detect()
        val mirror = RommMirror(db(), System::currentTimeMillis)
        mirror.sync(c, "main")
        assertEquals(3, mirror.withFiles(c, "main", 2)!!.files.size)
        fake.games.first { it.id == 2L }.updated = "2026-03-01T00:00:00+00:00"
        mirror.sync(c, "main", full = true)
        // Without the server, from what Fuse kept.
        assertEquals(3, mirror.withFiles(null, "main", 2)!!.files.size)
    }

    // ------------------------------------------------------------------ one game, not five

    private fun platformOf(slug: String) = PlatformCatalog.resolveFolder(slug)?.id?.value

    private fun rom(id: Long, slug: String, fs: String, md5: String? = null, titleId: String? = null) =
        RommRom(id, 1, slug, fs.substringBefore(" ("), fs, md5 = md5, titleId = titleId, files = listOf(RommFile(id * 10, fs)))

    @Test
    fun `PS4 and PS5 zips find the folders they unpack to, by name or by the game's own id`() {
        val roms = listOf(
            rom(1, "ps4", "Bloodborne (USA).zip"),
            rom(2, "ps5", "Astro Bot [PPSA12345].zip"),
            rom(3, "ps4", "Gravity Rush 2 (USA).zip"),
        )
        val games = listOf(
            LocalGame(10, "ps4", "Bloodborne", "Bloodborne (USA)"),
            LocalGame(20, "ps5", "Astro Bot", "PPSA-12345"),
            LocalGame(30, "ps4", "Gravity Rush", "Gravity Rush (USA)"),
        )
        val m = RommMatch.match(roms, games, ::platformOf).associate { it.romId to (it.gameId to it.reason) }
        assertEquals(10L to MatchReason.FILE_NAME, m[1])
        assertEquals(20L to MatchReason.TITLE_ID, m[2])
        // A different game whose name only starts the same is never joined.
        assertNull(m[3])
        assertEquals("CUSA07408", RommMatch.playStationId("Bloodborne [cusa_07408]"))
    }

    @Test
    fun `matching uses the strongest evidence and never a look-alike name`() {
        val games = listOf(
            LocalGame(1, "gba", "Metroid Fusion", "Metroid Fusion (USA).gba"),
            LocalGame(2, "gba", "Metroid Fusion", "Metroid Fusion (Europe).gba"),
            LocalGame(3, "psx", "FF7", "ff7.chd", serial = "SCUS-94163"),
            LocalGame(4, "gba", "Golden Sun", "gs.gba", md5 = "abc"),
            LocalGame(5, "gba", "Linked", "whatever.gba", rommRomId = 99),
            LocalGame(6, "snes", "Chrono Trigger", "Chrono Trigger (USA) (Rev 1).sfc"),
        )
        val roms = listOf(
            rom(10, "gba", "Metroid Fusion (USA).gba"),
            rom(11, "psx", "Final Fantasy VII (USA).chd", titleId = "SCUS94163"),
            rom(12, "gba", "Golden Sun (USA).gba", md5 = "abc"),
            rom(99, "gba", "Something Else.gba"),
            // Same name, other revision: not the same game.
            rom(13, "snes", "Chrono Trigger (USA).sfc"),
            // Same file name on another system: not the same game.
            rom(14, "gb", "Metroid Fusion (USA).gba"),
        )
        val m = RommMatch.match(roms, games, ::platformOf).associate { it.romId to (it.gameId to it.reason) }
        assertEquals(1L to MatchReason.FILE_NAME, m[10])
        assertEquals(3L to MatchReason.TITLE_ID, m[11])
        assertEquals(4L to MatchReason.HASH, m[12])
        assertEquals(5L to MatchReason.LINKED, m[99])
        assertNull(m[13])
        assertNull(m[14])
    }

    @Test
    fun `a name alone matches only when it is the only one on both sides, tags and all`() {
        val games = listOf(LocalGame(1, "gba", "Advance Wars", "Advance Wars (USA).zip"))
        val roms = listOf(rom(1, "gba", "Advance Wars (USA).gba"), rom(2, "gba", "Advance Wars (Europe).gba"))
        assertEquals(listOf(1L to MatchReason.NAME_AND_TAGS), RommMatch.match(roms, games, ::platformOf).map { it.romId to it.reason })
        val twice = listOf(rom(1, "gba", "Advance Wars (USA).gba"), rom(3, "gba", "Advance Wars (USA).gb"))
        assertEquals(emptyList(), RommMatch.match(twice, games, ::platformOf))
    }

    // ------------------------------------------------------------------ content and places

    @Test
    fun `discs, updates and DLC are told apart`() {
        val r = RommRom(
            1, 1, "psx", "Game", "Game", multi = true,
            files = listOf(
                RommFile(1, "Game (Disc 1).chd"), RommFile(2, "Game (Disc 2).chd"), RommFile(3, "Game.m3u"),
                RommFile(4, "patch.ips", path = "patch"), RommFile(5, "v1.04.nsp", path = "update/1.04"), RommFile(6, "Expansion.nsp", path = "dlc"),
                RommFile(7, "manual.pdf", category = "manual"),
            ),
        )
        val parts = RommContent.parts(r)
        assertEquals(listOf("Game", "Disc 1", "Disc 2"), parts.filter { it.kind == ContentKind.GAME }.map { it.label })
        assertEquals(listOf("1.04"), parts.filter { it.kind == ContentKind.UPDATE }.map { it.label })
        assertEquals(listOf("Expansion"), parts.filter { it.kind == ContentKind.DLC }.map { it.label })
        assertEquals(1, parts.count { it.kind == ContentKind.MANUAL })
        assertEquals(6, r.playable.size)
    }

    @Test
    fun `games land in the system's folder Fuse already has, by any name it knows`() {
        val psx = PlatformCatalog.byId("psx")!!
        val roots = listOf(RootListing("/storage/SD/ROMs", listOf("gba", "PlayStation", "bios")))
        val (folder, creates) = RommPlacement.systemFolder(psx, "psx", roots, emptyMap(), null, PlatformCatalog::resolveFolder)!!
        assertEquals("/storage/SD/ROMs/PlayStation", folder)
        assertFalse(creates)
        // The person's choice for the system wins.
        assertEquals("/games/ps1", RommPlacement.systemFolder(psx, "psx", roots, mapOf("psx" to "/games/ps1"), null, PlatformCatalog::resolveFolder)!!.first)
        // No folder for it yet: one named RomM's way in the chosen library.
        val n64 = PlatformCatalog.byId("n64")!!
        val made = RommPlacement.systemFolder(n64, "n64", roots, emptyMap(), "/storage/SD/ROMs", PlatformCatalog::resolveFolder)!!
        assertEquals("/storage/SD/ROMs/n64" to true, made)
        // Nowhere at all: Fuse asks.
        assertNull(RommPlacement.systemFolder(n64, "n64", emptyList(), emptyMap(), null, PlatformCatalog::resolveFolder))
    }

    @Test
    fun `a game of several files keeps RomM's layout in its own folder`() {
        val r = RommRom(1, 1, "switch", "Zelda", "Zelda", multi = true, files = listOf(RommFile(1, "Zelda.nsp"), RommFile(2, "u.nsp", path = "update"), RommFile(3, "bad.nsp", path = "../..")))
        val p = RommPlacement.layout(r, r.files, "/roms/switch")
        assertEquals("/roms/switch/Zelda", p.gamePath)
        assertEquals("/roms/switch/Zelda/update/u.nsp", p.files[2])
        assertEquals("/roms/switch/Zelda/bad.nsp", p.files[3])
        val single = RommRom(2, 1, "gba", "X", "X: Y?.gba", files = listOf(RommFile(9, "X: Y?.gba")))
        assertEquals("/roms/gba/X_ Y_.gba", RommPlacement.layout(single, single.files, "/roms/gba").gamePath)
    }

    // ------------------------------------------------------------------ BIOS

    @Test
    fun `only the BIOS a system is missing, never over a file that is there, never a bad dump`() {
        val good = "924e392ed05558ffdb115408c263dccf"
        val ps1 = PlatformCatalog.byId("psx")!!.copy(bios = BiosRequirement("PS1 BIOS", listOf(BiosFile("scph5501.bin", md5 = setOf(good)), BiosFile("scph5500.bin")), requiredCount = 1, hint = ""))
        val ps3 = PlatformCatalog.byId("ps3")!!.copy(bios = BiosRequirement("PS3", listOf(BiosFile("PS3UPDAT.PUP")), installedInEmulator = true, hint = ""))
        val fw = listOf(
            RommFirmware(1, 20, "scph5501.bin", 524288, md5 = good),
            RommFirmware(2, 20, "scph5500.bin", 524288, md5 = "ffff"),
            RommFirmware(3, 30, "PS3UPDAT.PUP", 200_000_000),
        )
        val slugs = mapOf(20L to "psx", 30L to "ps3")
        val existing = setOf("/bios/scph5500.bin")
        val picks = RommBios.needed(
            listOf(ps1, ps3), { BiosStatus(BiosState.MISSING, missing = listOf("scph5501.bin", "scph5500.bin")) }, fw, slugs::get, PlatformCatalog::resolveFolder,
            { _, f -> "/bios/${f.name}" }, { it in existing },
        )
        assertEquals(listOf("scph5501.bin"), picks.map { it.firmware.fileName })
        // A dump whose hash isn't a known good one is never used.
        assertFalse(RommBios.fits(RommFirmware(9, 20, "scph5501.bin", md5 = "0000"), ps1.bios!!.files[0]))
        // Ready systems need nothing.
        assertEquals(emptyList(), RommBios.needed(listOf(ps1), { BiosStatus(BiosState.READY) }, fw, slugs::get, PlatformCatalog::resolveFolder, { _, f -> "/x/${f.name}" }, { false }))
    }

    @Test
    fun `firmware an emulator installs goes to a folder of its own, with how to install it`() {
        val ps3 = PlatformCatalog.byId("ps3")!!.copy(bios = BiosRequirement("PS3", listOf(BiosFile("PS3UPDAT.PUP")), installedInEmulator = true, hint = "Install it from RPCS3's File menu."))
        val fw = listOf(RommFirmware(3, 30, "PS3UPDAT.PUP", 200_000_000))
        val slugs = mapOf(30L to "ps3")
        val folder = { _: io.github.matiyaaa.fuse.model.Platform -> "/games/Firmware/PS3" }
        // Fuse can't look inside the emulator: unknown is not missing, so nothing unless asked for everything.
        assertEquals(emptyList(), RommBios.needed(listOf(ps3), { BiosStatus(BiosState.UNKNOWN) }, fw, slugs::get, PlatformCatalog::resolveFolder, { _, _ -> null }, { false }, installerFolder = folder))
        val all = RommBios.needed(listOf(ps3), { BiosStatus(BiosState.UNKNOWN) }, fw, slugs::get, PlatformCatalog::resolveFolder, { _, _ -> null }, { false }, all = true, installerFolder = folder)
        assertEquals(listOf("/games/Firmware/PS3/PS3UPDAT.PUP"), all.map { it.destination })
        assertEquals("Install it from RPCS3's File menu.", all.single().install)
        // Already brought over: never again.
        assertEquals(emptyList(), RommBios.needed(listOf(ps3), { BiosStatus(BiosState.UNKNOWN) }, fw, slugs::get, PlatformCatalog::resolveFolder, { _, _ -> null }, { it == "/games/Firmware/PS3/PS3UPDAT.PUP" }, all = true, installerFolder = folder))
    }

    // ------------------------------------------------------------------ transfers, end to end

    private inner class Host(val fake: FakeRomm, val c: RommClient, override val mirror: RommMirror) : RommTransferHost {
        val landed = java.util.concurrent.CopyOnWriteArrayList<List<String>>()
        val uploadedIds = java.util.concurrent.CopyOnWriteArrayList<Long?>()
        override fun client(server: String): RommClient = c
        override suspend fun landed(job: RommDownloadJob, item: TransferItem, paths: List<String>) { landed += paths }
        override suspend fun uploaded(job: RommUploadJob, item: TransferItem, romId: Long?) { uploadedIds += romId }
    }

    private suspend fun TransferManager.await(id: String, status: TransferStatus): TransferItem {
        val until = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < until) {
            items.value.firstOrNull { it.id == id && it.status == status }?.let { return it }
            delay(10)
        }
        error("never $status: ${items.value.firstOrNull { it.id == id }}")
    }

    private fun manager(): TransferManager = TransferManager(File(root, "transfers"), scope).also { it.start() }

    @Test
    fun `a multi-disc game downloads into its own folder, which appears whole`() = runBlocking<Unit> {
        val fake = server()
        val c = client(fake).also { it.detect() }
        val mirror = RommMirror(db(), System::currentTimeMillis).also { it.sync(c, "main") }
        val host = Host(fake, c, mirror)
        val m = manager()
        m.register(RommDownloadHandler(fake.client, host))
        val ff7 = mirror.withFiles(c, "main", 2)!!
        val placement = RommPlacement.layout(ff7, ff7.files, File(root, "roms/psx").path)
        val job = RommDownloadJob("main", ff7.id, ff7.files.map { RommDownloadFile(it, it.name) }, newFolder = true, platform = "psx")
        val id = m.enqueue(TransferItem("", "romm:rom:2", ROMM_SOURCE, TransferDirection.DOWNLOAD, TransferKind.GAME, ff7.name, place = TransferPlace(placement.gamePath), totalBytes = ff7.sizeBytes, payload = job.encode()))
        m.await(id, TransferStatus.DONE)
        val folder = File(placement.gamePath)
        assertEquals(3, folder.list()!!.size)
        assertContentEquals(ByteArray(3000) { 3 }, File(folder, "Final Fantasy VII (USA) (Disc 2).chd").readBytes())
        assertFalse(File(folder.parentFile, ".${folder.name}.fuse-download").exists())
        assertEquals(1, host.landed.size)
    }

    @Test
    fun `BIOS from RomM is put in place, and never over the person's own file`() = runBlocking<Unit> {
        val fake = server().apply { firmware += Triple(5L, "scph5501.bin", ByteArray(512) { 9 }) }
        val c = client(fake).also { it.detect() }
        val mirror = RommMirror(db(), System::currentTimeMillis).also { it.sync(c, "main") }
        val m = manager()
        m.register(RommDownloadHandler(fake.client, Host(fake, c, mirror)))
        val fw = mirror.firmware("main").single()
        val dest = File(root, "bios/scph5501.bin")
        val id = m.enqueue(TransferItem("", "romm:bios:5", ROMM_SOURCE, TransferDirection.DOWNLOAD, TransferKind.BIOS, fw.fileName, place = TransferPlace(dest.path), payload = RommDownloadJob("main", firmware = fw).encode()))
        m.await(id, TransferStatus.DONE)
        assertContentEquals(ByteArray(512) { 9 }, dest.readBytes())
        dest.writeText("mine")
        val again = m.enqueue(TransferItem("", "romm:bios:5b", ROMM_SOURCE, TransferDirection.DOWNLOAD, TransferKind.BIOS, fw.fileName, place = TransferPlace(dest.path), payload = RommDownloadJob("main", firmware = fw).encode()))
        val failed = m.await(again, TransferStatus.FAILED)
        assertFalse(failed.retryable)
        assertEquals("mine", dest.readText())
    }

    @Test
    fun `an upload goes in chunks, then RomM scans, and a read-only sign-in is told what it needs`() = runBlocking<Unit> {
        val fake = server()
        val c = client(fake).also { it.detect() }
        val mirror = RommMirror(db(), System::currentTimeMillis).also { it.sync(c, "main") }
        val host = Host(fake, c, mirror)
        val m = manager()
        m.register(RommUploadHandler(host))
        val bytes = ByteArray(RommClient.CHUNK_BYTES + 1234) { (it % 97).toByte() }
        val file = File(root, "Golden Sun (USA).gba").apply { writeBytes(bytes) }
        val job = RommUploadJob("main", 10, listOf(RommUploadFile(file.path, file.name, sizeBytes = bytes.size.toLong())))
        val id = m.enqueue(TransferItem("", "romm:up:gs", ROMM_UPLOAD_SOURCE, TransferDirection.UPLOAD, TransferKind.GAME, "Golden Sun", payload = job.encode(), totalBytes = bytes.size.toLong()))
        m.await(id, TransferStatus.DONE)
        assertContentEquals(bytes, fake.received.single { it.first == file.name }.second)
        assertEquals(1, fake.scans)

        val ro = client(fake, token = "readonly").also { it.detect() }
        val m2 = manager()
        m2.register(RommUploadHandler(Host(fake, ro, mirror)))
        val id2 = m2.enqueue(TransferItem("", "romm:up:ro", ROMM_UPLOAD_SOURCE, TransferDirection.UPLOAD, TransferKind.GAME, "Golden Sun", payload = job.encode()))
        val failed = m2.await(id2, TransferStatus.FAILED)
        assertEquals(RommUploadHandler.UPLOAD_PERMISSION, failed.error)
    }

    @Test
    fun `a file RomM already has exactly is not sent again`() = runBlocking<Unit> {
        val fake = server()
        val c = client(fake).also { it.detect() }
        val mirror = RommMirror(db(), System::currentTimeMillis).also { it.sync(c, "main") }
        val m = manager()
        m.register(RommUploadHandler(Host(fake, c, mirror)))
        // Byte for byte Metroid Fusion, under another name: still the same file.
        val file = File(root, "renamed.gba").apply { writeBytes(ByteArray(5000) { 1 }) }
        val job = RommUploadJob("main", 10, listOf(RommUploadFile(file.path, file.name, sizeBytes = 5000)), scanAfter = false)
        val id = m.enqueue(TransferItem("", "romm:up:dup", ROMM_UPLOAD_SOURCE, TransferDirection.UPLOAD, TransferKind.GAME, "Metroid", payload = job.encode()))
        val done = m.await(id, TransferStatus.DONE)
        assertTrue(fake.received.isEmpty())
        assertTrue("renamed.gba" in done.payload)
        // The same name with other bytes is a different file, and goes.
        val other = File(root, "Metroid Fusion (USA).gba").apply { writeBytes(ByteArray(5000) { 7 }) }
        val job2 = RommUploadJob("main", 10, listOf(RommUploadFile(other.path, other.name, sizeBytes = 5000)), scanAfter = false)
        val id2 = m.enqueue(TransferItem("", "romm:up:dup2", ROMM_UPLOAD_SOURCE, TransferDirection.UPLOAD, TransferKind.GAME, "Metroid", payload = job2.encode()))
        m.await(id2, TransferStatus.DONE)
        assertEquals(1, fake.received.size)
    }

    private fun md5(b: ByteArray) = MessageDigest.getInstance("MD5").digest(b).joinToString("") { "%02x".format(it) }
}
