package io.github.matiyaaa.fuse.library.steam

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * A game of Fuse's for Steam's library. Its entry runs Fuse Fast Start (`--run <id>`) by default:
 * the game starts the way it does from Fuse (its emulator, its settings, its saves kept in step and
 * its time counted) with no window of Fuse's, and Fuse leaves when it ends. With [direct], the entry
 * starts the emulator itself, with nothing between Steam and the game. [collection] is the Steam
 * collection it goes in (its system).
 */
data class SteamGameEntry(
    val gameId: Long,
    val name: String,
    val collection: String? = null,
    val direct: SteamDirect? = null,
    /** Names Fuse gave its system's collection before, taken off the entry's tags so that collection goes. */
    val formerCollections: Set<String> = emptySet(),
)

/**
 * The emulator's own command for a Direct entry: what Play runs for the game, from its launch plan
 * ([argv], the program first; the folder it starts in; the variables it is given). Nothing of
 * Fuse's runs then: no save put in place or sent, no time counted, no profile followed.
 */
data class SteamDirect(
    val argv: List<String>,
    val workingDir: String? = null,
    val env: Map<String, String> = emptyMap(),
    /** The entry as the person's own shortcuts start its system's games ([SteamHabit]), in place of [argv] and [workingDir]. */
    val written: SteamWritten? = null,
)

/**
 * A Direct entry written as the person's own shortcuts write theirs: its `Exe` and `StartDir`
 * exactly as they have them, and their launch options for this game (Fuse's mark goes before them).
 */
data class SteamWritten(val exe: String, val startDir: String, val options: String)

/** How one of Fuse's entries in Steam starts its game. */
enum class SteamStart {
    /** Fuse Fast Start: Fuse with no window, as Play starts it. */
    FAST_START,

    /** An older entry: Fuse's window opens and plays it (`--play <id>`). It still works. */
    THROUGH_WINDOW,

    /** The emulator itself, straight from Steam. */
    DIRECT,
}

/** What adding games to Steam would do to one of them. */
enum class SteamGameAction {
    /** A new entry. */
    CREATE,

    /** Fuse's entry for it is there; its program, name or way of starting moves on, the person's changes stay. */
    UPDATE,

    /** Fuse's entry for it is there and right as it is. */
    UNCHANGED,

    /** Another tool (Steam ROM Manager, EmuDeck, the person) has an entry by this name: left alone, never doubled. */
    ELSEWHERE,
}

data class SteamGamePlanItem(val entry: SteamGameEntry, val action: SteamGameAction)

/**
 * A game's pictures for Steam, as files Fuse already keeps: the portrait capsule (box art), the wide
 * capsule, the hero banner, the logo over it and the icon. Missing ones are left to Steam.
 */
data class SteamArt(
    val portrait: String? = null,
    val wide: String? = null,
    val hero: String? = null,
    val logo: String? = null,
    val icon: String? = null,
)

/**
 * The shape Steam's library shows each picture in, from Steamworks' documentation: Library Assets
 * gives the library capsule as 600 by 900 and the library hero as 3840 by 1240, Store Graphical
 * Assets the header capsule as 460 by 215; an icon is square, and a logo has no shape of its own. A
 * picture far from its shape would be cropped or stretched there, so it isn't used and Steam's own
 * stays: [fits] allows 15% either way (10% for an icon), so a 16:9 screenshot is no header.
 */
object SteamArtShape {
    /** Width over height, by [SteamArt]'s kinds; none for the logo. */
    private val SHAPES = mapOf("portrait" to 600.0 / 900, "wide" to 460.0 / 215, "hero" to 3840.0 / 1240, "icon" to 1.0)

    fun fits(kind: String, width: Int, height: Int): Boolean {
        if (width <= 0 || height <= 0) return false
        val shape = SHAPES[kind] ?: return true
        val off = (width.toDouble() / height) / shape - 1
        return kotlin.math.abs(off) <= if (kind == "icon") 0.1 else 0.15
    }
}

/** Something wrong with an entry Fuse made, found by [SteamGames.health]. */
data class SteamHealthIssue(val gameId: Long, val appId: Long, val name: String, val kind: Kind) {
    enum class Kind {
        /** The game is no longer in Fuse's library. */
        GAME_GONE,

        /** The entry starts a Fuse that isn't here any more (an old AppImage). */
        PROGRAM_MOVED,

        /** Two of Fuse's entries start the same game. */
        DUPLICATE,

        /** An older entry that opens Fuse's window first, where Fast Start would start the game at once. */
        THROUGH_WINDOW,

        /** A Direct entry whose emulator isn't where the entry starts it any more. */
        EMULATOR_GONE,

        /**
         * A Direct entry that no longer starts the game as Play would: its emulator updated or moved,
         * its game moved to another drive, or its way of starting changed.
         */
        OUT_OF_DATE,

        /** The game's files can't be reached now (their drive or card is out): it can't start until they're back. */
        GAME_UNAVAILABLE,
    }
}

/**
 * Fuse's own game entries in Steam's `shortcuts.vdf`: planned before anything is written, written
 * so that running it again changes nothing, and taken out again by their game. An entry is Fuse's
 * only by what it starts: Fuse with `--run <id>` (or an older `--play <id>`), or, for a Direct
 * entry, a program whose launch options carry Fuse's mark, `FUSE_GAME=<id>` before `%command%`; a
 * name alone never makes an entry Fuse's. Entries anyone else made are never changed, and a game
 * someone else already put in Steam is never added twice.
 *
 * A Direct entry's launch options set Fuse's mark (and the variables the game's plan sets) as Steam
 * documents for launch options: "Set the variable, followed by `%command%`" (ValveSoftware/Proton,
 * README.md, Runtime Config Options); Steam puts the entry's program where `%command%` is. Steam
 * starts that line through the system's shell, which is why Direct entries are made on Linux only,
 * and the emulator's own arguments follow, quoted as a POSIX shell reads them.
 */
object SteamGames {
    private val FUSE = Regex("""(?:^|\s)--(?:play|run)\s+(\d+)(?:\s|$)""")
    private val RUN = Regex("""(?:^|\s)--run\s+(\d+)(?:\s|$)""")
    private val MARK = Regex("""(?:^|\s)FUSE_GAME=(\d+)(?:\s|$)""")
    private const val COMMAND = "%command%"
    private val NAME = Regex("[A-Za-z_][A-Za-z0-9_]*")
    private val PLAIN = Regex("""[A-Za-z0-9_@%+=:,./-]+""")

    /** The Fuse game an entry that starts Fuse plays, from its launch options; null for any other. */
    fun playId(launchOptions: String): Long? = FUSE.find(launchOptions)?.groupValues?.get(1)?.toLongOrNull()

    /** The Fuse game a Direct entry plays, from Fuse's mark in its launch options; null for any other. */
    fun directId(launchOptions: String): Long? =
        if (COMMAND in launchOptions) MARK.find(launchOptions.substringBefore(COMMAND))?.groupValues?.get(1)?.toLongOrNull() else null

    /** Fast Start's launch options for [gameId]. */
    fun launchOptions(gameId: Long): String = "--run $gameId"

    /** Whether [direct] can be written as a Direct entry: a program, and variables a shell can set. */
    fun canWrite(direct: SteamDirect): Boolean =
        (direct.written?.exe?.isNotBlank() ?: (direct.argv.firstOrNull()?.isNotBlank() == true)) && direct.env.keys.all { NAME.matches(it) }

    /**
     * A Direct entry's launch options: Fuse's mark and the plan's variables, then `%command%` (the
     * entry's program, which is the emulator), then the emulator's arguments.
     */
    fun directOptions(gameId: Long, direct: SteamDirect): String = buildList {
        add("FUSE_GAME=$gameId")
        direct.env.forEach { (k, v) -> add("$k=${shell(v)}") }
        val written = direct.written
        if (written == null) {
            add(COMMAND)
            direct.argv.drop(1).forEach { add(shell(it)) }
        } else {
            // Their options as they write them; Steam puts the program after them when they don't say where.
            add(if (COMMAND in written.options) written.options else "$COMMAND ${written.options}".trimEnd())
        }
    }.joinToString(" ")

    /** [arg] as a POSIX shell reads it back: as it is when plain, else in single quotes. */
    internal fun shell(arg: String): String = if (arg.isNotEmpty() && PLAIN.matches(arg)) arg else "'" + arg.replace("'", "'\\''") + "'"

    private class Found(val index: String, val block: BinaryVdf.Block, val exe: String, val name: String, val options: String) {
        val startDir: String get() = (block["StartDir"] as? BinaryVdf.Str)?.value.orEmpty()
    }

    /** The game [e] is Fuse's entry for, and how it starts it; null when it isn't one of Fuse's. */
    private fun ours(e: Found, isFuse: (String) -> Boolean): Pair<Long, SteamStart>? {
        if (isFuse(e.exe.trim('"'))) {
            val id = playId(e.options) ?: return null
            return id to if (RUN.containsMatchIn(e.options)) SteamStart.FAST_START else SteamStart.THROUGH_WINDOW
        }
        return directId(e.options)?.let { it to SteamStart.DIRECT }
    }

    /** Whether a shortcut that runs [exe] with [launchOptions] is one of Fuse's game entries. */
    fun isOurs(exe: String, launchOptions: String, isFuse: (String) -> Boolean): Boolean =
        ours(Found("", BinaryVdf.Block(), exe, "", launchOptions), isFuse) != null

    private fun entries(root: BinaryVdf.Block?): List<Found> {
        val list = root?.get("shortcuts") as? BinaryVdf.Block ?: return emptyList()
        return list.entries.mapNotNull { (k, v) -> (v as? BinaryVdf.Block)?.let { found(k, it) } }
    }

    private fun found(index: String, b: BinaryVdf.Block) = Found(
        index, b,
        (b["Exe"] as? BinaryVdf.Str)?.value.orEmpty(),
        (b["AppName"] as? BinaryVdf.Str)?.value.orEmpty(),
        (b["LaunchOptions"] as? BinaryVdf.Str)?.value.orEmpty(),
    )

    /** The game list entry [v] is Fuse's entry for; null for anyone else's. */
    private fun gameOf(index: String, v: BinaryVdf.Value, isFuse: (String) -> Boolean): Long? = (v as? BinaryVdf.Block)?.let { ours(found(index, it), isFuse)?.first }

    /** A name as entries are compared by: case, spaces and punctuation aside. */
    private fun key(name: String) = name.lowercase().filter { it.isLetterOrDigit() }

    /** The program [game]'s entry starts: the emulator for a Direct entry, else Fuse. */
    private fun program(fuseExe: String, game: SteamGameEntry): String = game.direct?.argv?.first() ?: fuseExe

    /** [game]'s entry's `Exe` as written: the person's own for a Direct entry written their way, else its program in quotes. */
    private fun exeOf(fuseExe: String, game: SteamGameEntry): String = game.direct?.written?.exe ?: quote(program(fuseExe, game))

    /** [game]'s entry's `StartDir` as written. */
    private fun startDirOf(fuseExe: String, game: SteamGameEntry): String =
        game.direct?.written?.startDir ?: quote(game.direct?.workingDir ?: parentOf(program(fuseExe, game)))

    /** Whether [have] already starts [game] exactly as an entry written now would. */
    private fun upToDate(have: Found, start: SteamStart, fuseExe: String, game: SteamGameEntry): Boolean {
        val d = game.direct
        return if (d == null) {
            start == SteamStart.FAST_START && have.exe == quote(fuseExe)
        } else {
            start == SteamStart.DIRECT && have.exe == exeOf(fuseExe, game) && have.options == directOptions(game.gameId, d) &&
                have.startDir == startDirOf(fuseExe, game)
        }
    }

    /**
     * What writing [games] into [existing] would do, game by game, without writing anything. [isFuse]
     * says whether an entry's program (quotes taken off) is a Fuse. Null when the file can't be read.
     */
    fun plan(existing: ByteArray?, fuseExe: String, games: List<SteamGameEntry>, isFuse: (String) -> Boolean): List<SteamGamePlanItem>? {
        val root = parse(existing) ?: return null
        val all = entries(root).map { it to ours(it, isFuse) }
        val mine = all.mapNotNull { (e, o) -> o?.let { Triple(it.first, e, it.second) } }.groupBy { it.first }
        val others = all.filter { it.second == null }.map { key(it.first.name) }.toSet()
        return games.distinctBy { it.gameId }.map { g ->
            val have = mine[g.gameId]?.firstOrNull()
            val action = when {
                have != null -> if (upToDate(have.second, have.third, fuseExe, g)) SteamGameAction.UNCHANGED else SteamGameAction.UPDATE
                key(g.name) in others -> SteamGameAction.ELSEWHERE
                else -> SteamGameAction.CREATE
            }
            SteamGamePlanItem(g, action)
        }
    }

    /**
     * [existing] with [games] in it: new entries for games without one, Fuse's entries brought up to
     * date (the program that starts the game now, and the way it starts it), games another tool
     * already put in Steam left out. The person's changes to an entry stay: its name, icon and tags,
     * and on a Fast Start entry the launch options they added beside `--run`. A Direct entry's
     * launch options are Fuse's, written again whenever the game's command changes. [icons] gives a
     * game's icon file, when there is one. Null when the file can't be read.
     */
    fun write(existing: ByteArray?, fuseExe: String, games: List<SteamGameEntry>, isFuse: (String) -> Boolean, icons: (Long) -> String = { "" }): ByteArray? {
        val plan = plan(existing, fuseExe, games, isFuse) ?: return null
        val root = parse(existing) ?: return null
        val list = root["shortcuts"] as? BinaryVdf.Block ?: BinaryVdf.Block().also { root["shortcuts"] = it }
        for (item in plan) {
            if (item.action == SteamGameAction.ELSEWHERE || item.action == SteamGameAction.UNCHANGED) continue
            val g = item.entry
            val d = g.direct
            val exe = exeOf(fuseExe, g)
            val have = entries(root).firstOrNull { ours(it, isFuse)?.first == g.gameId }
            val entry = have?.block ?: BinaryVdf.Block().also { b ->
                val next = (list.entries.mapNotNull { it.first.toIntOrNull() }.maxOrNull() ?: -1) + 1
                list.entries += next.toString() to b
            }
            val fresh = have == null
            fun keep(key: String, value: String) {
                val now = (entry[key] as? BinaryVdf.Str)?.value
                if (fresh || now.isNullOrEmpty()) entry[key] = BinaryVdf.Str(value)
            }
            // An entry keeps its id (and so its art in Steam) when it changes how it starts.
            entry["appid"] = entry["appid"] ?: BinaryVdf.Int32(SteamShortcuts.appId(exe, g.name))
            keep("AppName", g.name)
            entry["Exe"] = BinaryVdf.Str(exe)
            entry["StartDir"] = BinaryVdf.Str(startDirOf(fuseExe, g))
            keep("icon", icons(g.gameId))
            entry["ShortcutPath"] = entry["ShortcutPath"] ?: BinaryVdf.Str("")
            val options = (entry["LaunchOptions"] as? BinaryVdf.Str)?.value
            entry["LaunchOptions"] = BinaryVdf.Str(
                when {
                    d != null -> directOptions(g.gameId, d)
                    // The person's own launch options stay, as long as they still start this game through Fuse.
                    have != null && options != null && isFuse(have.exe.trim('"')) && playId(options) == g.gameId -> fastStart(options, g.gameId)
                    else -> launchOptions(g.gameId)
                },
            )
            entry["IsHidden"] = entry["IsHidden"] ?: BinaryVdf.Int32(0)
            entry["AllowDesktopConfig"] = entry["AllowDesktopConfig"] ?: BinaryVdf.Int32(1)
            entry["AllowOverlay"] = entry["AllowOverlay"] ?: BinaryVdf.Int32(1)
            entry["OpenVR"] = entry["OpenVR"] ?: BinaryVdf.Int32(0)
            entry["Devkit"] = entry["Devkit"] ?: BinaryVdf.Int32(0)
            entry["DevkitGameID"] = entry["DevkitGameID"] ?: BinaryVdf.Str("")
            entry["DevkitOverrideAppID"] = entry["DevkitOverrideAppID"] ?: BinaryVdf.Int32(0)
            entry["LastPlayTime"] = entry["LastPlayTime"] ?: BinaryVdf.Int32(0)
            entry["FlatpakAppID"] = entry["FlatpakAppID"] ?: BinaryVdf.Str("")
            val had = (entry["tags"] as? BinaryVdf.Block)?.entries?.mapNotNull { (it.second as? BinaryVdf.Str)?.value }.orEmpty() - g.formerCollections
            val tags = BinaryVdf.Block()
            (had + listOfNotNull(g.collection).filter { it !in had }).forEachIndexed { i, t -> tags.entries += i.toString() to BinaryVdf.Str(t) }
            entry["tags"] = tags
        }
        // A system's collection under its new name on every entry of Fuse's in it, not only those written now.
        val renames = games.flatMap { g -> g.collection?.let { c -> g.formerCollections.map { it to c } }.orEmpty() }.toMap()
        if (renames.isNotEmpty()) {
            for (e in entries(root)) {
                if (ours(e, isFuse) == null) continue
                val had = (e.block["tags"] as? BinaryVdf.Block)?.entries?.mapNotNull { (it.second as? BinaryVdf.Str)?.value } ?: continue
                val now = had.map { renames[it] ?: it }.distinct()
                if (now == had) continue
                e.block["tags"] = BinaryVdf.Block().also { b -> now.forEachIndexed { i, t -> b.entries += i.toString() to BinaryVdf.Str(t) } }
            }
        }
        return BinaryVdf.write(root)
    }

    /**
     * [existing] with shortcuts another tool made for Fuse's games ([takes], by the id Steam files
     * their art under, as [SteamLearn] finds them) made Fuse's: each becomes the entry [write] would
     * make for its game, in the same place, keeping its id (so Steam's art for it stays), its name,
     * icon and tags, and joining its system's collection. Fuse's own entries and every shortcut not
     * in [takes] stay as they are. Null when the file can't be read; the same bytes when nothing changed.
     */
    fun takeOver(existing: ByteArray?, fuseExe: String, takes: Map<Long, SteamGameEntry>, isFuse: (String) -> Boolean): ByteArray? {
        if (existing == null || existing.isEmpty() || takes.isEmpty()) return existing
        val root = BinaryVdf.parse(existing) ?: return null
        val list = root["shortcuts"] as? BinaryVdf.Block ?: return existing
        var changed = false
        for ((k, v) in list.entries) {
            val b = v as? BinaryVdf.Block ?: continue
            val e = found(k, b)
            if (ours(e, isFuse) != null) continue
            val id = artId(e)
            val g = takes[id] ?: continue
            val d = g.direct
            // Its id written out, so a new program doesn't give it a new one (and lose its art).
            b["appid"] = BinaryVdf.Int32(id.toInt())
            b["Exe"] = BinaryVdf.Str(exeOf(fuseExe, g))
            b["StartDir"] = BinaryVdf.Str(startDirOf(fuseExe, g))
            b["LaunchOptions"] = BinaryVdf.Str(if (d != null) directOptions(g.gameId, d) else launchOptions(g.gameId))
            val had = (b["tags"] as? BinaryVdf.Block)?.entries?.mapNotNull { (it.second as? BinaryVdf.Str)?.value }.orEmpty()
            if (g.collection != null && g.collection !in had) {
                val tags = BinaryVdf.Block()
                (had + g.collection).forEachIndexed { i, t -> tags.entries += i.toString() to BinaryVdf.Str(t) }
                b["tags"] = tags
            }
            changed = true
        }
        return if (changed) BinaryVdf.write(root) else existing
    }

    /**
     * How Fuse's entry for [game] starts once written into [existing], as [write] sets it: its
     * program and folder (quoted, as Steam keeps them) and its launch options, the person's own
     * beside `--run` kept on a Fast Start entry already there. For changes made through Steam's
     * running client, which must match what a write would make.
     */
    fun startOf(existing: ByteArray?, fuseExe: String, game: SteamGameEntry, isFuse: (String) -> Boolean): Triple<String, String, String> {
        val d = game.direct
        val have = entries(parse(existing)).firstOrNull { ours(it, isFuse)?.first == game.gameId }
        val options = when {
            d != null -> directOptions(game.gameId, d)
            have != null && isFuse(have.exe.trim('"')) && playId(have.options) == game.gameId -> fastStart(have.options, game.gameId)
            else -> launchOptions(game.gameId)
        }
        return Triple(exeOf(fuseExe, game), startDirOf(fuseExe, game), options)
    }

    /** [options] (which start [gameId] through Fuse) starting it with Fast Start, the rest as it was. */
    private fun fastStart(options: String, gameId: Long): String =
        if (RUN.containsMatchIn(options)) options else options.replace(Regex("""(^|\s)--play(\s+$gameId)(?=\s|$)"""), "$1--run$2")

    /**
     * [existing] without Fuse's entries for [gameIds] (null: every game Fuse put there), the rest
     * numbered again as Steam numbers them. Fuse's own entry and everyone else's stay. Null when the
     * file can't be read; the same bytes when nothing was taken out.
     */
    fun remove(existing: ByteArray?, gameIds: Set<Long>?, isFuse: (String) -> Boolean): ByteArray? {
        if (existing == null || existing.isEmpty()) return existing
        val root = BinaryVdf.parse(existing) ?: return null
        val list = root["shortcuts"] as? BinaryVdf.Block ?: return existing
        val kept = list.entries.filterNot { (k, v) ->
            val id = gameOf(k, v, isFuse) ?: return@filterNot false
            gameIds == null || id in gameIds
        }
        if (kept.size == list.entries.size) return existing
        list.entries.clear()
        kept.forEachIndexed { i, (_, v) -> list.entries += i.toString() to v }
        return BinaryVdf.write(root)
    }

    /**
     * [existing] put right: Fuse's entries for games no longer in [library] taken out, and a game's
     * second entry (the first, with whatever the person changed on it, stays). Entries that start an
     * older Fuse, or start their game an older way, are brought up to date by writing their games
     * again ([write]). Everyone else's entries are never touched. Null when the file can't be read.
     */
    fun repair(existing: ByteArray?, library: Set<Long>, isFuse: (String) -> Boolean): ByteArray? {
        if (existing == null || existing.isEmpty()) return existing
        val root = BinaryVdf.parse(existing) ?: return null
        val list = root["shortcuts"] as? BinaryVdf.Block ?: return existing
        val seen = HashSet<Long>()
        val kept = list.entries.filter { (k, v) ->
            val id = gameOf(k, v, isFuse) ?: return@filter true
            id in library && seen.add(id)
        }
        if (kept.size == list.entries.size) return existing
        list.entries.clear()
        kept.forEachIndexed { i, (_, v) -> list.entries += i.toString() to v }
        return BinaryVdf.write(root)
    }

    /** The Fuse games [bytes] has entries for, with each entry's id as Steam names its art files (unsigned). */
    fun inSteam(bytes: ByteArray?, isFuse: (String) -> Boolean): Map<Long, Long> =
        entries(parse(bytes)).mapNotNull { e -> ours(e, isFuse)?.let { it.first to artId(e) } }.toMap()

    /** Fuse's Direct entries in [bytes]: each entry's id as Steam knows it (unsigned 32-bit) to its game. */
    fun directIn(bytes: ByteArray?, isFuse: (String) -> Boolean): Map<Long, Long> =
        entries(parse(bytes)).mapNotNull { e -> ours(e, isFuse)?.takeIf { it.second == SteamStart.DIRECT }?.let { artId(e) to it.first } }.toMap()

    /** How each Fuse game [bytes] has an entry for starts (its first entry, when there are two). */
    fun starts(bytes: ByteArray?, isFuse: (String) -> Boolean): Map<Long, SteamStart> {
        val out = LinkedHashMap<Long, SteamStart>()
        for (e in entries(parse(bytes))) ours(e, isFuse)?.let { (id, start) -> out.getOrPut(id) { start } }
        return out
    }

    /**
     * What is wrong with the entries Fuse made in [bytes]: a game no longer in [library], the same
     * game twice, a game whose files can't be reached now ([unavailable]), a program that isn't
     * [fuseExe] any more, an older entry that opens Fuse's window first, a Direct entry whose
     * emulator [exists] no longer finds, and one that doesn't start its game as [wanted] (the entry
     * each game would get written now) says.
     */
    fun health(
        bytes: ByteArray?,
        fuseExe: String,
        library: Set<Long>,
        isFuse: (String) -> Boolean,
        exists: (String) -> Boolean = { true },
        wanted: Map<Long, SteamGameEntry> = emptyMap(),
        unavailable: Set<Long> = emptySet(),
    ): List<SteamHealthIssue> {
        val out = ArrayList<SteamHealthIssue>()
        val seen = HashSet<Long>()
        for (e in entries(parse(bytes))) {
            val (id, start) = ours(e, isFuse) ?: continue
            val art = artId(e)
            val kind = when {
                id !in seen && id !in library -> SteamHealthIssue.Kind.GAME_GONE
                id in seen -> SteamHealthIssue.Kind.DUPLICATE
                id in unavailable -> SteamHealthIssue.Kind.GAME_UNAVAILABLE
                start == SteamStart.DIRECT -> when {
                    !exists(SteamLearn.words(e.exe).firstOrNull().orEmpty()) -> SteamHealthIssue.Kind.EMULATOR_GONE
                    wanted[id]?.let { !upToDate(e, start, fuseExe, it) } == true -> SteamHealthIssue.Kind.OUT_OF_DATE
                    else -> null
                }
                e.exe != quote(fuseExe) -> SteamHealthIssue.Kind.PROGRAM_MOVED
                start == SteamStart.THROUGH_WINDOW -> SteamHealthIssue.Kind.THROUGH_WINDOW
                else -> null
            }
            kind?.let { out += SteamHealthIssue(id, art, e.name, it) }
            seen += id
        }
        return out
    }

    /**
     * Fuse's games in [bytes] by the collections [names] they are tagged with: each name to the
     * entries' ids, as Steam's collections list apps (unsigned 32-bit).
     */
    fun byCollection(bytes: ByteArray?, names: Set<String>, isFuse: (String) -> Boolean): Map<String, List<Long>> {
        val out = LinkedHashMap<String, MutableList<Long>>()
        for (n in names) out[n] = mutableListOf()
        for (e in entries(parse(bytes))) {
            if (ours(e, isFuse) == null) continue
            val tags = (e.block["tags"] as? BinaryVdf.Block)?.entries?.mapNotNull { (it.second as? BinaryVdf.Str)?.value }.orEmpty()
            for (t in tags) out[t]?.add(artId(e))
        }
        return out
    }

    /** The art id Steam uses for [e]: the one it was given, else the one Steam works out. */
    private fun artId(e: Found): Long = ((e.block["appid"] as? BinaryVdf.Int32)?.value ?: SteamShortcuts.appId(e.exe, e.name)).toLong() and 0xFFFFFFFFL

    /** The art id a new entry for [game] gets, before it is written. */
    fun newArtId(fuseExe: String, game: SteamGameEntry): Long = SteamShortcuts.appId(exeOf(fuseExe, game), game.name).toLong() and 0xFFFFFFFFL

    private fun parse(bytes: ByteArray?): BinaryVdf.Block? = if (bytes == null || bytes.isEmpty()) BinaryVdf.Block() else BinaryVdf.parse(bytes)

    private fun quote(path: String) = "\"$path\""

    private fun parentOf(path: String): String = path.replace('\\', '/').substringBeforeLast('/', ".").let { p ->
        // Keep the system's own separators for Windows paths.
        if ('\\' in path) p.replace('/', '\\') else p
    }
}

/** One of the person's own collections in Steam (not one of Fuse's): its id, its name and the apps in it. */
data class SteamCollection(val id: String, val name: String, val apps: List<Long>)

/** How Fuse's collection for a system and the person's own for it live together. */
enum class SteamCollectionWay {
    /** Both, apart. */
    APART,

    /** Fuse's games go into theirs, and Fuse keeps none of its own for that system. */
    INTO,

    /** As [INTO], with theirs given Fuse's name. */
    RENAMED,
}

/**
 * What the person chose for their own collections beside Fuse's, and the collections Fuse has
 * written, kept beside Steam's own files (`fuse-collections.json` in the user's `config` folder) so
 * every writer, Fuse or its helper while Steam restarts, follows them.
 */
@kotlinx.serialization.Serializable
data class SteamCollectionChoices(
    /** Fuse's collection, by name, to the id of the person's collection its games go into instead. */
    val into: Map<String, String> = emptyMap(),
    /** The person's collection, by id, to the name they chose to give it in Fuse's way. */
    val renamed: Map<String, String> = emptyMap(),
    /** The person's collections, by id, they chose to keep apart from Fuse's. */
    val kept: Set<String> = emptySet(),
    /** Fuse's own collections it has written, so one Steam Cloud dropped is told from one the person deleted. */
    val written: Set<String> = emptySet(),
    /** Fuse's own collections to make again at the next write: kept apart once more after their games went into the person's. */
    val back: Set<String> = emptySet(),
)

/**
 * Steam's collections, as Steam keeps them for each user in `config/cloudstorage`: a JSON list of
 * `[key, record]` pairs, a collection being `user-collections.<id>` whose `value` is the collection
 * as JSON (`id`, `name`, `added`: the apps' ids). Steam must be closed while this is written, as it
 * writes the file when it quits. Fuse's own collections (ids starting `fuse-`) are Fuse's to change;
 * a collection of the person's changes only as they chose ([SteamCollectionChoices]: Fuse's games
 * put into it, its name), and never goes. Every other record is kept exactly, and a Fuse collection
 * no longer wanted is marked deleted rather than dropped, as Steam does.
 */
object SteamCollections {
    const val PREFIX = "fuse-"

    private val json = Json { ignoreUnknownKeys = true }

    /** The id Fuse gives the collection named [name]. */
    fun idOf(name: String): String = PREFIX + name.encodeToByteArray().joinToString("") { ((it.toInt() and 0xFF) + 0x100).toString(16).substring(1) }

    /**
     * A system's collection name, maker then console ("Nintendo Game Boy Advance"), or the console's
     * alone where it already starts with its maker ("Sega Saturn") or the maker isn't known.
     */
    fun nameFor(maker: String?, console: String): String =
        if (maker.isNullOrBlank() || console.startsWith(maker, ignoreCase = true)) console else "$maker $console"

    /** A name as collections are matched by: letter case, spaces and punctuation aside. */
    fun key(name: String): String = name.lowercase().filter { it.isLetterOrDigit() }

    /** The person's own collections in [text] (not Fuse's, not deleted), in the file's order. */
    fun theirs(text: String?): List<SteamCollection> {
        val list = text?.let { runCatching { json.parseToJsonElement(it).jsonArray }.getOrNull() } ?: return emptyList()
        return list.mapNotNull { e ->
            val pair = e as? JsonArray ?: return@mapNotNull null
            val key = pair.getOrNull(0)?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            if (!key.startsWith("user-collections.") || key.startsWith("user-collections.$PREFIX")) return@mapNotNull null
            val rec = pair.getOrNull(1) as? JsonObject ?: return@mapNotNull null
            if (rec["is_deleted"]?.jsonPrimitive?.booleanOrNull == true) return@mapNotNull null
            val value = rec["value"]?.jsonPrimitive?.contentOrNull?.let { runCatching { json.parseToJsonElement(it).jsonObject }.getOrNull() } ?: return@mapNotNull null
            val name = value["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val apps = (value["added"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull?.toLongOrNull() }.orEmpty()
            SteamCollection(key.removePrefix("user-collections."), name, apps)
        }
    }

    /**
     * Fuse's collections it wrote ([written]) that aren't in [text] at all any more: Steam Cloud
     * dropped them (a collection the person deleted is still there, marked deleted). One whose games
     * now go into a collection of the person's is never missing.
     */
    fun dropped(text: String?, choices: SteamCollectionChoices): Set<String> {
        val list = text?.let { runCatching { json.parseToJsonElement(it).jsonArray }.getOrNull() } ?: return emptySet()
        val keys = list.mapNotNull { (it as? JsonArray)?.getOrNull(0)?.jsonPrimitive?.contentOrNull }.toSet()
        return choices.written.filter { it !in choices.into && "user-collections.${idOf(it)}" !in keys }.toSet()
    }

    /**
     * [text] (the namespace file, or null for none yet) with Fuse's collections set to [wanted]
     * (name to the apps' 32-bit ids), as [choices] says: a name whose games go into a collection of
     * the person's that is still there puts them there (beside theirs, never taking any out) under
     * the name they chose, and Fuse keeps none of its own for it. [now] is in seconds. Apps the person
     * took out of a collection in Steam stay out. Null when [text] isn't Steam's list.
     */
    fun write(text: String?, wanted: Map<String, List<Long>>, now: Long, choices: SteamCollectionChoices = SteamCollectionChoices()): String? {
        val list: MutableList<JsonElement> = if (text.isNullOrBlank()) mutableListOf() else runCatching { json.parseToJsonElement(text).jsonArray.toMutableList() }.getOrNull() ?: return null
        val index = HashMap<String, Int>()
        list.forEachIndexed { i, e -> (e as? JsonArray)?.getOrNull(0)?.jsonPrimitive?.contentOrNull?.let { index[it] = i } }
        val keep = HashSet<String>()
        fun record(key: String, value: JsonObject) = JsonArray(
            listOf(
                JsonPrimitive(key),
                JsonObject(
                    mapOf(
                        "key" to JsonPrimitive(key),
                        "timestamp" to JsonPrimitive(now),
                        "value" to JsonPrimitive(value.toString()),
                        "version" to JsonPrimitive(now.toString()),
                        "conflictResolutionMethod" to JsonPrimitive("custom"),
                        "strMethodId" to JsonPrimitive("union-collections"),
                    ),
                ),
            ),
        )
        fun liveValue(key: String): JsonObject? = index[key]?.let { list[it] as? JsonArray }?.getOrNull(1)?.let { it as? JsonObject }
            ?.takeIf { it["is_deleted"]?.jsonPrimitive?.booleanOrNull != true }?.get("value")?.jsonPrimitive?.contentOrNull
            ?.let { runCatching { json.parseToJsonElement(it).jsonObject }.getOrNull() }
        fun longs(value: JsonObject?, field: String) = (value?.get(field) as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull?.toLongOrNull() }.orEmpty()
        // The person's collections Fuse's games go into, under the name they chose; theirs are never taken out.
        val into = wanted.keys.mapNotNull { name -> choices.into[name]?.let { theirs -> liveValue("user-collections.$theirs")?.let { name to theirs } } }.toMap()
        for ((theirs, names) in into.entries.groupBy({ it.value }, { it.key })) {
            val key = "user-collections.$theirs"
            val old = liveValue(key) ?: continue
            val removed = longs(old, "removed").toSet()
            val had = longs(old, "added")
            val added = (had + names.flatMap { wanted.getValue(it) }.filter { it !in removed && it !in had }).distinct()
            val name = choices.renamed[theirs] ?: old["name"]?.jsonPrimitive?.contentOrNull ?: continue
            if (added == had && name == old["name"]?.jsonPrimitive?.contentOrNull) continue
            list[index.getValue(key)] = record(key, JsonObject(old + mapOf("name" to JsonPrimitive(name), "added" to JsonArray(added.map { JsonPrimitive(it) }))))
        }
        for ((name, apps) in wanted) {
            if (name in into) continue
            val id = idOf(name)
            val key = "user-collections.$id"
            keep += key
            val old = index[key]?.let { list[it] as? JsonArray }?.getOrNull(1) as? JsonObject
            val oldValue = old?.takeIf { it["is_deleted"]?.jsonPrimitive?.booleanOrNull != true }?.get("value")?.jsonPrimitive?.contentOrNull
                ?.let { runCatching { json.parseToJsonElement(it).jsonObject }.getOrNull() }
            val removed = oldValue?.get("removed")?.let { r -> (r as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull?.toLongOrNull() } }.orEmpty().toSet()
            val added = apps.distinct().filter { it !in removed }
            val value = JsonObject(
                mapOf(
                    "id" to JsonPrimitive(id),
                    "name" to JsonPrimitive(name),
                    "added" to JsonArray(added.map { JsonPrimitive(it) }),
                    "removed" to JsonArray(removed.map { JsonPrimitive(it) }),
                ),
            )
            val at = index[key]
            if (at != null) list[at] = record(key, value) else list.add(record(key, value))
        }
        // Fuse's collections no longer wanted: marked deleted.
        for ((key, at) in index) {
            if (!key.startsWith("user-collections.$PREFIX") || key in keep) continue
            val pair = list[at] as? JsonArray ?: continue
            val rec = pair.getOrNull(1) as? JsonObject ?: continue
            if (rec["is_deleted"]?.jsonPrimitive?.booleanOrNull == true) continue
            list[at] = JsonArray(listOf(pair[0], JsonObject(rec + mapOf("is_deleted" to JsonPrimitive(true), "timestamp" to JsonPrimitive(now)))))
        }
        return JsonArray(list).toString()
    }

    /** The names of the collections Fuse made that are still there (not deleted) in [text]. */
    fun fuseNames(text: String?): Set<String> {
        val list = text?.let { runCatching { json.parseToJsonElement(it).jsonArray }.getOrNull() } ?: return emptySet()
        return list.mapNotNull { e ->
            val pair = e as? JsonArray ?: return@mapNotNull null
            val key = pair.getOrNull(0)?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            if (!key.startsWith("user-collections.$PREFIX")) return@mapNotNull null
            val rec = pair.getOrNull(1) as? JsonObject ?: return@mapNotNull null
            if (rec["is_deleted"]?.jsonPrimitive?.booleanOrNull == true) return@mapNotNull null
            rec["value"]?.jsonPrimitive?.contentOrNull?.let { runCatching { json.parseToJsonElement(it).jsonObject["name"]?.jsonPrimitive?.contentOrNull }.getOrNull() }
        }.toSet()
    }

    /** Which namespace file holds the collections: the newest of `cloud-storage-namespaces.json`, else 1. */
    fun activeNamespace(namespaces: String?): Int {
        val list = namespaces?.let { runCatching { json.parseToJsonElement(it).jsonArray }.getOrNull() } ?: return 1
        return list.mapNotNull { e ->
            val pair = e as? JsonArray ?: return@mapNotNull null
            val n = pair.getOrNull(0)?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
            val v = pair.getOrNull(1)?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: return@mapNotNull null
            n to v
        }.filter { it.second > 0 }.maxByOrNull { it.second }?.first ?: 1
    }
}
