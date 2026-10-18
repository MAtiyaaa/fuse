package io.github.matiyaaa.fuse.library.steam

/**
 * A shortcut someone else put in Steam (Steam ROM Manager, EmuDeck, a script, the person) that
 * plays one of Fuse's games: its command names the game's own file, exactly.
 */
data class SteamLearned(
    /** The entry's id, as Steam files its art under it (unsigned 32-bit). */
    val appId: Long,
    /** Its name in Steam. */
    val name: String,
    /** The program it starts, by file name (`flatpak`, `retroarch`, `mgba-qt`). */
    val program: String,
    /** The Fuse game it plays. */
    val gameId: Long,
    /** The game's file, as the entry names it. */
    val path: String,
)

/**
 * Reads the shortcuts other tools made in Steam's `shortcuts.vdf` and finds the ones that play a
 * game Fuse has, so Fuse can offer to take them over (its own entry in the same place, with the
 * person's name, art and collections kept) or leave them. Only reads: nothing here writes.
 *
 * A shortcut is matched only by a game's file appearing, exactly and whole, as its program or one
 * of its arguments (in `Exe` or `LaunchOptions`, read as a POSIX shell splits words, quotes and
 * backslashes taken off); never by its name. Fuse's own entries are never in the list, and nor is
 * a game Fuse already has an entry for.
 */
object SteamLearn {
    fun find(bytes: ByteArray?, isFuse: (String) -> Boolean, files: Map<String, Long>): List<SteamLearned> {
        val root = bytes?.takeIf { it.isNotEmpty() }?.let(BinaryVdf::parse) ?: return emptyList()
        val list = root["shortcuts"] as? BinaryVdf.Block ?: return emptyList()
        val fuses = SteamGames.inSteam(bytes, isFuse).keys
        val out = ArrayList<SteamLearned>()
        for ((_, v) in list.entries) {
            val b = v as? BinaryVdf.Block ?: continue
            val exe = (b["Exe"] as? BinaryVdf.Str)?.value.orEmpty()
            val options = (b["LaunchOptions"] as? BinaryVdf.Str)?.value.orEmpty()
            val name = (b["AppName"] as? BinaryVdf.Str)?.value.orEmpty()
            if (SteamGames.isOurs(exe, options, isFuse)) continue
            val program = words(exe).firstOrNull().orEmpty()
            val hit = (listOf(program) + words(options)).firstNotNullOfOrNull { w -> files[w]?.let { w to it } } ?: continue
            if (hit.second in fuses || out.any { it.gameId == hit.second }) continue
            val appId = ((b["appid"] as? BinaryVdf.Int32)?.value ?: SteamShortcuts.appId(exe, name)).toLong() and 0xFFFFFFFFL
            out += SteamLearned(appId, name, program.replace('\\', '/').substringAfterLast('/'), hit.second, hit.first)
        }
        return out
    }

    /**
     * [line] split into words as a POSIX shell splits them: on unquoted spaces, with single quotes
     * keeping everything, double quotes keeping all but a backslash before `"`, `\`, `$` or a
     * backquote, and a backslash outside quotes keeping the next character. Nothing is expanded.
     */
    fun words(line: String): List<String> {
        val out = ArrayList<String>()
        val word = StringBuilder()
        var inWord = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '\'' -> {
                    val end = line.indexOf('\'', i + 1).let { if (it < 0) line.length else it }
                    word.append(line, i + 1, end)
                    inWord = true
                    i = end
                }
                c == '"' -> {
                    inWord = true
                    i++
                    while (i < line.length && line[i] != '"') {
                        if (line[i] == '\\' && i + 1 < line.length && line[i + 1] in "\"\\$`") i++
                        word.append(line[i])
                        i++
                    }
                }
                c == '\\' && i + 1 < line.length -> {
                    word.append(line[i + 1])
                    inWord = true
                    i++
                }
                c.isWhitespace() -> if (inWord) {
                    out += word.toString()
                    word.clear()
                    inWord = false
                }
                else -> {
                    word.append(c)
                    inWord = true
                }
            }
            i++
        }
        if (inWord) out += word.toString()
        return out
    }
}
