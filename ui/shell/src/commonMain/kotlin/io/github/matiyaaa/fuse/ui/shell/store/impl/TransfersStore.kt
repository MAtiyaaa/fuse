package io.github.matiyaaa.fuse.ui.shell.store.impl

import io.github.matiyaaa.fuse.data.settings.DownloadSettings
import io.github.matiyaaa.fuse.model.CartridgeStatus
import io.github.matiyaaa.fuse.model.QueueState
import io.github.matiyaaa.fuse.model.UploadState
import io.github.matiyaaa.fuse.transfer.SpeedMeter
import io.github.matiyaaa.fuse.transfer.TransferArt
import io.github.matiyaaa.fuse.transfer.TransferConditions
import io.github.matiyaaa.fuse.transfer.TransferDirection
import io.github.matiyaaa.fuse.transfer.TransferItem
import io.github.matiyaaa.fuse.transfer.TransferKind
import io.github.matiyaaa.fuse.transfer.TransferLive
import io.github.matiyaaa.fuse.transfer.TransferPhase
import io.github.matiyaaa.fuse.transfer.TransferScheduler
import io.github.matiyaaa.fuse.transfer.TransferSettings
import io.github.matiyaaa.fuse.transfer.TransferStatus
import io.github.matiyaaa.fuse.transfer.TransferSummary
import io.github.matiyaaa.fuse.transfer.Transfers
import io.github.matiyaaa.fuse.transfer.WhilePlaying
import io.github.matiyaaa.fuse.ui.shell.store.AppStoreOps
import io.github.matiyaaa.fuse.ui.shell.store.CartridgeOps
import io.github.matiyaaa.fuse.ui.shell.store.StoreJob
import io.github.matiyaaa.fuse.ui.shell.store.StoreState
import io.github.matiyaaa.fuse.ui.shell.store.TransferAction
import io.github.matiyaaa.fuse.ui.shell.store.TransferRow
import io.github.matiyaaa.fuse.ui.shell.store.TransfersOps
import io.github.matiyaaa.fuse.ui.shell.store.UpdateOps
import io.github.matiyaaa.fuse.ui.shell.store.UpdateState
import io.github.matiyaaa.fuse.ui.shell.store.transferOrder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Downloads: Fuse's own queue ([engine]) with what other parts of Fuse and Cartridge are moving
 * mirrored beside it. The Store keeps its own installs (Android's installer drives them) and Cartridge
 * its own downloads; their rows offer what those sources allow, and every byte count shows the same way.
 */
internal class DefaultTransfersOps(
    private val ctx: StoreContext,
    val engine: Transfers,
    private val appStore: AppStoreOps,
    private val updates: UpdateOps,
    private val cartridge: CartridgeOps,
) : TransfersOps {
    private val mirroredLive = HashMap<String, MutableStateFlow<TransferLive>>()
    private val meters = HashMap<String, SpeedMeter>()
    /** When a mirrored transfer was first seen, so it keeps its place in the list. */
    private val firstSeen = HashMap<String, Long>()

    private val mirrored: StateFlow<List<TransferRow>> = combine(appStore.state, updates.state, cartridge.status) { store, update, cart ->
        synchronized(mirroredLive) { storeRows(store) + updateRows(update) + cartridgeRows(cart) }
    }.distinctUntilChanged().stateIn(ctx.scope, SharingStarted.Eagerly, emptyList())

    override val rows: StateFlow<List<TransferRow>> = combine(engine.items, mirrored) { own, other ->
        transferOrder(own.map { TransferRow(it, actionsFor(it)) } + other)
    }.distinctUntilChanged().stateIn(ctx.scope, SharingStarted.Eagerly, emptyList())

    override val summary: StateFlow<TransferSummary> = combine(engine.summary, mirrored) { own, other ->
        val moving = other.filter { it.item.status == TransferStatus.ACTIVE }
        if (moving.isEmpty()) return@combine own
        val lives = moving.mapNotNull { synchronized(mirroredLive) { mirroredLive[it.item.id]?.value } }
        val known = lives.mapNotNull { l -> l.totalBytes?.let { l.doneBytes to it } }
        own.copy(
            activeDownloads = own.activeDownloads + moving.count { !it.item.upload },
            activeUploads = own.activeUploads + moving.count { it.item.upload },
            progress = if (known.isEmpty()) own.progress else {
                val ownPart = own.progress
                val done = known.sumOf { it.first }.toFloat()
                val total = known.sumOf { it.second }.toFloat()
                val theirs = if (total > 0) done / total else null
                if (ownPart == null) theirs else theirs?.let { (it + ownPart) / 2f } ?: ownPart
            },
        )
    }.stateIn(ctx.scope, SharingStarted.Eagerly, TransferSummary())

    override val settings: StateFlow<TransferSettings> get() = engine.settings

    fun start() {
        engine.start()
        // The person's settings, with a sensible start for this device until they choose.
        ctx.scope.launch {
            ctx.settings.map { it.downloads }.distinctUntilChanged().collect { engine.configure(it.toTransferSettings()) }
        }
        // Long transfers keep Fuse running (Android) while they move.
        ctx.scope.launch {
            engine.summary.map { s -> if (s.active > 0) keepAliveText(s) to s.progress?.let { (it * 100).toInt() } else null }
                .distinctUntilChanged().collect { now ->
                    if (now == null) ctx.services.keepAliveForTransfers(false, "", null)
                    else ctx.services.keepAliveForTransfers(true, now.first, now.second)
                }
        }
        // Wi-Fi comes and goes without telling: transfers kept to Wi-Fi look every little while.
        ctx.scope.launch {
            while (true) {
                kotlinx.coroutines.delay(15_000)
                val unmetered = ctx.services.unmetered()
                if (unmetered != lastUnmetered) conditions(lastPlaying)
            }
        }
        // Drives come and go: transfers waiting for one look again at once.
        ctx.scope.launch { ctx.offline.collect { engine.drivesChanged() } }
    }

    /** A game started or stopped, or the connection changed: transfers follow the person's rules. */
    @kotlin.concurrent.Volatile private var lastPlaying = false
    @kotlin.concurrent.Volatile private var lastUnmetered = true

    fun conditions(playing: Boolean) {
        lastPlaying = playing
        lastUnmetered = ctx.services.unmetered()
        engine.conditions(TransferConditions(playing = playing, unmetered = lastUnmetered))
    }

    override fun live(id: String): StateFlow<TransferLive> = synchronized(mirroredLive) { mirroredLive[id] } ?: engine.live(id)

    override fun act(id: String, action: TransferAction) {
        val row = rows.value.firstOrNull { it.item.id == id } ?: return
        when (row.item.source) {
            STORE -> {
                val key = id.removePrefix("$STORE:")
                when (action) {
                    TransferAction.CANCEL, TransferAction.REMOVE -> appStore.cancel(key)
                    TransferAction.RETRY -> appStore.install(key)
                    else -> Unit
                }
            }
            FUSE -> if (action == TransferAction.RETRY) {
                (updates.state.value as? UpdateState.Failed)?.let { updates.download(it.release) }
            }
            CARTRIDGE -> if (action == TransferAction.OPEN) cartridge.open(io.github.matiyaaa.fuse.model.CartridgeRoute.Downloads)
            else -> when (action) {
                TransferAction.PAUSE -> engine.pause(id)
                TransferAction.RESUME -> engine.resume(id)
                TransferAction.RETRY -> engine.retry(id)
                TransferAction.CANCEL -> engine.cancel(id)
                TransferAction.MOVE_UP -> engine.moveUp(id)
                TransferAction.MOVE_DOWN -> engine.moveDown(id)
                TransferAction.MOVE_TO_TOP -> engine.moveToTop(id)
                TransferAction.REMOVE -> engine.remove(id)
                TransferAction.OPEN -> Unit
            }
        }
    }

    override fun pauseAll() {
        engine.pauseAll()
    }

    override fun resumeAll() {
        engine.resumeAll()
    }

    override fun clearFinished() {
        engine.clearFinished()
    }

    private fun actionsFor(t: TransferItem): List<TransferAction> = when (t.status) {
        TransferStatus.ACTIVE, TransferStatus.WAITING -> listOf(TransferAction.PAUSE, TransferAction.CANCEL, TransferAction.MOVE_TO_TOP)
        TransferStatus.QUEUED -> listOf(TransferAction.PAUSE, TransferAction.MOVE_UP, TransferAction.MOVE_DOWN, TransferAction.MOVE_TO_TOP, TransferAction.CANCEL)
        TransferStatus.PAUSED -> listOf(TransferAction.RESUME, TransferAction.MOVE_TO_TOP, TransferAction.CANCEL)
        TransferStatus.FAILED -> listOfNotNull(TransferAction.RETRY, TransferAction.CANCEL, TransferAction.REMOVE)
        TransferStatus.DONE, TransferStatus.CANCELLED -> listOf(TransferAction.REMOVE)
    }

    // ------------------------------------------------------------------ mirrored sources

    private fun liveFor(id: String, done: Long, total: Long?): MutableStateFlow<TransferLive> {
        val now = ctx.now()
        val speed = meters.getOrPut(id) { SpeedMeter() }.sample(done, now)
        val flow = mirroredLive.getOrPut(id) { MutableStateFlow(TransferLive()) }
        flow.value = TransferLive(done, total, speed, total?.let { SpeedMeter.eta(it - done, speed) })
        return flow
    }

    private fun mirroredItem(
        id: String,
        source: String,
        kind: TransferKind,
        title: String,
        status: TransferStatus,
        done: Long = 0,
        total: Long? = null,
        direction: TransferDirection = TransferDirection.DOWNLOAD,
        detail: String = "",
        platform: String? = null,
        art: TransferArt = TransferArt(),
        target: String = "",
        error: String? = null,
        phase: TransferPhase? = null,
    ): TransferItem {
        val seen = firstSeen.getOrPut(id) { ctx.now() }
        liveFor(id, done, total)
        return TransferItem(
            id = id, key = id, source = source, direction = direction, kind = kind, title = title, detail = detail, platform = platform, art = art,
            target = target, totalBytes = total, doneBytes = done, status = status, error = error, phase = phase,
            order = Long.MAX_VALUE / 2 + seen, createdAt = seen, finishedAt = if (status.finished) seen else null,
        )
    }

    private fun storeRows(s: StoreState): List<TransferRow> = s.jobs.mapNotNull { (key, job) ->
        if (job is StoreJob.Uninstalling) return@mapNotNull null
        val app = s.app(key)
        val name = app?.name ?: key
        val (status, phase) = when (job) {
            StoreJob.Waiting -> TransferStatus.QUEUED to null
            StoreJob.Resolving -> TransferStatus.ACTIVE to TransferPhase.STARTING
            is StoreJob.Downloading -> TransferStatus.ACTIVE to TransferPhase.TRANSFERRING
            StoreJob.Verifying -> TransferStatus.ACTIVE to TransferPhase.VERIFYING
            StoreJob.NeedsPermission -> TransferStatus.WAITING to null
            is StoreJob.Installing -> TransferStatus.ACTIVE to TransferPhase.FINISHING
            is StoreJob.Failed -> TransferStatus.FAILED to null
            StoreJob.Uninstalling -> return@mapNotNull null
        }
        val dl = job as? StoreJob.Downloading
        val item = mirroredItem(
            "$STORE:$key", STORE, if (s.desktop) TransferKind.EMULATOR else TransferKind.APP, name, status,
            done = dl?.bytes ?: 0, total = dl?.total, detail = app?.categories?.firstOrNull().orEmpty(),
            art = TransferArt(icon = s.icons[key]), target = if (s.desktop) s.folder ?: "This computer" else "This device",
            error = (job as? StoreJob.Failed)?.message, phase = phase,
        )
        TransferRow(
            item,
            when (job) {
                is StoreJob.Failed -> listOfNotNull(TransferAction.RETRY.takeIf { job.retry }, TransferAction.REMOVE)
                is StoreJob.Installing -> emptyList()
                else -> listOf(TransferAction.CANCEL)
            },
            mirrored = "Store",
        )
    }

    private fun updateRows(u: UpdateState): List<TransferRow> = when (u) {
        is UpdateState.Downloading -> listOf(
            TransferRow(
                mirroredItem("$FUSE:update", FUSE, TransferKind.FUSE_UPDATE, u.release.name, TransferStatus.ACTIVE,
                    done = ((u.progress ?: 0f) * 1000).toLong(), total = if (u.progress != null) 1000 else null, detail = u.release.tag, target = "This device", phase = TransferPhase.TRANSFERRING),
                emptyList(), mirrored = "Fuse",
            ),
        )
        is UpdateState.Failed -> listOf(
            TransferRow(mirroredItem("$FUSE:update", FUSE, TransferKind.FUSE_UPDATE, u.release.name, TransferStatus.FAILED, detail = u.release.tag, error = u.message), listOf(TransferAction.RETRY), mirrored = "Fuse"),
        )
        else -> emptyList()
    }

    private fun cartridgeRows(c: CartridgeStatus): List<TransferRow> {
        if (!c.installed || !c.bridge) return emptyList()
        val downloads = c.queue.map { q ->
            val status = when (q.state) {
                QueueState.DOWNLOADING -> TransferStatus.ACTIVE
                QueueState.QUEUED -> TransferStatus.QUEUED
                QueueState.PAUSED -> TransferStatus.PAUSED
                QueueState.FAILED -> TransferStatus.FAILED
            }
            TransferRow(
                mirroredItem("$CARTRIDGE:${q.romId}", CARTRIDGE, TransferKind.GAME, q.title, status, done = q.received, total = q.total,
                    platform = ctx.platforms.resolveFolder(q.platformSlug)?.id?.value, target = "Cartridge", phase = TransferPhase.TRANSFERRING.takeIf { status == TransferStatus.ACTIVE }),
                listOf(TransferAction.OPEN), mirrored = "Cartridge",
            )
        }
        val uploads = c.uploads.filter { it.active || it.state == UploadState.FAILED }.map { u ->
            val status = when (u.state) {
                UploadState.WAITING -> TransferStatus.QUEUED
                UploadState.UPLOADING, UploadState.SCANNING -> TransferStatus.ACTIVE
                UploadState.FAILED -> TransferStatus.FAILED
                UploadState.DONE -> TransferStatus.DONE
                UploadState.CANCELLED -> TransferStatus.CANCELLED
            }
            TransferRow(
                mirroredItem("$CARTRIDGE-up:${u.id}", CARTRIDGE, TransferKind.GAME, u.title, status, done = u.sent, total = u.total, direction = TransferDirection.UPLOAD,
                    platform = ctx.platforms.resolveFolder(u.platformSlug)?.id?.value, target = "RomM, through Cartridge", error = u.error,
                    phase = if (u.state == UploadState.SCANNING) TransferPhase.FINISHING else null),
                listOf(TransferAction.OPEN), mirrored = "Cartridge",
            )
        }
        return downloads + uploads
    }

    companion object {
        const val STORE = "store"
        const val FUSE = "fuse"
        const val CARTRIDGE = "cartridge"

        /** Every summary row the Downloads button turns into counts. */
        fun summaryOf(rows: List<TransferRow>, live: (String) -> TransferLive?): TransferSummary = TransferScheduler.summary(rows.map { it.item }, live)
    }
}

/** The person's Downloads settings as the queue reads them. */
internal fun DownloadSettings.toTransferSettings(cores: Int = defaultCores(), lowMemory: Boolean = false): TransferSettings {
    val auto = TransferSettings.defaults(cores, lowMemory)
    fun mode(s: String) = runCatching { WhilePlaying.valueOf(s) }.getOrDefault(WhilePlaying.REDUCED)
    return TransferSettings(
        maxDownloads = if (chosen) maxDownloads.coerceIn(1, TransferSettings.MAX_PARALLEL) else auto.maxDownloads,
        maxUploads = if (chosen) maxUploads.coerceIn(1, TransferSettings.MAX_PARALLEL) else auto.maxUploads,
        bandwidthLimit = bandwidthKbps.coerceAtLeast(0) * 1024L,
        downloadsWhilePlaying = mode(downloadsWhilePlaying),
        uploadsWhilePlaying = mode(uploadsWhilePlaying),
        wifiOnly = wifiOnly,
        resumeOnStart = resumeOnStart,
        keepFinishedDays = keepFinishedDays.coerceIn(0, 90),
    )
}

internal expect fun defaultCores(): Int

/** The notification's line while transfers move: what, how many, in which direction. */
internal fun keepAliveText(s: TransferSummary): String = listOfNotNull(
    when (s.activeDownloads) { 0 -> null; 1 -> "Downloading 1 item"; else -> "Downloading ${s.activeDownloads} items" },
    when (s.activeUploads) { 0 -> null; 1 -> "uploading 1"; else -> "uploading ${s.activeUploads}" },
    if (s.queued > 0) "${s.queued} queued" else null,
).joinToString(", ").replaceFirstChar { it.uppercase() }
