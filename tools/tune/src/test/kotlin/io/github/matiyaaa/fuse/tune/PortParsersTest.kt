package io.github.matiyaaa.fuse.tune

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Each Fuse Ports reader against small declarations written here by hand in the shape each port's
 * code uses (no port's code is copied in): what it finds, and what it leaves out.
 */
class PortParsersTest {
    private fun List<Found>.at(section: String, key: String) = single { it.section == section && it.key == key }

    @Test
    fun recompFrontendsOptionsWithTheirTabsFileWordsAndTheGamesOwn() {
        val config = """
            namespace recompui { namespace config {
                namespace general { inline const std::string id = "general"; namespace options { inline const std::string rumble_strength = "rumble_strength"; inline const std::string gyro_sensitivity = "gyro_sensitivity"; } }
                namespace graphics { inline const std::string id = "graphics"; namespace options { inline const std::string res_option = "res_option"; inline const std::string ds_option = "ds_option"; inline const std::string developer_mode = "developer_mode"; } }
                struct GeneralTabOptions { bool has_rumble_strength = true; bool has_gyro_sensitivity = true; };
            } }
        """.trimIndent()
        val graphics = """
            static EnumOptionVector resolution_options = {
                {ultramodern::renderer::Resolution::Original, "Original", "Original"},
                {ultramodern::renderer::Resolution::Original2x, "Original2x", "Original 2x"},
                {ultramodern::renderer::Resolution::Auto, "Auto"},
            };
            enum class DownsamplingOption { Off = 0, X2 = 2, X4 = 4, };
            static EnumOptionVector downsampling_options = { {DownsamplingOption::Off, "Off"}, {DownsamplingOption::X2, "2x"}, };
            recomp::config::Config &create_graphics_tab(const std::string &name) {
                recomp::config::Config &config = recompui::config::create_config_tab(name, graphics::id, true);
                config.add_bool_option(graphics::options::developer_mode, "Dev Mode", "Developer features.", false, true);
                config.add_enum_option(graphics::options::res_option, "Resolution", "Sets the <recomp-color primary>output</recomp-color> resolution." "<br />" "More.", resolution_options, ultramodern::renderer::Resolution::Auto);
                config.add_enum_option(graphics::options::ds_option, "Downsampling Quality", "Renders higher.", downsampling_options, DownsamplingOption::Off);
                config.on_json_serialize_option(graphics::options::ds_option, [](const recomp::config::ConfigValueVariant& value) { return 0; });
                return config;
            }
            recomp::config::Config &create_general_tab(const GeneralTabOptions& options, const std::string &name) {
                recomp::config::Config &config = recompui::config::create_config_tab(name, general::id, false);
                if (options.has_rumble_strength) { config.add_percent_number_option(general::options::rumble_strength, "Rumble Strength", "How strong.", 25.0); }
                if (options.has_gyro_sensitivity) { config.add_percent_number_option(general::options::gyro_sensitivity, "Gyro Sensitivity", "Aiming.", 50.0); }
                return config;
            }
        """.trimIndent()
        val game = """
            namespace banjo { namespace configkeys { namespace general { inline const std::string note_saving_mode = "note_saving_mode"; } } }
            static void add_general_options(recomp::config::Config &config) {
                static EnumOptionVector note_options = { {banjo::NoteSavingMode::Off, "Off", "Off"}, {banjo::NoteSavingMode::Both, "On", "Both"} };
                config.add_enum_option(banjo::configkeys::general::note_saving_mode, "Note Saving", "Saves notes.", note_options, banjo::NoteSavingMode::Both);
                config.add_number_option("camera_speed", "Camera Speed", "", 1, 10, 1, 0, false, 3);
            }
            void banjo::init_config() {
                recompui::config::GeneralTabOptions general_options{};
                general_options.has_gyro_sensitivity = false;
                auto &general_config = recompui::config::create_general_tab(general_options);
                add_general_options(general_config);
            }
        """.trimIndent()
        val found = N64Recomp.Frontend.parse(
            mapOf("lib/RecompFrontend/recompui/include/recompui/config.h" to config, "lib/RecompFrontend/recompui/src/config/ui_config_tab_graphics.cpp" to graphics, "src/game/config.cpp" to game),
        )
        val res = found.single { it.key == "res_option" }
        assertEquals(Triple("graphics.json", "Auto", "Resolution"), Triple(res.file, res.default, res.label))
        assertEquals("Sets the output resolution. More.", res.about, "the game's markup taken out, its strings joined")
        assertEquals(listOf("Original" to null, "Original2x" to "Original 2x", "Auto" to null), res.choices.map { it.value to it.label })
        assertEquals(listOf("0", "2"), found.single { it.key == "ds_option" }.choices.map { it.value }, "written as its number where the tab serializes it so")
        assertTrue(found.none { it.key == "developer_mode" }, "a hidden option isn't one the game shows")
        assertTrue(found.none { it.key == "gyro_sensitivity" }, "General's option the game turned off")
        val rumble = found.single { it.key == "rumble_strength" }
        assertEquals(listOf("general.json", "25", "0", "100"), listOf(rumble.file, rumble.default, rumble.min.toString(), rumble.max.toString()))
        assertEquals(listOf("general.json", "On"), found.single { it.key == "note_saving_mode" }.let { listOf(it.file, it.default) }, "the game's own, in the tab it is handed")
        val speed = found.single { it.key == "camera_speed" }
        assertEquals(listOf("3", "1", "10"), listOf(speed.default, speed.min.toString(), speed.max.toString()))
        assertTrue(found.all { it.section.isEmpty() && !it.perGame })
    }

    @Test
    fun theZeldaTemplatesFilesKeysAndDefaultsWithUltramodernsWords() {
        val runtime = """
            namespace ultramodern { namespace renderer {
                enum class WindowMode { Windowed, Fullscreen };
                enum class Antialiasing { None, MSAA2X, MSAA4X };
                class GraphicsConfig { public: WindowMode wm_option; Antialiasing msaa_option; int rr_manual_value; };
                NLOHMANN_JSON_SERIALIZE_ENUM(ultramodern::renderer::WindowMode, { {ultramodern::renderer::WindowMode::Windowed, "Windowed"}, {ultramodern::renderer::WindowMode::Fullscreen, "Fullscreen"} });
                NLOHMANN_JSON_SERIALIZE_ENUM(ultramodern::renderer::Antialiasing, { {ultramodern::renderer::Antialiasing::None, "None"}, {ultramodern::renderer::Antialiasing::MSAA2X, "MSAA2X"}, {ultramodern::renderer::Antialiasing::MSAA4X, "MSAA4X"} });
            } }
        """.trimIndent()
        val header = """
            namespace zelda64 { enum class AutosaveMode { On, Off }; int get_main_volume(); bool get_low_health_beeps_enabled(); }
            NLOHMANN_JSON_SERIALIZE_ENUM(zelda64::AutosaveMode, { {zelda64::AutosaveMode::On, "On"}, {zelda64::AutosaveMode::Off, "Off"} });
        """.trimIndent()
        val config = """
            constexpr std::u8string_view general_filename = u8"general.json";
            constexpr std::u8string_view graphics_filename = u8"graphics.json";
            constexpr std::u8string_view controls_filename = u8"controls.json";
            constexpr std::u8string_view sound_filename = u8"sound.json";
            constexpr auto msaa_default = ultramodern::renderer::Antialiasing::MSAA2X;
            constexpr int rr_manual_default = 60;
            void to_json(json& j, const renderer::GraphicsConfig& config) {
                j = json{ {"_config_version", 2}, {"wm_option", config.wm_option}, {"msaa_option", config.msaa_option}, {"rr_manual_value", config.rr_manual_value} };
            }
            void from_json(const json& j, renderer::GraphicsConfig& config) {
                config.wm_option = from_or_default(j, "wm_option", wm_default());
                config.msaa_option = from_or_default(j, "msaa_option", msaa_default);
                config.rr_manual_value = from_or_default(j, "rr_manual_value", rr_manual_default);
            }
            bool save_general_config(const std::filesystem::path& path) {
                nlohmann::json config_json{};
                config_json["autosave_mode"] = zelda64::get_autosave_mode();
                config_json["mouse_sensitivity"] = recomp::get_mouse_sensitivity();
                return save_json_with_backups(path, config_json);
            }
            void set_general_settings_from_json(const nlohmann::json& config_json) {
                zelda64::set_autosave_mode(from_or_default(config_json, "autosave_mode", zelda64::AutosaveMode::On));
                recomp::set_mouse_sensitivity(from_or_default(config_json, "mouse_sensitivity", 10));
            }
            bool save_graphics_config(const std::filesystem::path& path) {
                nlohmann::json config_json{};
                ultramodern::to_json(config_json, ultramodern::renderer::get_graphics_config());
                return save_json_with_backups(path, config_json);
            }
            bool save_controls_config(const std::filesystem::path& path) {
                nlohmann::json config_json{};
                add_input_bindings(config_json["keyboard"], 0, 0);
                return true;
            }
            bool save_sound_config(const std::filesystem::path& path) {
                nlohmann::json config_json{};
                config_json["main_volume"] = zelda64::get_main_volume();
                config_json["low_health_beeps"] = zelda64::get_low_health_beeps_enabled();
                return true;
            }
        """.trimIndent()
        val found = N64Recomp.Classic.parse(mapOf("lib/N64ModernRuntime/ultramodern/include/ultramodern/config.hpp" to runtime, "include/zelda_config.h" to header, "src/game/config.cpp" to config))
        assertEquals(
            setOf("general.json/autosave_mode", "general.json/mouse_sensitivity", "graphics.json/wm_option", "graphics.json/msaa_option", "graphics.json/rr_manual_value", "sound.json/main_volume", "sound.json/low_health_beeps"),
            found.map { "${it.file}/${it.key}" }.toSet(),
            "controls (bindings) and the file's own bookkeeping left out",
        )
        val msaa = found.single { it.key == "msaa_option" }
        assertEquals(Kind.ENUM to "MSAA2X", msaa.kind to msaa.default)
        assertEquals(listOf("None", "MSAA2X", "MSAA4X"), msaa.choices.map { it.value })
        val wm = found.single { it.key == "wm_option" }
        assertEquals(Kind.ENUM, wm.kind, "its type from GraphicsConfig")
        assertNull(wm.default, "worked out as it runs")
        assertEquals("60", found.single { it.key == "rr_manual_value" }.default)
        assertEquals(Kind.INT, found.single { it.key == "main_volume" }.kind, "a getter's type where it has no default")
        assertEquals(Kind.BOOL, found.single { it.key == "low_health_beeps" }.kind)
        assertEquals("On", found.single { it.key == "autosave_mode" }.default)
    }

    @Test
    fun libultrashipsCVarsUnderTheirObjectsWithTheMenusWords() {
        val prefixes = "set(CVAR_PREFIX_SETTING \"gSettings\")\nset(CVAR_PREFIX_ENHANCEMENT \"gEnhancements\")"
        val header = """
            #define CVAR_SETTING(var) CVAR_PREFIX_SETTING "." var
            #define CVAR_ENHANCEMENT(var) CVAR_PREFIX_ENHANCEMENT "." var
            enum Colors { Red, Blue, LightBlue, };
            typedef enum { TIME_TRAVEL_DISABLED, TIME_TRAVEL_ANY, } TimeTravel;
        """.trimIndent()
        val menu = """
            static const std::map<int32_t, const char*> themes = { { Colors::Red, "Red" }, { Colors::LightBlue, "Light Blue" }, };
            static const std::map<int32_t, const char*> travel = { { TIME_TRAVEL_DISABLED, "Disabled" }, { TIME_TRAVEL_ANY, "Any Ocarina" }, };
            void Menu::AddSettings() {
                WidgetPath path = { "Settings", "General", SECTION_COLUMN_1 };
                AddWidget(path, "Menu Theme", WIDGET_CVAR_COMBOBOX).CVar(CVAR_SETTING("Menu.Theme")).Options(ComboboxOptions().Tooltip("Changes the theme.").ComboMap(themes).DefaultIndex(Colors::LightBlue));
                path.sidebarName = "Graphics";
                AddWidget(path, "MSAA: %d", WIDGET_CVAR_SLIDER_INT).CVar(CVAR_SETTING("MSAAValue")).Options(IntSliderOptions().Min(1).Max(8).DefaultValue(1));
                path = { "Enhancements", "Time", SECTION_COLUMN_1 };
                AddWidget(path, "Time Travel", WIDGET_CVAR_COMBOBOX).CVar(CVAR_ENHANCEMENT("TimeTravel")).Options(ComboboxOptions().ComboMap(travel));
                AddWidget(path, "Skip Intro", WIDGET_CVAR_CHECKBOX).CVar("gEnhancements.SkipIntro").Options(CheckboxOptions().DefaultValue(true));
                AddWidget(path, "##scale", WIDGET_CVAR_SLIDER_FLOAT).CVar("gEnhancements.Scale");
                path = { "Dev Tools", "General", SECTION_COLUMN_1 };
                AddWidget(path, "Debug Mode", WIDGET_CVAR_CHECKBOX).CVar("gDeveloperTools.DebugEnabled");
            }
        """.trimIndent()
        val old = """
            void DrawMenu() {
                if (UIWidgets::BeginMenu("Audio")) {
                    UIWidgets::CVarSliderInt("Subwoofer threshold (Hz)", "gSubwooferThreshold", 10, 1000, 80, { .tooltip = "Below this, the subwoofer." });
                    const char* voices[] = { "Original", "Japanese" };
                    UIWidgets::CVarCombobox("Voices", "gVoiceLanguage", voices, { .defaultIndex = 0 });
                    UIWidgets::CVarCheckbox("Invert Y Axis", "gInvertYAxis", { .tooltip = "Inverts it", .defaultValue = true });
                }
            }
        """.trimIndent()
        val found = Libultraship.parse(mapOf("CMake/cvars.cmake" to prefixes, "soh/soh/cvars.h" to header, "soh/soh/SohGui/Menu.cpp" to menu, "src/port/ui/ImguiUI.cpp" to old))
        val theme = found.at("CVars${Found.PATH}gSettings${Found.PATH}Menu", "Theme")
        assertEquals(Triple(Kind.ENUM, "2", "Settings / General"), Triple(theme.kind, theme.default, theme.tab))
        assertEquals(listOf("0" to "Red", "2" to "Light Blue"), theme.choices.map { it.value to it.label })
        val msaa = found.at("CVars${Found.PATH}gSettings", "MSAAValue")
        assertEquals(listOf("MSAA", "1", "1", "8", "Settings / Graphics"), listOf(msaa.label, msaa.default, msaa.min.toString(), msaa.max.toString(), msaa.tab))
        assertEquals(listOf("0" to "Disabled", "1" to "Any Ocarina"), found.at("CVars${Found.PATH}gEnhancements", "TimeTravel").choices.map { it.value to it.label }, "a C enum's members")
        assertEquals("1", found.at("CVars${Found.PATH}gEnhancements", "SkipIntro").default, "a switch is 1 or 0 there")
        assertTrue(found.none { it.key == "Scale" }, "a control with no name of its own")
        assertTrue(found.none { it.key == "DebugEnabled" }, "the developers' tools")
        assertEquals(listOf("80", "10", "1000", "Audio"), found.at("CVars", "gSubwooferThreshold").let { listOf(it.default, it.min.toString(), it.max.toString(), it.tab) })
        assertEquals(listOf("0" to "Original", "1" to "Japanese"), found.at("CVars", "gVoiceLanguage").choices.map { it.value to it.label })
        assertEquals("1", found.at("CVars", "gInvertYAxis").default)
    }

    @Test
    fun idTech4sArchivedVariablesWithTheirKindsRangesAndWords() {
        val code = """
            static const char *r_apiArgs[] = { "best", "gl", "vulkan", NULL };
            idCVar r_mode( "r_mode", "5", CVAR_ARCHIVE | CVAR_RENDERER | CVAR_INTEGER, "video mode number", 0, 9, idCmdSystem::ArgCompletion_Integer<0,9> );
            idCVar r_fullscreen( "r_fullscreen", "1", CVAR_RENDERER | CVAR_ARCHIVE | CVAR_BOOL, "0 = windowed, " "1 = full screen" );
            idCVar r_api( "r_api", "gl", CVAR_RENDERER | CVAR_ARCHIVE, "the renderer", r_apiArgs, idCmdSystem::ArgCompletion_String<r_apiArgs> );
            idCVar s_volume( "s_volume_dB", "0", CVAR_SOUND | CVAR_ARCHIVE | CVAR_FLOAT, "volume in dB" );
            idCVar r_showTris( "r_showTris", "0", CVAR_RENDERER | CVAR_INTEGER, "not archived" );
            idCVar g_cheat( "g_cheat", "0", CVAR_GAME | CVAR_ARCHIVE | CVAR_CHEAT | CVAR_BOOL, "a cheat" );
        """.trimIndent()
        val found = IdTech4.parse(mapOf("neo/renderer/RenderSystem_init.cpp" to code))
        assertEquals(listOf("r_mode", "r_fullscreen", "r_api", "s_volume_dB"), found.map { it.key })
        assertEquals(listOf("INT", "5", "0", "9", "Renderer"), found.at("", "r_mode").let { listOf(it.kind.name, it.default, it.min.toString(), it.max.toString(), it.tab) })
        assertEquals(listOf("BOOL", "1", "0 = windowed, 1 = full screen"), found.at("", "r_fullscreen").let { listOf(it.kind.name, it.default, it.about) })
        assertEquals(listOf("best", "gl", "vulkan"), found.at("", "r_api").choices.map { it.value })
        assertEquals("Sound", found.at("", "s_volume_dB").tab)
    }

    @Test
    fun quakeIIIsArchivedVariablesFromTheEngineAndTheGamesTables() {
        val engine = """
            r_mode = Cvar_Get( "r_mode", "-2", CVAR_ARCHIVE | CVAR_LATCH );
            Cvar_CheckRange( r_mode, "-2", "13", CV_INTEGER );
            Cvar_SetDescription( r_mode, "Display resolution preset." );
            r_fullscreen = Cvar_Get( "r_fullscreen", "1", CVAR_ARCHIVE_ND | CVAR_LATCH );
            Cvar_CheckRange( r_fullscreen, "0", "1", CV_INTEGER );
            s_volume = Cvar_Get( "s_volume", "0.8", CVAR_ARCHIVE );
            cl_name = Cvar_Get( "name", "UnnamedPlayer", CVAR_USERINFO | CVAR_ARCHIVE );
            com_cheats = Cvar_Get( "sv_cheats", "1", CVAR_ROM | CVAR_ARCHIVE );
            r_debug = Cvar_Get( "r_debug", "0", CVAR_TEMP );
        """.trimIndent()
        val game = """
            static cvarTable_t cvarTable[] = {
                { &cg_fov, "cg_fov", "90", CVAR_ARCHIVE },
                { &cg_draw2D, "cg_draw2D", "1", 0 },
            };
        """.trimIndent()
        val found = Quake3.parse(mapOf("code/client/cl_main.c" to engine, "code/cgame/cg_main.c" to game))
        assertEquals(setOf("r_mode", "r_fullscreen", "s_volume", "name", "cg_fov"), found.map { it.key }.toSet())
        assertEquals(listOf("INT", "-2", "-2", "13", "Display resolution preset.", "Renderer"), found.at("", "r_mode").let { listOf(it.kind.name, it.default, it.min.toString(), it.max.toString(), it.about, it.tab) })
        assertEquals(Kind.BOOL, found.at("", "r_fullscreen").kind, "a whole number from 0 to 1")
        assertEquals(Kind.FLOAT, found.at("", "s_volume").kind)
        assertEquals(Kind.STRING, found.at("", "name").kind)
        assertEquals("Game", found.at("", "cg_fov").tab)
    }

    @Test
    fun rexgluesCvarsWithTheirCategoriesRangesAndWords() {
        val code = """
            REXCVAR_DEFINE_BOOL(vsync, true, "GPU", "Enable vertical sync");
            REXCVAR_DEFINE_INT32(resolution_scale, 1, "GPU", "Render scale").range(1, 4);
            REXCVAR_DEFINE_STRING(present_effect, "fsr", "GPU", "Post effect").allowed({"fsr", "cas", "none"});
            REXCVAR_DEFINE_DOUBLE(mouse_sensitivity, 0.0022, "Mouse", "How far a " "count turns");
            REXCVAR_DEFINE_BOOL(trace_calls, false, "Debug", "Trace").debug_only();
        """.trimIndent()
        val found = Rexglue.parse(mapOf("src/gpu.cpp" to code, "thirdparty/rexglue-sdk/include/rex/cvar.h" to "#define REXCVAR_DEFINE_BOOL(name, default_val, category, desc) x"))
        assertEquals(listOf("vsync", "resolution_scale", "present_effect", "mouse_sensitivity"), found.map { it.key })
        assertEquals(listOf("BOOL", "true", "GPU"), found.at("", "vsync").let { listOf(it.kind.name, it.default, it.tab) })
        assertEquals(listOf("1", "4"), found.at("", "resolution_scale").let { listOf(it.min.toString(), it.max.toString()) })
        assertEquals(listOf("fsr", "cas", "none"), found.at("", "present_effect").choices.map { it.value })
        assertEquals("How far a count turns", found.at("", "mouse_sensitivity").about)
    }

    @Test
    fun theSmallerPortsFromTheirOwnSettingsCode() {
        val rsdk = Rsdk.parse(
            mapOf(
                "RSDKv4/RetroEngine.hpp" to "#define DEFAULT_FULLSCREEN   false",
                "RSDKv4/Userdata.cpp" to """
                    ini.SetBool("Window", "FullScreen", Engine.startFullScreen = DEFAULT_FULLSCREEN);
                    ini.SetInteger("Window", "WindowScale", Engine.windowScale = 2);
                    ini.SetFloat("Audio", "BGMVolume", bgmVolume / (float)MAX_VOLUME);
                    ini.SetString("Dev", "DataFile", (char *)"Data.rsdk");
                    ini.SetInteger("Keyboard 1", "Up", inputDevice[INPUT_UP].keyMappings = SDL_SCANCODE_UP);
                    ini.SetFloat("Controller 1", "LStickDeadzone", LSTICK_DEADZONE = 0.3);
                """.trimIndent(),
            ),
        )
        assertEquals(listOf("Window/FullScreen", "Window/WindowScale", "Audio/BGMVolume", "Dev/DataFile", "Controller 1/LStickDeadzone"), rsdk.map { "${it.section}/${it.key}" })
        assertEquals(listOf("false", "2", null, "Data.rsdk", "0.3"), rsdk.map { it.default })

        val sm64 = Sm64CoopDx.parse(
            mapOf(
                Sm64CoopDx.CONFIG to """
                    ConfigWindow configWindow = { .fullscreen = false, .vsync = 1, };
                    unsigned int configFiltering = 2;
                    static const struct ConfigOption options[] = {
                        {.name = "fullscreen", .type = CONFIG_TYPE_BOOL, .boolValue = &configWindow.fullscreen},
                        {.name = "texture_filtering", .type = CONFIG_TYPE_UINT, .uintValue = &configFiltering},
                        {.name = "key_a", .type = CONFIG_TYPE_BIND, .uintValue = configKeyA},
                    };
                """.trimIndent(),
            ),
        )
        assertEquals(listOf("fullscreen" to "false", "texture_filtering" to "2"), sm64.map { it.key to it.default }, "bindings left out")

        val arx = ArxLibertatis.parse(
            mapOf(
                ArxLibertatis.CONFIG to """
                    namespace Default { const std::string renderer = "auto", language = std::string(); const int levelOfDetail = 2; const bool fullscreen = true; const float gamma = 5.f; }
                    namespace Section { const std::string Video = "video", Key = "key"; }
                    namespace Key { const std::string renderer = "renderer", fullscreen = "full_screen", levelOfDetail = "level_of_detail", gamma = "gamma"; }
                    void Config::init() {
                        video.renderer = reader.getKey(Section::Video, Key::renderer, Default::renderer);
                        video.fullscreen = reader.getKey(Section::Video, Key::fullscreen, Default::fullscreen);
                        video.levelOfDetail = reader.getKey(Section::Video, Key::levelOfDetail, Default::levelOfDetail);
                        video.gamma = reader.getKey(Section::Video, Key::gamma, Default::gamma);
                    }
                """.trimIndent(),
            ),
        )
        assertEquals(listOf("renderer" to Kind.STRING, "full_screen" to Kind.BOOL, "level_of_detail" to Kind.INT, "gamma" to Kind.FLOAT), arx.map { it.key to it.kind })
        assertEquals(listOf("auto", "true", "2", "5."), arx.map { it.default })

        val edge = EdgeClassic.parse(
            mapOf(
                "source_files/edge/r_misc.cc" to """
                    EDGE_DEFINE_CONSOLE_VARIABLE(r_fov, "90", kConsoleVariableFlagArchive)
                    EDGE_DEFINE_CONSOLE_VARIABLE_CLAMPED(r_vsync, "1", kConsoleVariableFlagArchive, 0, 1)
                    EDGE_DEFINE_CONSOLE_VARIABLE(debug_fps, "0", kConsoleVariableFlagNone)
                """.trimIndent(),
                "source_files/edge/s_sound.cc" to "EDGE_DEFINE_CONSOLE_VARIABLE(sound_effect_volume, \"0.15\", kConsoleVariableFlagArchive)",
            ),
        )
        assertEquals(listOf("r_fov" to Kind.INT, "r_vsync" to Kind.BOOL, "sound_effect_volume" to Kind.FLOAT), edge.map { it.key to it.kind })
        assertEquals(listOf("Renderer", "Renderer", "Sound"), edge.map { it.tab })

        val pd = PerfectDark.parse(
            mapOf(
                "port/src/video.c" to """
                    s32 vidVsync = 1;
                    f32 vidFov = 60.f;
                    void videoInit(void) {
                        configRegisterInt("Video.VSync", &vidVsync, 0, 1);
                        configRegisterFloat("Video.FieldOfView", &vidFov, 30.f, 120.f);
                        for (s32 i = 0; i < 4; ++i) { snprintf(key, sizeof(key), "Game.Player%d.Crosshair", i + 1); configRegisterInt(key, &g_Crosshair[i], 0, 2); }
                    }
                """.trimIndent(),
            ),
        )
        assertEquals(listOf("Video/VSync" to "1", "Video/FieldOfView" to "60."), pd.map { "${it.section}/${it.key}" to it.default })
        assertEquals(Kind.BOOL, pd.first().kind)
    }

    @Test
    fun aPortIsReadAtTheTagItsDownloadsNameAndSubmodulesAtTheirAddress() {
        val index = """
            {"ports": [
              {"id": "zelda64recomp", "version": "1.2.2", "homepage": "https://github.com/Zelda64Recomp/Zelda64Recomp",
               "builds": [{"url": "https://github.com/Zelda64Recomp/Zelda64Recomp/releases/download/v1.2.2/Zelda64Recompiled-Linux.tar.gz"}]},
              {"id": "openjk", "version": "latest", "homepage": "https://github.com/JACoders/OpenJK", "builds": []}
            ]}
        """.trimIndent()
        assertEquals(mapOf("zelda64recomp" to ("v1.2.2" to "https://github.com/Zelda64Recomp/Zelda64Recomp"), "openjk" to ("latest" to "https://github.com/JACoders/OpenJK")), PortSources.listed(index))
        val zelda = PortSources.emulators(index).single { it.family == "zelda64recomp" }
        assertEquals("Zelda64Recomp/Zelda64Recomp", zelda.repo)
        assertTrue(zelda.release.matches("v1.2.2") && !zelda.release.matches("v1.2.1"), "read at the one release the list names")
        assertEquals(mapOf("lib/RecompFrontend" to "https://github.com/N64Recomp/RecompFrontend"), gitmodules("[submodule \"lib/RecompFrontend\"]\n\tpath = lib/RecompFrontend\n\turl = https://github.com/N64Recomp/RecompFrontend\n"))
        assertEquals("https://github.com/owner/RecompFrontend", resolveUrl("https://github.com/owner/Game", "../RecompFrontend.git".removeSuffix(".git")))
    }

    @Test
    fun everyPortWhoseSettingsTheListNamesIsReadAndEveryPortReadIsNamed() {
        val root = generateSequence(java.io.File("").absoluteFile) { it.parentFile }.first { java.io.File(it, PortSources.INDEX).isFile }
        val index = java.io.File(root, PortSources.INDEX).readText()
        val data = java.io.File(root, "core/launch/src/commonMain/kotlin/io/github/matiyaaa/fuse/launch/settings/TuneData.kt").readText()
        val read = TuneWriter.readBack(data).first.map { it.first }.toSet()
        @Suppress("UNCHECKED_CAST")
        val ports = ((MiniJson.parse(index) as Map<String, Any?>)["ports"] as List<Map<String, Any?>>)
        val named = ports.filter { it["settings"] != null }.map { it["id"] as String }.toSet()
        assertEquals(emptySet(), named - read, "a port the list says has settings Fuse Tune hasn't read")
        val defined = PortSources.PORTS.map { it.id }.toSet() + setOf("devilutionx", "openrct2", "fallout2-ce", "fallout1-ce")
        assertEquals(emptySet(), defined - named, "a port Fuse Tune reads whose settings file the list doesn't name")
        // A port's file in a form other than ini is said by Fuse Tune's own data, never by the list (an older Fuse would drop it).
        assertTrue(ports.none { (it["settings"] as? Map<*, *>)?.containsKey("format") == true })
        val formats = TuneWriter.readMap(data, "TUNE_FORMATS")
        for (p in PortSources.PORTS) assertEquals(p.format.takeIf { it != "ini" }, formats[p.id], p.id)
    }
}
