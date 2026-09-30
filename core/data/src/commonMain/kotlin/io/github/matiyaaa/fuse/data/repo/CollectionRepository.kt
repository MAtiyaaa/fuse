package io.github.matiyaaa.fuse.data.repo

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOne
import io.github.matiyaaa.fuse.data.DAY_MS
import io.github.matiyaaa.fuse.data.SQL_CHUNK
import io.github.matiyaaa.fuse.data.currentTimeMillis
import io.github.matiyaaa.fuse.data.db.FuseDatabase
import io.github.matiyaaa.fuse.data.enumOr
import io.github.matiyaaa.fuse.data.ioDispatcher
import io.github.matiyaaa.fuse.data.lastInsertId
import io.github.matiyaaa.fuse.model.CollectionId
import io.github.matiyaaa.fuse.model.CollectionKind
import io.github.matiyaaa.fuse.model.GameCollection
import io.github.matiyaaa.fuse.model.GameId
import io.github.matiyaaa.fuse.model.MediaOwner
import io.github.matiyaaa.fuse.model.PlatformId
import io.github.matiyaaa.fuse.model.SortOrder
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** Identifies a collection, stored (manual) or computed (smart). */
sealed interface CollectionKey {
    /** A collection the user made. */
    data class Manual(val id: CollectionId) : CollectionKey

    data object Favorites : CollectionKey

    /** The [CollectionRepository.RECENT_LIMIT] most recently added games. */
    data object RecentlyAdded : CollectionKey

    /** Played within the last [CollectionRepository.PLAYING_DAYS] days. */
    data object Playing : CollectionKey

    /** Games the caller reports as completed (for example RetroAchievements mastery). */
    data object Completed : CollectionKey

    data object Unplayed : CollectionKey

    data class Platform(val id: PlatformId) : CollectionKey

    /** Genre from game metadata, matched case-insensitively. */
    data class Genre(val name: String) : CollectionKey
}

/** A collection as the Collections screen lists it. [name] is a default label the UI may localise. */
data class CollectionView(
    val key: CollectionKey,
    val name: String,
    val kind: CollectionKind,
    val gameCount: Int,
)

/**
 * Manual collections (rows) plus smart ones computed from the game table: Favorites, Playing,
 * Recently Added, Completed, Unplayed, one per platform and one per genre. Counts and members only
 * include visible games (present, not removed, not hidden). Deleting a collection never deletes games.
 */
class CollectionRepository(
    private val db: FuseDatabase,
    private val dispatcher: CoroutineDispatcher = ioDispatcher,
    private val clock: () -> Long = ::currentTimeMillis,
) {
    private val q get() = db.collectionQueries

    /** Manual collections with visible game counts, in the user's order. */
    fun observeManual(): Flow<List<GameCollection>> = q.selectAllWithCount().asFlow().mapToList(dispatcher)
        .map { rows ->
            rows.map {
                GameCollection(
                    id = CollectionId(it.id),
                    name = it.name,
                    kind = enumOr(it.kind, CollectionKind.MANUAL),
                    gameCount = it.game_count.toInt(),
                    order = it.sort_order.toInt(),
                )
            }
        }
        .flowOn(dispatcher)

    /**
     * Every collection: Favorites, Playing, Recently Added, Completed (only when [completed] is
     * given), Unplayed, then manual collections, then platforms (named by [platformName]) and genres
     * that have games. Time windows are fixed when the flow is created.
     */
    fun observeCollections(
        completed: Set<GameId>? = null,
        platformName: (PlatformId) -> String = { it.value },
    ): Flow<List<CollectionView>> {
        val counts = db.gameQueries.visibleCounts(clock() - PLAYING_DAYS * DAY_MS).asFlow().mapToOne(dispatcher)
        val platforms = db.gameQueries.platformCounts().asFlow().mapToList(dispatcher)
        val genres = db.gameContentQueries.genreCounts().asFlow().mapToList(dispatcher)
        return combine(counts, observeManual(), platforms, genres) { c, manual, platformRows, genreRows ->
            buildList {
                add(CollectionView(CollectionKey.Favorites, "Favorites", CollectionKind.FAVORITES, (c.favorites ?: 0).toInt()))
                add(CollectionView(CollectionKey.Playing, "Playing", CollectionKind.PLAYING, (c.playing ?: 0).toInt()))
                add(
                    CollectionView(
                        CollectionKey.RecentlyAdded, "Recently Added", CollectionKind.RECENT,
                        minOf(c.total, RECENT_LIMIT.toLong()).toInt(),
                    ),
                )
                if (completed != null) {
                    add(CollectionView(CollectionKey.Completed, "Completed", CollectionKind.COMPLETED, countVisible(completed)))
                }
                add(CollectionView(CollectionKey.Unplayed, "Unplayed", CollectionKind.UNPLAYED, (c.unplayed ?: 0).toInt()))
                manual.forEach { add(CollectionView(CollectionKey.Manual(it.id), it.name, it.kind, it.gameCount)) }
                platformRows
                    .map { PlatformId(it.platform_id) to it.game_count.toInt() }
                    .sortedBy { platformName(it.first).lowercase() }
                    .forEach { (id, count) ->
                        add(CollectionView(CollectionKey.Platform(id), platformName(id), CollectionKind.SMART, count))
                    }
                genreRows.forEach {
                    add(CollectionView(CollectionKey.Genre(it.genre), it.genre, CollectionKind.SMART, it.game_count.toInt()))
                }
            }
        }.flowOn(dispatcher)
    }

    /** Members of any collection. Manual ones keep the user's order; smart ones sort by title. */
    fun observeGames(key: CollectionKey, completed: Set<GameId> = emptySet()): Flow<List<GameSummary>> {
        val gq = db.gameQueries
        val query = when (key) {
            is CollectionKey.Manual -> q.gamesIn(key.id.value)
            CollectionKey.Favorites -> gq.favorites()
            CollectionKey.Playing -> gq.playedSince(clock() - PLAYING_DAYS * DAY_MS, -1)
            CollectionKey.RecentlyAdded -> gq.recentlyAdded(RECENT_LIMIT.toLong())
            CollectionKey.Unplayed -> gq.unplayed(-1)
            is CollectionKey.Platform -> gq.summariesByPlatform(key.id.value, 0, SortOrder.TITLE.code())
            is CollectionKey.Genre -> gq.byGenre(key.name)
            CollectionKey.Completed -> return observeByIds(completed)
        }
        return query.asFlow().mapToList(dispatcher).map { rows -> rows.map { it.toSummary() } }.flowOn(dispatcher)
    }

    /** Collections that contain [gameId], for the "Add to collection" checkmarks. */
    fun observeCollectionsOf(gameId: GameId): Flow<Set<CollectionId>> =
        q.collectionsOfGame(gameId.value).asFlow().mapToList(dispatcher).map { ids -> ids.map(::CollectionId).toSet() }
            .flowOn(dispatcher)

    /** Creates a manual collection at the end of the list. */
    suspend fun create(name: String): CollectionId = withContext(dispatcher) {
        db.transactionWithResult {
            val order = (q.maxOrder().executeAsOne().max_order ?: -1) + 1
            q.insert(name.trim(), CollectionKind.MANUAL.name, order, clock())
            CollectionId(db.lastInsertId())
        }
    }

    suspend fun rename(id: CollectionId, name: String) = withContext(dispatcher) {
        q.rename(name.trim(), id.value)
        Unit
    }

    /** Deletes the collection and its memberships (and its artwork). Games are untouched. */
    suspend fun delete(id: CollectionId) = withContext(dispatcher) {
        db.transaction {
            q.delete(id.value)
            db.mediaQueries.deleteOwner(MediaOwner.OfCollection(id).type(), id.value.toString())
        }
    }

    /** Appends games that are not already members, keeping the given order. */
    suspend fun addGames(id: CollectionId, games: List<GameId>) = withContext(dispatcher) {
        db.transaction {
            var order = (q.maxGameOrder(id.value).executeAsOne().max_order ?: -1) + 1
            games.distinct().forEach { q.addGame(id.value, it.value, order++) }
        }
    }

    suspend fun removeGames(id: CollectionId, games: Collection<GameId>) = withContext(dispatcher) {
        db.transaction { games.forEach { q.removeGame(id.value, it.value) } }
    }

    /** Sets member order to the order of [ordered]; members not listed keep their relative order after them. */
    suspend fun reorderGames(id: CollectionId, ordered: List<GameId>) = withContext(dispatcher) {
        db.transaction {
            val listed = ordered.distinct()
            val listedSet = listed.toSet()
            val rest = q.gamesIn(id.value).executeAsList().map { GameId(it.id) }.filterNot { it in listedSet }
            (listed + rest).forEachIndexed { i, game -> q.setGameOrder(i.toLong(), id.value, game.value) }
        }
    }

    /** Sets manual collection order to the order of [ordered]. */
    suspend fun reorder(ordered: List<CollectionId>) = withContext(dispatcher) {
        db.transaction { ordered.forEachIndexed { i, id -> q.setOrder(i.toLong(), id.value) } }
    }

    private fun countVisible(ids: Set<GameId>): Int =
        ids.map { it.value }.chunked(SQL_CHUNK).sumOf { db.gameQueries.countVisibleIn(it).executeAsOne() }.toInt()

    private fun observeByIds(ids: Set<GameId>): Flow<List<GameSummary>> {
        if (ids.isEmpty()) return flowOf(emptyList())
        val flows = ids.map { it.value }.chunked(SQL_CHUNK).map { db.gameQueries.summariesByIds(it).asFlow().mapToList(dispatcher) }
        return combine(flows) { parts -> parts.flatMap { it }.map { it.toSummary() }.sortedBy { it.sortKey } }.flowOn(dispatcher)
    }

    companion object {
        /** Window of the Playing collection. */
        const val PLAYING_DAYS = 14

        /** Size of the Recently Added collection. */
        const val RECENT_LIMIT = 50
    }
}
