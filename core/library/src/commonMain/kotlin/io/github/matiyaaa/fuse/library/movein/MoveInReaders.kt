package io.github.matiyaaa.fuse.library.movein

import io.github.matiyaaa.fuse.library.steam.Vdf
import io.github.matiyaaa.fuse.model.PlayStatus

/**
 * ES-DE's `gamelist.xml`, one per system (and EmulationStation's, Batocera's and RetroBat's, which
 * share it): each `<game>` or `<folder>` with its `path` inside the system's folder, and the
 * person's marks on it: `favorite`, `hidden`, `completed` (ES-DE), `rating` (0 to 1), `lastplayed`
 * (local time, `20231015T201500`) and `gametime` (Batocera's seconds played).
 */
object EsDeGamelist {
    private val entry = Regex("""<(game|folder)(?:\s[^>]*)?>(.*?)</\1>""", RegexOption.DOT_MATCHES_ALL)

    fun parse(xml: String, system: String, offsetMs: Long = 0): List<MovedGame> = entry.findAll(xml).mapNotNull { m ->
        val body = m.groupValues[2]
        fun tag(name: String) = Regex("""<$name>(.*?)</$name>""", RegexOption.DOT_MATCHES_ALL).find(body)?.groupValues?.get(1)?.let(::unescape)?.trim()
        val path = tag("path")?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        fun yes(name: String) = tag(name).equals("true", ignoreCase = true)
        MovedGame(
            path = path.removePrefix("./"),
            system = system,
            favorite = yes("favorite"),
            hidden = yes("hidden"),
            seconds = tag("gametime")?.toLongOrNull()?.coerceAtLeast(0) ?: 0,
            lastPlayedAt = tag("lastplayed")?.let { time(it, offsetMs) },
            status = if (yes("completed")) PlayStatus.BEATEN else null,
            rating = tag("rating")?.toDoubleOrNull()?.let { (it * 10 + 0.5).toInt().coerceIn(0, 10) } ?: 0,
        ).takeIf { it.says }
    }.toList()

    /** `20231015T201500`, local time ([offsetMs] from UTC), as epoch milliseconds. */
    fun time(text: String, offsetMs: Long = 0): Long? {
        val t = text.trim()
        if (t.length < 15 || t[8] != 'T') return null
        fun n(from: Int, to: Int) = t.substring(from, to).toIntOrNull()
        val y = n(0, 4) ?: return null
        val mo = n(4, 6) ?: return null
        val d = n(6, 8) ?: return null
        val h = n(9, 11) ?: return null
        val mi = n(11, 13) ?: return null
        val s = n(13, 15) ?: return null
        if (y < 1990 || mo !in 1..12 || d !in 1..31) return null
        return epochOf(y, mo, d, h, mi, s) - offsetMs
    }

    private fun unescape(s: String): String = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")
}

/**
 * ES-DE's custom collections, `collections/custom-<name>.cfg`: a game's file per line, with
 * `%ROMPATH%` standing for the ROM folder, so `%ROMPATH%/psx/Crash.chd` is Crash on psx.
 */
object EsDeCollection {
    fun parse(fileName: String, text: String): MovedCollection? {
        val name = fileName.removeSuffix(".cfg").removePrefix("custom-").trim().takeIf { it.isNotEmpty() } ?: return null
        val paths = text.lineSequence().map { it.trim().replace('\\', '/') }.filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { it.removePrefix("%ROMPATH%/") }.toList()
        return MovedCollection(name, paths).takeIf { paths.isNotEmpty() }
    }

    /** The system a collection's line names (`psx/Crash.chd` is psx), when it is under the ROM folder. */
    fun systemOf(line: String): String? = line.substringBefore('/', "").takeIf { it.isNotEmpty() && !line.startsWith("/") && !line.contains(':') }
}

/**
 * RetroArch's runtime logs, `playlists/logs/[core/]<game>.lrtl`: how long the game ran
 * (`"runtime": "12:34:56"`) and when it was last played (`"last_played": "2023-10-15 20:15:00"`,
 * local time), named after the game's file without its extension.
 */
object RetroArchRuntime {
    private val runtime = Regex(""""runtime"\s*:\s*"(\d+):(\d{1,2}):(\d{1,2})"""")
    private val last = Regex(""""last_played"\s*:\s*"(\d{4})-(\d{2})-(\d{2}) (\d{2}):(\d{2}):(\d{2})"""")

    fun parse(json: String, fileStem: String, offsetMs: Long = 0): MovedGame? {
        val r = runtime.find(json)?.groupValues
        val seconds = r?.let { (it[1].toLongOrNull() ?: 0) * 3_600 + (it[2].toLongOrNull() ?: 0) * 60 + (it[3].toLongOrNull() ?: 0) } ?: 0
        val at = last.find(json)?.groupValues?.let { g ->
            epochOf(g[1].toInt(), g[2].toInt(), g[3].toInt(), g[4].toInt(), g[5].toInt(), g[6].toInt()) - offsetMs
        }?.takeIf { it > 0 }
        return MovedGame(path = fileStem, seconds = seconds, lastPlayedAt = at).takeIf { it.says }
    }
}

/** RetroArch's favourites, `content_favorites.lpl`: a playlist whose items' `path`s are the games. */
object RetroArchFavourites {
    private val path = Regex(""""path"\s*:\s*"((?:[^"\\]|\\.)*)"""")

    fun parse(json: String): List<MovedGame> = path.findAll(json).map { it.groupValues[1].replace("\\\\", "\\").replace("\\/", "/") }
        .filter { it.isNotBlank() }
        // A game inside an archive is the archive's entry: `game.zip#game.sfc` is game.zip.
        .map { MovedGame(path = it.substringBefore('#'), favorite = true) }.toList()
}

/** Pegasus's favourites, `favorites.txt`: one game's file per line. */
object PegasusFavourites {
    fun parse(text: String): List<MovedGame> = text.lineSequence().map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .map { MovedGame(path = it, favorite = true) }.toList()
}

/**
 * Steam's own count of play time, in each account's `userdata/<id>/config/localconfig.vdf`:
 * `apps/<appid>` with `Playtime` (minutes) and `LastPlayed` (epoch seconds).
 */
object SteamPlaytime {
    fun parse(text: String): List<MovedGame> {
        val root = Vdf.parse(text)
        val store = (root["userlocalconfigstore"] as? Map<*, *>) ?: root
        val apps = (((store["software"] as? Map<*, *>)?.get("valve") as? Map<*, *>)?.get("steam") as? Map<*, *>)?.get("apps") as? Map<*, *>
            ?: return emptyList()
        return apps.mapNotNull { (id, v) ->
            val appId = (id as? String)?.toLongOrNull() ?: return@mapNotNull null
            val fields = v as? Map<*, *> ?: return@mapNotNull null
            val minutes = (fields["playtime"] as? String)?.toLongOrNull() ?: 0
            val last = (fields["lastplayed"] as? String)?.toLongOrNull()?.takeIf { it > 0 }?.times(1_000)
            MovedGame(path = "steam:$appId", seconds = minutes * 60, lastPlayedAt = last, steamAppId = appId).takeIf { it.says }
        }
    }
}

/** A local date and time as epoch milliseconds in UTC, by the civil calendar. */
internal fun epochOf(year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int): Long {
    val y = if (month <= 2) year - 1 else year
    val era = (if (y >= 0) y else y - 399) / 400
    val yoe = y - era * 400
    val mp = (month + 9) % 12
    val doy = (153 * mp + 2) / 5 + day - 1
    val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
    val days = era * 146_097L + doe - 719_468
    return ((days * 24 + hour) * 60 + minute) * 60_000L + second * 1_000L
}
