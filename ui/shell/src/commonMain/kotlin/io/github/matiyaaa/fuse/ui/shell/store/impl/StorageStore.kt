package io.github.matiyaaa.fuse.ui.shell.store.impl

import io.github.matiyaaa.fuse.library.FsPath
import io.github.matiyaaa.fuse.library.storage.GameFiles
import io.github.matiyaaa.fuse.library.storage.Volumes
import io.github.matiyaaa.fuse.model.GameId
import io.github.matiyaaa.fuse.model.LibrarySource
import io.github.matiyaaa.fuse.model.LibrarySourceId
import io.github.matiyaaa.fuse.model.SourceState
import io.github.matiyaaa.fuse.model.SourceStatus
import io.github.matiyaaa.fuse.model.StorageVolume
import io.github.matiyaaa.fuse.model.VolumeKind
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

internal class DefaultStorageOps(private val ctx: StoreContext, private val drives: Drives) : StorageOps {
    private val state = MutableStateFlow<StorageUsage?>(null)
    override val usage: StateFlow<StorageUsage?> = state
    private var job: Job? = null

    override fun refresh() {
        job?.cancel()
        job = ctx.scope.launch {
            drives.refresh()
            val statuses = drives.status.value
            val mounted = drives.volumes.value
            val sources = ctx.data.sources.all().filter { it.enabled }
            // Android games are apps: Android's own storage settings measure them.
            val summaries = ctx.data.games.observeAll().first().filterNot { it.isApp }
            val cards = ctx.cardsOnce(summaries).associateBy { it.id }
            val driveOf = statuses.associate { it.source.id to (it.volume?.id ?: it.source.volume?.id) }
            val sizes = ArrayList<GameSize>()
            val sourceOf = HashMap<GameId, LibrarySourceId>()
            val legacy = if (mounted.isEmpty()) volumesOf(sources) else emptyList()
            fun publish(finished: Boolean) {
                state.value = StorageUsage(
                    volumes = if (mounted.isEmpty()) {
                        legacy.map { v -> v.withGames(sizes.filter { sourceOf[it.card.id] in v.sources }) }
                    } else {
                        driveUsage(mounted, statuses, sizes)
                    },
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
                sourceOf[game.id] = game.location.sourceId
                val drive = driveOf[game.location.sourceId] ?: Volumes.locate(game.location.path, mounted)?.first?.id
                if (card.unavailable != null) {
                    // Its drive is out: what the last scan saw, without touching the missing files.
                    sizes += GameSize(card, game.location.sizeBytes, maxOf(1, game.discs.size), drive, lastKnown = true)
                    continue
                }
                val set = try {
                    GameFiles.resolve(ctx.services.fs, game.location, game.discs)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    null
                } ?: continue
                sizes += GameSize(card, set.sizeBytes, set.fileCount, drive)
                if (i % 25 == 24) publish(finished = false)
            }
            publish(finished = true)
        }
    }

    /**
     * Every drive that holds a library folder or a measured game: connected ones with their real
     * size and free space, then the ones that are out, as they were last seen.
     */
    private fun driveUsage(mounted: List<StorageVolume>, statuses: List<SourceStatus>, sizes: List<GameSize>): List<VolumeUsage> {
        val ids = LinkedHashSet<String>()
        statuses.filter { it.source.enabled }.forEach { s -> (s.volume?.id ?: s.source.volume?.id)?.let(ids::add) }
        sizes.mapNotNullTo(ids) { it.volumeId }
        val byDrive = sizes.groupBy { it.volumeId }
        val out = ids.mapNotNull { id ->
            val games = byDrive[id].orEmpty()
            val volume = mounted.firstOrNull { it.id == id }
            if (volume != null) {
                val (free, total) = if (volume.totalBytes > 0) volume.freeBytes to volume.totalBytes else volumeSpace(volume.mountPath) ?: (0L to 0L)
                VolumeUsage(
                    label = volume.label, totalBytes = total, freeBytes = free, gamesBytes = games.sumOf { it.bytes },
                    systems = sharesOf(games), id = id, kind = volume.kind, removable = volume.removable, online = true,
                    games = games.size, readOnly = volume.readOnly, mountPath = volume.mountPath, fsType = volume.fsType,
                )
            } else {
                val ref = statuses.firstOrNull { it.source.volume?.id == id }?.source?.volume ?: return@mapNotNull null
                VolumeUsage(
                    label = ref.label, totalBytes = 0, freeBytes = 0, gamesBytes = games.sumOf { it.bytes },
                    systems = sharesOf(games), id = id, kind = ref.kind, removable = ref.removable, online = false,
                    lastSeenAt = ref.lastSeenAt, games = games.size,
                )
            }
        }
        // The device's own storage first, other connected drives by name, drives that are out last.
        return out.sortedWith(compareBy<VolumeUsage>({ !it.online }, { it.kind != VolumeKind.INTERNAL }, { it.label.lowercase() }))
    }

    private fun sharesOf(games: List<GameSize>): List<SystemShare> =
        games.groupBy { it.card.platformId }.map { (platform, list) ->
            SystemShare(platform, ctx.platformName(platform), list.first().card.accent, list.sumOf { it.bytes })
        }.sortedByDescending { it.bytes }

    override suspend fun size(game: GameId): Long? {
        val g = ctx.data.games.get(game) ?: return null
        return runCatching { GameFiles.resolve(ctx.services.fs, g.location, g.discs) }.getOrNull()?.takeIf { !it.isEmpty }?.sizeBytes
    }

    override suspend fun delete(games: List<GameId>): DeleteReport {
        val sources = ctx.data.sources.all().associateBy { it.id }
        var freed = 0L
        val failed = ArrayList<String>()
        val gone = HashSet<GameId>()
        // Files are only deleted where Fuse is sure which drive it is looking at.
        drives.refresh()
        val readable = drives.status.value.filter { it.state == SourceState.ONLINE }.map { it.source.id }.toSet()
        for (id in games.distinct()) {
            val game = ctx.data.games.get(id) ?: continue
            if (game.location.sourceId !in readable) {
                failed += game.displayTitle
                continue
            }
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

    /**
     * Where the system names no drives (tests, unusual hosts): library folders on the same drive
     * (same size and free space) count once.
     */
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

    private fun Volume.withGames(games: List<GameSize>): VolumeUsage =
        VolumeUsage(label, total, free, games.sumOf { it.bytes }, sharesOf(games), games = games.size)
}
