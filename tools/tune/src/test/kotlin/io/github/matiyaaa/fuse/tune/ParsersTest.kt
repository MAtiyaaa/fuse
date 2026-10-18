package io.github.matiyaaa.fuse.tune

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Each reader against small declarations written here by hand in the shape each emulator's code
 * uses (no emulator's code is copied in): what it finds, and what it leaves out.
 */
class ParsersTest {
    private fun List<Found>.at(section: String, key: String) = single { it.section == section && it.key == key }

    /** The string literals in Kotlin [text], as written (a regex would recurse too deep on long ones). */
    private fun literals(text: String): List<String> {
        val out = ArrayList<String>()
        var i = 0
        while (i < text.length) {
            if (text[i] != '"') { i++; continue }
            val start = i++
            while (i < text.length && text[i] != '"') i += if (text[i] == '\\') 2 else 1
            out += text.substring(start, minOf(i + 1, text.length))
            i++
        }
        return out
    }

    @Test
    fun dolphinsInfoDeclarationsWithTheirFileEnumsAndGameSections() {
        val found = Dolphin.parse(
            mapOf(
                Dolphin.GFX to """
                    // Hardware
                    const Info<bool> GFX_VSYNC{{System::GFX, "Hardware", "VSync"}, false};
                    const Info<int> GFX_EFB_SCALE{{System::GFX, "Settings", "InternalResolution"},
                                                  1};
                    const Info<AspectMode> GFX_ASPECT_RATIO{{System::GFX, "Settings", "AspectRatio"}, AspectMode::Auto};
                    const Info<std::string> GFX_DUMP_FORMAT{{System::GFX, "Settings", "DumpFormat"}, "avi"};
                    /* const Info<bool> GFX_OLD{{System::GFX, "Settings", "Old"}, true}; */
                """.trimIndent(),
                Dolphin.MAIN to """
                    const Info<bool> MAIN_CPU_THREAD{{System::Main, "Core", "CPUThread"}, true};
                    const Info<float> MAIN_EMULATION_SPEED{{System::Main, "Core", "EmulationSpeed"}, 1.0f};
                    const Info<bool> MAIN_ANALYTICS{{System::Main, "Analytics", "Enabled"}, false};
                """.trimIndent(),
                Dolphin.GAME to """
                    {"Core", {Config::System::Main, "Core"}},
                    {"Video_Hardware", {Config::System::GFX, "Hardware"}},
                    {"Video_Settings", {Config::System::GFX, "Settings"}},
                """.trimIndent(),
                Dolphin.ENUMS[0] to "enum class AspectMode : int { Auto, ForceWide, ForceStandard, Stretch };",
            ),
        )
        assertEquals(7, found.size, "the commented-out one is left out")
        val vsync = found.at("Hardware", "VSync")
        assertEquals(Found("GFX", "Hardware", "VSync", Kind.BOOL, "False", perGame = true, gameSection = "Video_Hardware"), vsync)
        assertEquals("1", found.at("Settings", "InternalResolution").default)
        val aspect = found.at("Settings", "AspectRatio")
        assertEquals(Kind.ENUM, aspect.kind)
        assertEquals("0", aspect.default)
        assertEquals(listOf(Choice("0", "Auto"), Choice("1", "ForceWide"), Choice("2", "ForceStandard"), Choice("3", "Stretch")), aspect.choices)
        assertEquals("avi", found.at("Settings", "DumpFormat").default)
        val cpu = found.at("Core", "CPUThread")
        assertEquals(null, cpu.file)
        assertEquals(true, cpu.perGame)
        assertEquals(null, cpu.gameSection)
        assertEquals("1.0", found.at("Core", "EmulationSpeed").default)
        // A section a game's file can't hold.
        assertEquals(false, found.at("Analytics", "Enabled").perGame)
    }

    @Test
    fun duckStationsLoadCallsWithEnumNamesByTheirSystem() {
        val found = DuckStation.parse(
            mapOf(
                DuckStation.SETTINGS to """
                    void Settings::Load(const SettingsInterface& si)
                    {
                      gpu_pgxp = si.GetBoolValue("GPU", "PGXPEnable", false);
                      gpu_scale = static_cast<u8>(si.GetIntValue("GPU", "ResolutionScale", 1));
                      speed = si.GetFloatValue("Main", "EmulationSpeed", 1.0f);
                      gpu_renderer = ParseRendererName(si.GetStringViewValue("GPU", "Renderer", GetRendererName(DEFAULT_RENDERER)).c_str())
                                       .value_or(DEFAULT_RENDERER);
                      cpu_mode = ParseCPUExecutionMode(si.GetStringViewValue("CPU", "ExecutionMode", GetCPUExecutionModeName(DEFAULT_CPU_MODE)));
                      backups = si.GetBoolValue("Main", "CreateSaveStateBackups", DEFAULT_BACKUPS);
                      bios = si.GetStringValue("BIOS", "SearchDirectory", "bios");
                    }

                    static constexpr const std::array s_gpu_renderer_names = {
                      "Automatic",
                    #ifdef _WIN32
                      "D3D11", "D3D12",
                    #endif
                    #ifndef __APPLE__
                      "OpenGL",
                    #endif
                    #ifdef ENABLE_VULKAN
                      "Vulkan",
                    #endif
                      "Software",
                    };
                    static constexpr const std::array s_gpu_renderer_display_names = {
                      TRANSLATE_DISAMBIG_NOOP("Settings", "Automatic", "GPURenderer"),
                    #ifdef _WIN32
                      TRANSLATE_DISAMBIG_NOOP("Settings", "Direct3D 11", "GPURenderer"),
                      TRANSLATE_DISAMBIG_NOOP("Settings", "Direct3D 12", "GPURenderer"),
                    #endif
                    #ifndef __APPLE__
                      TRANSLATE_DISAMBIG_NOOP("Settings", "OpenGL", "GPURenderer"),
                    #endif
                    #ifdef ENABLE_VULKAN
                      TRANSLATE_DISAMBIG_NOOP("Settings", "Vulkan", "GPURenderer"),
                    #endif
                      TRANSLATE_DISAMBIG_NOOP("Settings", "Software Renderer", "GPURenderer"),
                    };
                    static constexpr const std::array s_cpu_execution_mode_names = { "Interpreter", "Recompiler" };

                    std::optional<GPURenderer> Settings::ParseRendererName(std::string_view str)
                    {
                      for (const char* name : s_gpu_renderer_names) {}
                      return std::nullopt;
                    }
                    const char* Settings::GetRendererName(GPURenderer renderer)
                    {
                      return s_gpu_renderer_names[static_cast<size_t>(renderer)];
                    }
                    std::optional<CPUExecutionMode> Settings::ParseCPUExecutionMode(std::string_view str)
                    {
                      for (const char* name : s_cpu_execution_mode_names) {}
                    }
                    const char* Settings::GetCPUExecutionModeName(CPUExecutionMode mode)
                    {
                      return s_cpu_execution_mode_names[static_cast<u8>(mode)];
                    }
                """.trimIndent(),
                DuckStation.HEADER to """
                    struct Settings {
                      static constexpr GPURenderer DEFAULT_RENDERER = GPURenderer::Software;
                      static constexpr bool DEFAULT_BACKUPS = true;
                    #ifdef CPU_ARCH_X64
                      static constexpr CPUExecutionMode DEFAULT_CPU_MODE = CPUExecutionMode::Recompiler;
                    #else
                      static constexpr CPUExecutionMode DEFAULT_CPU_MODE = CPUExecutionMode::Interpreter;
                    #endif
                    };
                """.trimIndent(),
                DuckStation.TYPES to """
                    enum class GPURenderer : u8
                    {
                      Automatic,
                    #ifdef _WIN32
                      HardwareD3D11,
                      HardwareD3D12,
                    #endif
                    #ifndef __APPLE__
                      HardwareOpenGL,
                    #endif
                    #ifdef ENABLE_VULKAN
                      HardwareVulkan,
                    #endif
                      Software,
                      Count
                    };
                """.trimIndent(),
            ),
        )
        assertEquals(7, found.size)
        assertEquals(Found(null, "GPU", "PGXPEnable", Kind.BOOL, "false"), found.at("GPU", "PGXPEnable"))
        assertEquals(Kind.INT, found.at("GPU", "ResolutionScale").kind)
        assertEquals("1", found.at("GPU", "ResolutionScale").default)
        val renderer = found.at("GPU", "Renderer")
        assertEquals(Kind.ENUM, renderer.kind)
        // The name at the constant's member, as GetRendererName indexes the array.
        assertEquals("Software", renderer.default)
        assertEquals(
            listOf(
                Choice("Automatic"), Choice("D3D11", "Direct3D 11", setOf("windows")), Choice("D3D12", "Direct3D 12", setOf("windows")),
                Choice("OpenGL", hosts = setOf("windows", "linux", "android")), Choice("Vulkan"), Choice("Software", "Software Renderer"),
            ),
            renderer.choices,
        )
        // Defined once per processor: worked out at run time, so no default in the data.
        assertEquals(null, found.at("CPU", "ExecutionMode").default)
        assertEquals(listOf("Interpreter", "Recompiler"), found.at("CPU", "ExecutionMode").choices.map { it.value })
        assertEquals("true", found.at("Main", "CreateSaveStateBackups").default)
        assertEquals("bios", found.at("BIOS", "SearchDirectory").default)
    }

    @Test
    fun preprocessorConditionsLimitEntriesToTheSystemsTheyName() {
        assertEquals(setOf("windows"), Choices.hostsOf("#ifdef _WIN32"))
        assertEquals(setOf("windows", "linux", "android"), Choices.hostsOf("#ifndef __APPLE__"))
        assertEquals(setOf("macos", "linux", "android"), Choices.hostsOf("#if !defined(_WIN32)"))
        assertEquals(setOf("linux", "android"), Choices.hostsOf("#if defined(__linux__) || defined(__ANDROID__)"))
        assertEquals(null, Choices.hostsOf("#ifdef ENABLE_VULKAN"))
        val entries = Choices.entries(
            """
                "A",
            #ifdef _WIN32
                "B",
            #else
                "C",
            #endif
                nullptr
            """.trimIndent(),
        )
        assertEquals(listOf("A" to null, "B" to setOf("windows"), "C" to setOf("macos", "linux", "android")), entries.map { it.value to it.hosts })
    }

    @Test
    fun ppssppsTablesUnderTheirSectionsWithPerGameFlags() {
        val found = Ppsspp.parse(
            mapOf(
                Ppsspp.CONFIG to """
                    static const ConfigSetting graphicsSettings[] = {
                        ConfigSetting("InternalResolution", SETTING(g_Config, iInternalResolution), 0, CfgFlag::PER_GAME | CfgFlag::REPORT),
                        ConfigSetting("HardwareTransform", SETTING(g_Config, bHardwareTransform), true, CfgFlag::PER_GAME | CfgFlag::REPORT),
                    #if PPSSPP_PLATFORM(ANDROID)
                        ConfigSetting("CustomDriver", SETTING(g_Config, sCustomDriver), "", CfgFlag::DEFAULT),
                    #endif
                        ConfigSetting("GraphicsBackend", SETTING(g_Config, iGPUBackend), &DefaultGPUBackend, &T::To, &T::From, CfgFlag::DEFAULT),
                        ConfigSetting("FrameSkip", SETTING(g_Config, fFrameSkip), 0.5f, CfgFlag::PER_GAME),
                        ConfigSetting("VendorChecks", SETTING(g_Config, bVendorChecks), true, CfgFlag::DONT_SAVE),
                    };
                    static const ConfigSetting soundSettings[] = {
                        ConfigSetting("Enable", SETTING(g_Config, bEnableSound), true, CfgFlag::PER_GAME),
                    };
                    static const ConfigSetting layoutSettings[] = {
                        ConfigSetting("ScreenScale", SETTING(g_Config, fScreenScale), 1.0f, CfgFlag::PER_GAME),
                    };
                    static const ConfigSectionMeta g_sectionMeta[] = {
                        { &g_Config, graphicsSettings, ARRAY_SIZE(graphicsSettings), "Graphics" },
                        { &g_Config, soundSettings, ARRAY_SIZE(soundSettings), "Sound" },
                        { &g_Config.landscape, layoutSettings, ARRAY_SIZE(layoutSettings), "Layout.Landscape", "Graphics" },
                        { &g_Config.portrait, layoutSettings, ARRAY_SIZE(layoutSettings), "Layout.Portrait" },
                    };
                """.trimIndent(),
            ),
        )
        // Never written (DONT_SAVE): left out. One table kept under two sections: in each.
        assertEquals(8, found.size)
        assertEquals("1.0", found.at("Layout.Landscape", "ScreenScale").default)
        assertEquals(Kind.FLOAT, found.at("Layout.Portrait", "ScreenScale").kind)
        assertEquals(Found(null, "Graphics", "InternalResolution", Kind.INT, "0", perGame = true), found.at("Graphics", "InternalResolution"))
        assertEquals("True", found.at("Graphics", "HardwareTransform").default)
        assertEquals(false, found.at("Graphics", "CustomDriver").perGame)
        assertEquals("", found.at("Graphics", "CustomDriver").default)
        assertEquals(null, found.at("Graphics", "GraphicsBackend").default)
        assertEquals(Kind.FLOAT, found.at("Graphics", "FrameSkip").kind)
        assertEquals(Kind.BOOL, found.at("Sound", "Enable").kind)
    }

    @Test
    fun pcsx2sWrappersUnderTheirSectionsWithTypesFromItsHeader() {
        val found = Pcsx2.parse(
            mapOf(
                Pcsx2.CONFIG to """
                    const char* Pcsx2Config::GSOptions::AspectRatioNames[] = {"Stretch", "Auto 4:3/3:2", "4:3", "16:9", nullptr};
                    Pcsx2Config::GSOptions::GSOptions()
                    {
                        bitsets[0] = 0;
                        Reset();
                        Renderer = GSRendererType::Auto;
                    }
                    void Pcsx2Config::GSOptions::Reset()
                    {
                        UpscaleMultiplier = 2.0f;
                        AspectRatio = AspectRatioType::RAuto4_3_3_2;
                    }
                    void Pcsx2Config::GSOptions::LoadSave(SettingsWrapper& wrap)
                    {
                        SettingsWrapSection("EmuCore/GS");
                        SettingsWrapBitBool(VsyncEnable);
                        SettingsWrapEntryEx(UpscaleMultiplier, "upscale_multiplier");
                        SettingsWrapIntEnumEx(Renderer, "Renderer");
                        SettingsWrapEnumEx(AspectRatio, "AspectRatio", AspectRatioNames);
                        SettingsWrapEntry(Zoom);
                    }
                    void Pcsx2Config::UI::LoadSave(SettingsWrapper& wrap)
                    {
                        SettingsWrapSection("UI");
                        SettingsWrapBitBoolEx(StartFullscreen, "StartFullscreen");
                    }
                """.trimIndent(),
                Pcsx2.HEADER to """
                    enum class GSRendererType : s8 { Auto = -1, DX11 = 3, OGL = 12, SW = 13, VK = 14 };
                    enum class AspectRatioType : u8 { Stretch, RAuto4_3_3_2, R4_3, R16_9 };
                    struct GSOptions {
                        float UpscaleMultiplier = 1.0f;
                        GSRendererType Renderer;
                        AspectRatioType AspectRatio;
                        float Zoom = 100.0f;
                    };
                """.trimIndent(),
            ),
        )
        assertEquals(6, found.size)
        // Kept in the bits the constructor clears, and never set: off.
        assertEquals(Found(null, "EmuCore/GS", "VsyncEnable", Kind.BOOL, "false", perGame = true), found.at("EmuCore/GS", "VsyncEnable"))
        // The constructor's value (through the function it calls) over the header's.
        assertEquals(Found(null, "EmuCore/GS", "upscale_multiplier", Kind.FLOAT, "2.0", perGame = true), found.at("EmuCore/GS", "upscale_multiplier"))
        // No constructor: nothing said.
        assertEquals(null, found.at("UI", "StartFullscreen").default)
        val renderer = found.at("EmuCore/GS", "Renderer")
        assertEquals("-1", renderer.default)
        assertEquals(listOf(Choice("-1", "Auto"), Choice("3", "DX11"), Choice("12", "OGL"), Choice("13", "SW"), Choice("14", "VK")), renderer.choices)
        val aspect = found.at("EmuCore/GS", "AspectRatio")
        assertEquals(listOf("Stretch", "Auto 4:3/3:2", "4:3", "16:9"), aspect.choices.map { it.value })
        assertEquals("Auto 4:3/3:2", aspect.default)
        assertEquals("100.0", found.at("EmuCore/GS", "Zoom").default)
        // Not an EmuCore section: PCSX2's own, never a game's.
        assertEquals(false, found.at("UI", "StartFullscreen").perGame)
    }

    @Test
    fun rpcs3sTreeAsPathsWithRangesAndEnumWords() {
        val found = Rpcs3.parse(
            mapOf(
                Rpcs3.CONFIG to """
                    struct cfg_root : cfg::node
                    {
                        struct node_core : cfg::node
                        {
                            node_core(cfg::node* _this) : cfg::node(_this, "Core") {}

                            cfg::_enum<ppu_decoder_type> ppu_decoder{ this, "PPU Decoder", ppu_decoder_type::llvm };
                            cfg::_int<1, 8> ppu_threads{ this, "PPU Threads", 2 };
                            cfg::_bool ppu_debug{ this, "PPU Debug" };
                            cfg::uint64 spu_llvm_upper_bound{ this, "SPU LLVM Upper Bound", 0xffffffffffffffff };
                            cfg::set_entry libraries_control{ this, "Libraries Control" };
                        } core{ this };

                        struct node_io : cfg::node
                        {
                            node_io(cfg::node* _this) : cfg::node(_this, "Input/Output") {}
                            cfg::_bool keep_pads_connected{ this, "Keep Pads Connected", false, true };
                        } io{ this };

                        struct node_video : cfg::node
                        {
                            node_video(cfg::node* _this) : cfg::node(_this, "Video") {}

                            cfg::uint<0, 16> anisotropic_level{ this, "Anisotropic Filter Override", 0, true };

                            struct node_vk : cfg::node
                            {
                                node_vk(cfg::node* _this) : cfg::node(_this, "Vulkan") {}
                                cfg::string adapter{ this, "Adapter" };
                            } vk{ this };

                            cfg::_bool vsync{ this, "VSync", false, true };
                        } video{ this };
                    };
                """.trimIndent(),
                Rpcs3.TYPES to """
                    template <>
                    void fmt_class_string<ppu_decoder_type>::format(std::string& out, u64 arg)
                    {
                        switch (type)
                        {
                        case ppu_decoder_type::_static: return "Interpreter (static)";
                        case ppu_decoder_type::llvm: return "Recompiler (LLVM)";
                        }
                    }
                """.trimIndent(),
            ),
        )
        // A list (set_entry) isn't one value: left out.
        assertEquals(8, found.size)
        val decoder = found.at("Core", "PPU Decoder")
        assertEquals("Recompiler (LLVM)", decoder.default)
        assertEquals(listOf(Choice("Interpreter (static)"), Choice("Recompiler (LLVM)")), decoder.choices)
        assertEquals(Found(null, "Core", "PPU Threads", Kind.INT, "2", 1, 8), found.at("Core", "PPU Threads"))
        assertEquals("false", found.at("Core", "PPU Debug").default)
        assertEquals(16L, found.at("Video", "Anisotropic Filter Override").max)
        assertEquals("", found.at("Video${Found.PATH}Vulkan", "Adapter").default)
        assertEquals("18446744073709551615", found.at("Core", "SPU LLVM Upper Bound").default)
        // A part of the path may hold a slash.
        assertEquals(Kind.BOOL, found.at("Input/Output", "Keep Pads Connected").kind)
        // After the nested node closes, its parent's path again.
        assertEquals(Kind.BOOL, found.at("Video", "VSync").kind)
    }

    @Test
    fun releasesAreChosenAndOrderedByTheirNumbers() {
        val dolphin = EMULATORS.single { it.family == "dolphin" }
        assertEquals(listOf("2609a", "2609", "2606", "2412"), releases(listOf("nJoy", "2412", "2609", "2606", "2609a", "5.0"), dolphin.release))
        val pcsx2 = EMULATORS.single { it.family == "pcsx2" }
        // Odd minor numbers are nightlies.
        assertEquals(listOf("v2.4.0", "v2.2.0"), releases(listOf("v2.2.0", "v2.3.12", "v2.4.0", "v2.9.129"), pcsx2.release))
        val ppsspp = EMULATORS.single { it.family == "ppsspp" }
        assertEquals(listOf("v1.20.4", "v1.20", "v1.19.3"), releases(listOf("v1.19.3", "v1.20", "v1.20.4", "v1.20.4-rc"), ppsspp.release))
    }

    @Test
    fun aSettingIsDescribedByItsNewestReleaseAndListsTheReleasesItIsIn() {
        val newer = listOf(Found(null, "GPU", "A", Kind.BOOL, "true"), Found(null, "GPU", "B", Kind.INT, "2"))
        val older = listOf(Found(null, "GPU", "A", Kind.BOOL, "false"), Found(null, "GPU", "Gone", Kind.INT, "1"))
        val merged = merge(listOf("v2", "v1"), mapOf("v2" to newer, "v1" to older))
        assertEquals(listOf("A", "B", "Gone"), merged.map { it.found.key })
        assertEquals("true", merged[0].found.default)
        assertEquals(listOf("v2", "v1"), merged[0].tags)
        assertEquals(listOf("v2"), merged[1].tags)
        assertEquals(listOf("v1"), merged[2].tags)
    }

    @Test
    fun aChoiceIsWrittenWithWhatItIsCalledAndTheSystemsThatHaveIt() {
        assertEquals("Auto", TuneWriter.choice(Choice("Auto")))
        assertEquals("D3D11\u001eDirect3D 11\u001dwindows", TuneWriter.choice(Choice("D3D11", "Direct3D 11", setOf("windows"))))
        assertEquals("OpenGL\u001dandroid+linux+windows", TuneWriter.choice(Choice("OpenGL", hosts = setOf("windows", "linux", "android"))))
        val line = TuneWriter.line("rpcs3", Merged(Found(null, "Video${Found.PATH}Vulkan", "Adapter", Kind.STRING, ""), listOf("v0.0.43", "v0.0.42")))
        assertEquals(listOf("rpcs3", TuneWriter.NONE, "Video${Found.PATH}Vulkan", "Adapter", "STRING", "", TuneWriter.NONE, TuneWriter.NONE, "", "1", TuneWriter.NONE, "v0.0.43,v0.0.42"), line.split('\t'))
    }

    @Test
    fun theDataIsWrittenAsKotlinTheJvmCanHold() {
        val many = (1..3000).map { Merged(Found(null, "Section", "Key number $it with \"quotes\" and \$dollars", Kind.STRING, "a\\b"), listOf("v1")) }
        val text = TuneWriter.kotlin(listOf(Triple("dolphin", listOf("v1"), many)))
        assertTrue(text.startsWith("// Generated by tools/tune"))
        // No string constant longer than the JVM allows.
        val strings = literals(text)
        assertTrue(strings.all { it.length < 65_000 })
        assertTrue(strings.size > 3)
        assertTrue("\\\$dollars" in text)
    }
}
