package io.github.matiyaaa.fuse.ui.shell.store

import io.github.matiyaaa.fuse.data.settings.DownloadSettings
import io.github.matiyaaa.fuse.transfer.WhilePlaying
import io.github.matiyaaa.fuse.ui.shell.store.impl.toTransferSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Downloads and uploads while a game is played: one switch for both, on by default. */
class TransfersWhilePlayingTest {
    @Test
    fun onByDefaultAtFullSpeedBothWays() {
        val d = DownloadSettings()
        assertTrue(d.transfersWhilePlaying)
        val t = d.toTransferSettings()
        assertEquals(WhilePlaying.FULL, t.downloadsWhilePlaying)
        assertEquals(WhilePlaying.FULL, t.uploadsWhilePlaying)
    }

    @Test
    fun offHoldsBothUntilTheGameCloses() {
        val off = DownloadSettings().withTransfersWhilePlaying(false)
        assertFalse(off.transfersWhilePlaying)
        val t = off.toTransferSettings()
        assertEquals(WhilePlaying.PAUSE, t.downloadsWhilePlaying)
        assertEquals(WhilePlaying.PAUSE, t.uploadsWhilePlaying)
        assertTrue(off.withTransfersWhilePlaying(true).transfersWhilePlaying)
    }

    @Test
    fun settingsFromBeforeReadAsOneSwitch() {
        // The slower default before 0.4.4 reads as on, at full speed.
        val before = DownloadSettings(downloadsWhilePlaying = "REDUCED", uploadsWhilePlaying = "REDUCED")
        assertTrue(before.transfersWhilePlaying)
        assertEquals(WhilePlaying.FULL, before.toTransferSettings().uploadsWhilePlaying)
        // A pause chosen for either one reads as off, for both.
        val paused = DownloadSettings(uploadsWhilePlaying = "PAUSE")
        assertFalse(paused.transfersWhilePlaying)
        assertEquals(WhilePlaying.PAUSE, paused.toTransferSettings().downloadsWhilePlaying)
    }
}
