package io.github.matiyaaa.fuse.capture

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import java.io.File

/**
 * Saves captures where the system keeps pictures and videos, so they show in the gallery: through
 * MediaStore on Android 10 and newer (Pictures/Fuse, Movies/Fuse), and as plain files in the same
 * folders on Android 9, which needs the storage permission to write there.
 */
internal class MediaStoreSaver(private val context: Context) {
    private val resolver get() = context.contentResolver

    /** Saves [bitmap] as a PNG named [name]. False when it couldn't be written. */
    fun savePng(bitmap: Bitmap, name: String): Boolean {
        val file = "${CaptureFiles.safeName(name)}.png"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, file)
                put(MediaStore.MediaColumns.MIME_TYPE, PNG)
                put(MediaStore.MediaColumns.RELATIVE_PATH, CaptureFiles.PICTURES)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
            return try {
                val written = resolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } ?: false
                if (!written) error("Nothing was written")
                resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
                true
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                false
            }
        }
        val out = File(publicDir(Environment.DIRECTORY_PICTURES), file)
        return try {
            out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            scan(out, PNG)
            true
        } catch (e: Exception) {
            out.delete()
            false
        }
    }

    /** A recording being written: [fd] goes to the muxer, then it is either finished or abandoned. */
    inner class PendingVideo internal constructor(val fd: ParcelFileDescriptor, private val uri: Uri?, private val file: File?) {
        /** Makes the finished file visible in the gallery. */
        fun finish() {
            runCatching { fd.close() }
            if (uri != null) {
                resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            } else if (file != null) {
                scan(file, MP4)
            }
        }

        /** Throws the unfinished file away. */
        fun abandon() {
            runCatching { fd.close() }
            uri?.let { runCatching { resolver.delete(it, null, null) } }
            file?.delete()
        }

        /** A frame from the start of the finished recording, for the saved card. */
        fun thumbnail(): Bitmap? {
            val retriever = MediaMetadataRetriever()
            return try {
                if (uri != null) retriever.setDataSource(context, uri) else retriever.setDataSource(file?.path)
                retriever.getFrameAtTime(0)
            } catch (e: Exception) {
                null
            } finally {
                runCatching { retriever.release() }
            }
        }
    }

    /** Starts an MP4 named [name] in Movies/Fuse; null when it can't be created. */
    fun newVideo(name: String): PendingVideo? {
        val file = "${CaptureFiles.safeName(name)}.mp4"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, file)
                put(MediaStore.MediaColumns.MIME_TYPE, MP4)
                put(MediaStore.MediaColumns.RELATIVE_PATH, CaptureFiles.MOVIES)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return null
            val fd = try {
                resolver.openFileDescriptor(uri, "rw")
            } catch (e: Exception) {
                null
            }
            if (fd == null) {
                resolver.delete(uri, null, null)
                return null
            }
            return PendingVideo(fd, uri, null)
        }
        val out = File(publicDir(Environment.DIRECTORY_MOVIES), file)
        return try {
            PendingVideo(ParcelFileDescriptor.open(out, ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE), null, out)
        } catch (e: Exception) {
            null
        }
    }

    @Suppress("DEPRECATION")
    private fun publicDir(type: String): File = File(Environment.getExternalStoragePublicDirectory(type), "Fuse").apply { mkdirs() }

    private fun scan(file: File, mime: String) {
        MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf(mime), null)
    }

    private companion object {
        const val PNG = "image/png"
        const val MP4 = "video/mp4"
    }
}
