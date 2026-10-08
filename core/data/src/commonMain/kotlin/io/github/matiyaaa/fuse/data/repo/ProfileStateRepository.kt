package io.github.matiyaaa.fuse.data.repo

import io.github.matiyaaa.fuse.data.db.FuseDatabase
import io.github.matiyaaa.fuse.model.FilenameTags
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/** One game's person-owned state, as stored. */
data class UserGameRow(
    val id: Long,
    val platform: String,
    /** The title as the file names it (the same on every copy), not the one the person gave it. */
    val title: String,
    val customTitle: String?,
    val serial: String?,
    val favorite: Boolean,
    val hidden: Boolean,
    val pinned: Boolean,
    val lastPlayed: Long?,
    val trackedSeconds: Long,
    val sessionCount: Long,
    val emulator: String?,
)

data class SessionRow(val gameId: Long, val emulator: String?, val startedAt: Long, val endedAt: Long)

data class CollectionRow(val id: Long, val name: String, val order: Long, val createdAt: Long, val games: List<Long>)

/** A person's state over the library: their games' state, sessions and collections. */
data class UserState(val games: List<UserGameRow>, val sessions: List<SessionRow>, val collections: List<CollectionRow>)

/**
 * Reads and writes a person's state over the library, for Fuse Sync's profiles: favourites,
 * hidden and pinned games, custom titles, chosen emulators, play time, Last Played, sessions and
 * collections. Writing replaces it all in one transaction, so switching profiles never leaves half
 * of one person and half of another. The library itself (games, files, metadata) is untouched.
 */
class ProfileStateRepository(
    private val db: FuseDatabase,
    private val dispatcher: CoroutineDispatcher,
    private val clock: () -> Long,
) {
    private val q get() = db.profileStateQueries
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun read(): UserState = withContext(dispatcher) { readNow() }

    private fun readNow(): UserState {
        val games = q.userGames().executeAsList().map { r ->
            val serial = r.tags_json?.let { t -> runCatching { json.decodeFromString(FilenameTags.serializer(), t).serial }.getOrNull() }
            UserGameRow(
                r.id, r.platform_id, r.title_original, r.title_custom, serial,
                r.favorite != 0L, r.hidden != 0L, r.pinned != 0L, r.last_played_at, r.tracked_seconds, r.session_count, r.emulator_override,
            )
        }
        val sessions = q.endedSessions().executeAsList().mapNotNull { s -> s.ended_at?.let { SessionRow(s.game_id, s.emulator_id, s.started_at, it) } }
        val members = q.collectionMembers().executeAsList().groupBy({ it.collection_id }, { it.game_id })
        val collections = q.manualCollections().executeAsList().map { c -> CollectionRow(c.id, c.name, c.sort_order, c.created_at, members[c.id].orEmpty()) }
        return UserState(games, sessions, collections)
    }

    /**
     * Original identities retained by a semantic migration. Sync can use these aliases without
     * exposing the old package as a separate title or guessing from similar names.
     */
    suspend fun absorbedIdentities(): Map<UserGameRow, Long> = withContext(dispatcher) {
        val owners = db.gameAbsorptionQueries.selectAll().executeAsList().associate { it.child_id to it.owner_id }
        owners.mapNotNull { (child, owner) ->
            db.gameQueries.selectById(child).executeAsOneOrNull()?.let { r ->
                val serial = r.tags_json?.let { t -> runCatching { json.decodeFromString(FilenameTags.serializer(), t).serial }.getOrNull() }
                UserGameRow(r.id, r.platform_id, r.title_original, r.title_custom, serial, r.favorite != 0L,
                    r.hidden != 0L, r.pinned != 0L, r.last_played_at, r.tracked_seconds, r.session_count, r.emulator_override) to resolveOwner(owner, owners)
            }
        }.toMap()
    }

    private fun resolveOwner(id: Long, owners: Map<Long, Long>): Long {
        var current = id
        val seen = HashSet<Long>()
        while (seen.add(current)) current = owners[current] ?: return current
        return id // A corrupt relation must never reinterpret an unrelated record.
    }

    /** Old inactive-profile snapshots can still contain the retired package's local id. */
    private fun normalizeAbsorbed(state: UserState): UserState {
        val owners = db.gameAbsorptionQueries.selectAll().executeAsList().associate { it.child_id to it.owner_id }
        if (owners.isEmpty()) return state
        fun owner(id: Long) = resolveOwner(id, owners)
        val games = state.games.groupBy { owner(it.id) }.map { (id, rows) ->
            val base = rows.firstOrNull { it.id == id }
            if (base == null && rows.none { it.id in owners }) return@map rows.first()
            val identity = db.gameQueries.selectById(id).executeAsOneOrNull()
            if (identity == null) return@map rows.first()
            val serial = identity.tags_json?.let { t -> runCatching { json.decodeFromString(FilenameTags.serializer(), t).serial }.getOrNull() }
            UserGameRow(
                id = id, platform = identity.platform_id, title = identity.title_original,
                customTitle = base?.customTitle, serial = serial,
                favorite = rows.any { it.favorite }, hidden = base?.hidden ?: false,
                pinned = rows.any { it.pinned }, lastPlayed = rows.mapNotNull { it.lastPlayed }.maxOrNull(),
                trackedSeconds = rows.sumOf { it.trackedSeconds }, sessionCount = rows.sumOf { it.sessionCount },
                emulator = base?.emulator,
            )
        }
        return UserState(games, state.sessions.map { it.copy(gameId = owner(it.gameId)) }.distinct(),
            state.collections.map { it.copy(games = it.games.map(::owner).distinct()) })
    }

    /**
     * Puts [state] in place, touching only what differs (so the library's views see one small
     * change, not a rewrite): each game listed gets its state and games not listed are cleared to
     * no one's; sessions are matched by game and times; collections by when they were made
     * ([CollectionRow.createdAt], the same on every device), with [CollectionRow.id] ignored.
     */
    suspend fun write(state: UserState) = withContext(dispatcher) {
        val now = clock()
        db.transaction {
            val current = readNow()
            val canonical = normalizeAbsorbed(state)
            val byId = canonical.games.associateBy { it.id }
            for (r in current.games) {
                val g = byId[r.id]
                val want = g?.let { r.copy(customTitle = it.customTitle, favorite = it.favorite, hidden = it.hidden, pinned = it.pinned, lastPlayed = it.lastPlayed, trackedSeconds = it.trackedSeconds, sessionCount = it.sessionCount, emulator = it.emulator) }
                    ?: r.copy(customTitle = null, favorite = false, hidden = false, pinned = false, lastPlayed = null, trackedSeconds = 0, sessionCount = 0, emulator = null)
                if (want == r) continue
                q.setUserState(
                    favorite = if (want.favorite) 1 else 0,
                    hidden = if (want.hidden) 1 else 0,
                    pinned = if (want.pinned) 1 else 0,
                    lastPlayed = want.lastPlayed,
                    tracked = want.trackedSeconds,
                    sessions = want.sessionCount,
                    titleCustom = want.customTitle,
                    emulator = want.emulator,
                    now = now,
                    id = r.id,
                )
            }
            fun SessionRow.key() = Triple(gameId, startedAt, endedAt)
            val have = current.sessions.associateBy { it.key() }
            val want = canonical.sessions.associateBy { it.key() }
            for (k in have.keys - want.keys) q.deleteSession(k.first, k.second, k.third)
            for (k in want.keys - have.keys) want.getValue(k).let { q.insertSession(it.gameId, it.emulator, it.startedAt, it.endedAt) }
            val haveC = current.collections.associateBy { it.createdAt }
            val wantC = canonical.collections.associateBy { it.createdAt }
            for (k in haveC.keys - wantC.keys) haveC.getValue(k).let { q.clearMembers(it.id); q.deleteCollection(it.id) }
            for ((k, c) in wantC) {
                val existing = haveC[k]
                val id = if (existing == null) {
                    q.insertCollection(c.name, c.order, c.createdAt)
                    q.lastCollectionId().executeAsOne()
                } else {
                    if (existing.name != c.name || existing.order != c.order) q.updateCollection(c.name, c.order, existing.id)
                    if (existing.games == c.games) continue
                    q.clearMembers(existing.id)
                    existing.id
                }
                c.games.forEachIndexed { i, g -> q.insertMember(id, g, i.toLong()) }
            }
        }
    }
}
