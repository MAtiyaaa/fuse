package io.github.matiyaaa.fuse.desktop.platform

import io.github.matiyaaa.fuse.ui.shell.platform.ManualOpened
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO
import kotlinx.coroutines.runBlocking
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.common.PDRectangle
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Manuals read from real files: a PDF drawn page by page, a CBZ in page order, and what can't be read. */
class DesktopManualsTest {
    private val dir: File = Files.createTempDirectory("fuse-manuals").toFile()

    @AfterTest
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun aPdfOpensAndEachPageIsDrawnToFit(): Unit = runBlocking {
        val pdf = File(dir, "Lantern Coast Manual.pdf")
        PDDocument().use { doc ->
            repeat(3) { doc.addPage(PDPage(PDRectangle.A5)) }
            doc.save(pdf)
        }
        val opened = assertIs<ManualOpened.Ready>(DesktopManuals().open(pdf.absolutePath))
        val doc = opened.document
        assertEquals(3, doc.pageCount)
        val page = assertNotNull(doc.page(1, 400, 600))
        // It fits the room it was given, keeping its shape, and fills it one way or the other.
        assertTrue(page.width <= 400 && page.height <= 600, "${page.width}x${page.height}")
        assertTrue(page.width >= 395 || page.height >= 595, "drawn as large as the room allows: ${page.width}x${page.height}")
        assertEquals(PDRectangle.A5.height / PDRectangle.A5.width, page.height.toFloat() / page.width, 0.02f)
        doc.close()
    }

    @Test
    fun aCbzReadsItsPicturesInPageOrder(): Unit = runBlocking {
        val cbz = File(dir, "Manual.cbz")
        ZipOutputStream(cbz.outputStream()).use { zip ->
            // Stored out of order, and numbered as people number them.
            for ((name, shade) in listOf("page10.png" to 10, "page2.png" to 2, "page1.png" to 1, "__MACOSX/.page1.png" to 0)) {
                zip.putNextEntry(ZipEntry(name))
                val img = BufferedImage(50, 70, BufferedImage.TYPE_INT_RGB).apply {
                    createGraphics().apply { color = Color(shade * 20, shade * 20, shade * 20); fillRect(0, 0, 50, 70); dispose() }
                }
                zip.write(ByteArrayOutputStream().also { ImageIO.write(img, "png", it) }.toByteArray())
                zip.closeEntry()
            }
        }
        val doc = assertIs<ManualOpened.Ready>(DesktopManuals().open(cbz.absolutePath)).document
        // page1, page2, page10: the hidden file left by a Mac is no page.
        assertEquals(3, doc.pageCount)
        val first = assertNotNull(doc.page(0, 500, 700))
        // A picture is never enlarged past its own pixels.
        assertEquals(50, first.width)
        val pixels = IntArray(1)
        first.readPixels(pixels, 0, 0, 1, 1)
        assertEquals(20, (pixels[0] shr 16) and 0xFF, "the first page is page1")
        val last = assertNotNull(doc.page(2, 500, 700))
        last.readPixels(pixels, 0, 0, 1, 1)
        assertEquals(200, (pixels[0] shr 16) and 0xFF, "the last page is page10")
        doc.close()
    }

    @Test
    fun whatCantBeReadSaysWhy(): Unit = runBlocking {
        val cbr = File(dir, "Manual.cbr").apply { writeBytes(ByteArray(10)) }
        assertTrue(assertIs<ManualOpened.Unreadable>(DesktopManuals().open(cbr.absolutePath)).reason.contains("CBR"))
        val broken = File(dir, "Broken.pdf").apply { writeBytes("not a pdf".encodeToByteArray()) }
        assertIs<ManualOpened.Unreadable>(DesktopManuals().open(broken.absolutePath))
        assertIs<ManualOpened.Unreadable>(DesktopManuals().open(File(dir, "Gone.pdf").absolutePath))
    }
}
