package io.github.matiyaaa.fuse.ui.shell.downloads

import io.github.matiyaaa.fuse.transfer.TransferDirection
import io.github.matiyaaa.fuse.transfer.TransferItem
import io.github.matiyaaa.fuse.transfer.TransferKind
import io.github.matiyaaa.fuse.transfer.TransferLive
import io.github.matiyaaa.fuse.transfer.TransferStatus
import io.github.matiyaaa.fuse.ui.shell.store.TransferRow
import kotlin.test.Test
import kotlin.test.assertEquals

/** A request another device turned down is said in that device's words, its name once. */
class RequestWordsTest {
    private fun refused(error: String?) = TransferRow(
        TransferItem(
            id = "t1", key = "request:1", source = "request", direction = TransferDirection.DOWNLOAD, kind = TransferKind.GAME,
            title = "Mario Kart 8 Deluxe", status = TransferStatus.FAILED, error = error,
        ),
        actions = emptyList(), device = "dev-1", deviceName = "Atiya HTPC",
    )

    @Test
    fun theDevicesNameIsSaidOnce() {
        assertEquals("Atiya HTPC can't see this game on any device.", rowWords(refused("Atiya HTPC can't see this game on any device."), TransferLive(), null))
        assertEquals("Atiya HTPC couldn't: The disk is full", rowWords(refused("The disk is full"), TransferLive(), null))
        assertEquals("Atiya HTPC couldn't: it didn't work", rowWords(refused(null), TransferLive(), null))
    }
}
