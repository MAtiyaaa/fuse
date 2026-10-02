package io.github.matiyaaa.fuse.launch.patches

/** A named patch in a `.pnach` file, as PCSX2 lists it in a game's Patches tab. */
data class PnachPatch(val name: String, val description: String? = null, val author: String? = null)

/**
 * `.pnach` files as PCSX2 reads them (pcsx2/Patch.cpp, LoadPatchesFromString and its UI listing):
 * `//` comments, `[name]` starts a patch group, `description=`, `comment=` and `author=` describe it,
 * and only groups with `patch=` or `dpatch=` lines are patches. Lines before the first group are
 * unlabelled patches, which PCSX2 always applies; they can't be switched, so they aren't listed.
 */
object Pnach {
    /** True when patch lines come before any `[name]`: PCSX2 then leaves its bundled patches out (pcsx2/Patch.cpp, PatchStringHasUnlabelledPatch). */
    fun hasUnlabelled(text: String): Boolean {
        for (raw in text.lineSequence()) {
            val line = raw.substringBefore("//").trim()
            if (line.isEmpty()) continue
            if (line.startsWith('[') && line.endsWith(']') && line.length > 2) return false
            if (line.substringBefore('=').trim() == "patch") return true
        }
        return false
    }

    fun parse(text: String): List<PnachPatch> {
        val out = ArrayList<PnachPatch>()
        var name: String? = null
        var description: String? = null
        var author: String? = null
        var hasPatch = false
        fun close() {
            val n = name
            if (n != null && n.isNotEmpty() && hasPatch && out.none { it.name == n }) out += PnachPatch(n, description, author)
        }
        for (raw in text.lineSequence()) {
            var line = raw.trim()
            line.indexOf("//").takeIf { it >= 0 }?.let { line = line.substring(0, it).trim() }
            if (line.isEmpty()) continue
            if (line.startsWith('[')) {
                if (line.length < 2 || !line.endsWith(']')) continue
                close()
                name = line.substring(1, line.length - 1)
                description = null
                author = null
                hasPatch = false
                continue
            }
            val eq = line.indexOf('=')
            if (eq <= 0) continue
            val key = line.substring(0, eq).trim()
            val value = line.substring(eq + 1).trim()
            when (key) {
                "author" -> author = value.ifEmpty { null }
                "description" -> description = value.ifEmpty { null }
                "comment" -> if (description == null) description = value.ifEmpty { null }
                "patch", "dpatch" -> hasPatch = true
            }
        }
        close()
        return out
    }
}

/**
 * An INI file edited without disturbing anything else in it: comments, order, spacing and every
 * other key stay exactly as they were. Sections and keys match ignoring case, as PCSX2's SimpleIni
 * does; a key can repeat, which is how PCSX2 keeps lists (`Enable = name` once per patch).
 */
class IniText(text: String) {
    private val lines: MutableList<String> = text.replace("\r\n", "\n").split('\n').toMutableList().also {
        // A file's final newline isn't a line of its own.
        if (it.size > 1 && it.last().isEmpty()) it.removeAt(it.lastIndex)
        if (it.size == 1 && it[0].isEmpty()) it.clear()
    }
    private val crlf = text.contains("\r\n")

    fun values(section: String, key: String): List<String> {
        val range = sectionRange(section) ?: return emptyList()
        return range.mapNotNull { i -> entry(lines[i])?.takeIf { it.first.equals(key, ignoreCase = true) }?.second }
    }

    /** Adds `key = value` at the end of [section] (made at the end when missing), unless it is there already. */
    fun add(section: String, key: String, value: String): Boolean {
        if (values(section, key).any { it == value }) return false
        val range = sectionRange(section)
        if (range == null) {
            if (lines.isNotEmpty() && lines.last().isNotBlank()) lines += ""
            lines += "[$section]"
            lines += "$key = $value"
            return true
        }
        // After the section's last line that says something, so blank lines before the next section stay.
        var at = range.first
        for (i in range) if (lines[i].isNotBlank()) at = i + 1
        lines.add(at, "$key = $value")
        return true
    }

    /** Removes every `key = value` line in [section]; true when one was there. */
    fun remove(section: String, key: String, value: String): Boolean {
        val range = sectionRange(section) ?: return false
        val drop = range.filter { i -> entry(lines[i])?.let { it.first.equals(key, ignoreCase = true) && it.second == value } == true }
        drop.asReversed().forEach { lines.removeAt(it) }
        return drop.isNotEmpty()
    }

    fun bool(section: String, key: String): Boolean? = values(section, key).lastOrNull()?.lowercase()?.let {
        when (it) {
            "true", "1", "yes", "on" -> true
            "false", "0", "no", "off" -> false
            else -> null
        }
    }

    override fun toString(): String {
        val sep = if (crlf) "\r\n" else "\n"
        return if (lines.isEmpty()) "" else lines.joinToString(sep) + sep
    }

    /** The lines of [section]'s body (after its header, up to the next header). */
    private fun sectionRange(section: String): IntRange? {
        val start = lines.indexOfFirst { header(it)?.equals(section, ignoreCase = true) == true }
        if (start < 0) return null
        var end = start + 1
        while (end < lines.size && header(lines[end]) == null) end++
        return (start + 1) until end
    }

    private fun header(line: String): String? {
        val t = line.trim()
        return if (t.length >= 2 && t.startsWith('[') && t.endsWith(']')) t.substring(1, t.length - 1).trim() else null
    }

    private fun entry(line: String): Pair<String, String>? {
        val t = line.trim()
        if (t.isEmpty() || t.startsWith(';') || t.startsWith('#') || t.startsWith('[')) return null
        val eq = t.indexOf('=')
        if (eq <= 0) return null
        return t.substring(0, eq).trim() to t.substring(eq + 1).trim()
    }
}

/** How one patch stands for a game, and who decided it. */
enum class PatchState {
    /** Off. Fuse can turn it on. */
    OFF,

    /** On because Fuse turned it on; Fuse can turn it off again. */
    ON_BY_FUSE,

    /** On because it was turned on in PCSX2 (or by hand). Fuse leaves it alone. */
    ON_IN_PCSX2,

    /** On for every game by PCSX2's widescreen or no-interlacing setting. Fuse leaves it alone. */
    ON_FOR_ALL_GAMES,

    /** Turned off for this game in PCSX2. Fuse leaves it alone. */
    OFF_IN_PCSX2,
}

data class PatchStatus(val patch: PnachPatch, val state: PatchState) {
    val on: Boolean get() = state == PatchState.ON_BY_FUSE || state == PatchState.ON_IN_PCSX2 || state == PatchState.ON_FOR_ALL_GAMES
    val fuseCanChange: Boolean get() = state == PatchState.OFF || state == PatchState.ON_BY_FUSE
}

/**
 * Turning PCSX2 patches on and off for one game, the way PCSX2 stores it (pcsx2/Patch.cpp,
 * ReloadEnabledLists): its game settings file (`gamesettings/SERIAL_CRC.ini`) lists the patches it
 * applies as `[Patches] Enable = name` and ones turned off as `Disable = name`; the widescreen and
 * no-interlacing settings (`[EmuCore] EnableWideScreenPatches`, `EnableNoInterlacingPatches`, in the
 * game's file or PCSX2.ini) turn on the patches named "Widescreen 16:9" and "No-Interlacing".
 *
 * Fuse only ever turns off a patch it turned on itself ([owned]); anything the user set in PCSX2
 * stays exactly as it is.
 */
object Pcsx2PatchRules {
    const val SECTION = "Patches"
    const val ENABLE = "Enable"
    const val DISABLE = "Disable"
    const val WIDESCREEN = "Widescreen 16:9"
    const val NO_INTERLACING = "No-Interlacing"

    /** The game settings file's name: `SLUS-20946_0C040404.ini`, as PCSX2's GetGameSettingsPath makes it. */
    fun gameSettingsName(serial: String, crc: Long): String {
        val hex = crc.toString(16).uppercase().padStart(8, '0')
        return if (serial.isEmpty()) "$hex.ini" else "${sanitize(serial)}_$hex.ini"
    }

    /** Patch files on disk for a game, by PCSX2's name templates: `SERIAL_CRC*.pnach`, then `CRC*.pnach`. */
    fun matchesPnach(fileName: String, serial: String, crc: Long): Boolean {
        val hex = crc.toString(16).uppercase().padStart(8, '0')
        val name = fileName.uppercase()
        if (!name.endsWith(".PNACH")) return false
        return name.startsWith("${serial.uppercase()}_$hex") || name.startsWith(hex)
    }

    /** The patch file inside PCSX2's patches.zip, by preference: `SERIAL_CRC.pnach`, then `CRC.pnach`. */
    fun zipEntries(serial: String, crc: Long): List<String> {
        val hex = crc.toString(16).uppercase().padStart(8, '0')
        return listOfNotNull(serial.takeIf { it.isNotEmpty() }?.let { "${it}_$hex.pnach" }, "$hex.pnach")
    }

    fun states(
        patches: List<PnachPatch>,
        game: IniText,
        global: IniText?,
        owned: Set<String>,
    ): List<PatchStatus> {
        val enabled = game.values(SECTION, ENABLE).toSet()
        val disabled = game.values(SECTION, DISABLE).toSet()
        fun setting(key: String) = game.bool("EmuCore", key) ?: global?.bool("EmuCore", key) ?: false
        val widescreen = setting("EnableWideScreenPatches")
        val noInterlacing = setting("EnableNoInterlacingPatches")
        return patches.map { p ->
            val state = when {
                p.name in disabled -> PatchState.OFF_IN_PCSX2
                p.name in enabled && p.name in owned -> PatchState.ON_BY_FUSE
                p.name in enabled -> PatchState.ON_IN_PCSX2
                (p.name == WIDESCREEN && widescreen) || (p.name == NO_INTERLACING && noInterlacing) -> PatchState.ON_FOR_ALL_GAMES
                else -> PatchState.OFF
            }
            PatchStatus(p, state)
        }
    }

    /**
     * Turns [name] on or off in [game] when Fuse may: on only from [PatchState.OFF], off only from
     * [PatchState.ON_BY_FUSE]. Returns the new owned set, or null when nothing may change.
     */
    fun change(status: PatchStatus, on: Boolean, game: IniText, owned: Set<String>): Set<String>? {
        val name = status.patch.name
        return when {
            on && status.state == PatchState.OFF -> {
                game.add(SECTION, ENABLE, name)
                owned + name
            }
            !on && status.state == PatchState.ON_BY_FUSE -> {
                game.remove(SECTION, ENABLE, name)
                owned - name
            }
            else -> null
        }
    }

    /** What PCSX2's Path::SanitizeFileName leaves of a serial: no path or reserved characters. */
    private fun sanitize(s: String): String = s.map { if (it in "<>:\"/\\|?*" || it.code < 32) '_' else it }.joinToString("")
}

/**
 * Where one PCSX2 install keeps what Fuse reads for patches: its data folder, the game settings and
 * patches folders inside it (PCSX2.ini's `[Folders]` can move them), and its bundled patches.zip
 * when this system lets Fuse read it (not inside a running AppImage).
 */
data class Pcsx2Home(
    val dataRoot: String,
    val gameSettings: String,
    val patches: String,
    val patchesZip: String?,
) {
    val settingsFile: String get() = "$dataRoot/inis/PCSX2.ini"
}
