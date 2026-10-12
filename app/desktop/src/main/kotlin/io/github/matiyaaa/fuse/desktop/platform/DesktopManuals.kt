package io.github.matiyaaa.fuse.desktop.platform

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import io.github.matiyaaa.fuse.ui.shell.platform.ManualDocument
import io.github.matiyaaa.fuse.ui.shell.platform.ManualOpened
import io.github.matiyaaa.fuse.ui.shell.platform.ManualReader
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import java.util.zip.ZipFile
import javax.imageio.ImageIO
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException
import org.apache.pdfbox.rendering.ImageType
import org.apache.pdfbox.rendering.PDFRenderer

/**
 * Manuals on the desktop: PDFs drawn by Apache PDFBox, and CBZ archives of page pictures. Each
 * page is drawn at the size it is shown at, so text stays sharp however far it is zoomed.
 */
class DesktopManuals : ManualReader {
    override suspend fun open(path: String): ManualOpened = withContext(Dispatchers.IO) {
        val file = File(path)
        if (!file.isFile) return@withContext ManualOpened.Unreadable("The manual isn't where it was. Rescanning finds it again if it moved")
        when (file.extension.lowercase()) {
            "pdf" -> openPdf(file)
            "cbz" -> openCbz(file)
            "cbr" -> ManualOpened.Unreadable("CBR manuals (RAR archives) can't be opened here. A CBZ or PDF of the same manual can")
            else -> ManualOpened.Unreadable("Fuse reads manuals as PDF or CBZ files")
        }
    }

    private fun openPdf(file: File): ManualOpened = try {
        val doc = Loader.loadPDF(file)
        if (doc.numberOfPages == 0) {
            doc.close()
            ManualOpened.Unreadable("This PDF has no pages")
        } else {
            ManualOpened.Ready(PdfManual(doc))
        }
    } catch (_: InvalidPasswordException) {
        ManualOpened.Unreadable("This manual is locked with a password")
    } catch (e: Exception) {
        ManualOpened.Unreadable("This PDF couldn't be read: ${e.message ?: "it may be damaged"}")
    }

    private fun openCbz(file: File): ManualOpened = try {
        val zip = ZipFile(file)
        val pages = zip.entries().asSequence()
            .filter { !it.isDirectory && it.name.substringAfterLast('.').lowercase() in PICTURES && !it.name.substringAfterLast('/').startsWith(".") }
            .map { it.name }
            .sortedWith(NaturalOrder)
            .toList()
        if (pages.isEmpty()) {
            zip.close()
            ManualOpened.Unreadable("This CBZ has no pictures in it")
        } else {
            ManualOpened.Ready(CbzManual(zip, pages))
        }
    } catch (e: Exception) {
        ManualOpened.Unreadable("This CBZ couldn't be read: ${e.message ?: "it may be damaged"}")
    }

    private class PdfManual(private val doc: PDDocument) : ManualDocument {
        private val renderer = PDFRenderer(doc)

        // PDFBox draws one page at a time.
        private val lock = Mutex()
        override val pageCount: Int = doc.numberOfPages

        override suspend fun page(index: Int, maxWidthPx: Int, maxHeightPx: Int): ImageBitmap? = withContext(Dispatchers.IO) {
            lock.withLock {
                runCatching {
                    val box = doc.getPage(index).cropBox
                    // A PDF's units are points (1/72 inch): the scale that fits the page in the room it has.
                    val scale = minOf(maxWidthPx / box.width, maxHeightPx / box.height).coerceIn(0.1f, MAX_PDF_SCALE)
                    renderer.renderImage(index, scale, ImageType.RGB).toComposeImageBitmap()
                }.getOrNull()
            }
        }

        override fun close() = doc.close()
    }

    private class CbzManual(private val zip: ZipFile, private val pages: List<String>) : ManualDocument {
        private val lock = Mutex()
        override val pageCount: Int = pages.size

        override suspend fun page(index: Int, maxWidthPx: Int, maxHeightPx: Int): ImageBitmap? = withContext(Dispatchers.IO) {
            lock.withLock {
                runCatching {
                    val bytes = zip.getInputStream(zip.getEntry(pages[index])).use { it.readBytes() }
                    val image = ImageIO.read(bytes.inputStream())
                    if (image == null) {
                        // A format ImageIO doesn't read (WebP): Skia does, at its own size.
                        org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap()
                    } else {
                        fit(image, maxWidthPx, maxHeightPx).toComposeImageBitmap()
                    }
                }.getOrNull()
            }
        }

        override fun close() = zip.close()
    }

    private companion object {
        val PICTURES = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp")

        /** The most a PDF page is enlarged: past this, more pixels add no detail to read. */
        const val MAX_PDF_SCALE = 6f

        /** "page2" before "page10", as people number pages. */
        val NaturalOrder = Comparator<String> { a, b ->
            val chunks = Regex("\\d+|\\D+")
            val ca = chunks.findAll(a.lowercase()).map { it.value }.toList()
            val cb = chunks.findAll(b.lowercase()).map { it.value }.toList()
            for (i in 0 until minOf(ca.size, cb.size)) {
                val x = ca[i]
                val y = cb[i]
                val diff = if (x[0].isDigit() && y[0].isDigit()) {
                    x.trimStart('0').length.compareTo(y.trimStart('0').length).takeIf { it != 0 } ?: x.trimStart('0').compareTo(y.trimStart('0'))
                } else {
                    x.compareTo(y)
                }
                if (diff != 0) return@Comparator diff
            }
            ca.size - cb.size
        }

        /** [image] made smaller to fit, never larger (a picture has only the pixels it has). */
        fun fit(image: BufferedImage, maxW: Int, maxH: Int): BufferedImage {
            val scale = minOf(maxW.toFloat() / image.width, maxH.toFloat() / image.height)
            if (scale >= 1f) return image
            val w = (image.width * scale).toInt().coerceAtLeast(1)
            val h = (image.height * scale).toInt().coerceAtLeast(1)
            val out = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
            out.createGraphics().apply {
                setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
                setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
                drawImage(image, 0, 0, w, h, null)
                dispose()
            }
            return out
        }
    }
}
