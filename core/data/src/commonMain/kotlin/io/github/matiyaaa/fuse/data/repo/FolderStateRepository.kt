package io.github.matiyaaa.fuse.data.repo

import io.github.matiyaaa.fuse.data.db.FuseDatabase
import io.github.matiyaaa.fuse.data.ioDispatcher
import io.github.matiyaaa.fuse.data.prefixUpperBound
import io.github.matiyaaa.fuse.model.FolderStateStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/** [FolderStateStore] backed by the folder_state table. */
class FolderStateRepository(
    private val db: FuseDatabase,
    private val dispatcher: CoroutineDispatcher = ioDispatcher,
) : FolderStateStore {
    override suspend fun lastModified(path: String): Long? = withContext(dispatcher) {
        db.folderStateQueries.get(path).executeAsOneOrNull()
    }

    override suspend fun remember(path: String, modifiedAt: Long) = withContext(dispatcher) {
        db.folderStateQueries.put(path, modifiedAt)
        Unit
    }

    /** Forgets every folder whose path starts with [pathPrefix] (all of them for ""). */
    override suspend fun forget(pathPrefix: String) = withContext(dispatcher) { db.forgetFolderState(pathPrefix) }
}

internal fun FuseDatabase.forgetFolderState(pathPrefix: String) {
    if (pathPrefix.isEmpty()) {
        folderStateQueries.deleteAll()
    } else {
        folderStateQueries.deleteRange(pathPrefix, prefixUpperBound(pathPrefix))
    }
}
