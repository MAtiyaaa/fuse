package io.github.matiyaaa.fuse.ui.shell.music

import kotlin.test.Test
import kotlin.test.assertEquals

class MenuMusicRemoteTest {
    @Test
    fun skipsStepThroughTheAlbumsAndWrap() {
        val all = BundledMusic.tracks
        assertEquals(all[1].id, MenuMusicRemote.neighbour(all[0].id, 1))
        assertEquals(all.last().id, MenuMusicRemote.neighbour(all[0].id, -1))
        assertEquals(all.first().id, MenuMusicRemote.neighbour(all.last().id, 1))
    }

    @Test
    fun fromTheUsersOwnSongASkipLandsOnABundledOne() {
        assertEquals(BundledMusic.tracks.first().id, MenuMusicRemote.neighbour(BundledMusic.OWN_SONG, 1))
        assertEquals(BundledMusic.tracks.last().id, MenuMusicRemote.neighbour(BundledMusic.OWN_SONG, -1))
    }
}
