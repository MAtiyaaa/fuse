package io.github.matiyaaa.fuse.data.repo

import io.github.matiyaaa.fuse.data.DataJson
import io.github.matiyaaa.fuse.data.db.FuseDatabase
import io.github.matiyaaa.fuse.data.decodeOrNull
import io.github.matiyaaa.fuse.data.ioDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer

/** A cached value with its timestamps. */
data class CacheEntry(val valueJson: String, val fetchedAt: Long, val expiresAt: Long?) {
    fun isExpired(now: Long): Boolean = expiresAt != null && expiresAt <= now
}

/**
 * Namespaced JSON cache with expiry, for RetroAchievements and scraper responses. Expired entries
 * stay readable through [entry] so the UI can show stale data offline until [purgeExpired] runs.
 */
class CacheRepository(
    private val db: FuseDatabase,
    private val dispatcher: CoroutineDispatcher = ioDispatcher,
) {
    private val q get() = db.kvCacheQueries

    /** The fresh value, or null when absent or expired at [now]. */
    suspend fun getOrNull(namespace: String, key: String, now: Long): String? =
        entry(namespace, key)?.takeUnless { it.isExpired(now) }?.valueJson

    /** The stored entry, expired or not. */
    suspend fun entry(namespace: String, key: String): CacheEntry? = withContext(dispatcher) {
        q.get(namespace, key).executeAsOneOrNull()?.let { CacheEntry(it.value_json, it.fetched_at, it.expires_at) }
    }

    /** Stores [valueJson]; [ttlMs] null means it never expires. */
    suspend fun put(namespace: String, key: String, valueJson: String, now: Long, ttlMs: Long?) = withContext(dispatcher) {
        q.put(namespace, key, valueJson, now, ttlMs?.let { now + it })
        Unit
    }

    /** Typed [getOrNull]; an entry that no longer decodes counts as absent. */
    suspend fun <T> get(namespace: String, key: String, serializer: KSerializer<T>, now: Long): T? =
        decodeOrNull(serializer, getOrNull(namespace, key, now))

    /** Typed [put]. */
    suspend fun <T> put(namespace: String, key: String, value: T, serializer: KSerializer<T>, now: Long, ttlMs: Long?) =
        put(namespace, key, DataJson.encodeToString(serializer, value), now, ttlMs)

    suspend fun remove(namespace: String, key: String) = withContext(dispatcher) {
        q.delete(namespace, key)
        Unit
    }

    suspend fun clear(namespace: String) = withContext(dispatcher) {
        q.deleteNamespace(namespace)
        Unit
    }

    /** Deletes entries expired at [now]; returns how many. */
    suspend fun purgeExpired(now: Long): Int = withContext(dispatcher) { q.purgeExpired(now).value.toInt() }
}
