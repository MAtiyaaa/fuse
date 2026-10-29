package io.github.matiyaaa.fuse.ui.shell.store.impl

import io.github.matiyaaa.fuse.integrations.disc.DiscFiles
import io.github.matiyaaa.fuse.integrations.disc.DiscImage
import io.github.matiyaaa.fuse.integrations.disc.TrackWanted
import io.github.matiyaaa.fuse.library.disc.DiscSectors

/**
 * A compressed disc image's data track as the library's disc reader reads it
 * ([io.github.matiyaaa.fuse.library.disc.PlayStationDisc]): a CHD through its hunks, a CSO or ZSO
 * through its blocks, each sector's 2048 bytes of data from the track's first sector. So a game
 * kept as a CHD is known by the serial on its disc, like one kept as an ISO.
 */
internal object DiscData {
    /** [path]'s data track, its files opened through [files]; [close] lets go of them. Null when it can't be read as a disc. */
    suspend fun sectors(path: String, files: DiscFiles, close: () -> Unit = {}): DiscSectors? {
        val track = DiscImage.open(path, files)?.open(TrackWanted.FirstData) ?: return null
        return object : DiscSectors {
            override suspend fun read(lba: Long, length: Int): ByteArray? {
                val buffer = ByteArray(length)
                return buffer.takeIf { track.read(track.firstSector + lba, buffer, length) == length }
            }

            override fun close() = close()
        }
    }
}
