package io.github.matiyaaa.fuse.model

import kotlinx.serialization.Serializable

/** What kind of drive a [StorageVolume] is, for its icon and how Fuse talks about it. */
@Serializable
enum class VolumeKind {
    /** The device's own storage (Android internal storage, the system drive). */
    INTERNAL,
    /** Another fixed drive inside the machine (a second SSD, `D:`). */
    FIXED,
    SD_CARD,
    USB,
    /** An external drive whose bus Fuse couldn't tell. */
    EXTERNAL,
    NETWORK,
    OPTICAL,
    OTHER,
}

/**
 * A mounted drive as the operating system reports it.
 *
 * @property id Stable identity of the filesystem: `uuid:<UUID>` (Linux, macOS), `vsn:<serial>`
 *   (Windows volume serial), `android:primary` or `android:<uuid>` (Android). When the system offers
 *   nothing stable, the id is `mount:<path>`, which [isWeakId] marks as only as good as the path.
 * @property mountPaths Every path this filesystem is reachable at, the preferred one first (Linux
 *   can show one drive at `/run/media/me/GAMES` and through a bind mount elsewhere). Forward slashes.
 */
@Serializable
data class StorageVolume(
    val id: String,
    val label: String,
    val mountPaths: List<String>,
    val kind: VolumeKind = VolumeKind.OTHER,
    val removable: Boolean = false,
    val readOnly: Boolean = false,
    val totalBytes: Long = 0,
    val freeBytes: Long = 0,
    /** ext4, exfat, ntfs, apfs... when known. Shown only in technical details. */
    val fsType: String? = null,
) {
    val mountPath: String get() = mountPaths.firstOrNull().orEmpty()
    val isWeakId: Boolean get() = id.startsWith(WEAK_PREFIX)
    val usedBytes: Long get() = (totalBytes - freeBytes).coerceAtLeast(0)

    companion object {
        const val WEAK_PREFIX = "mount:"
        fun weakId(mountPath: String) = WEAK_PREFIX + mountPath
    }
}

/**
 * The drive a library folder lives on, remembered with the folder so Fuse can tell "the SD card is
 * out" from "these games were deleted", and find the folder again when the drive comes back under
 * another path (`E:` becoming `F:`, `/run/media` becoming `/media`).
 */
@Serializable
data class VolumeRef(
    val id: String,
    val label: String,
    val kind: VolumeKind = VolumeKind.OTHER,
    val removable: Boolean = false,
    /** The library folder relative to the drive's root ("ROMs", "" for the root itself). */
    val relativePath: String = "",
    /** Epoch millis Fuse last saw the drive mounted. */
    val lastSeenAt: Long? = null,
) {
    val isWeakId: Boolean get() = id.startsWith(StorageVolume.WEAK_PREFIX)
}

/** Whether a library folder can be read right now, and if not, why. */
@Serializable
enum class SourceState {
    /** The folder is there and readable. */
    ONLINE,
    /** The drive it lives on isn't connected. Its games are kept, untouched, until it returns. */
    OFFLINE,
    /** The drive is connected under another path; Fuse follows it (see [SourceStatus.relinkTo]). */
    MOVED,
    /**
     * A different drive is mounted where this folder's drive used to be, with a folder of the same
     * name. Fuse doesn't guess: the user says whether it is the same library.
     */
    OTHER_DRIVE,
    /** The drive is there but the folder isn't. */
    FOLDER_MISSING,
    /** The folder is there but Fuse may not read it (storage permission). */
    NO_ACCESS,
}

/** A library folder with its drive and state. */
@Serializable
data class SourceStatus(
    val source: LibrarySource,
    val state: SourceState,
    /** The drive the folder is on now, when it is mounted. */
    val volume: StorageVolume? = null,
    /** Where the folder is now, for [SourceState.MOVED]. */
    val relinkTo: String? = null,
) {
    /** True when a scan may look at this folder (and so may mark its games missing). */
    val scannable: Boolean get() = state == SourceState.ONLINE
    val driveLabel: String get() = volume?.label ?: source.volume?.label ?: source.label
}
