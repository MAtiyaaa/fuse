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
}
