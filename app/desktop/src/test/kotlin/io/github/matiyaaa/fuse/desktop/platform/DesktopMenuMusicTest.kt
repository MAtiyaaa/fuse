package io.github.matiyaaa.fuse.desktop.platform

import io.github.matiyaaa.fuse.ui.shell.platform.MusicState
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import javax.sound.sampled.AudioFileFormat
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The Linux menu music with its sound output replaced by a recorder: live volume changes reach the
 * song that is playing, zero is silence without losing the song, turning music off ends the mixer,
 * and an output that fails is opened again instead of leaving the music silent.
 */
class DesktopMenuMusicTest {
    private lateinit var dir: File
    private lateinit var song: String
    private val outputs = CopyOnWriteArrayList<Recorder>()
    private var failOpens = 0
    private lateinit var music: DesktopMenuMusic

    /** Keeps the loudest sample of each block written. */
    private inner class Recorder(override val rate: Int, override val channels: Int) : MusicOutput {
        val peaks = CopyOnWriteArrayList<Int>()
        @Volatile var closed = false

        override fun write(bytes: ByteArray) {
            var peak = 0
            for (i in 0 until bytes.size / 2) {
                val s = (bytes[i * 2].toInt() and 0xFF) or (bytes[i * 2 + 1].toInt() shl 8)
                peak = maxOf(peak, abs(s.toShort().toInt()))
            }
            peaks += peak
            // Paced like a real device would be, roughly.
            Thread.sleep(3)
        }

        override fun pause() = Unit
        override fun resume() = Unit
        override fun close() {
            closed = true
        }
    }

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("fuse-music").toFile()
        song = File(dir, "tone.wav").also(::writeTone).absolutePath
        music = DesktopMenuMusic({ rate, channels ->
            if (failOpens > 0) {
                failOpens--
                throw IllegalStateException("device busy")
            }
            Recorder(rate, channels).also { outputs += it }
        }, retryMs = 20)
    }

    @AfterTest
    fun tearDown() {
        music.close()
        dir.deleteRecursively()
    }

    private fun latestPeak(): Int {
        val o = outputs.last()
        val before = o.peaks.size
        waitUntil { o.peaks.size >= before + 12 }
        return o.peaks.takeLast(4).max()
    }

    @Test
    fun volumeChangesAreHeardOnTheSongThatIsPlaying() {
        music.apply(MusicState(song, 1f, true))
        waitUntil { outputs.isNotEmpty() }
        // The fade in takes 1.2 seconds of audio.
        waitUntil(5_000) { outputs.last().peaks.size > 80 }
        val full = latestPeak()
        music.apply(MusicState(song, 0.5f, true))
        val half = latestPeak()
        music.apply(MusicState(song, 0f, true))
        val silent = latestPeak()
        music.apply(MusicState(song, 0.5f, true))
        val back = latestPeak()
        assertTrue(full > half && half > 0, "full $full, half $half")
        assertTrue(silent == 0, "silent $silent")
        assertTrue(back > 0, "back $back")
        // One output the whole time: nothing was restarted to change the volume.
        assertTrue(outputs.size == 1)
    }

    @Test
    fun turningMusicOffEndsTheMixerAndOnStartsItAgain() {
        music.apply(MusicState(song, 0.6f, true))
        waitUntil { outputs.isNotEmpty() && outputs.last().peaks.size > 10 }
        music.apply(MusicState(null, 0.6f, true))
        waitUntil(5_000) { !music.running }
        // Changed while off, and in place when it comes back.
        music.apply(MusicState(null, 0.9f, true))
        music.apply(MusicState(song, 0.9f, true))
        waitUntil { music.running }
        waitUntil(5_000) { outputs.last().peaks.size > 80 }
        assertTrue(latestPeak() > 0)
    }

    @Test
    fun aFailingOutputIsOpenedAgain() {
        failOpens = 2
        music.apply(MusicState(song, 0.6f, true))
        waitUntil(5_000) { outputs.isNotEmpty() && outputs.last().peaks.size > 80 }
        assertTrue(latestPeak() > 0)
    }

    @Test
    fun theSameStateAppliedAgainRevivesAMixerThatGaveUp() {
        failOpens = DesktopMenuMusic.MAX_FAILURES
        music.apply(MusicState(song, 0.6f, true))
        waitUntil(5_000) { !music.running && failOpens == 0 }
        assertFalse(outputs.isNotEmpty())
        // Nothing changed in the settings, yet the music comes back: no off and on needed.
        music.apply(MusicState(song, 0.6f, true))
        waitUntil(5_000) { outputs.isNotEmpty() && outputs.last().peaks.size > 80 }
        assertTrue(latestPeak() > 0)
    }

    @Test
    fun quietFadesToSilenceAndBack() {
        music.apply(MusicState(song, 1f, true))
        waitUntil(5_000) { outputs.isNotEmpty() && outputs.last().peaks.size > 80 }
        music.apply(MusicState(song, 1f, false))
        Thread.sleep(400)
        music.apply(MusicState(song, 1f, true))
        waitUntil(5_000) { outputs.last().peaks.size > 200 }
        assertTrue(latestPeak() > 0)
    }

    private fun waitUntil(timeoutMs: Long = 3_000, check: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!check()) {
            if (System.currentTimeMillis() > end) error("timed out")
            Thread.sleep(10)
        }
    }

    /** One second of a 440 Hz tone, stereo 16-bit at 44.1 kHz. */
    private fun writeTone(file: File) {
        val rate = 44_100
        val frames = rate
        val bytes = ByteArray(frames * 4)
        for (i in 0 until frames) {
            val s = (sin(2 * PI * 440 * i / rate) * 12_000).toInt()
            for (c in 0 until 2) {
                bytes[i * 4 + c * 2] = (s and 0xFF).toByte()
                bytes[i * 4 + c * 2 + 1] = (s shr 8 and 0xFF).toByte()
            }
        }
        val format = AudioFormat(rate.toFloat(), 16, 2, true, false)
        AudioSystem.write(AudioInputStream(ByteArrayInputStream(bytes), format, frames.toLong()), AudioFileFormat.Type.WAVE, file)
    }
}
