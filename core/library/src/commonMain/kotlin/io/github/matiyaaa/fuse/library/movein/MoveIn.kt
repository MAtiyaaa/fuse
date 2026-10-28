package io.github.matiyaaa.fuse.library.movein

import io.github.matiyaaa.fuse.model.PlayStatus

/** A launcher Fuse can bring the person's history in from when they move in. */
enum class MoveInSource(val label: String) {
    /** ES-DE (and EmulationStation, Batocera, RetroBat): favourites, hidden games, last played, ratings, completed games and custom collections. */
    ESDE("ES-DE"),

    /** RetroArch: play time and last played from its runtime logs, and its favourites. */
    RETROARCH("RetroArch"),

    /** Pegasus: its favourites. */
    PEGASUS("Pegasus"),

    /** Steam: play time of the Steam games Fuse lists, only when asked for. */
    STEAM("Steam"),

    /** RetroAchievements: the person's Want to Play list, as Want to play in the Journal. */
    RETROACHIEVEMENTS("RetroAchievements"),
}

/**
 * What another launcher knew about one game, to be matched to a game here by its file ([path]),
 * or by its Steam id. Every mark is what the launcher said; nothing is guessed.
 */
data class MovedGame(
    /** The game's file as the launcher names it: a full path, or one inside its system's folder. */
    val path: String,
    /** The launcher's name for the game's system (its folder's name), when it says. */
    val system: String? = null,
    val favorite: Boolean = false,
    val hidden: Boolean = false,
    /** Time played, when the launcher counted it. */
    val seconds: Long = 0,
    val lastPlayedAt: Long? = null,
    val status: PlayStatus? = null,
    /** Out of ten; 0 is none. */
    val rating: Int = 0,
    /** Steam's id for a Steam game. */
    val steamAppId: Long? = null,
) {
    /** The file's own name, without its folders. */
    val fileName: String get() = path.replace('\\', '/').trimEnd('/').substringAfterLast('/')

    /** Whether it says anything worth bringing in. */
    val says: Boolean get() = favorite || hidden || seconds > 0 || lastPlayedAt != null || status != null || rating > 0
}

/** A collection of the person's own in another launcher: its name and its games' files. */
data class MovedCollection(val name: String, val paths: List<String>)

/** What was found of one launcher: where it was, what it says about each game, and its collections. */
data class MoveInFind(
    val source: MoveInSource,
    val where: String,
    val games: List<MovedGame>,
    val collections: List<MovedCollection> = emptyList(),
)

/** One game here, as moving in matches it: its id, system, file and Steam id. */
data class HereGame(val id: Long, val platform: String, val path: String, val launchPath: String, val steamAppId: Long? = null)

/**
 * Matches what launchers said to the games here: the same file first (a launcher reading the same
 * folders), then the same file name (or the name without its extension, as RetroArch keeps it) on
 * a system the launcher's folder name means, then the only game here of that name; a Steam game by
 * its Steam id. Two games of one name on one system,
 * or a name no game here has, match nothing rather than the wrong game.
 */
object MoveInMatch {
    fun match(moved: List<MovedGame>, here: List<HereGame>, platformOf: (String) -> String?): Map<MovedGame, Long> {
        fun norm(p: String) = p.replace('\\', '/').trimEnd('/').lowercase()
        val byPath = HashMap<String, Long>()
        for (g in here) {
            byPath[norm(g.path)] = g.id
            byPath[norm(g.launchPath)] = g.id
        }
        val byName = HashMap<String, MutableSet<HereGame>>()
        val byStem = HashMap<String, MutableSet<HereGame>>()
        for (g in here) for (p in listOf(g.path, g.launchPath)) {
            val name = norm(p).substringAfterLast('/')
            byName.getOrPut(name) { LinkedHashSet() } += g
            byStem.getOrPut(name.substringBeforeLast('.')) { LinkedHashSet() } += g
        }
        val bySteam = here.filter { it.steamAppId != null }.associate { it.steamAppId!! to it.id }
        val out = LinkedHashMap<MovedGame, Long>()
        for (m in moved) {
            val id = m.steamAppId?.let { bySteam[it] }
                ?: byPath[norm(m.path)]
                ?: run {
                    // RetroArch names a game by its file without the extension.
                    val key = m.fileName.lowercase()
                    val named = (byName[key] ?: byStem[key]).orEmpty().distinctBy { it.id }
                    val platform = m.system?.let(platformOf)
                    val onSystem = if (platform != null) named.filter { it.platform == platform } else emptyList()
                    onSystem.singleOrNull()?.id ?: named.singleOrNull()?.takeIf { platform == null || onSystem.isEmpty() }?.id
                }
            if (id != null) out[m] = id
        }
        return out
    }
}
