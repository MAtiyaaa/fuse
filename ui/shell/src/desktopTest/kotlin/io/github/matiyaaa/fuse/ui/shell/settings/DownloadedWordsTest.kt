package io.github.matiyaaa.fuse.ui.shell.settings

import io.github.matiyaaa.fuse.model.ReleaseInfo
import io.github.matiyaaa.fuse.ui.shell.store.UpdateState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** An update coming down says how much has come and how fast, not only a percentage. */
class DownloadedWordsTest {
    private val release = ReleaseInfo("v9.9.9", "Fuse 9.9.9", "", null, emptyList(), "")
    private val mib = 1024L * 1024

    @Test
    fun howMuchAndHowFast() {
        val f = '\u2007'
        assertEquals("${f}78 of 180 MB at 2.1 MB/s", downloadedWords(UpdateState.Downloading(release, 0.43f, 78 * mib, 180 * mib, 2 * mib + mib / 10)))
        assertEquals("${f}${f}2 of 180 MB at 0.5 MB/s", downloadedWords(UpdateState.Downloading(release, 0.01f, mib * 3 / 2, 180 * mib, 512 * 1024)))
        // Before it has moved long enough to tell its speed.
        assertEquals("${f}${f}0 of 180 MB", downloadedWords(UpdateState.Downloading(release, 0f, 0, 180 * mib, null)))
        // As it grows, the line keeps its length, so the row never wraps onto a second line and back.
        val lengths = listOf(0L, 9 * mib, 99 * mib, 180 * mib).map { downloadedWords(UpdateState.Downloading(release, 0.5f, it, 180 * mib, 2 * mib))!!.length }
        assertEquals(1, lengths.distinct().size, lengths.toString())
        // A small download keeps tenths throughout.
        assertEquals("0.5 of 8.0 MB", downloadedWords(UpdateState.Downloading(release, 0.06f, mib / 2, 8 * mib, null)))
        // Nothing known yet.
        assertNull(downloadedWords(UpdateState.Downloading(release, null)))
    }
}
