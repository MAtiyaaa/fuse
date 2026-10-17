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
        assertEquals("78 of 180 MB, 2.1 MB a second", downloadedWords(UpdateState.Downloading(release, 0.43f, 78 * mib, 180 * mib, 2 * mib + mib / 10)))
        assertEquals("1.5 of 180 MB, 512 KB a second", downloadedWords(UpdateState.Downloading(release, 0.01f, mib * 3 / 2, 180 * mib, 512 * 1024)))
        // Before it has moved long enough to tell its speed.
        assertEquals("0.0 of 180 MB", downloadedWords(UpdateState.Downloading(release, 0f, 0, 180 * mib, null)))
        // Nothing known yet.
        assertNull(downloadedWords(UpdateState.Downloading(release, null)))
    }
}
