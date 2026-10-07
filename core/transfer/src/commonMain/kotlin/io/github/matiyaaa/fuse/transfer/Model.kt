package io.github.matiyaaa.fuse.transfer

import io.github.matiyaaa.fuse.model.VolumeRef
import kotlinx.serialization.Serializable

/** Which way a transfer goes: onto this device, or from it to a server. */
@Serializable
enum class TransferDirection { DOWNLOAD, UPLOAD }

/** What a transfer carries, for its icon and how Downloads talks about it. */
@Serializable
enum class TransferKind {
    GAME,
    /** A game's other content: a disc, an update, DLC, a patch. */
    CONTENT,
    BIOS,
    FIRMWARE,
    /** A film or episode for watching offline. */
    MEDIA,
    SUBTITLE,
    APP,
    EMULATOR,
    /** A new version of Fuse itself. */
    FUSE_UPDATE,
    /** Saves moving through Fuse Sync, when there is enough of them to be worth showing. */
    SAVES,
    OTHER,
}

/** Where a transfer stands. */
@Serializable
enum class TransferStatus {
    /** Waiting for its turn. */
    QUEUED,
    /** Bytes are moving (or being checked and put in place: see [TransferItem.phase]). */
    ACTIVE,
    /** Paused by the person. Stays paused until they resume it. */
    PAUSED,
    /** Held by something Fuse waits out by itself: see [TransferItem.waiting]. */
    WAITING,
    DONE,
    FAILED,
    CANCELLED,
    ;

    val finished: Boolean get() = this == DONE || this == FAILED || this == CANCELLED
}

/** Why a [TransferStatus.WAITING] transfer waits; each clears by itself. */
@Serializable
enum class WaitReason {
    /** The drive it goes to (or comes from) isn't connected. [TransferItem.waitingFor] names it. */
    DRIVE,
    /** The server or the network isn't there; tried again shortly. */
    NETWORK,
    /** A game is being played and the person asked transfers to pause meanwhile. */
    PLAYING,
    /** Only on Wi-Fi, and this connection isn't. */
    WIFI,

    /** The other device it comes from (or goes to) is off or away. [TransferItem.waitingFor] names it. */
    DEVICE,
}

/** What an active transfer is doing. */
@Serializable
enum class TransferPhase { STARTING, TRANSFERRING, VERIFYING, PLACING, FINISHING }

/**
 * Pictures for a transfer's row: [logo] (a game's logo, preferred over writing its title out),
 * [cover] (box art or a poster) and [icon] (an app's icon). Any may be a path, an address Fuse's
 * images understand, or null.
 */
@Serializable
data class TransferArt(val logo: String? = null, val cover: String? = null, val icon: String? = null)

/**
 * Where a transfer lands or comes from on this device: a path as it was last known, and the drive
 * it is on ([volume], with the path inside the drive as [VolumeRef.relativePath]). A drive that comes
 * back under another path (`E:` turning `F:`, `/run/media/x` turning `/media/x`) is followed by its id.
 */
@Serializable
data class TransferPlace(val path: String, val volume: VolumeRef? = null)

/**
 * One transfer Fuse makes for the person. [key] says what it is ("romm:rom:42", "jellyfin:item:ab12")
 * so the same thing is never queued twice. [payload] is the owning source's own note (which files,
 * an upload's session), kept with the transfer so it survives a restart.
 */
@Serializable
data class TransferItem(
    val id: String,
    val key: String,
    /** Which part of Fuse owns it: "romm", "jellyfin", "store", "fuse", "sync"... */
    val source: String,
    val direction: TransferDirection,
    val kind: TransferKind,
    val title: String,
    /** A second line: "Disc 2", "Season 1, Episode 3", "Update 1.04". */
    val detail: String = "",
    /** Fuse's system id, for the system art at the side of its row. */
    val platform: String? = null,
    val art: TransferArt = TransferArt(),
    /** Where it lands (downloads) or what is sent (uploads). */
    val place: TransferPlace? = null,
    /** Where it is going, as the person reads it: "SD card · ROMs/psx", "RomM · Home". Never a token or a full URL. */
    val target: String = "",
    val totalBytes: Long? = null,
    val doneBytes: Long = 0,
    val status: TransferStatus = TransferStatus.QUEUED,
    val phase: TransferPhase? = null,
    val waiting: WaitReason? = null,
    /** The drive a [WaitReason.DRIVE] transfer waits for, by its label. */
    val waitingFor: String? = null,
    /** Why it failed, in words for the person. */
    val error: String? = null,
    /** True when trying again may work (a server that was down); false for a refusal (no permission). */
    val retryable: Boolean = true,
    /** Its place in the queue: lower goes first. */
    val order: Long = 0,
    val createdAt: Long = 0,
    val startedAt: Long? = null,
    val finishedAt: Long? = null,
    /** Whether its source can carry on from where it stopped (HTTP ranges, chunked uploads). */
    val resumable: Boolean = true,
    val attempts: Int = 0,
    /** When a waiting transfer tries again by itself. */
    val retryAt: Long? = null,
    val payload: String = "",
) {
    val progress: Float? get() = totalBytes?.takeIf { it > 0 }?.let { (doneBytes.toFloat() / it).coerceIn(0f, 1f) }
    val running: Boolean get() = status == TransferStatus.ACTIVE
    val upload: Boolean get() = direction == TransferDirection.UPLOAD
}

/** A transfer's moving numbers, updated many times a second while it runs (kept apart so lists stay still). */
data class TransferLive(
    val doneBytes: Long = 0,
    val totalBytes: Long? = null,
    /** Bytes a second, smoothed; 0 while nothing moves. */
    val speed: Long = 0,
    /** Seconds left at [speed], when both sizes are known and it moves. */
    val etaSeconds: Long? = null,
) {
    val progress: Float? get() = totalBytes?.takeIf { it > 0 }?.let { (doneBytes.toFloat() / it).coerceIn(0f, 1f) }
}

/** What to do with transfers while a game is being played. */
@Serializable
enum class WhilePlaying { FULL, REDUCED, PAUSE }

/**
 * The person's transfer settings (Settings, Downloads). Simultaneous downloads and uploads are
 * counted apart, each 1 to 5. [bandwidthLimit] is bytes a second for all of them together, 0 for none.
 */
@Serializable
data class TransferSettings(
    val maxDownloads: Int = 3,
    val maxUploads: Int = 2,
    val bandwidthLimit: Long = 0,
    val downloadsWhilePlaying: WhilePlaying = WhilePlaying.REDUCED,
    val uploadsWhilePlaying: WhilePlaying = WhilePlaying.REDUCED,
    val wifiOnly: Boolean = false,
    /** Carry on transfers left unfinished when Fuse closed. */
    val resumeOnStart: Boolean = true,
    /** How long finished transfers stay listed, in days (0 clears them as soon as Fuse restarts). */
    val keepFinishedDays: Int = 7,
) {
    fun limit(direction: TransferDirection): Int = (if (direction == TransferDirection.DOWNLOAD) maxDownloads else maxUploads).coerceIn(1, MAX_PARALLEL)

    fun whilePlaying(direction: TransferDirection): WhilePlaying = if (direction == TransferDirection.DOWNLOAD) downloadsWhilePlaying else uploadsWhilePlaying

    companion object {
        /** Never more than five each way, whatever a setting says. */
        const val MAX_PARALLEL = 5

        /** Bytes a second a REDUCED transfer gets while a game runs, at most. */
        const val REDUCED_RATE = 2L * 1024 * 1024

        /** A sensible start for this device: fewer at once on a small device or a slow link. */
        fun defaults(cpuCores: Int, lowMemory: Boolean): TransferSettings = TransferSettings(
            maxDownloads = when {
                lowMemory -> 2
                cpuCores >= 8 -> 4
                else -> 3
            },
            maxUploads = if (lowMemory) 1 else 2,
        )
    }
}

/** Conditions outside Fuse's transfers that decide whether they may run. */
data class TransferConditions(
    /** A game is being played on this device. */
    val playing: Boolean = false,
    /** The connection is Wi-Fi or wired (not mobile data). */
    val unmetered: Boolean = true,
)

/** The counts the Downloads button shows. */
data class TransferSummary(
    val activeDownloads: Int = 0,
    val activeUploads: Int = 0,
    val queued: Int = 0,
    val waiting: Int = 0,
    val failed: Int = 0,
    /** 0..1 over everything moving whose size is known; null when none is known. */
    val progress: Float? = null,
) {
    val active: Int get() = activeDownloads + activeUploads
    val any: Boolean get() = active + queued + waiting > 0
}

/** A transfer stopped for good, with words for the person and whether trying again may help. */
class TransferFailure(message: String, val retryable: Boolean = true, cause: Throwable? = null) : Exception(message, cause)

/** The network or server went away mid-transfer: Fuse waits and carries on from where it was. */
class TransferInterrupted(message: String, cause: Throwable? = null) : Exception(message, cause)

/** The drive a transfer needs isn't connected. */
class DriveMissing(val label: String) : Exception("$label isn't connected")

/**
 * The only devices that have what a transfer needs are off or away: it waits for one, without
 * running out of tries (a device can be away for days), and looks again whenever devices change.
 */
class DeviceAway(val label: String) : Exception("$label is away")
