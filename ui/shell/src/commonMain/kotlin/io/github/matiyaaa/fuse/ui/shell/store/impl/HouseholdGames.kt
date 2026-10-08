package io.github.matiyaaa.fuse.ui.shell.store.impl

import io.github.matiyaaa.fuse.data.repo.fillFrom
import io.github.matiyaaa.fuse.data.repo.overwriteWith
import io.github.matiyaaa.fuse.library.parse.FilenameParser
import io.github.matiyaaa.fuse.model.ExternalLinks
import io.github.matiyaaa.fuse.model.Game
import io.github.matiyaaa.fuse.model.GameId
import io.github.matiyaaa.fuse.model.GameLocation
import io.github.matiyaaa.fuse.model.GameMetadata
import io.github.matiyaaa.fuse.model.GameTitles
import io.github.matiyaaa.fuse.model.LibrarySourceId
import io.github.matiyaaa.fuse.model.LocationKind
import io.github.matiyaaa.fuse.model.MetadataSource
import io.github.matiyaaa.fuse.sync.LibraryEntry
import io.github.matiyaaa.fuse.ui.shell.store.HOUSEHOLD_BASE
import io.github.matiyaaa.fuse.ui.shell.store.householdGameId
import io.github.matiyaaa.fuse.ui.shell.store.householdOnly
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * The household's other devices' games, as games here: each known by the household's id for it
 * (the same on every device), shown with an id of its own on this device that never changes, and
 * with Fuse's own name, details and art for it kept here for good (in the database, art in the
 * media table under its id), found by the same sources and rules as RomM's games and the library's.
 * So the Fuse Library opens at once and stays complete while a device or the host is away.
 */
internal class HouseholdGames(
    private val ctx: StoreContext,
    /** The best copy of a game the household has, by its household id; null when no device lists it any more. */
    private val entryOf: (String) -> LibraryEntry?,
    private val onChanged: (GameId) -> Unit,
) : RemoteGames {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private val lock = Mutex()
    private var loaded = false
    private val seqs = HashMap<String, Long>()
    private val keys = HashMap<Long, String>()
    private val records = HashMap<String, RommGameRecord>()

    private suspend fun load() {
        if (loaded) return
        withContext(Dispatchers.Default) {
            ctx.data.database.kvCacheQueries.inNamespace(IDS).executeAsList().forEach { row ->
                row.value_json.trim('"').toLongOrNull()?.let { seq -> seqs[row.key] = seq; keys[seq] = row.key }
            }
            ctx.data.database.kvCacheQueries.inNamespace(RECORDS).executeAsList().forEach { row ->
                runCatching { json.decodeFromString(RommGameRecord.serializer(), row.value_json) }.getOrNull()?.let { records[row.key] = it }
            }
        }
        loaded = true
    }

    /** The id [key] is shown with here: given once, kept for good. */
    suspend fun idOf(key: String): GameId = lock.withLock {
        load()
        seqs[key]?.let { return householdGameId(it) }
        val next = (seqs.values.maxOrNull() ?: 0L) + 1
        seqs[key] = next
        keys[next] = key
        ctx.data.cache.put(IDS, key, "\"$next\"", ctx.now(), null)
        householdGameId(next)
    }

    /** Ids for every key at once (a list of thousands). */
    suspend fun idsOf(all: Collection<String>): Map<String, GameId> {
        val out = HashMap<String, GameId>()
        val fresh = ArrayList<String>()
        lock.withLock {
            load()
            for (k in all) seqs[k]?.let { out[k] = householdGameId(it) } ?: fresh.add(k)
        }
        for (k in fresh) out[k] = idOf(k)
        return out
    }

    /** The household id behind [id], or null. */
    suspend fun keyOf(id: GameId): String? {
        val seq = id.householdOnly ?: return null
        return lock.withLock { load(); keys[seq] }
    }

    suspend fun record(key: String): RommGameRecord? = lock.withLock { load(); records[key] }

    suspend fun allRecords(): Map<String, RommGameRecord> = lock.withLock { load(); HashMap(records) }

    private suspend fun edit(key: String, change: (RommGameRecord) -> RommGameRecord) {
        val next = lock.withLock {
            load()
            change(records[key] ?: RommGameRecord()).also { records[key] = it }
        }
        ctx.data.cache.put(RECORDS, key, json.encodeToString(RommGameRecord.serializer(), next), ctx.now(), null)
    }

    override fun owns(id: GameId): Boolean = id.value <= HOUSEHOLD_BASE - 1

    override suspend fun get(id: GameId): Game? {
        val key = keyOf(id) ?: return null
        val entry = entryOf(key) ?: return null
        return game(id, entry, record(key))
    }

    /** [entry] as a Fuse game, with Fuse's own [record] over what the device that has it said. */
    fun game(id: GameId, entry: LibraryEntry, record: RommGameRecord?): Game? {
        val platform = ctx.platforms.byId(io.github.matiyaaa.fuse.model.PlatformId(entry.platform)) ?: ctx.platforms.resolveFolder(entry.platform) ?: return null
        val theirs = GameMetadata(description = entry.summary, releaseYear = entry.releaseYear, developer = entry.developer, genres = entry.genres, source = MetadataSource.LOCAL)
        val name = entry.name.ifBlank { entry.title }
        return Game(
            id = id,
            platformId = platform.id,
            titles = GameTitles(original = name.substringBeforeLast('.').ifBlank { entry.title }, custom = record?.titleCustom, metadata = record?.titleMetadata ?: entry.title),
            location = GameLocation(LibrarySourceId(0), "household://${entry.game}", if (entry.folder) LocationKind.FOLDER else LocationKind.FILE, "household://${entry.game}/$name", sizeBytes = entry.sizeBytes),
            tags = FilenameParser.parse(name, hasExtension = '.' in name).tags.let { it.copy(serial = entry.serial ?: it.serial) },
            metadata = record?.metadata?.fillFrom(theirs) ?: theirs,
            links = ExternalLinks(steamGridDbGameId = record?.steamGridDbId ?: entry.steamGridDbId, igdbId = record?.igdbId ?: entry.igdbId, providerClaims = entry.providerClaims),
        )
    }

    /** The name Fuse shows: the person's, then a source's, then the device's. */
    fun title(entry: LibraryEntry, record: RommGameRecord?): String = record?.titleCustom ?: record?.titleMetadata ?: entry.title

    override suspend fun applyMetadata(id: GameId, metadata: GameMetadata, titleFromMetadata: String?, onlyFillEmpty: Boolean) {
        val key = keyOf(id) ?: return
        edit(key) { rec ->
            val current = rec.metadata ?: GameMetadata()
            val protect = onlyFillEmpty || (current.source == MetadataSource.USER && metadata.source != MetadataSource.USER)
            val merged = if (protect) current.fillFrom(metadata) else current.overwriteWith(metadata)
            val newTitle = titleFromMetadata?.trim()?.takeIf(String::isNotEmpty)
            val title = when {
                newTitle == null -> rec.titleMetadata
                protect && rec.titleMetadata != null -> rec.titleMetadata
                else -> newTitle
            }
            rec.copy(metadata = merged.takeIf { it != GameMetadata() }, titleMetadata = title)
        }
    }

    override suspend fun replaceMetadata(id: GameId, metadata: GameMetadata?, titleMetadata: String?) {
        val key = keyOf(id) ?: return
        edit(key) { it.copy(metadata = metadata?.takeIf { m -> m != GameMetadata() }, titleMetadata = titleMetadata?.trim()?.ifEmpty { null }) }
    }

    override suspend fun updateLinks(id: GameId, transform: (ExternalLinks) -> ExternalLinks) {
        val key = keyOf(id) ?: return
        val entry = entryOf(key)
        edit(key) { rec ->
            val next = transform(ExternalLinks(steamGridDbGameId = rec.steamGridDbId ?: entry?.steamGridDbId, igdbId = rec.igdbId ?: entry?.igdbId))
            rec.copy(steamGridDbId = next.steamGridDbGameId, igdbId = next.igdbId)
        }
    }

    override suspend fun rename(id: GameId, title: String?) {
        val key = keyOf(id) ?: return
        edit(key) { it.copy(titleCustom = title?.trim()?.ifEmpty { null }) }
        onChanged(id)
    }

    override fun changed(id: GameId) = onChanged(id)

    companion object {
        const val IDS = "reach.ids"
        const val RECORDS = "reach.game"
    }
}
