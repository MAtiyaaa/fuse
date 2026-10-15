package io.github.matiyaaa.fuse.desktop

import io.github.matiyaaa.fuse.desktop.platform.OpusPcm
import io.github.matiyaaa.fuse.desktop.platform.WebmOpus
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The menu songs Fuse ships (Opus in WebM) decode on the desktop, whole and with sound. */
class WebmOpusTest {
    private val songs = File("../../ui/designsystem/src/commonMain/composeResources/files/music")

    @Test
    fun theBundledSongsDecodeWholeAtTheirLength() {
        val files = songs.listFiles { f -> f.name.endsWith(".webm") }.orEmpty()
        assertTrue(files.isNotEmpty(), "songs ship inside Fuse")
        for (f in files) {
            val song = assertNotNull(WebmOpus.read(f.readBytes()), f.name)
            assertEquals(2, song.channels, f.name)
            val pcm = OpusPcm(song)
            var frames = 0L
            var loud = 0
            while (true) {
                val block = pcm.next() ?: break
                frames += block.size / 2
                loud = maxOf(loud, block.maxOf { abs(it.toInt()) })
            }
            val seconds = frames / OpusPcm.RATE.toDouble()
            // puddleworld is 241 s and alright apothecary 158 s long.
            assertTrue(seconds > 120 && seconds < 300, "${f.name} decodes to ${"%.1f".format(seconds)} s")
            assertTrue(loud > 2_000, "${f.name} has sound ($loud)")
        }
    }

    @Test
    fun anythingElseIsRefused() {
        assertNull(WebmOpus.read(ByteArray(64) { it.toByte() }))
        assertNull(WebmOpus.read(ByteArray(0)))
    }
}
