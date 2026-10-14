package io.github.matiyaaa.fuse.desktop.capture

import io.github.matiyaaa.fuse.ui.shell.platform.RecordingReady
import java.awt.Color
import java.awt.GraphicsEnvironment
import java.io.File
import java.nio.file.Files
import javax.swing.JFrame
import javax.swing.JPanel
import javax.swing.SwingUtilities
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A screenshot and a short recording of a real window, where there is a screen to take them from
 * (a Linux desktop, or Xvfb). Skipped elsewhere.
 */
class DesktopScreenCaptureTest {
    @Test
    fun aScreenshotAndARecordingOfTheWindowAreSaved() = runBlocking<Unit> {
        // Linux with a display (a desktop, or Xvfb) only: a build machine without one has nothing to
        // capture, and Windows and macOS runners may not allow capturing their screen at all.
        val linux = System.getProperty("os.name").orEmpty().lowercase().contains("linux")
        if (!linux || GraphicsEnvironment.isHeadless() || System.getenv("DISPLAY").isNullOrEmpty()) return@runBlocking
        val home = Files.createTempDirectory("capture-home").toFile()
        val before = System.getProperty("user.home")
        System.setProperty("user.home", home.path)
        val frame = JFrame("Fuse capture test")
        try {
            SwingUtilities.invokeAndWait {
                frame.contentPane = JPanel().apply { background = Color(0x6A, 0x3F, 0x5C) }
                frame.setSize(320, 240)
                frame.setLocation(40, 40)
                frame.isVisible = true
            }
            Thread.sleep(500)
            val capture = DesktopScreenCapture { frame }
            val shot = capture.screenshot("Fuse test shot")
            assertNull(shot.failure, shot.failure)
            val png = File(home, "Pictures/Fuse/Fuse test shot.png")
            assertTrue(png.isFile && png.length() > 0)
            val image = javax.imageio.ImageIO.read(png)
            // The window's own colour, from its middle.
            assertEquals(0x6A3F5C, image.getRGB(image.width / 2, image.height / 2) and 0xFFFFFF)

            assertEquals(RecordingReady.READY, capture.prepareRecording(withSound = false))
            capture.startRecording("Fuse test clip")
            Thread.sleep(1_500)
            val clip = assertNotNull(capture.stopRecording())
            assertNull(clip.failure, clip.failure)
            val mp4 = File(home, "Videos/Fuse/Fuse test clip.mp4")
            assertTrue(mp4.isFile && mp4.length() > 1_000, "a real video: ${mp4.length()} bytes")

            // With sound: what Fuse plays (a tone, handed over as its interface sounds would be)
            // is in the MP4 as AAC, as long as the picture.
            assertEquals(RecordingReady.READY, capture.prepareRecording(withSound = true))
            capture.startRecording("Fuse test clip with sound")
            val tone = Thread {
                val chunk = ByteArray(441 * 2)
                var n = 0
                val end = System.nanoTime() + 2_000_000_000L
                while (System.nanoTime() < end) {
                    for (i in 0 until 441) {
                        val v = (kotlin.math.sin(2 * Math.PI * 440 * n++ / 44_100.0) * 8_000).toInt()
                        chunk[i * 2] = v.toByte(); chunk[i * 2 + 1] = (v shr 8).toByte()
                    }
                    io.github.matiyaaa.fuse.ui.designsystem.sound.AudioTap.feed("sounds", chunk, 0, chunk.size, 44_100, 1)
                    Thread.sleep(10)
                }
            }.apply { start() }
            tone.join()
            val withSound = assertNotNull(capture.stopRecording())
            assertNull(withSound.failure, withSound.failure)
            val clip2 = File(home, "Videos/Fuse/Fuse test clip with sound.mp4")
            assertTrue(clip2.isFile && clip2.length() > 1_000)
            val probe = io.github.matiyaaa.fuse.desktop.system.Processes.which("ffprobe")
            if (probe != null) {
                val out = io.github.matiyaaa.fuse.desktop.system.Processes.run(
                    listOf(probe, "-v", "error", "-show_entries", "stream=codec_type,codec_name,duration", "-of", "csv=p=0", clip2.path),
                    timeoutMs = 20_000,
                )!!.stdout.lines().filter { it.isNotBlank() }
                val video = out.first { "video" in it }
                val audio = out.firstOrNull { "audio" in it }
                assertNotNull(audio, "an audio track: $out")
                assertTrue(audio.startsWith("aac"), audio)
                val vd = video.substringAfterLast(',').toDouble()
                val ad = audio.substringAfterLast(',').toDouble()
                assertTrue(vd > 1.5 && kotlin.math.abs(vd - ad) < 0.25, "picture ${vd}s, sound ${ad}s")
            }
        } finally {
            SwingUtilities.invokeAndWait { frame.dispose() }
            System.setProperty("user.home", before)
            home.deleteRecursively()
        }
    }
}
