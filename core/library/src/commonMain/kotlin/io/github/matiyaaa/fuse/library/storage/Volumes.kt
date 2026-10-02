package io.github.matiyaaa.fuse.library.storage

import io.github.matiyaaa.fuse.library.FsPath
import io.github.matiyaaa.fuse.model.LibrarySource
import io.github.matiyaaa.fuse.model.SourceState
import io.github.matiyaaa.fuse.model.SourceStatus
import io.github.matiyaaa.fuse.model.StorageVolume
import io.github.matiyaaa.fuse.model.VolumeRef

/**
 * Pure logic that ties library folders to the drives they live on. Kept free of any system call so
 * every rule is tested: the hosts only report which drives are mounted and whether a folder exists.
 *
 * The rule that matters most: a drive that isn't there is never mistaken for games that were
 * deleted. A folder whose drive is gone is [SourceState.OFFLINE] and is not scanned, so not one of
 * its games is marked missing.
 */
object Volumes {
    /** The drive [path] is on (the longest mount path containing it) and the path relative to it. */
    fun locate(path: String, volumes: List<StorageVolume>): Pair<StorageVolume, String>? {
        val clean = FsPath.normalize(path)
        var best: Pair<StorageVolume, String>? = null
        var bestLength = -1
        for (volume in volumes) {
            for (mount in volume.mountPaths) {
                val m = FsPath.normalize(mount)
                val relative = relativeTo(clean, m) ?: continue
                if (m.length > bestLength) {
                    best = volume to relative
                    bestLength = m.length
                }
            }
        }
        return best
    }

    /**
     * [path] relative to [root] ("" when they are the same), or null when [path] isn't inside it.
     * Windows drive letters compare without case.
     */
    fun relativeTo(path: String, root: String): String? {
        val windows = FsPath.drive(root) != null
        val p = if (windows) path.lowercase() else path
        val r = (if (windows) root.lowercase() else root).trimEnd('/')
        if (r.isEmpty()) return path.trimStart('/') // "/" contains everything.
        if (p == r) return ""
        if (windows && r.length == 2 && p.startsWith("$r/")) return path.substring(3)
        return if (p.startsWith("$r/")) path.substring(r.length + 1) else null
    }

    /** What [source] is when its drive is [volume]: the reference kept with the folder. */
    fun refFor(source: LibrarySource, volume: StorageVolume, relative: String, now: Long): VolumeRef =
        VolumeRef(volume.id, volume.label, volume.kind, volume.removable, relative, now)

    /**
     * Where [source] stands given the mounted [volumes]. [exists] says whether a path is a folder
     * right now, [readable] whether Fuse may list it.
     *
     * - Never seen on a drive: located now and checked, like before Fuse knew about drives.
     * - Its drive (by [VolumeRef.id]) is mounted and holds the path: online, or the folder is missing.
     * - Its drive is mounted under another path: [SourceState.MOVED], with where the folder is now.
     * - Its drive isn't mounted: [SourceState.OFFLINE], whatever is at the old path. On Linux an empty
     *   mount point folder stays behind when a drive is unplugged; listing it would look like every
     *   game was deleted.
     * - A drive with a weak id (one only as good as its path) never makes a readable folder offline,
     *   since the same drive can come back with a better id once the system offers one.
     */
    suspend fun evaluate(
        source: LibrarySource,
        volumes: List<StorageVolume>,
        exists: suspend (String) -> Boolean,
        readable: suspend (String) -> Boolean,
    ): SourceStatus {
        val path = FsPath.normalize(source.path)
        val ref = source.volume
        val here = locate(path, volumes)

        suspend fun atPath(volume: StorageVolume?): SourceStatus = when {
            !exists(path) -> SourceStatus(source, SourceState.FOLDER_MISSING, volume)
            !readable(path) -> SourceStatus(source, SourceState.NO_ACCESS, volume)
            else -> SourceStatus(source, SourceState.ONLINE, volume)
        }

        if (ref == null) return atPath(here?.first)

        val mine = volumes.firstOrNull { it.id == ref.id }
        if (mine != null) {
            // Its own drive: is the folder reachable at the remembered path?
            if (here != null && here.first.id == mine.id) return atPath(mine)
            val moved = FsPath.normalize(FsPath.join(mine.mountPath, ref.relativePath))
            return if (moved != path && exists(moved)) {
                SourceStatus(source, SourceState.MOVED, mine, relinkTo = moved)
            } else {
                SourceStatus(source, SourceState.FOLDER_MISSING, mine)
            }
        }

        // Its drive isn't among the mounted ones.
        val weak = ref.isWeakId || here?.first?.isWeakId == true
        if (here != null && weak && exists(path)) return atPath(here.first)
        if (here != null && !here.first.isWeakId && exists(path) && here.first.removable && ref.removable) {
            // Another removable drive now sits where this one was, with the same folder on it.
            return SourceStatus(source, SourceState.OTHER_DRIVE, here.first)
        }
        return SourceStatus(source, SourceState.OFFLINE, null)
    }

    /**
     * The path [path] would have after its library folder moved from [oldRoot] to [newRoot], or null
     * when it isn't inside [oldRoot].
     */
    fun rebase(path: String, oldRoot: String, newRoot: String): String? {
        val relative = relativeTo(path, FsPath.normalize(oldRoot)) ?: return null
        return if (relative.isEmpty()) FsPath.normalize(newRoot) else FsPath.join(FsPath.normalize(newRoot), relative)
    }

    /**
     * Folders that are the same storage seen through two paths (`/run/media/me/GAMES/ROMs` and a bind
     * mount of it), given their canonical paths: each list holds sources that would be scanned twice.
     */
    fun duplicateRoots(canonicalBySource: Map<LibrarySource, String?>): List<List<LibrarySource>> =
        canonicalBySource.entries
            .filter { it.value != null }
            .groupBy { FsPath.normalize(it.value!!) }
            .values
            .filter { it.size > 1 }
            .map { group -> group.map { it.key }.sortedBy { it.id.value } }
}
