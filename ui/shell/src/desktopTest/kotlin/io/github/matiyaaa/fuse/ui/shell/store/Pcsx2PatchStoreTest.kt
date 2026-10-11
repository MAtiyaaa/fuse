package io.github.matiyaaa.fuse.ui.shell.store

import io.github.matiyaaa.fuse.data.FuseData
import io.github.matiyaaa.fuse.data.db.DesktopDatabase
import io.github.matiyaaa.fuse.launch.patches.PatchState
import io.github.matiyaaa.fuse.launch.patches.Pcsx2Home
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
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** PCSX2 patches end to end: read from PCSX2's own files, changed only where Fuse may. */
class Pcsx2PatchStoreTest {
    private lateinit var root: File
    private lateinit var cache: File
    private lateinit var pcsx2: File
    private lateinit var scope: CoroutineScope

    /** A minimal PS2 ISO: SYSTEM.CNF and a boot program of ten bytes (CRC 0C040404). */
    private fun ps2Iso(): ByteArray {
        val sector = 2048
        val image = ByteArray(sector * 24)
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
        val cnf = "BOOT2 = cdrom0:\\SLUS_209.46;1\r\nVMODE = NTSC\r\n".encodeToByteArray()
        val elf = ByteArray(10) { (it + 1).toByte() }
        var at = 18 * sector
        at += record(at, 18, sector, true, "\u0000")
        at += record(at, 18, sector, true, "\u0001")
        at += record(at, 20, cnf.size, false, "SYSTEM.CNF;1")
        record(at, 21, elf.size, false, "SLUS_209.46;1")
        cnf.copyInto(image, 20 * sector)
        elf.copyInto(image, 21 * sector)
        return image
    }

    @BeforeTest
    fun setUp() {
        root = Files.createTempDirectory("fuse-patch").toFile()
        cache = Files.createTempDirectory("fuse-patch-cache").toFile()
        pcsx2 = Files.createTempDirectory("fuse-pcsx2").toFile()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        File(root, "ps2").mkdirs()
        File(root, "ps2/Sky Racer.iso").writeBytes(ps2Iso())
        File(pcsx2, "inis").mkdirs()
        File(pcsx2, "inis/PCSX2.ini").writeText("[EmuCore]\nEnableWideScreenPatches = false\n")
        File(pcsx2, "patches").mkdirs()
        File(pcsx2, "patches/SLUS-20946_0C040404.pnach").writeText("[Widescreen 16:9]\npatch=1,EE,00200000,word,3F400000\n")
        File(pcsx2, "gamesettings").mkdirs()
        File(pcsx2, "gamesettings/SLUS-20946_0C040404.ini").writeText("[Patches]\nEnable = Widescreen 16:9\n")
        ZipOutputStream(File(pcsx2, "patches.zip").outputStream()).use { z ->
            z.putNextEntry(ZipEntry("SLUS-20946_0C040404.pnach"))
            z.write("[60 FPS]\ndescription=Runs at 60\npatch=0,EE,00300000,word,00000001\n[Widescreen 16:9]\npatch=1,EE,1,word,1\n".encodeToByteArray())
            z.closeEntry()
        }
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
        listOf(root, cache, pcsx2).forEach { it.deleteRecursively() }
    }

    @Test
    fun fuseTurnsOnAndOffOnlyItsOwnPatches(): Unit = runBlocking {
        val services = FakeServices(
            FuseData(DesktopDatabase.open(File(cache, "fuse.db").absolutePath)), cache,
            installedEmulators = listOf(InstalledEmulator(EmulatorId("linux.pcsx2"), "PCSX2", Host.LINUX, "/usr/bin/pcsx2-qt", platforms = setOf(PlatformId("ps2")), detectedVia = "PATH")),
        )
        val home = pcsx2.absolutePath
        services.pcsx2Home = Pcsx2Home(home, "$home/gamesettings", "$home/patches", "$home/patches.zip")
        val store = createFuseStore(services, scope)
        store.sources.add(root.absolutePath, LibrarySourceKind.ROMS_ROOT)
        val game = withTimeout(20_000) { store.library.games(GameQuery()).first { it.size == 1 } }.single()

        val list = assertIs<GamePatchList.Ready>(store.library.gamePatches(game.id))
        assertEquals("SLUS-20946", list.serial)
        assertEquals("0C040404", list.crc)
        // The patch file on disk wins over the bundled one of the same name.
        assertEquals(listOf("Widescreen 16:9", "60 FPS"), list.patches.map { it.patch.name })
        assertEquals(PatchState.ON_IN_EMULATOR, list.patches[0].state)
        assertEquals(PatchState.OFF, list.patches[1].state)

        val settings = File(pcsx2, "gamesettings/SLUS-20946_0C040404.ini")
        assertTrue(store.library.setGamePatch(game.id, "60 FPS", on = true))
        assertEquals("[Patches]\nEnable = Widescreen 16:9\nEnable = 60 FPS\n", settings.readText())
        // The user's own patch stays on whatever Fuse is asked.
        assertFalse(store.library.setGamePatch(game.id, "Widescreen 16:9", on = false))
        assertTrue(store.library.setGamePatch(game.id, "60 FPS", on = false))
        assertEquals("[Patches]\nEnable = Widescreen 16:9\n", settings.readText())
    }

    @Test
    fun cheatsTurnOnWithPcsx2sCheatsSwitchAndOffAgainOnlyAsFuseLeftThem(): Unit = runBlocking {
        val services = FakeServices(
            FuseData(DesktopDatabase.open(File(cache, "fuse3.db").absolutePath)), cache,
            installedEmulators = listOf(InstalledEmulator(EmulatorId("linux.pcsx2"), "PCSX2", Host.LINUX, "/usr/bin/pcsx2-qt", platforms = setOf(PlatformId("ps2")), detectedVia = "PATH")),
        )
        val home = pcsx2.absolutePath
        File(pcsx2, "cheats").mkdirs()
        File(pcsx2, "cheats/SLUS-20946_0C040404.pnach").writeText("[Infinite Health]\npatch=1,EE,00400000,word,000003E7\n[Max Money]\npatch=1,EE,00400004,word,0098967F\n")
        services.pcsx2Home = Pcsx2Home(home, "$home/gamesettings", "$home/patches", "$home/patches.zip", cheats = "$home/cheats")
        val store = createFuseStore(services, scope)
        store.sources.add(root.absolutePath, LibrarySourceKind.ROMS_ROOT)
        val game = withTimeout(20_000) { store.library.games(GameQuery()).first { it.size == 1 } }.single()
        val settings = File(pcsx2, "gamesettings/SLUS-20946_0C040404.ini")

        val list = assertIs<GamePatchList.Ready>(store.library.gamePatches(game.id))
        assertEquals(listOf("Infinite Health", "Max Money"), list.cheats.map { it.patch.name })
        assertFalse(list.cheatsOn)

        // On: the cheat is listed, and PCSX2's cheats are turned on for this game.
        assertTrue(store.library.setGamePatch(game.id, "Infinite Health", on = true, cheat = true))
        assertEquals("[Patches]\nEnable = Widescreen 16:9\n\n[Cheats]\nEnable = Infinite Health\n\n[EmuCore]\nEnableCheats = true\n", settings.readText())
        assertTrue(assertIs<GamePatchList.Ready>(store.library.gamePatches(game.id)).cheatsOn)
        assertTrue(store.library.setGamePatch(game.id, "Max Money", on = true, cheat = true))
        // Off again: with the last one, the switch Fuse turned on goes too.
        assertTrue(store.library.setGamePatch(game.id, "Infinite Health", on = false, cheat = true))
        assertTrue("EnableCheats = true" in settings.readText())
        assertTrue(store.library.setGamePatch(game.id, "Max Money", on = false, cheat = true))
        assertFalse("EnableCheats" in settings.readText())
        assertFalse("Enable = Max Money" in settings.readText())
    }

    @Test
    fun aPatchTheUserAlsoTurnedOnInPcsx2IsntFusesToTakeBack(): Unit = runBlocking {
        val services = FakeServices(
            FuseData(DesktopDatabase.open(File(cache, "fuse2.db").absolutePath)), cache,
            installedEmulators = listOf(InstalledEmulator(EmulatorId("linux.pcsx2"), "PCSX2", Host.LINUX, "/usr/bin/pcsx2-qt", platforms = setOf(PlatformId("ps2")), detectedVia = "PATH")),
        )
        val home = pcsx2.absolutePath
        services.pcsx2Home = Pcsx2Home(home, "$home/gamesettings", "$home/patches", "$home/patches.zip")
        val store = createFuseStore(services, scope)
        store.sources.add(root.absolutePath, LibrarySourceKind.ROMS_ROOT)
        val game = withTimeout(20_000) { store.library.games(GameQuery()).first { it.size == 1 } }.single()
        val settings = File(pcsx2, "gamesettings/SLUS-20946_0C040404.ini")

        assertTrue(store.library.setGamePatch(game.id, "60 FPS", on = true))
        // The user takes it out in PCSX2, then turns it on there again: now it's theirs.
        settings.writeText("[Patches]\nEnable = Widescreen 16:9\n")
        assertIs<GamePatchList.Ready>(store.library.gamePatches(game.id))
        settings.writeText("[Patches]\nEnable = Widescreen 16:9\nEnable = 60 FPS\n")
        val list = assertIs<GamePatchList.Ready>(store.library.gamePatches(game.id))
        assertEquals(PatchState.ON_IN_EMULATOR, list.patches.first { it.patch.name == "60 FPS" }.state)
        assertFalse(store.library.setGamePatch(game.id, "60 FPS", on = false))
    }
}
