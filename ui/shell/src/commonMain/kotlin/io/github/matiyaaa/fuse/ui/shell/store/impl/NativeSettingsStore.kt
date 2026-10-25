package io.github.matiyaaa.fuse.ui.shell.store.impl

import io.github.matiyaaa.fuse.launch.patches.DuckStationPatchRules
import io.github.matiyaaa.fuse.launch.patches.IniText
import io.github.matiyaaa.fuse.launch.patches.Pcsx2PatchRules
import io.github.matiyaaa.fuse.data.settings.AppSettings
import io.github.matiyaaa.fuse.data.settings.OwnSettingProfile
import io.github.matiyaaa.fuse.launch.settings.NativeSettings
import io.github.matiyaaa.fuse.launch.settings.SettingProfile
import io.github.matiyaaa.fuse.launch.settings.SettingProfiles
import io.github.matiyaaa.fuse.launch.settings.SettingSource
import io.github.matiyaaa.fuse.launch.settings.SettingsEdit
import io.github.matiyaaa.fuse.launch.settings.SettingsText
import io.github.matiyaaa.fuse.launch.settings.Tune
import io.github.matiyaaa.fuse.launch.settings.TuneKind
import io.github.matiyaaa.fuse.launch.settings.TuneSetting
import io.github.matiyaaa.fuse.launch.settings.YamlText
import io.github.matiyaaa.fuse.library.disc.DiscIdentity
import io.github.matiyaaa.fuse.model.EmulatorId
import io.github.matiyaaa.fuse.model.Game
import io.github.matiyaaa.fuse.model.GameId
import io.github.matiyaaa.fuse.model.InstalledEmulator
import io.github.matiyaaa.fuse.ui.shell.store.EmulatorFiles
import io.github.matiyaaa.fuse.ui.shell.store.EmulatorSettingsView
import io.github.matiyaaa.fuse.ui.shell.store.EverySettingView
import io.github.matiyaaa.fuse.ui.shell.store.SettingProfileView
import io.github.matiyaaa.fuse.ui.shell.store.TuneSection
import io.github.matiyaaa.fuse.ui.shell.store.TuneValue

/**
 * An emulator's own settings, for every game or for one game, read and changed in the emulator's own
 * files (PCSX2, DuckStation, PPSSPP, Dolphin, and RPCS3, RetroArch with a game's core options, Eden
 * and Azahar for Every Setting): a game's file holds only
 * what differs for it, the rest comes from the emulator's settings for every game. Every key Fuse
 * changes is recorded with what it held before, so Fuse can put back exactly that (see
 * [SettingsEdit]). Fuse's own picks ([NativeSettings]) come with words of Fuse's; Every Setting is
 * all of them, as the emulator's code has them ([Tune]).
 */
internal class NativeSettingsStore(
    private val ctx: StoreContext,
    private val detectNow: suspend () -> Unit,
    private val discIdentity: suspend (GameId) -> DiscIdentity?,
    private val pspGameId: suspend (Game) -> String?,
    /** A GameCube or Wii game's six-letter id (GALE01), from its name or its disc. */
    private val nintendoGameId: suspend (Game) -> String? = { null },
    /** The emulator [Game] starts in here, as a launch would choose it. */
    private val startsIn: suspend (Game) -> InstalledEmulator?,
    /** Changes the stored settings, for the person's own profiles. */
    private val changeSettings: suspend ((AppSettings) -> AppSettings) -> Unit = {},
    /** A PS3 game's title id (BLUS30443), which RPCS3 keeps its own settings under. */
    private val ps3TitleId: suspend (Game) -> String? = { null },
    /** The RetroArch core and content a game starts with, for its override file. */
    private val retroArchRun: suspend (Game) -> NativePatches.RetroArchRun? = { null },
) {
    private val data = ctx.data
    private val fs get() = ctx.services.fs

    /** Where one emulator's settings are: its file for every game and, for a game, that game's own. */
    private class Target(
        val family: String,
        val emulator: String,
        val files: EmulatorFiles,
        val globalPath: String,
        val gamePath: String?,
        val gameTitle: String?,
        /** The emulator's other settings files for every game, by [io.github.matiyaaa.fuse.launch.settings.NativeSetting.file]. */
        val globals: Map<String, String> = emptyMap(),
        /** Its files are YAML (RPCS3's), not ini. */
        val yaml: Boolean = false,
        /** Its files are RetroArch's `key = "value"` config, not ini. */
        val cfg: Boolean = false,
        /** Its files are JSON of groups (shadPS4's), not ini. */
        val json: Boolean = false,
        /** Its files are TOML (Xenia's), not ini. */
        val toml: Boolean = false,
        /** Its files are MAME's ini, an option a line with its name padded before its value. */
        val mameIni: Boolean = false,
        /**
         * Its file is one setting a line, its name then its value ([io.github.matiyaaa.fuse.launch.settings.WordsText]):
         * after this command (`seta`, the Quake engines'), or alone (`""`, the Doom ports'); null for neither.
         */
        val words: String? = null,
        /** Its files are Eden's, each value kept in two or three keys ([io.github.matiyaaa.fuse.launch.settings.EdenConfig]). */
        val eden: Boolean = false,
        /** Its files are Azahar's, each value kept in two keys ([io.github.matiyaaa.fuse.launch.settings.AzaharConfig]). */
        val azahar: Boolean = false,
        /** Its ini has no spaces around `=` (the Retro Engine's settings.ini, which its reader needs so). */
        val tight: Boolean = false,
                /** Its file is Supermodel.ini, its values read with their quotes and comments off ([io.github.matiyaaa.fuse.launch.settings.SupermodelIni]). */
        val supermodel: Boolean = false,
        /** The installed version, for which of every setting it has. */
        val version: String? = null,
        /** How its files are changed: the emulator's own folders, or a port's in Fuse's folder of ports. */
        val writer: Writer = EmulatorWriter(files),
        /**
         * The emulator reads a game's own file alone, in place of [globalPath] (Cemu's profile in
         * place of the one it ships), so a file Fuse makes starts as a copy of that one, beginning
         * with [madeAs]; and with a file of its own, a setting it hasn't got is the emulator's default.
         */
        val alone: Boolean = false,
        val madeAs: String = "",
        /** Read alone a section at a time (Vita3K's elements): a section a game's file hasn't got follows every game's. */
        val aloneBySection: Boolean = false,
        /** A game's own file is XML with this root (Vita3K's `config`), whatever the file for every game is. */
        val gameXml: String? = null,
        /**
         * A game's settings kept in the file for every game, in a section of its own (Flycast's
         * `[<game id>]`, each as `<section>.<key>`): where each goes, and the game it is, which its
         * changes are recorded under apart from every game's.
         */
        val gameEntry: ((TuneSetting) -> Pair<String, String>)? = null,
        val gameRecords: String? = null,
        /** A RetroArch game's core options, beside RetroArch's own settings. */
        val core: CoreTarget? = null,
        /** Shown by the tabs its own settings have (a port's menus), not by where in its file each is kept. */
        val byTab: Boolean = false,
    ) {
        val forGame: Boolean get() = gamePath != null

        /** A file of this emulator's ([path], where it is), to edit in place. */
        fun text(content: String?, path: String? = null): SettingsText = when {
            gameXml != null && path != null && path == gamePath -> io.github.matiyaaa.fuse.launch.settings.XmlText(content ?: "", gameXml)
            yaml -> YamlText(content ?: "")
            cfg -> io.github.matiyaaa.fuse.launch.settings.CfgText(content ?: "")
            // A text setting is written as a JSON string, whatever it holds.
            json -> io.github.matiyaaa.fuse.launch.settings.JsonText(content ?: "") { section, key -> isText(section, key) }
            toml -> io.github.matiyaaa.fuse.launch.settings.TomlText(content ?: "") { section, key -> isText(section, key) }
            mameIni -> io.github.matiyaaa.fuse.launch.settings.MameIniText(content ?: "")
            words != null -> io.github.matiyaaa.fuse.launch.settings.WordsText(content ?: "", words.takeIf { it.isNotEmpty() && it != BARE }, bare = words == BARE) { key -> isText("", key) }
            else -> IniText(content ?: "", spaced = !(eden || azahar || tight))
        }

        /** Whether the setting at [section] and [key] (in any of the emulator's files) holds text. */
        private fun isText(section: String, key: String): Boolean =
            Tune.forEmulator(family).any { it.kind == TuneKind.TEXT && it.section.equals(section, ignoreCase = true) && it.key.equals(key, ignoreCase = true) }

        /** What [file] holds for [key] as the emulator reads it: Eden's from its keys together, every other's as written. */
        fun valueIn(file: SettingsText?, section: String, key: String, game: Boolean, default: String?): String? = when {
            file == null -> null
            eden -> io.github.matiyaaa.fuse.launch.settings.EdenConfig.value(file, section, key, game, default)
            azahar -> io.github.matiyaaa.fuse.launch.settings.AzaharConfig.value(file, section, key, game, default)
            supermodel -> file.values(section, key).lastOrNull()?.let(io.github.matiyaaa.fuse.launch.settings.SupermodelIni::value)
            else -> file.values(section, key).lastOrNull()
        }

        /** The keys one change writes (Eden's two or three, every other's one), each with its value; null takes it out. */
        fun keys(section: String, key: String, value: String?, game: Boolean, default: String?): List<Triple<String, String, String?>> = when {
            eden -> io.github.matiyaaa.fuse.launch.settings.EdenConfig.writes(section, key, value, game, default)
            azahar -> io.github.matiyaaa.fuse.launch.settings.AzaharConfig.writes(section, key, value, game, default)
            else -> listOf(Triple(section, key, value))
        }

        /** Where the every-setting [s] is kept in a game's own settings: its section and key. */
        fun gameSectionOf(s: TuneSetting): String = gameEntry?.invoke(s)?.first ?: s.sectionIn(true)
        fun gameKeyOf(s: TuneSetting): String = gameEntry?.invoke(s)?.second ?: s.keyIn(true)

        /** What Fuse's changes to [path] are recorded under: a game's apart when it shares the file for every game. */
        fun record(path: String): String = if (gameRecords != null && path == gamePath) "$path#game:$gameRecords" else path

        /** The file the every-setting [setting] is changed in by this view. */
        fun pathOf(setting: TuneSetting): String = gamePath ?: setting.file?.let { globals[it] } ?: globalPath

        /** The file [setting] is changed in by this view. */
        fun pathOf(setting: io.github.matiyaaa.fuse.launch.settings.NativeSetting): String =
            gamePath ?: setting.file?.let { globals[it] } ?: globalPath

        /** Every file this view changes. */
        val paths: List<String> get() = gamePath?.let { listOfNotNull(it, core?.gamePath) } ?: (listOf(globalPath) + globals.values).distinct()
    }

    /**
     * A game's core options in RetroArch (runloop.c, `runloop_init_core_options_path`): the game's own
     * `.opt` ([gamePath]) when it has one, which RetroArch then reads alone; else its folder's
     * (`<folder name>.opt`); else the core's for every game (`<core>/<core>.opt`, or with
     * `global_core_options` on, `core_options_path` or `retroarch-core-options.cfg` beside
     * retroarch.cfg, which RetroArch also reads while the core's own file isn't made yet). [everyGame]
     * are those after the game's own, in the order RetroArch looks for them.
     */
    private class CoreTarget(val family: String, val name: String, val gamePath: String, val everyGame: List<String>)

    /** How a [Target]'s files are written and taken away. */
    private interface Writer {
        suspend fun write(path: String, text: String): Boolean
        suspend fun remove(path: String): Boolean
    }

    private class EmulatorWriter(private val files: EmulatorFiles) : Writer {
        override suspend fun write(path: String, text: String) = files.write(path, text)
        override suspend fun remove(path: String) = files.remove(path)
    }

    /** A port's settings, in its home in Fuse's folder of ports (never an emulator's folder). */
    private class PortWriter(private val files: io.github.matiyaaa.fuse.ui.shell.store.PortFiles) : Writer {
        override suspend fun write(path: String, text: String) = files.writeText(path, text)
        override suspend fun remove(path: String) = files.remove(path)
    }

    private fun no(title: String, reason: String) = null to EmulatorSettingsView.Unavailable(title, reason)

    /**
     * A Fuse Ports game's own settings: the port's file the list names ([io.github.matiyaaa.fuse.model.PortSettings],
     * in its home), read through Fuse Tune as the port's code has them. The port is the game, so
     * its file is the game's.
     */
    private fun portTarget(game: Game, files: EmulatorFiles): Pair<Target?, EmulatorSettingsView.Unavailable?> {
        val launcher = game.location.path.replace('\\', '/')
        val root = launcher.substringBeforeLast('/', "").ifEmpty { return no("Not set up", "Set this port up again in Addons, Ports.") }
        val id = launcher.substringAfterLast('/').removeSuffix(".${io.github.matiyaaa.fuse.launch.ports.PortLauncher.EXTENSION}")
        val installed = ctx.settings.value.ports.installed[id] ?: return no("Not installed", "Install it again in Addons, Ports.")
        val port = installed.port
        val own = port.settings
        if (own == null || Tune.forEmulator(id).isEmpty()) return no("Fuse can't change ${port.name}'s settings", "${port.name} keeps its settings its own way. Change them in its own menus.")
        val portFiles = ctx.services.portFiles ?: return no("Not on this device", "Ports' settings can be changed from Fuse on a computer.")
        val layout = io.github.matiyaaa.fuse.launch.ports.PortLayout(root, id)
        val path = own.file.replace(io.github.matiyaaa.fuse.model.PortTokens.HOME, layout.home).replace(io.github.matiyaaa.fuse.model.PortTokens.INSTALL, layout.install)
        // A port that keeps a file per tab (the N64 recompilations' general.json, graphics.json) keeps them together, beside the one the list names.
        val folder = path.substringBeforeLast('/')
        val others = Tune.forEmulator(id).mapNotNull { it.file }.distinct().associateWith { "$folder/$it" }
        return Target(
            id, port.name, files, path, null, port.game, globals = others,
            // As Fuse Tune read the port's code, whatever an older list says (it names only ini).
            json = Tune.format(id) == "json",
            toml = Tune.format(id) == "toml",
            tight = Tune.format(id) == "ini-tight",
            words = when (Tune.format(id)) {
                "seta" -> "seta"
                "words" -> ""
                "words-bare" -> BARE
                "edge" -> io.github.matiyaaa.fuse.launch.settings.WordsText.SLASH
                else -> null
            },
            version = port.version, writer = PortWriter(portFiles), byTab = true,
        ) to null
    }

    private suspend fun target(id: GameId?, emulator: EmulatorId?): Pair<Target?, EmulatorSettingsView.Unavailable?> {
        val files = ctx.services.settingsFiles ?: return no("Not on this device", "Emulator settings can be changed from Fuse on a computer. On this device, change them in the emulator.")
        if (ctx.installed.value.isEmpty()) detectNow()
        val game = id?.let { data.games.get(it) ?: return no("Game not found", "This game is no longer in the library.") }
        if (game != null && game.platformId == io.github.matiyaaa.fuse.launch.ports.PortAdapter.PLATFORM) return portTarget(game, files)
        val installed = if (game != null) {
            val starts = startsIn(game)
            when {
                starts != null && family(starts) in FAMILIES -> starts
                starts != null -> return null to EmulatorSettingsView.Unavailable(
                    "${starts.name}'s settings are in ${starts.name}", "This game starts in ${starts.name}, which keeps its own settings: open it to change them. $SUPPORTED",
                    open = starts.id to starts.name,
                )
                else -> PLATFORM_FAMILY[game.platformId.value]?.let { f -> ctx.installed.value.firstOrNull { family(it) == f } }
                    ?: return no("No emulator to set up", "$SUPPORTED None of them plays this game here.")
            }
        } else {
            ctx.installed.value.firstOrNull { it.id == emulator } ?: return no("Not installed", "This emulator isn't installed on this device.")
        }
        val family = family(installed)
        val name = installed.name
        // On Android, only an emulator whose files are in shared storage; the others' are in their own private folders.
        if (ctx.host == io.github.matiyaaa.fuse.model.Host.ANDROID && family !in NativeSettings.onAndroid) {
            // Said with a way there: the emulator opens on its own settings' home, a press away.
            return null to EmulatorSettingsView.Unavailable(
                "$name's settings are in $name",
                "$name keeps its settings in its own private folder on Android, which no other app can open. Open $name to change them. ${NativeSettings.ON_ANDROID}",
                open = installed.id to name,
            )
        }
        // ARMSX2 numbers its own releases, not PCSX2's: which of PCSX2's it follows isn't known.
        val version = installed.version.takeUnless { installed.id.value.substringAfter('.') in NativeSettings.ownVersions }
        fun notSetUp() = no("$name isn't set up yet", "Start $name once and finish its first-run setup, then come back.")
        fun unreadable(what: String) = no("Fuse can't tell which game this is", "$name keeps a game's own settings under its $what, which Fuse reads from ISO and BIN images (and EBOOT.PBP files for PSP). Compressed images (CHD, CSO) aren't read; change this game's settings in $name.")
        return when (family) {
            "pcsx2" -> {
                val home = files.pcsx2(installed) ?: return notSetUp()
                val gamePath = if (game != null) {
                    val disc = discIdentity(game.id) ?: return unreadable("serial and CRC")
                    val crc = disc.crc ?: return unreadable("serial and CRC")
                    "${home.gameSettings}/${Pcsx2PatchRules.gameSettingsName(disc.serial, crc)}"
                } else null
                Target(family, name, files, home.settingsFile, gamePath, game?.titles?.original, version = version) to null
            }
            "duckstation" -> {
                val home = files.duckstation(installed) ?: return notSetUp()
                val gamePath = if (game != null) {
                    val disc = discIdentity(game.id)?.takeIf { !it.ps2 } ?: return unreadable("serial")
                    "${home.gameSettings}/${DuckStationPatchRules.gameSettingsName(disc.serial)}"
                } else null
                Target(family, name, files, home.settingsFile, gamePath, game?.titles?.original, version = version) to null
            }
            "ppsspp" -> {
                val home = files.ppsspp(installed) ?: return notSetUp()
                val gamePath = if (game != null) home.gameSettingsFile(pspGameId(game) ?: return unreadable("game id")) else null
                Target(family, name, files, home.settingsFile, gamePath, game?.titles?.original, version = version) to null
            }
            "dolphin" -> {
                val home = files.dolphin(installed) ?: return notSetUp()
                val gamePath = if (game != null) {
                    val gameId = nintendoGameId(game)
                        ?: return no("Fuse can't tell which game this is", "Dolphin keeps a game's own settings under its six-letter id, which Fuse reads from ISO, GCM, RVZ, WIA and CISO images. Change this game's settings in Dolphin, or add its id to the file name, like [GALE01].")
                    home.gameFile(gameId)
                } else null
                Target(family, name, files, home.file(null), gamePath, game?.titles?.original, mapOf(NativeSettings.DOLPHIN_GFX to home.file(NativeSettings.DOLPHIN_GFX)), version = version) to null
            }
            "rpcs3" -> {
                val config = files.rpcs3Config(installed) ?: return notSetUp()
                // fs::get_config_dir(true) (Utilities/File.cpp): its own `config` folder on Windows.
                val dir = if (ctx.host == io.github.matiyaaa.fuse.model.Host.WINDOWS) "$config/config" else config
                val gamePath = if (game != null) {
                    val titleId = ps3TitleId(game)
                        ?: return no("Fuse can't tell which game this is", "RPCS3 keeps a game's own settings under its title id (like BLUS30443), which Fuse reads from the file or folder name, or from a game folder's PARAM.SFO. Change this game's settings in RPCS3.")
                    // rpcs3::utils::get_custom_config_path (rpcs3/Emu/system_utils.cpp).
                    "$dir/custom_configs/config_$titleId.yml"
                } else null
                Target(family, name, files, "$dir/config.yml", gamePath, game?.titles?.original, yaml = true, version = version) to null
            }
            "retroarch" -> {
                val home = files.retroArch(installed) ?: return notSetUp()
                val config = home.config ?: return notSetUp()
                var core: CoreTarget? = null
                // config_load_override (configuration.c): <config folder>/<core's library name>/<content>.cfg.
                val gamePath = if (game != null) {
                    val run = retroArchRun(game) ?: return no("Fuse can't tell which core plays this", "RetroArch keeps a game's own settings under the core that plays it. Choose a core for this system first.")
                    val library = io.github.matiyaaa.fuse.sync.retroArchLibraryName(run.core)
                        ?: return no("Fuse doesn't know this core's name", "RetroArch keeps a game's own settings under the core's own name, which Fuse doesn't know for ${run.core}. Change them in RetroArch's Quick Menu, Overrides.")
                    val content = io.github.matiyaaa.fuse.launch.patches.RetroArchCheats.contentName(run.content)
                    core = coreTarget(run.core, library, run.content, home.configDir, config)
                    io.github.matiyaaa.fuse.launch.patches.RetroArchCheats.overrideFile(home.configDir, library, content)
                } else null
                Target(family, name, files, config, gamePath, game?.titles?.original, cfg = true, version = version, core = core) to null
            }
            "azahar", "azaharplus", "lime3ds", "mandarine" -> {
                val config = files.azaharConfig(installed) ?: return notSetUp()
                val gamePath = if (game != null) {
                    val titleId = files.threeDsTitleId(game.location.path, game.tags.serial)
                        ?: return no("Fuse can't tell which game this is", "Azahar keeps a game's own settings under its title id, which Fuse reads from cartridge images, CXI, APP and CIA files. Compressed ones (.zcci, .zcxi) don't say it; change this game's settings in Azahar: right-click it, then Properties.")
                    io.github.matiyaaa.fuse.launch.settings.AzaharConfig.gameFile(config, titleId)
                } else null
                Target(family, name, files, io.github.matiyaaa.fuse.launch.settings.AzaharConfig.globalFile(config), gamePath, game?.titles?.original, azahar = true, version = version) to null
            }
            "mame" -> {
                // src/emu/emuopts.cpp, mame_options::parse_standard_inis: mame.ini, then a system's own <short name>.ini along its ini path.
                val folder = files.mameIniFolder(installed) ?: return notSetUp()
                val gamePath = game?.let { "$folder/${it.location.path.replace('\\', '/').substringAfterLast('/').substringBeforeLast('.')}.ini" }
                Target(family, name, files, "$folder/mame.ini", gamePath, game?.titles?.original, mameIni = true, version = version) to null
            }
            "flycast" -> {
                // emu.cfg; a game's own in its [<game id>] section there, each as <section>.<key> (core/cfg/option.h Option::load).
                val config = files.flycastConfig(installed) ?: return notSetUp()
                val gameId = if (game != null) {
                    files.dreamcastGameId(game.location.path, game.tags.serial)
                        ?: return no("Fuse can't tell which game this is", "Flycast keeps a game's own settings under its product number, which Fuse reads from GDI, ISO and BIN images. Compressed ones (CHD, CDI) don't say it here; change this game's settings in Flycast.")
                } else null
                val path = "$config/emu.cfg"
                Target(
                    family, name, files, path, if (gameId != null) path else null, game?.titles?.original, version = version,
                    gameEntry = gameId?.let { id -> { s: TuneSetting -> id to "${s.section}.${s.key}" } }, gameRecords = gameId,
                ) to null
            }
            "melonds" -> {
                // melonDS.toml in its config folder (main.cpp pathInit); the first instance's own under [Instance0].
                if (game != null) return no("melonDS has no settings of a game's own", "Every game plays with melonDS's settings for every game. Change them from melonDS's own page in Fuse, or in melonDS.")
                val config = files.melondsConfig(installed) ?: return notSetUp()
                Target(family, name, files, "$config/melonDS.toml", null, null, toml = true, version = version) to null
            }
            "supermodel" -> {
                // Supermodel.ini in its Config folder (FileSystemPath.cpp); a game's own in its [ <rom set> ] section
                // there, which Supermodel reads over [ Global ] (Main.cpp, MergeINISections).
                val config = files.supermodelConfig(installed, game?.location?.path) ?: return notSetUp()
                val path = "$config/Supermodel.ini"
                val romSet = game?.location?.path?.replace('\\', '/')?.substringAfterLast('/')?.substringBeforeLast('.')
                Target(
                    family, name, files, path, if (romSet != null) path else null, game?.titles?.original, version = version, supermodel = true,
                    gameEntry = romSet?.let { r -> { s: TuneSetting -> r to s.key } }, gameRecords = romSet,
                ) to null
            }
            "vita3k" -> {
                // settings.cpp: config.yml in its config folder, a game's config/config_<title id>.xml (get_custom_config_path).
                val config = files.vita3kConfig(installed) ?: return notSetUp()
                val gamePath = if (game != null) {
                    val titleId = game.tags.serial?.uppercase()?.takeIf { VITA_ID.matches(it) }
                        ?: Regex("\\[([A-Za-z]{4}\\d{5})]").find(game.location.path)?.groupValues?.get(1)?.uppercase()
                        ?: return no("Fuse can't tell which game this is", "Vita3K keeps a game's own settings under its title id (like PCSE00082), which Fuse reads from the game's name, like [PCSE00082]. Change this game's settings in Vita3K.")
                    "$config/config/config_$titleId.xml"
                } else null
                Target(
                    family, name, files, "$config/config.yml", gamePath, game?.titles?.original, yaml = true, version = version,
                    alone = true, aloneBySection = true, gameXml = "config",
                ) to null
            }
            "xenia" -> {
                // config.cc: xenia-canary.config.toml in its storage root, a game's config/<title id>.config.toml (ReadGameConfig).
                val root = files.xeniaRoot(installed) ?: return notSetUp()
                val gamePath = if (game != null) {
                    val titleId = game.tags.serial?.uppercase()?.takeIf { Regex("^[0-9A-F]{8}$").matches(it) }
                        ?: Regex("\\[([0-9A-Fa-f]{8})]").find(game.location.path)?.groupValues?.get(1)?.uppercase()
                        ?: return no("Fuse can't tell which game this is", "Xenia keeps a game's own settings under its title id (8 letters and digits, like 4D5307E6), which Fuse reads from the file name, like [4D5307E6]. Change this game's settings in Xenia.")
                    "$root/config/$titleId.config.toml"
                } else null
                Target(family, name, files, "$root/xenia-canary.config.toml", gamePath, game?.titles?.original, toml = true, version = version) to null
            }
            "shadps4" -> {
                // EmulatorSettingsImpl::Save (src/core/emulator_settings.cpp): config.json in its user folder, a game's custom_configs/<serial>.json.
                val user = files.shadPs4User(installed)?.takeIf { fs.readText("$it/config.json", MAX_TEXT) != null } ?: return notSetUp()
                val gamePath = if (game != null) {
                    val serial = game.tags.serial?.uppercase()?.filter { it.isLetterOrDigit() }?.takeIf { it.startsWith("CUSA") || it.startsWith("PPSA") }
                        ?: return no("Fuse can't tell which game this is", "shadPS4 keeps a game's own settings under its serial (like CUSA00900), which Fuse reads from the game's folder or file name. Change this game's settings in shadPS4.")
                    "$user/custom_configs/$serial.json"
                } else null
                Target(family, name, files, "$user/config.json", gamePath, game?.titles?.original, json = true, version = version) to null
            }
            "cemu" -> {
                if (game == null) return no("Cemu's settings for every game are in Cemu", "Cemu keeps them in its own Options. Fuse changes a Wii U game's own profile: open a game's Emulator Settings.")
                val home = files.cemu(installed) ?: return notSetUp()
                val titleId = files.wiiUTitleId(game.location.path, game.tags.serial)
                    ?: return no("Fuse can't tell which game this is", "Cemu keeps a game's profile under its title id, which Fuse reads from a game folder's meta/meta.xml (beside its code folder). Change this game's profile in Cemu: right-click it, then Edit Game Profile.")
                val title = game.titles.original
                Target(
                    family, name, files, io.github.matiyaaa.fuse.launch.settings.CemuProfile.shippedFile(home.data, titleId),
                    io.github.matiyaaa.fuse.launch.settings.CemuProfile.gameFile(home.config, titleId), title, version = version,
                    alone = true, madeAs = io.github.matiyaaa.fuse.launch.settings.CemuProfile.header(title),
                ) to null
            }
            "eden", "citron" -> {
                val config = files.edenConfig(installed) ?: return notSetUp()
                val gamePath = if (game != null) {
                    val titleId = game.tags.serial?.let(io.github.matiyaaa.fuse.library.parse.Serials::switchBaseTitleId)
                        ?: return no("Fuse can't tell which game this is", "Eden keeps a game's own settings under its title id, which Fuse reads from the file name, like [0100F2C0115B6000]. Change this game's settings in Eden: right-click it, then Properties.")
                    io.github.matiyaaa.fuse.launch.settings.EdenConfig.gameFile(config, titleId)
                } else null
                Target(family, name, files, io.github.matiyaaa.fuse.launch.settings.EdenConfig.globalFile(config), gamePath, game?.titles?.original, eden = true, version = version) to null
            }
            else -> no("Fuse can't change ${installed.name}'s settings", "$SUPPORTED Change ${installed.name}'s in ${installed.name}.")
        }
    }

    /**
     * [core]'s options for the game [content], when Fuse Tune has read them and RetroArch reads a
     * game's own (`game_specific_options`, on unless retroarch.cfg turns it off; configuration.c).
     */
    private suspend fun coreTarget(core: String, library: String, content: String, configDir: String, retroarchCfg: String): CoreTarget? {
        val family = io.github.matiyaaa.fuse.launch.settings.CoreOptionFiles.family(core)
        if (Tune.forEmulator(family).isEmpty()) return null
        val cfg = io.github.matiyaaa.fuse.launch.settings.CfgText(fs.readText(retroarchCfg, MAX_TEXT) ?: "")
        fun on(key: String, default: Boolean) = cfg.values("", key).lastOrNull()?.trim()?.lowercase()?.let { it == "true" } ?: default
        if (!on("game_specific_options", true)) return null
        val files = io.github.matiyaaa.fuse.launch.settings.CoreOptionFiles
        val global = files.globalFile(cfg.values("", "core_options_path").lastOrNull(), retroarchCfg)
        val every = listOfNotNull(files.folderFile(configDir, library, content)) +
            if (on("global_core_options", false)) listOf(global) else listOf(files.coreFile(configDir, library), global)
        return CoreTarget(family, library, files.gameFile(configDir, library, content), every)
    }

    private suspend fun read(t: Target, path: String?): SettingsText? = path?.let { p -> fs.readText(p, MAX_TEXT)?.let { t.text(it, p) } }

    /** A core options file RetroArch reads: `key = "value"`, like retroarch.cfg. */
    private suspend fun readCore(path: String): SettingsText? = fs.readText(path, MAX_TEXT)?.let { io.github.matiyaaa.fuse.launch.settings.CfgText(it) }

    /** The file the game's core options come from without one of its own: the first of [CoreTarget.everyGame] that is there. */
    private suspend fun coreEveryGame(c: CoreTarget): SettingsText? = c.everyGame.firstNotNullOfOrNull { readCore(it) }

    suspend fun view(id: GameId?, emulator: EmulatorId?): EmulatorSettingsView {
        val (t, unavailable) = target(id, emulator)
        if (t == null) return unavailable!!
        val globals = (listOf<String?>(null) + t.globals.keys).associateWith { read(t, it?.let(t.globals::get) ?: t.globalPath) }
        val game = read(t, t.gamePath)
        val settings = NativeSettings.forEmulator(t.family, ctx.host).filter { !t.forGame || it.perGame }
        val values = settings.map { SettingsEdit.valueFor(it, if (t.forGame) game else null, globals[it.file]) }
        val current = values.associate { it.setting.id to it.value }
        return EmulatorSettingsView.Ready(
            emulator = t.emulator,
            game = t.gameTitle,
            values = values,
            canRestore = t.paths.any { data.owned.get(KIND, t.record(it)).isNotEmpty() },
            profiles = if (t.gamePath == null) emptyList() else profiles(t.family).map { p ->
                SettingProfileView(p.id, p.name, p.about, p.own, SettingProfiles.matches(p, current, settings), p.lines)
            },
        )
    }

    /** Fuse's profiles for [family], then the person's own for it. */
    private suspend fun profiles(family: String): List<SettingProfile> {
        val offered = NativeSettings.forEmulator(family, ctx.host).filter { it.perGame }
        val own = data.settings.current().library.settingProfiles.filter { it.family == family }.map { o ->
            // Only what this emulator still has and accepts, should a value have gone since.
            val kept = o.values.filter { (k, v) -> offered.any { s -> s.id == k && s.choices.any { it.value == v } } }
            SettingProfile(o.id, o.name, describe(kept, offered), kept, own = true)
        }.filter { it.values.isNotEmpty() }
        return SettingProfiles.builtIn(family, ctx.host) + own
    }

    private fun describe(values: Map<String, String>, settings: List<io.github.matiyaaa.fuse.launch.settings.NativeSetting>): String =
        values.mapNotNull { (k, v) -> settings.firstOrNull { it.id == k }?.let { "${it.label}: ${it.labelFor(v)}" } }.joinToString(", ")

    /**
     * Gives the game every value of [profile] in one change of its own settings file, each recorded
     * like a single change (see [set]), so Put Back undoes it the same way.
     */
    suspend fun applyProfile(id: GameId?, emulator: EmulatorId?, profile: String): Boolean {
        val t = target(id, emulator).first ?: return false
        val path = t.gamePath ?: return false
        val p = profiles(t.family).firstOrNull { it.id == profile } ?: return false
        val settings = NativeSettings.forEmulator(t.family, ctx.host)
        val before = fs.readText(path, MAX_TEXT)
        val file = t.text(before, path)
        var records = data.owned.get(KIND, path)
        for ((key, value) in p.values) {
            val s = settings.firstOrNull { it.id == key && it.perGame } ?: continue
            records = SettingsEdit.change(file, records, s.sectionIn(game = true), s.key, value)
        }
        if (!t.writer.write(path, file.toString())) return false
        data.owned.set(KIND, path, records)
        if (before == null) data.owned.set(KIND, "$path#made", setOf("file"))
        return true
    }

    /** Keeps the game's values now (its own, else every game's) as a profile of the person's own. */
    suspend fun saveProfile(id: GameId?, emulator: EmulatorId?, name: String): Boolean {
        val view = view(id, emulator) as? EmulatorSettingsView.Ready ?: return false
        val t = target(id, emulator).first ?: return false
        val clean = name.trim().take(40).ifEmpty { return false }
        val values = view.values.associate { it.setting.id to it.value }
        val own = OwnSettingProfile("own-" + ctx.now().toString(36), t.family, clean, values)
        changeSettings { s -> s.copy(library = s.library.copy(settingProfiles = s.library.settingProfiles.filterNot { it.family == t.family && it.name.equals(clean, ignoreCase = true) } + own)) }
        return true
    }

    suspend fun deleteProfile(profile: String): Boolean {
        if (data.settings.current().library.settingProfiles.none { it.id == profile }) return false
        changeSettings { s -> s.copy(library = s.library.copy(settingProfiles = s.library.settingProfiles.filterNot { it.id == profile })) }
        return true
    }

    /**
     * Gives [setting] the [value] (for one game, or every game); null takes the game's own value out
     * so it follows every game's again (or, for every game, leaves the emulator's default).
     */
    suspend fun set(id: GameId?, emulator: EmulatorId?, setting: String, value: String?): Boolean {
        val t = target(id, emulator).first ?: return false
        val s = NativeSettings.forEmulator(t.family, ctx.host).firstOrNull { it.id == setting } ?: return false
        if (t.forGame && !s.perGame) return false
        if (value != null && s.choices.none { it.value == value }) return false
        val path = t.pathOf(s)
        val before = fs.readText(path, MAX_TEXT)
        val file = t.text(before, path)
        val records = SettingsEdit.change(file, data.owned.get(KIND, path), s.sectionIn(t.forGame), s.key, value)
        if (!t.writer.write(path, file.toString())) return false
        data.owned.set(KIND, path, records)
        // A game's own file Fuse made: taken away again once nothing Fuse set is left in it.
        if (before == null) data.owned.set(KIND, "$path#made", setOf("file"))
        return true
    }

    /** Puts back what Fuse changed in this file; a key changed in the emulator since stays as it is. */
    suspend fun restore(id: GameId?, emulator: EmulatorId?): Boolean {
        val t = target(id, emulator).first ?: return false
        var any = false
        for (path in t.paths) {
            val records = data.owned.get(KIND, t.record(path))
            if (records.isEmpty()) continue
            val text = fs.readText(path, MAX_TEXT)
            val file = t.text(text, path)
            SettingsEdit.restore(file, records)
            val made = data.owned.get(KIND, "$path#made").isNotEmpty()
            val done = when {
                text == null -> true
                made && !file.hasEntries() -> t.writer.remove(path)
                else -> t.writer.write(path, file.toString())
            }
            if (!done) return false
            data.owned.set(KIND, t.record(path), emptySet())
            data.owned.set(KIND, "$path#made", emptySet())
            any = true
        }
        return any
    }

    /**
     * Every setting of the emulator's (Fuse Tune) for the game [id] or every game in [emulator], as
     * the installed version has them, grouped as the emulator's code groups them: each with what the
     * game's own file, else the file for every game, holds, else the emulator's default.
     */
    suspend fun every(id: GameId?, emulator: EmulatorId?): EverySettingView {
        val (t, unavailable) = target(id, emulator)
        if (t == null) return EverySettingView.Unavailable(unavailable!!.title, unavailable.reason, unavailable.open)
        val offered = offered(t).filter { (s, _) -> !t.forGame || s.perGame }
        val all = offered.map { it.first }
        val locks = offered.mapNotNull { (s, why) -> why?.let { s.id to it } }.toMap()
        if (all.isEmpty()) return EverySettingView.Unavailable("Fuse Tune hasn't read ${t.emulator}", "${t.emulator}'s settings aren't in Fuse Tune yet. $SUPPORTED")
        val files = (listOf<String?>(null) + all.mapNotNull { it.file }.distinct()).associateWith { f ->
            read(t, f?.let { t.globals[it] } ?: t.globalPath)
        }
        val game = read(t, t.gamePath)
        // Grouped as the emulator's own settings group them: its tabs where its file has no sections (RetroArch's).
        val sections = all.groupBy { it.file to (it.tab?.takeIf { _ -> it.section.isEmpty() || t.byTab } ?: it.section) }.map { (where, settings) ->
            val (file, section) = where
            val first = settings.first()
            TuneSection(
                id = "${file.orEmpty()}|$section",
                title = when {
                    first.section.isEmpty() || (t.byTab && first.tab != null) -> section.ifEmpty { "Other" }
                    else -> sectionTitle(t.family, file, first.path)
                },
                values = settings.map { valueOf(t, it, if (t.forGame) game else null, files[it.file]).copy(locked = locks[it.id]) },
            )
        }
        val known = versionKnown(t.version)
        return EverySettingView.Ready(
            emulator = t.emulator,
            game = t.gameTitle,
            release = if (known) Tune.releaseFor(t.family, t.version) else null,
            sections = sections + coreSections(t),
            canRestore = t.paths.any { data.owned.get(KIND, t.record(it)).isNotEmpty() },
            gameFileNote = when {
                !t.forGame || fs.readText(t.gamePath!!, MAX_TEXT) != null -> null
                t.family == "rpcs3" -> RPCS3_GAME_FILE
                t.alone && fs.readText(t.globalPath, MAX_TEXT) != null -> "Until this game has a profile of its own, ${t.emulator} reads the one it ships for it, shown here as every game's; Fuse makes the game's own as a copy of it."
                else -> null
            },
            versionUnknown = !known && Tune.releases(t.family).size > 1,
        )
    }

    /**
     * The settings [t] offers, each with why it is locked (null when it isn't): those its version
     * has ([Tune.forVersion]); when Fuse can't tell the version, every release's, a setting that not
     * every release read has locked, since the one installed may not read it.
     */
    private fun offered(t: Target): List<Pair<TuneSetting, String?>> {
        if (versionKnown(t.version)) return Tune.forVersion(t.family, t.version).map { it to null }
        val read = Tune.releases(t.family)
        return Tune.forEmulator(t.family).map { s ->
            s to when {
                s.releases.size >= read.size -> null
                // Newest first: in the newest one read means it came in later; else it went.
                read.firstOrNull() in s.releases -> "Locked: only in ${t.emulator} ${s.releases.last()} and later, and Fuse can't tell which version this is"
                else -> "Locked: only in ${t.emulator} ${s.releases.first()} and earlier, and Fuse can't tell which version this is"
            }
        }
    }

    /** Whether [version] says which release it is (a number Fuse Tune can place). */
    private fun versionKnown(version: String?): Boolean = !version.isNullOrBlank() && io.github.matiyaaa.fuse.launch.settings.TuneVersions.numbers(version).isNotEmpty()

    /**
     * The game's core options, by the core's own categories: from the game's own `.opt` when it has
     * one (RetroArch reads that alone, so an option it hasn't got is the core's default), else what
     * every game gets ([coreEveryGame]).
     */
    private suspend fun coreSections(t: Target): List<TuneSection> {
        val c = t.core ?: return emptyList()
        val every = coreEveryGame(c)
        val own = readCore(c.gamePath)
        return Tune.forEmulator(c.family).groupBy { it.tab.orEmpty() }.map { (tab, settings) ->
            TuneSection(
                id = "${c.family}|$tab",
                title = if (tab.isEmpty()) "${c.name} Core" else "${c.name} Core: $tab",
                values = settings.map { s ->
                    val written = every?.values("", s.key)?.lastOrNull()
                    val everyGame = written ?: s.default
                    val mine = own?.values("", s.key)?.lastOrNull()
                    when {
                        mine != null -> TuneValue(s, mine, SettingSource.GAME, everyGame)
                        own != null || written == null -> TuneValue(s, s.default, SettingSource.DEFAULT, everyGame)
                        else -> TuneValue(s, written, SettingSource.EVERY_GAME, everyGame)
                    }
                },
            )
        }
    }

    /**
     * Gives a game's core option [value] in its own `.opt`. RetroArch reads that file alone, so Fuse
     * makes it as RetroArch's own Quick Menu does (Core Options, Save Game Options): with every option
     * as the game has it now, each recorded, then this one. Null gives it what every game gets.
     */
    private suspend fun setCore(t: Target, c: CoreTarget, s: TuneSetting, value: String?): Boolean {
        val every = coreEveryGame(c)
        fun everyGame(o: TuneSetting) = every?.values("", o.key)?.lastOrNull() ?: o.default
        val before = fs.readText(c.gamePath, MAX_TEXT)
        val file = io.github.matiyaaa.fuse.launch.settings.CfgText(before ?: "")
        var records = data.owned.get(KIND, c.gamePath)
        if (before == null) for (o in Tune.forEmulator(c.family)) everyGame(o)?.let { records = SettingsEdit.change(file, records, "", o.key, it) }
        records = SettingsEdit.change(file, records, "", s.key, value ?: everyGame(s))
        if (!t.writer.write(c.gamePath, file.toString())) return false
        data.owned.set(KIND, c.gamePath, records)
        if (before == null) data.owned.set(KIND, "${c.gamePath}#made", setOf("file"))
        return true
    }

    /**
     * What [emulator]'s files for every game hold for each of [values], as the emulator reads it
     * (Azahar's and Eden's from their keys together), or null when its settings can't be read here.
     */
    suspend fun current(emulator: EmulatorId, values: List<io.github.matiyaaa.fuse.launch.online.OnlineValue>): Map<io.github.matiyaaa.fuse.launch.online.OnlineValue, String?>? {
        val t = target(null, emulator).first ?: return null
        val files = values.map { it.file }.distinct().associateWith { f -> read(t, f?.let { t.globals[it] } ?: t.globalPath) }
        return values.associateWith { v -> t.valueIn(files[v.file], v.section, v.key, false, null) }
    }

    /**
     * Gives [emulator]'s files for every game each of [values] (each as the emulator writes it, see
     * [Target.keys]), recorded apart from the person's other setting changes (as [TOGETHER]), so
     * [restoreTogether] puts back exactly these.
     */
    suspend fun setTogether(emulator: EmulatorId, values: List<io.github.matiyaaa.fuse.launch.online.OnlineValue>): Boolean {
        val t = target(null, emulator).first ?: return false
        for ((file, these) in values.groupBy { it.file }) {
            val path = file?.let { t.globals[it] } ?: t.globalPath
            val text = t.text(fs.readText(path, MAX_TEXT), path)
            var records = data.owned.get(TOGETHER, path)
            for (v in these) for ((section, key, value) in t.keys(v.section, v.key, v.value, false, null)) records = SettingsEdit.change(text, records, section, key, value)
            if (!t.writer.write(path, text.toString())) return false
            data.owned.set(TOGETHER, path, records)
        }
        return true
    }

    /** Whether Play Together changed [emulator]'s settings, so they can be put back. */
    suspend fun changedTogether(emulator: EmulatorId): Boolean {
        val t = target(null, emulator).first ?: return false
        return t.paths.any { data.owned.get(TOGETHER, it).isNotEmpty() }
    }

    /** Puts back what Play Together changed in [emulator]'s settings; a value changed in the emulator since stays. */
    suspend fun restoreTogether(emulator: EmulatorId): Boolean {
        val t = target(null, emulator).first ?: return false
        var any = false
        for (path in t.paths) {
            val records = data.owned.get(TOGETHER, path)
            if (records.isEmpty()) continue
            val text = fs.readText(path, MAX_TEXT) ?: run { data.owned.set(TOGETHER, path, emptySet()); continue }
            val file = t.text(text, path)
            SettingsEdit.restore(file, records)
            if (!t.writer.write(path, file.toString())) return false
            data.owned.set(TOGETHER, path, emptySet())
            any = true
        }
        return any
    }

    /** RPCS3's `rpcn.yml` for [emulator] (beside its `config.yml`), or null when it isn't RPCS3 or isn't set up. */
    suspend fun rpcnPath(emulator: EmulatorId): Pair<EmulatorFiles, String>? {
        val t = target(null, emulator).first?.takeIf { it.family == "rpcs3" } ?: return null
        return t.files to t.globalPath.substringBeforeLast('/') + "/" + io.github.matiyaaa.fuse.launch.online.Rpcn.FILE
    }

    /** Gives the every-setting [setting] the [value] (see [FuseStore.LibraryOps.setEveryEmulatorSetting]). */
    suspend fun setEvery(id: GameId?, emulator: EmulatorId?, setting: String, value: String?): Boolean {
        val t = target(id, emulator).first ?: return false
        t.core?.takeIf { setting.substringBefore('|') == it.family }?.let { c ->
            val s = Tune.find(setting) ?: return false
            if (value != null && !s.accepts(value, ctx.host)) return false
            return setCore(t, c, s, value)
        }
        val (s, locked) = offered(t).firstOrNull { it.first.id == setting } ?: return false
        if (locked != null) return false
        if (t.forGame && !s.perGame) return false
        if (value != null && !s.accepts(value, ctx.host)) return false
        val written = value?.let { written(s, it) }
        val path = t.pathOf(s)
        val before = fs.readText(path, MAX_TEXT)
        val file = t.text(before, path)
        var records = data.owned.get(KIND, t.record(path))
        if (before == null && t.alone && t.forGame) {
            // Made as a copy of what the emulator read until now, every key of it recorded.
            val from = read(t, t.globalPath)
            val made = t.text(t.madeAs, path)
            // Read a section at a time, every one of its settings goes in, as the emulator's own dialog writes them.
            for (o in offered(t).map { it.first }.filter { it.perGame }) {
                val v = from?.values(o.section, o.key)?.lastOrNull() ?: o.default.takeIf { t.aloneBySection }
                v?.let { records = SettingsEdit.change(made, records, t.gameSectionOf(o), t.gameKeyOf(o), written(o, it)) }
            }
            for ((section, key, v) in t.keys(t.gameSectionOf(s), t.gameKeyOf(s), written, true, s.default)) records = SettingsEdit.change(made, records, section, key, v)
            if (!t.writer.write(path, made.toString())) return false
            data.owned.set(KIND, path, records)
            data.owned.set(KIND, "$path#made", setOf("file"))
            return true
        }
        val (inSection, inKey) = if (t.forGame) t.gameSectionOf(s) to t.gameKeyOf(s) else s.section to s.key
        for ((section, key, v) in t.keys(inSection, inKey, written, t.forGame, s.default)) records = SettingsEdit.change(file, records, section, key, v)
        if (!t.writer.write(path, file.toString())) return false
        data.owned.set(KIND, t.record(path), records)
        if (before == null) data.owned.set(KIND, "$path#made", setOf("file"))
        return true
    }

    private fun valueOf(t: Target, s: TuneSetting, game: SettingsText?, global: SettingsText?): TuneValue {
        fun shown(v: String?) = if (s.kind == TuneKind.SWITCH) Tune.switchShown(s.family, v) else v
        val every = shown(t.valueIn(global, s.section, s.key, game = false, s.default))
        val own = shown(if (s.perGame) t.valueIn(game, t.gameSectionOf(s), t.gameKeyOf(s), game = true, s.default) else null)
        val everyGame = every ?: shown(s.default)
        return when {
            own != null -> TuneValue(s, own, SettingSource.GAME, everyGame)
            // Read alone: what its own file hasn't got is the emulator's default, not the other file's.
            t.alone && game != null && (!t.aloneBySection || game.hasSection(t.gameSectionOf(s))) -> TuneValue(s, shown(s.default), SettingSource.DEFAULT, everyGame)
            every != null -> TuneValue(s, every, SettingSource.EVERY_GAME, everyGame)
            else -> TuneValue(s, shown(s.default), SettingSource.DEFAULT, everyGame)
        }
    }

    /**
     * [value] as the emulator writes it: Dolphin's and PPSSPP's switches as `True` and `False`,
     * Supermodel's text in quotes, and a decimal in a TOML file with its point, as melonDS reads only
     * a float as one (`Table::GetDouble`, `is_floating`).
     */
    private fun written(s: TuneSetting, value: String): String = when {
        s.family == "supermodel" && s.kind == TuneKind.TEXT -> io.github.matiyaaa.fuse.launch.settings.SupermodelIni.text(value)
        s.family in TOML_POINT && s.kind == TuneKind.DECIMAL && Regex("^[+-]?\\d+$").matches(value.trim()) -> value.trim() + ".0"
        s.kind != TuneKind.SWITCH -> value.trim().takeIf { s.kind != TuneKind.TEXT } ?: value
        Tune.switchWritten(s.family, value.equals("true", ignoreCase = true)) != null -> Tune.switchWritten(s.family, value.equals("true", ignoreCase = true))!!
        s.default?.firstOrNull()?.isUpperCase() == true || s.family in setOf("dolphin", "ppsspp") -> value.lowercase().replaceFirstChar { it.uppercase() }
        else -> value.lowercase()
    }

    /**
     * A part of the emulator's settings as Fuse shows it: its own name as words (DuckStation's
     * `TextureReplacements` is Texture Replacements, PCSX2's `EmuCore/GS` Emu Core / GS), and the
     * file when it has more than one.
     */
    private fun sectionTitle(family: String, file: String?, path: List<String>): String {
        val parts = if (family == "pcsx2") path.flatMap { it.split('/') } else path
        // A space Qt's ini wrote as %20 (Azahar's "Data%20Storage") read as one.
        val name = parts.joinToString(" / ") { io.github.matiyaaa.fuse.ui.shell.app.words(it.replace("%20", " ")) }
        return when {
            family == "dolphin" && file == NativeSettings.DOLPHIN_GFX -> "Graphics: $name"
            else -> name
        }
    }

    private companion object {
        const val MAX_TEXT = 1024 * 1024

        /** Where Fuse records the keys it changed in emulator settings files, by file. */
        const val KIND = "emulator.settings"

        /** The same, for the keys Play Together changed, put back on their own. */
        const val TOGETHER = "emulator.together"

        /** The emulator a system's games are changed in when they don't start in one Fuse knows. */
        val PLATFORM_FAMILY = mapOf("ps2" to "pcsx2", "psx" to "duckstation", "psp" to "ppsspp", "ngc" to "dolphin", "wii" to "dolphin", "ps3" to "rpcs3", "switch" to "eden", "3ds" to "azahar", "new-nintendo-3ds" to "azahar", "wiiu" to "cemu", "ps4" to "shadps4", "xbox360" to "xenia", "psvita" to "vita3k", "dc" to "flycast", "model3" to "supermodel")

        val FAMILIES = NativeSettings.changeable

        /** [Target.words] for a file whose text is written as it is, never quoted (sm64coopdx's). */
        const val BARE = "\u0000bare"

                /** Those whose TOML reads a decimal only with its point. */
        val TOML_POINT = setOf("melonds", "xenia")

        /** A Vita game's title id: four letters, five digits (PCSE00082). */
        val VITA_ID = Regex("^[A-Z]{4}\\d{5}$")

        const val SUPPORTED = "Fuse changes settings in PCSX2, DuckStation, PPSSPP, Dolphin, RPCS3, RetroArch, Eden and Citron, Azahar and its family (AzaharPlus, Lime3DS, Mandarine), Cemu, shadPS4, Xenia, Vita3K, Flycast, MAME, Supermodel and melonDS."

        /** What making a game's own RPCS3 file changes besides (rpcs3/Emu/System.cpp: "Found custom config. Ignoring database config"). */
        const val RPCS3_GAME_FILE = "A game's own RPCS3 settings take the place of the settings RPCS3's own database gives that game."

        fun family(e: InstalledEmulator): String = NativeSettings.familyOf(e.id.value)
    }
}
