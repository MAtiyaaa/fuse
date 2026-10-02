package io.github.matiyaaa.fuse.ui.shell.store.impl

import io.github.matiyaaa.fuse.library.FsAccessException
import io.github.matiyaaa.fuse.library.FsPath
import io.github.matiyaaa.fuse.library.storage.Volumes
import io.github.matiyaaa.fuse.model.LibrarySource
import io.github.matiyaaa.fuse.model.LibrarySourceId
import io.github.matiyaaa.fuse.model.SourceState
import io.github.matiyaaa.fuse.model.SourceStatus
import io.github.matiyaaa.fuse.model.StorageVolume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A library folder that can't be read right now, and why: its games are shown as offline, never as
 * deleted, and launching one says which drive to connect.
 */
internal data class OfflineRoot(val path: String, val driveLabel: String, val state: SourceState, val lastSeenAt: Long?) {
    fun holds(folderPath: String): Boolean = Volumes.relativeTo(FsPath.normalize(folderPath), path) != null
}

/**
 * Ties library folders to the drives they live on ([Volumes] holds the rules). Each [refresh] reads
 * the mounted drives, follows a drive that came back under another path (moving every stored path of
 * that folder in one transaction), remembers which drive each readable folder is on, and publishes
 * which folders a scan may look at.
 */
internal class Drives(private val ctx: StoreContext) {
    private val volumeState = MutableStateFlow<List<StorageVolume>>(emptyList())
    val volumes: StateFlow<List<StorageVolume>> = volumeState
    private val statusState = MutableStateFlow<List<SourceStatus>>(emptyList())
    val status: StateFlow<List<SourceStatus>> = statusState
    private val lock = Mutex()

    /** Re-reads drives and folders. Returns the folders that were unreadable and are back. */
    suspend fun refresh(): Set<LibrarySourceId> = lock.withLock {
        val mounted = try {
            ctx.services.volumes.volumes()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
        volumeState.value = mounted
        val before = statusState.value.associate { it.source.id to it.state }
        val now = ctx.now()
        val out = ArrayList<SourceStatus>()
        for (source in ctx.data.sources.all()) {
            var status = evaluate(source, mounted)
            if (status.state == SourceState.MOVED && status.relinkTo != null && status.volume != null) {
                val target = status.relinkTo!!
                val relative = Volumes.relativeTo(target, FsPath.normalize(status.volume!!.mountPath)).orEmpty()
                val ref = Volumes.refFor(source, status.volume!!, relative, now)
                status = if (ctx.data.sources.relink(source.id, source.path, target, ref)) {
                    val moved = source.copy(path = target, volume = ref)
                    evaluate(moved, mounted)
                } else {
                    // Games already exist at the new path (it was added on its own): the user decides.
                    status
                }
            }
            if (status.state == SourceState.ONLINE) status = remember(status, now)
            out += status
        }
        statusState.value = out
        ctx.offline.value = out.filter { it.state in OFFLINE_STATES }.map {
            OfflineRoot(FsPath.normalize(it.source.path), it.driveLabel, it.state, it.source.volume?.lastSeenAt)
        }
        out.filter { it.state == SourceState.ONLINE && before[it.source.id].let { b -> b != null && b != SourceState.ONLINE } }
            .map { it.source.id }.toSet()
    }

    /** Status of [source] on [mounted] drives. Without any drive information the folder is just looked at. */
    private suspend fun evaluate(source: LibrarySource, mounted: List<StorageVolume>): SourceStatus {
        val fs = ctx.services.fs
        val subject = if (mounted.isEmpty()) source.copy(volume = null) else source
        return Volumes.evaluate(
            subject,
            mounted,
            exists = { path -> runCatching { fs.stat(path)?.isDirectory == true }.getOrDefault(false) },
            readable = { path ->
                try {
                    fs.list(path)
                    true
                } catch (e: CancellationException) {
                    throw e
                } catch (e: FsAccessException) {
                    false
                } catch (e: Exception) {
                    false
                }
            },
        ).copy(source = source)
    }

    /** Keeps the drive a readable folder is on, written only when something changed or it's been a while. */
    private suspend fun remember(status: SourceStatus, now: Long): SourceStatus {
        val volume = status.volume ?: return status
        val source = status.source
        val relative = Volumes.relativeTo(FsPath.normalize(source.path), FsPath.normalize(volume.mountPath)) ?: return status
        val old = source.volume
        val fresh = Volumes.refFor(source, volume, relative, now)
        val stale = old == null || old.id != fresh.id || old.label != fresh.label || old.relativePath != fresh.relativePath ||
            old.kind != fresh.kind || old.removable != fresh.removable || (now - (old.lastSeenAt ?: 0)) > SEEN_EVERY_MS
        if (!stale) return status
        ctx.data.sources.setVolume(source.id, fresh)
        return status.copy(source = source.copy(volume = fresh))
    }

    /** The state of the folder holding [folderPath] when it can't be read now, else null. */
    fun offlineFor(folderPath: String): OfflineRoot? = ctx.offline.value.firstOrNull { it.holds(folderPath) }

    companion object {
        /** States whose games are shown as unavailable instead of being scanned. */
        val OFFLINE_STATES = setOf(SourceState.OFFLINE, SourceState.OTHER_DRIVE, SourceState.NO_ACCESS, SourceState.FOLDER_MISSING, SourceState.MOVED)

        /** How often a drive's "last seen" time is written while it stays connected. */
        const val SEEN_EVERY_MS = 10 * 60 * 1000L
    }
}
