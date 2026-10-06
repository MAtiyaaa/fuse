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
import io.github.matiyaaa.fuse.romm.RommMirror
import io.github.matiyaaa.fuse.romm.RommRom
import io.github.matiyaaa.fuse.ui.shell.store.rommGameId
import io.github.matiyaaa.fuse.ui.shell.store.rommOnly
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** What Fuse knows of a RomM game it doesn't have, beyond what the server says: kept for good. */
@Serializable
internal data class RommGameRecord(
    /** Details from Fuse's sources or the user, over RomM's. */
    val metadata: GameMetadata? = null,
    /** The name a source gave it. */
    val titleMetadata: String? = null,
    /** The user's own name (Rename Display Title). */
    val titleCustom: String? = null,
    val steamGridDbId: Long? = null,
    val igdbId: Long? = null,
)

/**
 * RomM games Fuse doesn't have, as games: the server's details from the mirror with Fuse's own
 * (found by the same sources and order as the library's games, or typed by the user) over them.
 * Records live in Fuse's database for each server and are read once into memory, so lists of
 * thousands of games draw at once and nothing found is ever lost or looked for again.
 */
internal class RommGames(
    private val ctx: StoreContext,
    private val mirror: RommMirror,
    private val server: () -> String,
    private val onChanged: (GameId) -> Unit,
) : RemoteGames {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private val lock = Mutex()
    private var loadedFor: String? = null
    private var records: MutableMap<Long, RommGameRecord> = HashMap()

    private fun namespace(server: String) = "romm.game.$server"

    /** Every record for the server in use, read once. */
    suspend fun all(): Map<Long, RommGameRecord> = lock.withLock { loaded() }

    private suspend fun loaded(): MutableMap<Long, RommGameRecord> {
        val s = server()
        if (loadedFor == s) return records
        val rows = withContext(Dispatchers.Default) { ctx.data.database.kvCacheQueries.inNamespace(namespace(s)).executeAsList() }
        records = rows.mapNotNull { row ->
            val id = row.key.toLongOrNull() ?: return@mapNotNull null
            runCatching { json.decodeFromString(RommGameRecord.serializer(), row.value_json) }.getOrNull()?.let { id to it }
        }.toMap(HashMap())
        loadedFor = s
        return records
    }

    private suspend fun edit(romId: Long, change: (RommGameRecord) -> RommGameRecord) {
        val s = server()
        val next = lock.withLock {
            val map = loaded()
            val after = change(map[romId] ?: RommGameRecord())
            map[romId] = after
            after
        }
        ctx.data.cache.put(namespace(s), romId.toString(), json.encodeToString(RommGameRecord.serializer(), next), ctx.now(), null)
    }

    override fun owns(id: GameId): Boolean = id.rommOnly != null

    override suspend fun get(id: GameId): Game? {
        val romId = id.rommOnly ?: return null
        val r = mirror.rom(server(), romId) ?: return null
        return game(r, lock.withLock { loaded()[romId] })
    }

    /** [r] as a Fuse game, with [record] over what the server says. */
    fun game(r: RommRom, record: RommGameRecord?): Game? {
        val platform = ctx.platforms.resolveFolder(r.platformSlug) ?: return null
        val original = r.fsName.substringBeforeLast('.').ifBlank { r.name }
        val server = GameMetadata(description = r.summary, releaseYear = r.year, developer = r.developer, genres = r.genres, source = MetadataSource.ROMM)
        return Game(
            id = rommGameId(r.id),
            platformId = platform.id,
            titles = GameTitles(original = original, custom = record?.titleCustom, metadata = record?.titleMetadata ?: r.name.ifBlank { null }),
            location = GameLocation(LibrarySourceId(0), "romm://${r.id}", if (r.multi) LocationKind.FOLDER else LocationKind.FILE, "romm://${r.id}/${r.fsName}", sizeBytes = r.sizeBytes),
            tags = FilenameParser.parse(r.fsName, hasExtension = '.' in r.fsName).tags,
            metadata = record?.metadata?.fillFrom(server) ?: server,
            links = ExternalLinks(steamGridDbGameId = record?.steamGridDbId, igdbId = record?.igdbId, rommRomId = r.id),
        )
    }

    /** The name Fuse shows for [r]: the user's, then a source's, then RomM's. */
    fun title(r: RommRom, record: RommGameRecord?): String = record?.titleCustom ?: record?.titleMetadata ?: r.name

    override suspend fun applyMetadata(id: GameId, metadata: GameMetadata, titleFromMetadata: String?, onlyFillEmpty: Boolean) {
        val romId = id.rommOnly ?: return
        edit(romId) { rec ->
            val current = rec.metadata ?: GameMetadata()
            // As for the library's games: what the user typed is only ever filled by a source, never replaced.
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
        val romId = id.rommOnly ?: return
        edit(romId) { it.copy(metadata = metadata?.takeIf { m -> m != GameMetadata() }, titleMetadata = titleMetadata?.trim()?.ifEmpty { null }) }
    }

    override suspend fun updateLinks(id: GameId, transform: (ExternalLinks) -> ExternalLinks) {
        val romId = id.rommOnly ?: return
        edit(romId) { rec ->
            val next = transform(ExternalLinks(steamGridDbGameId = rec.steamGridDbId, igdbId = rec.igdbId, rommRomId = romId))
            rec.copy(steamGridDbId = next.steamGridDbGameId, igdbId = next.igdbId)
        }
    }

    override suspend fun rename(id: GameId, title: String?) {
        val romId = id.rommOnly ?: return
        edit(romId) { it.copy(titleCustom = title?.trim()?.ifEmpty { null }) }
        onChanged(id)
    }

    override fun changed(id: GameId) = onChanged(id)
}
