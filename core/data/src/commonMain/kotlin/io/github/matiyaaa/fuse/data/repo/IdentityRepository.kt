package io.github.matiyaaa.fuse.data.repo

import io.github.matiyaaa.fuse.data.db.FuseDatabase
import io.github.matiyaaa.fuse.data.ioDispatcher
import io.github.matiyaaa.fuse.model.GameId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/** One thing Fuse knows a game by ([IdentityRepository]). */
data class IdentityFact(val kind: String, val value: String, val source: String)

/** HowLongToBeat's answer for a game's title, kept a while; [hltbId] 0 means it has no such game. */
data class HltbCached(val hltbId: Long, val json: String, val fetchedAt: Long)

/**
 * What Fuse knows each game by beyond its own row (Fuse Identity): serials read from a game or its
 * name, content hashes, the RetroAchievements hash, a HowLongToBeat game chosen for it; the pairs a
 * person said are different games; and HowLongToBeat's times by title.
 */
class IdentityRepository(
    private val db: FuseDatabase,
    private val dispatcher: CoroutineDispatcher = ioDispatcher,
    private val clock: () -> Long,
) {
    private val q get() = db.gameIdentityQueries

    suspend fun forGame(id: GameId): List<IdentityFact> = withContext(dispatcher) {
        q.forGame(id.value).executeAsList().map { IdentityFact(it.kind, it.value_, it.source) }
    }

    /** Everything known for every game, by game, for a matching pass. */
    suspend fun all(): Map<Long, List<IdentityFact>> = withContext(dispatcher) {
        q.all().executeAsList().groupBy({ it.game_id }, { IdentityFact(it.kind, it.value_, it.source) })
    }

    /** The games known by [value] under [kind]. */
    suspend fun gamesWith(kind: String, value: String): List<GameId> = withContext(dispatcher) {
        q.gamesWith(kind, value).executeAsList().map(::GameId)
    }

    suspend fun record(id: GameId, kind: String, value: String, source: String) = withContext(dispatcher) {
        q.put(id.value, kind, value, source, clock())
    }

    /** Replaces every [kind] fact for [id] with [values] (one at most for a link, such as a chosen HowLongToBeat game). */
    suspend fun replace(id: GameId, kind: String, values: Collection<String>, source: String) = withContext(dispatcher) {
        db.transaction {
            q.clearKind(id.value, kind)
            values.forEach { q.put(id.value, kind, it, source, clock()) }
        }
    }

    /** A person said [remoteId] (on [kind]: romm, hltb, ra) is not [id]. */
    suspend fun veto(kind: String, remoteId: String, id: GameId) = withContext(dispatcher) {
        q.veto(kind, remoteId, id.value, clock())
    }

    /** Every pair a person said are different games on [kind], as remote id to game id. */
    suspend fun vetoes(kind: String): Set<Pair<String, Long>> = withContext(dispatcher) {
        q.vetoes(kind).executeAsList().map { it.remote_id to it.game_id }.toSet()
    }

    suspend fun unveto(kind: String, id: GameId) = withContext(dispatcher) { q.unveto(kind, id.value) }

    suspend fun hltb(workKey: String): HltbCached? = withContext(dispatcher) {
        q.hltbGet(workKey).executeAsOneOrNull()?.let { HltbCached(it.hltb_id, it.json, it.fetched_at) }
    }

    suspend fun putHltb(workKey: String, hltbId: Long, json: String) = withContext(dispatcher) {
        q.hltbPut(workKey, hltbId, json, clock())
    }

    suspend fun forgetHltb(workKey: String) = withContext(dispatcher) { q.hltbForget(workKey) }

    companion object {
        const val SERIAL = "serial"
        const val SERIAL_CONTENT = "serial.content"
        const val MD5 = "md5"
        const val SHA1 = "sha1"
        const val CRC32 = "crc32"
        const val RA = "ra"
        const val HLTB = "hltb"
        const val PACKAGE = "package"
    }
}
