package io.github.matiyaaa.fuse.library.achievements

import io.github.matiyaaa.fuse.library.FsPath
import io.github.matiyaaa.fuse.library.FuseFileSystem
import io.github.matiyaaa.fuse.library.steam.BinaryVdf

/** One achievement or trophy as a game's own records keep it. [unlockedAt] is epoch millis (0 when unlocked at an unknown time). */
data class LocalAchievement(
    val id: String,
    val name: String,
    val description: String,
    val hidden: Boolean,
    /** An address or a file for its picture, and the locked picture where there is one. */
    val icon: String?,
    val iconLocked: String?,
    val unlockedAt: Long?,
    /** Trophy points (PlayStation); 0 where achievements carry none (Steam). */
    val points: Int = 0,
    /** "platinum", "gold", "silver", "bronze" for trophies. */
    val grade: String? = null,
)

/** Where a set of achievements came from, for its label. */
enum class LocalAchievementSource { STEAM, PS3_TROPHIES, PS4_TROPHIES, VITA_TROPHIES, XBOX360 }

data class LocalAchievementSet(val source: LocalAchievementSource, val title: String?, val items: List<LocalAchievement>, val icon: String? = null)

/**
 * Reads achievements from what a game's own platform keeps on this computer, without accounts or
 * keys: Steam's stats cache ([steam]), RPCS3's trophy folders ([ps3Trophies]), shadPS4's
 * ([ps4Trophies]), Vita3K's ([vitaTrophies]) and Xenia's profiles ([xbox360Achievements]). Only reads.
 */
class LocalAchievements(private val fs: FuseFileSystem) {

    // Steam

    /**
     * The achievements of Steam game [appId] as Steam's client last cached them: their names and
     * pictures from its schema (appcache/stats/UserGameStatsSchema_<app>.bin) and what is unlocked,
     * with when, from the user's stats (UserGameStats_<account>_<app>.bin). With [steamId] (a 64-bit
     * SteamID) only that account's are read, so people sharing a computer never see each other's;
     * without, the newest stats there are. Null when the game has no achievements or Steam has no
     * record of them here.
     */
    suspend fun steam(steamRoots: List<String>, appId: Long, steamId: String? = null): LocalAchievementSet? {
        val account = steamId?.toULongOrNull()?.let { (it - STEAM_ID_BASE).toLong() }
        for (root in steamRoots) {
            val stats = FsPath.join(root, "appcache", "stats")
            val schema = read(FsPath.join(stats, "UserGameStatsSchema_$appId.bin"), MAX_SCHEMA)?.let(BinaryVdf::parse) ?: continue
            val user = runCatching { fs.list(stats) }.getOrDefault(emptyList())
                .filter { it.name.startsWith("UserGameStats_") && it.name.endsWith("_$appId.bin") && !it.name.startsWith("UserGameStatsSchema") }
                .filter { account == null || it.name == "UserGameStats_${account}_$appId.bin" }
                .maxByOrNull { it.modifiedAt }
                ?.let { read(it.path, MAX_STATS) }?.let(BinaryVdf::parse)
            return steamFrom(schema, user, appId)
        }
        return null
    }

    /** The set described by a parsed schema and (when there is one) a parsed user stats file. */
    fun steamFrom(schema: BinaryVdf.Block, user: BinaryVdf.Block?, appId: Long): LocalAchievementSet? {
        val app = (schema.entries.firstOrNull()?.second as? BinaryVdf.Block) ?: return null
        val title = (app["gamename"] as? BinaryVdf.Str)?.value
        val stats = app["stats"] as? BinaryVdf.Block ?: return null
        val cache = user?.let { (it["cache"] as? BinaryVdf.Block) ?: (it.entries.firstOrNull()?.second as? BinaryVdf.Block) }
        val out = ArrayList<LocalAchievement>()
        for ((statId, statValue) in stats.entries) {
            val stat = statValue as? BinaryVdf.Block ?: continue
            val bits = stat["bits"] as? BinaryVdf.Block ?: continue
            val mine = cache?.get(statId) as? BinaryVdf.Block
            val data = mine?.get("data").asLong() ?: 0L
            val times = mine?.get("AchievementTimes") as? BinaryVdf.Block
            for ((bitKey, bitValue) in bits.entries) {
                val bit = bitValue as? BinaryVdf.Block ?: continue
                val index = bit["bit"].asLong()?.toInt() ?: bitKey.toIntOrNull() ?: continue
                val display = bit["display"] as? BinaryVdf.Block
                val unlocked = index in 0..63 && (data ushr index) and 1L == 1L
                val at = times?.get(index.toString()).asLong()?.takeIf { it > 0 }?.let { it * 1000 }
                out += LocalAchievement(
                    id = (bit["name"] as? BinaryVdf.Str)?.value ?: "$statId.$index",
                    name = localized(display?.get("name")) ?: (bit["name"] as? BinaryVdf.Str)?.value ?: "Achievement",
                    description = localized(display?.get("desc")).orEmpty(),
                    hidden = display?.get("hidden").asLong() == 1L || (display?.get("hidden") as? BinaryVdf.Str)?.value == "1",
                    icon = (display?.get("icon") as? BinaryVdf.Str)?.value?.takeIf { it.isNotBlank() }?.let { steamIcon(appId, it) },
                    iconLocked = (display?.get("icon_gray") as? BinaryVdf.Str)?.value?.takeIf { it.isNotBlank() }?.let { steamIcon(appId, it) },
                    unlockedAt = if (unlocked) at ?: 0L else null,
                )
            }
        }
        if (out.isEmpty()) return null
        return LocalAchievementSet(LocalAchievementSource.STEAM, title, out)
    }

    private fun localized(v: BinaryVdf.Value?): String? = when (v) {
        is BinaryVdf.Str -> v.value
        is BinaryVdf.Block -> (v["english"] as? BinaryVdf.Str)?.value
            ?: v.entries.firstOrNull { it.first != "token" && it.second is BinaryVdf.Str }?.let { (it.second as BinaryVdf.Str).value }
        else -> null
    }?.trim()?.takeIf { it.isNotEmpty() }

    private fun BinaryVdf.Value?.asLong(): Long? = when (this) {
        is BinaryVdf.Int32 -> value.toLong() and 0xFFFFFFFFL
        is BinaryVdf.Int64 -> value
        is BinaryVdf.Str -> value.trim().toLongOrNull()
        is BinaryVdf.Float32 -> value.toLong()
        else -> null
    }

    private fun steamIcon(appId: Long, file: String) = "https://cdn.akamai.steamstatic.com/steamcommunity/public/images/apps/$appId/$file"

    // PlayStation 3 (RPCS3)

    /**
     * A PS3 game's trophies as RPCS3 keeps them in [devHdd0Roots] (each an RPCS3 dev_hdd0 folder):
     * the set is found by the trophy folders the game ships ([gameFolders], its PS3_GAME/TROPDIR or
     * the installed game's own), else by its [serial]'s installed copy, else by [title] matching the
     * set's name. Unlocked trophies come from TROPUSR.DAT; names, kinds and pictures from the set.
     */
    suspend fun ps3Trophies(devHdd0Roots: List<String>, gameFolders: List<String>, serial: String?, title: String): LocalAchievementSet? {
        for (dev in devHdd0Roots) {
            val users = runCatching { fs.list(FsPath.join(dev, "home")) }.getOrDefault(emptyList()).filter { it.isDirectory }
            val ids = buildList {
                for (folder in gameFolders) {
                    addAll(dirNames(FsPath.join(folder, "PS3_GAME", "TROPDIR")))
                    addAll(dirNames(FsPath.join(folder, "TROPDIR")))
                }
                if (serial != null) addAll(dirNames(FsPath.join(dev, "game", serial, "TROPDIR")))
            }.distinct()
            for (user in users.sortedBy { it.name }) {
                val trophyRoot = FsPath.join(user.path, "trophy")
                val sets = dirNames(trophyRoot)
                val chosen = ids.firstOrNull { it in sets } ?: sets.firstOrNull { set ->
                    val conf = read(FsPath.join(trophyRoot, set, "TROPCONF.SFM"), MAX_CONF)?.decodeToString() ?: return@firstOrNull false
                    TITLE.find(conf)?.groupValues?.get(1)?.let { sameTitle(xml(it), title) } == true
                } ?: continue
                return trophySet(FsPath.join(trophyRoot, chosen))
            }
        }
        return null
    }

    /** A trophy set from its folder (TROPCONF.SFM or TROP.SFM, TROPUSR.DAT and the TROPxxx.PNG pictures). */
    suspend fun trophySet(dir: String): LocalAchievementSet? {
        val conf = (read(FsPath.join(dir, "TROP.SFM"), MAX_CONF) ?: read(FsPath.join(dir, "TROPCONF.SFM"), MAX_CONF))?.decodeToString() ?: return null
        val unlocked = read(FsPath.join(dir, "TROPUSR.DAT"), MAX_USR)?.let(::tropusr).orEmpty()
        val items = TROPHY.findAll(conf).map { m ->
            val id = m.groupValues[1]
            val number = id.toIntOrNull() ?: 0
            val grade = when (m.groupValues[3].uppercase()) {
                "P" -> "platinum"
                "G" -> "gold"
                "S" -> "silver"
                else -> "bronze"
            }
            val icon = FsPath.join(dir, "TROP${id.padStart(3, '0')}.PNG")
            LocalAchievement(
                id = id,
                name = xml(m.groupValues[4]),
                description = xml(m.groupValues[5]),
                hidden = m.groupValues[2].equals("yes", ignoreCase = true),
                icon = icon,
                iconLocked = icon,
                unlockedAt = unlocked[number],
                points = POINTS.getValue(grade),
                grade = grade,
            )
        }.toList()
        if (items.isEmpty()) return null
        val title = TITLE.find(conf)?.groupValues?.get(1)?.let(::xml)
        return LocalAchievementSet(LocalAchievementSource.PS3_TROPHIES, title, items, icon = FsPath.join(dir, "ICON0.PNG"))
    }

    /**
     * Unlocked trophies in a TROPUSR.DAT (RPCS3's layout, big-endian): a 48-byte header with the
     * table count, 32-byte table headers (type, entry size, count, offset), and in the type 6 table
     * one entry per trophy with its id, state and unlock time (a PS3 tick: microseconds since year 1).
     */
    fun tropusr(b: ByteArray): Map<Int, Long> {
        fun u32(at: Int): Long = if (at + 4 > b.size) -1 else
            ((b[at].toLong() and 0xFF) shl 24) or ((b[at + 1].toLong() and 0xFF) shl 16) or ((b[at + 2].toLong() and 0xFF) shl 8) or (b[at + 3].toLong() and 0xFF)
        fun u64(at: Int): Long = (u32(at) shl 32) or (u32(at + 4) and 0xFFFFFFFFL)
        if (b.size < 48 || u32(0) != MAGIC) return emptyMap()
        val tables = u32(8).toInt().coerceIn(0, 16)
        val out = HashMap<Int, Long>()
        for (t in 0 until tables) {
            val h = 48 + t * 32
            if (u32(h) != 6L) continue
            val size = u32(h + 4).toInt()
            val count = u32(h + 12).toInt()
            val offset = u64(h + 16).toInt()
            if (size <= 0 || count !in 0..1024 || offset < 0) continue
            for (i in 0 until count) {
                val e = offset + i * (size + 16)
                if (e + 48 > b.size) break
                val id = u32(e + 16).toInt()
                val state = u32(e + 20)
                if (state == 0L) continue
                val tick = u64(e + 32)
                out[id] = (tick - EPOCH_TICKS).takeIf { it > 0 }?.let { it / 1000 } ?: 0L
            }
        }
        return out
    }

    // PlayStation 4 (shadPS4)

    /**
     * A PS4 game's trophies as shadPS4 keeps them, in its user folders [userDirs] (src/core/libraries/
     * np/np_trophy.cpp, common/path_util.h): the set's names, kinds and pictures in
     * `trophy/<NPWR id>/Xml/TROP.XML` and `Icons/`, and each console user's unlocks (with the time,
     * in seconds) in `home/<user>/trophy/<NPWR id>.xml`. The set is the one [npCommIds] names (read
     * from the game's own npbind.dat), else the one whose title is [title]. Only reads.
     */
    suspend fun ps4Trophies(userDirs: List<String>, npCommIds: List<String>, title: String): LocalAchievementSet? {
        for (user in userDirs) {
            val sets = dirNames(FsPath.join(user, "trophy"))
            if (sets.isEmpty()) continue
            val chosen = npCommIds.firstOrNull { it in sets } ?: sets.firstOrNull { set ->
                val conf = ps4Conf(user, set) ?: return@firstOrNull false
                TITLE.find(conf)?.groupValues?.get(1)?.let { sameTitle(xml(it), title) } == true
            } ?: continue
            val conf = ps4Conf(user, chosen) ?: continue
            // The first console user with a record of the set (shadPS4's first is 1000).
            val homes = dirNames(FsPath.join(user, "home")).sorted()
            val state = homes.firstNotNullOfOrNull { u -> read(FsPath.join(user, "home", u, "trophy", "$chosen.xml"), MAX_CONF)?.decodeToString() }
            return ps4Set(conf, state, FsPath.join(user, "trophy", chosen, "Icons"))
        }
        return null
    }

    private suspend fun ps4Conf(user: String, set: String): String? {
        val dir = FsPath.join(user, "trophy", set, "Xml")
        return (read(FsPath.join(dir, "TROP.XML"), MAX_CONF) ?: read(FsPath.join(dir, "TROP_01.XML"), MAX_CONF))?.decodeToString()
    }

    /** A set from its TROP.XML ([conf]), a user's unlock record ([state], or none) and its [icons] folder. */
    fun ps4Set(conf: String, state: String?, icons: String): LocalAchievementSet? {
        val unlocked = HashMap<Int, Long>()
        if (state != null) for (m in TAG.findAll(state)) {
            val attrs = m.groupValues[1]
            val id = ATTR_ID.find(attrs)?.groupValues?.get(1)?.toIntOrNull() ?: continue
            if (ATTR_UNLOCK.find(attrs)?.groupValues?.get(1)?.lowercase() != "true") continue
            unlocked[id] = ATTR_TIME.find(attrs)?.groupValues?.get(1)?.toLongOrNull()?.takeIf { it > 0 }?.let { it * 1000 } ?: 0L
        }
        val items = confItems(conf, unlocked, icons)
        if (items.isEmpty()) return null
        val title = TITLE.find(conf)?.groupValues?.get(1)?.let(::xml)
        return LocalAchievementSet(LocalAchievementSource.PS4_TROPHIES, title, items, icon = FsPath.join(icons, "ICON0.PNG"))
    }

    /**
     * The trophies a trophy set's configuration ([conf], PS4's TROP.XML or the Vita's TROP.SFM) lists,
     * read attribute by attribute, with [unlocked]'s times (by trophy id) and pictures in [icons].
     */
    private fun confItems(conf: String, unlocked: Map<Int, Long>, icons: String?): List<LocalAchievement> =
        TROPHY_BLOCK.findAll(conf).mapNotNull { m ->
            val attrs = m.groupValues[1]
            val body = m.groupValues[2]
            val number = ATTR_ID.find(attrs)?.groupValues?.get(1)?.toIntOrNull() ?: return@mapNotNull null
            val grade = when (ATTR_TYPE.find(attrs)?.groupValues?.get(1)?.uppercase()) {
                "P" -> "platinum"
                "G" -> "gold"
                "S" -> "silver"
                else -> "bronze"
            }
            val icon = icons?.let { FsPath.join(it, "TROP${number.toString().padStart(3, '0')}.PNG") }
            LocalAchievement(
                id = number.toString(),
                name = NAME.find(body)?.groupValues?.get(1)?.let(::xml) ?: "Trophy",
                description = DETAIL.find(body)?.groupValues?.get(1)?.let(::xml).orEmpty(),
                hidden = ATTR_HIDDEN.find(attrs)?.groupValues?.get(1).equals("yes", ignoreCase = true),
                icon = icon,
                iconLocked = icon,
                unlockedAt = unlocked[number],
                points = POINTS.getValue(grade),
                grade = grade,
            )
        }.toList()

    /**
     * A PS4 game's trophies from the game itself, before shadPS4 has set them out in its user folder
     * (it does when the game first opens them): `sce_sys/trophy/trophy00.trp` in one of
     * [gameFolders] ([Ps4Trp]), decrypted with the person's own trophy key as shadPS4 keeps it in
     * [userDirs] (`keys.json`'s `TrophyKeySet.ReleaseTrophyKey`, `src/common/key_manager.cpp`, else
     * an older `config.toml`'s `[Keys] TrophyKey`). Nothing is earned yet (shadPS4 records that) and
     * no pictures come with them; the key is only read. Only reads.
     */
    suspend fun ps4TrophiesFromGame(gameFolders: List<String>, userDirs: List<String>, npCommIds: List<String>): LocalAchievementSet? {
        val id = npCommIds.firstOrNull() ?: return null
        val key = userDirs.firstNotNullOfOrNull { trophyKey(it) } ?: return null
        for (folder in gameFolders) {
            val path = FsPath.join(folder, "sce_sys", "trophy", "trophy00.trp")
            val header = runCatching { fs.readBytes(path, 0, Ps4Trp.HEADER) }.getOrNull() ?: continue
            val (count, size) = Ps4Trp.entryCount(header) ?: continue
            val table = runCatching { fs.readBytes(path, Ps4Trp.HEADER.toLong(), count * size) }.getOrNull() ?: continue
            val entry = Ps4Trp.conf(Ps4Trp.entries(table, count, size))?.takeIf { it.length <= MAX_CONF } ?: continue
            val esfm = runCatching { fs.readBytes(path, entry.offset, entry.length.toInt()) }.getOrNull() ?: continue
            val conf = Ps4Trp.decrypt(esfm, key, id) ?: continue
            val items = confItems(conf, emptyMap(), icons = null)
            if (items.isEmpty()) continue
            return LocalAchievementSet(LocalAchievementSource.PS4_TROPHIES, TITLE.find(conf)?.groupValues?.get(1)?.let(::xml), items)
        }
        return null
    }

    /** The trophy key shadPS4 has for the person in its user folder [user], or null. */
    private suspend fun trophyKey(user: String): ByteArray? {
        read(FsPath.join(user, "keys.json"), MAX_CONF)?.decodeToString()?.let { json ->
            Ps4Trp.key(TROPHY_KEY_JSON.find(json)?.groupValues?.get(1))?.let { return it }
        }
        val toml = read(FsPath.join(user, "config.toml"), MAX_CONF)?.decodeToString() ?: return null
        return Ps4Trp.key(TROPHY_KEY_TOML.find(toml)?.groupValues?.get(1))
    }

    /** The trophy set ids (NPWRxxxxx_00) a PS4 game's npbind.dat names. */
    fun npCommIds(npbind: ByteArray): List<String> =
        NPWR.findAll(npbind.decodeToString(throwOnInvalidSequence = false)).map { it.value }.distinct().toList()

    // PlayStation Vita (Vita3K)

    /**
     * A Vita game's trophies as Vita3K keeps them under its pref paths [prefPaths] (where its `ux0`
     * is), following vita3k/np/src/trophy/context.cpp: the set's names, kinds and pictures in
     * `ux0/user/<user>/trophy/conf/<NPWR id>/` (TROP.SFM, TROPxxx.PNG and ICON0.PNG, copied from the
     * game's TROPHY.TRP when it first opened them) and what is unlocked in
     * `trophy/data/<NPWR id>/TROPUSR.DAT`. The set is one the game itself ships: [npCommIds] (the
     * folders of its `sce_sys/trophy`), or those of its installed copy `ux0/app/<titleId>/sce_sys/trophy`.
     * Never matched by name. Only reads.
     */
    suspend fun vitaTrophies(prefPaths: List<String>, titleId: String?, npCommIds: List<String>): LocalAchievementSet? {
        for (pref in prefPaths) {
            val ux0 = FsPath.join(pref, "ux0")
            val installed = titleId?.uppercase()?.takeIf { VITA_TITLE.matches(it) }?.let { dirNames(FsPath.join(ux0, "app", it, "sce_sys", "trophy")) }.orEmpty()
            val ids = (npCommIds + installed).filter { NPWR.matches(it) }.distinct()
            if (ids.isEmpty()) continue
            // Vita3K's users are numbered folders ("00" by default); the first with a record of the set.
            for (user in dirNames(FsPath.join(ux0, "user")).sorted()) {
                val trophy = FsPath.join(ux0, "user", user, "trophy")
                for (id in ids) {
                    val conf = FsPath.join(trophy, "conf", id)
                    val text = (read(FsPath.join(conf, "TROP.SFM"), MAX_CONF) ?: read(FsPath.join(conf, "TROPCONF.SFM"), MAX_CONF))?.decodeToString() ?: continue
                    val unlocked = read(FsPath.join(trophy, "data", id, "TROPUSR.DAT"), MAX_USR)?.let(::vitaTropusr).orEmpty()
                    val items = confItems(text, unlocked, conf)
                    if (items.isEmpty()) continue
                    val title = TITLE.find(text)?.groupValues?.get(1)?.let(::xml)
                    return LocalAchievementSet(LocalAchievementSource.VITA_TROPHIES, title, items, icon = FsPath.join(conf, "ICON0.PNG"))
                }
            }
        }
        return null
    }

    /**
     * Unlocked trophies in Vita3K's TROPUSR.DAT (save_trophy_progress_file, in the computer's own
     * byte order, little-endian on every computer Vita3K runs on): the magic 0x12D5819A, then one
     * bit per trophy (128 of them) from byte 4, and after the visibility bits, group and trophy
     * counts, the platinum's id and 16 group counts, each trophy's unlock time from byte 112, in
     * seconds since 1970 (`std::time`).
     */
    fun vitaTropusr(b: ByteArray): Map<Int, Long> {
        fun u32(at: Int): Long = ((b[at].toLong() and 0xFF) or ((b[at + 1].toLong() and 0xFF) shl 8) or
            ((b[at + 2].toLong() and 0xFF) shl 16) or ((b[at + 3].toLong() and 0xFF) shl 24))
        fun u64(at: Int): Long = u32(at) or (u32(at + 4) shl 32)
        if (b.size < VITA_TIMES + VITA_TROPHIES * 8 || u32(0) != VITA_MAGIC) return emptyMap()
        val out = HashMap<Int, Long>()
        for (id in 0 until VITA_TROPHIES) {
            if (((u32(4 + (id shr 5) * 4) ushr (id and 31)) and 1L) == 0L) continue
            out[id] = u64(VITA_TIMES + id * 8).takeIf { it in 1..MAX_SECONDS }?.let { it * 1000 } ?: 0L
        }
        return out
    }

    // Xbox 360 (Xenia)

    /**
     * An Xbox 360 game's achievements as Xenia keeps them, under each of its content folders
     * [contentRoots] (xenia_main.cc): each profile is a folder named by its 16-digit XUID, holding
     * `FFFE07D1/00010000/<XUID>/<title id>.gpd` for each game it played (profile_manager.cc,
     * user_profile.cc). The game is found by its 8-digit [titleId] alone, never by name. Profiles are
     * separate people, so one profile's are shown: the one that played the game last.
     */
    @OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
    suspend fun xbox360Achievements(contentRoots: List<String>, titleId: String): LocalAchievementSet? {
        val id = titleId.uppercase().takeIf { X360_TITLE.matches(it) } ?: return null
        val files = contentRoots.flatMap { root ->
            dirNames(root).filter { XUID.matches(it) && it != "0000000000000000" }.map { xuid ->
                FsPath.join(root, xuid, "FFFE07D1", "00010000", xuid, "$id.gpd")
            }
        }.mapNotNull { path -> runCatching { fs.stat(path) }.getOrNull()?.takeIf { !it.isDirectory } }
        val newest = files.maxByOrNull { it.modifiedAt } ?: return null
        return read(newest.path, MAX_GPD)?.let(::gpd)
    }

    /**
     * Achievements in a title's GPD (an XDBF file; xdbf_io.h and gpd_info.h): a 24-byte header with
     * the entry and free-slot counts, 18-byte entries (section, id, offset, size) and the data after
     * them. Section 1 entries are achievements (id, picture, gamerscore, flags with 0x20000 for
     * unlocked and 0x8 for shown before it is, the unlock time as a Windows FILETIME, then three
     * UTF-16 strings: name, how it was earned, and what to do); section 2 holds the pictures (PNG)
     * and section 5 the strings, the game's name under 0x8000. Pictures are kept as data URIs, so
     * nothing is written anywhere.
     */
    @OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
    fun gpd(b: ByteArray): LocalAchievementSet? {
        fun u16(at: Int): Int = ((b[at].toInt() and 0xFF) shl 8) or (b[at + 1].toInt() and 0xFF)
        fun u32(at: Int): Long = (u16(at).toLong() shl 16) or u16(at + 2).toLong()
        fun u64(at: Int): Long = (u32(at) shl 32) or u32(at + 4)
        fun text(from: Int, end: Int): Pair<String, Int> {
            val out = StringBuilder()
            var i = from
            while (i + 1 < end) {
                val c = u16(i)
                i += 2
                if (c == 0) break
                out.append(c.toChar())
            }
            return out.toString().trim() to i
        }
        if (b.size < 24 || b.decodeToString(0, 4) != "XDBF") return null
        val entries = u32(8).toInt()
        val used = u32(12).toInt()
        val free = u32(16).toInt()
        if (entries !in 0..MAX_ENTRIES || used !in 0..entries || free !in 0..MAX_ENTRIES) return null
        val data = 24 + entries * 18 + free * 8
        val images = HashMap<Long, Pair<Int, Int>>()
        var title: String? = null
        class Raw(val id: Long, val image: Long, val score: Int, val flags: Long, val time: Long, val name: String, val earned: String, val howTo: String)
        val raw = ArrayList<Raw>()
        for (i in 0 until used) {
            val e = 24 + i * 18
            if (e + 18 > b.size) break
            val section = u16(e)
            val entryId = u64(e + 2)
            val start = data + u32(e + 10).toInt()
            val size = u32(e + 14).toInt()
            if (start < data || size < 0 || start + size > b.size) continue
            when (section) {
                GPD_IMAGE -> images[entryId] = start to size
                GPD_STRING -> if (entryId == GPD_TITLE) title = text(start, start + size).first.takeIf { it.isNotEmpty() }
                GPD_ACHIEVEMENT -> if (size >= 0x1C) {
                    val name = text(start + 0x1C, start + size)
                    val earned = text(name.second, start + size)
                    val howTo = text(earned.second, start + size)
                    raw += Raw(u32(start + 4), u32(start + 8), u32(start + 12).toInt(), u32(start + 16), u64(start + 20), name.first, earned.first, howTo.first)
                }
            }
        }
        if (raw.isEmpty()) return null
        fun picture(id: Long): String? = images[id]?.let { (start, size) ->
            if (size < 8 || size > MAX_PICTURE || b[start] != 0x89.toByte() || b[start + 1] != 'P'.code.toByte()) return@let null
            "data:image/png;base64," + kotlin.io.encoding.Base64.encode(b, start, start + size)
        }
        val items = raw.sortedBy { it.id }.map { a ->
            val unlocked = (a.flags and GPD_ACHIEVED) != 0L
            val picture = picture(a.image)
            LocalAchievement(
                id = a.id.toString(),
                name = a.name.ifEmpty { "Achievement" },
                // Earned, it says how; not yet, what to do (each falls back on the other).
                description = if (unlocked) a.earned.ifEmpty { a.howTo } else a.howTo.ifEmpty { a.earned },
                hidden = (a.flags and GPD_SHOW_UNACHIEVED) == 0L,
                icon = picture,
                iconLocked = picture,
                unlockedAt = if (unlocked) fileTime(a.time) ?: 0L else null,
                points = a.score,
            )
        }
        return LocalAchievementSet(LocalAchievementSource.XBOX360, title, items, icon = picture(GPD_TITLE))
    }

    /** A Windows FILETIME (100 ns since 1601) as epoch millis, or null when it is unset or unlikely. */
    private fun fileTime(ticks: Long): Long? = (ticks / 10_000 - FILETIME_EPOCH_MS).takeIf { ticks > 0 && it in 1..MAX_SECONDS * 1000 }

    // Shared

    private suspend fun dirNames(path: String): List<String> =
        runCatching { fs.list(path) }.getOrDefault(emptyList()).filter { it.isDirectory }.map { it.name }

    private suspend fun read(path: String, max: Int): ByteArray? {
        val size = runCatching { fs.stat(path) }.getOrNull()?.takeIf { !it.isDirectory }?.sizeBytes ?: return null
        if (size <= 0 || size > max) return null
        return runCatching { fs.readBytes(path, 0, size.toInt()) }.getOrNull()
    }

    private fun xml(s: String): String = s.trim()
        .replace(NUMERIC) { m -> m.groupValues[1].toIntOrNull()?.takeIf { it in 1..0xFFFF }?.toChar()?.toString() ?: m.value }
        .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")

    private fun sameTitle(a: String, b: String): Boolean {
        fun plain(s: String) = s.lowercase().filter { it.isLetterOrDigit() }
        return plain(a).isNotEmpty() && plain(a) == plain(b)
    }

    private companion object {
        /** A 64-bit SteamID minus this is the account id Steam names its stats files by. */
        const val STEAM_ID_BASE: ULong = 76561197960265728uL
        const val MAX_SCHEMA = 8 * 1024 * 1024
        const val MAX_STATS = 2 * 1024 * 1024
        const val MAX_CONF = 2 * 1024 * 1024
        const val MAX_USR = 1024 * 1024
        const val MAGIC = 0x818F54ADL

        /** PS3 ticks (microseconds since 0001-01-01) at the Unix epoch. */
        const val EPOCH_TICKS = 62_135_596_800_000_000L

        val TROPHY = Regex("""<trophy\s+id="(\d+)"\s+hidden="(\w+)"\s+ttype="(\w)"[^>]*>\s*<name>(.*?)</name>\s*<detail>(.*?)</detail>""", RegexOption.DOT_MATCHES_ALL)
        val TITLE = Regex("""<title-name>(.*?)</title-name>""", RegexOption.DOT_MATCHES_ALL)

        // shadPS4's XML, read attribute by attribute (their order isn't fixed).
        val TAG = Regex("""<trophy\b([^>]*)/?>""")
        val TROPHY_BLOCK = Regex("""<trophy\b([^>]*)>(.*?)</trophy>""", RegexOption.DOT_MATCHES_ALL)
        val ATTR_ID = Regex("""\bid="(\d+)"""")
        val ATTR_UNLOCK = Regex("""\bunlockstate="(\w+)"""")
        val ATTR_TIME = Regex("""\btimestamp="(\d+)"""")
        val ATTR_TYPE = Regex("""\bttype="(\w)"""")
        val ATTR_HIDDEN = Regex("""\bhidden="(\w+)"""")
        val NAME = Regex("""<name>(.*?)</name>""", RegexOption.DOT_MATCHES_ALL)
        val DETAIL = Regex("""<detail>(.*?)</detail>""", RegexOption.DOT_MATCHES_ALL)
        val NPWR = Regex("""NPWR\d{5}_\d{2}""")

        // shadPS4's trophy key: keys.json (v.0.20.0) as nlohmann writes it, or an older config.toml's [Keys].
        val TROPHY_KEY_JSON = Regex(""""ReleaseTrophyKey"\s*:\s*"([0-9A-Fa-f]{32})"""")
        val TROPHY_KEY_TOML = Regex("""(?m)^\s*TrophyKey\s*=\s*["']([0-9A-Fa-f]{32})["']""")
        val NUMERIC = Regex("""&#(\d+);""")
        val VITA_TITLE = Regex("""[A-Z]{4}\d{5}""")
        val X360_TITLE = Regex("""[0-9A-F]{8}""")
        val XUID = Regex("""[0-9A-Fa-f]{16}""")

        // Vita3K's TROPUSR.DAT: magic, then 128 trophies' bits, and their times from byte 112.
        const val VITA_MAGIC = 0x12D5819AL
        const val VITA_TROPHIES = 128
        const val VITA_TIMES = 112

        // Xenia's GPD sections (gpd_info.h GpdSection), the title's own id, and achievement flags.
        const val GPD_ACHIEVEMENT = 1
        const val GPD_IMAGE = 2
        const val GPD_STRING = 5
        const val GPD_TITLE = 0x8000L
        const val GPD_ACHIEVED = 0x20000L
        const val GPD_SHOW_UNACHIEVED = 0x8L
        const val MAX_GPD = 8 * 1024 * 1024
        const val MAX_ENTRIES = 65_536
        const val MAX_PICTURE = 512 * 1024

        /** Milliseconds from 1601 (FILETIME's start) to 1970. */
        const val FILETIME_EPOCH_MS = 11_644_473_600_000L

        /** Seconds from 1970 to 2200: a time past that is taken as unset rather than believed. */
        const val MAX_SECONDS = 7_258_118_400L
        val POINTS = mapOf("platinum" to 180, "gold" to 90, "silver" to 30, "bronze" to 15)
    }
}
