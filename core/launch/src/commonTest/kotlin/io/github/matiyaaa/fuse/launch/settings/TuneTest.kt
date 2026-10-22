package io.github.matiyaaa.fuse.launch.settings

import io.github.matiyaaa.fuse.model.Host
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Fuse Tune's data as core:launch reads it, against what Fuse already offers. */
class TuneTest {
    /**
     * Those read at one build: a branch's head (AzaharPlus, Xenia Canary), a rolling tag (Vita3K's
     * `continuous`) or the newest of daily builds that all say the same version (Supermodel's).
     */
    private val ONE_BUILD = setOf("azaharplus", "xenia", "vita3k", "supermodel")

    /** Those with fewer releases read: melonDS's melonDS.toml came with 1.0, so 1.0 and 1.1. */
    private val RELEASES = mapOf("melonds" to 2)

    @Test
    fun everyEmulatorReadHasItsReleasesAndASettingsCountLikeItsOwn() {
        // RetroArch's cores are read as families of their own (`core.snes9x`), at one commit each.
        val emulators = Tune.families.filterNot { it.startsWith(CoreOptionFiles.family("")) || it in PORTS }.toSet()
        assertEquals(
            setOf(
                "dolphin", "duckstation", "ppsspp", "pcsx2", "rpcs3", "devilutionx", "openrct2", "fallout2-ce", "retroarch", "eden", "citron",
                "azahar", "azaharplus", "lime3ds", "mandarine", "cemu", "shadps4", "xenia", "vita3k", "flycast", "mame", "supermodel", "melonds",
            ),
            emulators,
        )
        assertEquals(PORTS, Tune.families.filter { it in PORTS }.toSet(), "every port read")
        // Each has as many as its own settings show; a reader that broke would find a handful.
        val least = mapOf(
            "devilutionx" to 40, "fallout2-ce" to 40, "azahar" to 90, "azaharplus" to 90, "lime3ds" to 70, "mandarine" to 80, "cemu" to 10, "shadps4" to 80, "vita3k" to 70,
            "flycast" to 95, "mame" to 120, "melonds" to 60,
        )
        for (family in emulators) {
            assertEquals(if (family in ONE_BUILD) 1 else RELEASES[family] ?: 4, Tune.releases(family).size, family)
            assertTrue(Tune.forEmulator(family).size > (least[family] ?: 150), "$family: ${Tune.forEmulator(family).size}")
            assertTrue(Tune.forEmulator(family).all { it.releases.isNotEmpty() }, family)
        }
    }

    @Test
    fun theN64RecompilationsAreReadAtTheReleaseTheListNamesATabAFile() {
        for (family in N64_PORTS) {
            assertEquals(1, Tune.releases(family).size, family)
            assertEquals("json", Tune.format(family), family)
            val settings = Tune.forEmulator(family)
            assertTrue(settings.size >= 10, "$family: ${settings.size}")
            // Each tab its own file beside the others, its keys at the top of it.
            assertTrue(settings.all { it.file?.endsWith(".json") == true && it.section.isEmpty() && !it.perGame }, family)
            assertTrue(settings.any { it.file == "graphics.json" }, family)
        }
        // RecompFrontend's options, with the game's own words and its choices' names.
        val resolution = Tune.find("banjorecomp", "graphics.json", "", "res_option")!!
        assertEquals(Triple(TuneKind.CHOICE, "Auto", "Resolution"), Triple(resolution.kind, resolution.default, resolution.label))
        assertEquals(listOf("Original", "Original 2x", "Auto"), resolution.choices.map { it.shown })
        val downsampling = Tune.find("banjorecomp", "graphics.json", "", "ds_option")!!
        assertEquals(listOf("0", "2", "4"), downsampling.choices.map { it.value }, "written as its number, as the tab serializes it")
        // The template's: ultramodern's words for the enum, the default from_json gives.
        val msaa = Tune.find("zelda64recomp", "graphics.json", "", "msaa_option")!!
        assertEquals("MSAA2X", msaa.default)
        assertTrue("MSAA4X" in msaa.choices.map { it.value })
        assertEquals(TuneKind.CHOICE, Tune.find("zelda64recomp", "graphics.json", "", "wm_option")!!.kind, "its type from GraphicsConfig, its default worked out as it runs")
        assertEquals("ini", Tune.format("devilutionx"))
    }

    @Test
    fun noSignInIsEverOffered() {
        // RetroArch's cheevos_password, Flycast's RetroAchievements Token and the like are left out where they are read.
        val names = Regex("(?i)pass(?:word|wd|phrase)|token|secret|api_?key")
        val held = Tune.families.flatMap { Tune.forEmulator(it) }.filter { it.kind == TuneKind.TEXT && names.containsMatchIn(it.key) }
        assertEquals(emptyList(), held.map { it.id })
    }

    @Test
    fun supermodelsSwitchesAreReadEveryWayItReadsThemAndWrittenAsItsOwnFile() {
        assertEquals(listOf("true", "false", "true"), listOf("1", "0", "On").map { Tune.switchShown("supermodel", it) })
        assertEquals(null, Tune.switchWritten("supermodel", true), "true and false, as its own Supermodel.ini has them")
        val vsync = Tune.find("supermodel", null, "Global", "VSync")!!
        assertEquals(Triple(TuneKind.SWITCH, "true", "Video"), Triple(vsync.kind, vsync.default, vsync.tab))
        assertTrue(vsync.perGame, "a game's own section may hold any")
    }

    @Test
    fun retroArchsCoresOptionsAreReadAtOneCommitEachAsChoicesAnyGameCanHave() {
        val cores = Tune.families.filter { it.startsWith(CoreOptionFiles.family("")) }
        assertTrue(cores.size > 60, "${cores.size} cores")
        for (family in cores) {
            assertEquals(1, Tune.releases(family).size, family)
            assertTrue(Tune.forEmulator(family).all { it.kind == TuneKind.CHOICE && it.perGame && it.section.isEmpty() && it.default != null }, family)
            assertTrue(Tune.forEmulator(family).all { s -> s.choices.any { it.value == s.default } }, "$family: every default one of its values")
        }
        // Keys made of the cores' own macros, read as each build has them.
        assertNotNull(Tune.find(CoreOptionFiles.family("mednafen_psx_hw"), null, "", "beetle_psx_hw_renderer"))
        assertNotNull(Tune.find(CoreOptionFiles.family("mednafen_psx"), null, "", "beetle_psx_cpu_freq_scale"))
        val rdp = assertNotNull(Tune.find(CoreOptionFiles.family("mupen64plus_next"), null, "", "mupen64plus-rdp-plugin"))
        assertEquals("RDP Plugin", rdp.label)
        assertEquals("System", Tune.find(CoreOptionFiles.family("mgba"), null, "", "mgba_skip_bios")?.tab, "its category's name")
    }

    @Test
    fun aPortsSettingsComeWithItsOwnNamesAndSwitchesAsItWritesThem() {
        val full = assertNotNull(Tune.find("devilutionx", null, "Graphics", "Fullscreen"))
        assertEquals(TuneKind.SWITCH, full.kind)
        assertEquals("true", full.default, "shown as every switch is, though DevilutionX writes 1")
        assertEquals("Fullscreen", full.label)
        assertEquals("Display the game in windowed or fullscreen mode.", full.about)
        assertEquals("1", Tune.switchWritten("devilutionx", true))
        assertEquals("0", Tune.switchWritten("devilutionx", false))
        assertEquals("false", Tune.switchShown("devilutionx", "0"))
        assertEquals(null, Tune.switchWritten("dolphin", true), "the others write their own words")
        val scale = assertNotNull(Tune.find("devilutionx", null, "Graphics", "Scaling Quality"))
        assertEquals(listOf("Nearest Pixel", "Bilinear", "Anisotropic"), scale.choices.map { it.shown })
        // Hidden in DevilutionX's own settings, so not offered.
        assertEquals(null, Tune.find("devilutionx", null, "Graphics", "Gamma Correction"))
        // OpenRCT2's switches are its own `true` and `false`; Fallout 2's settings are numbers.
        assertEquals("true", assertNotNull(Tune.find("openrct2", null, "general", "use_vsync")).default)
        assertEquals(listOf("IMPERIAL", "METRIC", "SI"), assertNotNull(Tune.find("openrct2", null, "general", "measurement_format")).choices.map { it.value })
        assertEquals(TuneKind.NUMBER, assertNotNull(Tune.find("fallout2-ce", null, "preferences", "game_difficulty")).kind)
    }

    @Test
    fun retroArchsAndEdensSettingsComeWithTheNamesTheirMenusGiveThem() {
        // RetroArch's file has no sections; its menu's tabs group them, and any can be a game's override.
        val vsync = assertNotNull(Tune.find("retroarch", null, "", "video_vsync"))
        assertEquals(TuneKind.SWITCH, vsync.kind)
        assertEquals("Vertical Sync (VSync)", vsync.label)
        assertEquals("Video", vsync.tab)
        assertTrue(Tune.forEmulator("retroarch").all { it.perGame && it.section.isEmpty() })
        val swap = assertNotNull(Tune.find("retroarch", null, "", "video_swap_interval"))
        assertEquals(0L to 4L, swap.min to swap.max)
        // Eden's are kept under its categories' sections, an enum as its member's number.
        val resolution = assertNotNull(Tune.find("eden", null, "Renderer", "resolution_setup"))
        assertEquals("Resolution", resolution.label)
        assertEquals("3", resolution.default)
        assertEquals("1X (720p/1080p)", resolution.choices.single { it.value == "3" }.shown)
        assertTrue(resolution.perGame, "a SwitchableSetting can be set for one game")
        assertEquals(false, Tune.find("eden", null, "Debugging", "use_gdbstub")?.perGame, "a plain Setting only for every game")
        // Azahar's, under the groups its Qt config keeps them in, with its own screens' words.
        val api = assertNotNull(Tune.find("azahar", null, "Renderer", "graphics_api"))
        assertEquals("Graphics API", api.label)
        assertEquals(TuneKind.CHOICE, api.kind)
        assertTrue(api.perGame)
        assertEquals(false, Tune.find("azahar", null, "Data%20Storage", "use_virtual_sd")?.perGame, "a group written with Qt's %20, for every game")
        assertEquals(0L to 18L, Tune.find("azahar", null, "Renderer", "resolution_factor")?.let { it.min to it.max })
    }

    @Test
    fun everySettingFuseOffersIsInTheEmulatorsOwnCodeWithValuesItReads() {
        for (host in listOf(Host.LINUX, Host.WINDOWS, Host.MACOS)) {
            for (family in NativeSettings.families) {
                for (s in NativeSettings.forEmulator(family, host)) {
                    val t = assertNotNull(Tune.find(family, s.file, s.section, s.key), "${s.id}: ${s.section} ${s.key} isn't in ${family}'s code")
                    assertEquals(s.perGame, t.perGame, "${s.id} per game")
                    if (s.gameSection != null) assertEquals(s.gameSection, t.gameSection, "${s.id} game section")
                    for (c in s.choices) assertTrue(t.accepts(c.value, host), "${s.id} on $host: ${family} doesn't read ${c.value} (${t.kind}, ${t.choicesOn(host).map { it.value }})")
                    assertTrue(t.accepts(s.default, host), "${s.id}: default ${s.default}")
                }
            }
        }
    }

    @Test
    fun choicesASystemsBuildHasntGotArentOffered() {
        val renderer = assertNotNull(Tune.find("duckstation", null, "GPU", "Renderer"))
        assertEquals("Direct3D 11", renderer.choices.first { it.value == "D3D11" }.shown)
        assertTrue(renderer.choicesOn(Host.WINDOWS).any { it.value == "D3D12" })
        assertTrue(renderer.choicesOn(Host.LINUX).none { it.value == "D3D12" || it.value == "Metal" })
        assertTrue(renderer.accepts("Vulkan", Host.LINUX))
        assertTrue(!renderer.accepts("Metal", Host.LINUX))
    }

    @Test
    fun valuesAreCheckedAsTheEmulatorReadsThem() {
        val scale = assertNotNull(Tune.find("rpcs3", null, "Video", "Resolution Scale"))
        assertEquals(TuneKind.NUMBER, scale.kind)
        assertTrue(scale.accepts("150", Host.LINUX))
        assertTrue(!scale.accepts("5000", Host.LINUX), "past RPCS3's own range")
        assertTrue(!scale.accepts("1.5", Host.LINUX))
        val vsync = assertNotNull(Tune.find("dolphin", "GFX", "Hardware", "VSync"))
        assertTrue(vsync.accepts("True", Host.LINUX))
        assertTrue(!vsync.accepts("maybe", Host.LINUX))
        // RPCS3's unsigned 64-bit numbers reach past a Long.
        val bound = assertNotNull(Tune.find("rpcs3", null, "Core", "SPU LLVM Upper Bound"))
        assertTrue(bound.accepts(bound.default!!, Host.LINUX))
        // A YAML section is its path of nodes.
        assertEquals(listOf("Video", "Vulkan"), assertNotNull(Tune.find("rpcs3", null, "Video/Vulkan", "Adapter")).path)
    }

    @Test
    fun theInstalledVersionDecidesWhichSettingsShow() {
        // A core's one release is the commit read, not a version a core says it is; so is a project's that tags no releases.
        for (family in Tune.families.filterNot { it.startsWith(CoreOptionFiles.family("")) || it in ONE_BUILD }) {
            val read = Tune.releases(family)
            // A port read at its one rolling build (Quake3e's `latest`) has no number to place a version by.
            if (TuneVersions.numbers(read.first()).isEmpty()) continue
            assertEquals(read.first(), Tune.releaseFor(family, null), "unknown: the newest")
            val newer = TuneVersions.numbers(read.first()).let { listOf(it[0] + 1) + it.drop(1) }.joinToString(".")
            assertEquals(read.first(), Tune.releaseFor(family, newer), "newer than any read ($newer)")
            assertEquals(read.last(), Tune.releaseFor(family, "0.0.1"), "older than any read: the oldest")
            for (r in read) {
                assertEquals(r, Tune.releaseFor(family, r))
                assertTrue(Tune.forVersion(family, r).all { r in it.releases }, "$family $r")
            }
        }
        // A setting only the newest release has isn't offered to an older one.
        val newer = Tune.families.flatMap { f -> Tune.forEmulator(f).filter { Tune.releases(f).last() !in it.releases } }
        assertTrue(newer.isNotEmpty(), "some setting came in a release read")
        for (s in newer) assertTrue(s !in Tune.forVersion(s.family, Tune.releases(s.family).last()), s.id)
    }

    @Test
    fun versionsAreReadByTheirNumbers() {
        assertEquals(listOf(0L, 1L, 11894L), TuneVersions.numbers("v0.1-11894"))
        assertEquals(listOf(2609L, 1L), TuneVersions.numbers("2609a"))
        assertEquals(listOf(2L, 8L, 2L), TuneVersions.numbers("PCSX2 v2.8.2"))
        assertEquals(listOf(2L, 8L, 2L), TuneVersions.numbers("pcsx2-v2.8.2-linux-appimage-x64-Qt"))
        assertEquals(listOf(1L, 20L, 4L), TuneVersions.numbers("1.20.4-rc1"))
        assertTrue(TuneVersions.compare(TuneVersions.numbers("2609a"), TuneVersions.numbers("2609")) > 0)
        assertTrue(TuneVersions.compare(TuneVersions.numbers("2609-120"), TuneVersions.numbers("2609a")) > 0)
        assertTrue(TuneVersions.compare(TuneVersions.numbers("v1.20"), TuneVersions.numbers("v1.20.4")) < 0)
        assertEquals("v0.1-11826", Tune.releaseFor("duckstation", "0.1-11830"))
    }
}

/** The ports Fuse Tune reads beside the emulators (DevilutionX, OpenRCT2 and Fallout 2 CE are read as those are). */
private val PORTS: Set<String> get() = N64_PORTS + setOf(
    "fallout1-ce", "shipwright", "2ship2harkinian", "starship", "spaghettikart", "ghostship", "lighthouse", "paperboat", "battleship",
    "dhewm3", "openq4", "quake3e", "lilium-voyager", "spearmint", "openmohaa", "iortcw", "openjk", "openjo",
    "perfect-dark-remaster-recomp", "wet-recomp", "rsdkv3", "rsdkv4", "sm64coopdx",
)

/** The N64 recompilations Fuse Ports lists, read by Fuse Tune at the release the list names. */
private val N64_PORTS = setOf(
    "zelda64recomp", "dino-recomp", "megaman64-recomp", "bm64-recomp", "goemon64-recomp", "dnzh-recomp", "mariokart64-recomp", "starfox64-recomp",
    "banjorecomp", "sssv-recomp", "harvestmoon64-recomp", "dk64-recomp", "lamborghini-recomp", "aerogauge-recomp", "snowboardkids2-recomp",
    "battlezone-recomp", "dinotale",
)
