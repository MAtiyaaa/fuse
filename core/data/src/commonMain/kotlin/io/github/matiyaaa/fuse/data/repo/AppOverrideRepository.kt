package io.github.matiyaaa.fuse.data.repo

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import io.github.matiyaaa.fuse.data.asBool
import io.github.matiyaaa.fuse.data.db.App_override
import io.github.matiyaaa.fuse.data.db.FuseDatabase
import io.github.matiyaaa.fuse.data.ioDispatcher
import io.github.matiyaaa.fuse.data.toDb
import io.github.matiyaaa.fuse.model.AppEntry
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** The user's choices about one installed app. */
data class AppOverride(
    val appId: String,
    val customTitle: String? = null,
    val pinned: Boolean = false,
    val hidden: Boolean = false,
    /** Position among pinned apps, or null for default ordering. */
    val sortOrder: Int? = null,
    val lastUsedAt: Long? = null,
)

/**
 * Per-app overrides for the Apps section. The installed-app list itself comes from the platform
 * (launcher APIs or .desktop files); [applyTo] merges these overrides onto it.
 */
class AppOverrideRepository(
    private val db: FuseDatabase,
    private val dispatcher: CoroutineDispatcher = ioDispatcher,
) {
    private val q get() = db.appOverrideQueries

    fun observeAll(): Flow<Map<String, AppOverride>> = q.selectAll().asFlow().mapToList(dispatcher)
        .map { rows -> rows.associate { it.app_id to it.toModel() } }
        .flowOn(dispatcher)

    suspend fun get(appId: String): AppOverride? = withContext(dispatcher) { q.selectById(appId).executeAsOneOrNull()?.toModel() }

    /** Sets or clears (null/blank) the display title. */
    suspend fun setCustomTitle(appId: String, title: String?) =
        upsert(appId) { q.setCustomTitle(title?.trim()?.takeIf(String::isNotEmpty), appId) }

    /** Pins or unpins; a newly pinned app goes to the end of the pinned order. */
    suspend fun setPinned(appId: String, pinned: Boolean) = upsert(appId) {
        val wasPinned = q.selectById(appId).executeAsOne().pinned.asBool()
        if (pinned == wasPinned) return@upsert
        q.setPinned(pinned.toDb(), appId)
        val order = if (pinned) {
            q.selectAll().executeAsList()
                .filter { it.pinned.asBool() && it.app_id != appId }
                .mapNotNull { it.sort_order }
                .maxOrNull()?.plus(1) ?: 0L
        } else {
            null
        }
        q.setSortOrder(order, appId)
    }

    suspend fun setHidden(appId: String, hidden: Boolean) = upsert(appId) { q.setHidden(hidden.toDb(), appId) }

    suspend fun markUsed(appId: String, now: Long) = upsert(appId) { q.setLastUsed(now, appId) }

    /** Orders pinned apps as in [ordered]. */
    suspend fun reorderPinned(ordered: List<String>) = withContext(dispatcher) {
        db.transaction {
            ordered.forEachIndexed { i, id ->
                q.ensure(id)
                q.setSortOrder(i.toLong(), id)
            }
        }
    }

    /** Forgets every choice about [appId]. */
    suspend fun clear(appId: String) = withContext(dispatcher) {
        q.delete(appId)
        Unit
    }

    private suspend fun upsert(appId: String, block: () -> Unit) = withContext(dispatcher) {
        db.transaction {
            q.ensure(appId)
            block()
        }
    }

    companion object {
        /** Applies [overrides] to installed [entries] (title, pinned, hidden, last used). */
        fun applyTo(entries: List<AppEntry>, overrides: Map<String, AppOverride>): List<AppEntry> = entries.map { entry ->
            val o = overrides[entry.id] ?: return@map entry
            entry.copy(
                customTitle = o.customTitle,
                pinned = o.pinned,
                hidden = o.hidden,
                lastUsedAt = o.lastUsedAt ?: entry.lastUsedAt,
            )
        }
    }
}

private fun App_override.toModel() = AppOverride(
    appId = app_id,
    customTitle = custom_title,
    pinned = pinned.asBool(),
    hidden = hidden.asBool(),
    sortOrder = sort_order?.toInt(),
    lastUsedAt = last_used_at,
)
