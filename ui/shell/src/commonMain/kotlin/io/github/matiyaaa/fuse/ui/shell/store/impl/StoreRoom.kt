package io.github.matiyaaa.fuse.ui.shell.store.impl

import io.github.matiyaaa.fuse.integrations.obtainium.DesktopAssetKind
import io.github.matiyaaa.fuse.ui.shell.home.bytesText

/**
 * Room for an install, worked out before anything is downloaded: the download itself, plus what an
 * archive is unpacked to beside it until it is put in place (an AppImage is only moved, so it needs
 * no more). Unpacked sizes aren't published, so an archive counts as [UNPACKED] times its download,
 * a generous figure for the zips, tarballs and 7-Zip archives emulators ship. Pure.
 */
internal object StoreRoom {
    /** A little left over, so an install never fills the disk to the last byte. */
    const val MARGIN = 64L * 1024 * 1024

    /** How many times its download an archive is taken to need while it is unpacked. */
    const val UNPACKED = 3L

    /** Bytes [kind] of [downloadBytes] needs while it is installed. */
    fun needed(downloadBytes: Long, kind: DesktopAssetKind?): Long = when (kind) {
        DesktopAssetKind.APPIMAGE, null -> downloadBytes
        else -> downloadBytes * (1 + UNPACKED)
    } + MARGIN

    /** Why [name] can't be installed for want of room, or null when there is room (or either size is unknown). */
    fun problem(name: String, downloadBytes: Long?, kind: DesktopAssetKind?, freeBytes: Long?): String? {
        if (downloadBytes == null || downloadBytes <= 0 || freeBytes == null) return null
        val need = needed(downloadBytes, kind)
        if (need <= freeBytes) return null
        return "$name needs about ${bytesText(need)} free to install, and there's ${bytesText(freeBytes)} where Fuse puts programs. Make some room, then try again."
    }

    /** What the page says about room: what it needs and what is free, or null when a size is unknown. */
    fun line(downloadBytes: Long?, kind: DesktopAssetKind?, freeBytes: Long?): String? {
        if (downloadBytes == null || downloadBytes <= 0 || freeBytes == null) return null
        val need = needed(downloadBytes, kind)
        val free = "${bytesText(freeBytes)} free"
        return if (need <= freeBytes) "Needs about ${bytesText(need)}  ·  $free" else "Needs about ${bytesText(need)}, more than the $free"
    }
}
