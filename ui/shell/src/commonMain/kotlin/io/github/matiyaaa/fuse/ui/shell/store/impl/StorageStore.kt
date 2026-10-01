package io.github.matiyaaa.fuse.ui.shell.store.impl

import io.github.matiyaaa.fuse.library.FsPath
import io.github.matiyaaa.fuse.library.storage.GameFiles
import io.github.matiyaaa.fuse.model.GameId
import io.github.matiyaaa.fuse.model.LibrarySource
import io.github.matiyaaa.fuse.model.LibrarySourceId
import io.github.matiyaaa.fuse.ui.shell.store.DeleteReport
import io.github.matiyaaa.fuse.ui.shell.store.GameSize
import io.github.matiyaaa.fuse.ui.shell.store.StorageOps
import io.github.matiyaaa.fuse.ui.shell.store.StorageUsage
import io.github.matiyaaa.fuse.ui.shell.store.SystemShare
import io.github.matiyaaa.fuse.ui.shell.store.VolumeUsage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

internal class DefaultStorageOps(private val ctx: StoreContext) : StorageOps {
    private val state = MutableStateFlow<StorageUsage?>(null)
    override val usage: StateFlow<StorageUsage?> = state
    private var job: Job? = null

    override fun refresh() {
        job?.cancel()
        job = ctx.scope.launch {
            val sources = ctx.data.sources.all().filter { it.enabled }
            val summaries = ctx.data.games.observeAll().first()
            val cards = ctx.cardsOnce(summaries).associateBy { it.id }
            val volumes = volumesOf(sources)
            val sizes = ArrayList<GameSize>()
            val sourceOf = HashMap<GameId, LibrarySourceId>()
            fun publish(finished: Boolean) {
                state.value = StorageUsage(
                    volumes = volumes.map { v -> v.withGames(sizes.filter { sourceOf[it.card.id] in v.sources }) },
                    games = sizes.sortedByDescending { it.bytes },
                    measured = sizes.size,
                    total = summaries.size,
                    finished = finished,
                )
            }
            publish(finished = summaries.isEmpty())
            for ((i, summary) in summaries.withIndex()) {
                val game = ctx.data.games.get(summary.id) ?: continue
                val card = cards[summary.id] ?: continue
                val set = try {
                    GameFiles.resolve(ctx.services.fs, game.location, game.discs)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    null
                } ?: continue
                sourceOf[game.id] = game.location.sourceId
                sizes += GameSize(card, set.sizeBytes, set.fileCount)
                if (i % 25 == 24) publish(finished = false)
            }
            publish(finished = true)
        }
    }

    override suspend fun size(game: GameId): Long? {
        val g = ctx.data.games.get(game) ?: return null
        return runCatching { GameFiles.resolve(ctx.services.fs, g.location, g.discs) }.getOrNull()?.takeIf { !it.isEmpty }?.sizeBytes
    }

    override suspend fun delete(games: List<GameId>): DeleteReport {
        val sources = ctx.data.sources.all().associateBy { it.id }
        var freed = 0L
        val failed = ArrayList<String>()
        val gone = HashSet<GameId>()
        for (id in games.distinct()) {
            val game = ctx.data.games.get(id) ?: continue
            val root = sources[game.location.sourceId]?.path?.let(FsPath::normalize)
            val set = runCatching { GameFiles.resolve(ctx.services.fs, game.location, game.discs) }.getOrNull()
            // Only files inside the game's own library folder, and never the folder itself.
            val safe = root != null && set != null && set.paths.all { inside(it, root) }
            if (!safe) {
                failed += game.displayTitle
                continue
            }
            val ok = set!!.paths.all { ctx.services.fs.delete(it) }
            if (ok) {
                gone += id
                freed += set.sizeBytes
                ctx.data.games.forgetDeleted(id, ctx.now())
            } else {
                failed += game.displayTitle
            }
        }
        // The list follows at once, then the drives are measured again.
        state.value = state.value?.let { u -> u.copy(games = u.games.filter { it.card.id !in gone }) }
        if (gone.isNotEmpty()) refresh()
        return DeleteReport(gone.size, freed, failed)
    }

    private fun inside(path: String, root: String): Boolean {
        val p = FsPath.normalize(path)
        return p != root && p.startsWith(root.trimEnd('/') + "/")
    }

    /** A drive with the library folders on it. */
    private data class Volume(val label: String, val total: Long, val free: Long, val sources: Set<LibrarySourceId>)

    /** Library folders on the same drive (same size and free space) count once. */
    private fun volumesOf(sources: List<LibrarySource>): List<Volume> =
        sources.mapNotNull { s -> volumeSpace(s.path)?.let { (free, total) -> Triple(s, free, total) } }
            .groupBy { (_, free, total) -> free to total }
            .map { (space, members) ->
                Volume(
                    label = members.joinToString(", ") { it.first.label },
                    total = space.second,
                    free = space.first,
                    sources = members.map { it.first.id }.toSet(),
                )
            }

    private fun Volume.withGames(games: List<GameSize>): VolumeUsage {
        val bySystem = games.groupBy { it.card.platformId }.map { (platform, list) ->
            SystemShare(platform, ctx.platformName(platform), list.first().card.accent, list.sumOf { it.bytes })
        }.sortedByDescending { it.bytes }
        return VolumeUsage(label, total, free, games.sumOf { it.bytes }, bySystem)
    }
}
