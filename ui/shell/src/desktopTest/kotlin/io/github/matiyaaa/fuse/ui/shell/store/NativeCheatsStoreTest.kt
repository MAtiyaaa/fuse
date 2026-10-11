package io.github.matiyaaa.fuse.ui.shell.store

import io.github.matiyaaa.fuse.data.FuseData
import io.github.matiyaaa.fuse.data.db.DesktopDatabase
import io.github.matiyaaa.fuse.launch.patches.DuckStationHome
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

/** DuckStation's patches and cheats, and PPSSPP's cheats, end to end: read from their files, changed only where Fuse may. */
class NativeCheatsStoreTest {
    private lateinit var root: File
    private lateinit var cache: File
    private lateinit var emu: File
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
        root = Files.createTempDirectory("fuse-cheats").toFile()
        cache = Files.createTempDirectory("fuse-cheats-cache").toFile()
        emu = Files.createTempDirectory("fuse-cheats-emu").toFile()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
        listOf(root, cache, emu).forEach { it.deleteRecursively() }
    }

    private fun services(db: String, vararg emulators: InstalledEmulator) =
        FakeServices(FuseData(DesktopDatabase.open(File(cache, db).absolutePath)), cache, installedEmulators = emulators.toList())

    private fun duckStation(): DuckStationHome {
        File(root, "psx").mkdirs()
        File(root, "psx/Racer.iso").writeBytes(iso(mapOf("SYSTEM.CNF" to "BOOT = cdrom:\\SLUS_005.94;1\r\nTCB = 4\r\n".encodeToByteArray(), "SLUS_005.94" to ByteArray(16))))
        val data = File(emu, "duckstation").apply { mkdirs() }
        File(data, "settings.ini").writeText("[GPU]\nWidescreenHack = true\n\n[Display]\nAspectRatio = 16:9\n")
        File(data, "patches").mkdirs()
        File(data, "patches/SLUS-00594.cht").writeText("[Widescreen 16:9]\nOverrideAspectRatio = 16:9\n80012345 1234\n\n[Skip Intro]\nDescription = Straight to the menu\n80012346 0001\n")
        File(data, "cheats").mkdirs()
        // The person's own file changes the bundled Max Money.
        File(data, "cheats/SLUS-00594.cht").writeText("[Max Money]\nDescription = mine\n80020000 FFFF\n")
        ZipOutputStream(File(data, "cheats.zip").outputStream()).use { z ->
            z.putNextEntry(ZipEntry("SLUS-00594.cht"))
            z.write("[Infinite Health]\n80013000 00FF\n[Max Money]\n80020000 0001\n[Moon Jump]\nActivation = Manual\nD0012345 0040\n".encodeToByteArray())
            z.closeEntry()
        }
        val home = data.absolutePath
        return DuckStationHome(home, "$home/gamesettings", "$home/cheats", "$home/patches", cheatsZip = "$home/cheats.zip", patchesZip = null)
    }

    private val duckstationInstalled = InstalledEmulator(EmulatorId("linux.duckstation"), "DuckStation", Host.LINUX, "/usr/bin/duckstation-qt", platforms = setOf(PlatformId("psx")), detectedVia = "PATH")

    @Test
    fun duckStationCheatsTurnOnWithItsSwitchAndOffAgain(): Unit = runBlocking {
        val services = services("ds.db", duckstationInstalled)
        services.duckstationHome = duckStation()
        val store = createFuseStore(services, scope)
        store.sources.add(root.absolutePath, LibrarySourceKind.ROMS_ROOT)
        val game = withTimeout(20_000) { store.library.games(GameQuery()).first { it.size == 1 } }.single()
        val settings = File(emu, "duckstation/gamesettings/SLUS-00594.ini")

        val list = assertIs<GamePatchList.Ready>(store.library.gamePatches(game.id))
        assertEquals("DuckStation", list.emulator)
        assertEquals("SLUS-00594", list.serial)
        assertEquals(listOf("Widescreen 16:9", "Skip Intro"), list.patches.map { it.patch.name })
        // Its widescreen setting with a 16:9 screen turns the widescreen patch on for every game.
        assertEquals(PatchState.ON_FOR_ALL_GAMES, list.patches[0].state)
        assertEquals(listOf("Infinite Health", "Max Money"), list.cheats.map { it.patch.name })
        assertEquals("mine", list.cheats[1].patch.description)
        assertFalse(list.cheatsOn)
        assertTrue(list.warning!!.contains("1 code applied by hand"))
        assertFalse(list.bundledRead, "its bundled patches couldn't be read here")

        assertTrue(store.library.setGamePatch(game.id, "Skip Intro", on = true))
        assertEquals("[Patches]\nEnable = Skip Intro\n", settings.readText())
        assertFalse(store.library.setGamePatch(game.id, "Widescreen 16:9", on = false))

        assertTrue(store.library.setGamePatch(game.id, "Infinite Health", on = true, cheat = true))
        assertEquals("[Patches]\nEnable = Skip Intro\n\n[Cheats]\nEnable = Infinite Health\nEnableCheats = true\n", settings.readText())
        assertTrue(assertIs<GamePatchList.Ready>(store.library.gamePatches(game.id)).cheatsOn)
        assertTrue(store.library.setGamePatch(game.id, "Max Money", on = true, cheat = true))
        assertTrue(store.library.setGamePatch(game.id, "Infinite Health", on = false, cheat = true))
        assertTrue("EnableCheats = true" in settings.readText())
        assertTrue(store.library.setGamePatch(game.id, "Max Money", on = false, cheat = true))
        assertTrue(store.library.setGamePatch(game.id, "Skip Intro", on = false))
        assertEquals("[Patches]\n\n[Cheats]\n", settings.readText())
    }

    @Test
    fun duckStationsOwnCheatSwitchStaysWhenTheUserTurnedItOn(): Unit = runBlocking {
        val services = services("ds2.db", duckstationInstalled)
        services.duckstationHome = duckStation()
        val settings = File(emu, "duckstation/gamesettings/SLUS-00594.ini").apply { parentFile.mkdirs(); writeText("[Cheats]\nEnableCheats = true\nEnable = Max Money\n") }
        val store = createFuseStore(services, scope)
        store.sources.add(root.absolutePath, LibrarySourceKind.ROMS_ROOT)
        val game = withTimeout(20_000) { store.library.games(GameQuery()).first { it.size == 1 } }.single()

        val list = assertIs<GamePatchList.Ready>(store.library.gamePatches(game.id))
        assertTrue(list.cheatsOn)
        assertEquals(PatchState.ON_IN_EMULATOR, list.cheats.first { it.patch.name == "Max Money" }.state)
        assertFalse(store.library.setGamePatch(game.id, "Max Money", on = false, cheat = true))
        assertTrue(store.library.setGamePatch(game.id, "Infinite Health", on = true, cheat = true))
        assertTrue(store.library.setGamePatch(game.id, "Infinite Health", on = false, cheat = true))
        assertEquals("[Cheats]\nEnableCheats = true\nEnable = Max Money\n", settings.readText())
    }

    private val ppssppInstalled = InstalledEmulator(EmulatorId("linux.ppsspp"), "PPSSPP", Host.LINUX, "/usr/bin/PPSSPPSDL", platforms = setOf(PlatformId("psp")), detectedVia = "PATH")

    private val cheatText = "_S ULUS-10041\n_G Some Game\n_C0 Infinite HP\n_L 0x20001234 0x000003E7\n_C1 Max Money\n_L 0x20005678 0x0098967F\n"

    private fun ppsspp(): PpssppHome {
        File(root, "psp").mkdirs()
        File(root, "psp/Some Game.iso").writeBytes(iso(emptyMap(), mapOf("PSP_GAME" to mapOf("PARAM.SFO" to sfo(linkedMapOf("DISC_ID" to "ULUS10041", "TITLE" to "Some Game"))))))
        val stick = File(emu, "ppsspp").apply { mkdirs() }
        File(stick, "PSP/Cheats").mkdirs()
        File(stick, "PSP/Cheats/ULUS10041.ini").writeText(cheatText)
        File(stick, "PSP/SYSTEM").mkdirs()
        File(stick, "PSP/SYSTEM/ppsspp.ini").writeText("[General]\nEnableCheats = False\n")
        return PpssppHome(stick.absolutePath)
    }

    @Test
    fun ppssppCheatsAreSwitchedInTheirFileAndItsSettingsForEveryGameStayAlone(): Unit = runBlocking {
        val services = services("pp.db", ppssppInstalled)
        services.ppssppHome = ppsspp()
        val store = createFuseStore(services, scope)
        store.sources.add(root.absolutePath, LibrarySourceKind.ROMS_ROOT)
        val game = withTimeout(20_000) { store.library.games(GameQuery()).first { it.size == 1 } }.single()
        val file = File(emu, "ppsspp/PSP/Cheats/ULUS10041.ini")
        val global = File(emu, "ppsspp/PSP/SYSTEM/ppsspp.ini")

        val list = assertIs<GamePatchList.Ready>(store.library.gamePatches(game.id))
        assertEquals("ULUS10041", list.serial)
        assertTrue(list.patches.isEmpty())
        assertEquals(listOf(PatchState.OFF, PatchState.ON_IN_EMULATOR), list.cheats.map { it.state })
        assertNotNull(list.warning, "PPSSPP's cheats are off, which Fuse says rather than changing for every game")

        assertTrue(store.library.setGamePatch(game.id, "Infinite HP", on = true, cheat = true))
        assertEquals(cheatText.replace("_C0 Infinite HP", "_C1 Infinite HP"), file.readText())
        assertEquals("[General]\nEnableCheats = False\n", global.readText())
        assertFalse(store.library.setGamePatch(game.id, "Max Money", on = false, cheat = true))
        assertTrue(store.library.setGamePatch(game.id, "Infinite HP", on = false, cheat = true))
        assertEquals(cheatText, file.readText())
    }

    @Test
    fun ppssppsSwitchInTheGamesOwnSettingsFollowsFusesCheats(): Unit = runBlocking {
        val services = services("pp2.db", ppssppInstalled)
        services.ppssppHome = ppsspp()
        val own = File(emu, "ppsspp/PSP/SYSTEM/ULUS10041_ppsspp.ini").apply { writeText("[General]\nEnableCheats = False\nEnableLogging = True\n") }
        val store = createFuseStore(services, scope)
        store.sources.add(root.absolutePath, LibrarySourceKind.ROMS_ROOT)
        val game = withTimeout(20_000) { store.library.games(GameQuery()).first { it.size == 1 } }.single()

        assertNull(assertIs<GamePatchList.Ready>(store.library.gamePatches(game.id)).warning)
        assertTrue(store.library.setGamePatch(game.id, "Infinite HP", on = true, cheat = true))
        assertEquals("[General]\nEnableCheats = True\nEnableLogging = True\n", own.readText())
        assertTrue(assertIs<GamePatchList.Ready>(store.library.gamePatches(game.id)).cheatsOn)
        assertTrue(store.library.setGamePatch(game.id, "Infinite HP", on = false, cheat = true))
        assertEquals("[General]\nEnableCheats = False\nEnableLogging = True\n", own.readText())
    }

    private val retroArchInstalled = InstalledEmulator(EmulatorId("linux.retroarch"), "RetroArch", Host.LINUX, "/usr/bin/retroarch", platforms = setOf(PlatformId("gba")), detectedVia = "PATH")

    private val downloaded = "cheats = 2\n\ncheat0_desc = \"Infinite HP\"\ncheat0_code = \"0300:FF\"\ncheat0_enable = false\n\ncheat1_desc = \"Max Coins\"\ncheat1_code = \"0301:99\"\ncheat1_enable = false\n"

    private fun retroArch(): io.github.matiyaaa.fuse.launch.patches.RetroArchHome {
        File(root, "gba").mkdirs()
        File(root, "gba/Golden Sun (USA).gba").writeBytes(ByteArray(64))
        val base = File(emu, "retroarch").apply { mkdirs() }
        File(base, "retroarch.cfg").writeText("apply_cheats_after_load = \"false\"\n")
        // Cheats RetroArch downloaded (Online Updater, Update Cheats), by system.
        File(base, "cheats/Nintendo - Game Boy Advance").mkdirs()
        File(base, "cheats/Nintendo - Game Boy Advance/Golden Sun (USA).cht").writeText(downloaded)
        val home = base.absolutePath
        return io.github.matiyaaa.fuse.launch.patches.RetroArchHome("$home/retroarch.cfg", "$home/cheats", "$home/config")
    }

    @Test
    fun retroArchCheatsComeFromItsDownloadsAndTurnOnForThisGameAlone(): Unit = runBlocking {
        val services = services("ra.db", retroArchInstalled)
        services.retroArchHome = retroArch()
        val store = createFuseStore(services, scope)
        store.sources.add(root.absolutePath, LibrarySourceKind.ROMS_ROOT)
        val game = withTimeout(20_000) { store.library.games(GameQuery()).first { it.size == 1 } }.single()
        val base = File(emu, "retroarch")
        val own = File(base, "cheats/mGBA/Golden Sun (USA).cht")
        val override = File(base, "config/mGBA/Golden Sun (USA).cfg")

        val list = assertIs<GamePatchList.Ready>(store.library.gamePatches(game.id))
        assertEquals("RetroArch", list.emulator)
        assertEquals("Golden Sun (USA) in mGBA", list.serial)
        assertEquals(listOf("Infinite HP", "Max Coins"), list.cheats.map { it.patch.shown })
        assertFalse(list.cheatsOn)
        assertTrue(list.warning!!.contains("downloaded cheats"))

        // On: the downloaded cheats become the game's own file, and its override applies them.
        assertTrue(store.library.setGamePatch(game.id, "0", on = true, cheat = true))
        assertEquals(downloaded.replace("cheat0_enable = false", "cheat0_enable = true"), own.readText())
        assertEquals("apply_cheats_after_load = \"true\"\n", override.readText())
        assertTrue(assertIs<GamePatchList.Ready>(store.library.gamePatches(game.id)).cheatsOn)
        assertEquals(File(base, "retroarch.cfg").readText(), "apply_cheats_after_load = \"false\"\n", "RetroArch's settings for every game stay as they were")

        // Off again: what Fuse made goes, and nothing else changed.
        assertTrue(store.library.setGamePatch(game.id, "0", on = false, cheat = true))
        assertFalse(own.exists())
        assertFalse(override.exists())
    }

    @Test
    fun retroArchsOwnOverrideAndCheatsTheUserTurnedOnStay(): Unit = runBlocking {
        val services = services("ra2.db", retroArchInstalled)
        services.retroArchHome = retroArch()
        val base = File(emu, "retroarch")
        val own = File(base, "cheats/mGBA/Golden Sun (USA).cht").apply { parentFile.mkdirs(); writeText(downloaded.replace("cheat1_enable = false", "cheat1_enable = true")) }
        val override = File(base, "config/mGBA/Golden Sun (USA).cfg").apply { parentFile.mkdirs(); writeText("video_scale_integer = \"true\"\n") }
        val store = createFuseStore(services, scope)
        store.sources.add(root.absolutePath, LibrarySourceKind.ROMS_ROOT)
        val game = withTimeout(20_000) { store.library.games(GameQuery()).first { it.size == 1 } }.single()

        val list = assertIs<GamePatchList.Ready>(store.library.gamePatches(game.id))
        assertNull(list.warning)
        assertEquals(PatchState.ON_IN_EMULATOR, list.cheats[1].state)
        assertFalse(store.library.setGamePatch(game.id, "1", on = false, cheat = true))
        assertTrue(store.library.setGamePatch(game.id, "0", on = true, cheat = true))
        assertEquals("video_scale_integer = \"true\"\napply_cheats_after_load = \"true\"\n", override.readText())
        assertTrue(store.library.setGamePatch(game.id, "0", on = false, cheat = true))
        // The person's own files stay, as they were.
        assertEquals(downloaded.replace("cheat1_enable = false", "cheat1_enable = true"), own.readText())
        assertEquals("video_scale_integer = \"true\"\n", override.readText())
    }
}
