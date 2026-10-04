package io.github.matiyaaa.fuse.desktop.platform

import javazoom.jl.decoder.Bitstream
import javazoom.jl.decoder.Decoder
import javazoom.jl.decoder.SampleBuffer
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The songs Fuse ships decode with the MP3 decoder the Linux app uses, at one shared format for crossfades. */
class BundledMusicDecodeTest {
    @Test
    fun everyBundledSongDecodesAt44kStereo() {
        val dir = File("../../ui/designsystem/src/commonMain/composeResources/files/music")
        val songs = dir.listFiles { f -> f.name.endsWith(".mp3") }.orEmpty()
        assertEquals(24, songs.size, "songs in ${dir.absolutePath}")
        for (song in songs) {
            song.inputStream().buffered().use { input ->
                val bitstream = Bitstream(input)
                val decoder = Decoder()
                var frames = 0
                while (frames < 200) {
                    val header = bitstream.readFrame() ?: break
                    val out = decoder.decodeFrame(header, bitstream) as SampleBuffer
                    assertEquals(44_100, out.sampleFrequency, song.name)
                    assertEquals(2, out.channelCount, song.name)
                    bitstream.closeFrame()
                    frames++
                }
                assertTrue(frames == 200, "${song.name} decoded $frames frames")
            }
        }
    }
}
