package io.github.matiyaaa.fuse.data.repo

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToOne
import io.github.matiyaaa.fuse.data.DataJson
import io.github.matiyaaa.fuse.data.asBool
import io.github.matiyaaa.fuse.data.currentTimeMillis
import io.github.matiyaaa.fuse.data.db.FuseDatabase
import io.github.matiyaaa.fuse.data.db.SelectAllTitles
import io.github.matiyaaa.fuse.data.decodeOrNull
import io.github.matiyaaa.fuse.data.ioDispatcher
import io.github.matiyaaa.fuse.model.GameId
import io.github.matiyaaa.fuse.model.GameTitles
import io.github.matiyaaa.fuse.model.PlatformId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/** A display title that Clean Display Names would change. */
data class TitleChange(
    val gameId: GameId,
    val platformId: PlatformId,
    val original: String,
    val before: String,
    val after: String,
)

/**
 * Bulk Clean Display Names with undo. Applying stores each game's previous cleaned title and
 * use-cleaned flag; [undoLast] restores them. Only display titles change: files are never renamed,
 * and custom or metadata titles (which win over cleaned ones) are never modified.
 */
class TitleCleanupRepository(
    private val db: FuseDatabase,
    private val dispatcher: CoroutineDispatcher = ioDispatcher,
    private val clock: () -> Long = ::currentTimeMillis,
) {
    /** Games whose visible title would change, without writing anything. */
    suspend fun preview(cleaner: (String) -> String): List<TitleChange> = withContext(dispatcher) {
        db.gameQueries.selectAllTitles().executeAsList().mapNotNull { row ->
            val before = row.titles()
            val after = before.copy(cleaned = clean(cleaner, row.title_original), useCleaned = true)
            if (before.display == after.display) null
            else TitleChange(GameId(row.id), PlatformId(row.platform_id), row.title_original, before.display, after.display)
        }.sortedBy { it.after.lowercase() }
    }

    /**
     * Sets every game's cleaned title from [cleaner] and turns cleaned titles on. Returns how many
     * games changed; nothing is recorded when none did.
     */
    suspend fun apply(cleaner: (String) -> String): Int = withContext(dispatcher) {
        db.transactionWithResult {
            val now = clock()
            val entries = ArrayList<HistoryEntry>()
            for (row in db.gameQueries.selectAllTitles().executeAsList()) {
                val before = row.titles()
                val cleaned = clean(cleaner, row.title_original)
                if (before.cleaned == cleaned && before.useCleaned) continue
                entries += HistoryEntry(row.id, before.cleaned, before.useCleaned)
                db.writeTitles(row.id, before.copy(cleaned = cleaned, useCleaned = true), now)
            }
            if (entries.isNotEmpty()) {
                db.titleCleanupQueries.insert(now, DataJson.encodeToString(HistorySerializer, entries))
            }
            entries.size
        }
    }

    /** Restores the titles changed by the most recent [apply]. Returns the games restored, or null if nothing to undo. */
    suspend fun undoLast(): Int? = withContext(dispatcher) {
        db.transactionWithResult {
            val last = db.titleCleanupQueries.latest().executeAsOneOrNull() ?: return@transactionWithResult null
            val entries = decodeOrNull(HistorySerializer, last.entries_json).orEmpty()
            val now = clock()
            var restored = 0
            for (entry in entries) {
                val row = db.gameQueries.selectTitles(entry.gameId).executeAsOneOrNull() ?: continue
                val current = GameTitles(row.title_original, row.title_cleaned, row.title_custom, row.title_metadata, row.use_cleaned.asBool())
                db.writeTitles(entry.gameId, current.copy(cleaned = entry.cleaned, useCleaned = entry.useCleaned), now)
                restored++
            }
            db.titleCleanupQueries.delete(last.id)
            restored
        }
    }

    /** True while there is an apply to undo. */
    fun observeCanUndo(): Flow<Boolean> =
        db.titleCleanupQueries.count().asFlow().mapToOne(dispatcher).map { it > 0 }.flowOn(dispatcher)

    private fun clean(cleaner: (String) -> String, original: String): String? =
        runCatching { cleaner(original).trim() }.getOrNull()?.takeIf(String::isNotEmpty)

    private fun SelectAllTitles.titles() =
        GameTitles(title_original, title_cleaned, title_custom, title_metadata, use_cleaned.asBool())

    @Serializable
    private data class HistoryEntry(val gameId: Long, val cleaned: String?, val useCleaned: Boolean)

    private companion object {
        val HistorySerializer = ListSerializer(HistoryEntry.serializer())
    }
}
