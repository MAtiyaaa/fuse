package io.github.matiyaaa.fuse.platform

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import io.github.matiyaaa.fuse.ui.shell.platform.ManualDocument
import io.github.matiyaaa.fuse.ui.shell.platform.ManualOpened
import io.github.matiyaaa.fuse.ui.shell.platform.ManualReader
import java.io.File
import java.util.zip.ZipFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Manuals on Android: PDFs drawn by Android's own PdfRenderer, and CBZ archives of page pictures,
 * from a file path or a document Fuse was given access to. Each page is drawn at the size it is
 * shown at.
 */
class AndroidManuals(private val context: Context) : ManualReader {
    override suspend fun open(path: String): ManualOpened = withContext(Dispatchers.IO) {
        val name = if (path.startsWith("content://")) Uri.parse(path).lastPathSegment.orEmpty() else path
        when (name.substringAfterLast('.').lowercase()) {
            "pdf" -> openPdf(path)
            "cbz" -> openCbz(path)
            "cbr" -> ManualOpened.Unreadable("CBR manuals (RAR archives) can't be opened here. A CBZ or PDF of the same manual can")
            else -> ManualOpened.Unreadable("Fuse reads manuals as PDF or CBZ files")
        }
    }

    private fun descriptor(path: String): ParcelFileDescriptor? =
        if (path.startsWith("content://")) {
            context.contentResolver.openFileDescriptor(Uri.parse(path), "r")
        } else {
            File(path).takeIf { it.isFile }?.let { ParcelFileDescriptor.open(it, ParcelFileDescriptor.MODE_READ_ONLY) }
        }

    private fun openPdf(path: String): ManualOpened = try {
        val fd = descriptor(path) ?: return ManualOpened.Unreadable("The manual isn't where it was. Rescanning finds it again if it moved")
        val renderer = PdfRenderer(fd)
        if (renderer.pageCount == 0) {
            renderer.close()
            fd.close()
            ManualOpened.Unreadable("This PDF has no pages")
        } else {
            ManualOpened.Ready(PdfManual(renderer, fd))
        }
    } catch (_: SecurityException) {
        ManualOpened.Unreadable("This manual is locked with a password")
    } catch (e: Exception) {
        ManualOpened.Unreadable("This PDF couldn't be read: ${e.message ?: "it may be damaged"}")
    }

    private fun openCbz(path: String): ManualOpened = try {
        // A document given through Android's picker is copied once into the cache, as an archive's pages are read out of order.
        val file = if (path.startsWith("content://")) {
            val copy = File(context.cacheDir, "manuals/${path.hashCode()}.cbz")
            if (!copy.isFile) {
                copy.parentFile?.mkdirs()
                context.contentResolver.openInputStream(Uri.parse(path))?.use { input -> copy.outputStream().use { input.copyTo(it) } }
            }
            copy
        } else {
            File(path)
        }
        if (!file.isFile) return ManualOpened.Unreadable("The manual isn't where it was. Rescanning finds it again if it moved")
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

    private class PdfManual(private val renderer: PdfRenderer, private val fd: ParcelFileDescriptor) : ManualDocument {
        // PdfRenderer has one page open at a time.
        private val lock = Mutex()
        override val pageCount: Int = renderer.pageCount

        override suspend fun page(index: Int, maxWidthPx: Int, maxHeightPx: Int): ImageBitmap? = withContext(Dispatchers.IO) {
            lock.withLock {
                runCatching {
                    renderer.openPage(index).use { page ->
                        // A PDF's units are points: the scale that fits the page in the room it has.
                        val scale = minOf(maxWidthPx.toFloat() / page.width, maxHeightPx.toFloat() / page.height).coerceIn(0.1f, MAX_PDF_SCALE)
                        val bitmap = Bitmap.createBitmap((page.width * scale).toInt().coerceAtLeast(1), (page.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                        // PdfRenderer leaves what the page doesn't paint transparent: paper is white.
                        bitmap.eraseColor(Color.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        bitmap.asImageBitmap()
                    }
                }.getOrNull()
            }
        }

        override fun close() {
            runCatching { renderer.close() }
            runCatching { fd.close() }
        }
    }

    private class CbzManual(private val zip: ZipFile, private val pages: List<String>) : ManualDocument {
        private val lock = Mutex()
        override val pageCount: Int = pages.size

        override suspend fun page(index: Int, maxWidthPx: Int, maxHeightPx: Int): ImageBitmap? = withContext(Dispatchers.IO) {
            lock.withLock {
                runCatching {
                    val bytes = zip.getInputStream(zip.getEntry(pages[index])).use { it.readBytes() }
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                    // Decoded no larger than shown: a whole page of a big scan would be wasted memory.
                    var sample = 1
                    while (bounds.outWidth / (sample * 2) >= maxWidthPx && bounds.outHeight / (sample * 2) >= maxHeightPx) sample *= 2
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
                }.getOrNull()
            }
        }

        override fun close() {
            runCatching { zip.close() }
        }
    }

    private companion object {
        val PICTURES = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp")

        /** The most a PDF page is enlarged: past this, more pixels add no detail to read. */
        const val MAX_PDF_SCALE = 4f

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
    }
}
