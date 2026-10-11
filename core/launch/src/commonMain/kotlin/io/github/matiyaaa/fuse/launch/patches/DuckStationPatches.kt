package io.github.matiyaaa.fuse.launch.patches

/** A code in a DuckStation `.cht` file, with what Fuse needs to list it. */
data class ChtCode(
    /** The name DuckStation enables it by: `group\name` when it is in a group. */
    val name: String,
    val description: String? = null,
    val author: String? = null,
    /** Applied by hand from DuckStation's menu while playing, never from the enabled list. */
    val manual: Boolean = false,
    /** It has a setting of its own (Option lines); DuckStation uses its first unless one is chosen there. */
    val hasOptions: Boolean = false,
    /** The screen shape a widescreen patch is for (`16:9`), from `OverrideAspectRatio`. */
    val aspectRatio: String? = null,
) {
    /** The name without its group, as DuckStation shows it inside the group. */
    val label: String get() = name.substringAfterLast('\\')

    val group: String? get() = name.substringBeforeLast('\\', "").ifEmpty { null }

    fun asPatch(): PnachPatch = PnachPatch(
        name,
        listOfNotNull(group, description, if (hasOptions) "Has its own setting; DuckStation uses the first unless you choose another there" else null)
            .joinToString(". ").ifEmpty { null },
        author,
        label = label.takeIf { it != name },
    )
}

/**
 * DuckStation's `.cht` files as it reads them (src/core/cheats.cpp, ExtractCodeInfo and ParseFile):
 * `[name]` starts a code, `#group=` (an older style) puts the codes after it in a group, `#` and `;`
 * start comments, and `Description`, `Author`, `Activation`, `Option`, `OverrideAspectRatio` and
 * `Ignore` describe the code above them. A later code with the same name replaces an earlier one.
 */
object Cht {
    fun parse(text: String): List<ChtCode> {
        val out = ArrayList<ChtCode>()
        var group: String? = null
        var legacyManual = false
        var current: ChtCode? = null
        var ignore = false
        fun finish() {
            val c = current ?: return
            // A code with no lines is still listed, as DuckStation lists it.
            if (!ignore) {
                val i = out.indexOfFirst { it.name == c.name }
                if (i >= 0) out[i] = c else out += c
            }
        }
        for (raw in text.lineSequence()) {
            var line = raw.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("#group=")) {
                group = line.substring(7).trim().ifEmpty { null }
                continue
            }
            if (line.startsWith("#activation=")) {
                legacyManual = line.substring(12).trim() == "Manual"
                continue
            }
            if (line[0] == '#' || line[0] == ';') continue
            if (line[0] == '[') {
                if (line.length < 3 || !line.endsWith(']')) continue
                val name = line.substring(1, line.length - 1).trim()
                if (name.isEmpty()) continue
                finish()
                current = ChtCode(if (group != null) "$group\\$name" else name, manual = legacyManual)
                ignore = false
                continue
            }
            // A comment at the end of a line goes, as DuckStation strips from the last # or ;.
            val comment = line.indexOfLast { it == '#' || it == ';' }
            if (comment >= 0) line = line.substring(0, comment).trim()
            if (line.isEmpty()) continue
            val code = current ?: continue
            val eq = line.indexOf('=')
            if (eq >= 0) {
                val key = line.substring(0, eq).trim()
                val value = line.substring(eq + 1).trim()
                current = when (key) {
                    "Description" -> code.copy(description = value.ifEmpty { null })
                    "Author" -> code.copy(author = value.ifEmpty { null })
                    "Activation" -> code.copy(manual = value == "Manual")
                    "Option", "OptionRange" -> code.copy(hasOptions = true)
                    "OverrideAspectRatio" -> code.copy(aspectRatio = value.takeIf { ASPECT.matches(it) })
                    else -> {
                        if (key == "Ignore") ignore = value.equals("true", ignoreCase = true) || value == "1"
                        code
                    }
                }
            }
        }
        finish()
        return out
    }

    private val ASPECT = Regex("""\d+:\d+""")
}

/**
 * Turning DuckStation patches and cheats on and off for one game, the way DuckStation stores it
 * (src/core/cheats.cpp, ReloadEnabledLists and AreCheatsEnabled): its game settings file
 * (`gamesettings/SERIAL.ini`) lists the patches it applies as `[Patches] Enable = name` and the
 * cheats as `[Cheats] Enable = name`, and cheats apply only while `[Cheats] EnableCheats = true` is in
 * that same file. Codes come from `.cht` files named after the serial (`SLUS-00594.cht`, or with the
 * game's hash, `SLUS-00594_0123456789ABCDEF.cht`) in its cheats and patches folders, after the ones
 * in its bundled cheats.zip and patches.zip.
 *
 * As with PCSX2, Fuse only ever turns off what it turned on itself.
 */
object DuckStationPatchRules {
    const val PATCHES = "Patches"
    const val CHEATS = "Cheats"
    const val ENABLE = "Enable"
    const val CHEATS_SWITCH = "EnableCheats"

    /** False in the game's file keeps the bundled cheats out (`LoadCheatsFromDatabase`, on unless set). */
    const val CHEATS_DATABASE = "LoadCheatsFromDatabase"

    /** The game settings file's name: `SLUS-00594.ini`, as System::GetGameSettingsPath makes it. */
    fun gameSettingsName(serial: String): String = "${sanitize(serial)}.ini"

    /** A `.cht` file for [serial]: `SERIAL.cht` or `SERIAL_<16 hex digits>.cht`. */
    fun matchesCht(fileName: String, serial: String): Boolean {
        if (!fileName.endsWith(".cht", ignoreCase = true) || !fileName.startsWith(serial, ignoreCase = true)) return false
        val rest = fileName.substring(serial.length, fileName.length - 4)
        return rest.isEmpty() || HASHED.matches(rest)
    }

    /** The entry in DuckStation's cheats.zip or patches.zip for [serial] (its hash isn't known to Fuse). */
    fun zipEntry(serial: String): String = "$serial.cht"

    /**
     * How each code stands. A patch with an `OverrideAspectRatio` matching [widescreenFor] (DuckStation's
     * widescreen rendering with that screen shape chosen) is on for every game; only the first such one,
     * as DuckStation enables only one.
     */
    fun states(codes: List<ChtCode>, game: IniText, owned: Set<String>, section: String, widescreenFor: String? = null): List<PatchStatus> {
        val enabled = game.values(section, ENABLE).toSet()
        val widescreen = if (section == PATCHES && widescreenFor != null) codes.firstOrNull { it.aspectRatio == widescreenFor }?.name else null
        return codes.filter { !it.manual }.map { c ->
            val state = when {
                c.name in enabled && c.name in owned -> PatchState.ON_BY_FUSE
                c.name in enabled -> PatchState.ON_IN_EMULATOR
                c.name == widescreen -> PatchState.ON_FOR_ALL_GAMES
                else -> PatchState.OFF
            }
            PatchStatus(c.asPatch(), state)
        }
    }

    /** Whether DuckStation applies cheats to this game: only its own game settings file can say so. */
    fun cheatsOn(game: IniText): Boolean = game.bool(CHEATS, CHEATS_SWITCH) ?: false

    /** Whether its bundled cheats are offered for this game. */
    fun databaseCheats(game: IniText): Boolean = game.bool(CHEATS, CHEATS_DATABASE) ?: true

    /**
     * The screen shape DuckStation's widescreen patches are applied for (Settings::gpu_widescreen_rendering
     * from `[GPU] WidescreenHack`, with `[Display] AspectRatio` other than 4:3), the game's file first;
     * null when it applies none.
     */
    fun widescreenFor(game: IniText, global: IniText?): String? {
        val on = game.bool("GPU", "WidescreenHack") ?: global?.bool("GPU", "WidescreenHack") ?: false
        if (!on) return null
        val ratio = game.values("Display", "AspectRatio").lastOrNull() ?: global?.values("Display", "AspectRatio")?.lastOrNull() ?: return null
        val m = Regex("""^(\d+):(\d+)$""").find(ratio.trim()) ?: return null
        val (n, d) = m.destructured
        if (n.toInt() <= 0 || d.toInt() <= 0 || (n == "4" && d == "3")) return null
        return "$n:$d"
    }

    /** True when `[Main] DisableAllEnhancements` is on: DuckStation then applies no patches or cheats. */
    fun enhancementsOff(game: IniText, global: IniText?): Boolean = game.bool("Main", "DisableAllEnhancements") ?: global?.bool("Main", "DisableAllEnhancements") ?: false

    /** What DuckStation's Path::SanitizeFileName leaves of a serial. */
    private fun sanitize(s: String): String = s.map { if (it in "<>:\"/\\|?*" || it.code < 32) '_' else it }.joinToString("")

    private val HASHED = Regex("_[0-9A-Fa-f]{16}")
}

/**
 * Where one DuckStation install keeps what Fuse reads for patches and cheats: its data folder
 * (Core::SetDataRoot), the folders settings.ini's `[Folders]` names (or their defaults inside it), and
 * the bundled cheats.zip and patches.zip in its resources when this system lets Fuse read them.
 */
data class DuckStationHome(
    val dataRoot: String,
    val gameSettings: String,
    val cheats: String,
    val patches: String,
    val cheatsZip: String?,
    val patchesZip: String?,
) {
    val settingsFile: String get() = "$dataRoot/settings.ini"
}
