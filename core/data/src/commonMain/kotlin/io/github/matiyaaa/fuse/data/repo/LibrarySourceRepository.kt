package io.github.matiyaaa.fuse.data.repo

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import io.github.matiyaaa.fuse.data.asBool
import io.github.matiyaaa.fuse.data.currentTimeMillis
import io.github.matiyaaa.fuse.data.db.FuseDatabase
import io.github.matiyaaa.fuse.data.db.Library_source
import io.github.matiyaaa.fuse.data.enumOr
import io.github.matiyaaa.fuse.data.ioDispatcher
import io.github.matiyaaa.fuse.data.lastInsertId
import io.github.matiyaaa.fuse.data.toDb
import io.github.matiyaaa.fuse.model.LibrarySource
import io.github.matiyaaa.fuse.model.LibrarySourceId
import io.github.matiyaaa.fuse.model.LibrarySourceKind
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** Folders the user added to the library. */
class LibrarySourceRepository(
    private val db: FuseDatabase,
    private val dispatcher: CoroutineDispatcher = ioDispatcher,
    private val clock: () -> Long = ::currentTimeMillis,
) {
    private val q get() = db.librarySourceQueries

    fun observeAll(): Flow<List<LibrarySource>> =
        q.selectAll().asFlow().mapToList(dispatcher).map { rows -> rows.map { it.toModel() } }.flowOn(dispatcher)

    suspend fun all(): List<LibrarySource> = withContext(dispatcher) { q.selectAll().executeAsList().map { it.toModel() } }

    suspend fun get(id: LibrarySourceId): LibrarySource? = withContext(dispatcher) {
        q.selectById(id.value).executeAsOneOrNull()?.toModel()
    }

    /** Adds a folder; adding a path that is already a source returns the existing id unchanged. */
    suspend fun add(path: String, label: String, kind: LibrarySourceKind, enabled: Boolean = true): LibrarySourceId =
        withContext(dispatcher) {
            db.transactionWithResult {
                q.selectByPath(path).executeAsOneOrNull()?.let { return@transactionWithResult LibrarySourceId(it.id) }
                q.insert(path, label, kind.name, enabled.toDb())
                LibrarySourceId(db.lastInsertId())
            }
        }

    /** Updates path, label, kind and enabled flag. */
    suspend fun update(source: LibrarySource) = withContext(dispatcher) {
        q.update(source.path, source.label, source.kind.name, source.enabled.toDb(), source.id.value)
        Unit
    }

    suspend fun setEnabled(id: LibrarySourceId, enabled: Boolean) = withContext(dispatcher) {
        q.setEnabled(enabled.toDb(), id.value)
        Unit
    }

    /** Records a completed scan of this source. */
    suspend fun markScanned(id: LibrarySourceId, now: Long = clock()) = withContext(dispatcher) {
        q.setLastScan(now, id.value)
        Unit
    }

    /**
     * Removes the source. Its games are kept and marked missing (with every user edit), so adding
     * the folder again restores them on the next scan; its remembered folder dates are forgotten.
     * Returns how many games were marked missing.
     */
    suspend fun remove(id: LibrarySourceId, now: Long = clock()): Int = withContext(dispatcher) {
        db.transactionWithResult {
            val source = q.selectById(id.value).executeAsOneOrNull() ?: return@transactionWithResult 0
            val marked = db.gameQueries.markSourceMissing(now, id.value).value.toInt()
            // The source folder and everything below it, not siblings sharing a name prefix.
            db.folderStateQueries.delete(source.path)
            db.forgetFolderState(source.path.trimEnd('/') + "/")
            q.delete(id.value)
            marked
        }
    }
}

private fun Library_source.toModel() = LibrarySource(
    id = LibrarySourceId(id),
    path = path,
    label = label,
    kind = enumOr(kind, LibrarySourceKind.ROMS_ROOT),
    enabled = enabled.asBool(),
    lastScanAt = last_scan_at,
)
