package io.github.matiyaaa.fuse.ui.shell.store

import io.github.matiyaaa.fuse.data.FuseData
import io.github.matiyaaa.fuse.data.db.DesktopDatabase
import io.github.matiyaaa.fuse.launch.settings.SettingSource
import io.github.matiyaaa.fuse.launch.settings.Tune
import io.github.matiyaaa.fuse.model.EmulatorId
import io.github.matiyaaa.fuse.model.Host
import io.github.matiyaaa.fuse.model.InstalledEmulator
import io.github.matiyaaa.fuse.model.LibrarySourceKind
import io.github.matiyaaa.fuse.model.PlatformId
import java.io.File
import java.nio.file.Files
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

/** Every Setting (Fuse Tune) end to end: read from the emulator's own files, changed in place and put back. */
class EverySettingStoreTest {
    private lateinit var root: File
    private lateinit var cache: File
    private lateinit var emu: File
    private lateinit var scope: CoroutineScope

    @BeforeTest
    fun setUp() {
        root = Files.createTempDirectory("fuse-every").toFile()
        cache = Files.createTempDirectory("fuse-every-cache").toFile()
        emu = Files.createTempDirectory("fuse-every-emu").toFile()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
        listOf(root, cache, emu).forEach { it.deleteRecursively() }
    }

    private fun services(db: String, vararg emulators: InstalledEmulator) =
        FakeServices(FuseData(DesktopDatabase.open(File(cache, db).absolutePath)), cache, installedEmulators = emulators.toList())

    private fun EverySettingView.Ready.value(section: String, key: String) =
        sections.flatMap { it.values }.single { it.setting.section == section && it.setting.key == key }

    @Test
    fun rpcs3sYamlIsChangedInPlaceAndAGamesOwnFileIsKeptUnderItsTitleId(): Unit = runBlocking {
        val installed = InstalledEmulator(EmulatorId("linux.rpcs3"), "RPCS3", Host.LINUX, "/usr/bin/rpcs3", version = "0.0.43-17000", platforms = setOf(PlatformId("ps3")), detectedVia = "PATH")
        val services = services("rpcs3.db", installed)
        val config = File(emu, "rpcs3").apply { mkdirs() }
        val yml = File(config, "config.yml")
        val text = "Core:\n  PPU Decoder: Recompiler (LLVM)\nVideo:\n  Renderer: Vulkan\n  Resolution Scale: 100\n  Vulkan:\n    Adapter: \"\"\n"
        yml.writeText(text)
        services.rpcs3ConfigDir = config.absolutePath
        File(root, "ps3").mkdirs()
        File(root, "ps3/Demon's Souls [BLUS30443].iso").writeBytes(ByteArray(64))
        val store = createFuseStore(services, scope)
        store.sources.add(root.absolutePath, LibrarySourceKind.ROMS_ROOT)
        val game = withTimeout(20_000) { store.library.games(GameQuery()).first { it.size == 1 } }.single()

        // For every game: every setting the installed release has, read from config.yml.
        val every = assertIs<EverySettingView.Ready>(store.library.everyEmulatorSetting(null, installed.id))
        assertEquals("v0.0.43", every.release)
        assertEquals(Tune.forVersion("rpcs3", "0.0.43").size, every.count)
        assertEquals("Vulkan", every.value("Video", "Renderer").value)
        assertEquals(SettingSource.EVERY_GAME, every.value("Video", "Renderer").source)
        assertEquals("Video / Vulkan", every.sections.single { it.id == "|Video/Vulkan" }.title)
        val scale = every.value("Video", "Resolution Scale").setting
        assertTrue(store.library.setEveryEmulatorSetting(null, installed.id, scale.id, "150"))
        assertEquals(text.replace("Resolution Scale: 100", "Resolution Scale: 150"), yml.readText())
        // Past RPCS3's own range: refused, and nothing written.
        assertFalse(store.library.setEveryEmulatorSetting(null, installed.id, scale.id, "5000"))
        assertEquals(text.replace("Resolution Scale: 100", "Resolution Scale: 150"), yml.readText())

        // A game's own: in custom_configs under its title id, made only when something is set, and said first.
        val own = File(config, "custom_configs/config_BLUS30443.yml")
        val forGame = assertIs<EverySettingView.Ready>(store.library.everyEmulatorSetting(game.id))
        assertNotNull(forGame.gameFileNote, "a game's own file takes the place of RPCS3's database settings")
        assertEquals(SettingSource.EVERY_GAME, forGame.value("Video", "Resolution Scale").source)
        assertEquals("150", forGame.value("Video", "Resolution Scale").value)
        val renderer = forGame.value("Video", "Renderer").setting
        assertTrue(store.library.setEveryEmulatorSetting(game.id, null, renderer.id, "OpenGL"))
        assertEquals("Video:\n  Renderer: OpenGL\n", own.readText())
        val after = assertIs<EverySettingView.Ready>(store.library.everyEmulatorSetting(game.id))
        assertEquals(SettingSource.GAME, after.value("Video", "Renderer").source)
        assertEquals("Vulkan", after.value("Video", "Renderer").everyGame)
        assertNull(after.gameFileNote)
        assertFalse(store.library.setEveryEmulatorSetting(game.id, null, renderer.id, "DirectX 9"), "not a renderer RPCS3 has")

        // Put Back: the game's file goes, and every game's comes back as it was.
        assertTrue(store.library.restoreEmulatorSettings(game.id, null))
        assertFalse(own.exists())
        assertTrue(store.library.restoreEmulatorSettings(null, installed.id))
        assertEquals(text, yml.readText())
    }

    @Test
    fun dolphinsEverySettingGoesInItsOwnFileAndTheGamesSections(): Unit = runBlocking {
        val installed = InstalledEmulator(EmulatorId("linux.dolphin"), "Dolphin", Host.LINUX, "/usr/bin/dolphin-emu", platforms = setOf(PlatformId("ngc")), detectedVia = "PATH")
        val services = services("dolphin.db", installed)
        val disc = ByteArray(0x440).also { b ->
            "GZLE01".encodeToByteArray().copyInto(b)
            byteArrayOf(0xC2.toByte(), 0x33, 0x9F.toByte(), 0x3D).copyInto(b, 0x1C)
        }
        File(root, "gc").mkdirs()
        File(root, "gc/Wind Waker.iso").writeBytes(disc)
        val config = File(emu, "dolphin/config").apply { mkdirs() }
        val gfx = File(config, "GFX.ini").apply { writeText("[Settings]\nInternalResolution = 2\n") }
        File(config, "Dolphin.ini").writeText("[Core]\nCPUThread = True\n")
        services.dolphinHome = io.github.matiyaaa.fuse.launch.settings.DolphinHome(config.absolutePath, File(emu, "dolphin/data/GameSettings").absolutePath)
        val store = createFuseStore(services, scope)
        store.sources.add(root.absolutePath, LibrarySourceKind.ROMS_ROOT)
        val game = withTimeout(20_000) { store.library.games(GameQuery()).first { it.size == 1 } }.single()

        val view = assertIs<EverySettingView.Ready>(store.library.everyEmulatorSetting(game.id))
        // Only what a game's own file can hold, each from where Dolphin reads it.
        assertTrue(view.sections.flatMap { it.values }.all { it.setting.perGame })
        assertEquals("2", view.value("Settings", "InternalResolution").value)
        assertTrue(view.sections.any { it.title == "Graphics: Settings" })
        val widescreen = view.value("Settings", "wideScreenHack")
        assertEquals("False", widescreen.value)
        assertEquals(SettingSource.DEFAULT, widescreen.source)
        // A switch is written as Dolphin writes them, under the game file's own section.
        assertTrue(store.library.setEveryEmulatorSetting(game.id, null, widescreen.setting.id, "true"))
        val own = File(emu, "dolphin/data/GameSettings/GZLE01.ini")
        assertEquals("[Video_Settings]\nwideScreenHack = True\n", own.readText())
        assertEquals("True", assertIs<EverySettingView.Ready>(store.library.everyEmulatorSetting(game.id)).value("Settings", "wideScreenHack").value)
        // The same change in Fuse's own picks and Every Setting is one record: Put Back takes both.
        assertTrue(store.library.setEmulatorSetting(game.id, null, "dolphin.resolution", "3"))
        assertTrue(store.library.restoreEmulatorSettings(game.id, null))
        assertFalse(own.exists())
        assertEquals("[Settings]\nInternalResolution = 2\n", gfx.readText())
    }
}
