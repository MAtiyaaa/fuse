package io.github.matiyaaa.fuse.data.repo

import app.cash.sqldelight.Query
import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import io.github.matiyaaa.fuse.data.DAY_MS
import io.github.matiyaaa.fuse.data.DataJson
import io.github.matiyaaa.fuse.data.SQL_CHUNK
import io.github.matiyaaa.fuse.data.TitleText
import io.github.matiyaaa.fuse.data.asBool
import io.github.matiyaaa.fuse.data.currentTimeMillis
import io.github.matiyaaa.fuse.data.db.FuseDatabase
import io.github.matiyaaa.fuse.data.db.Game_summary
import io.github.matiyaaa.fuse.data.decodeOrNull
import io.github.matiyaaa.fuse.data.ioDispatcher
import io.github.matiyaaa.fuse.data.toDb
import io.github.matiyaaa.fuse.model.EmulatorId
import io.github.matiyaaa.fuse.model.ExternalLinks
import io.github.matiyaaa.fuse.model.FolderPolicy
import io.github.matiyaaa.fuse.model.Game
import io.github.matiyaaa.fuse.model.GameId
import io.github.matiyaaa.fuse.model.LibrarySourceId
import io.github.matiyaaa.fuse.model.GameMetadata
import io.github.matiyaaa.fuse.model.GameTitles
import io.github.matiyaaa.fuse.model.MediaOwner
import io.github.matiyaaa.fuse.model.MetadataSource
import io.github.matiyaaa.fuse.model.PlatformId
import io.github.matiyaaa.fuse.model.SortOrder
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Games as the user sees them. List flows return [GameSummary] and exclude missing, removed and
 * (unless asked) hidden games. Mutations only touch user-owned columns; file-derived columns belong
 * to [LibraryIndexer].
 */
class GameRepository(
    private val db: FuseDatabase,
    private val dispatcher: CoroutineDispatcher = ioDispatcher,
    private val clock: () -> Long = ::currentTimeMillis,
) {
    private val q get() = db.gameQueries

    // Lists ---------------------------------------------------------------------------------------

    /** One platform's games in [sort] order. */
    fun observeByPlatform(
        platformId: PlatformId,
        sort: SortOrder = SortOrder.TITLE,
        includeHidden: Boolean = false,
    ): Flow<List<GameSummary>> = q.summariesByPlatform(platformId.value, includeHidden.toDb(), sort.code()).summaries()

    /** Every game in [sort] order. */
    fun observeAll(sort: SortOrder = SortOrder.TITLE, includeHidden: Boolean = false): Flow<List<GameSummary>> =
        q.summariesAll(includeHidden.toDb(), sort.code()).summaries()

    fun observeRecentlyPlayed(limit: Int = 20): Flow<List<GameSummary>> = q.recentlyPlayed(limit.toLong()).summaries()

    fun observeRecentlyAdded(limit: Int = 20): Flow<List<GameSummary>> = q.recentlyAdded(limit.toLong()).summaries()

    fun observeFavorites(): Flow<List<GameSummary>> = q.favorites().summaries()

    fun observePinned(): Flow<List<GameSummary>> = q.pinned().summaries()

    /** Games played within the last [days] days (window fixed at subscription), most recent first. */
    fun observeContinuePlaying(days: Int = 14, limit: Int = 20): Flow<List<GameSummary>> =
        q.playedSince(clock() - days * DAY_MS, limit.toLong()).summaries()

    /** Games by total play time (tracked plus imported), highest first. */
    fun observeMostPlayed(limit: Int = 20): Flow<List<GameSummary>> = q.mostPlayed(limit.toLong()).summaries()

    /** Never played and no recorded time. [limit] below zero means all. */
    fun observeUnplayed(limit: Int = -1): Flow<List<GameSummary>> = q.unplayed(limit.toLong()).summaries()

    fun observeByGenre(genre: String): Flow<List<GameSummary>> = q.byGenre(genre).summaries()

    /** Games whose files are gone, for the Missing Games screen. */
    fun observeMissing(): Flow<List<GameSummary>> = q.missingGames().summaries()

    fun observeHidden(): Flow<List<GameSummary>> = q.hiddenGames().summaries()

    /** Games the user removed from Fuse, so they can be restored. */
    fun observeRemoved(): Flow<List<GameSummary>> = q.removedGames().summaries()

    /** Visible game count per platform. */
    fun platformCounts(): Flow<Map<PlatformId, Int>> = q.platformCounts().asFlow().mapToList(dispatcher)
        .map { rows -> rows.associate { PlatformId(it.platform_id) to it.game_count.toInt() } }
        .flowOn(dispatcher)

    /**
     * Title search over visible games. The query is normalised like the stored search column
     * (case, accents, punctuation); titles starting with it rank first, then titles with a word
     * starting with it, then any substring match.
     */
    suspend fun search(query: String, limit: Int = 50): List<GameSummary> {
        val needle = TitleText.normalize(query)
        if (needle.isEmpty()) return emptyList()
        return withContext(dispatcher) {
            q.search(contains = "%$needle%", prefix = "$needle%", wordPrefix = "% $needle%", limit = limit.toLong())
                .executeAsList().map { it.toSummary() }
        }
    }

    // Single games --------------------------------------------------------------------------------

    suspend fun get(id: GameId): Game? = withContext(dispatcher) {
        q.selectById(id.value).executeAsOneOrNull()?.let { db.loadGames(listOf(it)).single() }
    }

    /** Full games for [ids] (content and discs batched), in no particular order. */
    suspend fun getAll(ids: Collection<GameId>): List<Game> = withContext(dispatcher) {
        ids.map { it.value }.distinct().chunked(SQL_CHUNK).flatMap { db.loadGames(q.selectByIds(it).executeAsList()) }
    }

    suspend fun summary(id: GameId): GameSummary? = withContext(dispatcher) {
        q.summaryById(id.value).executeAsOneOrNull()?.toSummary()
    }

    /** One game with its status flags; re-emits whenever the game table changes. */
    fun observe(id: GameId): Flow<GameRecord?> = q.selectById(id.value).asFlow().mapToOneOrNull(dispatcher)
        .map { row -> row?.toRecord(db.loadGames(listOf(row)).single()) }
        .flowOn(dispatcher)

    suspend fun idByPath(path: String): GameId? = withContext(dispatcher) {
        q.selectIdByPath(path).executeAsOneOrNull()?.let(::GameId)
    }

    /** Every game's path (removed games left out), for matching paths other apps report. */
    suspend fun paths(): List<Pair<GameId, String>> = withContext(dispatcher) {
        q.selectPaths().executeAsList().map { GameId(it.id) to it.path }
    }

    /** Every game with what tells it apart (its file, serial and RomM link), for Fuse RomM's matching. */
    suspend fun matchRows(): List<GameMatchRow> = withContext(dispatcher) {
        q.matchRows().executeAsList().map { r ->
            GameMatchRow(
                id = GameId(r.id),
                platform = r.platform_id,
                title = r.title_original,
                path = r.path,
                serial = io.github.matiyaaa.fuse.data.decodeOrNull(io.github.matiyaaa.fuse.model.FilenameTags.serializer(), r.tags_json)?.serial,
                rommRomId = r.romm_rom_id,
                missing = r.missing != 0L,
            )
        }
    }

    /** Local games linked to each RetroAchievements game id (for the COMPLETED collection). */
    /** Games set to an emulator of their own, present and in the library. */
    suspend fun emulatorOverrides(): Map<GameId, EmulatorId> = withContext(dispatcher) {
        q.selectEmulatorOverrides().executeAsList().mapNotNull { r -> r.emulator_override?.let { GameId(r.id) to EmulatorId(it) } }.toMap()
    }

    /** Games with a playlist, sheet or several discs, by their launch file, at most [limit]. */
    suspend fun playlistGames(limit: Int = 5_000): List<Pair<GameId, String>> = withContext(dispatcher) {
        q.selectPlaylistGames(limit.toLong()).executeAsList().map { GameId(it.id) to it.launch_path }
    }

    suspend fun idsForRetroAchievements(raGameIds: Collection<Long>): Map<Long, List<GameId>> = withContext(dispatcher) {
        raGameIds.distinct().chunked(SQL_CHUNK)
            .flatMap { q.selectIdsByRaGameIds(it).executeAsList() }
            .filter { it.ra_game_id != null }
            .groupBy({ it.ra_game_id!! }, { GameId(it.id) })
    }

    // Mutations -----------------------------------------------------------------------------------

    suspend fun setFavorite(id: GameId, favorite: Boolean) = write { q.setFavorite(favorite.toDb(), clock(), id.value) }

    suspend fun setHidden(id: GameId, hidden: Boolean) = write { q.setHidden(hidden.toDb(), clock(), id.value) }

    suspend fun setPinned(id: GameId, pinned: Boolean) = write { q.setPinned(pinned.toDb(), clock(), id.value) }

    /** Sets the user's display title, or clears it with null/blank. Files are never renamed. */
    suspend fun rename(id: GameId, customTitle: String?) = updateTitles(id) {
        it.copy(custom = customTitle?.trim()?.takeIf(String::isNotEmpty))
    }

    /** Switches between the cleaned and the on-disk title for one game. */
    suspend fun setUseCleaned(id: GameId, useCleaned: Boolean) = updateTitles(id) { it.copy(useCleaned = useCleaned) }

    suspend fun setEmulatorOverride(id: GameId, emulator: EmulatorId?) =
        write { q.setEmulatorOverride(emulator?.value, clock(), id.value) }

    suspend fun setFolderPolicyOverride(id: GameId, policy: FolderPolicy?) =
        write { q.setFolderPolicyOverride(policy?.name, clock(), id.value) }

    /**
     * Files the game under [platform] for good (a rescan keeps it), or back under the system its
     * folder says when null. The emulator chosen for the old system goes with it.
     */
    suspend fun setPlatformOverride(id: GameId, platform: PlatformId?) =
        write { q.setPlatformOverride(platform?.value, clock(), id.value) }

    /** Replaces every external id. */
    /** Per-game folder policy overrides by game path, for the scanner's folder policy resolver. */
    suspend fun folderPolicyOverrides(): Map<String, FolderPolicy> = withContext(dispatcher) {
        q.folderPolicyOverrides().executeAsList().mapNotNull { row ->
            val policy = row.folder_policy_override?.let { name -> FolderPolicy.entries.firstOrNull { it.name == name } }
            policy?.let { row.path to it }
        }.toMap()
    }

    suspend fun setLinks(id: GameId, links: ExternalLinks) = write { writeLinks(id, links) }

    /** Read-modify-write of the external ids in one transaction; returns the new links. */
    suspend fun updateLinks(id: GameId, transform: (ExternalLinks) -> ExternalLinks): ExternalLinks? =
        withContext(dispatcher) {
            db.transactionWithResult {
                val row = q.selectById(id.value).executeAsOneOrNull() ?: return@transactionWithResult null
                val current = ExternalLinks(row.ra_game_id, row.sgdb_game_id, row.igdb_id, row.romm_rom_id, row.steam_app_id)
                transform(current).also { writeLinks(id, it) }
            }
        }

    /**
     * Stores scraper or RomM metadata. With [onlyFillEmpty] only blank fields are filled; otherwise
     * provided (non-null) fields replace stored ones. Metadata the user edited (source USER) is only
     * ever filled, never overwritten, by non-user sources. [titleFromMetadata] fills the metadata
     * title; the user's custom title always wins over it and is never touched.
     * Returns false when the game does not exist.
     */
    suspend fun applyMetadata(
        id: GameId,
        metadata: GameMetadata,
        titleFromMetadata: String? = null,
        onlyFillEmpty: Boolean,
    ): Boolean = withContext(dispatcher) {
        db.transactionWithResult {
            val row = q.selectMetadata(id.value).executeAsOneOrNull() ?: return@transactionWithResult false
            val current = decodeOrNull(GameMetadata.serializer(), row.metadata_json) ?: GameMetadata()
            val protect = onlyFillEmpty || (current.source == MetadataSource.USER && metadata.source != MetadataSource.USER)
            val merged = if (protect) current.fillFrom(metadata) else current.overwriteWith(metadata)
            val newTitle = titleFromMetadata?.trim()?.takeIf(String::isNotEmpty)
            val metaTitle = when {
                newTitle == null -> row.title_metadata
                protect && row.title_metadata != null -> row.title_metadata
                else -> newTitle
            }
            val titles = GameTitles(row.title_original, row.title_cleaned, row.title_custom, metaTitle, row.use_cleaned.asBool())
            q.setMetadata(
                metadataJson = if (merged == GameMetadata()) null else DataJson.encodeToString(GameMetadata.serializer(), merged),
                releaseYear = merged.releaseYear?.toLong(),
                titleMetadata = metaTitle,
                searchTitle = TitleText.searchColumn(titles),
                sortTitle = TitleText.sortColumn(titles),
                now = clock(),
                id = id.value,
            )
            db.gameContentQueries.deleteGenres(id.value)
            merged.genres.map(String::trim).filter(String::isNotEmpty).forEach {
                db.gameContentQueries.insertGenre(id.value, it)
            }
            true
        }
    }

    /** Games whose details came from [source] (for checking RomM's details again). */
    suspend fun withMetadataFrom(source: MetadataSource): List<GameId> = withContext(dispatcher) {
        q.selectWithMetadataFrom(source.name).executeAsList().map(::GameId)
    }

    /**
     * Puts a game's details and metadata name back to exactly [metadata] and [titleMetadata] (null
     * clears them): undoing details that turned out to be another game's, or "Reset name and details".
     * The user's own name ([GameTitles.custom]) is never touched. Returns false when the game is gone.
     */
    suspend fun replaceMetadata(id: GameId, metadata: GameMetadata?, titleMetadata: String?): Boolean = withContext(dispatcher) {
        db.transactionWithResult {
            val row = q.selectMetadata(id.value).executeAsOneOrNull() ?: return@transactionWithResult false
            val meta = metadata?.takeIf { it != GameMetadata() }
            val metaTitle = titleMetadata?.trim()?.takeIf(String::isNotEmpty)
            val titles = GameTitles(row.title_original, row.title_cleaned, row.title_custom, metaTitle, row.use_cleaned.asBool())
            q.setMetadata(
                metadataJson = meta?.let { DataJson.encodeToString(GameMetadata.serializer(), it) },
                releaseYear = meta?.releaseYear?.toLong(),
                titleMetadata = metaTitle,
                searchTitle = TitleText.searchColumn(titles),
                sortTitle = TitleText.sortColumn(titles),
                now = clock(),
                id = id.value,
            )
            db.gameContentQueries.deleteGenres(id.value)
            meta?.genres.orEmpty().map(String::trim).filter(String::isNotEmpty).forEach {
                db.gameContentQueries.insertGenre(id.value, it)
            }
            true
        }
    }

    /**
     * "Remove from Fuse": hides the game everywhere and marks it removed so a rescan cannot bring it
     * back. Database only: files are never touched, and play time, media and edits are kept so
     * [restoreToFuse] undoes it completely.
     */
    suspend fun removeFromFuse(id: GameId) = write { q.setRemoved(removedAt = clock(), hidden = 1, now = clock(), id = id.value) }

    suspend fun restoreToFuse(id: GameId) = write { q.setRemoved(removedAt = null, hidden = 0, now = clock(), id = id.value) }

    /**
     * Deletes the record of a game whose file is missing, with its media, sessions and collection
     * entries. Only for an explicit user action on the Missing Games screen; returns false (and
     * deletes nothing) when the game is not missing.
     */
    suspend fun forgetMissing(id: GameId): Boolean = withContext(dispatcher) {
        db.transactionWithResult {
            val deleted = q.deleteMissing(id.value).value > 0
            if (deleted) db.mediaQueries.deleteOwner(MediaOwner.OfGame(id).type(), id.value.toString())
            deleted
        }
    }

    /**
     * Forgets a game whose files the user just deleted (Settings, Storage): the record goes, with
     * its media, sessions and collection entries, as if it had been missing.
     */
    suspend fun forgetDeleted(id: GameId, now: Long): Boolean = withContext(dispatcher) {
        db.transactionWithResult {
            q.markMissing(now, id.value)
            val deleted = q.deleteMissing(id.value).value > 0
            if (deleted) db.mediaQueries.deleteOwner(MediaOwner.OfGame(id).type(), id.value.toString())
            deleted
        }
    }

    /**
     * Points a game whose files were moved to another drive at their new place: everything of it
     * under [old] (the folder its files were in) is now under [new], in the library folder [source]
     * and its system folder [folder]. Its discs and content follow; play time, edits and art stay
     * with it. False when its path isn't under [old] or another game already has the new one, and
     * then nothing changes.
     */
    suspend fun relocate(id: GameId, source: LibrarySourceId, folder: String, old: String, new: String, now: Long): Boolean =
        withContext(dispatcher) {
            val from = old.trimEnd('/')
            val to = new.trimEnd('/')
            if (from.isEmpty() || to.isEmpty() || from == to) return@withContext false
            db.transactionWithResult {
                val row = q.selectById(id.value).executeAsOneOrNull() ?: return@transactionWithResult false
                val moved = when {
                    row.path == from -> to
                    row.path.startsWith("$from/") -> to + row.path.substring(from.length)
                    else -> return@transactionWithResult false
                }
                if (q.selectIdByPath(moved).executeAsOneOrNull() != null) return@transactionWithResult false
                db.gameContentQueries.relocateContent(old = from, new = to, id = id.value)
                db.gameContentQueries.relocateDiscs(old = from, new = to, id = id.value)
                q.relocateGame(sourceId = source.value, folder = folder, old = from, new = to, now = now, id = id.value)
                true
            }
        }

    // Internals -----------------------------------------------------------------------------------

    private suspend fun write(block: () -> Unit) = withContext(dispatcher) { block() }

    private fun writeLinks(id: GameId, links: ExternalLinks) {
        q.setLinks(
            raGameId = links.retroAchievementsGameId,
            sgdbGameId = links.steamGridDbGameId,
            igdbId = links.igdbId,
            rommRomId = links.rommRomId,
            steamAppId = links.steamAppId,
            now = clock(),
            id = id.value,
        )
    }

    private suspend fun updateTitles(id: GameId, transform: (GameTitles) -> GameTitles) = withContext(dispatcher) {
        db.transaction {
            val row = q.selectTitles(id.value).executeAsOneOrNull() ?: return@transaction
            val before = GameTitles(row.title_original, row.title_cleaned, row.title_custom, row.title_metadata, row.use_cleaned.asBool())
            db.writeTitles(id.value, transform(before), clock())
        }
    }

    private fun Query<Game_summary>.summaries(): Flow<List<GameSummary>> =
        asFlow().mapToList(dispatcher).map { rows -> rows.map { it.toSummary() } }.flowOn(dispatcher)
}

/** Writes every title column plus the derived search and sort columns. */
internal fun FuseDatabase.writeTitles(id: Long, titles: GameTitles, now: Long) {
    gameQueries.setTitleColumns(
        titleCustom = titles.custom,
        titleMetadata = titles.metadata,
        titleCleaned = titles.cleaned,
        useCleaned = titles.useCleaned.toDb(),
        searchTitle = TitleText.searchColumn(titles),
        sortTitle = TitleText.sortColumn(titles),
        now = now,
        id = id,
    )
}

internal fun SortOrder.code(): Long = when (this) {
    SortOrder.TITLE -> 0
    SortOrder.RECENTLY_PLAYED -> 1
    SortOrder.RECENTLY_ADDED -> 2
    SortOrder.MOST_PLAYED -> 3
    SortOrder.RELEASE_YEAR -> 4
}

fun GameMetadata.fillFrom(other: GameMetadata) = GameMetadata(
    description = description ?: other.description,
    releaseYear = releaseYear ?: other.releaseYear,
    developer = developer ?: other.developer,
    publisher = publisher ?: other.publisher,
    genres = genres.ifEmpty { other.genres },
    franchise = franchise ?: other.franchise,
    players = players ?: other.players,
    rating = rating ?: other.rating,
    source = source ?: other.source,
)

fun GameMetadata.overwriteWith(other: GameMetadata) = GameMetadata(
    description = other.description ?: description,
    releaseYear = other.releaseYear ?: releaseYear,
    developer = other.developer ?: developer,
    publisher = other.publisher ?: publisher,
    genres = other.genres.ifEmpty { genres },
    franchise = other.franchise ?: franchise,
    players = other.players ?: players,
    rating = other.rating ?: rating,
    source = other.source ?: source,
)

/** A game as Fuse RomM's matching sees it. */
data class GameMatchRow(
    val id: GameId,
    val platform: String,
    val title: String,
    val path: String,
    val serial: String?,
    val rommRomId: Long?,
    val missing: Boolean,
)
