package io.github.matiyaaa.fuse.ui.designsystem.media

import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import coil3.BitmapImage
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import coil3.size.Scale
import coil3.size.Size
import io.github.matiyaaa.fuse.model.MotionProfile
import io.github.matiyaaa.fuse.model.PerformanceProfile
import io.github.matiyaaa.fuse.model.RenderQuality
import io.github.matiyaaa.fuse.ui.designsystem.theme.FuseTheme
import kotlinx.coroutines.runBlocking
import okio.Buffer
import okio.FileSystem
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** GIFs chosen as art play on a computer: read frame by frame, and drawn moving where motion allows. */
@OptIn(ExperimentalTestApi::class)
class MovingPicturesTest {
    private fun fixture(name: String): ByteArray = javaClass.getResourceAsStream("/moving/$name")!!.readBytes()

    private fun file(name: String): File = File.createTempFile("fuse-moving", ".gif").apply {
        writeBytes(fixture(name))
        deleteOnExit()
    }

    private fun options(still: Boolean = false) = Options(
        PlatformContext.INSTANCE,
        size = Size(64, 64),
        scale = Scale.FIT,
        extras = coil3.Extras.Builder().apply { if (still) set(MovingPictures.Still, true) }.build(),
    )

    private fun fetched(bytes: ByteArray) = SourceFetchResult(ImageSource(Buffer().write(bytes), FileSystem.SYSTEM), null, DataSource.DISK)

    @Test
    fun aGifOrAnAnimatedWebpIsToldFromItsFirstBytes() {
        assertTrue(MovingPictures.isMoving(fixture("three-colours.gif")))
        assertTrue(MovingPictures.isMoving("GIF87a".encodeToByteArray()))
        // The extended WebP header with its animation flag set, and without.
        val webp = "RIFF\u0000\u0000\u0000\u0000WEBPVP8X".encodeToByteArray() + ByteArray(12)
        assertFalse(MovingPictures.isMoving(webp))
        assertTrue(MovingPictures.isMoving(webp.copyOf().also { it[20] = 0x02 }))
        assertFalse(MovingPictures.isMoving("RIFF\u0000\u0000\u0000\u0000WEBPVP8 ".encodeToByteArray() + ByteArray(12)))
        assertFalse(MovingPictures.isMoving(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte())))
    }

    @Test
    fun onlyGifAndWebpArtIsAskedForStill() {
        assertTrue(MovingPictures.mayMove("/media/custom/1-fire.GIF"))
        assertTrue(MovingPictures.mayMove("https://cdn.example/hero.webp?v=2"))
        assertFalse(MovingPictures.mayMove("/media/custom/hero.png"))
        assertFalse(MovingPictures.mayMove(42))
        // A GIF keeps its own colours where a plain logo would be drawn white.
        assertTrue(MovingPictures.looksMoving("/media/logo.gif"))
        assertFalse(MovingPictures.looksMoving("/media/logo.svg"))
    }

    @Test
    fun aGifIsReadFrameByFrameWithEachFramesTime(): Unit = runBlocking {
        val bytes = fixture("three-colours.gif")
        val loader = ImageLoader(PlatformContext.INSTANCE)
        val decoder = SkiaMovingDecoder.Factory().create(fetched(bytes), options(), loader)!!
        val image = assertIs<MovingSkiaImage>(decoder.decode()!!.image)
        assertEquals(3, image.frameCount)
        // The last frame asks for no time at all, so it shows for a tenth of a second, as in a browser.
        assertEquals(listOf(50, 120, 100), image.frameMillis)
        assertEquals(0, image.shown)
        // Asked for its first frame only, the GIF is left to the plain decoder.
        assertNull(SkiaMovingDecoder.Factory().create(fetched(bytes), options(still = true), loader))
        // A picture with one frame comes back plain.
        val one = SkiaMovingDecoder.Factory().create(fetched(fixture("one-colour.gif")), options(), loader)!!
        assertIs<BitmapImage>(one.decode()!!.image)
    }

    /** The colour in the middle of the window. */
    private fun androidx.compose.ui.test.ComposeUiTest.middle(): Color {
        val shot = onRoot().captureToImage()
        val pixels = IntArray(shot.width * shot.height)
        shot.readPixels(pixels)
        return Color(pixels[(shot.height / 2) * shot.width + shot.width / 2])
    }

    private fun shows(motion: MotionProfile, lowPower: Boolean = false, block: androidx.compose.ui.test.ComposeUiTest.() -> Unit) = runComposeUiTest {
        val gif = file("three-colours.gif")
        // This test's own loader, whatever another test left as the one every picture uses.
        SingletonImageLoader.setUnsafe(
            ImageLoader.Builder(PlatformContext.INSTANCE).components { addMovingPictures() }.memoryCache(null).diskCache(null).build(),
        )
        try {
            setContent {
                FuseTheme(motion = motion, quality = RenderQuality.of(PerformanceProfile.HIGH_QUALITY, null, lowPower = lowPower)) {
                    Artwork(gif.absolutePath, Modifier.size(48.dp), fadeIn = false)
                }
            }
            waitUntil(timeoutMillis = 10_000) { middle().red > 0.8f }
            block()
        } finally {
            SingletonImageLoader.reset()
        }
    }

    @Test
    fun aGifPlaysWhereMotionAllows() = shows(MotionProfile.STANDARD) {
        // Red, then green, then blue, in turn.
        waitUntil(timeoutMillis = 10_000) { middle().green > 0.8f }
        waitUntil(timeoutMillis = 10_000) { middle().blue > 0.8f }
    }

    private fun holdsStill(motion: MotionProfile, lowPower: Boolean) = shows(motion, lowPower) {
        mainClock.advanceTimeBy(1_000)
        assertTrue(middle().red > 0.8f, "still red under $motion, low power $lowPower")
    }

    @Test
    fun aGifHoldsItsFirstFrameUnderReducedMotion() = holdsStill(MotionProfile.REDUCED, lowPower = false)

    @Test
    fun aGifHoldsItsFirstFrameInLowPower() = holdsStill(MotionProfile.STANDARD, lowPower = true)
}
