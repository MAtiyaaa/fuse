package io.github.matiyaaa.fuse.data.repo

import io.github.matiyaaa.fuse.data.SQL_CHUNK
import io.github.matiyaaa.fuse.data.asBool
import io.github.matiyaaa.fuse.data.decodeOrNull
import io.github.matiyaaa.fuse.data.db.FuseDatabase
import io.github.matiyaaa.fuse.data.db.Game_content
import io.github.matiyaaa.fuse.data.db.Game_disc
import io.github.matiyaaa.fuse.data.db.Game_summary
import io.github.matiyaaa.fuse.data.enumOr
import io.github.matiyaaa.fuse.data.enumOrNull
import io.github.matiyaaa.fuse.model.AppGames
import io.github.matiyaaa.fuse.model.ChildContent
import io.github.matiyaaa.fuse.model.ContentKind
import io.github.matiyaaa.fuse.model.Disc
import io.github.matiyaaa.fuse.model.EmulatorId
import io.github.matiyaaa.fuse.model.ExternalLinks
import io.github.matiyaaa.fuse.model.FilenameTags
import io.github.matiyaaa.fuse.model.FolderInterpretation
import io.github.matiyaaa.fuse.model.FolderPolicy
import io.github.matiyaaa.fuse.model.Game
import io.github.matiyaaa.fuse.model.GameId
import io.github.matiyaaa.fuse.model.GameLocation
import io.github.matiyaaa.fuse.model.GameMetadata
import io.github.matiyaaa.fuse.model.GameTitles
import io.github.matiyaaa.fuse.model.LibrarySourceId
import io.github.matiyaaa.fuse.model.LocationKind
import io.github.matiyaaa.fuse.model.PlatformId
import io.github.matiyaaa.fuse.model.PlayStats
import io.github.matiyaaa.fuse.data.db.Game as GameRow

internal fun Game_summary.toSummary() = GameSummary(
    id = GameId(id),
    platformId = PlatformId(platform_id),
    titles = GameTitles(title_original, title_cleaned, title_custom, title_metadata, use_cleaned.asBool()),
    favorite = favorite.asBool(),
    hidden = hidden.asBool(),
    pinned = pinned.asBool(),
    missing = missing.asBool(),
    removed = removed_at != null,
    addedAt = added_at,
    lastPlayedAt = last_played_at,
    trackedSeconds = tracked_seconds,
    importedSeconds = imported_seconds,
    sessions = session_count.toInt(),
    releaseYear = release_year?.toInt(),
    dlcCount = dlc_count.toInt(),
    updateCount = update_count.toInt(),
    discCount = disc_count.toInt(),
    isApp = folder_path == AppGames.FOLDER,
)

internal fun GameRow.titles() = GameTitles(title_original, title_cleaned, title_custom, title_metadata, use_cleaned.asBool())

internal fun GameRow.toModel(content: List<ChildContent>, discs: List<Disc>) = Game(
    id = GameId(id),
    platformId = PlatformId(platform_id),
    titles = titles(),
    location = GameLocation(
        sourceId = LibrarySourceId(source_id),
        path = path,
        kind = enumOr(kind, LocationKind.FILE),
        launchPath = launch_path,
        sizeBytes = size_bytes,
        modifiedAt = modified_at,
        interpretation = enumOr(interpretation, FolderInterpretation.SINGLE_FILE),
    ),
    content = content,
    discs = discs,
    tags = decodeOrNull(FilenameTags.serializer(), tags_json) ?: FilenameTags(),
    metadata = decodeOrNull(GameMetadata.serializer(), metadata_json) ?: GameMetadata(),
    favorite = favorite.asBool(),
    hidden = hidden.asBool(),
    addedAt = added_at,
    play = PlayStats(
        lastPlayedAt = last_played_at,
        trackedSeconds = tracked_seconds,
        importedSeconds = imported_seconds,
        importedSource = imported_source,
        sessions = session_count.toInt(),
    ),
    links = ExternalLinks(
        retroAchievementsGameId = ra_game_id,
        steamGridDbGameId = sgdb_game_id,
        igdbId = igdb_id,
        rommRomId = romm_rom_id,
        steamAppId = steam_app_id,
    ),
    emulatorOverride = emulator_override?.let(::EmulatorId),
    folderPolicyOverride = enumOrNull<FolderPolicy>(folder_policy_override),
    platformOverride = platform_override?.let(::PlatformId),
    scannedPlatformId = platform_scanned?.let(::PlatformId),
)

internal fun GameRow.toRecord(game: Game) = GameRecord(
    game = game,
    pinned = pinned.asBool(),
    missing = missing.asBool(),
    missingSince = missing_since,
    removedAt = removed_at,
)

private fun Game_content.toChild() = ChildContent(
    kind = enumOr(kind, ContentKind.GAME),
    name = name,
    path = path,
    isDirectory = is_directory.asBool(),
    sizeBytes = size_bytes,
)

private fun Game_disc.toDisc() = Disc(number = number.toInt(), label = label, path = path)

/** Maps rows to [Game]s loading content and discs in chunked batches (two queries per 500 games). */
internal fun FuseDatabase.loadGames(rows: List<GameRow>): List<Game> {
    if (rows.isEmpty()) return emptyList()
    val ids = rows.map { it.id }
    val content = HashMap<Long, MutableList<ChildContent>>()
    val discs = HashMap<Long, MutableList<Disc>>()
    for (chunk in ids.chunked(SQL_CHUNK)) {
        gameContentQueries.contentFor(chunk).executeAsList().forEach {
            content.getOrPut(it.game_id) { mutableListOf() }.add(it.toChild())
        }
        gameContentQueries.discsFor(chunk).executeAsList().forEach {
            discs.getOrPut(it.game_id) { mutableListOf() }.add(it.toDisc())
        }
    }
    return rows.map { it.toModel(content[it.id].orEmpty(), discs[it.id].orEmpty()) }
}
