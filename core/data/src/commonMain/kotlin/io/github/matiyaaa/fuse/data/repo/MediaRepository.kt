package io.github.matiyaaa.fuse.data.repo

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import io.github.matiyaaa.fuse.data.SQL_CHUNK
import io.github.matiyaaa.fuse.data.currentTimeMillis
import io.github.matiyaaa.fuse.data.db.FuseDatabase
import io.github.matiyaaa.fuse.data.db.Media
import io.github.matiyaaa.fuse.data.enumOr
import io.github.matiyaaa.fuse.data.enumOrNull
import io.github.matiyaaa.fuse.data.ioDispatcher
import io.github.matiyaaa.fuse.model.GameId
import io.github.matiyaaa.fuse.model.MediaFillMode
import io.github.matiyaaa.fuse.model.MediaItem
import io.github.matiyaaa.fuse.model.MediaKind
import io.github.matiyaaa.fuse.model.MediaOwner
import io.github.matiyaaa.fuse.model.MediaSet
import io.github.matiyaaa.fuse.model.MediaSource
import io.github.matiyaaa.fuse.model.PlatformId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Artwork records. Rules:
 * - USER media (set with [setCustom]) is never replaced or removed by scrapers or rescans; only
 *   [setCustom], [resetCustom] and [reset] (explicit user actions) change it.
 * - Within a kind, USER media sorts first so [MediaSet.first] returns it. [MediaItem.order] in
 *   returned sets is the position within its kind after that ordering.
 */
class MediaRepository(
    private val db: FuseDatabase,
    private val dispatcher: CoroutineDispatcher = ioDispatcher,
    private val clock: () -> Long = ::currentTimeMillis,
) {
    private val q get() = db.mediaQueries

    fun observe(owner: MediaOwner): Flow<MediaSet> =
        q.selectByOwner(owner.type(), owner.key()).asFlow().mapToList(dispatcher).map(::toMediaSet).flowOn(dispatcher)

    suspend fun get(owner: MediaOwner): MediaSet = withContext(dispatcher) {
        toMediaSet(q.selectByOwner(owner.type(), owner.key()).executeAsList())
    }

    /** Media for many owners at once (chunked IN queries, not one query per owner). */
    suspend fun mediaFor(owners: Collection<MediaOwner>): Map<MediaOwner, MediaSet> = withContext(dispatcher) {
        batches(owners).flatMap { (type, keys) -> q.selectByOwners(type, keys).executeAsList() }.groupedByOwner(owners)
    }

    /** Live version of [mediaFor], for a visible page of a list. */
    fun observeFor(owners: Collection<MediaOwner>): Flow<Map<MediaOwner, MediaSet>> {
        val batches = batches(owners)
        if (batches.isEmpty()) return flowOf(emptyMap())
        val flows = batches.map { (type, keys) -> q.selectByOwners(type, keys).asFlow().mapToList(dispatcher) }
        return combine(flows) { parts -> parts.flatMap { it }.groupedByOwner(owners) }.flowOn(dispatcher)
    }

    /**
     * Sets the user's own artwork for [kind], replacing any previous USER item of that kind. Scraped
     * items stay stored underneath and show again after [resetCustom].
     */
    suspend fun setCustom(
        owner: MediaOwner,
        kind: MediaKind,
        localPath: String?,
        remoteUrl: String? = null,
        width: Int? = null,
        height: Int? = null,
        focusX: Float = 0.5f,
        focusY: Float = 0.5f,
        zoom: Float = 1f,
    ) {
        require(localPath != null || remoteUrl != null) { "Custom media needs a local path or a URL" }
        withContext(dispatcher) {
            db.transaction {
                q.deleteKindUser(owner.type(), owner.key(), kind.name)
                db.insertMedia(
                    owner,
                    MediaItem(kind, MediaSource.USER, localPath, remoteUrl, width, height, focusX, focusY, zoom, order = -1),
                    clock(),
                )
            }
        }
    }

    /** Removes the user's own artwork of [kind]; scraped or local art shows again. */
    suspend fun resetCustom(owner: MediaOwner, kind: MediaKind): Boolean = withContext(dispatcher) {
        q.deleteKindUser(owner.type(), owner.key(), kind.name).value > 0
    }

    /**
     * Stores scraper results. [items] must not be USER media.
     * - [MediaFillMode.FILL_MISSING]: only kinds with nothing stored (limited to [selectedKinds] when
     *   that is non-empty).
     * - [MediaFillMode.REPLACE_SELECTED]: kinds in [selectedKinds]; their non-USER items are replaced.
     * - [MediaFillMode.REPLACE_ALL]: every kind in [items]; non-USER items are replaced.
     * Kinds the provider returned nothing for are left alone. Returns the number of items inserted.
     */
    suspend fun putScraped(
        owner: MediaOwner,
        items: List<MediaItem>,
        mode: MediaFillMode,
        selectedKinds: Set<MediaKind> = emptySet(),
    ): Int {
        require(items.none { it.source == MediaSource.USER }) { "USER media goes through setCustom" }
        return withContext(dispatcher) {
            db.transactionWithResult {
                val type = owner.type()
                val key = owner.key()
                val now = clock()
                var inserted = 0
                for ((kind, group) in items.groupBy { it.kind }) {
                    val replace = when (mode) {
                        MediaFillMode.FILL_MISSING -> {
                            if (selectedKinds.isNotEmpty() && kind !in selectedKinds) continue
                            if (q.countKind(type, key, kind.name).executeAsOne() > 0) continue
                            false
                        }
                        MediaFillMode.REPLACE_SELECTED -> if (kind in selectedKinds) true else continue
                        MediaFillMode.REPLACE_ALL -> true
                    }
                    if (replace) q.deleteKindNonUser(type, key, kind.name)
                    group.forEach { db.insertMedia(owner, it, now) }
                    inserted += group.size
                }
                inserted
            }
        }
    }

    /**
     * Moves the focal point / zoom of the artwork currently shown for [kind] (the USER item when
     * there is one). Returns false when there is no artwork of that kind.
     */
    suspend fun adjust(owner: MediaOwner, kind: MediaKind, focusX: Float, focusY: Float, zoom: Float): Boolean =
        withContext(dispatcher) {
            db.transactionWithResult {
                val shown = q.selectKind(owner.type(), owner.key(), kind.name).executeAsList().firstOrNull()
                    ?: return@transactionWithResult false
                q.adjust(
                    focusX = focusX.coerceIn(0f, 1f).toDouble(),
                    focusY = focusY.coerceIn(0f, 1f).toDouble(),
                    zoom = zoom.coerceIn(1f, 10f).toDouble(),
                    id = shown.id,
                )
                true
            }
        }

    /** Removes every item of [kind], USER ones included (explicit user action). Returns the count. */
    suspend fun reset(owner: MediaOwner, kind: MediaKind): Int = withContext(dispatcher) {
        q.deleteKind(owner.type(), owner.key(), kind.name).value.toInt()
    }

    /**
     * Visible games (present, not removed) lacking any of [kinds], with the kinds each lacks. Scoped
     * to one platform, or the whole library when [platformId] is null. Feeds bulk Fill Missing.
     */
    suspend fun gamesMissing(kinds: Set<MediaKind>, platformId: PlatformId? = null): Map<GameId, Set<MediaKind>> =
        withContext(dispatcher) {
            val result = LinkedHashMap<GameId, MutableSet<MediaKind>>()
            for (kind in kinds) {
                q.gamesLackingKind(platformId?.value, kind.name).executeAsList().forEach {
                    result.getOrPut(GameId(it)) { mutableSetOf() }.add(kind)
                }
            }
            result
        }

    /** How many visible games lack each of [kinds] (library-wide or for one platform). */
    suspend fun missingCounts(kinds: Set<MediaKind>, platformId: PlatformId? = null): Map<MediaKind, Int> =
        withContext(dispatcher) {
            kinds.associateWith { q.gamesLackingKind(platformId?.value, it.name).executeAsList().size }
        }

    private fun batches(owners: Collection<MediaOwner>): List<Pair<String, List<String>>> =
        owners.groupBy { it.type() }.flatMap { (type, list) ->
            list.map { it.key() }.distinct().chunked(SQL_CHUNK).map { type to it }
        }

    private fun List<Media>.groupedByOwner(owners: Collection<MediaOwner>): Map<MediaOwner, MediaSet> {
        val rows = groupBy { it.owner_type to it.owner_id }
        return owners.associateWith { toMediaSet(rows[it.type() to it.key()].orEmpty()) }
    }
}

/** Storage key of an owner: its type column value. */
internal fun MediaOwner.type(): String = when (this) {
    is MediaOwner.OfGame -> "game"
    is MediaOwner.OfPlatform -> "platform"
    is MediaOwner.OfCollection -> "collection"
    is MediaOwner.OfApp -> "app"
}

/** Storage key of an owner: its id column value. */
internal fun MediaOwner.key(): String = when (this) {
    is MediaOwner.OfGame -> id.value.toString()
    is MediaOwner.OfPlatform -> id.value
    is MediaOwner.OfCollection -> id.value.toString()
    is MediaOwner.OfApp -> packageName
}

internal fun FuseDatabase.insertMedia(owner: MediaOwner, item: MediaItem, now: Long) {
    mediaQueries.insert(
        owner.type(), owner.key(), item.kind.name, item.source.name, item.localPath, item.remoteUrl,
        item.width?.toLong(), item.height?.toLong(), item.focusX.toDouble(), item.focusY.toDouble(),
        item.zoom.toDouble(), item.order.toLong(), now,
    )
}

/**
 * Media found next to a game on disk: inserted as LOCAL_FOLDER only when nothing of that kind is
 * stored; an existing LOCAL_FOLDER item just follows the new path. USER and scraped art is untouched.
 */
internal fun FuseDatabase.mergeLocalMedia(owner: MediaOwner, local: Map<MediaKind, String>, now: Long) {
    for ((kind, path) in local) {
        if (mediaQueries.countKind(owner.type(), owner.key(), kind.name).executeAsOne() == 0L) {
            insertMedia(owner, MediaItem(kind, MediaSource.LOCAL_FOLDER, localPath = path), now)
        } else {
            mediaQueries.updateLocalFolderPath(path, owner.type(), owner.key(), kind.name)
        }
    }
}

/** Rows must be sorted by kind, USER first, then sort order (as the queries return them). */
private fun toMediaSet(rows: List<Media>): MediaSet {
    if (rows.isEmpty()) return MediaSet.Empty
    val position = HashMap<MediaKind, Int>()
    val items = rows.mapNotNull { row ->
        val kind = enumOrNull<MediaKind>(row.kind) ?: return@mapNotNull null
        val order = position[kind] ?: 0
        position[kind] = order + 1
        MediaItem(
            kind = kind,
            source = enumOr(row.source, MediaSource.GENERATED),
            localPath = row.local_path,
            remoteUrl = row.remote_url,
            width = row.width?.toInt(),
            height = row.height?.toInt(),
            focusX = row.focus_x.toFloat(),
            focusY = row.focus_y.toFloat(),
            zoom = row.zoom.toFloat(),
            order = order,
        )
    }
    return MediaSet(items)
}
