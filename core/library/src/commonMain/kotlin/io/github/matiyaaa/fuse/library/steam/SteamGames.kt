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
 * A game of Fuse's for Steam's library: it runs Fuse with `--play <id>`, so the game starts the way
 * it does from Fuse (its emulator, its settings, its saves kept in step and its time counted) and
 * Fuse closes again when it ends. [collection] is the Steam collection it goes in (its system).
 */
data class SteamGameEntry(
    val gameId: Long,
    val name: String,
    val collection: String? = null,
)

/** What adding games to Steam would do to one of them. */
enum class SteamGameAction {
    /** A new entry. */
    CREATE,

    /** Fuse's entry for it is there; its program or name moves on, the person's changes stay. */
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

/** Something wrong with an entry Fuse made, found by [SteamGames.health]. */
data class SteamHealthIssue(val gameId: Long, val appId: Long, val name: String, val kind: Kind) {
    enum class Kind {
        /** The game is no longer in Fuse's library. */
        GAME_GONE,

        /** The entry starts a Fuse that isn't here any more (an old AppImage). */
        PROGRAM_MOVED,

        /** Two of Fuse's entries start the same game. */
        DUPLICATE,
    }
}

/**
 * Fuse's own game entries in Steam's `shortcuts.vdf`: planned before anything is written, written
 * so that running it again changes nothing, and taken out again by their game. An entry is Fuse's
 * only when it starts Fuse with `--play <id>`; a name alone never makes it so. Entries anyone else
 * made are never changed, and a game someone else already put in Steam is never added twice.
 */
object SteamGames {
    private val PLAY = Regex("""(?:^|\s)--play\s+(\d+)(?:\s|$)""")

    /** The Fuse game an entry starts, from its launch options; null for any other entry. */
    fun playId(launchOptions: String): Long? = PLAY.find(launchOptions)?.groupValues?.get(1)?.toLongOrNull()

    fun launchOptions(gameId: Long): String = "--play $gameId"

    private class Found(val index: String, val block: BinaryVdf.Block, val exe: String, val name: String, val options: String)

    private fun entries(root: BinaryVdf.Block?): List<Found> {
        val list = root?.get("shortcuts") as? BinaryVdf.Block ?: return emptyList()
        return list.entries.mapNotNull { (k, v) ->
            val b = v as? BinaryVdf.Block ?: return@mapNotNull null
            Found(
                k, b,
                (b["Exe"] as? BinaryVdf.Str)?.value.orEmpty(),
                (b["AppName"] as? BinaryVdf.Str)?.value.orEmpty(),
                (b["LaunchOptions"] as? BinaryVdf.Str)?.value.orEmpty(),
            )
        }
    }

    /** A name as entries are compared by: case, spaces and punctuation aside. */
    private fun key(name: String) = name.lowercase().filter { it.isLetterOrDigit() }

    /**
     * What writing [games] into [existing] would do, game by game, without writing anything. [isFuse]
     * says whether an entry's program (quotes taken off) is a Fuse. Null when the file can't be read.
     */
    fun plan(existing: ByteArray?, fuseExe: String, games: List<SteamGameEntry>, isFuse: (String) -> Boolean): List<SteamGamePlanItem>? {
        val root = parse(existing) ?: return null
        val all = entries(root)
        val ours = all.filter { isFuse(it.exe.trim('"')) && playId(it.options) != null }.groupBy { playId(it.options)!! }
        val others = all.filter { !(isFuse(it.exe.trim('"')) && playId(it.options) != null) }.map { key(it.name) }.toSet()
        return games.distinctBy { it.gameId }.map { g ->
            val have = ours[g.gameId]?.firstOrNull()
            val action = when {
                have != null -> if (have.exe == quote(fuseExe)) SteamGameAction.UNCHANGED else SteamGameAction.UPDATE
                key(g.name) in others -> SteamGameAction.ELSEWHERE
                else -> SteamGameAction.CREATE
            }
            SteamGamePlanItem(g, action)
        }
    }

    /**
     * [existing] with [games] in it: new entries for games without one, Fuse's entries brought up to
     * date (the program that starts Fuse now), games another tool already put in Steam left out.
     * The person's changes to an entry stay: its name, icon, launch options beyond `--play` and its
     * tags. [icons] gives a game's icon file, when there is one. Null when the file can't be read.
     */
    fun write(existing: ByteArray?, fuseExe: String, games: List<SteamGameEntry>, isFuse: (String) -> Boolean, icons: (Long) -> String = { "" }): ByteArray? {
        val plan = plan(existing, fuseExe, games, isFuse) ?: return null
        val root = parse(existing) ?: return null
        val list = root["shortcuts"] as? BinaryVdf.Block ?: BinaryVdf.Block().also { root["shortcuts"] = it }
        for (item in plan) {
            if (item.action == SteamGameAction.ELSEWHERE || item.action == SteamGameAction.UNCHANGED) continue
            val g = item.entry
            val exe = quote(fuseExe)
            val have = entries(root).firstOrNull { isFuse(it.exe.trim('"')) && playId(it.options) == g.gameId }
            val entry = have?.block ?: BinaryVdf.Block().also { b ->
                val next = (list.entries.mapNotNull { it.first.toIntOrNull() }.maxOrNull() ?: -1) + 1
                list.entries += next.toString() to b
            }
            val fresh = have == null
            fun keep(key: String, value: String) {
                val now = (entry[key] as? BinaryVdf.Str)?.value
                if (fresh || now.isNullOrEmpty()) entry[key] = BinaryVdf.Str(value)
            }
            entry["appid"] = entry["appid"] ?: BinaryVdf.Int32(SteamShortcuts.appId(exe, g.name))
            keep("AppName", g.name)
            entry["Exe"] = BinaryVdf.Str(exe)
            entry["StartDir"] = BinaryVdf.Str(quote(parentOf(fuseExe)))
            keep("icon", icons(g.gameId))
            entry["ShortcutPath"] = entry["ShortcutPath"] ?: BinaryVdf.Str("")
            // The person's own launch options stay, as long as they still start this game.
            val options = (entry["LaunchOptions"] as? BinaryVdf.Str)?.value
            if (options == null || playId(options) != g.gameId) entry["LaunchOptions"] = BinaryVdf.Str(launchOptions(g.gameId))
            entry["IsHidden"] = entry["IsHidden"] ?: BinaryVdf.Int32(0)
            entry["AllowDesktopConfig"] = entry["AllowDesktopConfig"] ?: BinaryVdf.Int32(1)
            entry["AllowOverlay"] = entry["AllowOverlay"] ?: BinaryVdf.Int32(1)
            entry["OpenVR"] = entry["OpenVR"] ?: BinaryVdf.Int32(0)
            entry["Devkit"] = entry["Devkit"] ?: BinaryVdf.Int32(0)
            entry["DevkitGameID"] = entry["DevkitGameID"] ?: BinaryVdf.Str("")
            entry["DevkitOverrideAppID"] = entry["DevkitOverrideAppID"] ?: BinaryVdf.Int32(0)
            entry["LastPlayTime"] = entry["LastPlayTime"] ?: BinaryVdf.Int32(0)
            entry["FlatpakAppID"] = entry["FlatpakAppID"] ?: BinaryVdf.Str("")
            val had = (entry["tags"] as? BinaryVdf.Block)?.entries?.mapNotNull { (it.second as? BinaryVdf.Str)?.value }.orEmpty()
            val tags = BinaryVdf.Block()
            (had + listOfNotNull(g.collection).filter { it !in had }).forEachIndexed { i, t -> tags.entries += i.toString() to BinaryVdf.Str(t) }
            entry["tags"] = tags
        }
        return BinaryVdf.write(root)
    }

    /**
     * [existing] without Fuse's entries for [gameIds] (null: every game Fuse put there), the rest
     * numbered again as Steam numbers them. Fuse's own entry and everyone else's stay. Null when the
     * file can't be read; the same bytes when nothing was taken out.
     */
    fun remove(existing: ByteArray?, gameIds: Set<Long>?, isFuse: (String) -> Boolean): ByteArray? {
        if (existing == null || existing.isEmpty()) return existing
        val root = BinaryVdf.parse(existing) ?: return null
        val list = root["shortcuts"] as? BinaryVdf.Block ?: return existing
        val kept = list.entries.filterNot { (_, v) ->
            val b = v as? BinaryVdf.Block ?: return@filterNot false
            val id = playId((b["LaunchOptions"] as? BinaryVdf.Str)?.value.orEmpty()) ?: return@filterNot false
            isFuse((b["Exe"] as? BinaryVdf.Str)?.value.orEmpty().trim('"')) && (gameIds == null || id in gameIds)
        }
        if (kept.size == list.entries.size) return existing
        list.entries.clear()
        kept.forEachIndexed { i, (_, v) -> list.entries += i.toString() to v }
        return BinaryVdf.write(root)
    }

    /**
     * [existing] put right: Fuse's entries for games no longer in [library] taken out, and a game's
     * second entry (the first, with whatever the person changed on it, stays). Entries that start an
     * older Fuse are brought up to date by writing their games again ([write]). Everyone else's
     * entries are never touched. Null when the file can't be read.
     */
    fun repair(existing: ByteArray?, library: Set<Long>, isFuse: (String) -> Boolean): ByteArray? {
        if (existing == null || existing.isEmpty()) return existing
        val root = BinaryVdf.parse(existing) ?: return null
        val list = root["shortcuts"] as? BinaryVdf.Block ?: return existing
        val seen = HashSet<Long>()
        val kept = list.entries.filter { (_, v) ->
            val b = v as? BinaryVdf.Block ?: return@filter true
            val id = playId((b["LaunchOptions"] as? BinaryVdf.Str)?.value.orEmpty()) ?: return@filter true
            if (!isFuse((b["Exe"] as? BinaryVdf.Str)?.value.orEmpty().trim('"'))) return@filter true
            id in library && seen.add(id)
        }
        if (kept.size == list.entries.size) return existing
        list.entries.clear()
        kept.forEachIndexed { i, (_, v) -> list.entries += i.toString() to v }
        return BinaryVdf.write(root)
    }

    /** The Fuse games [bytes] has entries for, with each entry's id as Steam names its art files (unsigned). */
    fun inSteam(bytes: ByteArray?, isFuse: (String) -> Boolean): Map<Long, Long> =
        entries(parse(bytes)).mapNotNull { e ->
            val id = playId(e.options) ?: return@mapNotNull null
            if (!isFuse(e.exe.trim('"'))) return@mapNotNull null
            id to artId(e)
        }.toMap()

    /**
     * What is wrong with the entries Fuse made in [bytes]: a game no longer in [library], a program
     * that isn't [fuseExe] any more, and the same game twice.
     */
    fun health(bytes: ByteArray?, fuseExe: String, library: Set<Long>, isFuse: (String) -> Boolean): List<SteamHealthIssue> {
        val ours = entries(parse(bytes)).filter { isFuse(it.exe.trim('"')) && playId(it.options) != null }
        val out = ArrayList<SteamHealthIssue>()
        val seen = HashSet<Long>()
        for (e in ours) {
            val id = playId(e.options)!!
            val art = artId(e)
            when {
                id !in seen && id !in library -> out += SteamHealthIssue(id, art, e.name, SteamHealthIssue.Kind.GAME_GONE)
                id in seen -> out += SteamHealthIssue(id, art, e.name, SteamHealthIssue.Kind.DUPLICATE)
                e.exe != quote(fuseExe) -> out += SteamHealthIssue(id, art, e.name, SteamHealthIssue.Kind.PROGRAM_MOVED)
            }
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
            if (playId(e.options) == null || !isFuse(e.exe.trim('"'))) continue
            val tags = (e.block["tags"] as? BinaryVdf.Block)?.entries?.mapNotNull { (it.second as? BinaryVdf.Str)?.value }.orEmpty()
            for (t in tags) out[t]?.add(artId(e))
        }
        return out
    }

    /** The art id Steam uses for [e]: the one it was given, else the one Steam works out. */
    private fun artId(e: Found): Long = ((e.block["appid"] as? BinaryVdf.Int32)?.value ?: SteamShortcuts.appId(e.exe, e.name)).toLong() and 0xFFFFFFFFL

    /** The art id a new entry for [game] gets, before it is written. */
    fun newArtId(fuseExe: String, game: SteamGameEntry): Long = SteamShortcuts.appId(quote(fuseExe), game.name).toLong() and 0xFFFFFFFFL

    private fun parse(bytes: ByteArray?): BinaryVdf.Block? = if (bytes == null || bytes.isEmpty()) BinaryVdf.Block() else BinaryVdf.parse(bytes)

    private fun quote(path: String) = "\"$path\""

    private fun parentOf(path: String): String = path.replace('\\', '/').substringBeforeLast('/', ".").let { p ->
        // Keep the system's own separators for Windows paths.
        if ('\\' in path) p.replace('/', '\\') else p
    }
}

/**
 * Steam's collections, as Steam keeps them for each user in `config/cloudstorage`: a JSON list of
 * `[key, record]` pairs, a collection being `user-collections.<id>` whose `value` is the collection
 * as JSON (`id`, `name`, `added`: the apps' ids). Steam must be closed while this is written, as it
 * writes the file when it quits. Only Fuse's own collections (ids starting `fuse-`) are changed;
 * every other record is kept exactly, and a Fuse collection no longer wanted is marked deleted
 * rather than dropped, as Steam does.
 */
object SteamCollections {
    const val PREFIX = "fuse-"

    private val json = Json { ignoreUnknownKeys = true }

    /** The id Fuse gives the collection named [name]. */
    fun idOf(name: String): String = PREFIX + name.encodeToByteArray().joinToString("") { ((it.toInt() and 0xFF) + 0x100).toString(16).substring(1) }

    /**
     * [text] (the namespace file, or null for none yet) with Fuse's collections set to [wanted]
     * (name to the apps' 32-bit ids). [now] is in seconds. Apps the person took out of a Fuse
     * collection in Steam stay out. Null when [text] isn't Steam's list.
     */
    fun write(text: String?, wanted: Map<String, List<Long>>, now: Long): String? {
        val list: MutableList<JsonElement> = if (text.isNullOrBlank()) mutableListOf() else runCatching { json.parseToJsonElement(text).jsonArray.toMutableList() }.getOrNull() ?: return null
        val index = HashMap<String, Int>()
        list.forEachIndexed { i, e -> (e as? JsonArray)?.getOrNull(0)?.jsonPrimitive?.contentOrNull?.let { index[it] = i } }
        val keep = HashSet<String>()
        for ((name, apps) in wanted) {
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
            val record = JsonArray(
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
            val at = index[key]
            if (at != null) list[at] = record else list.add(record)
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
