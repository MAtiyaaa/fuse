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

/** How the person's shortcuts quote a game's file in their launch options. */
enum class SteamQuoting {
    /** As it is, or with a backslash before each character the shell would read otherwise. */
    PLAIN,

    /** In double quotes. */
    DOUBLE,

    /** In single quotes. */
    SINGLE,
}

/**
 * Frame generation the person's shortcuts add around the program (lsfg-vk's variables, or a
 * wrapper such as `~/lsfg`): the [words] as they write them, and the multiplier when they say one.
 * Never copied into Fuse's entries; Fuse offers its own Frame Generation setting instead.
 */
data class SteamFrameWrapper(val words: String, val multiplier: Int?)

/**
 * How the person's own shortcuts start one system's games, as [SteamLearn.habits] learns it from
 * them: the program Steam runs and the folder it starts in, and their launch options before and
 * after the game's file, each exactly as they write them (quotes, variables before `%command%`,
 * the way they write a path). Fuse's Direct entries for that system can start the same way.
 */
data class SteamHabit(
    /** The system, by the name its Steam collection has. */
    val system: String,
    /** Its program, by file name (`flatpak`, `retroarch.sh`), for saying so. */
    val program: String,
    /** The entry's `Exe`, as they write it. */
    val exe: String,
    /** Its `StartDir` as they write it; one inside an AppImage's temporary mount is the AppImage's own folder instead. */
    val startDir: String,
    /** Their launch options before the game's file. */
    val before: String,
    /** Their launch options after the game's file. */
    val after: String,
    val quoting: SteamQuoting,
    /** Where they start a path differently from Fuse: Fuse's start to theirs (`/home/` to `/var/home/`). */
    val paths: Map<String, String> = emptyMap(),
    /** Frame generation they add, set aside rather than copied. */
    val frameGeneration: SteamFrameWrapper? = null,
    /** How many of their shortcuts start the system's games this way. */
    val shortcuts: Int = 1,
) {
    /** The game at [path] (as Fuse has it) started this way: its `Exe`, `StartDir` and launch options. */
    fun forGame(path: String): SteamWritten {
        val theirs = paths.entries.firstOrNull { path.startsWith(it.key) }?.let { it.value + path.removePrefix(it.key) } ?: path
        val written = when (quoting) {
            SteamQuoting.DOUBLE -> "\"" + theirs.replace(Regex("""(["\\$`])"""), "\\\\$1") + "\""
            SteamQuoting.SINGLE -> "'" + theirs.replace("'", "'\\''") + "'"
            SteamQuoting.PLAIN -> theirs.replace(Regex("""([^A-Za-z0-9_@%+=:,./-])"""), "\\\\$1")
        }
        return SteamWritten(exe, startDir, before + written + after)
    }
}

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
            val hit = (listOf(program) + words(options)).firstNotNullOfOrNull { w -> gameOf(w, files)?.let { w to it.first } } ?: continue
            if (hit.second in fuses || out.any { it.gameId == hit.second }) continue
            val appId = ((b["appid"] as? BinaryVdf.Int32)?.value ?: SteamShortcuts.appId(exe, name)).toLong() and 0xFFFFFFFFL
            out += SteamLearned(appId, name, program.replace('\\', '/').substringAfterLast('/'), hit.second, hit.first)
        }
        return out
    }

    /**
     * The same places written another way, as systems write them: Fedora's atomic desktops
     * (Silverblue, Kinoite, Bazzite) have `/home` as a link to `/var/home`, and udisks2 mounts a
     * drive under `/run/media/<user>` on most systems and `/media/<user>` on Debian and Ubuntu.
     */
    private val SAME_PLACES = listOf("/var/home/" to "/home/", "/run/media/" to "/media/")

    /** The game [word] names, by Fuse's own path or the same path written another way, with Fuse's start and theirs where they differ. */
    private fun gameOf(word: String, files: Map<String, Long>): Pair<Long, Pair<String, String>?>? {
        files[word]?.let { return it to null }
        for ((a, b) in SAME_PLACES) {
            for ((theirs, fuse) in listOf(a to b, b to a)) {
                if (!word.startsWith(theirs)) continue
                files[fuse + word.removePrefix(theirs)]?.let { return it to (fuse to theirs) }
            }
        }
        return null
    }

    /**
     * How the person's own shortcuts (in each of [lists], Steam's `shortcuts.vdf` per account)
     * start each system's games, from those that play a game of Fuse's ([files], by its path):
     * per system ([systemOf] a game), the way most of them do it. Only a shortcut whose program
     * is a full path outside an AppImage's temporary mount and whose launch options name the
     * game's file is learned from; Fuse's own entries never are.
     */
    fun habits(lists: List<ByteArray?>, isFuse: (String) -> Boolean, files: Map<String, Long>, systemOf: (Long) -> String?): List<SteamHabit> {
        val seen = ArrayList<SteamHabit>()
        for (bytes in lists) {
            val list = bytes?.takeIf { it.isNotEmpty() }?.let(BinaryVdf::parse)?.get("shortcuts") as? BinaryVdf.Block ?: continue
            for ((_, v) in list.entries) {
                val b = v as? BinaryVdf.Block ?: continue
                learn(b, isFuse, files, systemOf)?.let(seen::add)
            }
        }
        return seen.groupBy { it.system }.map { (_, ways) ->
            val alike = ways.groupBy { it.copy(paths = emptyMap(), shortcuts = 1) }
            val (way, all) = alike.entries.maxByOrNull { it.value.size }!!
            way.copy(paths = all.flatMap { it.paths.entries }.associate { it.key to it.value }, shortcuts = all.size)
        }.sortedBy { it.system }
    }

    private fun learn(b: BinaryVdf.Block, isFuse: (String) -> Boolean, files: Map<String, Long>, systemOf: (Long) -> String?): SteamHabit? {
        val exe = (b["Exe"] as? BinaryVdf.Str)?.value.orEmpty()
        val startDir = (b["StartDir"] as? BinaryVdf.Str)?.value.orEmpty()
        val raw = (b["LaunchOptions"] as? BinaryVdf.Str)?.value.orEmpty()
        if (SteamGames.isOurs(exe, raw, isFuse)) return null
        val program = words(exe).firstOrNull()?.takeIf { it.startsWith("/") && MOUNT !in it } ?: return null
        // Frame generation before the program is set aside, with the space after it.
        val parts = spans(raw)
        val command = parts.indexOfFirst { it.word == COMMAND }
        val frame = if (command < 0) emptyList() else parts.take(command).filter(::isFrameGeneration)
        var options = raw
        for (f in frame.asReversed()) {
            var end = f.end
            while (end < options.length && options[end].isWhitespace()) end++
            options = options.removeRange(f.start, end)
        }
        val (game, gamePart, place) = spans(options).firstNotNullOfOrNull { s -> gameOf(s.word, files)?.let { Triple(it.first, s, it.second) } } ?: return null
        val system = systemOf(game) ?: return null
        val written = options.substring(gamePart.start, gamePart.end)
        val quoting = when {
            written.length >= 2 && written.startsWith('"') && written.endsWith('"') -> SteamQuoting.DOUBLE
            written.length >= 2 && written.startsWith('\'') && written.endsWith('\'') -> SteamQuoting.SINGLE
            else -> SteamQuoting.PLAIN
        }
        // A start folder inside an AppImage's mount is gone once it closes: the AppImage's own folder instead.
        val folder = if (MOUNT in startDir) {
            val parent = program.substringBeforeLast('/').ifEmpty { "/" }
            if (startDir.startsWith('"')) "\"$parent\"" else parent
        } else startDir
        val wrapper = frame.takeIf { it.isNotEmpty() }?.let { f ->
            SteamFrameWrapper(f.joinToString(" ") { raw.substring(it.start, it.end) }, f.firstNotNullOfOrNull { MULTIPLIER.matchEntire(it.word)?.groupValues?.get(1)?.toIntOrNull() })
        }
        return SteamHabit(
            system, program.substringAfterLast('/'), exe, folder,
            options.substring(0, gamePart.start), options.substring(gamePart.end), quoting,
            paths = listOfNotNull(place).toMap(), frameGeneration = wrapper,
        )
    }

    private const val COMMAND = "%command%"

    /** Where an AppImage's files are while it runs (its runtime mounts it at `/tmp/.mount_<name><random>`). */
    private const val MOUNT = "/tmp/.mount_"
    private val MULTIPLIER = Regex("""(?i)LSFG(?:VK)?_MULTIPLIER=(\d+)""")

    /**
     * lsfg-vk's own variables (`LSFGVK_ENV`, `LSFGVK_MULTIPLIER`, and `LSFG_` ones of its earlier
     * releases), or a program whose name says lsfg (a wrapper script such as `~/lsfg`).
     */
    private fun isFrameGeneration(s: Part): Boolean {
        val name = s.word.substringBefore('=', "")
        return if (name.isNotEmpty() && name.all { it.isLetterOrDigit() || it == '_' }) "LSFG" in name.uppercase() else "lsfg" in s.word.substringAfterLast('/').lowercase()
    }

    /** One word of a launch line, and where it is written in it (its quotes included). */
    private class Part(val word: String, val start: Int, val end: Int)

    /** [line]'s words with where each is written, split as [words] splits them. */
    private fun spans(line: String): List<Part> {
        val out = ArrayList<Part>()
        var i = 0
        while (i < line.length) {
            while (i < line.length && line[i].isWhitespace()) i++
            if (i >= line.length) break
            val start = i
            while (i < line.length && !line[i].isWhitespace()) {
                when (line[i]) {
                    '\'' -> i = line.indexOf('\'', i + 1).let { if (it < 0) line.length else it } + 1
                    '"' -> {
                        i++
                        while (i < line.length && line[i] != '"') i += if (line[i] == '\\' && i + 1 < line.length) 2 else 1
                        i++
                    }
                    '\\' -> i += 2
                    else -> i++
                }
            }
            val end = i.coerceAtMost(line.length)
            out += Part(words(line.substring(start, end)).firstOrNull().orEmpty(), start, end)
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
