package io.github.matiyaaa.fuse.desktop.capture

import androidx.compose.ui.graphics.toComposeImageBitmap
import io.github.matiyaaa.fuse.desktop.system.DesktopOs
import io.github.matiyaaa.fuse.ui.shell.platform.CaptureResult
import io.github.matiyaaa.fuse.ui.shell.platform.RecordingReady
import io.github.matiyaaa.fuse.ui.shell.platform.ScreenCapture
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.Robot
import java.awt.Window
import java.awt.image.BufferedImage
import java.io.File
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.imageio.ImageIO
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.withContext

/**
 * Screenshots and recordings of Fuse's own window on a computer. A screenshot is the window's
 * area of the screen at its full pixel density; a recording takes that area thirty times a second
 * and hands the frames to the FFmpeg Fuse already carries (for Fuse Player), which encodes them as
 * H.264 in an MP4. Only Fuse's window is ever captured. Recordings have no sound on a computer yet.
 *
 * On a Mac, the system asks once for Screen Recording permission; until it is given, macOS hands
 * back only the desktop, so Fuse says so instead of saving that.
 */
class DesktopScreenCapture(private val window: () -> Window?) : ScreenCapture {
    private val os = DesktopOs.current
    private val home = System.getProperty("user.home").orEmpty()

    override val picturesPlace: String = "Pictures/Fuse"
    override val videosPlace: String = if (os == DesktopOs.MACOS) "Movies/Fuse" else "Videos/Fuse"

    private val pictures: File get() = File(xdg("XDG_PICTURES_DIR") ?: "$home/Pictures", "Fuse")
    private val videos: File get() = File(xdg("XDG_VIDEOS_DIR") ?: if (os == DesktopOs.MACOS) "$home/Movies" else "$home/Videos", "Fuse")

    private val stopped = MutableSharedFlow<CaptureResult>(extraBufferCapacity = 2)
    override val stoppedElsewhere: Flow<CaptureResult> = stopped
    override val leftFuse: Flow<Unit> = MutableSharedFlow()

    @Volatile private var recording: Recording? = null

    override fun freeBytes(): Long? = runCatching { pictures.apply { mkdirs() }.usableSpace }.getOrNull()

    override suspend fun screenshot(name: String): CaptureResult = withContext(Dispatchers.IO) {
        val image = grab() ?: return@withContext CaptureResult(picturesPlace, failure = "The screenshot couldn't be taken: Fuse's window isn't on screen")
        if (os == DesktopOs.MACOS && looksBlank(image)) {
            return@withContext CaptureResult(picturesPlace, failure = "macOS hasn't allowed Fuse to record the screen. Allow it in System Settings, Privacy & Security, Screen Recording, then try again")
        }
        val dir = pictures.apply { mkdirs() }
        val file = File(dir, if (name.endsWith(".png")) name else "$name.png")
        if (!runCatching { ImageIO.write(image, "png", file) }.getOrDefault(false)) {
            return@withContext CaptureResult(picturesPlace, failure = "The screenshot couldn't be saved in ${dir.path}")
        }
        CaptureResult(picturesPlace, preview = runCatching { thumbnail(image).toComposeImageBitmap() }.getOrNull())
    }

    override suspend fun prepareRecording(withSound: Boolean): RecordingReady {
        if (recording != null) return RecordingReady.UNAVAILABLE
        if (encoder() == null) return RecordingReady.UNAVAILABLE
        // No sound on a computer yet: it records the picture alone.
        return if (withSound) RecordingReady.READY_SILENT else RecordingReady.READY
    }

    override fun startRecording(name: String) {
        if (recording != null) return
        val program = encoder() ?: return
        val w = window() ?: return
        val first = grab(w) ?: return
        // An even size, as H.264 wants; at most 1920 wide, so a recording keeps up on any computer.
        val scale = minOf(1f, 1920f / first.width)
        val outW = ((first.width * scale).toInt() / 2) * 2
        val outH = ((first.height * scale).toInt() / 2) * 2
        val file = File(videos.apply { mkdirs() }, if (name.endsWith(".mp4")) name else "$name.mp4")
        val process = runCatching {
            ProcessBuilder(
                program, "-hide_banner", "-loglevel", "error", "-y",
                "-f", "rawvideo", "-pix_fmt", "bgr0", "-s", "${outW}x$outH", "-r", "$FPS", "-i", "-",
                "-c:v", "libx264", "-preset", "veryfast", "-crf", "21", "-pix_fmt", "yuv420p", "-movflags", "+faststart",
                file.path,
            ).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
        }.getOrNull() ?: return
        recording = Recording(process, file, outW, outH).also { it.start() }
    }

    override suspend fun stopRecording(): CaptureResult? = withContext(Dispatchers.IO) {
        val r = recording ?: return@withContext null
        recording = null
        r.finish()
    }

    /** One recording: frames taken on their own thread, paced to [FPS], piped to the encoder. */
    private inner class Recording(val process: Process, val file: File, val w: Int, val h: Int) {
        private val running = AtomicBoolean(true)
        private val frame = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        private val bytes = ByteBuffer.allocate(w * h * 4).order(ByteOrder.LITTLE_ENDIAN)
        private lateinit var thread: Thread

        fun start() {
            thread = Thread({ loop() }, "fuse-recording").apply { isDaemon = true; start() }
        }

        private fun loop() {
            val out: OutputStream = process.outputStream
            val pixels = IntArray(w * h)
            val period = 1_000_000_000L / FPS
            var next = System.nanoTime()
            try {
                while (running.get()) {
                    val shot = window()?.let { grab(it) }
                    if (shot != null) {
                        // Scaled into the recording's fixed size (the window may have changed size).
                        val g = frame.createGraphics()
                        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                        g.drawImage(shot, 0, 0, w, h, null)
                        g.dispose()
                    }
                    frame.getRGB(0, 0, w, h, pixels, 0, w)
                    bytes.clear()
                    bytes.asIntBuffer().put(pixels)
                    out.write(bytes.array(), 0, w * h * 4)
                    next += period
                    val wait = next - System.nanoTime()
                    // Falling behind: frames are repeated in time rather than piling up.
                    if (wait > 0) Thread.sleep(wait / 1_000_000, (wait % 1_000_000).toInt()) else next = System.nanoTime()
                }
            } catch (e: Exception) {
                if (running.getAndSet(false)) {
                    // The encoder stopped by itself: what it made so far is kept.
                    recording = null
                    stopped.tryEmit(close())
                }
            }
        }

        fun finish(): CaptureResult {
            running.set(false)
            thread.join(2_000)
            return close()
        }

        private fun close(): CaptureResult {
            runCatching { process.outputStream.close() }
            val done = process.waitFor(15, TimeUnit.SECONDS)
            if (!done) process.destroy()
            return if (file.isFile && file.length() > 0) CaptureResult(videosPlace, video = true)
            else CaptureResult(videosPlace, video = true, failure = "The recording couldn't be saved")
        }
    }

    /** The window's area of the screen, at the screen's own pixel density, or null when it isn't shown. */
    private fun grab(w: Window? = window()): BufferedImage? {
        if (w == null || !w.isShowing) return null
        val bounds = runCatching { Rectangle(w.locationOnScreen, w.size) }.getOrNull() ?: return null
        if (bounds.width <= 0 || bounds.height <= 0) return null
        val robot = runCatching { Robot(w.graphicsConfiguration.device) }.getOrNull() ?: return null
        // Device coordinates: the screen's bounds offset, as the robot of that screen wants them.
        val screen = w.graphicsConfiguration.bounds
        val local = Rectangle(bounds.x - screen.x, bounds.y - screen.y, bounds.width, bounds.height)
        val image = runCatching { robot.createMultiResolutionScreenCapture(local) }.getOrNull() ?: return null
        val best = image.resolutionVariants.maxByOrNull { it.getWidth(null) } ?: return null
        if (best is BufferedImage) return best
        val copy = BufferedImage(best.getWidth(null), best.getHeight(null), BufferedImage.TYPE_INT_RGB)
        copy.createGraphics().apply { drawImage(best, 0, 0, null); dispose() }
        return copy
    }

    /** All one colour: what macOS returns without Screen Recording permission (the desktop's own fill). */
    private fun looksBlank(image: BufferedImage): Boolean {
        val first = image.getRGB(0, 0)
        for (y in 0 until image.height step 37) for (x in 0 until image.width step 41) if (image.getRGB(x, y) != first) return false
        return true
    }

    private fun thumbnail(image: BufferedImage): BufferedImage {
        val tw = 480
        val th = (image.height * tw / image.width.toFloat()).toInt().coerceAtLeast(1)
        return BufferedImage(tw, th, BufferedImage.TYPE_INT_RGB).also { t ->
            t.createGraphics().apply {
                setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                drawImage(image, 0, 0, tw, th, null)
                dispose()
            }
        }
    }

    /** The FFmpeg program Fuse carries, unpacked by JavaCPP on first use; null when it can't be. */
    private fun encoder(): String? = runCatching { org.bytedeco.javacpp.Loader.load(org.bytedeco.ffmpeg.ffmpeg::class.java) }.getOrNull()

    /** A folder from the user's XDG user-dirs (Linux), when set. */
    private fun xdg(key: String): String? {
        if (os != DesktopOs.LINUX) return null
        val file = File(System.getenv("XDG_CONFIG_HOME") ?: "$home/.config", "user-dirs.dirs")
        val line = runCatching { file.readLines() }.getOrDefault(emptyList()).firstOrNull { it.startsWith("$key=") } ?: return null
        return line.substringAfter('=').trim('"').replace("\$HOME", home).takeIf { it.isNotBlank() }
    }

    private companion object {
        const val FPS = 30
    }
}
