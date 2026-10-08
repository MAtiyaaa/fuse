package io.github.matiyaaa.fuse.data.repo

import io.github.matiyaaa.fuse.data.DataJson
import io.github.matiyaaa.fuse.data.TitleText
import io.github.matiyaaa.fuse.data.asBool
import io.github.matiyaaa.fuse.data.db.FuseDatabase
import io.github.matiyaaa.fuse.data.fnv1a64
import io.github.matiyaaa.fuse.data.ioDispatcher
import io.github.matiyaaa.fuse.data.lastInsertId
import io.github.matiyaaa.fuse.data.toDb
import io.github.matiyaaa.fuse.model.ContentKind
import io.github.matiyaaa.fuse.model.FilenameTags
import io.github.matiyaaa.fuse.model.GameId
import io.github.matiyaaa.fuse.model.GameTitles
import io.github.matiyaaa.fuse.model.MediaOwner
import io.github.matiyaaa.fuse.model.PlatformFolderScan
import io.github.matiyaaa.fuse.model.ScanReport
import io.github.matiyaaa.fuse.model.ScannedGame
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/** What one reconciliation changed, for "12 games added" style notices. */
data class IndexDelta(
    val added: Int = 0,
    /** Existing games whose files changed (size, date, children, name...). */
    val updated: Int = 0,
    /** Games newly marked missing. */
    val missing: Int = 0,
    /** Missing games whose files came back. */
    val restored: Int = 0,
    val addedIds: List<GameId> = emptyList(),
) {
    val isEmpty: Boolean get() = added == 0 && updated == 0 && missing == 0 && restored == 0

    operator fun plus(other: IndexDelta) = IndexDelta(
        added = added + other.added,
        updated = updated + other.updated,
        missing = missing + other.missing,
        restored = restored + other.restored,
        addedIds = addedIds + other.addedIds,
    )
}

/**
 * Reconciles scanner output with the database without ever losing user data.
 *
 * Per platform folder, in one transaction: new paths are inserted; known paths get only their
 * file-derived columns rewritten (kind, launch path, interpretation, size, date, original and cleaned
 * title, tags, content, discs); games previously seen in that folder but absent now are marked
 * missing (never deleted); returning games are un-marked. Custom titles, favourites, hidden,
 * overrides, links, metadata, media, collections and play time are never written here.
 */
class LibraryIndexer(
    private val db: FuseDatabase,
    private val dispatcher: CoroutineDispatcher = ioDispatcher,
) {
    /**
     * Applies every scanned folder of [report]. [cleaner] produces the cleaned display title from
     * the on-disk title (Clean Display Names). New games use the cleaned title when
     * [useCleanedForNew] is true; existing games keep their own choice.
     */
    suspend fun apply(
        report: ScanReport,
        now: Long,
        cleaner: (String) -> String,
        useCleanedForNew: Boolean = false,
    ): IndexDelta {
        // One folder can hold games of two systems (a shortcuts folder: Steam's and Windows'),
        // scanned once for each: what one found is never missing from the other.
        val byFolder = report.scanned.groupBy { it.folderPath }
        return report.scanned.fold(IndexDelta()) { acc, scan ->
            val elsewhere = byFolder[scan.folderPath].orEmpty().filter { it !== scan }.flatMap { s -> s.games.map { it.path } }.toSet()
            acc + applyFolder(scan, now, cleaner, useCleanedForNew, elsewhere)
        }
    }

    /**
     * Applies one platform folder in a single transaction. [foundElsewhere] are games the same
     * folder's scan for another system found, which are not missing.
     */
    suspend fun applyFolder(
        scan: PlatformFolderScan,
        now: Long,
        cleaner: (String) -> String,
        useCleanedForNew: Boolean = false,
        foundElsewhere: Set<String> = emptySet(),
    ): IndexDelta = withContext(dispatcher) {
        db.transactionWithResult { reconcile(scan, now, cleaner, useCleanedForNew, foundElsewhere) }
    }

    private fun reconcile(
        scan: PlatformFolderScan,
        now: Long,
        cleaner: (String) -> String,
        useCleanedForNew: Boolean,
        foundElsewhere: Set<String>,
    ): IndexDelta {
        val gq = db.gameQueries
        val known = gq.selectIndexStateByFolder(scan.folderPath, ::IndexState).executeAsList().associateBy { it.path }
        val seen = HashSet<Long>()
        val addedIds = ArrayList<GameId>()
        var updated = 0
        var restored = 0

        for (game in scan.games.associateBy { it.path }.values) {
            val cleaned = runCatching { cleaner(game.title).trim() }.getOrNull()?.takeIf(String::isNotEmpty)
            val tagsJson = if (game.tags == FilenameTags()) null else DataJson.encodeToString(FilenameTags.serializer(), game.tags)
            val hash = fingerprint(game, cleaned, tagsJson)
            val state = known[game.path] ?: gq.selectIndexStateByPath(game.path, ::IndexState).executeAsOneOrNull()

            if (state == null) {
                val titles = GameTitles(original = game.title, cleaned = cleaned, useCleaned = useCleanedForNew)
                gq.insertScanned(
                    platformId = game.platformId.value,
                    sourceId = game.sourceId.value,
                    folderPath = scan.folderPath,
                    path = game.path,
                    kind = game.kind.name,
                    launchPath = game.launchPath,
                    interpretation = game.interpretation.name,
                    sizeBytes = game.sizeBytes,
                    modifiedAt = game.modifiedAt,
                    scanHash = hash,
                    titleOriginal = game.title,
                    titleCleaned = cleaned,
                    useCleaned = useCleanedForNew.toDb(),
                    searchTitle = TitleText.searchColumn(titles),
                    sortTitle = TitleText.sortColumn(titles),
                    tagsJson = tagsJson,
                    dlcCount = game.content.count { it.kind == ContentKind.DLC }.toLong(),
                    updateCount = game.content.count { it.kind == ContentKind.UPDATE }.toLong(),
                    discCount = game.discs.size.toLong(),
                    now = now,
                )
                val id = db.lastInsertId()
                writeChildren(id, game)
                db.mergeLocalMedia(MediaOwner.OfGame(GameId(id)), game.localMedia, now)
                addedIds += GameId(id)
                continue
            }

            seen += state.id
            val moved = state.folderPath != scan.folderPath
            val changed = moved || state.scanHash != hash
            if (changed) {
                val titles = GameTitles(game.title, cleaned, state.titleCustom, state.titleMetadata, state.useCleaned)
                gq.updateScanned(
                    platformId = game.platformId.value,
                    sourceId = game.sourceId.value,
                    folderPath = scan.folderPath,
                    kind = game.kind.name,
                    launchPath = game.launchPath,
                    interpretation = game.interpretation.name,
                    sizeBytes = game.sizeBytes,
                    modifiedAt = game.modifiedAt,
                    scanHash = hash,
                    titleOriginal = game.title,
                    titleCleaned = cleaned,
                    searchTitle = TitleText.searchColumn(titles),
                    sortTitle = TitleText.sortColumn(titles),
                    tagsJson = tagsJson,
                    dlcCount = game.content.count { it.kind == ContentKind.DLC }.toLong(),
                    updateCount = game.content.count { it.kind == ContentKind.UPDATE }.toLong(),
                    discCount = game.discs.size.toLong(),
                    now = now,
                    id = state.id,
                )
                db.gameContentQueries.deleteContent(state.id)
                db.gameContentQueries.deleteDiscs(state.id)
                writeChildren(state.id, game)
            }
            if (state.missing) {
                gq.markPresent(now, state.id)
                if (!state.removed) restored++
            } else if (changed && !state.removed) {
                updated++
            }
            if (changed || state.missing) db.mergeLocalMedia(MediaOwner.OfGame(GameId(state.id)), game.localMedia, now)
        }

        var missing = 0
        var forgotten = 0
        if (scan.complete) {
            val partOfAnother = scan.partOfAnother()
            for (state in known.values) {
                if (state.id in seen || state.missing || state.path in foundElsewhere) continue
                // A folder that is still there but isn't a game (a game's own data folder, which
                // older versions listed as games): forgotten, unless the user did something with it.
                if (!state.removed && scan.isNotAGame(state.path) && gq.deleteUntouchedFolder(state.id).value > 0) {
                    db.mediaQueries.deleteOwner(MediaOwner.OfGame(GameId(state.id)).type(), state.id.toString())
                    forgotten++
                    continue
                }
                // A copy of another game, or an update or DLC that now sits with its game.
                if (!state.removed && normal(state.path) in partOfAnother && gq.deleteUntouchedGame(state.id).value > 0) {
                    db.mediaQueries.deleteOwner(MediaOwner.OfGame(GameId(state.id)).type(), state.id.toString())
                    forgotten++
                    continue
                }
                gq.markMissing(now, state.id)
                if (!state.removed) missing++
            }
            // Only a complete scan may let the next quick scan skip this folder.
            db.folderStateQueries.put(scan.folderPath, scan.folderModifiedAt)
        }
        return IndexDelta(added = addedIds.size, updated = updated, missing = missing, restored = restored, addedIds = addedIds)
    }

    private fun writeChildren(gameId: Long, game: ScannedGame) {
        val cq = db.gameContentQueries
        game.content.forEachIndexed { i, c ->
            cq.insertContent(gameId, i.toLong(), c.kind.name, c.name, c.path, c.isDirectory.toDb(), c.sizeBytes)
        }
        game.discs.forEachIndexed { i, d ->
            cq.insertDisc(gameId, i.toLong(), d.number.toLong(), d.label, d.path)
        }
    }

    private class IndexState(
        val id: Long,
        val path: String,
        val folderPath: String,
        val scanHash: String,
        val missing: Boolean,
        val removed: Boolean,
        val titleCustom: String?,
        val titleMetadata: String?,
        val useCleaned: Boolean,
    ) {
        @Suppress("UNUSED_PARAMETER")
        constructor(
            id: Long,
            path: String,
            platform_id: String,
            source_id: Long,
            folder_path: String,
            scan_hash: String,
            missing: Long,
            removed_at: Long?,
            title_custom: String?,
            title_metadata: String?,
            use_cleaned: Long,
        ) : this(id, path, folder_path, scan_hash, missing.asBool(), removed_at != null, title_custom, title_metadata, use_cleaned.asBool())
    }
}

/** Fingerprint of every file-derived field of [game]; equal fingerprints mean nothing to rewrite. */
internal fun fingerprint(game: ScannedGame, cleaned: String?, tagsJson: String?): String {
    val sb = StringBuilder(256)
    fun f(value: Any?) {
        sb.append(value?.toString() ?: "\u0001").append('\u0000')
    }
    f(game.platformId.value)
    f(game.sourceId.value)
    f(game.kind.name)
    f(game.launchPath)
    f(game.interpretation.name)
    f(game.sizeBytes)
    f(game.modifiedAt)
    f(game.title)
    f(cleaned)
    f(tagsJson)
    f("content")
    game.content.forEach { f(it.kind.name); f(it.name); f(it.path); f(it.isDirectory); f(it.sizeBytes) }
    f("discs")
    game.discs.forEach { f(it.number); f(it.label); f(it.path) }
    f("media")
    game.localMedia.entries.sortedBy { it.key.name }.forEach { f(it.key.name); f(it.value) }
    return fnv1a64(sb.toString())
}

/** True when [path] was read in this scan and found not to be a game, or lies in a skipped data folder. */
/** Paths that are now part of another game: merged copies, and every game's updates, DLC and other content. */
private fun PlatformFolderScan.partOfAnother(): Set<String> =
    (absorbed + games.flatMap { g -> g.content.map { it.path } }).mapTo(HashSet(), ::normal)

private fun normal(path: String): String = path.replace('\\', '/').trimEnd('/')

private fun PlatformFolderScan.isNotAGame(path: String): Boolean {
    if (path in notGames) return true
    val p = path.trimEnd('/')
    return notGameTrees.any { tree -> p.startsWith(tree.trimEnd('/') + "/") || p == tree.trimEnd('/') }
}
