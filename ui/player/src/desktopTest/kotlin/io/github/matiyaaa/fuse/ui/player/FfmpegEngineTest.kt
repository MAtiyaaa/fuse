package io.github.matiyaaa.fuse.ui.player

import io.github.matiyaaa.fuse.playback.PlayMethod
import io.github.matiyaaa.fuse.playback.PlaySource
import io.github.matiyaaa.fuse.playback.TextCue
import io.github.matiyaaa.fuse.ui.player.ffmpeg.FfmpegEngine
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The desktop engine on real clips made by FFmpeg: it opens, plays on the clock, shows pictures,
 * decodes the subtitles inside, seeks, switches sound tracks and comes to the end. The build
 * machine has no sound card, so the clock is the wall's, as it would be on a silent stream.
 */
class FfmpegEngineTest {
    private lateinit var dir: File

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("fuse-player").toFile()
    }

    @AfterTest
    fun tearDown() {
        dir.deleteRecursively()
    }

    private var watched: FfmpegEngine? = null

    private fun waitUntil(what: String, ms: Long = 10_000, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            if (condition()) return
            Thread.sleep(20)
        }
        val e = watched
        throw AssertionError("Timed out waiting for $what" + if (e == null) "" else ", state ${e.state.value}, at ${e.positionMs()} ms, ${e.endConditionsForTest()}")
    }

    @Test
    fun aClipPlaysWithItsPicturesSubtitlesSeekAndEnd() {
        val file = File(dir, "clip.mkv")
        SampleMedia.write(file, seconds = 3, cues = listOf(SampleMedia.Cue(500, 1_500, "Hello <i>Fuse</i>"), SampleMedia.Cue(2_000, 2_600, "Second")), audioTracks = 2)
        val engine = FfmpegEngine().also { watched = it }
        val caps = engine.capabilities(hardwareDecoding = false)
        assertTrue(caps.audioCodecs.contains("aac"), caps.audioCodecs.toString())
        assertTrue("subrip" in caps.embeddedSubtitles)

        val source = PlaySource(url = file.absolutePath, method = PlayMethod.DIRECT_PLAY)
        engine.load(source, startMs = 0, audioOrder = 0, subtitleOrder = 0, play = true)
        waitUntil("the clip to open") { engine.state.value.status == EngineStatus.READY }
        val s = engine.state.value
        assertEquals(320, s.videoWidth)
        assertEquals(240, s.videoHeight)
        assertTrue((s.durationMs ?: 0) in 2_900..3_200, "duration ${s.durationMs}")

        // Pictures arrive in time order and the clock moves.
        var last = -1L
        var frames = 0
        waitUntil("pictures to play") {
            engine.takeFrameForTest()?.let { f ->
                assertTrue(f.ptsMs >= last, "frames out of order: ${f.ptsMs} after $last")
                last = f.ptsMs
                frames++
            }
            frames >= 10
        }
        assertEquals(320 * 240 * 4, engine.lastFrameBytes)

        // The cue inside the stream shows on time, styled.
        waitUntil("the first subtitle") { engine.cues.value.any { (it as? TextCue)?.plainText == "Hello Fuse" } }
        val cue = engine.cues.value.first() as TextCue
        assertTrue(cue.lines[0].any { it.italic && it.text == "Fuse" })
        assertTrue(engine.positionMs() in 400..1_700, "at ${engine.positionMs()}")

        // Paused, the clock stands still.
        engine.pause()
        val held = engine.positionMs()
        Thread.sleep(300)
        assertTrue(kotlin.math.abs(engine.positionMs() - held) < 30)

        // A seek lands where it was asked and shows a picture from there.
        engine.seekTo(2_100)
        waitUntil("a picture after the seek") { (engine.takeFrameForTest()?.ptsMs ?: -1) >= 2_000 }
        assertTrue(engine.positionMs() in 2_050..2_150, "at ${engine.positionMs()}")

        // The other sound track, then playing on to the end.
        engine.selectAudio(1)
        engine.play()
        waitUntil("the end", 8_000) {
            engine.takeFrameForTest()
            engine.state.value.status == EngineStatus.ENDED
        }
        assertTrue(!engine.state.value.playing)
        engine.release()
    }

    @Test
    fun theClipsSizeAndLengthSurviveTheClock() {
        // The clock thread updates the state while the stream opens; what the stream says it is
        // must never be written over, however the two line up. Loading again and again tries many.
        val file = File(dir, "clip.mkv")
        SampleMedia.write(file, seconds = 1, cues = emptyList(), audioTracks = 1)
        val engine = FfmpegEngine()
        engine.capabilities(hardwareDecoding = false)
        repeat(25) { n ->
            engine.load(PlaySource(url = file.absolutePath, method = PlayMethod.DIRECT_PLAY), startMs = 0, audioOrder = 0, subtitleOrder = null, play = true)
            waitUntil("load $n to open") { engine.state.value.status == EngineStatus.READY }
            val s = engine.state.value
            assertEquals(320, s.videoWidth, "load $n")
            assertTrue((s.durationMs ?: 0) > 0, "load $n has no length")
        }
        engine.release()
    }

    @Test
    fun seekingBackFromTheEndPlaysOnAgain() {
        // The stream applies a seek on its own thread; until it has, its end is the old one's, and
        // a press of play in that moment must not be lost to it.
        val file = File(dir, "short.mkv")
        SampleMedia.write(file, seconds = 2, cues = emptyList(), audioTracks = 1)
        val engine = FfmpegEngine().also { watched = it }
        engine.capabilities(hardwareDecoding = false)
        engine.load(PlaySource(url = file.absolutePath, method = PlayMethod.DIRECT_PLAY), startMs = 0, audioOrder = 0, subtitleOrder = null, play = true)
        // Longer than the clock's tick, so the clock surely looks while the seek is still pending.
        engine.slowSeeksForTest(400)
        repeat(3) { n ->
            waitUntil("end $n", 8_000) {
                engine.takeFrameForTest()
                engine.state.value.status == EngineStatus.ENDED
            }
            engine.seekTo(1_000)
            engine.play()
            waitUntil("playing again after seek $n", 8_000) {
                engine.takeFrameForTest()
                engine.state.value.status == EngineStatus.ENDED && engine.positionMs() >= 1_800
            }
        }
        engine.release()
    }

    @Test
    fun aMissingFileFailsCleanly() {
        val engine = FfmpegEngine()
        engine.load(PlaySource(url = File(dir, "missing.mkv").absolutePath, method = PlayMethod.DIRECT_PLAY), 0, null, null)
        waitUntil("the error") { engine.state.value.status == EngineStatus.ERROR }
        assertTrue(engine.state.value.error!!.startsWith("Couldn't open the stream"))
        engine.release()
    }
}
