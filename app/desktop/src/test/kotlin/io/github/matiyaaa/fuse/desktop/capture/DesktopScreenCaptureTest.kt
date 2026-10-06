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
 * (a desktop, or Xvfb in CI). Skipped on a machine with no display.
 */
class DesktopScreenCaptureTest {
    @Test
    fun aScreenshotAndARecordingOfTheWindowAreSaved() = runBlocking<Unit> {
        // No display here (a headless build machine): nothing to capture.
        if (GraphicsEnvironment.isHeadless() || System.getenv("DISPLAY").isNullOrEmpty() && !System.getProperty("os.name").orEmpty().let { "win" in it.lowercase() || "mac" in it.lowercase() }) return@runBlocking
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
        } finally {
            SwingUtilities.invokeAndWait { frame.dispose() }
            System.setProperty("user.home", before)
            home.deleteRecursively()
        }
    }
}
