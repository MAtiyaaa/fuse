package io.github.matiyaaa.fuse.data.backup

import io.github.matiyaaa.fuse.data.DataJson
import io.github.matiyaaa.fuse.data.TitleText
import io.github.matiyaaa.fuse.data.asBool
import io.github.matiyaaa.fuse.data.db.FuseDatabase
import io.github.matiyaaa.fuse.data.decodeOrNull
import io.github.matiyaaa.fuse.data.ioDispatcher
import io.github.matiyaaa.fuse.data.lastInsertId
import io.github.matiyaaa.fuse.data.repo.fillFrom
import io.github.matiyaaa.fuse.data.repo.writeTitles
import io.github.matiyaaa.fuse.data.settings.AppSettingsCodec
import io.github.matiyaaa.fuse.data.toDb
import io.github.matiyaaa.fuse.model.GameMetadata
import io.github.matiyaaa.fuse.model.GameTitles
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * What a backup can hold, restored together or on their own. A backup never holds games, firmware,
 * keys or passwords, nor what Fuse fetched by itself (scraped art, caches): only what the user made.
 */
enum class BackupPart {
    /**
     * Every setting except how Fuse looks: inputs, sound, library, scraping, displays, per-system
     * settings. How far setup got and the performance profile stay this device's own.
     */
    SETTINGS,

    /** The theme and its options, added themes, and Home: Flow's rows and the widget board as arranged. */
    APPEARANCE,

    /** Each game's own name, favourite, hidden and pinned state, emulator, system, details and links, collections and chosen art. */
    LIBRARY,

    /** Play time and play sessions. */
    PLAYTIME,
}

/** Identifies a game across devices: its path, else its path inside its library folder, else its file name on its system. */
@Serializable
data class GameKey(
    val platform: String,
    val path: String,
    /** The game's path inside its library folder ("psx/Game.chd"), when known. */
    val relative: String? = null,
) {
    val name: String get() = path.trimEnd('/').substringAfterLast('/')
}

@Serializable
data class BackupGame(
    val key: GameKey,
    val customTitle: String? = null,
    val useCleaned: Boolean = false,
    val titleMetadata: String? = null,
    val metadataJson: String? = null,
    val favorite: Boolean = false,
    val hidden: Boolean = false,
    val pinned: Boolean = false,
    val removedAt: Long? = null,
    val emulatorOverride: String? = null,
    val platformOverride: String? = null,
    val folderPolicy: String? = null,
    val trackedSeconds: Long = 0,
    val importedSeconds: Long = 0,
    val importedSource: String? = null,
    val sessions: Int = 0,
    val lastPlayedAt: Long? = null,
    val raGameId: Long? = null,
    val sgdbGameId: Long? = null,
    val igdbId: Long? = null,
    val rommRomId: Long? = null,
    val steamAppId: Long? = null,
)

@Serializable
data class BackupSession(val game: GameKey, val emulator: String? = null, val startedAt: Long, val endedAt: Long, val source: String)

@Serializable
data class BackupCollection(val name: String, val order: Int = 0, val createdAt: Long = 0, val games: List<GameKey> = emptyList())

/** Art the user chose. [file] names the picture inside the backup when it was a file of the user's own. */
@Serializable
data class BackupMedia(
    val ownerType: String,
    /** A platform id or a collection name; for a game, [game]. */
    val owner: String,
    val game: GameKey? = null,
    val kind: String,
    val remoteUrl: String? = null,
    val file: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val focusX: Float = 0.5f,
    val focusY: Float = 0.5f,
    val zoom: Float = 1f,
)

/** A setting of one system or one game. Game settings name their game by key. */
@Serializable
data class BackupScopedSetting(val scope: String, val scopeId: String, val game: GameKey? = null, val key: String, val valueJson: String)

/** Everything in a backup, as `content.json` inside the archive. */
@Serializable
data class BackupContent(
    /** The global settings document as stored, so parts a later Fuse knows survive. */
    val settings: String? = null,
    val scoped: List<BackupScopedSetting> = emptyList(),
    val games: List<BackupGame> = emptyList(),
    val sessions: List<BackupSession> = emptyList(),
    val collections: List<BackupCollection> = emptyList(),
    val media: List<BackupMedia> = emptyList(),
)

/** `manifest.json` inside the archive: what it is, from which Fuse, and what it holds. */
@Serializable
data class BackupManifest(
    val format: String = FORMAT,
    val version: Int = VERSION,
    val fuseVersion: String,
    val host: String,
    val createdAt: Long,
    val games: Int = 0,
    val collections: Int = 0,
    val sessions: Int = 0,
    val media: Int = 0,
) {
    companion object {
        const val FORMAT = "fuse-backup"

        /** The newest format this Fuse writes. Older ones are read; newer ones are read as far as understood. */
        const val VERSION = 1
    }
}

/** What a restore did. Games not in this library are reported, never created. */
data class RestoreReport(
    val games: Int = 0,
    val gamesNotFound: Int = 0,
    val sessions: Int = 0,
    val collections: Int = 0,
    val media: Int = 0,
    val settings: Boolean = false,
    val appearance: Boolean = false,
)

/**
 * Builds and applies backups. Export reads; restore writes in one transaction, so a restore that
 * fails changes nothing. Restoring merges: what the backup sets wins, what it leaves unset never
 * erases what this library has, and nothing is ever deleted.
 */
class BackupRepository(
    private val db: FuseDatabase,
    private val dispatcher: CoroutineDispatcher = ioDispatcher,
    private val clock: () -> Long,
) {
    private val q get() = db.backupQueries

    suspend fun export(): BackupContent = withContext(dispatcher) {
        val rows = q.gamesForBackup().executeAsList()
        val keys = rows.associate { r -> r.id to keyOf(r.platform_scanned ?: r.platform_id, r.path, r.source_path) }
        val games = rows.map { r ->
            BackupGame(
                key = keys.getValue(r.id),
                customTitle = r.title_custom,
                useCleaned = r.use_cleaned.asBool(),
                titleMetadata = r.title_metadata,
                metadataJson = r.metadata_json,
                favorite = r.favorite.asBool(),
                hidden = r.hidden.asBool(),
                pinned = r.pinned.asBool(),
                removedAt = r.removed_at,
                emulatorOverride = r.emulator_override,
                platformOverride = r.platform_override,
                folderPolicy = r.folder_policy_override,
                trackedSeconds = r.tracked_seconds,
                importedSeconds = r.imported_seconds,
                importedSource = r.imported_source,
                sessions = r.session_count.toInt(),
                lastPlayedAt = r.last_played_at,
                raGameId = r.ra_game_id,
                sgdbGameId = r.sgdb_game_id,
                igdbId = r.igdb_id,
                rommRomId = r.romm_rom_id,
                steamAppId = r.steam_app_id,
            )
        }.filter { it.isWorthKeeping() }
        val sessions = q.endedSessions().executeAsList().mapNotNull { s ->
            val key = keys[s.game_id] ?: return@mapNotNull null
            BackupSession(key, s.emulator_id, s.started_at, s.ended_at ?: return@mapNotNull null, s.source)
        }
        val members = q.collectionMembers().executeAsList().groupBy({ it.collection_id }, { it.game_id })
        val collections = q.manualCollections().executeAsList().map { c ->
            BackupCollection(c.name, c.sort_order.toInt(), c.created_at, members[c.id].orEmpty().mapNotNull { keys[it] })
        }
        val collectionNames = q.manualCollections().executeAsList().associate { it.id.toString() to it.name }
        val media = q.userMedia().executeAsList().mapNotNull { m ->
            val game = if (m.owner_type == "game") keys[m.owner_id.toLongOrNull()] ?: return@mapNotNull null else null
            val owner = when (m.owner_type) {
                "collection" -> collectionNames[m.owner_id] ?: return@mapNotNull null
                else -> m.owner_id
            }
            BackupMedia(
                ownerType = m.owner_type, owner = owner, game = game, kind = m.kind, remoteUrl = m.remote_url,
                file = m.local_path, width = m.width?.toInt(), height = m.height?.toInt(),
                focusX = m.focus_x.toFloat(), focusY = m.focus_y.toFloat(), zoom = m.zoom.toFloat(),
            )
        }
        val scoped = q.scopedRows().executeAsList().mapNotNull { s ->
            if (s.scope == "GAME") {
                val key = keys[s.scope_id.toLongOrNull()] ?: return@mapNotNull null
                BackupScopedSetting(s.scope, "", key, s.key, s.value_json)
            } else {
                BackupScopedSetting(s.scope, s.scope_id, null, s.key, s.value_json)
            }
        }
        BackupContent(
            settings = db.settingQueries.get("GLOBAL", "", "app").executeAsOneOrNull(),
            scoped = scoped,
            games = games,
            sessions = sessions,
            collections = collections,
            media = media,
        )
    }

    /** How many of the backup's games are in this library, and how many aren't. */
    suspend fun matches(content: BackupContent): Pair<Int, Int> = withContext(dispatcher) {
        val match = GameMatcher(q.gamesForBackup().executeAsList().map { r -> r.id to keyOf(r.platform_scanned ?: r.platform_id, r.path, r.source_path) })
        val found = content.games.count { match.find(it.key) != null }
        found to content.games.size - found
    }

    /**
     * Applies [parts] of [content]. [mediaFiles] maps a [BackupMedia.file] to where the caller put
     * that picture on this device; art whose file couldn't be placed is skipped.
     */
    suspend fun restore(content: BackupContent, parts: Set<BackupPart>, mediaFiles: Map<String, String> = emptyMap()): RestoreReport =
        withContext(dispatcher) {
            db.transactionWithResult {
                val now = clock()
                val match = GameMatcher(q.gamesForBackup().executeAsList().map { r -> r.id to keyOf(r.platform_scanned ?: r.platform_id, r.path, r.source_path) })
                var report = RestoreReport()

                if (BackupPart.SETTINGS in parts || BackupPart.APPEARANCE in parts) {
                    val merged = mergeSettings(content.settings, parts)
                    if (merged != null) {
                        db.settingQueries.put("GLOBAL", "", "app", merged, now)
                        report = report.copy(settings = BackupPart.SETTINGS in parts, appearance = BackupPart.APPEARANCE in parts)
                    }
                }
                if (BackupPart.SETTINGS in parts) {
                    content.scoped.filter { it.scope != "GAME" }.forEach { db.settingQueries.put(it.scope, it.scopeId, it.key, it.valueJson, now) }
                }

                if (BackupPart.LIBRARY in parts || BackupPart.PLAYTIME in parts) {
                    var restored = 0
                    var missing = 0
                    for (g in content.games) {
                        val id = match.find(g.key)
                        if (id == null) {
                            missing++
                            continue
                        }
                        restoreGame(id, g, parts, now)
                        restored++
                    }
                    report = report.copy(games = restored, gamesNotFound = missing)
                }

                if (BackupPart.LIBRARY in parts) {
                    content.scoped.filter { it.scope == "GAME" }.forEach { s ->
                        val id = s.game?.let(match::find) ?: return@forEach
                        db.settingQueries.put("GAME", id.toString(), s.key, s.valueJson, now)
                    }
                    var cols = 0
                    for (c in content.collections) {
                        val id = q.manualCollectionByName(c.name).executeAsOneOrNull() ?: run {
                            db.collectionQueries.insert(c.name, "MANUAL", c.order.toLong(), c.createdAt.takeIf { it > 0 } ?: now)
                            db.lastInsertId()
                        }
                        c.games.forEachIndexed { i, key -> match.find(key)?.let { db.collectionQueries.addGame(id, it, i.toLong()) } }
                        cols++
                    }
                    var art = 0
                    for (m in content.media) {
                        val ownerId = when (m.ownerType) {
                            "game" -> m.game?.let(match::find)?.toString()
                            "collection" -> q.manualCollectionByName(m.owner).executeAsOneOrNull()?.toString()
                            else -> m.owner
                        } ?: continue
                        // The art chosen here now stays; only a slot without the user's own choice takes the backup's.
                        if (q.userMediaKindCount(m.ownerType, ownerId, m.kind).executeAsOne() > 0) continue
                        val local = m.file?.let { mediaFiles[it] }
                        if (local == null && m.remoteUrl == null) continue
                        db.mediaQueries.insert(
                            m.ownerType, ownerId, m.kind, "USER", local, m.remoteUrl, m.width?.toLong(), m.height?.toLong(),
                            m.focusX.toDouble(), m.focusY.toDouble(), m.zoom.toDouble(), -1, now,
                        )
                        art++
                    }
                    report = report.copy(collections = cols, media = art)
                }

                if (BackupPart.PLAYTIME in parts) {
                    var added = 0
                    for (s in content.sessions) {
                        val id = match.find(s.game) ?: continue
                        if (q.sessionExists(id, s.startedAt).executeAsOne() > 0) continue
                        db.playSessionQueries.insert(id, s.emulator, s.startedAt, s.endedAt, s.source)
                        added++
                    }
                    report = report.copy(sessions = added)
                }
                report
            }
        }

    private fun restoreGame(id: Long, g: BackupGame, parts: Set<BackupPart>, now: Long) {
        val cur = db.gameQueries.selectById(id).executeAsOne()
        val library = BackupPart.LIBRARY in parts
        val playtime = BackupPart.PLAYTIME in parts
        q.restoreGame(
            favorite = (cur.favorite.asBool() || (library && g.favorite)).toDb(),
            hidden = (cur.hidden.asBool() || (library && g.hidden)).toDb(),
            pinned = (cur.pinned.asBool() || (library && g.pinned)).toDb(),
            removedAt = cur.removed_at ?: g.removedAt.takeIf { library },
            emulatorOverride = g.emulatorOverride.takeIf { library } ?: cur.emulator_override,
            platformOverride = g.platformOverride.takeIf { library } ?: cur.platform_override,
            folderPolicy = g.folderPolicy.takeIf { library } ?: cur.folder_policy_override,
            tracked = if (playtime) maxOf(cur.tracked_seconds, g.trackedSeconds) else cur.tracked_seconds,
            imported = if (playtime) maxOf(cur.imported_seconds, g.importedSeconds) else cur.imported_seconds,
            importedSource = if (playtime) g.importedSource ?: cur.imported_source else cur.imported_source,
            sessions = if (playtime) maxOf(cur.session_count, g.sessions.toLong()) else cur.session_count,
            lastPlayed = if (playtime) listOfNotNull(cur.last_played_at, g.lastPlayedAt).maxOrNull() else cur.last_played_at,
            raGameId = cur.ra_game_id ?: g.raGameId.takeIf { library },
            sgdbGameId = cur.sgdb_game_id ?: g.sgdbGameId.takeIf { library },
            igdbId = cur.igdb_id ?: g.igdbId.takeIf { library },
            rommRomId = cur.romm_rom_id ?: g.rommRomId.takeIf { library },
            steamAppId = cur.steam_app_id ?: g.steamAppId.takeIf { library },
            now = now,
            id = id,
        )
        if (!library) return
        // Names: the backup's own name wins; a name the backup doesn't have never erases one here.
        val titles = GameTitles(
            original = cur.title_original,
            cleaned = cur.title_cleaned,
            custom = g.customTitle ?: cur.title_custom,
            metadata = cur.title_metadata ?: g.titleMetadata,
            useCleaned = if (g.customTitle != null) g.useCleaned else cur.use_cleaned.asBool(),
        )
        db.writeTitles(id, titles, now)
        // Details fill what this library doesn't have yet; nothing it has is replaced.
        val restored = decodeOrNull(GameMetadata.serializer(), g.metadataJson) ?: return
        val current = decodeOrNull(GameMetadata.serializer(), cur.metadata_json) ?: GameMetadata()
        val merged = current.fillFrom(restored)
        if (merged == current) return
        db.gameQueries.setMetadata(
            metadataJson = DataJson.encodeToString(GameMetadata.serializer(), merged),
            releaseYear = merged.releaseYear?.toLong(),
            titleMetadata = titles.metadata,
            searchTitle = TitleText.searchColumn(titles),
            sortTitle = TitleText.sortColumn(titles),
            now = now,
            id = id,
        )
        db.gameContentQueries.deleteGenres(id)
        merged.genres.map { it.trim() }.filter { it.isNotEmpty() }.forEach { db.gameContentQueries.insertGenre(id, it) }
    }

    /**
     * The settings document after restoring [parts] of [backup] onto the one stored now. Settings
     * takes every section but appearance and Home; Appearance takes those two (Home's per-game
     * "taken off Continue playing" list stays this library's, since it names games by their id here).
     */
    private fun mergeSettings(backup: String?, parts: Set<BackupPart>): String? {
        // Bring the backup's document up to this version first, so its sections and this one's agree.
        val from = parseObject(backup)?.let { parseObject(AppSettingsCodec.encode(AppSettingsCodec.decode(backup), previous = backup)) } ?: return null
        val current = parseObject(db.settingQueries.get("GLOBAL", "", "app").executeAsOneOrNull()) ?: JsonObject(emptyMap())
        val out = LinkedHashMap(current)
        val look = setOf("appearance", "home")
        if (BackupPart.SETTINGS in parts) {
            from.filterKeys { it !in look && it !in DEVICE_SECTIONS }.forEach { (k, v) -> out[k] = v }
            // Which name cleanups already ran describes this database, not the one backed up.
            val library = from["library"] as? JsonObject
            if (library != null) {
                val here = current["library"] as? JsonObject
                out["library"] = JsonObject(library - LIBRARY_MARKERS + LIBRARY_MARKERS.mapNotNull { k -> here?.get(k)?.let { k to it } })
            }
        }
        if (BackupPart.APPEARANCE in parts) {
            from["appearance"]?.let { out["appearance"] = it }
            (from["home"] as? JsonObject)?.let { home ->
                val keep = (current["home"] as? JsonObject)?.get("continueDismissed")
                out["home"] = JsonObject(home - "continueDismissed" + listOfNotNull(keep?.let { "continueDismissed" to it }))
            }
        }
        // Re-read through the settings codec, so a document from another version is upgraded and repaired.
        val text = Json.encodeToString(JsonObject.serializer(), JsonObject(out))
        return AppSettingsCodec.encode(AppSettingsCodec.decode(text), previous = text)
    }

    private fun parseObject(text: String?): JsonObject? =
        text?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() }

    private fun keyOf(platform: String, path: String, sourcePath: String?): GameKey {
        val relative = sourcePath?.trimEnd('/')?.let { root -> path.takeIf { it.startsWith("$root/") }?.removePrefix("$root/") }
        return GameKey(platform, path, relative)
    }

    private fun BackupGame.isWorthKeeping(): Boolean =
        customTitle != null || favorite || hidden || pinned || removedAt != null || emulatorOverride != null ||
            platformOverride != null || folderPolicy != null || metadataJson != null || trackedSeconds > 0 ||
            importedSeconds > 0 || lastPlayedAt != null || raGameId != null || sgdbGameId != null || igdbId != null ||
            rommRomId != null || steamAppId != null

    companion object {
        /** Sections about this device or this install, never taken from a backup: how far setup got, the performance profile. */
        private val DEVICE_SECTIONS = setOf("version", "onboarding", "performance")
        private val LIBRARY_MARKERS = setOf("cleanedExistingNames", "cleanedNamesRules")

        val Codec: Json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = false
            explicitNulls = false
            prettyPrint = true
        }
    }
}

/**
 * Finds a backed-up game in this library: by its exact path, else by its path inside its library
 * folder on the same system (a library copied to a new card), else by its file name on the same
 * system when only one game has it. Two candidates are never guessed between.
 */
internal class GameMatcher(games: List<Pair<Long, GameKey>>) {
    private val byPath = games.associate { (id, k) -> k.path to id }
    private val byRelative = unique(games.mapNotNull { (id, k) -> k.relative?.let { (k.platform to it.lowercase()) to id } })
    private val byName = unique(games.map { (id, k) -> (k.platform to k.name.lowercase()) to id })

    fun find(key: GameKey): Long? =
        byPath[key.path]
            ?: key.relative?.let { byRelative[key.platform to it.lowercase()] }
            ?: byName[key.platform to key.name.lowercase()]

    private fun <K> unique(pairs: List<Pair<K, Long>>): Map<K, Long> =
        pairs.groupBy({ it.first }, { it.second }).filterValues { it.size == 1 }.mapValues { it.value.single() }
}
