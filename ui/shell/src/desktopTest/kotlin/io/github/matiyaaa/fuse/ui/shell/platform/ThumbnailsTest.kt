package io.github.matiyaaa.fuse.ui.shell.platform

import coil3.ImageLoader
import coil3.PlatformContext
import coil3.memory.MemoryCache
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Tile art: copied small once, read small after, and full size wherever it is drawn large. */
class ThumbnailsTest {
    @Test
    fun aTileReadsTheSmallCopyOnceItIsMade(): Unit = runBlocking {
        val root = Files.createTempDirectory("fuse-thumbs").toFile()
        try {
            val art = File(root, "box.jpg")
            val img = BufferedImage(1200, 1800, BufferedImage.TYPE_INT_RGB)
            img.createGraphics().apply { color = Color(40, 120, 200); fillRect(0, 0, 1200, 1800) }.dispose()
            ImageIO.write(img, "jpg", art)
            val loader = ImageLoader.Builder(PlatformContext.INSTANCE)
                .components { add(ThumbnailInterceptor(root.path)) }
                .memoryCache { MemoryCache.Builder().maxSizeBytes(0).build() }
                .build()
            fun req(px: Int) = ImageRequest.Builder(PlatformContext.INSTANCE).data(art.path).size(px, px).memoryCachePolicy(CachePolicy.DISABLED).build()
            assertIs<SuccessResult>(loader.execute(req(384)))
            val thumbs = File(root, "thumbs")
            repeat(50) { if (thumbs.listFiles()?.any { it.name.endsWith(".jpg") } == true) return@repeat; delay(50) }
            val made = thumbs.listFiles().orEmpty().filter { it.name.endsWith(".jpg") }
            assertEquals(1, made.size, "one small copy")
            assertTrue(made.single().length() < art.length(), "the copy is smaller")
            // The same size again reads the copy.
            val again = assertIs<SuccessResult>(loader.execute(req(384)))
            assertEquals(made.single().path, again.request.data.toString())
            // Drawn large (a game's page), the art's own file is read, and nothing new is copied.
            val large = assertIs<SuccessResult>(loader.execute(req(1600)))
            assertEquals(art.path, large.request.data.toString())
        } finally {
            root.deleteRecursively()
        }
    }
}
