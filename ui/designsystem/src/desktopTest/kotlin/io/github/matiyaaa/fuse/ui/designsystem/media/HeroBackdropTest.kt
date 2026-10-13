package io.github.matiyaaa.fuse.ui.designsystem.media

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runComposeUiTest
import coil3.ImageLoader
import coil3.compose.setSingletonImageLoaderFactory
import coil3.intercept.Interceptor
import coil3.request.ImageResult
import io.github.matiyaaa.fuse.model.PerformanceProfile
import io.github.matiyaaa.fuse.model.RenderQuality
import io.github.matiyaaa.fuse.ui.designsystem.theme.FuseTheme
import kotlinx.coroutines.delay
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Surface
import java.io.File
import kotlin.test.Test

/**
 * A room whose art takes its time still comes in: the placeholder stands in first, then the art
 * fades in over it, sharp or blurred. (Art slower than the placeholder's moment used to stay hidden
 * behind the placeholder for good.)
 */
@OptIn(ExperimentalTestApi::class)
class HeroBackdropTest {
    /** A picture of one colour, [size] pixels square, as a file Coil reads. */
    private fun picture(color: Color, size: Int): File {
        val surface = Surface.makeRasterN32Premul(size, size)
        surface.canvas.clear(color.toArgb())
        val bytes = surface.makeImageSnapshot().encodeToData(EncodedImageFormat.PNG)!!.bytes
        return File.createTempFile("fuse-room", ".png").apply {
            writeBytes(bytes)
            deleteOnExit()
        }
    }

    /** Coil, with every picture held back past the placeholder's moment. */
    private val slow = object : Interceptor {
        override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
            delay(SLOW_MS)
            return chain.proceed()
        }
    }

    private fun showsArt(blurred: Boolean, size: Int) = runComposeUiTest {
        val art = picture(Color(0xFF20E040), size)
        setContent {
            setSingletonImageLoaderFactory { context ->
                ImageLoader.Builder(context).components { add(slow) }.memoryCache(null).diskCache(null).build()
            }
            FuseTheme(quality = RenderQuality.of(PerformanceProfile.HIGH_QUALITY, null, lowPower = false)) {
                HeroBackdrop(
                    HeroSource(id = "game", model = art.absolutePath, accent = Color(0xFF8040C0), blurred = blurred),
                    Modifier.fillMaxSize(),
                    dim = 0f,
                    gradient = 0f,
                )
            }
        }
        // The middle of the room turns the art's green, through the dim and the glow.
        waitUntil(timeoutMillis = 10_000) {
            val shot = onRoot().captureToImage()
            val pixels = IntArray(shot.width * shot.height)
            shot.readPixels(pixels)
            val c = Color(pixels[(shot.height / 2) * shot.width + shot.width / 2])
            c.green > 0.25f && c.green > c.red * 2f && c.green > c.blue * 2f
        }
    }

    @Test
    fun slowSharpArtComesInOverThePlaceholder() = showsArt(blurred = false, size = 1600)

    @Test
    fun slowBlurredArtComesInOverThePlaceholder() = showsArt(blurred = true, size = 400)

    private companion object {
        const val SLOW_MS = 400L
    }
}
