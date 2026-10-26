package io.github.matiyaaa.fuse.data.health

import io.github.matiyaaa.fuse.data.SQL_CHUNK
import io.github.matiyaaa.fuse.data.db.FuseDatabase
import io.github.matiyaaa.fuse.data.ioDispatcher
import io.github.matiyaaa.fuse.model.GameId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * Fuse 0.4.5, Library Health: what it reads and the repairs it offers. Every repair is scoped to the
 * rows it names, never touches a game's own files, and never changes a picture the person chose.
 */
class LibraryHealthRepository(
    private val db: FuseDatabase,
    private val dispatcher: CoroutineDispatcher = ioDispatcher,
) {
    private val q get() = db.libraryHealthQueries

    /** A picture kept as a file on this device: whose it is, where, and whether the person chose it. */
    data class LocalPicture(
        val id: Long,
        val ownerType: String,
        val ownerId: String,
        val path: String,
        val chosen: Boolean,
        val hasWebAddress: Boolean,
    )

    /** Every picture kept as a file here (absolute paths only; an app's own icon is no file). */
    suspend fun localPictures(): List<LocalPicture> = withContext(dispatcher) {
        q.localPictures().executeAsList().mapNotNull { r ->
            val path = r.local_path ?: return@mapNotNull null
            if (!path.startsWith("/") && !(path.length > 2 && path[1] == ':')) return@mapNotNull null
            LocalPicture(r.id, r.owner_type, r.owner_id, path, r.source == "USER", r.has_web)
        }
    }

    /** What [repairGone] would change for [gone], before it does: (back to the web, forgotten, left as chosen). */
    fun plan(gone: List<LocalPicture>): Triple<Int, Int, Int> =
        Triple(gone.count { !it.chosen && it.hasWebAddress }, gone.count { !it.chosen && !it.hasWebAddress }, gone.count { it.chosen })

    /**
     * Pictures whose file is gone: a downloaded one with a web address goes back to it, one without
     * is forgotten so a fill can find another, and one the person chose stays as it is. Returns how
     * many rows changed.
     */
    suspend fun repairGone(gone: List<LocalPicture>): Int = withContext(dispatcher) {
        val ids = gone.filterNot { it.chosen }.map { it.id }
        if (ids.isEmpty()) return@withContext 0
        db.transaction {
            for (chunk in ids.chunked(SQL_CHUNK)) {
                q.pointToWeb(chunk)
                q.forgetPictures(chunk)
            }
        }
        ids.size
    }

    /** Pictures kept for games the library no longer has at all. */
    suspend fun orphanPictures(): Int = withContext(dispatcher) { q.orphanPictures().executeAsOne().toInt() }

    /** Forgets [orphanPictures] (their files stay where they are). Returns how many. */
    suspend fun removeOrphanPictures(): Int = withContext(dispatcher) {
        db.transactionWithResult {
            val n = q.orphanPictures().executeAsOne().toInt()
            q.removeOrphanPictures()
            n
        }
    }

    /**
     * Games that are the same file found twice: on the same system, the same name and the same size,
     * in two places. Each group is listed, never merged: they may be two copies kept on purpose.
     */
    suspend fun sameFileTwice(): List<List<GameId>> = withContext(dispatcher) {
        q.presentFiles().executeAsList()
            .groupBy { Triple(it.platform_id, it.path.substringAfterLast('/').substringAfterLast('\\').lowercase(), it.size_bytes) }
            .values.filter { it.size > 1 }
            .map { group -> group.map { GameId(it.id) }.sortedBy { it.value } }
            .sortedBy { it.first().value }
    }
}
