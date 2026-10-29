package io.github.matiyaaa.fuse.library.recommend

import io.github.matiyaaa.fuse.library.series.SeriesDetector
import io.github.matiyaaa.fuse.library.series.SeriesInput

/**
 * A game as recommendations see it: what it is (its system, title, the details Fuse found for it)
 * and what this person did with it. Everything comes from this device's own library; nothing is
 * looked up or sent anywhere.
 */
data class RecGame<K>(
    val key: K,
    val platform: String,
    val title: String,
    val favorite: Boolean = false,
    val lastPlayedAt: Long? = null,
    val playSeconds: Long = 0,
    val sessions: Int = 0,
    val addedAt: Long = 0,
    val year: Int? = null,
    val genres: List<String> = emptyList(),
    val developer: String? = null,
    val franchise: String? = null,
    /** 0 to 100, from the game's details. */
    val rating: Int? = null,
    /** Where the person is with it, in their Journal. */
    val status: io.github.matiyaaa.fuse.model.PlayStatus? = null,
    /** When [status] was set. */
    val statusAt: Long? = null,
    /** The person's own rating out of ten; 0 for none. */
    val stars: Int = 0,
) {
    val played: Boolean get() = sessions > 0 || playSeconds > 0 || lastPlayedAt != null
}

/** Why a game is suggested. */
enum class RecKind {
    /** A favourite not played in a long while, or never. */
    FORGOTTEN_FAVORITE,

    /** The next game of a series this person plays. */
    NEXT_IN_SERIES,

    /** From the maker of a game this person plays a lot. */
    SAME_MAKER,

    /** The same kind of game as one this person plays a lot. */
    SAME_GENRE,

    /** A game this person put real time into, then left. */
    BACK_TO,

    /** Well rated, never played. */
    WELL_RATED,

    /** New in the library and not played yet. */
    NEW_ARRIVAL,

    /** On the person's own Up Next list (their Journal). */
    UP_NEXT,
}

/** A suggestion and the reason for it, in words. */
data class Recommendation<K>(val key: K, val kind: RecKind, val reason: String, val score: Double)

/**
 * Suggestions from what someone owns and has done with it, each with its reason: a favourite left
 * untouched, the next game of a series they play, games from the makers and of the kinds they
 * play most, a game they put time into and left, and well rated or new games they haven't
 * started. It works from the library alone, on the device, so it works offline and tells no one
 * what anyone plays. The same library gives the same suggestions (no randomness), and games played
 * in the last [RECENT_DAYS] days are left to Continue Playing.
 *
 * The person's Journal speaks louder than play time: what they want to play next leads, what they
 * beat, completed or dropped is never suggested again, their own high ratings lead to more of the
 * same, and a game they dropped never does.
 */
object Recommender {
    const val DAY_MS = 86_400_000L
    const val RECENT_DAYS = 14
    const val FORGOTTEN_DAYS = 60
    const val BACK_TO_DAYS = 90

    /** Time in a game that says someone likes it. */
    const val LIKED_SECONDS = 2 * 3_600L

    /** Time that counts as having really started a game. */
    const val STARTED_SECONDS = 20 * 60L

    /** A rating worth pointing out. */
    const val WELL_RATED = 80

    /** How many games' makers and kinds lead suggestions. */
    private const val SEEDS = 5

    /** In a suggestion list, a reason gives way to another after this many in a row. */
    private const val RUN = 2

    fun <K> recommend(games: List<RecGame<K>>, now: Long, limit: Int = 20): List<Recommendation<K>> {
        if (games.isEmpty()) return emptyList()
        val series = seriesOf(games)
        val byKey = games.associateBy { it.key }
        val recent = now - RECENT_DAYS * DAY_MS
        // Not suggested: what is being played now (Continue Playing has it), and what is done with.
        fun open(g: RecGame<K>) = (g.lastPlayedAt ?: 0) < recent && g.status?.settled != true
        val found = LinkedHashMap<K, Recommendation<K>>()
        fun offer(r: Recommendation<K>) {
            val g = byKey[r.key] ?: return
            if (!open(g)) return
            val had = found[r.key]
            if (had == null || r.score > had.score) found[r.key] = r
        }

        // The person's own Up Next, the longest waiting a little ahead.
        for (g in games) {
            if (g.status != io.github.matiyaaa.fuse.model.PlayStatus.WANT) continue
            val waited = g.statusAt?.let { (now - it).coerceAtLeast(0) / DAY_MS.toDouble() } ?: 0.0
            val why = if (waited >= 14) "On your Up Next list for ${since(now - g.statusAt!!)}" else "On your Up Next list"
            offer(Recommendation(g.key, RecKind.UP_NEXT, why, 95.0 + minOf(5.0, waited / 30.0)))
        }

        // Favourites left alone.
        for (g in games) {
            if (!g.favorite) continue
            val last = g.lastPlayedAt
            when {
                last == null && !g.played -> offer(Recommendation(g.key, RecKind.FORGOTTEN_FAVORITE, "A favourite you haven't played yet", 90.0))
                last != null && now - last >= FORGOTTEN_DAYS * DAY_MS ->
                    offer(Recommendation(g.key, RecKind.FORGOTTEN_FAVORITE, "A favourite, last played ${since(now - last)} ago", 80.0 + minOf(10.0, (now - last) / (30.0 * DAY_MS))))
            }
        }

        // The next game of each series someone has played.
        for ((name, members) in series) {
            val played = members.filter { it.played }
            if (played.isEmpty()) continue
            val latest = played.maxWith(compareBy({ it.year ?: 0 }, { it.lastPlayedAt ?: 0 }))
            val waiting = members.filter { !it.played }
            val next = waiting.filter { latest.year != null && it.year != null && it.year >= latest.year }.minByOrNull { it.year!! }
                ?: waiting.minByOrNull { it.year ?: Int.MAX_VALUE }
                ?: continue
            val interest = played.sumOf { it.playSeconds }.coerceAtMost(40 * 3_600L) / 3_600.0
            offer(Recommendation(next.key, RecKind.NEXT_IN_SERIES, "Next in the $name series, after ${latest.title}", 70.0 + interest / 2))
        }

        // What someone plays most leads to more of the same: its makers and its kinds.
        val seeds = games.filter { (it.playSeconds >= STARTED_SECONDS || (it.favorite && it.played) || it.stars >= LIKED_STARS) && it.status != io.github.matiyaaa.fuse.model.PlayStatus.DROPPED }
            .sortedWith(compareByDescending<RecGame<K>> { weight(it, now) }.thenBy { it.title })
            .take(SEEDS)
        val seedKeys = seeds.map { it.key }.toSet()
        val fresh = games.filter { it.key !in seedKeys && it.playSeconds < STARTED_SECONDS }
        for ((rank, seed) in seeds.withIndex()) {
            val lead = 60.0 - rank * 4
            val maker = seed.developer?.trim()?.takeIf { it.isNotEmpty() }
            // Its own series is the series suggestion's to make.
            val seedSeries = seriesName(series, seed)
            if (maker != null) {
                fresh.filter { it.developer?.trim().equals(maker, ignoreCase = true) && (seedSeries == null || seriesName(series, it) != seedSeries) }
                    .sortedWith(compareByDescending<RecGame<K>> { it.rating ?: 0 }.thenBy { it.title })
                    .take(2)
                    .forEach { offer(Recommendation(it.key, RecKind.SAME_MAKER, "From $maker, who made ${seed.title}", lead + (it.rating ?: 0) / 20.0)) }
            }
            val kinds = seed.genres.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
            if (kinds.isNotEmpty()) {
                fresh.mapNotNull { g ->
                    val shared = g.genres.map { it.trim() }.filter { k -> kinds.any { it.equals(k, ignoreCase = true) } }
                    if (shared.isEmpty()) null else g to shared
                }
                    .sortedWith(compareByDescending<Pair<RecGame<K>, List<String>>> { it.second.size }.thenByDescending { it.first.platform == seed.platform }.thenByDescending { it.first.rating ?: 0 }.thenBy { it.first.title })
                    .take(2)
                    .forEach { (g, shared) -> offer(Recommendation(g.key, RecKind.SAME_GENRE, "${shared.first()}, like ${seed.title}", lead - 8 + shared.size * 2 + (g.rating ?: 0) / 25.0)) }
            }
        }

        // A game someone put real time into, then left.
        for (g in games) {
            val last = g.lastPlayedAt ?: continue
            if (g.playSeconds >= LIKED_SECONDS && now - last >= BACK_TO_DAYS * DAY_MS) {
                offer(Recommendation(g.key, RecKind.BACK_TO, "${hours(g.playSeconds)} played, last ${since(now - last)} ago", 50.0 + minOf(10.0, g.playSeconds / 36_000.0)))
            }
        }

        // Never started: the well rated, and the newly added.
        for (g in games) {
            if (g.played) continue
            val rating = g.rating
            if (rating != null && rating >= WELL_RATED) offer(Recommendation(g.key, RecKind.WELL_RATED, "Rated $rating, not played yet", 40.0 + (rating - WELL_RATED) / 2.0))
            if (g.addedAt > 0 && now - g.addedAt <= RECENT_DAYS * DAY_MS) offer(Recommendation(g.key, RecKind.NEW_ARRIVAL, "New in your library, not played yet", 35.0 + (g.addedAt - (now - RECENT_DAYS * DAY_MS)).toDouble() / DAY_MS / 2))
        }

        return mix(found.values.sortedWith(compareByDescending<Recommendation<K>> { it.score }.thenBy { byKey[it.key]?.title.orEmpty() }), limit)
    }

    /**
     * Games like [key]: the rest of its series first, then its maker's, then games sharing its kinds
     * (most shared first), each with why. Played ones come after the ones still waiting.
     */
    fun <K> similar(key: K, games: List<RecGame<K>>, limit: Int = 12): List<Recommendation<K>> {
        val to = games.firstOrNull { it.key == key } ?: return emptyList()
        val titles = games.associate { it.key to it.title }
        val series = seriesOf(games)
        val own = seriesName(series, to)
        val maker = to.developer?.trim()?.takeIf { it.isNotEmpty() }
        val kinds = to.genres.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
        return games.asSequence().filter { it.key != key }.mapNotNull { g ->
            val shared = g.genres.filter { it.trim().lowercase() in kinds }
            when {
                own != null && seriesName(series, g) == own -> Recommendation(g.key, RecKind.NEXT_IN_SERIES, "Also in the $own series", 100.0)
                maker != null && g.developer?.trim().equals(maker, ignoreCase = true) -> Recommendation(g.key, RecKind.SAME_MAKER, "Also from $maker", 70.0 + shared.size)
                shared.isNotEmpty() -> Recommendation(g.key, RecKind.SAME_GENRE, shared.joinToString(", "), 40.0 + shared.size * 5 + if (g.platform == to.platform) 2 else 0)
                else -> null
            }?.let { r -> r.copy(score = r.score - if (g.played) 30 else 0) }
        }.sortedWith(compareByDescending<Recommendation<K>> { it.score }.thenBy { titles[it.key].orEmpty() }).take(limit).toList()
    }

    /**
     * One game to put in the spotlight today, on Home or a system's page ([platform]): one of the
     * best suggestions, a different one each day, else any game from the collection. Null with no
     * games.
     */
    fun <K> spotlight(games: List<RecGame<K>>, now: Long, platform: String? = null, offsetMs: Long = 0): Recommendation<K>? {
        val pool = if (platform == null) games else games.filter { it.platform == platform }
        if (pool.isEmpty()) return null
        val day = (now + offsetMs).floorDiv(DAY_MS)
        val picks = recommend(pool, now, SPOTLIGHT_POOL)
        if (picks.isNotEmpty()) return picks[day.mod(picks.size.toLong()).toInt()]
        // Nothing to suggest yet: a game from the collection, the same one all day.
        val sorted = pool.sortedBy { it.title.lowercase() }
        val g = sorted[(day * 7 + 3).mod(sorted.size.toLong()).toInt()]
        val why = when {
            g.favorite -> "One of your favourites"
            !g.played -> "From your collection, not played yet"
            else -> "From your collection"
        }
        return Recommendation(g.key, RecKind.NEW_ARRIVAL, why, 0.0)
    }

    private const val SPOTLIGHT_POOL = 10

    /**
     * How much a played game says about someone: time in it, more when recent, more when a
     * favourite, and the person's own stars most of all (a five-star game counts like a favourite twice).
     */
    private fun weight(g: RecGame<*>, now: Long): Double {
        val hours = g.playSeconds / 3_600.0
        val age = g.lastPlayedAt?.let { (now - it).coerceAtLeast(0) / DAY_MS.toDouble() } ?: 365.0
        return hours * (1.0 + 30.0 / (30.0 + age)) + (if (g.favorite) 5.0 else 0.0) + g.stars
    }

    /** The person's own rating (out of ten) that says they liked a game: four stars. */
    private const val LIKED_STARS = 8

    /** Best first, but never more than [RUN] of one reason in a row while others wait. */
    private fun <K> mix(sorted: List<Recommendation<K>>, limit: Int): List<Recommendation<K>> {
        val left = sorted.toMutableList()
        val out = ArrayList<Recommendation<K>>()
        while (left.isNotEmpty() && out.size < limit) {
            val run = out.takeLast(RUN)
            val blocked = run.size == RUN && run.all { it.kind == run.first().kind }
            val i = if (blocked) left.indexOfFirst { it.kind != run.first().kind }.takeIf { it >= 0 } ?: 0 else 0
            out += left.removeAt(i)
        }
        return out
    }

    private fun <K> seriesOf(games: List<RecGame<K>>): Map<String, List<RecGame<K>>> {
        val byKey = games.associateBy { it.key }
        return SeriesDetector.detect(games.map { SeriesInput(it.key, it.title, it.franchise) })
            .associate { s -> s.name to s.members.mapNotNull { byKey[it] } }
    }

    private fun <K> seriesName(series: Map<String, List<RecGame<K>>>, g: RecGame<K>): String? =
        series.entries.firstOrNull { (_, members) -> members.any { it.key == g.key } }?.key

    /** "3 months", "5 weeks", "a year": a long time, roughly. */
    internal fun since(ms: Long): String {
        val days = ms / DAY_MS
        return when {
            days >= 730 -> "${days / 365} years"
            days >= 365 -> "a year"
            days >= 60 -> "${days / 30} months"
            days >= 14 -> "${days / 7} weeks"
            days >= 2 -> "$days days"
            else -> "a day"
        }
    }

    /** "12 hours", "1 hour". */
    internal fun hours(seconds: Long): String {
        val h = seconds / 3_600
        return if (h == 1L) "1 hour" else "$h hours"
    }
}
