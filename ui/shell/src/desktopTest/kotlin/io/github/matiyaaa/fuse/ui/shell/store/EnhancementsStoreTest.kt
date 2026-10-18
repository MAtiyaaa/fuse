package io.github.matiyaaa.fuse.ui.shell.store

import io.github.matiyaaa.fuse.data.FuseData
import io.github.matiyaaa.fuse.data.db.DesktopDatabase
import io.github.matiyaaa.fuse.launch.patches.DuckStationHome
import io.github.matiyaaa.fuse.launch.patches.EnhancementKind
import io.github.matiyaaa.fuse.launch.patches.PatchState
import io.github.matiyaaa.fuse.launch.patches.PpssppHome
import io.github.matiyaaa.fuse.model.EmulatorId
import io.github.matiyaaa.fuse.model.Host
import io.github.matiyaaa.fuse.model.InstalledEmulator
import io.github.matiyaaa.fuse.model.LibrarySourceKind
import io.github.matiyaaa.fuse.model.PlatformId
import io.ktor.client.engine.mock.respond
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * A person's own cheats and texture packs, end to end: shown exactly before anything changes, put
 * where the emulator reads them, and taken out again leaving every file as it was.
 */
class EnhancementsStoreTest {
    private lateinit var root: File
    private lateinit var cache: File
    private lateinit var emu: File
    private lateinit var mine: File
    private lateinit var scope: CoroutineScope

    /** A minimal ISO 9660 image with [files] in its root, and [dirs] of files beside them. */
    private fun iso(files: Map<String, ByteArray>, dirs: Map<String, Map<String, ByteArray>> = emptyMap()): ByteArray {
        val sector = 2048
        val image = ByteArray(sector * 40)
        fun le32(at: Int, v: Int) { for (i in 0..3) image[at + i] = (v ushr (8 * i)).toByte() }
        fun record(at: Int, lba: Int, size: Int, dir: Boolean, name: String): Int {
            val n = name.encodeToByteArray()
            val len = 33 + n.size + (if (n.size % 2 == 0) 1 else 0)
            image[at] = len.toByte(); le32(at + 2, lba); le32(at + 10, size)
            image[at + 25] = if (dir) 2 else 0; image[at + 32] = n.size.toByte(); n.copyInto(image, at + 33)
            return len
        }
        val pvd = 16 * sector
        image[pvd] = 1
        "CD001".encodeToByteArray().copyInto(image, pvd + 1)
        record(pvd + 156, 18, sector, true, "\u0000")
        var next = 19
        var at = 18 * sector
        at += record(at, 18, sector, true, "\u0000")
        at += record(at, 18, sector, true, "\u0001")
        for ((name, bytes) in files) {
            at += record(at, next, bytes.size, false, "$name;1")
            bytes.copyInto(image, next++ * sector)
        }
        for ((dir, inside) in dirs) {
            val dirSector = next++
            at += record(at, dirSector, sector, true, dir)
            var d = dirSector * sector
            d += record(d, dirSector, sector, true, "\u0000")
            d += record(d, 18, sector, true, "\u0001")
            for ((name, bytes) in inside) {
                d += record(d, next, bytes.size, false, "$name;1")
                bytes.copyInto(image, next++ * sector)
            }
        }
        return image
    }

    /** A PARAM.SFO holding [values] as text. */
    private fun sfo(values: Map<String, String>): ByteArray {
        val keys = StringBuilder()
        val data = ArrayList<Byte>()
        val entries = values.map { (k, v) ->
            val keyOff = keys.length
            keys.append(k).append('\u0000')
            val bytes = (v + "\u0000").encodeToByteArray()
            val dataOff = data.size
            bytes.forEach { data += it }
            Triple(keyOff, bytes.size, dataOff)
        }
        val keyTable = 20 + 16 * entries.size
        val dataTable = keyTable + keys.length
        val out = ByteArray(dataTable + data.size)
        fun le(at: Int, v: Int, n: Int) { for (i in 0 until n) out[at + i] = (v ushr (8 * i)).toByte() }
        out[1] = 'P'.code.toByte(); out[2] = 'S'.code.toByte(); out[3] = 'F'.code.toByte()
        le(4, 0x101, 4); le(8, keyTable, 4); le(12, dataTable, 4); le(16, entries.size, 4)
        entries.forEachIndexed { i, (keyOff, len, dataOff) ->
            val e = 20 + i * 16
            le(e, keyOff, 2); le(e + 2, 0x0204, 2); le(e + 4, len, 4); le(e + 8, len, 4); le(e + 12, dataOff, 4)
        }
        keys.toString().encodeToByteArray().copyInto(out, keyTable)
        data.toByteArray().copyInto(out, dataTable)
        return out
    }

    @BeforeTest
    fun setUp() {
        root = Files.createTempDirectory("fuse-enh").toFile()
        cache = Files.createTempDirectory("fuse-enh-cache").toFile()
        emu = Files.createTempDirectory("fuse-enh-emu").toFile()
        mine = Files.createTempDirectory("fuse-enh-mine").toFile()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
        listOf(root, cache, emu, mine).forEach { it.deleteRecursively() }
    }

    private fun services(db: String, vararg emulators: InstalledEmulator) =
        FakeServices(FuseData(DesktopDatabase.open(File(cache, db).absolutePath)), cache, installedEmulators = emulators.toList())

    private fun zip(name: String, entries: Map<String, String>): File {
        val f = File(mine, name)
        ZipOutputStream(f.outputStream()).use { z ->
            for ((k, v) in entries) {
                z.putNextEntry(ZipEntry(k))
                z.write(v.encodeToByteArray())
                z.closeEntry()
            }
        }
        return f
    }

    private val duckstationInstalled = InstalledEmulator(EmulatorId("linux.duckstation"), "DuckStation", Host.LINUX, "/usr/bin/duckstation-qt", platforms = setOf(PlatformId("psx")), detectedVia = "PATH")
    private val ppssppInstalled = InstalledEmulator(EmulatorId("linux.ppsspp"), "PPSSPP", Host.LINUX, "/usr/bin/PPSSPPSDL", platforms = setOf(PlatformId("psp")), detectedVia = "PATH")

    private val theirCheats = "[Max Money]\r\nDescription = mine\r\n80020000 FFFF\r\n"

    private fun duckStation(): DuckStationHome {
        File(root, "psx").mkdirs()
        File(root, "psx/Racer.iso").writeBytes(iso(mapOf("SYSTEM.CNF" to "BOOT = cdrom:\\SLUS_005.94;1\r\nTCB = 4\r\n".encodeToByteArray(), "SLUS_005.94" to ByteArray(16))))
        val data = File(emu, "duckstation").apply { mkdirs() }
        File(data, "settings.ini").writeText("[GPU]\nRenderer = Vulkan\n")
        File(data, "cheats").mkdirs()
        File(data, "cheats/SLUS-00594.cht").writeText(theirCheats)
        val home = data.absolutePath
        return DuckStationHome(home, "$home/gamesettings", "$home/cheats", "$home/patches", cheatsZip = null, patchesZip = null, textures = "$home/textures")
    }

    private suspend fun gameIn(store: FuseStore) = withTimeout(20_000) { store.library.games(GameQuery()).first { it.size == 1 } }.single()

    @Test
    fun ownCheatsAreAddedToTheEndAndComeOutLeavingTheFileAsItWas(): Unit = runBlocking {
        val services = services("ds.db", duckstationInstalled)
        services.duckstationHome = duckStation()
        val store = createFuseStore(services, scope)
        store.sources.add(root.absolutePath, LibrarySourceKind.ROMS_ROOT)
        val game = gameIn(store)
        val file = File(emu, "duckstation/cheats/SLUS-00594.cht")
        assertEquals(listOf(EnhancementKind.CHEATS, EnhancementKind.PATCHES, EnhancementKind.TEXTURES), store.library.enhancements(game.id).kinds)

        val own = File(mine, "Racer cheats.cht").apply { writeText("[Infinite Fuel]\n80030000 0063\n") }
        val preview = assertIs<EnhancementPreview.Ready>(store.library.planEnhancement(game.id, EnhancementKind.CHEATS, own.absolutePath))
        assertEquals(1, preview.codes)
        assertTrue(preview.appendsTo!!.endsWith("cheats/SLUS-00594.cht"))
        assertTrue(preview.adds.isEmpty() && preview.replaces.isEmpty())
        assertEquals(theirCheats, file.readText(), "nothing changes for a preview")

        assertNull(store.library.addEnhancement(preview.token))
        assertEquals(theirCheats + "[Infinite Fuel]\r\n80030000 0063\r\n", file.readText())
        assertTrue("Infinite Fuel" in assertIs<GamePatchList.Ready>(store.library.gamePatches(game.id)).cheats.map { it.patch.name })
        val added = store.library.enhancements(game.id).added.single()
        assertEquals("Racer cheats.cht", added.name)
        assertTrue(store.library.addEnhancement(preview.token) != null, "a preview is added once")

        // Turning the cheat on writes the game's own settings, which taking the codes out leaves alone.
        assertTrue(store.library.setGamePatch(game.id, "Infinite Fuel", on = true, cheat = true))
        assertEquals(EnhancementRemoval.Removed, store.library.removeEnhancement(game.id, added.id))
        assertEquals(theirCheats, file.readText())
        assertTrue(store.library.enhancements(game.id).added.isEmpty())
    }

    @Test
    fun codesChangedSinceAreOnlyPutBackWhenAsked(): Unit = runBlocking {
        val services = services("ds2.db", duckstationInstalled)
        services.duckstationHome = duckStation()
        val store = createFuseStore(services, scope)
        store.sources.add(root.absolutePath, LibrarySourceKind.ROMS_ROOT)
        val game = gameIn(store)
        val file = File(emu, "duckstation/cheats/SLUS-00594.cht")
        val own = File(mine, "x.cht").apply { writeText("[Infinite Fuel]\n80030000 0063\n") }
        val preview = assertIs<EnhancementPreview.Ready>(store.library.planEnhancement(game.id, EnhancementKind.CHEATS, own.absolutePath))
        assertNull(store.library.addEnhancement(preview.token))
        file.writeText(file.readText().replace("80030000 0063", "80030000 00FF"))
        val id = store.library.enhancements(game.id).added.single().id
        assertIs<EnhancementRemoval.ChangedSince>(store.library.removeEnhancement(game.id, id))
        assertTrue("00FF" in file.readText(), "nothing changes until the person says so")
        assertEquals(EnhancementRemoval.Removed, store.library.removeEnhancement(game.id, id, force = true))
        assertEquals(theirCheats, file.readText())
    }

    @Test
    fun aTexturePackGoesInItsFolderWithTheSettingsItNeedsAndComesOutCompletely(): Unit = runBlocking {
        val services = services("ds3.db", duckstationInstalled)
        services.duckstationHome = duckStation()
        val store = createFuseStore(services, scope)
        store.sources.add(root.absolutePath, LibrarySourceKind.ROMS_ROOT)
        val game = gameIn(store)
        val target = File(emu, "duckstation/textures/SLUS-00594/replacements")
        val settings = File(emu, "duckstation/gamesettings/SLUS-00594.ini").apply { parentFile.mkdirs(); writeText("[Display]\nAspectRatio = 4:3\n") }
        val pack = zip(
            "HD Racer.zip",
            mapOf(
                "HD Racer/SLUS-00594/replacements/vram-write-1.png" to "a",
                "HD Racer/SLUS-00594/replacements/cars/texpage-2.png" to "b",
                "HD Racer/SLUS-00594/replacements/notes.txt" to "c",
                "HD Racer/README.md" to "d",
                "../../escape.png" to "e",
            ),
        )
        val preview = assertIs<EnhancementPreview.Ready>(store.library.planEnhancement(game.id, EnhancementKind.TEXTURES, pack.absolutePath))
        assertEquals(2, preview.adds.size)
        assertTrue(preview.adds.all { "textures/SLUS-00594/replacements/" in it })
        assertEquals(3, preview.rejected.size)
        assertTrue(preview.rejected.any { it.startsWith("../../escape.png") })
        assertEquals(3, preview.settings.size)
        assertFalse(target.exists())

        assertNull(store.library.addEnhancement(preview.token))
        assertEquals("a", File(target, "vram-write-1.png").readText())
        assertEquals("b", File(target, "cars/texpage-2.png").readText())
        assertFalse(File(emu, "escape.png").exists())
        val text = settings.readText()
        assertTrue("AspectRatio = 4:3" in text)
        assertTrue("EnableVRAMWriteReplacements = true" in text && "EnableTextureReplacements = true" in text && "EnableTextureCache = true" in text)

        val id = store.library.enhancements(game.id).added.single().id
        assertEquals(EnhancementRemoval.Removed, store.library.removeEnhancement(game.id, id))
        assertFalse(File(target, "vram-write-1.png").exists())
        assertFalse(File(target, "cars/texpage-2.png").exists())
        val after = settings.readText()
        assertTrue("AspectRatio = 4:3" in after)
        assertFalse("Enable" in after)
    }

    @Test
    fun aPackForAnotherGameOrWithNothingUsableIsTurnedAway(): Unit = runBlocking {
        val services = services("ds4.db", duckstationInstalled)
        services.duckstationHome = duckStation()
        val store = createFuseStore(services, scope)
        store.sources.add(root.absolutePath, LibrarySourceKind.ROMS_ROOT)
        val game = gameIn(store)
        val other = zip("other.zip", mapOf("SCUS-94163/replacements/vram-write-1.png" to "a"))
        val no = assertIs<EnhancementPreview.Unavailable>(store.library.planEnhancement(game.id, EnhancementKind.TEXTURES, other.absolutePath))
        assertTrue("SCUS-94163" in no.reason)
        val unsafe = zip("bad.zip", mapOf("../../../evil.png" to "a"))
        assertIs<EnhancementPreview.Unavailable>(store.library.planEnhancement(game.id, EnhancementKind.TEXTURES, unsafe.absolutePath))
        val words = File(mine, "words.cht").apply { writeText("hello") }
        assertIs<EnhancementPreview.Unavailable>(store.library.planEnhancement(game.id, EnhancementKind.CHEATS, words.absolutePath))
        val wrongGame = File(mine, "SCUS-94163.cht").apply { writeText("[A]\n80000000 0001\n") }
        assertIs<EnhancementPreview.Unavailable>(store.library.planEnhancement(game.id, EnhancementKind.CHEATS, wrongGame.absolutePath))
        assertTrue(services.restorePoints.isEmpty())
    }

    private fun ppsspp(): PpssppHome {
        File(root, "psp").mkdirs()
        File(root, "psp/Some Game.iso").writeBytes(iso(emptyMap(), mapOf("PSP_GAME" to mapOf("PARAM.SFO" to sfo(linkedMapOf("DISC_ID" to "ULUS10041", "TITLE" to "Some Game"))))))
        val stick = File(emu, "ppsspp").apply { mkdirs() }
        File(stick, "PSP/Cheats").mkdirs()
        File(stick, "PSP/Cheats/ULUS10041.ini").writeText("_S ULUS-10041\n_G Some Game\n_C0 Infinite HP\n_L 0x20001234 0x000003E7\n")
        File(stick, "PSP/SYSTEM").mkdirs()
        File(stick, "PSP/SYSTEM/ppsspp.ini").writeText("[General]\nEnableCheats = True\n")
        return PpssppHome(stick.absolutePath)
    }

    @Test
    fun ppssppCheatsTurnedOnSinceStillComeOutAndItsPacksFollowTheirIni(): Unit = runBlocking {
        val services = services("pp.db", ppssppInstalled)
        services.ppssppHome = ppsspp()
        val store = createFuseStore(services, scope)
        store.sources.add(root.absolutePath, LibrarySourceKind.ROMS_ROOT)
        val game = gameIn(store)
        val file = File(emu, "ppsspp/PSP/Cheats/ULUS10041.ini")
        val before = file.readText()
        assertEquals(listOf(EnhancementKind.CHEATS, EnhancementKind.TEXTURES), store.library.enhancements(game.id).kinds)
        assertIs<EnhancementPreview.Unavailable>(store.library.planEnhancement(game.id, EnhancementKind.PATCHES, file.absolutePath))

        val other = File(mine, "other.ini").apply { writeText("_S ULES-00151\n_C0 Money\n_L 0x20000000 0x1\n") }
        assertIs<EnhancementPreview.Unavailable>(store.library.planEnhancement(game.id, EnhancementKind.CHEATS, other.absolutePath))
        val own = File(mine, "own.ini").apply { writeText("_S ULUS-10041\n_C0 Moon Jump\n_L 0x20000010 0x00000001\n") }
        val preview = assertIs<EnhancementPreview.Ready>(store.library.planEnhancement(game.id, EnhancementKind.CHEATS, own.absolutePath))
        assertNull(store.library.addEnhancement(preview.token))
        val cheats = assertIs<GamePatchList.Ready>(store.library.gamePatches(game.id)).cheats
        assertEquals(listOf("Infinite HP", "Moon Jump"), cheats.map { it.patch.name })
        assertTrue(store.library.setGamePatch(game.id, "Moon Jump", on = true, cheat = true))
        assertEquals(PatchState.ON_BY_FUSE, assertIs<GamePatchList.Ready>(store.library.gamePatches(game.id)).cheats[1].state)
        val id = store.library.enhancements(game.id).added.single().id
        assertEquals(EnhancementRemoval.Removed, store.library.removeEnhancement(game.id, id))
        assertEquals(before, file.readText())

        val pack = File(mine, "pack").apply { mkdirs() }
        File(pack, "textures.ini").writeText("[options]\nhash = quick\n\n[games]\nULES00151 = textures.ini\n")
        File(pack, "a.png").writeText("a")
        val wrong = assertIs<EnhancementPreview.Unavailable>(store.library.planEnhancement(game.id, EnhancementKind.TEXTURES, pack.absolutePath))
        assertTrue("ULUS10041" in wrong.reason)
        File(pack, "textures.ini").writeText("[options]\nhash = quick\n\n[games]\nULUS10041 = textures.ini\n")
        val ok = assertIs<EnhancementPreview.Ready>(store.library.planEnhancement(game.id, EnhancementKind.TEXTURES, pack.absolutePath))
        assertTrue(ok.target.endsWith("PSP/TEXTURES/ULUS10041"))
        assertTrue(ok.settings.isEmpty(), "Replace textures is on unless turned off")
        assertNull(store.library.addEnhancement(ok.token))
        assertEquals("a", File(emu, "ppsspp/PSP/TEXTURES/ULUS10041/a.png").readText())
    }

    // GameBanana ---------------------------------------------------------------------------------

    private fun md5(f: File) = java.security.MessageDigest.getInstance("MD5").digest(f.readBytes()).joinToString("") { "%02x".format(it) }

    private fun gameBanana(services: FakeServices, pack: File, md5: String = md5(pack), av: String = "clean") {
        val games = """{"_aMetadata": {"_nRecordCount": 2, "_bIsComplete": true}, "_aRecords": [
            {"_idRow": 9001, "_sModelName": "Game", "_sName": "Racer Turbo"},
            {"_idRow": 9000, "_sModelName": "Game", "_sName": "Racer"}]}"""
        val mods = """{"_aMetadata": {"_nRecordCount": 2, "_bIsComplete": true}, "_aRecords": [
            {"_idRow": 77, "_sModelName": "Mod", "_sName": "HD Cars", "_sProfileUrl": "https://gamebanana.com/mods/77", "_aRootCategory": {"_sName": "Textures"}, "_sVersion": "1.2", "_nLikeCount": 9},
            {"_idRow": 78, "_sModelName": "Mod", "_sName": "Translation", "_sProfileUrl": "https://gamebanana.com/mods/78", "_aRootCategory": {"_sName": "Game files"}}]}"""
        val mod = """{"_idRow": 77, "_sName": "HD Cars", "_sProfileUrl": "https://gamebanana.com/mods/77", "_aCategory": {"_sName": "Textures"}, "_sVersion": "1.2",
            "_sText": "Sharper cars", "_aFiles": [{"_idRow": 500, "_sFile": "hd_cars.zip", "_nFilesize": ${pack.length()}, "_sDownloadUrl": "https://gamebanana.com/dl/500",
            "_sMd5Checksum": "$md5", "_sAnalysisResult": "ok", "_sAvResult": "$av", "_sVersion": "1.2"}]}"""
        val gameFiles = """{"_idRow": 78, "_sName": "Translation", "_sProfileUrl": "https://gamebanana.com/mods/78", "_aCategory": {"_sName": "Game files"},
            "_aFiles": [{"_idRow": 501, "_sFile": "t.zip", "_nFilesize": 10, "_sDownloadUrl": "https://gamebanana.com/dl/501", "_sAnalysisResult": "ok", "_sAvResult": "clean"}]}"""
        services.web = { request ->
            val path = request.url.encodedPath
            val body = when {
                request.url.host != "gamebanana.com" -> null
                path.endsWith("/Util/Search/Results") -> games
                path.endsWith("/Mod/Index") && request.url.parameters["_aFilters[Generic_Game]"] == "9000" -> mods
                path.endsWith("/Mod/77/ProfilePage") -> mod
                path.endsWith("/Mod/78/ProfilePage") -> gameFiles
                else -> null
            }
            body?.let { respond(it, io.ktor.http.HttpStatusCode.OK, io.ktor.http.headersOf(io.ktor.http.HttpHeaders.ContentType, "application/json")) }
        }
        services.downloads["https://gamebanana.com/dl/500"] = pack
    }

    @Test
    fun aModFromGameBananaIsFetchedCheckedAndAddedLikeYourOwn(): Unit = runBlocking {
        val services = services("gb.db", duckstationInstalled)
        services.duckstationHome = duckStation()
        val pack = zip("hd_cars.zip", mapOf("SLUS-00594/replacements/vram-write-9.png" to "car"))
        gameBanana(services, pack)
        val store = createFuseStore(services, scope)
        store.sources.add(root.absolutePath, LibrarySourceKind.ROMS_ROOT)
        val game = gameIn(store)

        // Nothing is guessed: the game there is chosen, the same name listed first.
        assertTrue(assertIs<ModSiteMods.Unavailable>(store.library.modSiteMods(game.id)).needsGame)
        val found = store.library.modSiteGames(game.id)
        assertEquals("Racer", found.query)
        assertEquals(listOf("Racer", "Racer Turbo"), found.found.map { it.name })
        store.library.linkModSiteGame(game.id, found.found.first())
        assertEquals(9000L, store.library.modSiteGames(game.id).linked?.id)

        val mods = assertIs<ModSiteMods.Ready>(store.library.modSiteMods(game.id))
        assertEquals(listOf(EnhancementKind.TEXTURES, null), mods.mods.map { it.kind })
        assertIs<EnhancementPreview.Unavailable>(store.library.fetchModSiteFile(game.id, 78, 501))
        assertTrue(services.fetchedUrls.isEmpty(), "a kind Fuse doesn't add is never fetched")

        val preview = assertIs<EnhancementPreview.Ready>(store.library.fetchModSiteFile(game.id, 77, 500))
        assertEquals("HD Cars", preview.name)
        assertEquals(1, preview.adds.size)
        assertNull(store.library.addEnhancement(preview.token))
        assertEquals("car", File(emu, "duckstation/textures/SLUS-00594/replacements/vram-write-9.png").readText())
        val added = store.library.enhancements(game.id).added.single()
        assertEquals("GameBanana", added.origin)
        assertEquals("https://gamebanana.com/mods/77", added.originUrl)
        assertEquals("1.2", added.version)
        assertEquals(EnhancementRemoval.Removed, store.library.removeEnhancement(game.id, added.id))
        assertFalse(File(emu, "duckstation/textures/SLUS-00594/replacements/vram-write-9.png").exists())
    }

    @Test
    fun aFileThatFailedItsChecksOrArrivedChangedIsNeverOpened(): Unit = runBlocking {
        val services = services("gb2.db", duckstationInstalled)
        services.duckstationHome = duckStation()
        val pack = zip("hd_cars.zip", mapOf("SLUS-00594/replacements/vram-write-9.png" to "car"))
        gameBanana(services, pack, av = "pending")
        val store = createFuseStore(services, scope)
        store.sources.add(root.absolutePath, LibrarySourceKind.ROMS_ROOT)
        val game = gameIn(store)
        store.library.linkModSiteGame(game.id, store.library.modSiteGames(game.id).found.first())
        val refused = assertIs<EnhancementPreview.Unavailable>(store.library.fetchModSiteFile(game.id, 77, 500))
        assertTrue("virus scan" in refused.reason)
        assertTrue(services.fetchedUrls.isEmpty())

        val services2 = services("gb3.db", duckstationInstalled)
        services2.duckstationHome = duckStation()
        gameBanana(services2, pack, md5 = "0".repeat(32))
        val store2 = createFuseStore(services2, scope)
        store2.sources.add(root.absolutePath, LibrarySourceKind.ROMS_ROOT)
        val game2 = gameIn(store2)
        store2.library.linkModSiteGame(game2.id, store2.library.modSiteGames(game2.id).found.first())
        assertIs<EnhancementPreview.Unavailable>(store2.library.fetchModSiteFile(game2.id, 77, 500))
        assertEquals(listOf("https://gamebanana.com/dl/500"), services2.fetchedUrls)
        assertFalse(File(emu, "duckstation/textures").exists(), "nothing reaches the emulator's folders")
    }

    private val edenInstalled = InstalledEmulator(EmulatorId("linux.eden"), "Eden", Host.LINUX, "/usr/bin/eden", platforms = setOf(PlatformId("switch")), detectedVia = "PATH")

    @Test
    fun aSwitchModGoesInItsOwnFolderForTheGameAndComesOutCompletely(): Unit = runBlocking {
        val services = services("mods.db", edenInstalled)
        val load = File(emu, "eden/load").apply { mkdirs() }
        services.modHomeFor = { q -> io.github.matiyaaa.fuse.sync.ModHome("eden", "0100F2C0115B6000", "${load.absolutePath}/0100F2C0115B6000") }
        File(root, "switch").mkdirs()
        File(root, "switch/Hyrule [0100F2C0115B6000].nsp").writeBytes(ByteArray(64))
        val store = createFuseStore(services, scope)
        store.sources.add(root.absolutePath, LibrarySourceKind.ROMS_ROOT)
        val game = gameIn(store)
        assertEquals(listOf(EnhancementKind.MODS), store.library.enhancements(game.id).kinds)

        val mod = zip(
            "grass.zip",
            mapOf("Better Grass/romfs/Terrain/grass.bin" to "g", "Better Grass/exefs/main.npdm" to "m", "readme.txt" to "r", "../../escape.bin" to "e"),
        )
        val preview = assertIs<EnhancementPreview.Ready>(store.library.planEnhancement(game.id, EnhancementKind.MODS, mod.absolutePath))
        // The game's emulator was asked where its mods go, for this game.
        assertEquals("linux.eden", services.modQueries.last().emulatorId)
        assertTrue(preview.target.endsWith("load/0100F2C0115B6000/Better Grass"))
        assertEquals(2, preview.adds.size)
        assertTrue(preview.rejected.any { it.startsWith("../../escape.bin") })
        assertTrue(preview.notes.any { "exefs" in it })
        val folder = File(load, "0100F2C0115B6000/Better Grass")
        assertFalse(folder.exists(), "nothing changes for a preview")

        assertNull(store.library.addEnhancement(preview.token))
        assertEquals("g", File(folder, "romfs/Terrain/grass.bin").readText())
        assertEquals("m", File(folder, "exefs/main.npdm").readText())
        val added = store.library.enhancements(game.id).added.single()
        assertEquals(EnhancementKind.MODS, added.kind)

        // A second mod of the same name gets a folder of its own.
        val again = assertIs<EnhancementPreview.Ready>(store.library.planEnhancement(game.id, EnhancementKind.MODS, mod.absolutePath))
        assertTrue(again.target.endsWith("Better Grass 2"))

        assertEquals(EnhancementRemoval.Removed, store.library.removeEnhancement(game.id, added.id))
        assertFalse(File(folder, "romfs/Terrain/grass.bin").exists())
        assertFalse(File(folder, "exefs/main.npdm").exists())
    }

    @Test
    fun aModForAnotherGameOrNotAModIsTurnedAway(): Unit = runBlocking {
        val services = services("mods2.db", edenInstalled)
        val load = File(emu, "eden/load").apply { mkdirs() }
        services.modHomeFor = { io.github.matiyaaa.fuse.sync.ModHome("eden", "0100F2C0115B6000", "${load.absolutePath}/0100F2C0115B6000") }
        File(root, "switch").mkdirs()
        File(root, "switch/Hyrule [0100F2C0115B6000].nsp").writeBytes(ByteArray(64))
        val store = createFuseStore(services, scope)
        store.sources.add(root.absolutePath, LibrarySourceKind.ROMS_ROOT)
        val game = gameIn(store)
        val other = zip("other.zip", mapOf("01006A800016E000/Speed/romfs/a.bin" to "a"))
        val no = assertIs<EnhancementPreview.Unavailable>(store.library.planEnhancement(game.id, EnhancementKind.MODS, other.absolutePath))
        assertEquals("Made for another game", no.title)
        // The game's own update id is the same game.
        val update = zip("update.zip", mapOf("0100F2C0115B6800/Speed/romfs/a.bin" to "a"))
        assertIs<EnhancementPreview.Ready>(store.library.planEnhancement(game.id, EnhancementKind.MODS, update.absolutePath))
        val pictures = zip("pics.zip", mapOf("a.png" to "a"))
        assertEquals("Not a Switch mod", assertIs<EnhancementPreview.Unavailable>(store.library.planEnhancement(game.id, EnhancementKind.MODS, pictures.absolutePath)).title)
        assertTrue(File(load, "0100F2C0115B6000").listFiles().isNullOrEmpty())
    }

    // Nexus Mods ---------------------------------------------------------------------------------

    /** Nexus Mods answering as its documentation and its own client describe, for a made-up game and mod. */
    private fun nexus(services: FakeServices, mod: File, premium: Boolean) {
        val json = io.ktor.http.headersOf(io.ktor.http.HttpHeaders.ContentType, "application/json")
        val ok = io.ktor.http.HttpStatusCode.OK
        val modJson = """{"mod_id":55,"game_id":1,"domain_name":"hyrule","name":"Better Grass","summary":"Taller grass","version":"1.0","author":"Ana",
            "updated_timestamp":1790000000,"endorsement_count":12,"contains_adult_content":false,"available":true,"status":"published"}"""
        services.web = { request ->
            val path = request.url.encodedPath
            when {
                request.url.host != "api.nexusmods.com" -> null
                request.headers["apikey"] != "my-own-key" -> respond("""{"message":"invalid key"}""", io.ktor.http.HttpStatusCode.Unauthorized, json)
                path == "/v1/users/validate.json" -> respond("""{"user_id":7,"name":"Mo","is_premium":$premium,"is_supporter":false}""", ok, json)
                path == "/v1/games.json" -> respond(
                    """[{"id":2,"domain_name":"hyrulewarriors","name":"Hyrule Warriors","mods":3},{"id":1,"domain_name":"hyrule","name":"Hyrule","mods":10}]""", ok, json,
                )
                path == "/v1/games/hyrule/mods/trending.json" -> respond("[$modJson]", ok, json)
                path.startsWith("/v1/games/hyrule/mods/latest_") -> respond("[]", ok, json)
                path == "/v1/games/hyrule/mods/55.json" -> respond(modJson, ok, json)
                path == "/v1/games/hyrule/mods/55/files.json" -> respond(
                    """{"files":[{"file_id":900,"category_id":1,"category_name":"MAIN","name":"Better Grass","file_name":"Better Grass-55-1-0.zip","version":"1.0",
                        "size_kb":1,"uploaded_timestamp":1790000000,"is_primary":true}],"file_updates":[]}""",
                    ok, json,
                )
                path == "/v1/games/hyrule/mods/55/files/900/download_link.json" -> when {
                    premium || (request.url.parameters["key"] == "onetime" && request.url.parameters["expires"] == "1790003600") ->
                        respond("""[{"name":"Nexus CDN","short_name":"Nexus CDN","URI":"https://cf-files.nexusmods.com/cdn/55/grass.zip"}]""", ok, json)
                    else -> respond("""{"message":"premium only"}""", io.ktor.http.HttpStatusCode.Forbidden, json)
                }
                else -> null
            }
        }
        services.downloads["https://cf-files.nexusmods.com/cdn/55/grass.zip"] = mod
    }

    private suspend fun hyrule(services: FakeServices): Pair<FuseStore, GameCard> {
        val load = File(emu, "eden/load").apply { mkdirs() }
        services.modHomeFor = { io.github.matiyaaa.fuse.sync.ModHome("eden", "0100F2C0115B6000", "${load.absolutePath}/0100F2C0115B6000") }
        File(root, "switch").mkdirs()
        File(root, "switch/Hyrule [0100F2C0115B6000].nsp").writeBytes(ByteArray(64))
        val store = createFuseStore(services, scope)
        store.sources.add(root.absolutePath, LibrarySourceKind.ROMS_ROOT)
        return store to gameIn(store)
    }

    @Test
    fun aModFromNexusModsComesWithYourOwnKeyAndIsAddedLikeYourOwn(): Unit = runBlocking {
        val services = services("nexus.db", edenInstalled)
        nexus(services, zip("grass.zip", mapOf("Better Grass/romfs/Terrain/grass.bin" to "g")), premium = true)
        val (store, game) = hyrule(services)

        // Nothing without the person's own key.
        assertEquals(io.github.matiyaaa.fuse.ui.shell.store.NexusAccount.NoKey, store.library.nexusAccount())
        assertTrue(assertIs<io.github.matiyaaa.fuse.ui.shell.store.NexusModList.Unavailable>(store.library.nexusMods(game.id)).needsKey)
        assertTrue(services.requestHosts.none { it == "api.nexusmods.com" })
        store.credentials.put(io.github.matiyaaa.fuse.data.settings.SecretKeys.NEXUS_API_KEY, "my-own-key")
        assertEquals(io.github.matiyaaa.fuse.ui.shell.store.NexusAccount.Ready("Mo", premium = true), store.library.nexusAccount())

        // The game there is chosen from Nexus Mods' own list, the same name first.
        assertTrue(assertIs<io.github.matiyaaa.fuse.ui.shell.store.NexusModList.Unavailable>(store.library.nexusMods(game.id)).needsGame)
        val found = store.library.nexusGames(game.id)
        assertEquals(listOf("hyrule", "hyrulewarriors"), found.found.map { it.domain })
        store.library.linkNexusGame(game.id, found.found.first())
        val list = assertIs<io.github.matiyaaa.fuse.ui.shell.store.NexusModList.Ready>(store.library.nexusMods(game.id))
        assertEquals("Better Grass", list.lists.getValue(io.github.matiyaaa.fuse.integrations.nexus.NexusList.TRENDING).single().name)

        val page = assertIs<io.github.matiyaaa.fuse.ui.shell.store.NexusPage.Ready>(store.library.nexusPage(game.id, "hyrule", 55))
        assertEquals(EnhancementKind.MODS, page.kind)
        val preview = assertIs<EnhancementPreview.Ready>(store.library.fetchNexusFile(game.id, "hyrule", 55, 900))
        assertNull(store.library.addEnhancement(preview.token))
        assertEquals("g", File(emu, "eden/load/0100F2C0115B6000/Better Grass/romfs/Terrain/grass.bin").readText())
        val added = store.library.enhancements(game.id).added.single()
        assertEquals("Nexus Mods", added.origin)
        assertEquals("https://www.nexusmods.com/hyrule/mods/55", added.originUrl)
        assertTrue(services.fetchedUrls.none { "my-own-key" in it }, "the key goes in its header only")
        // The person's own: never among the keys the household shares.
        assertTrue(io.github.matiyaaa.fuse.data.settings.SecretKeys.NEXUS_API_KEY !in io.github.matiyaaa.fuse.data.settings.SecretKeys.all)
        assertTrue(io.github.matiyaaa.fuse.data.settings.SecretKeys.NEXUS_API_KEY in store.credentials.stored.value)
    }

    @Test
    fun anAccountThatIsntPremiumUsesTheWebsitesLinkAndTwoModsChangingOneFileAreSaid(): Unit = runBlocking {
        val services = services("nexus2.db", edenInstalled)
        nexus(services, zip("grass.zip", mapOf("Better Grass/romfs/Terrain/grass.bin" to "g")), premium = false)
        val (store, game) = hyrule(services)
        services.secrets.put(io.github.matiyaaa.fuse.data.settings.SecretKeys.NEXUS_API_KEY, "my-own-key")
        store.library.linkNexusGame(game.id, store.library.nexusGames(game.id).found.first())

        val refused = assertIs<EnhancementPreview.Unavailable>(store.library.fetchNexusFile(game.id, "hyrule", 55, 900))
        assertTrue("Premium" in refused.reason, refused.reason)
        assertTrue(services.fetchedUrls.isEmpty())
        val elsewhere = assertIs<EnhancementPreview.Unavailable>(store.library.fetchNexusFile(game.id, "hyrule", 55, 900, "nxm://hyrule/mods/55/files/901?key=onetime&expires=1790003600"))
        assertTrue("another file" in elsewhere.title)
        val preview = assertIs<EnhancementPreview.Ready>(
            store.library.fetchNexusFile(game.id, "hyrule", 55, 900, "nxm://hyrule/mods/55/files/900?key=onetime&expires=1790003600&user_id=7"),
        )
        assertNull(store.library.addEnhancement(preview.token))

        // A second mod changing the same file is said before it is added; one changing others isn't.
        val lush = zip("lush.zip", mapOf("Lush Grass/romfs/Terrain/grass.bin" to "l", "Lush Grass/romfs/Terrain/rock.bin" to "r"))
        val clash = assertIs<EnhancementPreview.Ready>(store.library.planEnhancement(game.id, EnhancementKind.MODS, lush.absolutePath))
        assertTrue(clash.notes.any { "Better Grass changes the same file (romfs/Terrain/grass.bin)" in it }, clash.notes.toString())
        val rocks = zip("rocks.zip", mapOf("Rocks/romfs/Terrain/stone.bin" to "s"))
        val fine = assertIs<EnhancementPreview.Ready>(store.library.planEnhancement(game.id, EnhancementKind.MODS, rocks.absolutePath))
        assertTrue(fine.notes.none { "changes the same" in it })
    }

    // Dolphin's cheats --------------------------------------------------------------------------

    private val dolphinInstalled = InstalledEmulator(EmulatorId("linux.dolphin"), "Dolphin", Host.LINUX, "/usr/bin/dolphin-emu", platforms = setOf(PlatformId("ngc"), PlatformId("wii")), detectedVia = "PATH")

    @Test
    fun dolphinCodesGoToTheEndOfTheGamesOwnFileOffAndComeOutExactly(): Unit = runBlocking {
        val services = services("dolphin.db", dolphinInstalled)
        val settings = File(emu, "dolphin/GameSettings/GALE01.ini").apply { parentFile.mkdirs(); writeText("[Video_Settings]\nHiresTextures = True\n[Gecko]\n\$Old Code\n04000000 00000001\n") }
        val before = settings.readText()
        services.modHomeFor = { io.github.matiyaaa.fuse.sync.ModHome("dolphin", "GALE01", File(emu, "dolphin/Load/Textures").absolutePath, settingsFile = settings.absolutePath) }
        // Dolphin's folders, the only place its files are changed.
        services.dolphinHome = io.github.matiyaaa.fuse.launch.settings.DolphinHome(File(emu, "dolphin/Config").absolutePath, File(emu, "dolphin/GameSettings").absolutePath)
        File(root, "gc").mkdirs()
        File(root, "gc/Melee [GALE01].iso").writeBytes(ByteArray(64))
        val store = createFuseStore(services, scope)
        store.sources.add(root.absolutePath, LibrarySourceKind.ROMS_ROOT)
        val game = gameIn(store)
        assertTrue(EnhancementKind.CHEATS in store.library.enhancements(game.id).kinds)

        val codes = File(root, "melee codes.txt").apply { writeText("\$Old Code\n04000000 00000001\n\$Infinite Time [Ana]\n04111111 00000000\nnot a code\n") }
        val preview = assertIs<EnhancementPreview.Ready>(store.library.planEnhancement(game.id, EnhancementKind.CHEATS, codes.absolutePath))
        assertEquals(1, preview.codes, "the code the game already has is left out")
        assertTrue(preview.rejected.any { it.startsWith("\$Old Code") } && preview.rejected.any { it.startsWith("not a code") }, preview.rejected.toString())
        assertTrue(preview.notes.any { "Properties, Gecko Codes" in it })
        assertNull(store.library.addEnhancement(preview.token))
        val now = settings.readText()
        assertTrue(now.startsWith(before.trimEnd()), "what was there stays")
        assertTrue("[Gecko]\n\$Infinite Time [Ana]\n04111111 00000000\n[Core]\nEnableCheats = True" in now, now)
        assertFalse("[Gecko_Enabled]\n\$Infinite Time" in now, "added off")

        // Another game's file is turned away by its name.
        val other = File(root, "GMSE01.ini").apply { writeText("[Gecko]\n\$Fly\n04000000 00000001\n") }
        assertEquals("Made for another game", assertIs<EnhancementPreview.Unavailable>(store.library.planEnhancement(game.id, EnhancementKind.CHEATS, other.absolutePath)).title)

        val added = store.library.enhancements(game.id).added.single()
        assertEquals(EnhancementRemoval.Removed, store.library.removeEnhancement(game.id, added.id))
        assertEquals(before, settings.readText())
    }
}
