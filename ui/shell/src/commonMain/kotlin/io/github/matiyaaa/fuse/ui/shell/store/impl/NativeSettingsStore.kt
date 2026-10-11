package io.github.matiyaaa.fuse.ui.shell.store.impl

import io.github.matiyaaa.fuse.launch.patches.DuckStationPatchRules
import io.github.matiyaaa.fuse.launch.patches.IniText
import io.github.matiyaaa.fuse.launch.patches.Pcsx2PatchRules
import io.github.matiyaaa.fuse.data.settings.AppSettings
import io.github.matiyaaa.fuse.data.settings.OwnSettingProfile
import io.github.matiyaaa.fuse.launch.settings.NativeSettings
import io.github.matiyaaa.fuse.launch.settings.SettingProfile
import io.github.matiyaaa.fuse.launch.settings.SettingProfiles
import io.github.matiyaaa.fuse.launch.settings.SettingsEdit
import io.github.matiyaaa.fuse.library.disc.DiscIdentity
import io.github.matiyaaa.fuse.model.EmulatorId
import io.github.matiyaaa.fuse.model.Game
import io.github.matiyaaa.fuse.model.GameId
import io.github.matiyaaa.fuse.model.InstalledEmulator
import io.github.matiyaaa.fuse.ui.shell.store.EmulatorFiles
import io.github.matiyaaa.fuse.ui.shell.store.EmulatorSettingsView
import io.github.matiyaaa.fuse.ui.shell.store.SettingProfileView

/**
 * An emulator's own settings, for every game or for one game, read and changed in the emulator's own
 * files (PCSX2, DuckStation, PPSSPP): a game's file holds only what differs for it, the rest comes
 * from the emulator's settings for every game. Every key Fuse changes is recorded with what it held
 * before, so Fuse can put back exactly that (see [SettingsEdit]).
 */
internal class NativeSettingsStore(
    private val ctx: StoreContext,
    private val detectNow: suspend () -> Unit,
    private val discIdentity: suspend (GameId) -> DiscIdentity?,
    private val pspGameId: suspend (Game) -> String?,
    /** The emulator [Game] starts in here, as a launch would choose it. */
    private val startsIn: suspend (Game) -> InstalledEmulator?,
    /** Changes the stored settings, for the person's own profiles. */
    private val changeSettings: suspend ((AppSettings) -> AppSettings) -> Unit = {},
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
    ) {
        /** The file this view changes. */
        val path: String get() = gamePath ?: globalPath
    }

    private fun no(title: String, reason: String) = null to EmulatorSettingsView.Unavailable(title, reason)

    private suspend fun target(id: GameId?, emulator: EmulatorId?): Pair<Target?, EmulatorSettingsView.Unavailable?> {
        val files = ctx.services.emulatorFiles ?: return no("Not on this device", "Emulator settings can be changed from Fuse on a computer. On this device, change them in the emulator.")
        if (ctx.installed.value.isEmpty()) detectNow()
        val game = id?.let { data.games.get(it) ?: return no("Game not found", "This game is no longer in the library.") }
        val installed = if (game != null) {
            val starts = startsIn(game)
            when {
                starts != null && family(starts) in NativeSettings.families -> starts
                starts != null -> return no("Fuse can't change ${starts.name}'s settings", "This game starts in ${starts.name}, which keeps its own settings; change them there. Fuse changes settings in PCSX2, DuckStation and PPSSPP.")
                else -> PLATFORM_FAMILY[game.platformId.value]?.let { f -> ctx.installed.value.firstOrNull { family(it) == f } }
                    ?: return no("No emulator to set up", "Fuse changes settings in PCSX2, DuckStation and PPSSPP, and none of them plays this game here.")
            }
        } else {
            ctx.installed.value.firstOrNull { it.id == emulator } ?: return no("Not installed", "This emulator isn't installed on this device.")
        }
        val family = family(installed)
        val name = installed.name
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
                Target(family, name, files, home.settingsFile, gamePath, game?.titles?.original) to null
            }
            "duckstation" -> {
                val home = files.duckstation(installed) ?: return notSetUp()
                val gamePath = if (game != null) {
                    val disc = discIdentity(game.id)?.takeIf { !it.ps2 } ?: return unreadable("serial")
                    "${home.gameSettings}/${DuckStationPatchRules.gameSettingsName(disc.serial)}"
                } else null
                Target(family, name, files, home.settingsFile, gamePath, game?.titles?.original) to null
            }
            "ppsspp" -> {
                val home = files.ppsspp(installed) ?: return notSetUp()
                val gamePath = if (game != null) home.gameSettingsFile(pspGameId(game) ?: return unreadable("game id")) else null
                Target(family, name, files, home.settingsFile, gamePath, game?.titles?.original) to null
            }
            else -> no("Fuse can't change ${installed.name}'s settings", "Fuse changes settings in PCSX2, DuckStation and PPSSPP. Change ${installed.name}'s in ${installed.name}.")
        }
    }

    private suspend fun read(path: String?): IniText? = path?.let { fs.readText(it, MAX_TEXT) }?.let { IniText(it) }

    suspend fun view(id: GameId?, emulator: EmulatorId?): EmulatorSettingsView {
        val (t, unavailable) = target(id, emulator)
        if (t == null) return unavailable!!
        val global = read(t.globalPath)
        val game = read(t.gamePath)
        val settings = NativeSettings.forEmulator(t.family, ctx.host).filter { t.gamePath == null || it.perGame }
        val values = settings.map { SettingsEdit.valueFor(it, if (t.gamePath != null) game else null, global) }
        val current = values.associate { it.setting.id to it.value }
        return EmulatorSettingsView.Ready(
            emulator = t.emulator,
            game = t.gameTitle,
            values = values,
            canRestore = data.owned.get(KIND, t.path).isNotEmpty(),
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
        if (t.gamePath == null) return false
        val p = profiles(t.family).firstOrNull { it.id == profile } ?: return false
        val settings = NativeSettings.forEmulator(t.family, ctx.host)
        val before = fs.readText(t.path, MAX_TEXT)
        val file = IniText(before ?: "")
        var records = data.owned.get(KIND, t.path)
        for ((key, value) in p.values) {
            val s = settings.firstOrNull { it.id == key && it.perGame } ?: continue
            records = SettingsEdit.change(file, records, s.section, s.key, value)
        }
        if (!t.files.write(t.path, file.toString())) return false
        data.owned.set(KIND, t.path, records)
        if (before == null) data.owned.set(KIND, "${t.path}#made", setOf("file"))
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
        if (t.gamePath != null && !s.perGame) return false
        if (value != null && s.choices.none { it.value == value }) return false
        val before = fs.readText(t.path, MAX_TEXT)
        val file = IniText(before ?: "")
        val records = SettingsEdit.change(file, data.owned.get(KIND, t.path), s.section, s.key, value)
        if (!t.files.write(t.path, file.toString())) return false
        data.owned.set(KIND, t.path, records)
        // A game's own file Fuse made: taken away again once nothing Fuse set is left in it.
        if (before == null) data.owned.set(KIND, "${t.path}#made", setOf("file"))
        return true
    }

    /** Puts back what Fuse changed in this file; a key changed in the emulator since stays as it is. */
    suspend fun restore(id: GameId?, emulator: EmulatorId?): Boolean {
        val t = target(id, emulator).first ?: return false
        val records = data.owned.get(KIND, t.path)
        if (records.isEmpty()) return false
        val text = fs.readText(t.path, MAX_TEXT)
        val file = IniText(text ?: "")
        SettingsEdit.restore(file, records)
        val made = data.owned.get(KIND, "${t.path}#made").isNotEmpty()
        val done = when {
            text == null -> true
            made && !file.hasEntries() -> t.files.remove(t.path)
            else -> t.files.write(t.path, file.toString())
        }
        if (!done) return false
        data.owned.set(KIND, t.path, emptySet())
        data.owned.set(KIND, "${t.path}#made", emptySet())
        return true
    }

    private companion object {
        const val MAX_TEXT = 1024 * 1024

        /** Where Fuse records the keys it changed in emulator settings files, by file. */
        const val KIND = "emulator.settings"

        /** The emulator a system's games are changed in when they don't start in one Fuse knows. */
        val PLATFORM_FAMILY = mapOf("ps2" to "pcsx2", "psx" to "duckstation", "psp" to "ppsspp")

        fun family(e: InstalledEmulator): String = e.id.value.substringAfter('.')
    }
}
