package io.github.matiyaaa.fuse.launch.patches

/** A cheat in a RetroArch `.cht` file: its number there, what it says it does, and whether it is on. */
data class RaCheat(val index: Int, val desc: String?, val on: Boolean)

/**
 * RetroArch's config files (`retroarch.cfg`, overrides and `.cht` cheat files), as its config_file.c
 * reads them: `key = value` or `key = "value"`, `#` starting a comment. Edited without disturbing
 * anything else in the file.
 */
object RetroArchCfg {
    fun parse(text: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (raw in text.lineSequence()) {
            val (key, value) = entry(raw) ?: continue
            out[key] = value
        }
        return out
    }

    /** [key] set to [value] (quoted, as RetroArch writes it): its line replaced where it is, else added at the end. */
    fun set(text: String, key: String, value: String): String {
        val lines = lines(text)
        val at = lines.indexOfFirst { entry(it)?.first == key }
        val line = "$key = \"$value\""
        if (at >= 0) lines[at] = line else lines += line
        return join(text, lines)
    }

    /** Every [key] line taken out. */
    fun remove(text: String, key: String): String = join(text, lines(text).filterNot { entry(it)?.first == key }.toMutableList())

    internal fun entry(raw: String): Pair<String, String>? {
        val line = raw.trim()
        if (line.isEmpty() || line.startsWith('#')) return null
        val eq = line.indexOf('=')
        if (eq <= 0) return null
        val key = line.substring(0, eq).trim()
        var value = line.substring(eq + 1).trim()
        value = if (value.startsWith('"')) {
            // Up to the closing quote; RetroArch has no escapes.
            value.substring(1).substringBefore('"')
        } else {
            value.substringBefore('#').trim().substringBefore(' ')
        }
        return key to value
    }

    internal fun lines(text: String): MutableList<String> {
        val all = text.replace("\r\n", "\n").split('\n').toMutableList()
        if (all.isNotEmpty() && all.last().isEmpty()) all.removeAt(all.lastIndex)
        return all
    }

    internal fun join(original: String, lines: List<String>): String {
        if (lines.isEmpty()) return ""
        val sep = if (original.contains("\r\n")) "\r\n" else "\n"
        return lines.joinToString(sep) + sep
    }

    /**
     * A folder setting as RetroArch expands it: empty or `default` means its default ([fallback]), `~`
     * the home folder, and a leading `:` the program's folder ([programDir]).
     */
    fun folder(value: String?, fallback: String, home: String, programDir: String?): String {
        val v = value?.trim()?.replace('\\', '/') ?: return fallback
        return when {
            v.isEmpty() || v == "default" -> fallback
            v.startsWith("~") -> home + v.drop(1)
            v.startsWith(":") -> (programDir ?: return fallback) + "/" + v.drop(1).trimStart('/')
            else -> v
        }.trimEnd('/')
    }
}

/**
 * RetroArch's cheats for one game (cheat_manager.c): a game's own cheat file is
 * `<cheat_database_path>/<core's library name>/<content name>.cht`, loaded every time the game starts
 * with that core; `cheats = N` then `cheatK_desc`, `cheatK_code` and `cheatK_enable` for each. The
 * enabled ones are applied when the game loads only while `apply_cheats_after_load` is on, which a
 * per-game override (`<config folder>/<core>/<content name>.cfg`, loaded before the cheats while
 * `auto_overrides_enable` is on) can turn on for that game alone. RetroArch's own downloaded database
 * keeps cheats by system: `<cheat_database_path>/<System>/<Game>.cht`.
 */
object RetroArchCheats {
    const val APPLY = "apply_cheats_after_load"
    const val OVERRIDES = "auto_overrides_enable"

    fun parse(text: String): List<RaCheat> {
        val cfg = RetroArchCfg.parse(text)
        val count = cfg["cheats"]?.toIntOrNull()?.coerceIn(0, MAX_CHEATS) ?: return emptyList()
        return (0 until count).map { k ->
            RaCheat(k, cfg["cheat${k}_desc"]?.takeIf { it.isNotBlank() }, cfg["cheat${k}_enable"].let { it == "true" || it == "1" })
        }
    }

    /** [text] with cheat [index] turned [on]: its enable line changed (keeping its quoting), or added after its other lines. */
    fun set(text: String, index: Int, on: Boolean): String {
        val lines = RetroArchCfg.lines(text)
        val key = "cheat${index}_enable"
        val at = lines.indexOfFirst { RetroArchCfg.entry(it)?.first == key }
        val value = if (on) "true" else "false"
        if (at >= 0) {
            val quoted = lines[at].substringAfter('=').trim().startsWith('"')
            lines[at] = "$key = " + if (quoted) "\"$value\"" else value
        } else {
            val last = lines.indexOfLast { RetroArchCfg.entry(it)?.first?.startsWith("cheat${index}_") == true }
            lines.add(if (last >= 0) last + 1 else lines.size, "$key = \"$value\"")
        }
        return RetroArchCfg.join(text, lines)
    }

    fun gameFile(cheats: String, core: String, content: String): String = "$cheats/$core/$content.cht"

    fun overrideFile(config: String, core: String, content: String): String = "$config/$core/$content.cfg"

    /** The content's name as RetroArch uses it: its file name without the extension. */
    fun contentName(path: String): String = path.replace('\\', '/').substringAfterLast('/').let { if ('.' in it) it.substringBeforeLast('.') else it }

    /** Database cheats for [content] among [files] (names in a system's folder): the same name, else the only one with the same title. */
    fun databaseMatch(files: List<String>, content: String, title: String?): String? {
        val chts = files.filter { it.endsWith(".cht", ignoreCase = true) }
        chts.firstOrNull { it.dropLast(4).equals(content, ignoreCase = true) }?.let { return it }
        fun short(s: String) = s.substringBefore(" (").substringBefore(" [").trim().lowercase()
        val want = short(title ?: content)
        return chts.filter { short(it.dropLast(4)) == want }.singleOrNull()
    }

    private const val MAX_CHEATS = 2048
}

/**
 * Where one RetroArch install keeps its settings and cheats: its retroarch.cfg (when it has one), its
 * cheats folder (`cheat_database_path`) and its config folder for overrides (`rgui_config_directory`),
 * each worked out as RetroArch does on this system.
 */
data class RetroArchHome(val config: String?, val cheats: String, val configDir: String)
