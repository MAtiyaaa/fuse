package io.github.matiyaaa.fuse.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/** A person on a Fuse Sync Host. Every save, state and setting that is theirs carries it. */
@Serializable
@JvmInline
value class ProfileId(val value: String)

/** One device that syncs: a handheld, a PC, a TV. Stable for the life of its Fuse install. */
@Serializable
@JvmInline
value class DeviceId(val value: String)

/**
 * A hybrid logical clock reading: wall time in milliseconds, a counter for readings in the same
 * millisecond (or behind a clock that went back), and the device that made it, so two readings
 * never tie. Ordering never trusts the wall clock alone: a device whose clock runs slow still moves
 * forward past everything it has seen.
 */
@Serializable
data class Hlc(val millis: Long, val counter: Int, val device: String) : Comparable<Hlc> {
    override fun compareTo(other: Hlc): Int = compareValuesBy(this, other, Hlc::millis, Hlc::counter, Hlc::device)

    companion object {
        val ZERO = Hlc(0, 0, "")
    }
}

/**
 * Makes [Hlc] readings for one device: always later than anything it made or saw before. Safe to
 * read from several threads at once (saves are captured while records are sent).
 */
class HlcClock(private val device: String, private val wall: () -> Long) {
    private var last = Hlc.ZERO

    /** A new reading, later than every earlier one. */
    fun now(): Hlc = synchronized(this) {
        val w = wall()
        last = if (w > last.millis) Hlc(w, 0, device) else Hlc(last.millis, last.counter + 1, device)
        last
    }

    /** Takes in a reading from elsewhere, so the next one is later than it too. */
    fun seen(other: Hlc): Unit = synchronized(this) {
        if (other > last) last = Hlc(other.millis, other.counter, device)
    }
}

/** A value set at [at]: the later setting wins, everywhere, whatever order they arrive in. */
@Serializable
data class Lww<T>(val value: T, val at: Hlc) {
    fun merge(other: Lww<T>?): Lww<T> = if (other == null || at >= other.at) this else other
}

/** Merges two optional registers: the later one, or whichever exists. */
fun <T> Lww<T>?.mergeWith(other: Lww<T>?): Lww<T>? = when {
    this == null -> other
    other == null -> this
    else -> merge(other)
}

/**
 * Who a game is, across devices, independent of where its file lives: its system and the best
 * identity the library has for it (a serial, else the content hash, else the cleaned title). The
 * same game on a Steam Deck and an Android handheld has the same key, so its saves and play time
 * follow the person, not the path.
 */
@Serializable
data class GameKey(val platform: String, val identity: String) {
    /** One string, safe as a map key and inside a path: `platform:identity`. */
    val id: String get() = "$platform:$identity"

    companion object {
        private val SAFE = Regex("[^a-z0-9._-]+")
        private val TAGS = Regex("\\(.*?\\)|\\[.*?]")

        /** The key for a game known by [serial], [contentHash] or [title], in that order of trust. */
        fun of(platform: String, serial: String?, contentHash: String?, title: String): GameKey {
            val normalizedSerial = serial?.lowercase()?.filter { it.isLetterOrDigit() }?.takeIf { it.isNotEmpty() }
            val normalizedHash = contentHash?.lowercase()?.replace(SAFE, "")?.takeIf { it.isNotEmpty() }
            val identity = when {
                // Serials are written many ways (SLUS-00067, slus_000.67, SLUS00067): letters and digits only.
                normalizedSerial != null -> "s." + normalizedSerial
                normalizedHash != null -> "h." + normalizedHash
                // Region and version tags differ between copies of one game: "Pokemon Ruby (USA)" is "pokemon-ruby".
                else -> "t." + title.lowercase().replace(TAGS, "").replace(SAFE, "-").trim('-')
            }
            return GameKey(platform.lowercase().replace(SAFE, "-"), identity)
        }

        fun parse(id: String): GameKey? {
            val i = id.indexOf(':')
            if (i <= 0 || i == id.lastIndex) return null
            return GameKey(id.substring(0, i), id.substring(i + 1))
        }
    }
}

/** One play session, as a device saw it. Sessions merge by [id], so none is ever counted twice. */
@Serializable
data class SessionEntry(
    val id: String,
    val device: String,
    val startedAt: Long,
    val endedAt: Long,
    val emulator: String? = null,
) {
    val seconds: Long get() = ((endedAt - startedAt) / 1000).coerceAtLeast(0)

    companion object {
        /** A session's id from its times, so a device names the same session the same way however it learns of it. */
        fun idOf(startedAt: Long, endedAt: Long): String = "t:$startedAt-$endedAt"
    }
}

/**
 * What a profile knows about one game, made to merge: play time is a counter per device (each
 * device only ever adds to its own, so 30 minutes on one and 20 on another offline are 50 once
 * they meet), sessions are a set, Last Played is the latest, and favourite, hidden and pinned are
 * last-writer-wins registers.
 */
@Serializable
data class GameRecord(
    val key: GameKey,
    val playSeconds: Map<String, Long> = emptyMap(),
    val sessions: Map<String, SessionEntry> = emptyMap(),
    val lastPlayed: Long? = null,
    val favorite: Lww<Boolean>? = null,
    val hidden: Lww<Boolean>? = null,
    val pinned: Lww<Boolean>? = null,
    /** Taken off Continue Playing at this time (it comes back once played again after). */
    val continueDismissed: Lww<Long?>? = null,
    /** The title the person gave it, if any. */
    val title: Lww<String?>? = null,
    /** The emulator the person chose for it, by id (portable across devices that have it). */
    val emulator: Lww<String?>? = null,
    /** Its name as a device last showed it, for the Hub (not the person's own name for it: that's [title]). */
    val name: String? = null,
) {
    val totalSeconds: Long get() = playSeconds.values.sum()

    fun merge(other: GameRecord): GameRecord {
        require(key == other.key) { "Merging different games" }
        return GameRecord(
            key = key,
            playSeconds = (playSeconds.keys + other.playSeconds.keys).associateWith { d -> maxOf(playSeconds[d] ?: 0, other.playSeconds[d] ?: 0) },
            sessions = sessions + other.sessions.filterKeys { it !in sessions },
            lastPlayed = listOfNotNull(lastPlayed, other.lastPlayed).maxOrNull(),
            favorite = favorite.mergeWith(other.favorite),
            hidden = hidden.mergeWith(other.hidden),
            pinned = pinned.mergeWith(other.pinned),
            continueDismissed = continueDismissed.mergeWith(other.continueDismissed),
            title = title.mergeWith(other.title),
            emulator = emulator.mergeWith(other.emulator),
            name = other.name ?: name,
        )
    }

    /** This device played [session]: its own counter grows, the session joins the set. */
    fun played(session: SessionEntry): GameRecord {
        if (session.id in sessions) return this
        val mine = (playSeconds[session.device] ?: 0) + session.seconds
        return copy(
            playSeconds = playSeconds + (session.device to mine),
            sessions = sessions + (session.id to session),
            lastPlayed = maxOf(lastPlayed ?: 0, session.endedAt),
        )
    }
}

/**
 * A collection: its name and whether it was deleted as registers, and each game's membership as
 * its own register, so adding a game on one device and removing another on a second both hold.
 */
@Serializable
data class CollectionRecord(
    val id: String,
    val name: Lww<String>,
    val deleted: Lww<Boolean>? = null,
    val members: Map<String, Lww<Boolean>> = emptyMap(),
    val order: Lww<Int>? = null,
) {
    val games: List<String> get() = members.filterValues { it.value }.keys.sorted()

    fun merge(other: CollectionRecord): CollectionRecord = CollectionRecord(
        id = id,
        name = name.merge(other.name),
        deleted = deleted.mergeWith(other.deleted),
        members = (members.keys + other.members.keys).associateWith { g -> members[g].mergeWith(other.members[g])!! },
        order = order.mergeWith(other.order),
    )
}

/**
 * Everything a profile carries that isn't a file: per game records, collections, and its settings
 * (theme, Home, the quick menu, player and second-screen preferences...) as registers by key. It
 * merges in any order and any number of times to the same result.
 */
@Serializable
data class ProfileMeta(
    val games: Map<String, GameRecord> = emptyMap(),
    val collections: Map<String, CollectionRecord> = emptyMap(),
    val settings: Map<String, Lww<JsonElement>> = emptyMap(),
) {
    fun merge(other: ProfileMeta): ProfileMeta = ProfileMeta(
        games = (games.keys + other.games.keys).associateWith { k ->
            val a = games[k]
            val b = other.games[k]
            if (a == null) b!! else if (b == null) a else a.merge(b)
        },
        collections = (collections.keys + other.collections.keys).associateWith { k ->
            val a = collections[k]
            val b = other.collections[k]
            if (a == null) b!! else if (b == null) a else a.merge(b)
        },
        settings = (settings.keys + other.settings.keys).associateWith { k -> settings[k].mergeWith(other.settings[k])!! },
    )

    fun game(key: GameKey): GameRecord = games[key.id] ?: GameRecord(key)

    fun withGame(record: GameRecord): ProfileMeta = copy(games = games + (record.key.id to record))

    /**
     * These records by each game's one id across devices ([aliases]: every id a game has been known
     * by, to its one id): records kept under an id since settled on another join that game's
     * record, and collections name their games the same way. Merging is safe: counters take the
     * larger, sessions join by id, settings take the later.
     */
    fun byIds(aliases: Map<String, String>): ProfileMeta {
        if (aliases.isEmpty()) return this
        fun moved(id: String) = aliases[id]?.let { it != id } == true
        if (games.keys.none(::moved) && collections.values.none { it.members.keys.any(::moved) }) return this
        val out = HashMap<String, GameRecord>()
        for ((id, record) in games) {
            val key = aliases[id]?.takeIf { it != id }?.let(GameKey::parse)
            val moved = if (key != null) record.copy(key = key) else record
            val at = key?.id ?: id
            out[at] = out[at]?.copy(key = moved.key)?.merge(moved) ?: moved
        }
        val cols = collections.mapValues { (_, c) ->
            val members = HashMap<String, Lww<Boolean>>()
            for ((g, v) in c.members) {
                val at = aliases[g] ?: g
                members[at] = members[at].mergeWith(v)!!
            }
            c.copy(members = members)
        }
        return copy(games = out, collections = cols)
    }

    /**
     * These records with every choice (favourites, names, settings, collections) stamped [at]: put
     * into another profile that is the same person, anything it already says wins and these only
     * fill what it doesn't. Play time and sessions keep their counts, so time played joins up.
     */
    fun stampedAt(at: Hlc): ProfileMeta {
        fun <T> Lww<T>?.re(): Lww<T>? = this?.copy(at = at)
        return ProfileMeta(
            games = games.mapValues { (_, g) ->
                g.copy(favorite = g.favorite.re(), hidden = g.hidden.re(), pinned = g.pinned.re(), continueDismissed = g.continueDismissed.re(), title = g.title.re(), emulator = g.emulator.re())
            },
            collections = collections.mapValues { (_, c) ->
                c.copy(name = c.name.copy(at = at), deleted = c.deleted.re(), members = c.members.mapValues { (_, m) -> m.copy(at = at) }, order = c.order.re())
            },
            settings = settings.mapValues { (_, v) -> v.copy(at = at) },
        )
    }

    /** The games played most recently first: Continue Playing, as every device of the profile sees it. */
    fun continuePlaying(): List<GameRecord> = games.values
        .filter { r -> r.lastPlayed != null && r.hidden?.value != true && (r.continueDismissed?.value ?: 0) < (r.lastPlayed ?: 0) }
        .sortedByDescending { it.lastPlayed }
}

/** Highest observed register revision, used to seed clocks after restart and before new edits. */
fun ProfileMeta.latestRevision(): Hlc {
    var latest = Hlc.ZERO
    fun observe(value: Hlc?) { if (value != null && value > latest) latest = value }
    settings.values.forEach { observe(it.at) }
    games.values.forEach { game ->
        observe(game.favorite?.at); observe(game.hidden?.at); observe(game.pinned?.at)
        observe(game.continueDismissed?.at); observe(game.title?.at); observe(game.emulator?.at)
    }
    collections.values.forEach { collection ->
        observe(collection.name.at); observe(collection.deleted?.at); observe(collection.order?.at)
        collection.members.values.forEach { observe(it.at) }
    }
    return latest
}
