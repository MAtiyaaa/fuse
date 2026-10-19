package io.github.matiyaaa.fuse.tune

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
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
        assertEquals(listOf("rpcs3", TuneWriter.NONE, "Video${Found.PATH}Vulkan", "Adapter", "STRING", "", TuneWriter.NONE, TuneWriter.NONE, "", "1", TuneWriter.NONE, "v0.0.43,v0.0.42", TuneWriter.NONE, TuneWriter.NONE, TuneWriter.NONE), line.split('\t'))
        val named = TuneWriter.line("devilutionx", Merged(Found(null, "Graphics", "Zoom", Kind.BOOL, "0", label = "Zoom", about = "Zoom on when enabled."), listOf("1.5.5")))
        assertEquals(listOf("Zoom", "Zoom on when enabled.", TuneWriter.NONE), named.split('\t').takeLast(3))
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

    @Test
    fun anEmulatorsWordsAreWrittenWithoutDashesAndItsValuesAsTheyAre() {
        val f = Found(null, "", "core_palette", Kind.ENUM, "modern\u2013balanced", choices = listOf(Choice("modern\u2013balanced", "Modern \u2013 Balanced")), label = "Range 0\u2013100", about = "Fast\u2014or not")
        val line = TuneWriter.line("core.x", Merged(f, listOf("abc")))
        assertTrue("Modern, Balanced" in line && "Range 0-100" in line && "Fast, or not" in line, line)
        assertTrue("modern\u2013balanced" in line, "the value the core reads stays")
    }

    @Test
    fun onlyTheLinesABuildCompilesAreKept() {
        val text = """
            a
            #ifndef USE_SDL1
            b
            #if defined(NXDK) || defined(__ANDROID__)
            c
            #elif SDL_VERSION_ATLEAST(2, 0, 0)
            d
            #else
            e
            #endif
            #endif
            #ifdef USE_SDL1
            f
            #else
            g
            #endif
            #if !defined(USE_SDL1) || \
                defined(__3DS__)
            h
            #endif
            i
        """.trimIndent()
        val kept = Source.select(text) { mapOf("SDL_VERSION_ATLEAST(2,0,0)" to true)[it] }
        assertEquals(listOf("a", "b", "d", "g", "h", "i"), kept.lines().filter { it.isNotBlank() })
        assertEquals(text.lines().size, kept.lines().size, "lines left out stay as empty lines")
        assertTrue(Source.condition(" !(defined(A) && B) || C", mapOf("A" to true, "B" to true)::get).not())
        assertTrue(Source.condition(" 1", { null }))
    }

    @Test
    fun devilutionXsOptionsUnderTheirCategoriesWithTheirNamesAsADesktopBuildHasThem() {
        val header = """
            enum class ScalingQuality : uint8_t {
                NearestPixel,
                BilinearFiltering,
                AnisotropicFiltering,
            };
            enum class FrameRateControl : uint8_t {
                None = 0,
            #ifndef USE_SDL1
                VerticalSync = 1,
            #endif
                CPUSleep = 2,
            };
            struct GraphicsOptions : OptionCategoryBase {
                OptionEntryResolution resolution;
                OptionEntryBoolean fullscreen;
            #ifndef USE_SDL1
                OptionEntryEnum<ScalingQuality> scaleQuality;
            #endif
                OptionEntryEnum<FrameRateControl> frameRateControl;
                OptionEntryInt<int> gammaCorrection;
            };
            struct AudioOptions : OptionCategoryBase {
                OptionEntryInt<std::uint32_t> sampleRate;
                OptionEntryInt<int> soundVolume;
            };
        """.trimIndent()
        val options = """
            #ifndef DEFAULT_AUDIO_SAMPLE_RATE
            #define DEFAULT_AUDIO_SAMPLE_RATE 22050
            #endif
            GraphicsOptions::GraphicsOptions()
                : OptionCategoryBase("Graphics", N_("Graphics"), N_("Graphics Settings"))
                , fullscreen("Fullscreen", OptionEntryFlags::CantChangeInGame, N_("Fullscreen"), N_("Display the game in windowed or fullscreen mode."), true)
            #ifndef USE_SDL1
                , scaleQuality("Scaling Quality", OptionEntryFlags::None, N_("Scaling Quality"), N_("Enables optional filters."), ScalingQuality::AnisotropicFiltering,
                      {
                          { ScalingQuality::NearestPixel, N_("Nearest Pixel") },
                          { ScalingQuality::BilinearFiltering, N_("Bilinear") },
                          { ScalingQuality::AnisotropicFiltering, N_("Anisotropic") },
                      })
            #endif
                , frameRateControl("Frame Rate Control", OptionEntryFlags::RecreateUI, N_("Frame Rate Control"), N_("Manages frame rate."),
            #if defined(NXDK) || defined(USE_SDL1)
                      FrameRateControl::CPUSleep
            #else
                      FrameRateControl::VerticalSync
            #endif
                      ,
                      {
                          { FrameRateControl::None, N_("None") },
            #ifndef USE_SDL1
                          { FrameRateControl::VerticalSync, N_("Vertical Sync") },
            #endif
                          { FrameRateControl::CPUSleep, N_("Limit FPS") },
                      })
                , gammaCorrection("Gamma Correction", OptionEntryFlags::Invisible, "Gamma Correction", "Gamma correction level.", 100)
            {
                fullscreen.SetValueChangedCallback(SetFullscreenMode);
            }
            AudioOptions::AudioOptions()
                : OptionCategoryBase("Audio", N_("Audio"), N_("Audio Settings"))
                , soundVolume("Sound Volume", OptionEntryFlags::Invisible, "Sound Volume", "Movie and SFX volume.", VOLUME_MAX)
                , sampleRate("Sample Rate", OptionEntryFlags::CantChangeInGame, N_("Sample Rate"), N_("Output sample rate (Hz)."), DEFAULT_AUDIO_SAMPLE_RATE, { 22050, 44100, 48000 })
            {
            }
        """.trimIndent()
        val found = DevilutionX.parse(mapOf(DevilutionX.OPTIONS to options, DevilutionX.HEADER to header))
        assertEquals(listOf("Graphics/Fullscreen", "Graphics/Scaling Quality", "Graphics/Frame Rate Control", "Audio/Sample Rate"), found.map { "${it.section}/${it.key}" }, "hidden entries and its own kinds are left out")
        val full = found.at("Graphics", "Fullscreen")
        assertEquals(Kind.BOOL, full.kind)
        assertEquals("1", full.default, "a switch as DevilutionX writes it")
        assertEquals("Fullscreen", full.label)
        assertEquals("Display the game in windowed or fullscreen mode.", full.about)
        val scale = found.at("Graphics", "Scaling Quality")
        assertEquals(listOf(Choice("0", "Nearest Pixel"), Choice("1", "Bilinear"), Choice("2", "Anisotropic")), scale.choices)
        assertEquals("2", scale.default)
        val frames = found.at("Graphics", "Frame Rate Control")
        assertEquals(listOf("0", "1", "2"), frames.choices.map { it.value }, "a desktop build has vertical sync")
        assertEquals("1", frames.default)
        val rate = found.at("Audio", "Sample Rate")
        assertEquals(Kind.ENUM, rate.kind)
        assertEquals(listOf("22050", "44100", "48000"), rate.choices.map { it.value })
        assertEquals("22050", rate.default, "a default named by a #define")
    }

    @Test
    fun readingSomeFamiliesAgainKeepsTheOthersAsTheyWere() {
        val before = TuneWriter.kotlin(
            listOf(
                Triple("dolphin", listOf("2609"), listOf(Merged(Found(null, "Core", "CPUThread", Kind.BOOL, "True"), listOf("2609")))),
                Triple("devilutionx", listOf("1.5.4"), listOf(Merged(Found(null, "Graphics", "Old", Kind.BOOL, "0"), listOf("1.5.4")))),
            ),
        )
        val (releases, lines) = TuneWriter.readBack(before)
        assertEquals(listOf("dolphin" to listOf("2609"), "devilutionx" to listOf("1.5.4")), releases)
        assertEquals(2, lines.size)
        val after = keep(before, listOf(Triple("devilutionx", listOf("1.5.5", "1.5.4"), listOf(Merged(Found(null, "Graphics", "Zoom", Kind.BOOL, "0", label = "Zoom"), listOf("1.5.5", "1.5.4"))))))
        val (r2, l2) = TuneWriter.readBack(after)
        assertEquals(listOf("dolphin" to listOf("2609"), "devilutionx" to listOf("1.5.5", "1.5.4")), r2)
        assertEquals(listOf(lines[0], TuneWriter.line("devilutionx", Merged(Found(null, "Graphics", "Zoom", Kind.BOOL, "0", label = "Zoom"), listOf("1.5.5", "1.5.4")))), l2)
        val raw = "a\\b\"c\$d\u0001\n\t"
        assertEquals(raw, TuneWriter.unquote(TuneWriter.quote(raw).removeSurrounding("\"")), "quoting read back is the same text")
    }

    @Test
    fun openRct2sSectionsWithTheirKeysDefaultsAndEnumWords() {
        val code = """
            static const auto Enum_MeasurementFormat = ConfigEnum<MeasurementFormat>({
                ConfigEnumEntry<MeasurementFormat>("IMPERIAL", MeasurementFormat::imperial),
                ConfigEnumEntry<MeasurementFormat>("METRIC", MeasurementFormat::metric),
            });
            static void ReadGeneral(IIniReader* reader)
            {
                if (reader->ReadSection("general"))
                {
                    model->alwaysShowGridlines = reader->GetBoolean("always_show_gridlines", false);
                    model->autosaveFrequency = reader->GetInt32("autosave", AUTOSAVE_EVERY_5MINUTES);
                    model->edgeScrollingSpeed = reader->GetInt32("edge_scrolling_speed", 12);
                    model->measurementFormat = reader->GetEnum<MeasurementFormat>(
                        "measurement_format", MeasurementFormat::metric, Enum_MeasurementFormat);
                    model->language = reader->GetEnum<int32_t>("language", Platform::GetLocaleLanguage(), Enum_LanguageEnum);
                    model->customCurrencySymbol = reader->GetString("custom_currency_symbol", "Ctm");
                }
            }
            static void WriteGeneral(IIniWriter* writer)
            {
                writer->WriteSection("general");
                writer->WriteBoolean("always_show_gridlines", model->alwaysShowGridlines);
            }
            static void ReadSound(IIniReader* reader)
            {
                if (reader->ReadSection("sound"))
                {
                    model->masterVolume = reader->GetFloat("master_volume", 1.0f);
                }
            }
        """.trimIndent()
        val found = OpenRct2.parse(mapOf(OpenRct2.CONFIG to code))
        assertEquals(listOf("general/always_show_gridlines", "general/autosave", "general/edge_scrolling_speed", "general/measurement_format", "general/custom_currency_symbol", "sound/master_volume"), found.map { "${it.section}/${it.key}" }, "the language, of a table of its own, is left out")
        assertEquals("false", found.at("general", "always_show_gridlines").default)
        assertEquals(null, found.at("general", "autosave").default, "a constant from elsewhere")
        assertEquals(listOf(Choice("IMPERIAL"), Choice("METRIC")), found.at("general", "measurement_format").choices)
        assertEquals("METRIC", found.at("general", "measurement_format").default)
        assertEquals(Kind.FLOAT, found.at("sound", "master_volume").kind)
        assertEquals("1.0", found.at("sound", "master_volume").default)
    }

    @Test
    fun fallout2sDefaultsByTheNamesItsHeaderGivesThemAndNotTheMapEditors() {
        val header = """
            #define GAME_CONFIG_SYSTEM_KEY "system"
            #define GAME_CONFIG_PREFERENCES_KEY "preferences"
            #define GAME_CONFIG_MAPPER_KEY "mapper"
            #define GAME_CONFIG_LANGUAGE_KEY "language"
            #define GAME_CONFIG_GAME_DIFFICULTY_KEY "game_difficulty"
            #define GAME_CONFIG_BRIGHTNESS_KEY "brightness"
            #define GAME_CONFIG_LIBRARIAN_KEY "librarian"
            #define ENGLISH "english"
        """.trimIndent()
        val code = """
            bool gameConfigInit(bool isMapper, int argc, char** argv)
            {
                configSetString(&gGameConfig, GAME_CONFIG_SYSTEM_KEY, GAME_CONFIG_LANGUAGE_KEY, ENGLISH);
                configSetInt(&gGameConfig, GAME_CONFIG_PREFERENCES_KEY, GAME_CONFIG_GAME_DIFFICULTY_KEY, 1);
                configSetDouble(&gGameConfig, GAME_CONFIG_PREFERENCES_KEY, GAME_CONFIG_BRIGHTNESS_KEY, 1.0);
                if (isMapper) {
                    configSetInt(&gGameConfig, GAME_CONFIG_MAPPER_KEY, GAME_CONFIG_LIBRARIAN_KEY, 0);
                }
            }
        """.trimIndent()
        val found = Fallout2Ce.parse(mapOf(Fallout2Ce.CONFIG to code, Fallout2Ce.HEADER to header))
        assertEquals(listOf("system/language", "preferences/game_difficulty", "preferences/brightness"), found.map { "${it.section}/${it.key}" })
        assertEquals("english", found.at("system", "language").default)
        assertEquals(Kind.INT, found.at("preferences", "game_difficulty").kind)
        assertEquals("1.0", found.at("preferences", "brightness").default)
    }

    @Test
    fun retroArchsKeysWithTheMenusNamesRangesAndTabs() {
        val config = """
            static struct config_bool_setting *populate_settings_bool(settings_t *settings, int *size)
            {
               SETTING_BOOL("video_vsync",                   &settings->bools.video_vsync, true, DEFAULT_VSYNC, false);
               SETTING_BOOL("video_hard_sync",               &settings->bools.video_hard_sync, true, DEFAULT_HARD_SYNC, false);
            }
            static struct config_uint_setting *populate_settings_uint(settings_t *settings, int *size)
            {
               SETTING_UINT("video_swap_interval",           &settings->uints.video_swap_interval, true, DEFAULT_SWAP_INTERVAL, false);
               SETTING_UINT("video_rotation",                &settings->uints.video_rotation, true, ORIENTATION_NORMAL, false);
            }
            static struct config_float_setting *populate_settings_float(settings_t *settings, int *size)
            {
               SETTING_FLOAT("audio_volume",                 &settings->floats.audio_volume, true, DEFAULT_AUDIO_VOLUME, false);
            }
            static struct config_array_setting *populate_settings_array(settings_t *settings, int *size)
            {
               SETTING_ARRAY("video_driver",                 settings->arrays.video_driver, false, NULL, true);
            }
            static struct config_path_setting *populate_settings_path(settings_t *settings, int *size)
            {
               SETTING_PATH("libretro_directory",            settings->paths.directory_libretro, false, NULL, false);
            }
        """.trimIndent()
        val defaults = """
            #define DEFAULT_VSYNC true
            #define DEFAULT_SWAP_INTERVAL 1
            #define DEFAULT_AUDIO_VOLUME 0.0
            #if defined(HAVE_X)
            #define DEFAULT_HARD_SYNC true
            #else
            #define DEFAULT_HARD_SYNC false
            #endif
        """.trimIndent()
        val menu = """
            START_GROUP(list, list_info, &group_info, msg_hash_to_str(MENU_ENUM_LABEL_VALUE_VIDEO_SETTINGS), parent_group);
            CONFIG_BOOL(
                  list, list_info,
                  &settings->bools.video_vsync,
                  MENU_ENUM_LABEL_VIDEO_VSYNC,
                  MENU_ENUM_LABEL_VALUE_VIDEO_VSYNC,
                  DEFAULT_VSYNC,
                  MENU_ENUM_LABEL_VALUE_OFF,
                  MENU_ENUM_LABEL_VALUE_ON,
                  &group_info, &subgroup_info, parent_group, general_write_handler, general_read_handler, SD_FLAG_NONE);
            CONFIG_UINT(
                  list, list_info,
                  &settings->uints.video_swap_interval,
                  MENU_ENUM_LABEL_VIDEO_SWAP_INTERVAL,
                  MENU_ENUM_LABEL_VALUE_VIDEO_SWAP_INTERVAL,
                  DEFAULT_SWAP_INTERVAL,
                  &group_info, &subgroup_info, parent_group, general_write_handler, general_read_handler);
            menu_settings_list_current_add_range(list, list_info, 0, 4, 1, true, true);
            START_GROUP(list, list_info, &group_info, msg_hash_to_str(MENU_ENUM_LABEL_VALUE_AUDIO_SETTINGS), parent_group);
            CONFIG_FLOAT(
                  list, list_info,
                  &settings->floats.audio_volume,
                  MENU_ENUM_LABEL_AUDIO_VOLUME,
                  MENU_ENUM_LABEL_VALUE_AUDIO_VOLUME,
                  DEFAULT_AUDIO_VOLUME,
                  "%.1f", &group_info, &subgroup_info, parent_group, general_write_handler, general_read_handler);
            menu_settings_list_current_add_range(list, list_info, -80, 12, 1.0, true, true);
        """.trimIndent()
        val words = """
            MSG_HASH(
               MENU_ENUM_LABEL_VALUE_VIDEO_SETTINGS,
               "Video"
               )
            MSG_HASH(
               MENU_ENUM_LABEL_VALUE_AUDIO_SETTINGS,
               "Audio"
               )
            MSG_HASH(
               MENU_ENUM_LABEL_VALUE_VIDEO_VSYNC,
               "VSync"
               )
            MSG_HASH(
               MENU_ENUM_SUBLABEL_VIDEO_VSYNC,
               "Synchronize the graphics card's video output "
               "to the screen refresh rate."
               )
            MSG_HASH(
               MENU_ENUM_LABEL_VALUE_VIDEO_SWAP_INTERVAL,
               "VSync Swap Interval"
               )
            MSG_HASH(
               MENU_ENUM_LABEL_VALUE_AUDIO_VOLUME,
               "Volume Gain (dB)"
               )
        """.trimIndent()
        val found = RetroArch.parse(mapOf(RetroArch.CONFIG to config, RetroArch.DEFAULTS to defaults, RetroArch.MENU to menu, RetroArch.WORDS to words))
        assertEquals(listOf("video_vsync", "video_hard_sync", "video_swap_interval", "video_rotation", "audio_volume", "video_driver"), found.map { it.key }, "a computer's own paths are left out")
        val vsync = found.single { it.key == "video_vsync" }
        assertEquals(Kind.BOOL, vsync.kind)
        assertEquals("true", vsync.default)
        assertEquals("VSync", vsync.label)
        assertEquals("Synchronize the graphics card's video output to the screen refresh rate.", vsync.about)
        assertEquals("Video", vsync.tab)
        assertEquals(null, found.single { it.key == "video_hard_sync" }.default, "a default that depends on the build")
        val swap = found.single { it.key == "video_swap_interval" }
        assertEquals(0L to 4L, swap.min to swap.max)
        assertEquals("1", swap.default)
        assertEquals(null, found.single { it.key == "video_rotation" }.default, "a constant from elsewhere")
        val volume = found.single { it.key == "audio_volume" }
        assertEquals(Kind.FLOAT, volume.kind)
        assertEquals("Audio", volume.tab)
        assertEquals(-80L to 12L, volume.min to volume.max)
        assertEquals(Kind.STRING, found.single { it.key == "video_driver" }.kind)
        assertEquals("", found.single { it.key == "video_driver" }.default)
        assertTrue(found.all { it.section.isEmpty() && it.perGame }, "retroarch.cfg has no sections, and a game's override may hold any of them")
    }

    @Test
    fun azaharsSettingsByTheGroupsItsQtConfigReadsThemIn() {
        val settings = """
            enum class GraphicsAPI {
                Software = 0,
                OpenGL = 1,
                Vulkan = 2,
            };
            struct Values {
                SwitchableSetting<s32, true> cpu_clock_percentage{100, 5, 400, Keys::cpu_clock_percentage};
                SwitchableSetting<bool> is_new_3ds{true, Keys::is_new_3ds};
                Setting<bool> use_cpu_jit{true, Keys::use_cpu_jit};
                SwitchableSetting<GraphicsAPI, true> graphics_api{GraphicsAPI::OpenGL, GraphicsAPI::Software, GraphicsAPI::Vulkan, Keys::graphics_api};
                Setting<bool> use_virtual_sd{true, Keys::use_virtual_sd};
                SwitchableSetting<bool> dump_textures{false, Keys::dump_textures};
                Setting<std::string> never_read{"", Keys::never_read};
            };
        """.trimIndent()
        val config = """
            void QtConfig::ReadValues() {
                if (global) {
                    ReadDataStorageValues();
                }
                ReadCoreValues();
                ReadRendererValues();
            }
            void QtConfig::ReadCoreValues() {
                qt_config->beginGroup(QStringLiteral("Core"));
                ReadGlobalSetting(Settings::values.cpu_clock_percentage);
                if (global) {
                    ReadBasicSetting(Settings::values.use_cpu_jit);
                    ReadGlobalSetting(Settings::values.is_new_3ds);
                }
                qt_config->endGroup();
            }
            void QtConfig::ReadRendererValues() {
                qt_config->beginGroup(QStringLiteral("Renderer"));
                ReadGlobalSetting(Settings::values.graphics_api);
                qt_config->endGroup();
            }
            void QtConfig::ReadDataStorageValues() {
                qt_config->beginGroup(QStringLiteral("Data Storage"));
                ReadBasicSetting(Settings::values.use_virtual_sd);
                ReadGlobalSetting(Settings::values.dump_textures);
                qt_config->endGroup();
            }
        """.trimIndent()
        val screens = """
            add(
                SwitchSetting(
                    BooleanSetting.NEW_3DS,
                    R.string.new_3ds,
                    0,
                    BooleanSetting.NEW_3DS.key,
                    BooleanSetting.NEW_3DS.defaultValue
                )
            )
            add(
                SingleChoiceSetting(
                    IntSetting.GRAPHICS_API,
                    R.string.graphics_api,
                    R.string.graphics_api_description,
                    R.array.graphicsApiNames,
                    R.array.graphicsApiValues,
                    IntSetting.GRAPHICS_API.key,
                    IntSetting.GRAPHICS_API.defaultValue
                )
            )
        """.trimIndent()
        val booleans = """
            enum class BooleanSetting(override val key: String, override val section: String, override val defaultValue: Boolean) {
                NEW_3DS(SettingKeys.is_new_3ds(), Settings.SECTION_SYSTEM, true),
            }
        """.trimIndent()
        val ints = "enum class IntSetting {\n    GRAPHICS_API(SettingKeys.graphics_api(), Settings.SECTION_RENDERER, 1),\n}"
        val strings = """
            <resources>
                <string name="new_3ds">New 3DS Mode</string>
                <string name="graphics_api">Graphics API</string>
                <string name="graphics_api_description">Azahar\'s renderer &amp; its backend.</string>
                <string name="software">Software</string>
            </resources>
        """.trimIndent()
        val arrays = """
            <resources>
                <string-array name="graphicsApiNames">
                    <item>@string/software</item>
                    <item>OpenGL ES</item>
                    <item>Vulkan</item>
                </string-array>
                <!-- the numbers settings.h gives them -->
                <integer-array name="graphicsApiValues">
                    <item>0</item>
                    <item>1</item>
                    <item>2</item>
                </integer-array>
            </resources>
        """.trimIndent()
        val words = mapOf(Azahar.SCREENS to screens, Azahar.MODELS[0] to booleans, Azahar.MODELS[1] to ints, Azahar.STRINGS to strings, Azahar.ARRAYS to arrays)
        val found = Azahar.parse(mapOf(Azahar.SETTINGS to settings, Azahar.CONFIG to config) + words).associateBy { it.key }
        assertEquals("New 3DS Mode", found.getValue("is_new_3ds").label)
        assertNull(found.getValue("is_new_3ds").about)
        assertEquals("Graphics API" to "Azahar's renderer & its backend.", found.getValue("graphics_api").let { it.label to it.about })
        assertEquals(listOf("Software", "OpenGL ES", "Vulkan"), found.getValue("graphics_api").choices.map { it.label }, "as its screens name them")
        assertNull(found.getValue("use_cpu_jit").label, "on no screen")
        assertEquals(setOf("cpu_clock_percentage", "is_new_3ds", "use_cpu_jit", "graphics_api", "use_virtual_sd", "dump_textures"), found.keys, "only what the Qt config reads")
        val clock = found.getValue("cpu_clock_percentage")
        assertEquals("Core" to (5L to 400L), clock.section to (clock.min to clock.max))
        assertEquals("100", clock.default)
        assertTrue(clock.perGame)
        assertFalse(found.getValue("is_new_3ds").perGame, "read only for every game")
        assertFalse(found.getValue("use_cpu_jit").perGame, "a plain Setting")
        assertFalse(found.getValue("dump_textures").perGame, "in a group read only for every game")
        val api = found.getValue("graphics_api")
        assertEquals(Kind.ENUM, api.kind)
        assertEquals("1", api.default)
        assertEquals(listOf("0" to "Software", "1" to "OpenGL ES", "2" to "Vulkan"), api.choices.map { it.value to it.label })
        assertEquals("Data%20Storage" to "Data Storage", found.getValue("use_virtual_sd").let { it.section to it.tab }, "as QSettings writes a group with a space")
    }

    @Test
    fun edensSettingsByTheirSectionsWithWhatItsQtSettingsCallThem() {
        val settings = """
            struct Values {
                SwitchableSetting<bool> use_vsync{linkage, true, "use_vsync", Category::Renderer};
                SwitchableSetting<VSyncMode, true> vsync_mode{linkage, VSyncMode::Fifo, VSyncMode::Immediate, VSyncMode::FifoRelaxed, "use_vsync_mode", Category::Renderer, Specialization::RuntimeList, true, true};
                SwitchableSetting<int, true> resolution_scale{linkage, 100, 25, 800, "resolution_scale", Category::RendererAdvanced};
                Setting<bool> use_multi_core{linkage, true, "use_multi_core", Category::Core};
                Setting<bool> never_saved{linkage, false, "never_saved", Category::Core, Specialization::Default, false};
                SwitchableSetting<AudioEngine> sink_id{linkage, AudioEngine::Auto, "output_engine", Category::Audio, Specialization::RuntimeList};
                Setting<std::string> title{linkage, "", "title", Category::Ui};
            };
        """.trimIndent()
        val categories = """
            const char* TranslateCategory(Category category) {
                switch (category) {
                case Category::Audio:
                    return "Audio";
                case Category::Core:
                    return "Core";
                case Category::Renderer:
                case Category::RendererAdvanced:
                    return "Renderer";
                case Category::MaxEnum:
                    break;
                }
                return "Miscellaneous";
            }
        """.trimIndent()
        val enums = """
            ENUM(VSyncMode, Immediate, Mailbox, Fifo, FifoRelaxed);
            enum class AudioEngine : u32 { Auto, Cubeb, Sdl2, Null, Oboe, };
        """.trimIndent()
        val words = """
            INSERT(Settings, use_vsync, tr("Use VSync:"), tr("Waits for the screen."));
            INSERT(Settings, vsync_mode, tr("VSync Mode:"), tr("FIFO (VSync) does not drop frames "
                                                                "or exhibit tearing."));
            INSERT(Settings, resolution_scale, tr("Resolution:"), QString());
            translations->insert({Settings::EnumMetadata<Settings::VSyncMode>::Index(),
                                  {
                                      PAIR(VSyncMode, Immediate, tr("Immediate (no synchronization)")),
                                      PAIR(VSyncMode, Fifo, tr("FIFO (VSync)")),
                                  }});
        """.trimIndent()
        val found = Eden.parse(mapOf(Eden.SETTINGS to settings, Eden.CATEGORIES to categories, Eden.ENUMS to enums, Eden.WORDS to words))
        assertEquals(listOf("use_vsync", "use_vsync_mode", "resolution_scale", "use_multi_core", "output_engine"), found.map { it.key }, "one never saved, and one of a category with no section, are left out")
        val vsync = found.single { it.key == "use_vsync" }
        assertEquals("Renderer" to "true", vsync.section to vsync.default)
        assertEquals("Use VSync", vsync.label)
        assertTrue(vsync.perGame)
        val mode = found.single { it.key == "use_vsync_mode" }
        assertEquals(Kind.ENUM, mode.kind)
        assertEquals("2", mode.default, "written as the member's number")
        assertEquals(listOf("Immediate (no synchronization)", "Mailbox", "FIFO (VSync)", "FifoRelaxed"), mode.choices.map { it.label })
        assertEquals("FIFO (VSync) does not drop frames or exhibit tearing.", mode.about)
        val scale = found.single { it.key == "resolution_scale" }
        assertEquals(25L to 800L, scale.min to scale.max)
        assertEquals("Renderer", scale.section)
        assertEquals("Renderer Advanced", scale.tab)
        assertFalse(found.single { it.key == "use_multi_core" }.perGame, "a plain Setting is for every game only")
        assertEquals(listOf("0", "1", "2", "3", "4"), found.single { it.key == "output_engine" }.choices.map { it.value })
    }

    @Test
    fun aCoresOptionsWithTheirChoicesDefaultsAndCategories() {
        val v2 = """
            #define CORE_NAME "snes9x"
            #ifdef HAVE_OPENGL
            #define RENDER "Hardware"
            #endif
            struct retro_core_option_v2_category option_cats_us[] = {
               { "video", "Video", "Change how the picture looks." },
               { NULL, NULL, NULL },
            };
            struct retro_core_option_v2_definition option_defs_us[] = {
               {
                  CORE_NAME "_region",
                  "Console Region (Reload Core)",
                  NULL,
                  "Which region the system is from. "
                  "'PAL' is 50 Hz.",
                  NULL,
                  NULL,
                  {
                     { "auto", "Auto" },
                     { "ntsc", "NTSC" },
                     { "pal",  "PAL" },
                     { NULL, NULL },
                  },
                  "auto"
               },
               {
                  CORE_NAME "_overscan",
                  "Crop Overscan",
                  "Crop",
                  NULL,
                  NULL,
                  "video",
                  {
                     { "enabled",  NULL },
                     { "disabled", NULL },
                     { NULL, NULL },
                  },
                  "missing"
               },
               { NULL, NULL, NULL, NULL, NULL, NULL, {{0}}, NULL },
            };
        """.trimIndent()
        val found = CoreOptions.parse(v2)
        assertEquals(listOf("snes9x_region", "snes9x_overscan"), found.map { it.key }, "keys made of the core's name and the rest")
        val region = found.first()
        assertEquals(Kind.ENUM, region.kind)
        assertEquals("auto", region.default)
        assertEquals(listOf("auto" to "Auto", "ntsc" to "NTSC", "pal" to "PAL"), region.choices.map { it.value to it.label })
        assertEquals("Console Region (Reload Core)", region.label)
        assertEquals("Which region the system is from. 'PAL' is 50 Hz.", region.about)
        assertTrue(region.perGame, "RetroArch keeps any core option in a game's own .opt file")
        val overscan = found.last()
        assertEquals("enabled", overscan.default, "a default that isn't one of the values is the first")
        assertEquals("Video", overscan.tab, "the category's name")
        assertEquals(listOf<String?>(null, null), overscan.choices.map { it.label }, "a value with no label of its own")

        val v1 = """
            struct retro_core_option_definition option_defs_us[] = {
               {
                  "gambatte_gb_colorization",
                  "GB Colorization",
                  "Enables colorization of Game Boy games.",
                  {
                     { "disabled", NULL },
                     { "auto",     "Auto" },
                     { NULL, NULL },
                  },
                  "disabled"
               },
               { NULL, NULL, NULL, {{0}}, NULL },
            };
        """.trimIndent()
        val old = CoreOptions.parse(v1).single()
        assertEquals("gambatte_gb_colorization" to "disabled", old.key to old.default)
        assertEquals("Enables colorization of Game Boy games.", old.about)
        assertEquals(null, old.tab)
    }

    @Test
    fun aCoresKeysMadeOfMacrosAreReadAsItsBuildDefinesThem() {
        // A define with nothing after it is one line, not the next one's value (ParaLLEl N64's header).
        val own = """
            #define LIBRETRO_CORE_OPTIONS_H__

            #include <libretro.h>
            #define CORE_NAME "parallel-n64"
            struct retro_core_option_v2_definition option_defs[] = {
               { CORE_NAME "-upscaling", "Upscaling", NULL, NULL, NULL, NULL, { { "1x", NULL }, { "2x", NULL }, { NULL, NULL } }, "1x" },
               { NULL, NULL, NULL, NULL, NULL, NULL, {{0}}, NULL },
            };
            struct retro_core_option_v2_definition option_defs_us[MAX_CORE_OPTIONS];
        """.trimIndent()
        assertEquals(listOf("parallel-n64-upscaling"), CoreOptions.parse(own).map { it.key }, "the entries of option_defs, which the core copies when it starts")

        // Beetle PSX names its keys with a macro from another header, which its HW build defines its own way.
        val header = """
            struct retro_core_option_v2_definition option_defs_us[] = {
               { BEETLE_OPT(renderer), "Renderer", NULL, NULL, NULL, "video", { { "hardware", "Hardware" }, { "software", "Software" }, { NULL, NULL } }, "hardware" },
               { NULL, NULL, NULL, NULL, NULL, NULL, {{0}}, NULL },
            };
        """.trimIndent()
        val options = """
            #ifdef HAVE_HW
            #define BEETLE_OPT(_o) ("beetle_psx_hw_" # _o)
            #else
            #define BEETLE_OPT(_o) ("beetle_psx_" # _o)
            #endif
        """.trimIndent()
        assertEquals(setOf("BEETLE_OPT"), CoreOptions.unknown(header))
        assertEquals(listOf("beetle_psx_hw_renderer"), CoreOptions.parse(header, CoreOptions.defines(options, setOf("HAVE_HW"))).map { it.key })
        assertEquals(listOf("beetle_psx_renderer"), CoreOptions.parse(header, CoreOptions.defines(options)).map { it.key })
        assertTrue(CoreOptions.parse(header).isEmpty(), "a key whose macro isn't known is left out")

        val recipe = coreRecipes("""
            mednafen_psx_hw libretro-beetle_psx_hw https://github.com/libretro/beetle-psx-libretro.git master YES GENERIC Makefile . HAVE_HW=1 HAVE_LIGHTREC=1
            flycast libretro-flycast https://github.com/flyinghead/flycast master YES CMAKE Makefile build -DLIBRETRO=ON -DCMAKE_BUILD_TYPE=Release
        """.trimIndent())
        assertEquals(setOf("HAVE_HW", "HAVE_LIGHTREC"), recipe.getValue("mednafen_psx_hw").flags)
        assertEquals(setOf("LIBRETRO"), recipe.getValue("flycast").flags)
        assertEquals("https://github.com/flyinghead/flycast" to "master", recipe.getValue("flycast").let { it.url to it.branch })
    }

    @Test
    fun theCoresFuseStartsGamesWithAreItsQuotedNamesThatArentSystems() {
        val source = """
            private val shared = mapOf(
                "psx" to listOf("swanstation", "pcsx_rearmed"),
                "gb" to GB,
            )
            private val GB = listOf("gambatte", "sameboy", "swanstation")
        """.trimIndent()
        assertEquals(listOf("gambatte", "pcsx_rearmed", "sameboy", "swanstation"), coreNames(source))
    }
}
