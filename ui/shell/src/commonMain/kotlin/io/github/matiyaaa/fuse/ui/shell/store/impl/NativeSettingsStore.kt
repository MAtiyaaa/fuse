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
 * files (PCSX2, DuckStation, PPSSPP, Dolphin, and RPCS3 for Every Setting): a game's file holds only
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
        /** The installed version, for which of every setting it has. */
        val version: String? = null,
    ) {
        val forGame: Boolean get() = gamePath != null

        /** A file of this emulator's, to edit in place. */
        fun text(content: String?): SettingsText = if (yaml) YamlText(content ?: "") else IniText(content ?: "")

        /** The file the every-setting [setting] is changed in by this view. */
        fun pathOf(setting: TuneSetting): String = gamePath ?: setting.file?.let { globals[it] } ?: globalPath

        /** The file [setting] is changed in by this view. */
        fun pathOf(setting: io.github.matiyaaa.fuse.launch.settings.NativeSetting): String =
            gamePath ?: setting.file?.let { globals[it] } ?: globalPath

        /** Every file this view changes. */
        val paths: List<String> get() = gamePath?.let(::listOf) ?: (listOf(globalPath) + globals.values).distinct()
    }

    private fun no(title: String, reason: String) = null to EmulatorSettingsView.Unavailable(title, reason)

    private suspend fun target(id: GameId?, emulator: EmulatorId?): Pair<Target?, EmulatorSettingsView.Unavailable?> {
        val files = ctx.services.emulatorFiles ?: return no("Not on this device", "Emulator settings can be changed from Fuse on a computer. On this device, change them in the emulator.")
        if (ctx.installed.value.isEmpty()) detectNow()
        val game = id?.let { data.games.get(it) ?: return no("Game not found", "This game is no longer in the library.") }
        val installed = if (game != null) {
            val starts = startsIn(game)
            when {
                starts != null && family(starts) in FAMILIES -> starts
                starts != null -> return no("Fuse can't change ${starts.name}'s settings", "This game starts in ${starts.name}, which keeps its own settings; change them there. $SUPPORTED")
                else -> PLATFORM_FAMILY[game.platformId.value]?.let { f -> ctx.installed.value.firstOrNull { family(it) == f } }
                    ?: return no("No emulator to set up", "$SUPPORTED None of them plays this game here.")
            }
        } else {
            ctx.installed.value.firstOrNull { it.id == emulator } ?: return no("Not installed", "This emulator isn't installed on this device.")
        }
        val family = family(installed)
        val name = installed.name
        val version = installed.version
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
            else -> no("Fuse can't change ${installed.name}'s settings", "$SUPPORTED Change ${installed.name}'s in ${installed.name}.")
        }
    }

    private suspend fun read(t: Target, path: String?): SettingsText? = path?.let { fs.readText(it, MAX_TEXT) }?.let(t::text)

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
            canRestore = t.paths.any { data.owned.get(KIND, it).isNotEmpty() },
            profiles = if (t.gamePath == null) emptyList() else profiles(t.family).map { p ->
                SettingProfileView(p.id, p.name, p.about, p.own, SettingProfiles.matches(p, current, settings))
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
        val file = t.text(before)
        var records = data.owned.get(KIND, path)
        for ((key, value) in p.values) {
            val s = settings.firstOrNull { it.id == key && it.perGame } ?: continue
            records = SettingsEdit.change(file, records, s.sectionIn(game = true), s.key, value)
        }
        if (!t.files.write(path, file.toString())) return false
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
        val file = t.text(before)
        val records = SettingsEdit.change(file, data.owned.get(KIND, path), s.sectionIn(t.forGame), s.key, value)
        if (!t.files.write(path, file.toString())) return false
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
            val records = data.owned.get(KIND, path)
            if (records.isEmpty()) continue
            val text = fs.readText(path, MAX_TEXT)
            val file = t.text(text)
            SettingsEdit.restore(file, records)
            val made = data.owned.get(KIND, "$path#made").isNotEmpty()
            val done = when {
                text == null -> true
                made && !file.hasEntries() -> t.files.remove(path)
                else -> t.files.write(path, file.toString())
            }
            if (!done) return false
            data.owned.set(KIND, path, emptySet())
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
        if (t == null) return EverySettingView.Unavailable(unavailable!!.title, unavailable.reason)
        val all = Tune.forVersion(t.family, t.version).filter { !t.forGame || it.perGame }
        if (all.isEmpty()) return EverySettingView.Unavailable("Fuse Tune hasn't read ${t.emulator}", "${t.emulator}'s settings aren't in Fuse Tune yet. $SUPPORTED")
        val files = (listOf<String?>(null) + all.mapNotNull { it.file }.distinct()).associateWith { f ->
            read(t, f?.let { t.globals[it] } ?: t.globalPath)
        }
        val game = read(t, t.gamePath)
        val sections = all.groupBy { it.file to it.section }.map { (where, settings) ->
            val (file, section) = where
            TuneSection(
                id = "${file.orEmpty()}|$section",
                title = sectionTitle(t.family, file, settings.first().path),
                values = settings.map { valueOf(it, if (t.forGame) game else null, files[it.file]) },
            )
        }
        return EverySettingView.Ready(
            emulator = t.emulator,
            game = t.gameTitle,
            release = Tune.releaseFor(t.family, t.version),
            sections = sections,
            canRestore = t.paths.any { data.owned.get(KIND, it).isNotEmpty() },
            gameFileNote = if (t.forGame && t.family == "rpcs3" && fs.readText(t.gamePath!!, MAX_TEXT) == null) RPCS3_GAME_FILE else null,
        )
    }

    /** Gives the every-setting [setting] the [value] (see [FuseStore.LibraryOps.setEveryEmulatorSetting]). */
    suspend fun setEvery(id: GameId?, emulator: EmulatorId?, setting: String, value: String?): Boolean {
        val t = target(id, emulator).first ?: return false
        val s = Tune.forVersion(t.family, t.version).firstOrNull { it.id == setting } ?: return false
        if (t.forGame && !s.perGame) return false
        if (value != null && !s.accepts(value, ctx.host)) return false
        val written = value?.let { written(s, it) }
        val path = t.pathOf(s)
        val before = fs.readText(path, MAX_TEXT)
        val file = t.text(before)
        val records = SettingsEdit.change(file, data.owned.get(KIND, path), s.sectionIn(t.forGame), s.key, written)
        if (!t.files.write(path, file.toString())) return false
        data.owned.set(KIND, path, records)
        if (before == null) data.owned.set(KIND, "$path#made", setOf("file"))
        return true
    }

    private fun valueOf(s: TuneSetting, game: SettingsText?, global: SettingsText?): TuneValue {
        val every = global?.values(s.section, s.key)?.lastOrNull()
        val own = if (s.perGame) game?.values(s.sectionIn(game = true), s.key)?.lastOrNull() else null
        val everyGame = every ?: s.default
        return when {
            own != null -> TuneValue(s, own, SettingSource.GAME, everyGame)
            every != null -> TuneValue(s, every, SettingSource.EVERY_GAME, everyGame)
            else -> TuneValue(s, s.default, SettingSource.DEFAULT, everyGame)
        }
    }

    /** [value] as the emulator writes it: Dolphin's and PPSSPP's switches as `True` and `False`. */
    private fun written(s: TuneSetting, value: String): String = when {
        s.kind != TuneKind.SWITCH -> value.trim().takeIf { s.kind != TuneKind.TEXT } ?: value
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
        val name = parts.joinToString(" / ") { io.github.matiyaaa.fuse.ui.shell.app.words(it) }
        return when {
            family == "dolphin" && file == NativeSettings.DOLPHIN_GFX -> "Graphics: $name"
            else -> name
        }
    }

    private companion object {
        const val MAX_TEXT = 1024 * 1024

        /** Where Fuse records the keys it changed in emulator settings files, by file. */
        const val KIND = "emulator.settings"

        /** The emulator a system's games are changed in when they don't start in one Fuse knows. */
        val PLATFORM_FAMILY = mapOf("ps2" to "pcsx2", "psx" to "duckstation", "psp" to "ppsspp", "ngc" to "dolphin", "wii" to "dolphin", "ps3" to "rpcs3")

        val FAMILIES = NativeSettings.changeable

        const val SUPPORTED = "Fuse changes settings in PCSX2, DuckStation, PPSSPP, Dolphin and RPCS3."

        /** What making a game's own RPCS3 file changes besides (rpcs3/Emu/System.cpp: "Found custom config. Ignoring database config"). */
        const val RPCS3_GAME_FILE = "A game's own RPCS3 settings take the place of the settings RPCS3's own database gives that game."

        fun family(e: InstalledEmulator): String = e.id.value.substringAfter('.')
    }
}
