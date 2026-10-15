package io.github.matiyaaa.fuse.desktop.platform

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The songs Fuse ships decode with the Opus decoder the desktop app uses, at one shared format
 * (48 kHz stereo) for crossfades between them. The songs fetched later are made the same way
 * (tools/music/encode.sh).
 */
class BundledMusicDecodeTest {
    @Test
    fun everyBundledSongDecodesAt48kStereo() {
        val dir = File("../../ui/designsystem/src/commonMain/composeResources/files/music")
        val songs = dir.listFiles { f -> f.name.endsWith(".webm") }.orEmpty()
        // The menu's song and setup's song; the rest are fetched the first time they are wanted.
        assertEquals(2, songs.size, "songs in ${dir.absolutePath}")
        for (song in songs) {
            val webm = assertNotNull(WebmOpus.read(song.readBytes()), song.name)
            assertEquals(2, webm.channels, song.name)
            val pcm = OpusPcm(webm)
            var blocks = 0
            while (blocks < 200) {
                val block = pcm.next() ?: break
                assertEquals(0, block.size % 2, "${song.name} is interleaved stereo")
                blocks++
            }
            assertTrue(blocks == 200, "${song.name} decoded $blocks blocks")
        }
        assertEquals(48_000, OpusPcm.RATE)
    }
}
