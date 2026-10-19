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

    @Test
    fun retroArchsGameSettingsGoInItsOverrideForTheCoreThatPlaysIt(): Unit = runBlocking {
        val installed = InstalledEmulator(EmulatorId("linux.retroarch"), "RetroArch", Host.LINUX, "/usr/bin/retroarch", version = "1.22.2", platforms = setOf(PlatformId("gba")), detectedVia = "PATH")
        val services = services("retroarch.db", installed)
        val base = File(emu, "retroarch").apply { mkdirs() }
        val cfg = File(base, "retroarch.cfg")
        val text = "video_vsync = \"true\"\nvideo_swap_interval = \"1\"\n"
        cfg.writeText(text)
        services.retroArchHome = io.github.matiyaaa.fuse.launch.patches.RetroArchHome(cfg.absolutePath, "${base.absolutePath}/cheats", "${base.absolutePath}/config")
        File(root, "gba").mkdirs()
        File(root, "gba/Golden Sun (USA).gba").writeBytes(ByteArray(64))
        val store = createFuseStore(services, scope)
        store.sources.add(root.absolutePath, LibrarySourceKind.ROMS_ROOT)
        val game = withTimeout(20_000) { store.library.games(GameQuery()).first { it.size == 1 } }.single()

        // For every game: retroarch.cfg, grouped by the tabs of RetroArch's own menu.
        val every = assertIs<EverySettingView.Ready>(store.library.everyEmulatorSetting(null, installed.id))
        assertEquals("v1.22.2", every.release)
        val vsync = every.value("", "video_vsync")
        assertEquals("Vertical Sync (VSync)", vsync.setting.label)
        assertEquals("true", vsync.value)
        assertEquals(SettingSource.EVERY_GAME, vsync.source)
        assertTrue(every.sections.single { it.title == "Video" }.values.any { it.setting.key == "video_vsync" })
        val swap = every.value("", "video_swap_interval").setting
        assertFalse(store.library.setEveryEmulatorSetting(null, installed.id, swap.id, "9"), "past the menu's own range")

        // A game's own: the override RetroArch reads for it, under the core that plays it.
        val override = File(base, "config/mGBA/Golden Sun (USA).cfg")
        val forGame = assertIs<EverySettingView.Ready>(store.library.everyEmulatorSetting(game.id))
        assertEquals(SettingSource.EVERY_GAME, forGame.value("", "video_vsync").source)
        assertTrue(store.library.setEveryEmulatorSetting(game.id, null, vsync.setting.id, "false"))
        assertEquals("video_vsync = \"false\"\n", override.readText())
        val after = assertIs<EverySettingView.Ready>(store.library.everyEmulatorSetting(game.id)).value("", "video_vsync")
        assertEquals("false" to SettingSource.GAME, after.value to after.source)
        assertEquals("true", after.everyGame)
        assertEquals(text, cfg.readText(), "every game's stay as they were")

        // Put Back: the override Fuse made goes again.
        assertTrue(store.library.restoreEmulatorSettings(game.id, null))
        assertFalse(override.exists())
    }

    @Test
    fun withTheVersionUnknownASettingOnlySomeReleasesHaveIsLockedWithTheReason(): Unit = runBlocking {
        // Found on the PATH with no version Fuse can read.
        val installed = InstalledEmulator(EmulatorId("linux.pcsx2"), "PCSX2", Host.LINUX, "/usr/bin/pcsx2-qt", version = "git", platforms = setOf(PlatformId("ps2")), detectedVia = "PATH")
        val services = services("unknown.db", installed)
        val home = File(emu, "PCSX2").apply { mkdirs() }
        services.pcsx2Home = io.github.matiyaaa.fuse.launch.patches.Pcsx2Home(home.absolutePath, "${home.absolutePath}/gamesettings", "${home.absolutePath}/patches", null)
        val ini = File(services.pcsx2Home!!.settingsFile).apply { parentFile.mkdirs(); writeText("[EmuCore/GS]\nupscale_multiplier = 2\n") }
        val store = createFuseStore(services, scope)

        val view = assertIs<EverySettingView.Ready>(store.library.everyEmulatorSetting(null, installed.id))
        assertTrue(view.versionUnknown)
        assertNull(view.release)
        assertEquals(Tune.forEmulator("pcsx2").size, view.count, "every release's settings")
        val newer = Tune.forEmulator("pcsx2").first { it.releases.size < Tune.releases("pcsx2").size && Tune.releases("pcsx2").first() in it.releases }
        val bold = view.sections.flatMap { it.values }.single { it.setting.id == newer.id }
        assertEquals("Locked: only in PCSX2 ${newer.releases.last()} and later, and Fuse can't tell which version this is", bold.locked)
        assertFalse(store.library.setEveryEmulatorSetting(null, installed.id, newer.id, newer.default ?: "true"), "the installed one may not read it")
        // One every release has stays open.
        val scale = view.value("EmuCore/GS", "upscale_multiplier")
        assertNull(scale.locked)
        assertTrue(store.library.setEveryEmulatorSetting(null, installed.id, scale.setting.id, "3"))
        assertEquals("[EmuCore/GS]\nupscale_multiplier = 3\n", ini.readText())
    }

    @Test
    fun aGamesCoreOptionsGoInItsOwnOptFileMadeWithEveryOptionAsRetroArchMakesIt(): Unit = runBlocking {
        val installed = InstalledEmulator(EmulatorId("linux.retroarch"), "RetroArch", Host.LINUX, "/usr/bin/retroarch", version = "1.22.2", platforms = setOf(PlatformId("gba")), detectedVia = "PATH")
        val services = services("cores.db", installed)
        val base = File(emu, "retroarch").apply { mkdirs() }
        val cfg = File(base, "retroarch.cfg").apply { writeText("video_vsync = \"true\"\n") }
        services.retroArchHome = io.github.matiyaaa.fuse.launch.patches.RetroArchHome(cfg.absolutePath, "${base.absolutePath}/cheats", "${base.absolutePath}/config")
        val perCore = File(base, "config/mGBA/mGBA.opt").apply { parentFile.mkdirs() }
        val coreText = "mgba_skip_bios = \"ON\"\n"
        perCore.writeText(coreText)
        File(root, "gba").mkdirs()
        File(root, "gba/Golden Sun (USA).gba").writeBytes(ByteArray(64))
        val store = createFuseStore(services, scope)
        store.sources.add(root.absolutePath, LibrarySourceKind.ROMS_ROOT)
        val game = withTimeout(20_000) { store.library.games(GameQuery()).first { it.size == 1 } }.single()
        val options = Tune.forEmulator("core.mgba")
        assertTrue(options.isNotEmpty(), "Fuse Tune read mGBA's options")

        // Beside RetroArch's own: the core's, by its own categories, each as the core's file for every game has it.
        val view = assertIs<EverySettingView.Ready>(store.library.everyEmulatorSetting(game.id))
        val skip = view.sections.filter { it.title.startsWith("mGBA Core") }.flatMap { it.values }.single { it.setting.key == "mgba_skip_bios" }
        assertEquals("ON" to SettingSource.EVERY_GAME, skip.value to skip.source)
        val other = options.first { it.key != "mgba_skip_bios" }
        assertEquals(SettingSource.DEFAULT, view.sections.flatMap { it.values }.single { it.setting.id == other.id }.source)

        // The game's own file is made with every option as the game has it now, RetroArch reading it alone.
        val own = File(base, "config/mGBA/Golden Sun (USA).opt")
        assertTrue(store.library.setEveryEmulatorSetting(game.id, null, skip.setting.id, "OFF"))
        val written = io.github.matiyaaa.fuse.launch.settings.CfgText(own.readText())
        assertEquals("OFF", written.values("", "mgba_skip_bios").single())
        assertEquals(other.default, written.values("", other.key).single())
        assertTrue(options.all { written.values("", it.key).isNotEmpty() })
        val after = assertIs<EverySettingView.Ready>(store.library.everyEmulatorSetting(game.id)).sections.flatMap { it.values }
        val skipAfter = after.single { it.setting.id == skip.setting.id }
        assertEquals(Triple("OFF", SettingSource.GAME, "ON"), Triple(skipAfter.value, skipAfter.source, skipAfter.everyGame))
        assertFalse(store.library.setEveryEmulatorSetting(game.id, null, skip.setting.id, "MAYBE"), "not a value mGBA has")
        // Same as Every Game: the core's value for every game, written, since RetroArch reads the game's file alone.
        assertTrue(store.library.setEveryEmulatorSetting(game.id, null, skip.setting.id, null))
        assertEquals("ON", io.github.matiyaaa.fuse.launch.settings.CfgText(own.readText()).values("", "mgba_skip_bios").single())
        assertEquals(coreText, perCore.readText(), "every game's stay as they were")

        // Put Back: every key Fuse wrote goes, so the file Fuse made does too.
        assertTrue(store.library.restoreEmulatorSettings(game.id, null))
        assertFalse(own.exists())

        // With a game's own options turned off in RetroArch, it reads none, so Fuse offers none.
        cfg.writeText("game_specific_options = \"false\"\n")
        assertTrue(assertIs<EverySettingView.Ready>(store.library.everyEmulatorSetting(game.id)).sections.none { it.title.startsWith("mGBA Core") })
    }

    @Test
    fun shadPs4sJsonIsChangedInPlaceAndAGamesOwnFileIsKeptUnderItsSerial(): Unit = runBlocking {
        val installed = InstalledEmulator(EmulatorId("linux.shadps4"), "shadPS4", Host.LINUX, "/opt/shadps4/Shadps4-sdl.AppImage", version = "0.20.0", platforms = setOf(PlatformId("ps4")), detectedVia = "AppImage")
        val services = services("shadps4.db", installed)
        val user = File(emu, "shadPS4").apply { mkdirs() }
        val config = File(user, "config.json")
        val text = "{\n  \"GPU\": {\n    \"readbacks_mode\": 0,\n    \"fsr_enabled\": true\n  },\n  \"General\": {\n    \"install_dirs\": [\n      {\n        \"enabled\": true,\n        \"path\": \"/games\"\n      }\n    ]\n  }\n}\n"
        config.writeText(text)
        services.shadPs4UserDir = user.absolutePath
        File(root, "ps4/Bloodborne [CUSA00900]").mkdirs()
        File(root, "ps4/Bloodborne [CUSA00900]/eboot.bin").writeBytes(ByteArray(64))
        val store = createFuseStore(services, scope)
        store.sources.add(root.absolutePath, LibrarySourceKind.ROMS_ROOT)
        val game = withTimeout(20_000) { store.library.games(GameQuery()).first { it.isNotEmpty() } }.first()

        val every = assertIs<EverySettingView.Ready>(store.library.everyEmulatorSetting(null, installed.id))
        val fsr = every.value("GPU", "fsr_enabled")
        assertEquals("true" to SettingSource.EVERY_GAME, fsr.value to fsr.source)
        assertTrue(store.library.setEveryEmulatorSetting(null, installed.id, fsr.setting.id, "false"))
        assertEquals(text.replace("\"fsr_enabled\": true", "\"fsr_enabled\": false"), config.readText(), "in place, the rest as it was")

        // A game's own: custom_configs/<serial>.json, holding only what is its own.
        val forGame = assertIs<EverySettingView.Ready>(store.library.everyEmulatorSetting(game.id))
        assertTrue(forGame.sections.flatMap { it.values }.all { it.setting.perGame })
        val readbacks = forGame.value("GPU", "readbacks_mode").setting
        assertTrue(store.library.setEveryEmulatorSetting(game.id, null, readbacks.id, "2"))
        val own = File(user, "custom_configs/CUSA00900.json")
        assertEquals("{\n  \"GPU\": {\n    \"readbacks_mode\": 2\n  }\n}\n", own.readText())
        val after = assertIs<EverySettingView.Ready>(store.library.everyEmulatorSetting(game.id)).value("GPU", "readbacks_mode")
        assertEquals("2" to SettingSource.GAME, after.value to after.source)

        assertTrue(store.library.restoreEmulatorSettings(game.id, null))
        assertFalse(own.exists())
        assertTrue(store.library.restoreEmulatorSettings(null, installed.id))
        assertEquals(text, config.readText())
    }

    @Test
    fun cemusGameProfileIsMadeAsACopyOfTheOneItShipsSinceItReadsOnlyOne(): Unit = runBlocking {
        val installed = InstalledEmulator(EmulatorId("linux.cemu"), "Cemu", Host.LINUX, "/usr/bin/cemu", version = "2.6", platforms = setOf(PlatformId("wiiu")), detectedVia = "PATH")
        val services = services("cemu.db", installed)
        val data = File(emu, "Cemu/data").apply { mkdirs() }
        val config = File(emu, "Cemu/config").apply { mkdirs() }
        services.cemuHome = io.github.matiyaaa.fuse.launch.settings.CemuHome(data.absolutePath, config.absolutePath)
        // The profile Cemu ships for the game, which it reads while the game has none of its own.
        val shipped = File(data, "gameProfiles/default/0005000010145d00.ini").apply { parentFile.mkdirs(); writeText("# Mario Kart 8\n\n[Graphics]\naccurateShaderMul = false\n") }
        File(root, "wiiu/Mario Kart 8 [0005000010145D00]/code").mkdirs()
        File(root, "wiiu/Mario Kart 8 [0005000010145D00]/code/Turbo.rpx").writeBytes(ByteArray(64))
        val store = createFuseStore(services, scope)
        store.sources.add(root.absolutePath, LibrarySourceKind.ROMS_ROOT)
        val game = withTimeout(20_000) { store.library.games(GameQuery()).first { it.isNotEmpty() } }.first()

        assertIs<EverySettingView.Unavailable>(store.library.everyEmulatorSetting(null, installed.id), "Cemu's settings for every game stay in Cemu")
        val view = assertIs<EverySettingView.Ready>(store.library.everyEmulatorSetting(game.id))
        assertNotNull(view.gameFileNote, "says the shipped profile is read until the game has its own")
        val mul = view.value("Graphics", "accurateShaderMul")
        assertEquals("false" to SettingSource.EVERY_GAME, mul.value to mul.source)
        val cpu = view.value("CPU", "cpuMode").setting
        assertTrue(store.library.setEveryEmulatorSetting(game.id, null, cpu.id, "Auto"))
        val own = File(config, "gameProfiles/0005000010145d00.ini")
        val text = own.readText()
        assertTrue(text.startsWith("# "), "its name first, as Cemu writes it")
        assertTrue("accurateShaderMul = false" in text && "cpuMode = Auto" in text, text)
        val after = assertIs<EverySettingView.Ready>(store.library.everyEmulatorSetting(game.id))
        assertEquals(SettingSource.GAME, after.value("Graphics", "accurateShaderMul").source, "copied, so the game reads what it did")
        // With a profile of its own, what it hasn't got is Cemu's own default.
        val pad = after.value("General", "startWithPadView")
        assertEquals("false" to SettingSource.DEFAULT, pad.value to pad.source)

        assertTrue(store.library.restoreEmulatorSettings(game.id, null))
        assertFalse(own.exists(), "every key Fuse wrote went, so the file did too")
        assertTrue(shipped.isFile, "Cemu's own never touched")
    }

    @Test
    fun azaharsGameFileIsItsTitleIdsAndItsGroupsAreQtsOwn(): Unit = runBlocking {
        val installed = InstalledEmulator(EmulatorId("linux.azahar"), "Azahar", Host.LINUX, "/usr/bin/azahar", version = "2126.2", platforms = setOf(PlatformId("3ds")), detectedVia = "PATH")
        val services = services("azahar.db", installed)
        val config = File(emu, "azahar-emu").apply { mkdirs() }
        val qt = File(config, "qt-config.ini")
        val text = "[Core]\ncpu_clock_percentage\\default=true\ncpu_clock_percentage=100\n\n[Data%20Storage]\nuse_virtual_sd\\default=true\nuse_virtual_sd=true\n"
        qt.writeText(text)
        services.azaharConfigDir = config.absolutePath
        File(root, "3ds").mkdirs()
        File(root, "3ds/Kirby [0004000000183600].3ds").writeBytes(ByteArray(64))
        val store = createFuseStore(services, scope)
        store.sources.add(root.absolutePath, LibrarySourceKind.ROMS_ROOT)
        val game = withTimeout(20_000) { store.library.games(GameQuery()).first { it.size == 1 } }.single()

        val every = assertIs<EverySettingView.Ready>(store.library.everyEmulatorSetting(null, installed.id))
        val clock = every.value("Core", "cpu_clock_percentage")
        assertEquals("100" to SettingSource.DEFAULT, clock.value to clock.source)
        assertTrue(every.sections.any { it.title == "Data Storage" }, "Qt's %20 read as a space")
        assertTrue(store.library.setEveryEmulatorSetting(null, installed.id, clock.setting.id, "150"))
        assertEquals(text.replace("cpu_clock_percentage\\default=true\ncpu_clock_percentage=100", "cpu_clock_percentage\\default=false\ncpu_clock_percentage=150"), qt.readText())

        // A game's own: custom/<title id>.ini, as Azahar writes it.
        val own = File(config, "custom/0004000000183600.ini")
        assertTrue(store.library.setEveryEmulatorSetting(game.id, null, clock.setting.id, "200"))
        assertEquals("[Core]\ncpu_clock_percentage\\use_global=false\ncpu_clock_percentage=200\n", own.readText())
        val after = assertIs<EverySettingView.Ready>(store.library.everyEmulatorSetting(game.id)).value("Core", "cpu_clock_percentage")
        assertEquals("200" to SettingSource.GAME, after.value to after.source)
        assertEquals("150", after.everyGame)

        assertTrue(store.library.restoreEmulatorSettings(game.id, null))
        assertFalse(own.exists())
        assertTrue(store.library.restoreEmulatorSettings(null, installed.id))
        assertEquals(text, qt.readText())
    }

    @Test
    fun edensValuesAreItsKeysTogetherAndAGamesOwnFileIsItsTitleIds(): Unit = runBlocking {
        val installed = InstalledEmulator(EmulatorId("linux.eden"), "Eden", Host.LINUX, "/usr/bin/eden", version = "0.2.1", platforms = setOf(PlatformId("switch")), detectedVia = "PATH")
        val services = services("eden.db", installed)
        val config = File(emu, "eden").apply { mkdirs() }
        val qt = File(config, "qt-config.ini")
        val text = "[Renderer]\nresolution_setup\\default=false\nresolution_setup=6\nuse_vsync\\default=true\nuse_vsync=0\n"
        qt.writeText(text)
        services.edenConfigDir = config.absolutePath
        File(root, "switch").mkdirs()
        File(root, "switch/Tears of the Kingdom [0100F2C0115B6000][v0].nsp").writeBytes(ByteArray(64))
        val store = createFuseStore(services, scope)
        store.sources.add(root.absolutePath, LibrarySourceKind.ROMS_ROOT)
        val game = withTimeout(20_000) { store.library.games(GameQuery()).first { it.size == 1 } }.single()

        // For every game: a value counts only where it isn't marked as Eden's default.
        val every = assertIs<EverySettingView.Ready>(store.library.everyEmulatorSetting(null, installed.id))
        assertEquals("v0.2.1", every.release)
        val resolution = every.value("Renderer", "resolution_setup")
        assertEquals("Resolution", resolution.setting.label)
        assertEquals("6" to SettingSource.EVERY_GAME, resolution.value to resolution.source)
        val vsync = every.value("Renderer", "use_vsync")
        assertEquals("2" to SettingSource.DEFAULT, vsync.value to vsync.source, "marked the default, whatever the key holds")
        assertTrue(store.library.setEveryEmulatorSetting(null, installed.id, vsync.setting.id, "1"))
        assertEquals(text.replace("use_vsync\\default=true\nuse_vsync=0", "use_vsync\\default=false\nuse_vsync=1"), qt.readText())

        // A game's own: custom/<title id>.ini, written as Eden writes it, its own only where it says so.
        val own = File(config, "custom/0100F2C0115B6000.ini")
        val forGame = assertIs<EverySettingView.Ready>(store.library.everyEmulatorSetting(game.id))
        assertEquals("6" to SettingSource.EVERY_GAME, forGame.value("Renderer", "resolution_setup").let { it.value to it.source })
        assertTrue(store.library.setEveryEmulatorSetting(game.id, null, resolution.setting.id, "3"))
        assertEquals("[Renderer]\nresolution_setup\\use_global=false\nresolution_setup\\default=true\nresolution_setup=3\n", own.readText())
        val after = assertIs<EverySettingView.Ready>(store.library.everyEmulatorSetting(game.id)).value("Renderer", "resolution_setup")
        assertEquals("3" to SettingSource.GAME, after.value to after.source)
        assertEquals("6", after.everyGame)

        // Put Back: each of Eden's keys as it was, and the game's file Fuse made goes.
        assertTrue(store.library.restoreEmulatorSettings(game.id, null))
        assertFalse(own.exists())
        assertTrue(store.library.restoreEmulatorSettings(null, installed.id))
        assertEquals(text, qt.readText())
    }
}
