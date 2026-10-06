package io.github.matiyaaa.fuse.transfer

import io.github.matiyaaa.fuse.library.FsPath
import io.github.matiyaaa.fuse.library.storage.Volumes
import io.github.matiyaaa.fuse.model.StorageVolume

/**
 * Which transfers start next. Pure, so every rule is tested: downloads and uploads have their own
 * limits, the queue's order decides, a paused or finished transfer never starts, and a waiting one
 * starts again once what it waited for has cleared.
 */
object TransferScheduler {
    /**
     * The ids to start now, first first. [items] is the whole list; [now] decides which waits are
     * over; [held] says whether a direction is held entirely (paused while playing, not on Wi-Fi).
     */
    fun next(
        items: List<TransferItem>,
        settings: TransferSettings,
        now: Long,
        held: (TransferDirection) -> WaitReason? = { null },
    ): List<String> {
        val out = ArrayList<String>()
        for (direction in TransferDirection.entries) {
            if (held(direction) != null) continue
            val mine = items.filter { it.direction == direction }
            val room = settings.limit(direction) - mine.count { it.status == TransferStatus.ACTIVE }
            if (room <= 0) continue
            mine.asSequence()
                .filter { startable(it, now) }
                .sortedWith(compareBy({ it.order }, { it.createdAt }))
                .take(room)
                .forEach { out += it.id }
        }
        return out
    }

    /** Whether [item] may start at [now]: queued, or waiting on something that may have cleared. */
    fun startable(item: TransferItem, now: Long): Boolean = when (item.status) {
        TransferStatus.QUEUED -> true
        // A drive or the network are tried again once their time comes (or at once, when the
        // drive is seen again: the manager clears retryAt then).
        TransferStatus.WAITING -> item.waiting != WaitReason.PLAYING && item.waiting != WaitReason.WIFI && (item.retryAt ?: 0) <= now
        else -> false
    }

    /** How long a transfer that lost its way waits before trying again: 5 s, 15 s, 30 s, then each minute. */
    fun backoff(attempts: Int): Long = when {
        attempts <= 1 -> 5_000
        attempts == 2 -> 15_000
        attempts == 3 -> 30_000
        else -> 60_000
    }

    /** The order for a new transfer at the end of the queue. */
    fun endOrder(items: List<TransferItem>): Long = (items.maxOfOrNull { it.order } ?: 0) + 1

    /**
     * New orders after moving [id] by [by] places (-1 up, +1 down) among unfinished transfers of its
     * direction, or to the top when [toTop]. Returns the changed orders by id.
     */
    fun move(items: List<TransferItem>, id: String, by: Int = 0, toTop: Boolean = false): Map<String, Long> {
        val item = items.firstOrNull { it.id == id } ?: return emptyMap()
        val line = items.filter { it.direction == item.direction && !it.status.finished }.sortedWith(compareBy({ it.order }, { it.createdAt })).toMutableList()
        val at = line.indexOfFirst { it.id == id }
        if (at < 0) return emptyMap()
        val to = if (toTop) 0 else (at + by).coerceIn(0, line.lastIndex)
        if (to == at) return emptyMap()
        line.removeAt(at)
        line.add(to, item)
        val base = line.minOf { it.order }
        return line.mapIndexed { i, t -> t.id to base + i }.filter { (tid, order) -> items.first { it.id == tid }.order != order }.toMap()
    }

    /** The counts and progress the Downloads button shows. */
    fun summary(items: List<TransferItem>, live: (String) -> TransferLive?): TransferSummary {
        var done = 0L
        var total = 0L
        var known = false
        for (t in items) {
            if (t.status != TransferStatus.ACTIVE) continue
            val l = live(t.id)
            val size = l?.totalBytes ?: t.totalBytes ?: continue
            known = true
            total += size
            done += l?.doneBytes ?: t.doneBytes
        }
        return TransferSummary(
            activeDownloads = items.count { it.status == TransferStatus.ACTIVE && !it.upload },
            activeUploads = items.count { it.status == TransferStatus.ACTIVE && it.upload },
            queued = items.count { it.status == TransferStatus.QUEUED },
            waiting = items.count { it.status == TransferStatus.WAITING },
            failed = items.count { it.status == TransferStatus.FAILED },
            progress = if (known && total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else null,
        )
    }
}

/**
 * Speed and time left from a stream of byte counts: an exponentially weighted average of the rate
 * over about the last few seconds, so a short stall doesn't send the estimate to infinity and a burst
 * doesn't promise the impossible.
 */
class SpeedMeter(private val halfLifeMs: Long = 2_500) {
    private var lastAt = -1L
    private var lastBytes = 0L
    private var rate = 0.0

    /** Takes [bytes] (done so far) at [now]; returns the smoothed rate in bytes a second. */
    fun sample(bytes: Long, now: Long): Long {
        if (lastAt < 0 || bytes < lastBytes) {
            lastAt = now
            lastBytes = bytes
            return rate.toLong()
        }
        val dt = now - lastAt
        if (dt < 200) return rate.toLong()
        val instant = (bytes - lastBytes) * 1000.0 / dt
        val k = 1 - kotlin.math.exp(-dt * LN2 / halfLifeMs)
        rate = if (rate == 0.0) instant else rate + (instant - rate) * k
        lastAt = now
        lastBytes = bytes
        return rate.toLong()
    }

    fun reset() {
        lastAt = -1
        lastBytes = 0
        rate = 0.0
    }

    companion object {
        private const val LN2 = 0.6931471805599453

        /** Seconds left for [remaining] bytes at [speed], or null when it can't be told. */
        fun eta(remaining: Long, speed: Long): Long? = if (speed <= 0 || remaining < 0) null else (remaining + speed - 1) / speed
    }
}

/**
 * Where a [TransferPlace] is now. Its drive is found by id among the mounted [volumes], so a drive
 * back under another path is followed; a drive that isn't there is [DriveMissing], never a path that
 * happens to exist elsewhere.
 */
object TransferPlaces {
    /** The path [place] is at now, or null when its drive isn't connected. */
    fun resolve(place: TransferPlace, volumes: List<StorageVolume>): String? {
        val ref = place.volume ?: return place.path
        // Without any drive reported (a platform that can't tell), the path as it was is all there is.
        if (volumes.isEmpty()) return place.path
        val volume = volumes.firstOrNull { it.id == ref.id } ?: return null
        val mount = volume.mountPath.ifEmpty { return null }
        return if (ref.relativePath.isEmpty()) FsPath.normalize(mount) else FsPath.join(FsPath.normalize(mount), ref.relativePath)
    }

    /** [path] with the drive it is on, so it can be followed later. */
    fun of(path: String, volumes: List<StorageVolume>, now: Long): TransferPlace {
        val (volume, relative) = Volumes.locate(path, volumes) ?: return TransferPlace(FsPath.normalize(path))
        return TransferPlace(
            FsPath.normalize(path),
            io.github.matiyaaa.fuse.model.VolumeRef(volume.id, volume.label, volume.kind, volume.removable, relative, now),
        )
    }
}
