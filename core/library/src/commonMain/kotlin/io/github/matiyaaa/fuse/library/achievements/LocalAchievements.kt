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
enum class LocalAchievementSource { STEAM, PS3_TROPHIES }

data class LocalAchievementSet(val source: LocalAchievementSource, val title: String?, val items: List<LocalAchievement>, val icon: String? = null)

/**
 * Reads achievements from what a game's own platform keeps on this computer, without accounts or
 * keys: Steam's stats cache ([steam]) and RPCS3's trophy folders ([ps3Trophies]). Only reads.
 */
class LocalAchievements(private val fs: FuseFileSystem) {

    // Steam

    /**
     * The achievements of Steam game [appId] as Steam's client last cached them: their names and
     * pictures from its schema (appcache/stats/UserGameStatsSchema_<app>.bin) and what is unlocked,
     * with when, from the newest user's stats (UserGameStats_<user>_<app>.bin). Null when the game
     * has no achievements or Steam has no record of them here.
     */
    suspend fun steam(steamRoots: List<String>, appId: Long): LocalAchievementSet? {
        for (root in steamRoots) {
            val stats = FsPath.join(root, "appcache", "stats")
            val schema = read(FsPath.join(stats, "UserGameStatsSchema_$appId.bin"), MAX_SCHEMA)?.let(BinaryVdf::parse) ?: continue
            val user = runCatching { fs.list(stats) }.getOrDefault(emptyList())
                .filter { it.name.startsWith("UserGameStats_") && it.name.endsWith("_$appId.bin") && !it.name.startsWith("UserGameStatsSchema") }
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

    // Shared

    private suspend fun dirNames(path: String): List<String> =
        runCatching { fs.list(path) }.getOrDefault(emptyList()).filter { it.isDirectory }.map { it.name }

    private suspend fun read(path: String, max: Int): ByteArray? {
        val size = runCatching { fs.stat(path) }.getOrNull()?.takeIf { !it.isDirectory }?.sizeBytes ?: return null
        if (size <= 0 || size > max) return null
        return runCatching { fs.readBytes(path, 0, size.toInt()) }.getOrNull()
    }

    private fun xml(s: String): String = s.trim()
        .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")

    private fun sameTitle(a: String, b: String): Boolean {
        fun plain(s: String) = s.lowercase().filter { it.isLetterOrDigit() }
        return plain(a).isNotEmpty() && plain(a) == plain(b)
    }

    private companion object {
        const val MAX_SCHEMA = 8 * 1024 * 1024
        const val MAX_STATS = 2 * 1024 * 1024
        const val MAX_CONF = 2 * 1024 * 1024
        const val MAX_USR = 1024 * 1024
        const val MAGIC = 0x818F54ADL

        /** PS3 ticks (microseconds since 0001-01-01) at the Unix epoch. */
        const val EPOCH_TICKS = 62_135_596_800_000_000L

        val TROPHY = Regex("""<trophy\s+id="(\d+)"\s+hidden="(\w+)"\s+ttype="(\w)"[^>]*>\s*<name>(.*?)</name>\s*<detail>(.*?)</detail>""", RegexOption.DOT_MATCHES_ALL)
        val TITLE = Regex("""<title-name>(.*?)</title-name>""", RegexOption.DOT_MATCHES_ALL)
        val POINTS = mapOf("platinum" to 180, "gold" to 90, "silver" to 30, "bronze" to 15)
    }
}
