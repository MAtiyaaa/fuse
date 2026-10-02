package io.github.matiyaaa.fuse.data.repo

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import io.github.matiyaaa.fuse.data.DataJson
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
import io.github.matiyaaa.fuse.model.VolumeRef
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

    /** Remembers the drive [id] lives on (null forgets it). */
    suspend fun setVolume(id: LibrarySourceId, volume: VolumeRef?) = withContext(dispatcher) {
        q.setVolume(volume?.let { DataJson.encodeToString(VolumeRef.serializer(), it) }, id.value)
        Unit
    }

    /**
     * Moves a library folder whose drive now appears under another path from [from] to [to]: the
     * folder, its games and their discs, content and art found beside them, in one transaction.
     * Nothing changes (and false is returned) when games already exist under [to], for example
     * because the new path was also added as a folder of its own, so no two rows ever collide.
     */
    suspend fun relink(id: LibrarySourceId, from: String, to: String, volume: VolumeRef?): Boolean = withContext(dispatcher) {
        val old = from.trimEnd('/')
        val new = to.trimEnd('/')
        if (old.isEmpty() || new.isEmpty() || old == new) return@withContext false
        db.transactionWithResult {
            if (q.selectByPath(to).executeAsOneOrNull() != null || q.selectByPath(new).executeAsOneOrNull() != null) {
                return@transactionWithResult false
            }
            if (db.gameQueries.countPathsAtPrefix(new).executeAsOne() > 0) return@transactionWithResult false
            db.gameContentQueries.relinkContent(old = old, new = new, sourceId = id.value)
            db.gameContentQueries.relinkDiscs(old = old, new = new, sourceId = id.value)
            db.gameQueries.relinkGames(old = old, new = new, sourceId = id.value)
            db.mediaQueries.relinkLocalMedia(old = old, new = new)
            db.folderStateQueries.relink(old = old, new = new)
            q.setPath(new, id.value)
            q.setVolume(volume?.let { DataJson.encodeToString(VolumeRef.serializer(), it) }, id.value)
            true
        }
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
    volume = volume_json?.let { runCatching { DataJson.decodeFromString(VolumeRef.serializer(), it) }.getOrNull() },
)
