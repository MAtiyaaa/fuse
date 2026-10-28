package io.github.matiyaaa.fuse.ui.shell.store.impl

import io.github.matiyaaa.fuse.launch.patches.ChtCode
import io.github.matiyaaa.fuse.launch.patches.Cht
import io.github.matiyaaa.fuse.launch.patches.CwCheat
import io.github.matiyaaa.fuse.launch.patches.CwCheats
import io.github.matiyaaa.fuse.launch.patches.DuckStationPatchRules
import io.github.matiyaaa.fuse.launch.patches.IniText
import io.github.matiyaaa.fuse.launch.patches.PatchState
import io.github.matiyaaa.fuse.launch.patches.PatchStatus
import io.github.matiyaaa.fuse.launch.patches.Pcsx2PatchRules
import io.github.matiyaaa.fuse.launch.patches.Pnach
import io.github.matiyaaa.fuse.launch.patches.PnachPatch
import io.github.matiyaaa.fuse.launch.patches.PpssppHome
import io.github.matiyaaa.fuse.launch.patches.RaCheat
import io.github.matiyaaa.fuse.launch.patches.RetroArchCfg
import io.github.matiyaaa.fuse.launch.patches.RetroArchCheats
import io.github.matiyaaa.fuse.library.disc.DiscIdentity
import io.github.matiyaaa.fuse.model.Game
import io.github.matiyaaa.fuse.model.GameId
import io.github.matiyaaa.fuse.model.InstalledEmulator
import io.github.matiyaaa.fuse.ui.shell.store.EmulatorFiles
import io.github.matiyaaa.fuse.ui.shell.store.GamePatchList
import kotlinx.coroutines.CancellationException

/**
 * A game's patches and cheats in its emulator's own files: RetroArch's for a game that starts there,
 * else PCSX2 for PS2 games, DuckStation for PlayStation games, PPSSPP for PSP games, RPCS3 for PS3 games and shadPS4 for PS4 games. Each is read the way the emulator reads it and changed the
 * way the emulator changes it, and Fuse only ever turns off what it turned on itself (recorded per
 * file in [io.github.matiyaaa.fuse.data.repo.OwnedChangesRepository]); anything set in the emulator
 * stays exactly as it is.
 */
internal class NativePatches(
    private val ctx: StoreContext,
    private val detectNow: suspend () -> Unit,
    private val discIdentity: suspend (GameId) -> DiscIdentity?,
    private val pspGameId: suspend (Game) -> String?,
    /** The RetroArch run [Game] would start with here (its install, core and content); null when it wouldn't start in RetroArch. */
    private val retroArchRun: suspend (Game) -> RetroArchRun? = { null },
) {
    /** What RetroArch would be started with for a game: the install, the core, and the content it is given. */
    class RetroArchRun(val installed: InstalledEmulator, val core: String, val content: String)

    private val data = ctx.data
    private val fs get() = ctx.services.fs

    suspend fun list(id: GameId): GamePatchList {
        val (context, unavailable) = context(id)
        return context?.view ?: unavailable!!
    }

    suspend fun set(id: GameId, name: String, on: Boolean, cheat: Boolean): Boolean {
        val context = context(id).first ?: return false
        context.ppsspp?.let { return setPpsspp(context, it, name, on) }
        context.rpcs3?.let { return setRpcs3(context, it, name, on) }
        context.shadPs4?.let { return setShadPs4(context, it, name, on) }
        context.retroArch?.let { return setRetroArch(context, it, name, on) }
        val statuses = if (cheat) context.cheats else context.statuses
        val status = statuses.firstOrNull { it.patch.name == name } ?: return false
        val section = if (cheat) context.cheatSection else context.patchSection
        val ownedBefore = if (cheat) context.ownedCheats else context.owned
        val owned = Pcsx2PatchRules.change(status, on, context.game, ownedBefore, section) ?: return false
        var ownsSwitch = context.ownsCheatsSwitch
        if (cheat) {
            val (switchSection, switchKey) = context.cheatsSwitch
            // Turning a cheat on turns on the emulator's cheats for this game when they are off, and
            // turning off the last one Fuse turned on turns them off again, only if Fuse turned them on.
            if (on && !context.cheatsOn) {
                context.game.set(switchSection, switchKey, "true")
                ownsSwitch = true
            }
            if (!on && owned.isEmpty() && ownsSwitch) {
                context.game.removeKey(switchSection, switchKey)
                ownsSwitch = false
            }
        }
        if (!context.files.write(context.settingsPath, context.game.toString())) return false
        if (cheat) {
            data.owned.set(context.kind, "${context.settingsPath}#cheats", owned)
            data.owned.set(context.kind, "${context.settingsPath}#switch", if (ownsSwitch) setOf(context.cheatsSwitch.second) else emptySet())
        } else {
            data.owned.set(context.kind, context.settingsPath, owned)
        }
        return true
    }

    /** Everything needed to read or change one game's patches and cheats. */
    private class Context(
        val files: EmulatorFiles,
        /** Where Fuse records what it turned on, by file. */
        val kind: String,
        /** The file Fuse changes: the game's settings file (PCSX2, DuckStation) or its cheat file (PPSSPP). */
        val settingsPath: String,
        val game: IniText,
        val owned: Set<String>,
        val statuses: List<PatchStatus>,
        val view: GamePatchList.Ready,
        val ownedCheats: Set<String> = emptySet(),
        val cheats: List<PatchStatus> = emptyList(),
        /** Fuse turned on the emulator's cheats for this game, so it turns them off with its last cheat. */
        val ownsCheatsSwitch: Boolean = false,
        val cheatsOn: Boolean = false,
        val patchSection: String = Pcsx2PatchRules.SECTION,
        val cheatSection: String = Pcsx2PatchRules.CHEATS,
        /** The section and key of the emulator's cheats switch in [game]. */
        val cheatsSwitch: Pair<String, String> = "EmuCore" to Pcsx2PatchRules.CHEATS_SWITCH,
        val ppsspp: PpssppContext? = null,
        val retroArch: RetroArchContext? = null,
        val rpcs3: Rpcs3Context? = null,
        val shadPs4: Shadps4Context? = null,
    )

    /** PPSSPP's cheats are switched in the cheat file itself; its cheats switch is in the game's own settings file, when it has one. */
    private class PpssppContext(
        val text: String,
        val cheats: List<CwCheat>,
        val gameSettingsPath: String,
        /** The game's own settings file, when PPSSPP has made one for it. */
        val gameSettings: IniText?,
    )

    /**
     * RetroArch's cheats are switched in the game's own cheat file (made from its downloaded cheats
     * when there isn't one yet); its Apply Cheats After Game Load in the game's override file.
     */
    private class RetroArchContext(
        val text: String,
        val cheats: List<RaCheat>,
        /** The game's own cheat file isn't there yet: its cheats are from RetroArch's downloaded ones. */
        val fromDatabase: Boolean,
        val overridePath: String,
        val overrideText: String?,
        /** RetroArch reads game overrides (`auto_overrides_enable`, on unless turned off). */
        val overrides: Boolean,
    )

    private fun no(title: String, reason: String) = null to GamePatchList.Unavailable(title, reason)

    private suspend fun context(id: GameId): Pair<Context?, GamePatchList.Unavailable?> {
        val game = data.games.get(id) ?: return no("Game not found", "This game is no longer in the library.")
        val files = ctx.services.emulatorFiles ?: return no("Not on this device", "Patches and cheats can be changed from Fuse on a computer. On this device, change them in the emulator.")
        if (ctx.installed.value.isEmpty()) detectNow()
        // The emulator the game starts in decides: RetroArch's cheats when it starts there.
        retroArchRun(game)?.let { return retroArch(game, files, it) }
        return when (game.platformId.value) {
            "ps2" -> pcsx2(id, files)
            "psx" -> duckstation(id, files)
            "psp" -> ppsspp(game, files)
            "ps3" -> rpcs3(game, files)
            "ps4" -> shadPs4(game, files)
            else -> no("No cheats here", "Fuse changes cheats for games that start in RetroArch, and patches and cheats in PCSX2, DuckStation, PPSSPP, RPCS3 and shadPS4. This game's emulator keeps its own; change them there.")
        }
    }

    private fun installed(name: String): InstalledEmulator? = ctx.installed.value.firstOrNull { it.id.value.substringAfter('.') == name }

    private suspend fun list(folder: String): List<io.github.matiyaaa.fuse.library.FsEntry> =
        try { fs.list(folder) } catch (e: CancellationException) { throw e } catch (e: Exception) { emptyList() }

    /** What Fuse recorded turning on in [file], less anything no longer on there (forgotten for good, so turning it on again in the emulator makes it the user's). */
    private suspend fun owned(kind: String, file: String, stillOn: Set<String>): Set<String> {
        val recorded = data.owned.get(kind, file)
        val owned = recorded.filter { it in stillOn }.toSet()
        if (owned != recorded) data.owned.set(kind, file, owned)
        return owned
    }

    // PCSX2 ---------------------------------------------------------------------------------------

    private suspend fun pcsx2(id: GameId, files: EmulatorFiles): Pair<Context?, GamePatchList.Unavailable?> {
        val pcsx2 = installed("pcsx2") ?: return no("PCSX2 isn't installed", "Install PCSX2, start it once so it sets up its folders, and its patches show here.")
        val home = files.pcsx2(pcsx2) ?: return no("PCSX2 isn't set up yet", "Start PCSX2 once and finish its first-run setup, then come back.")
        val disc = discIdentity(id) ?: return no(
            "Fuse can't read this disc",
            "Patches are filed under the game's serial and CRC, which Fuse reads from ISO, BIN, CHD and CSO images. This one doesn't say them; change its patches in PCSX2.",
        )
        val crc = disc.crc ?: return no("Not a PS2 disc", "This image doesn't start a PS2 program, so PCSX2 has no patches for it.")
        val rules = Pcsx2PatchRules
        // Patch files on disk come first, as in PCSX2; a name already listed isn't listed again.
        val patches = LinkedHashMap<String, PnachPatch>()
        var unlabelled = false
        for (f in list(home.patches).filter { !it.isDirectory && rules.matchesPnach(it.name, disc.serial, crc) }.sortedBy { it.name }) {
            val text = fs.readText(f.path, MAX_TEXT) ?: continue
            if (Pnach.hasUnlabelled(text)) unlabelled = true
            Pnach.parse(text).forEach { patches.putIfAbsent(it.name, it) }
        }
        var bundledRead = home.patchesZip != null
        if (!unlabelled && home.patchesZip != null) {
            for (entry in rules.zipEntries(disc.serial, crc)) {
                val text = files.zipText(home.patchesZip!!, entry) ?: continue
                Pnach.parse(text).forEach { patches.putIfAbsent(it.name, it) }
                break
            }
        }
        // An unlabelled patch file means PCSX2 leaves its bundled patches out, so nothing is missing.
        if (unlabelled) bundledRead = true
        val settingsPath = "${home.gameSettings}/${rules.gameSettingsName(disc.serial, crc)}"
        val game = IniText(fs.readText(settingsPath, MAX_TEXT) ?: "")
        val global = fs.readText(home.settingsFile, MAX_TEXT)?.let { IniText(it) }
        val owned = owned(PCSX2_KIND, settingsPath, game.values(rules.SECTION, rules.ENABLE).toSet())
        val statuses = rules.states(patches.values.toList(), game, global, owned)
        // Cheats: the same kind of file in PCSX2's cheats folder, listed under [Cheats].
        val cheatList = LinkedHashMap<String, PnachPatch>()
        if (home.cheats.isNotEmpty()) {
            for (f in list(home.cheats).filter { !it.isDirectory && rules.matchesPnach(it.name, disc.serial, crc) }.sortedBy { it.name }) {
                val text = fs.readText(f.path, MAX_TEXT) ?: continue
                Pnach.parse(text).forEach { cheatList.putIfAbsent(it.name, it) }
            }
        }
        val ownedCheats = owned(PCSX2_KIND, "$settingsPath#cheats", game.values(rules.CHEATS, rules.ENABLE).toSet())
        val cheats = rules.states(cheatList.values.toList(), game, global, ownedCheats, rules.CHEATS)
        // The switch Fuse turned on stays Fuse's only while the game's file still says so.
        val ownsSwitch = data.owned.get(PCSX2_KIND, "$settingsPath#switch").contains(rules.CHEATS_SWITCH) && game.bool("EmuCore", rules.CHEATS_SWITCH) == true
        val cheatsOn = rules.cheatsOn(game, global)
        val crcText = disc.crcText ?: ""
        val view = GamePatchList.Ready(
            serial = disc.serial,
            crc = crcText,
            patches = statuses,
            bundledRead = bundledRead,
            cheats = cheats,
            cheatsOn = cheatsOn,
            emulator = "PCSX2",
            whereFrom = "PCSX2 has none filed under ${disc.serial} (CRC $crcText)" +
                (if (bundledRead) "." else ", in its patches folder. Its bundled patches couldn't be read on this system; check the game's Patches tab in PCSX2.") +
                " Cheats come from .pnach files named ${disc.serial}_$crcText.pnach in PCSX2's cheats folder.",
        )
        return Context(files, PCSX2_KIND, settingsPath, game, owned, statuses, view, ownedCheats, cheats, ownsSwitch, cheatsOn) to null
    }

    // DuckStation ---------------------------------------------------------------------------------

    private suspend fun duckstation(id: GameId, files: EmulatorFiles): Pair<Context?, GamePatchList.Unavailable?> {
        val ds = installed("duckstation") ?: return no("DuckStation isn't installed", "Install DuckStation, start it once so it sets up its folders, and its patches and cheats show here.")
        val home = files.duckstation(ds) ?: return no("DuckStation isn't set up yet", "Start DuckStation once and finish its first-run setup, then come back.")
        val disc = discIdentity(id) ?: return no(
            "Fuse can't read this disc",
            "DuckStation files patches and cheats under the game's serial, which Fuse reads from ISO, BIN, CUE and CHD images. PBP files aren't read; change them in DuckStation's Game Properties.",
        )
        if (disc.ps2) return no("Not a PlayStation disc", "This image starts a PS2 program, so DuckStation has nothing for it.")
        val rules = DuckStationPatchRules
        val serial = disc.serial
        val settingsPath = "${home.gameSettings}/${rules.gameSettingsName(serial)}"
        val game = IniText(fs.readText(settingsPath, MAX_TEXT) ?: "")
        val global = fs.readText(home.settingsFile, MAX_TEXT)?.let { IniText(it) }

        // The bundled file first, then files on disk, a later code replacing one of the same name, as
        // DuckStation's GetCodeInfoList does.
        suspend fun codes(folder: String, zip: String?, useZip: Boolean): List<ChtCode> {
            val out = ArrayList<ChtCode>()
            fun merge(more: List<ChtCode>) = more.forEach { c ->
                val i = out.indexOfFirst { it.name == c.name }
                if (i >= 0) out[i] = c else out += c
            }
            if (useZip && zip != null) files.zipText(zip, rules.zipEntry(serial))?.let { merge(Cht.parse(it)) }
            for (f in list(folder).filter { !it.isDirectory && rules.matchesCht(it.name, serial) }.sortedBy { it.name }) {
                fs.readText(f.path, MAX_TEXT)?.let { merge(Cht.parse(it)) }
            }
            return out
        }

        val patchCodes = codes(home.patches, home.patchesZip, useZip = true)
        val cheatCodes = codes(home.cheats, home.cheatsZip, useZip = rules.databaseCheats(game))
        val owned = owned(DUCKSTATION_KIND, settingsPath, game.values(rules.PATCHES, rules.ENABLE).toSet())
        val statuses = rules.states(patchCodes, game, owned, rules.PATCHES, rules.widescreenFor(game, global))
        val ownedCheats = owned(DUCKSTATION_KIND, "$settingsPath#cheats", game.values(rules.CHEATS, rules.ENABLE).toSet())
        val cheats = rules.states(cheatCodes, game, ownedCheats, rules.CHEATS)
        val ownsSwitch = data.owned.get(DUCKSTATION_KIND, "$settingsPath#switch").contains(rules.CHEATS_SWITCH) && rules.cheatsOn(game)
        val cheatsOn = rules.cheatsOn(game)
        val hardcore = (game.bool("Cheevos", "Enabled") ?: global?.bool("Cheevos", "Enabled") ?: false) &&
            (game.bool("Cheevos", "ChallengeMode") ?: global?.bool("Cheevos", "ChallengeMode") ?: false)
        val manual = (patchCodes + cheatCodes).count { it.manual }
        val warning = listOfNotNull(
            "DuckStation's Disable All Enhancements is on, so it applies none of these.".takeIf { rules.enhancementsOff(game, global) },
            "RetroAchievements hardcore mode is on in DuckStation, so it applies no cheats, none of your own patch files, and only the bundled patches allowed in hardcore.".takeIf { hardcore },
            (if (manual == 1) "1 code applied by hand from DuckStation's menu while playing isn't listed." else "$manual codes applied by hand from DuckStation's menu while playing aren't listed.").takeIf { manual > 0 },
        ).joinToString(" ").ifEmpty { null }
        val bundled = home.patchesZip != null && home.cheatsZip != null
        val view = GamePatchList.Ready(
            serial = serial,
            crc = "",
            patches = statuses,
            bundledRead = bundled,
            cheats = cheats,
            cheatsOn = cheatsOn,
            emulator = "DuckStation",
            whereFrom = "DuckStation has none filed under $serial" +
                (if (bundled) "." else " in its own folders. Its bundled patches and cheats couldn't be read on this system; check the game's Game Properties in DuckStation.") +
                " Your own come from $serial.cht in its cheats and patches folders.",
            warning = warning,
            cheatsNote = "Turning a cheat on turns on DuckStation's cheats for this game, and off again with the last one.",
        )
        return Context(
            files, DUCKSTATION_KIND, settingsPath, game, owned, statuses, view, ownedCheats, cheats, ownsSwitch, cheatsOn,
            patchSection = rules.PATCHES, cheatSection = rules.CHEATS, cheatsSwitch = rules.CHEATS to rules.CHEATS_SWITCH,
        ) to null
    }

    // PPSSPP --------------------------------------------------------------------------------------

    private suspend fun ppsspp(game: Game, files: EmulatorFiles): Pair<Context?, GamePatchList.Unavailable?> {
        val ppsspp = installed("ppsspp") ?: return no("PPSSPP isn't installed", "Install PPSSPP, start it once so it makes its memory stick, and its cheats show here.")
        val home = files.ppsspp(ppsspp) ?: return no("PPSSPP isn't set up yet", "Start PPSSPP once so it makes its memory stick, then come back.")
        val gameId = pspGameId(game) ?: return no(
            "Fuse can't tell which game this is",
            "PPSSPP files cheats under the game's id (like ULUS10041), which Fuse reads from ISO, CSO and CHD images and EBOOT.PBP files, or from a game id in its name.",
        )
        val path = home.cheatFile(gameId)
        val text = fs.readText(path, MAX_TEXT) ?: ""
        val parsed = CwCheats.parse(text, gameId)
        val owned = owned(PPSSPP_KIND, path, parsed.filter { it.on }.map { it.name }.toSet())
        val cheats = parsed.distinctBy { it.name }.map { c ->
            PatchStatus(
                PnachPatch(c.name),
                when {
                    c.on && c.name in owned -> PatchState.ON_BY_FUSE
                    c.on -> PatchState.ON_IN_EMULATOR
                    else -> PatchState.OFF
                },
            )
        }
        val gameSettingsPath = home.gameSettingsFile(gameId)
        val gameSettings = fs.readText(gameSettingsPath, MAX_TEXT)?.let { IniText(it) }
        val global = fs.readText(home.settingsFile, MAX_TEXT)?.let { IniText(it) }
        fun setting(section: String, key: String) = gameSettings?.bool(section, key) ?: global?.bool(section, key)
        val cheatsOn = setting(PpssppHome.SECTION, PpssppHome.CHEATS_SWITCH) ?: false
        val ownsSwitch = gameSettings != null &&
            data.owned.get(PPSSPP_KIND, "$gameSettingsPath#switch").contains(PpssppHome.CHEATS_SWITCH) && cheatsOn
        // Hardcore mode is on by default in PPSSPP once achievements are.
        val hardcore = (setting("Achievements", "AchievementsEnable") ?: false) && (setting("Achievements", "AchievementsChallengeMode") ?: true)
        val warning = listOfNotNull(
            "PPSSPP's cheats are off. Turn on Enable cheats in its System settings (or in this game's own settings) for these to apply."
                .takeIf { !cheatsOn && gameSettings == null && cheats.isNotEmpty() },
            "RetroAchievements hardcore mode is on in PPSSPP, which keeps cheats off while you are signed in.".takeIf { hardcore },
        ).joinToString(" ").ifEmpty { null }
        val view = GamePatchList.Ready(
            serial = gameId,
            crc = "",
            patches = emptyList(),
            bundledRead = true,
            cheats = cheats,
            cheatsOn = cheatsOn,
            emulator = "PPSSPP",
            whereFrom = "PPSSPP keeps a game's cheats in PSP/Cheats/$gameId.ini on its memory stick. Import them there from cheat.db in its Cheats screen, or add your own.",
            warning = warning,
            cheatsNote = if (gameSettings != null) {
                "Turning a cheat on turns on PPSSPP's cheats in this game's own settings, and off again with the last one."
            } else {
                ""
            },
        )
        return Context(
            files, PPSSPP_KIND, path, IniText(""), emptySet(), emptyList(), view, owned, cheats, ownsSwitch, cheatsOn,
            ppsspp = PpssppContext(text, parsed, gameSettingsPath, gameSettings),
        ) to null
    }

    /**
     * A PPSSPP cheat on or off: its `_C` line rewritten, as PPSSPP's Cheats screen does. Turning one on
     * also turns on PPSSPP's cheats in the game's own settings file when it has one and they are off
     * there (PPSSPP's settings for every game are left alone); turning off the last one Fuse turned on
     * turns them off again, only if Fuse turned them on.
     */
    private suspend fun setPpsspp(context: Context, pp: PpssppContext, name: String, on: Boolean): Boolean {
        val status = context.cheats.firstOrNull { it.patch.name == name } ?: return false
        if (!(on && status.state == PatchState.OFF) && !(!on && status.state == PatchState.ON_BY_FUSE)) return false
        val cheat = pp.cheats.firstOrNull { it.name == name } ?: return false
        val text = CwCheats.set(pp.text, cheat, on) ?: return false
        if (!context.files.write(context.settingsPath, text)) return false
        val owned = if (on) context.ownedCheats + name else context.ownedCheats - name
        data.owned.set(PPSSPP_KIND, context.settingsPath, owned)
        val settings = pp.gameSettings ?: return true
        var ownsSwitch = context.ownsCheatsSwitch
        var changed = false
        if (on && !context.cheatsOn) {
            settings.set(PpssppHome.SECTION, PpssppHome.CHEATS_SWITCH, "True")
            ownsSwitch = true
            changed = true
        }
        if (!on && owned.isEmpty() && ownsSwitch) {
            settings.set(PpssppHome.SECTION, PpssppHome.CHEATS_SWITCH, "False")
            ownsSwitch = false
            changed = true
        }
        if (changed && !context.files.write(pp.gameSettingsPath, settings.toString())) return true
        data.owned.set(PPSSPP_KIND, "${pp.gameSettingsPath}#switch", if (ownsSwitch) setOf(PpssppHome.CHEATS_SWITCH) else emptySet())
        return true
    }

    // RPCS3 --------------------------------------------------------------------------------------

    /** RPCS3's patches for a game: every patch its patch files list for this game's serial and version, switched in patch_config.yml. */
    private class Rpcs3Context(
        val entries: Map<String, List<io.github.matiyaaa.fuse.launch.patches.Rpcs3PatchEntry>>,
        val configText: String?,
    )

    private suspend fun rpcs3(game: Game, files: EmulatorFiles): Pair<Context?, GamePatchList.Unavailable?> {
        val rpcs3 = installed("rpcs3") ?: return no("RPCS3 isn't installed", "Install RPCS3, start it once so it sets up its folders, and its patches show here.")
        val config = files.rpcs3Config(rpcs3) ?: return no("RPCS3 isn't set up yet", "Start RPCS3 once so it makes its settings folder, then come back.")
        val sfo = sfoNear(game.location.launchPath)
        val serial = (game.tags.serial?.uppercase()?.filter { it.isLetterOrDigit() }?.takeIf { PS3_ID.matches(it) }
            ?: sfo?.get("TITLE_ID")?.trim()?.uppercase()?.takeIf { PS3_ID.matches(it) })
            ?: return no("Fuse can't tell which game this is", "RPCS3 files patches under the game's serial (like BLUS30443), which Fuse reads from a game folder's PARAM.SFO or from the game's name.")
        // An update installed in RPCS3 is the version it runs.
        val updated = files.rpcs3Storage(rpcs3).firstNotNullOfOrNull { sfoAt("$it/game/$serial/PARAM.SFO") }
        val version = (updated ?: sfo)?.get("APP_VER")?.trim()?.takeIf { APP_VER.matches(it) }
        val entries = LinkedHashMap<String, MutableList<io.github.matiyaaa.fuse.launch.patches.Rpcs3PatchEntry>>()
        var read = false
        for (name in io.github.matiyaaa.fuse.launch.patches.Rpcs3Patches.files(serial)) {
            val text = fs.readText("$config/patches/$name", MAX_YAML) ?: continue
            read = true
            io.github.matiyaaa.fuse.launch.patches.Rpcs3Patches.forGame(text, serial, version).forEach { e ->
                entries.getOrPut(e.description) { ArrayList() }.add(e)
            }
        }
        val configPath = io.github.matiyaaa.fuse.launch.patches.Rpcs3Patches.configPath(config, ctx.host == io.github.matiyaaa.fuse.model.Host.WINDOWS)
        val configText = fs.readText(configPath, MAX_YAML)
        val patchConfig = io.github.matiyaaa.fuse.launch.patches.Rpcs3PatchConfig(configText)
        val onNow = entries.filterValues { list -> list.any(patchConfig::enabled) }.keys
        val owned = owned(RPCS3_KIND, configPath, onNow)
        val statuses = entries.map { (description, list) ->
            val first = list.first()
            PatchStatus(
                PnachPatch(description, description = first.notes, author = first.author),
                when {
                    description in onNow && description in owned -> PatchState.ON_BY_FUSE
                    description in onNow -> PatchState.ON_IN_EMULATOR
                    else -> PatchState.OFF
                },
            )
        }
        val view = GamePatchList.Ready(
            serial = serial,
            crc = version.orEmpty(),
            patches = statuses,
            bundledRead = true,
            emulator = "RPCS3",
            whereFrom = if (read) {
                "RPCS3 has none for $serial${version?.let { " version $it" }.orEmpty()} in its patch files. Download the newest in RPCS3's Patch Manager, then come back."
            } else {
                "RPCS3 has no patch files yet. Open its Patch Manager and download them, then come back."
            },
            warning = "Fuse doesn't know this game's version, so patches for every version of $serial are listed; RPCS3 applies only the one made for the game you run.".takeIf { version == null && entries.isNotEmpty() },
            cheatsNote = "",
        )
        return Context(
            files, RPCS3_KIND, configPath, IniText(""), owned, statuses, view,
            rpcs3 = Rpcs3Context(entries, configText),
        ) to null
    }

    /** An RPCS3 patch on or off: `Enabled` for every hash the patch lists for this game, as its Patch Manager does. */
    private suspend fun setRpcs3(context: Context, r: Rpcs3Context, name: String, on: Boolean): Boolean {
        val status = context.statuses.firstOrNull { it.patch.name == name } ?: return false
        if (!(on && status.state == PatchState.OFF) && !(!on && status.state == PatchState.ON_BY_FUSE)) return false
        val entries = r.entries[name] ?: return false
        val config = io.github.matiyaaa.fuse.launch.patches.Rpcs3PatchConfig(r.configText)
        entries.forEach { config.set(it, on) }
        val made = data.owned.get(RPCS3_KIND, "${context.settingsPath}#made").isNotEmpty()
        val owned = if (on) context.owned + name else context.owned - name
        if (!on && made && config.isEmpty) {
            if (!context.files.remove(context.settingsPath)) return false
            data.owned.set(RPCS3_KIND, "${context.settingsPath}#made", emptySet())
        } else {
            if (!context.files.write(context.settingsPath, config.toString())) return false
            if (r.configText == null) data.owned.set(RPCS3_KIND, "${context.settingsPath}#made", setOf("file"))
        }
        data.owned.set(RPCS3_KIND, context.settingsPath, owned)
        return true
    }

    // shadPS4 ------------------------------------------------------------------------------------

    /** shadPS4's patches for a game: the file each patch repository names for its serial, switched by `isEnabled`. */
    private class Shadps4Context(
        /** Patch key (`repo/file#index`) to the file it is in and its place there. */
        val where: Map<String, Pair<String, Int>>,
        val texts: Map<String, String>,
    )

    private suspend fun shadPs4(game: Game, files: EmulatorFiles): Pair<Context?, GamePatchList.Unavailable?> {
        val shad = installed("shadps4") ?: return no("shadPS4 isn't installed", "Install shadPS4, start it once so it sets up its folders, and its patches show here.")
        val user = files.shadPs4User(shad) ?: return no("shadPS4 isn't set up yet", "Start shadPS4 once so it makes its user folder, then come back.")
        val launch = game.location.launchPath
        val sfo = sfoNear(launch)
        val serial = (game.tags.serial?.uppercase()?.filter { it.isLetterOrDigit() }?.takeIf { PS4_ID.matches(it) }
            ?: sfo?.get("TITLE_ID")?.trim()?.uppercase()?.takeIf { PS4_ID.matches(it) })
            ?: return no("Fuse can't tell which game this is", "shadPS4 files patches under the game's id (like CUSA00900), which Fuse reads from the game folder's sce_sys/param.sfo or from the game's name.")
        // shadPS4 runs an update kept beside the game (GAME-UPDATE or GAME-patch) over it.
        val folder = (if (fs.stat(launch)?.isDirectory == true) launch else io.github.matiyaaa.fuse.library.FsPath.parent(launch))?.trimEnd('/', '\\')
        val update = folder?.let { f -> listOf("-UPDATE", "-patch").firstNotNullOfOrNull { sfoAt("$f$it/sce_sys/param.sfo") } }
        val version = (update ?: sfo)?.get("APP_VER")?.trim()?.takeIf { it.isNotEmpty() }
        val patchesDir = "$user/patches"
        val where = LinkedHashMap<String, Pair<String, Int>>()
        val texts = HashMap<String, String>()
        val statuses = ArrayList<PatchStatus>()
        val onNow = HashSet<String>()
        val found = ArrayList<Pair<String, io.github.matiyaaa.fuse.launch.patches.Shadps4Patch>>()
        for (repo in list(patchesDir).filter { it.isDirectory }.sortedBy { it.name }) {
            val index = fs.readText("${repo.path}/files.json", MAX_TEXT) ?: continue
            val name = io.github.matiyaaa.fuse.launch.patches.Shadps4Patches.fileFor(index, serial) ?: continue
            val path = "${repo.path}/$name"
            val xml = fs.readText(path, MAX_TEXT) ?: continue
            texts[path] = xml
            for (p in io.github.matiyaaa.fuse.launch.patches.Shadps4Patches.parse(xml)) {
                if (!io.github.matiyaaa.fuse.launch.patches.Shadps4Patches.applies(p, version)) continue
                val key = "${repo.name}/$name#${p.index}"
                where[key] = path to p.index
                if (p.enabled) onNow += key
                found += key to p
            }
        }
        // What Fuse turned on is recorded per patch, across the game's files.
        val record = "$patchesDir#$serial"
        val owned = owned(SHADPS4_KIND, record, onNow)
        val repos = found.map { it.first.substringBefore('/') }.distinct().size
        for ((key, p) in found) {
            statuses += PatchStatus(
                PnachPatch(key, description = p.note, author = p.author, label = if (repos > 1) "${p.name} (${key.substringBefore('/')})" else p.name),
                when {
                    key in onNow && key in owned -> PatchState.ON_BY_FUSE
                    key in onNow -> PatchState.ON_IN_EMULATOR
                    else -> PatchState.OFF
                },
            )
        }
        val view = GamePatchList.Ready(
            serial = serial,
            crc = version.orEmpty(),
            patches = statuses,
            bundledRead = true,
            emulator = "shadPS4",
            whereFrom = "shadPS4 has none for $serial${version?.let { " version $it" }.orEmpty()}. Download patches in shadPS4's Patch Manager, then come back.",
            cheatsNote = "",
        )
        return Context(
            files, SHADPS4_KIND, record, IniText(""), owned, statuses, view,
            shadPs4 = Shadps4Context(where, texts),
        ) to null
    }

    /** A shadPS4 patch on or off: its `isEnabled`, nothing else in the file changed. */
    private suspend fun setShadPs4(context: Context, s: Shadps4Context, name: String, on: Boolean): Boolean {
        val status = context.statuses.firstOrNull { it.patch.name == name } ?: return false
        if (!(on && status.state == PatchState.OFF) && !(!on && status.state == PatchState.ON_BY_FUSE)) return false
        val (path, index) = s.where[name] ?: return false
        val xml = s.texts[path] ?: return false
        val changed = io.github.matiyaaa.fuse.launch.patches.Shadps4Patches.setEnabled(xml, index, on) ?: return false
        if (!context.files.write(path, changed)) return false
        data.owned.set(SHADPS4_KIND, context.settingsPath, if (on) context.owned + name else context.owned - name)
        return true
    }

    /** PARAM.SFO's strings for the game at [path]: beside it, in a PS3_GAME folder, or in sce_sys (PS4), up to three folders up. */
    private suspend fun sfoNear(path: String): Map<String, String>? {
        var dir: String? = if (fs.stat(path)?.isDirectory == true) path else io.github.matiyaaa.fuse.library.FsPath.parent(path)
        repeat(3) {
            val d = dir ?: return null
            for (rel in listOf("PARAM.SFO", "PS3_GAME/PARAM.SFO", "sce_sys/param.sfo")) sfoAt("$d/$rel")?.let { return it }
            dir = io.github.matiyaaa.fuse.library.FsPath.parent(d)
        }
        return null
    }

    private suspend fun sfoAt(path: String): Map<String, String>? =
        runCatching { fs.readBytes(path, 0, 64 * 1024) }.getOrNull()?.takeIf { it.size >= 20 }
            ?.let { runCatching { io.github.matiyaaa.fuse.library.disc.ParamSfo.strings(it) }.getOrNull() }?.takeIf { it.isNotEmpty() }

    // RetroArch -----------------------------------------------------------------------------------

    private suspend fun retroArch(game: Game, files: EmulatorFiles, run: RetroArchRun): Pair<Context?, GamePatchList.Unavailable?> {
        val home = files.retroArch(run.installed) ?: return no("RetroArch isn't set up yet", "Start RetroArch once so it writes its settings, then come back.")
        val core = io.github.matiyaaa.fuse.sync.retroArchLibraryName(run.core) ?: return no(
            "Fuse doesn't know this core's name",
            "RetroArch files cheats under the core's own name, which Fuse doesn't know for ${run.core}. Change them in RetroArch's Quick Menu, Cheats.",
        )
        val content = RetroArchCheats.contentName(run.content)
        val path = RetroArchCheats.gameFile(home.cheats, core, content)
        val own = fs.readText(path, MAX_TEXT)
        // No cheat file of the game's own yet: RetroArch's downloaded cheats for its system, by name.
        var source: String? = null
        val text = own ?: run {
            for (system in io.github.matiyaaa.fuse.integrations.libretro.LibretroThumbnails.systemsFor(game.platformId)) {
                val folder = "${home.cheats}/$system"
                val match = RetroArchCheats.databaseMatch(list(folder).filter { !it.isDirectory }.map { it.name }, content, game.titles.original) ?: continue
                val found = fs.readText("$folder/$match", MAX_TEXT) ?: continue
                source = "$system/$match"
                return@run found
            }
            ""
        }
        val parsed = RetroArchCheats.parse(text)
        val owned = if (own == null) emptySet() else owned(RETROARCH_KIND, path, parsed.filter { it.on }.map { it.index.toString() }.toSet())
        val cheats = parsed.map { c ->
            PatchStatus(
                PnachPatch(c.index.toString(), label = c.desc ?: "Cheat ${c.index + 1}"),
                when {
                    c.on && c.index.toString() in owned -> PatchState.ON_BY_FUSE
                    c.on -> PatchState.ON_IN_EMULATOR
                    else -> PatchState.OFF
                },
            )
        }
        val cfg = home.config?.let { fs.readText(it, MAX_TEXT) }?.let(RetroArchCfg::parse).orEmpty()
        val overridePath = RetroArchCheats.overrideFile(home.configDir, core, content)
        val overrideText = fs.readText(overridePath, MAX_TEXT)
        val overrides = cfg[RetroArchCheats.OVERRIDES] != "false"
        val forGame = if (overrides) overrideText?.let(RetroArchCfg::parse)?.get(RetroArchCheats.APPLY) else null
        val cheatsOn = (forGame ?: cfg[RetroArchCheats.APPLY]) == "true"
        val ownsSwitch = data.owned.get(RETROARCH_KIND, "$overridePath#switch").contains(RetroArchCheats.APPLY) && forGame == "true"
        val view = GamePatchList.Ready(
            serial = "$content in $core",
            crc = "",
            patches = emptyList(),
            bundledRead = true,
            cheats = cheats,
            cheatsOn = cheatsOn,
            emulator = "RetroArch",
            whereFrom = "RetroArch has none for $content with $core: no $core/$content.cht in its cheats folder, and none for it among the cheats it downloaded (Online Updater, Update Cheats).",
            warning = listOfNotNull(
                source?.let { "These are RetroArch's downloaded cheats; turning one on saves them as this game's own." },
                "RetroArch's game overrides are off, so cheats turned on here load with the game and apply once you choose Apply Changes in its Quick Menu, Cheats."
                    .takeIf { !overrides && !cheatsOn && cheats.isNotEmpty() },
            ).joinToString(" ").ifEmpty { null },
            cheatsNote = if (overrides) "Turning one on also turns on Apply Cheats After Game Load for this game alone, and off with the last one." else "",
        )
        return Context(
            files, RETROARCH_KIND, path, IniText(""), emptySet(), emptyList(), view, owned, cheats, ownsSwitch, cheatsOn,
            retroArch = RetroArchContext(text, parsed, own == null, overridePath, overrideText, overrides),
        ) to null
    }

    /**
     * A RetroArch cheat on or off: its `cheatK_enable` line in the game's cheat file (made from the
     * downloaded cheats when Fuse is the first to change one). Turning one on also turns on Apply Cheats
     * After Game Load in the game's override when it is off; turning off the last one Fuse turned on
     * takes back what Fuse added there, and the files Fuse made for it.
     */
    private suspend fun setRetroArch(context: Context, ra: RetroArchContext, name: String, on: Boolean): Boolean {
        val status = context.cheats.firstOrNull { it.patch.name == name } ?: return false
        if (!(on && status.state == PatchState.OFF) && !(!on && status.state == PatchState.ON_BY_FUSE)) return false
        val index = name.toIntOrNull() ?: return false
        val path = context.settingsPath
        val owned = if (on) context.ownedCheats + name else context.ownedCheats - name
        val madeFile = ra.fromDatabase || data.owned.get(RETROARCH_KIND, "$path#made").isNotEmpty()
        val text = RetroArchCheats.set(ra.text, index, on)
        // A cheat file Fuse made, with nothing on in it any more, goes again.
        if (!on && madeFile && owned.isEmpty() && RetroArchCheats.parse(text).none { it.on }) {
            if (!context.files.remove(path)) return false
            data.owned.set(RETROARCH_KIND, "$path#made", emptySet())
        } else {
            if (!context.files.write(path, text)) return false
            if (ra.fromDatabase) data.owned.set(RETROARCH_KIND, "$path#made", setOf("file"))
        }
        data.owned.set(RETROARCH_KIND, path, owned)
        if (!ra.overrides) return true
        val override = ra.overridePath
        var ownsSwitch = context.ownsCheatsSwitch
        if (on && !context.cheatsOn) {
            if (!context.files.write(override, RetroArchCfg.set(ra.overrideText ?: "", RetroArchCheats.APPLY, "true"))) return true
            if (ra.overrideText == null) data.owned.set(RETROARCH_KIND, "$override#made", setOf("file"))
            ownsSwitch = true
        }
        if (!on && owned.isEmpty() && ownsSwitch) {
            val rest = RetroArchCfg.remove(ra.overrideText ?: "", RetroArchCheats.APPLY)
            val made = data.owned.get(RETROARCH_KIND, "$override#made").isNotEmpty()
            val done = if (made && rest.isBlank()) context.files.remove(override) else context.files.write(override, rest)
            if (!done) return true
            if (made && rest.isBlank()) data.owned.set(RETROARCH_KIND, "$override#made", emptySet())
            ownsSwitch = false
        }
        data.owned.set(RETROARCH_KIND, "$override#switch", if (ownsSwitch) setOf(RetroArchCheats.APPLY) else emptySet())
        return true
    }

    private companion object {
        const val MAX_TEXT = 1024 * 1024

        /** Where Fuse records what it turned on, by file: kept as it was for PCSX2, which came first. */
        const val PCSX2_KIND = "pcsx2.patches"
        const val DUCKSTATION_KIND = "duckstation.patches"
        const val PPSSPP_KIND = "ppsspp.cheats"
        const val RETROARCH_KIND = "retroarch.cheats"
        const val RPCS3_KIND = "rpcs3.patches"
        const val SHADPS4_KIND = "shadps4.patches"

        /** RPCS3's patch.yml runs to a few megabytes. */
        const val MAX_YAML = 32 * 1024 * 1024
        val PS3_ID = Regex("^[A-Z]{4}\\d{5}$")
        val PS4_ID = Regex("^(CUSA|PLAS|PCAS|PCJS|PLJM|PLJS|PCKS|PLKS)\\d{5}$")
        val APP_VER = Regex("^\\d{2}\\.\\d{2}$")
    }
}
