package io.github.matiyaaa.fuse.ui.shell.store.impl

import io.github.matiyaaa.fuse.data.db.Offline_media
import io.github.matiyaaa.fuse.jellyfin.Account
import io.github.matiyaaa.fuse.jellyfin.JELLYFIN_SOURCE
import io.github.matiyaaa.fuse.jellyfin.JellyfinDownloadJob
import io.github.matiyaaa.fuse.jellyfin.JellyfinService
import io.github.matiyaaa.fuse.jellyfin.MediaItem
import io.github.matiyaaa.fuse.jellyfin.MediaType
import io.github.matiyaaa.fuse.jellyfin.OFFLINE_MOVE_SOURCE
import io.github.matiyaaa.fuse.jellyfin.OfflineEntry
import io.github.matiyaaa.fuse.jellyfin.OfflineHost
import io.github.matiyaaa.fuse.jellyfin.OfflineMeta
import io.github.matiyaaa.fuse.jellyfin.OfflineMoveJob
import io.github.matiyaaa.fuse.jellyfin.OfflinePlan
import io.github.matiyaaa.fuse.jellyfin.OfflineResolver
import io.github.matiyaaa.fuse.jellyfin.decodeOfflineMeta
import io.github.matiyaaa.fuse.jellyfin.encode
import io.github.matiyaaa.fuse.jellyfin.offlineHandlers
import io.github.matiyaaa.fuse.library.FsPath
import io.github.matiyaaa.fuse.model.VolumeRef
import io.github.matiyaaa.fuse.playback.PlaybackResolver
import io.github.matiyaaa.fuse.transfer.TransferArt
import io.github.matiyaaa.fuse.transfer.TransferDirection
import io.github.matiyaaa.fuse.transfer.TransferItem
import io.github.matiyaaa.fuse.transfer.TransferKind
import io.github.matiyaaa.fuse.transfer.TransferPlace
import io.github.matiyaaa.fuse.transfer.TransferPlaces
import io.github.matiyaaa.fuse.transfer.TransferStatus
import io.github.matiyaaa.fuse.transfer.Transfers
import io.github.matiyaaa.fuse.ui.shell.store.OfflineMediaOps
import io.github.matiyaaa.fuse.ui.shell.store.OfflinePlanView
import io.github.matiyaaa.fuse.ui.shell.store.OfflineState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * Jellyfin films and episodes kept on this device (see [OfflineMediaOps]). Each is one row in
 * Fuse's database, keyed by server and item, with the drive it is on remembered beside its path, so
 * a card that moves from E: to F: is followed and one that is out is shown as away, never dropped.
 */
internal class DefaultOfflineMedia(
    private val ctx: StoreContext,
    private val engine: LibraryEngine,
    private val transfers: Transfers,
    private val service: JellyfinService,
) : OfflineMediaOps {
    override val supported = true
    private val q get() = ctx.data.database.offlineMediaQueries
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val lock = Mutex()
    private val entriesFlow = MutableStateFlow<List<OfflineEntry>>(emptyList())
    override val entries: StateFlow<List<OfflineEntry>> = entriesFlow

    /** Short messages for the person: a film ready to watch offline, a move done. */
    val notices = MutableSharedFlow<String>(extraBufferCapacity = 4)

    override val folder: StateFlow<String> = ctx.settings.map { it.jellyfin.offlineFolder.ifBlank { ctx.services.mediaDir } }
        .distinctUntilChanged().stateIn(ctx.scope, SharingStarted.Eagerly, ctx.settings.value.jellyfin.offlineFolder.ifBlank { ctx.services.mediaDir })

    private val host = object : OfflineHost {
        override suspend fun session(server: String): Pair<String, Account> {
            val s = service.session()
            if (server != "main" && s.second.serverId != null && s.second.serverId != server) {
                throw io.github.matiyaaa.fuse.jellyfin.JellyfinException("Fuse is signed in to another Jellyfin server now.", io.github.matiyaaa.fuse.jellyfin.JellyfinException.Kind.AUTH)
            }
            return s
        }

        override fun authorization(token: String): String = service.client.authorization(token)

        override suspend fun landed(job: JellyfinDownloadJob, item: TransferItem, video: String, meta: OfflineMeta) {
            record(job.server, video, meta)
            notices.tryEmit("${title(meta)} is ready to watch offline")
        }

        override suspend fun moved(job: OfflineMoveJob, folder: String, place: TransferPlace) {
            val row = withContext(Dispatchers.Default) { q.byKey(job.key).executeAsOneOrNull() } ?: return
            val video = FsPath.join(folder, FsPath.name(row.path))
            val volume = TransferPlaces.of(video, engine.drives.volumes.value, ctx.now()).volume
            withContext(Dispatchers.Default) {
                q.moved(video, volume?.let { json.encodeToString(VolumeRef.serializer(), it) }, row.subtitle_path?.let { FsPath.join(folder, FsPath.name(it)) }, job.key)
            }
            reload()
        }
    }

    fun start() {
        offlineHandlers(ctx.services.http, host).forEach(transfers::register)
        ctx.scope.launch { reload() }
        // Drives come and go: what is here follows them.
        ctx.scope.launch { engine.drives.volumes.collect { reload() } }
        // Back online: where things were left offline goes to the server.
        ctx.scope.launch {
            service.state.map { it.base != null && !it.offline && it.account != null }.distinctUntilChanged().collect { online -> if (online) sendUnsent() }
        }
    }

    override fun refresh() {
        ctx.scope.launch { reload() }
    }

    private suspend fun reload() = lock.withLock {
        val volumes = engine.drives.volumes.value
        val rows = withContext(Dispatchers.Default) { q.all().executeAsList() }
        entriesFlow.value = rows.mapNotNull { r -> entryOf(r, volumes) }
    }

    private suspend fun entryOf(r: Offline_media, volumes: List<io.github.matiyaaa.fuse.model.StorageVolume>): OfflineEntry? {
        val meta = decodeOfflineMeta(r.meta_json) ?: return null
        val volume = r.volume_json?.let { runCatching { json.decodeFromString(VolumeRef.serializer(), it) }.getOrNull() }
        val now = TransferPlaces.resolve(TransferPlace(r.path, volume), volumes)
        // Its drive is here but the file isn't: deleted from outside Fuse. Shown as gone, not as here.
        val present = now?.let { p -> runCatching { ctx.services.fs.stat(p) }.getOrNull()?.let { !it.isDirectory } } == true
        return OfflineEntry(
            key = r.key, server = r.server, itemId = r.item_id,
            path = now.takeIf { present }, lastPath = r.path,
            driveLabel = volume?.label, meta = meta, addedAt = r.added_at,
        )
    }

    override fun state(itemId: String): Flow<OfflineState> = combine(entriesFlow, transfers.items) { list, items ->
        val e = list.firstOrNull { it.itemId == itemId }
        val t = items.lastOrNull { it.source == JELLYFIN_SOURCE && it.key.endsWith(":$itemId") && it.status != TransferStatus.CANCELLED }
        when {
            e != null && e.here -> OfflineState.Here(e)
            t != null && !t.status.finished -> OfflineState.Downloading(
                t.totalBytes?.takeIf { it > 0 }?.let { (t.doneBytes.toFloat() / it).coerceIn(0f, 1f) },
                waiting = t.status == TransferStatus.WAITING || t.status == TransferStatus.PAUSED,
            )
            t != null && t.status == TransferStatus.FAILED -> OfflineState.Failed(t.error ?: "The download failed")
            e != null -> OfflineState.Away(e)
            else -> OfflineState.None
        }
    }.distinctUntilChanged()

    override suspend fun entry(itemId: String): OfflineEntry? = entriesFlow.value.firstOrNull { it.itemId == itemId }

    override suspend fun plan(item: MediaItem): OfflinePlanView {
        val root = folder.value
        val items = try {
            when (item.type) {
                MediaType.MOVIE, MediaType.EPISODE, MediaType.VIDEO -> listOf(item)
                MediaType.SEASON -> service.episodes(item.seriesId ?: item.parentId ?: item.id, item.id)
                MediaType.SERIES -> service.episodes(item.id, null)
                else -> return OfflinePlanView(item.name, emptyList(), 0, 0, root, "Only films and episodes can be kept offline.")
            }
        } catch (e: Exception) {
            return OfflinePlanView(item.name, emptyList(), 0, 0, root, e.message ?: "Jellyfin can't be reached.")
        }
        if (service.downloadAllowed() == false) {
            return OfflinePlanView(item.name, emptyList(), 0, 0, root, "Your Jellyfin account isn't allowed to download. An administrator can turn on \"Allow media downloading\" for you in Jellyfin's Users settings.")
        }
        val kept = entriesFlow.value.map { it.itemId }.toSet()
        val going = transfers.items.value.filter { it.source == JELLYFIN_SOURCE && !it.status.finished }.map { it.key.substringAfterLast(':') }.toSet()
        val wanted = items.filter { it.id !in kept && it.id !in going }
        val title = when (item.type) {
            MediaType.SERIES -> item.name
            MediaType.SEASON -> listOfNotNull(item.seriesName, item.name).joinToString(", ")
            else -> if (item.type == MediaType.EPISODE) listOfNotNull(item.seriesName, item.episodeLabel).joinToString(" ") else item.name
        }
        // Sizes are asked for up to a season's worth; a whole show is estimated from those.
        val sized = wanted.take(SIZE_ASKS).map { runCatching { service.offlineJob(it, service.serverKey).meta.sizeBytes }.getOrDefault(0L) }
        val total = if (wanted.size <= SIZE_ASKS) sized.sum() else (sized.average() * wanted.size).toLong()
        return OfflinePlanView(title, wanted, items.size - wanted.size, total, root)
    }

    override suspend fun start(plan: OfflinePlanView): Int {
        var n = 0
        val volumes = engine.drives.volumes.value
        for (item in plan.items) {
            val job = runCatching { service.offlineJob(item, service.serverKey) }.getOrNull() ?: continue
            val dir = FsPath.join(plan.folder, OfflinePlan.folder(item))
            val place = TransferPlaces.of(dir, volumes, ctx.now())
            val m = job.meta
            transfers.enqueue(
                TransferItem(
                    id = "", key = "jellyfin:${job.server}:${item.id}", source = JELLYFIN_SOURCE, direction = TransferDirection.DOWNLOAD,
                    kind = TransferKind.MEDIA, title = title(m),
                    detail = if (m.isEpisode) listOfNotNull(m.episodeLabel, m.name).joinToString("  ·  ") else m.year?.toString().orEmpty(),
                    art = TransferArt(
                        logo = m.logoArt?.toArt()?.let { service.imageUrl(it.sized(600)) },
                        cover = (m.backdropArt ?: m.thumbArt ?: m.posterArt)?.toArt()?.let { service.imageUrl(it.sized(960)) },
                        icon = m.posterArt?.toArt()?.let { service.imageUrl(it.sized(300)) },
                    ),
                    place = place, target = "${place.volume?.label ?: "This device"}  ·  ${shortPath(dir)}",
                    totalBytes = m.sizeBytes.takeIf { it > 0 }, payload = job.encode(),
                ),
            )
            n++
        }
        return n
    }

    override suspend fun remove(keys: List<String>): Int {
        var n = 0
        for (key in keys) {
            val e = entriesFlow.value.firstOrNull { it.key == key } ?: continue
            if (e.here) {
                val fs = ctx.services.fs
                for (name in filesOf(e)) runCatching { fs.delete(e.file(name)) }
                // The folders Fuse made go when nothing else is in them (a season, then its show).
                var dir: String? = e.folder
                repeat(2) {
                    val d = dir ?: return@repeat
                    if (runCatching { fs.list(d) }.getOrNull()?.isEmpty() == true) runCatching { fs.delete(d) }
                    dir = FsPath.parent(d)
                }
            }
            withContext(Dispatchers.Default) { q.remove(key) }
            n++
        }
        reload()
        return n
    }

    override suspend fun move(keys: List<String>, folder: String): Int {
        var n = 0
        val volumes = engine.drives.volumes.value
        for (key in keys) {
            val e = entriesFlow.value.firstOrNull { it.key == key && it.here } ?: continue
            val to = FsPath.join(folder, OfflinePlan.folderOf(e.meta))
            if (FsPath.normalize(to) == FsPath.normalize(e.folder)) continue
            val toPlace = TransferPlaces.of(to, volumes, ctx.now())
            val fromPlace = TransferPlaces.of(e.folder, volumes, ctx.now())
            transfers.enqueue(
                TransferItem(
                    id = "", key = "offline-move:$key", source = OFFLINE_MOVE_SOURCE, direction = TransferDirection.DOWNLOAD,
                    kind = TransferKind.MEDIA, title = title(e.meta), detail = "Moving to ${toPlace.volume?.label ?: "this device"}",
                    place = toPlace, target = "${toPlace.volume?.label ?: "This device"}  ·  ${shortPath(to)}",
                    totalBytes = e.meta.sizeBytes.takeIf { it > 0 },
                    payload = OfflineMoveJob(key, fromPlace, filesOf(e)).encode(),
                ),
            )
            n++
        }
        return n
    }

    private var resolverMade: OfflineResolver? = null

    override fun resolver(): PlaybackResolver = resolverMade ?: OfflineResolver(
        find = { id -> entriesFlow.value.firstOrNull { it.itemId == id } },
        readText = { path -> ctx.services.fs.readText(path, 4 * 1024 * 1024) },
        saveResume = { e, pos, finished -> saveResume(e, pos, finished) },
        nextOf = { e, step ->
            val show = e.meta.seriesId ?: return@OfflineResolver null
            val list = entriesFlow.value.filter { it.meta.seriesId == show && it.here }
                .sortedWith(compareBy<OfflineEntry>({ it.meta.season ?: 0 }, { it.meta.episode ?: 0 }))
            val i = list.indexOfFirst { it.key == e.key }
            if (i < 0) null else list.getOrNull(i + step)
        },
    ).also { resolverMade = it }

    /** Where it was left, kept here at once and sent to the server when it can be reached. */
    private suspend fun saveResume(e: OfflineEntry, positionMs: Long, finished: Boolean) {
        val meta = e.meta.copy(unsentMs = if (finished) 0 else positionMs, played = e.meta.played || finished)
        withContext(Dispatchers.Default) {
            q.byKey(e.key).executeAsOneOrNull()?.let { r -> upsert(r.copy(meta_json = meta.encode())) }
        }
        reload()
        if (service.state.value.base != null && !service.state.value.offline) sendUnsent()
    }

    private suspend fun sendUnsent() {
        val pending = entriesFlow.value.filter { it.meta.unsentMs != null && it.server == service.serverKey }
        for (e in pending) {
            val pos = e.meta.unsentMs ?: continue
            val ok = runCatching {
                if (e.meta.played && pos == 0L) service.setPlayed(e.itemId, true) else service.reportPosition(e.itemId, pos)
            }.isSuccess
            if (!ok) return
            withContext(Dispatchers.Default) {
                q.byKey(e.key).executeAsOneOrNull()?.let { r -> upsert(r.copy(meta_json = e.meta.copy(unsentMs = null, resumeMs = pos).encode())) }
            }
        }
        if (pending.isNotEmpty()) reload()
    }

    private suspend fun record(server: String, video: String, meta: OfflineMeta) {
        val volume = TransferPlaces.of(video, engine.drives.volumes.value, ctx.now()).volume
        val key = "jellyfin:$server:${meta.itemId}"
        withContext(Dispatchers.Default) {
            upsert(
                Offline_media(
                    key = key, source = JELLYFIN_SOURCE, server = server, item_id = meta.itemId, kind = meta.type, title = meta.name,
                    series_name = meta.seriesName, series_id = meta.seriesId, season_id = meta.seasonId,
                    season = meta.season?.toLong(), episode = meta.episode?.toLong(), path = video,
                    volume_json = volume?.let { json.encodeToString(VolumeRef.serializer(), it) },
                    subtitle_path = meta.subtitles.firstOrNull { it.file.isNotEmpty() }?.let { FsPath.join(FsPath.parent(video) ?: "", it.file) },
                    subtitle_language = meta.subtitles.firstOrNull { it.file.isNotEmpty() }?.language,
                    size_bytes = meta.sizeBytes, runtime_ms = meta.runtimeMs, poster = meta.poster, meta_json = meta.encode(), added_at = ctx.now(),
                ),
            )
        }
        reload()
    }

    private fun upsert(r: Offline_media) = q.upsert(
        r.key, r.source, r.server, r.item_id, r.kind, r.title, r.series_name, r.series_id, r.season_id, r.season, r.episode,
        r.path, r.volume_json, r.subtitle_path, r.subtitle_language, r.size_bytes, r.runtime_ms, r.poster, r.meta_json, r.added_at,
    )

    /** Every file Fuse put down for [e]: the video, its subtitles and its pictures. */
    private fun filesOf(e: OfflineEntry): List<String> = buildList {
        add(FsPath.name(e.path ?: e.lastPath))
        e.meta.subtitles.filter { it.file.isNotEmpty() }.forEach { add(it.file) }
        listOfNotNull(e.meta.poster, e.meta.backdrop, e.meta.logo, e.meta.thumb).forEach { add(it) }
    }.distinct()

    private fun title(m: OfflineMeta) = if (m.isEpisode) m.seriesName ?: m.name else m.name

    private fun shortPath(path: String): String = FsPath.normalize(path).split('/').filter { it.isNotEmpty() }.takeLast(2).joinToString("/")

    private companion object {
        const val SIZE_ASKS = 24
    }
}
