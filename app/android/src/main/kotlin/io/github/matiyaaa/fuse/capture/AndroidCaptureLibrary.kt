package io.github.matiyaaa.fuse.capture

import android.content.ContentUris
import android.content.Context
import android.database.ContentObserver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Size
import androidx.annotation.RequiresApi
import androidx.core.graphics.scale
import io.github.matiyaaa.fuse.link.CaptureReader
import io.github.matiyaaa.fuse.link.LinkCapture
import io.github.matiyaaa.fuse.link.LinkCaptures
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext

/**
 * Fuse's screenshots and recordings as Phone Link sees them: what is in Pictures/Fuse and
 * Movies/Fuse, read only. MediaStore on Android 10 and newer; the same folders as plain files on
 * Android 9. Keys are MediaStore ids or file names inside those folders, so nothing else on the
 * device can be reached through them.
 */
class AndroidCaptureLibrary(context: Context) : LinkCaptures {
    private val context = context.applicationContext
    private val resolver get() = context.contentResolver

    override suspend fun list(): List<LinkCapture> = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            query(IMAGES, CaptureFiles.PICTURES, video = false) + query(VIDEOS, CaptureFiles.MOVIES, video = true)
        } else {
            files(Environment.DIRECTORY_PICTURES, video = false) + files(Environment.DIRECTORY_MOVIES, video = true)
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun query(collection: Uri, folder: String, video: Boolean): List<LinkCapture> {
        val columns = buildList {
            add(MediaStore.MediaColumns._ID)
            add(MediaStore.MediaColumns.DISPLAY_NAME)
            add(MediaStore.MediaColumns.MIME_TYPE)
            add(MediaStore.MediaColumns.SIZE)
            add(MediaStore.MediaColumns.DATE_TAKEN)
            add(MediaStore.MediaColumns.DATE_ADDED)
            add(MediaStore.MediaColumns.WIDTH)
            add(MediaStore.MediaColumns.HEIGHT)
            if (video) add(MediaStore.MediaColumns.DURATION)
        }.toTypedArray()
        val out = mutableListOf<LinkCapture>()
        runCatching {
            resolver.query(collection, columns, "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?", arrayOf("$folder/%"), null)?.use { c ->
                val id = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val name = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val mime = c.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
                val size = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                val taken = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_TAKEN)
                val added = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
                val width = c.getColumnIndexOrThrow(MediaStore.MediaColumns.WIDTH)
                val height = c.getColumnIndexOrThrow(MediaStore.MediaColumns.HEIGHT)
                val duration = if (video) c.getColumnIndexOrThrow(MediaStore.MediaColumns.DURATION) else -1
                while (c.moveToNext()) {
                    val bytes = c.getLong(size)
                    if (bytes <= 0) continue
                    out += LinkCapture(
                        key = "${if (video) VIDEO else IMAGE}:${c.getLong(id)}",
                        name = c.getString(name) ?: continue,
                        video = video,
                        mime = c.getString(mime) ?: if (video) "video/mp4" else "image/png",
                        size = bytes,
                        // DATE_TAKEN is in milliseconds, DATE_ADDED in seconds.
                        takenAt = c.getLong(taken).takeIf { it > 0 } ?: (c.getLong(added) * 1_000),
                        width = c.getInt(width),
                        height = c.getInt(height),
                        durationMs = if (duration >= 0) c.getLong(duration) else 0,
                    )
                }
            }
        }
        return out
    }

    private fun files(type: String, video: Boolean): List<LinkCapture> {
        val dir = publicDir(type)
        return dir.listFiles().orEmpty().filter { it.isFile && it.length() > 0 && !it.name.startsWith(".") }.map { f ->
            LinkCapture(
                key = "${if (video) FILE_VIDEO else FILE_IMAGE}:${f.name}",
                name = f.name,
                video = video,
                mime = if (video) "video/mp4" else if (f.extension.equals("png", true)) "image/png" else "image/jpeg",
                size = f.length(),
                takenAt = f.lastModified(),
            )
        }
    }

    override suspend fun thumbnail(key: String): ByteArray? = withContext(Dispatchers.IO) {
        val bitmap = runCatching {
            when (val target = resolve(key)) {
                is Target.Media -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    resolver.loadThumbnail(target.uri, Size(THUMB_WIDTH, THUMB_WIDTH * 9 / 16), null)
                } else {
                    null
                }
                is Target.Path -> if (target.video) videoFrame(target.file) else sampled(target.file)
                null -> null
            }
        }.getOrNull() ?: return@withContext null
        try {
            ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 82, it) }.toByteArray()
        } finally {
            bitmap.recycle()
        }
    }

    override suspend fun open(key: String): CaptureReader? = withContext(Dispatchers.IO) {
        runCatching {
            when (val target = resolve(key)) {
                is Target.Media -> resolver.openFileDescriptor(target.uri, "r")?.let(::DescriptorReader)
                is Target.Path -> target.file.takeIf { it.isFile }?.let { FileReader(RandomAccessFile(it, "r")) }
                null -> null
            }
        }.getOrNull()
    }

    override val changes: Flow<Unit> = callbackFlow {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                trySend(Unit)
            }
        }
        resolver.registerContentObserver(IMAGES, true, observer)
        resolver.registerContentObserver(VIDEOS, true, observer)
        awaitClose { resolver.unregisterContentObserver(observer) }
    }

    private sealed interface Target {
        class Media(val uri: Uri) : Target
        class Path(val file: File, val video: Boolean) : Target
    }

    /** Only keys this library hands out: an id in its collection, or a file name inside Fuse's folders. */
    private fun resolve(key: String): Target? {
        val kind = key.substringBefore(':')
        val rest = key.substringAfter(':', "")
        return when (kind) {
            IMAGE -> rest.toLongOrNull()?.let { Target.Media(ContentUris.withAppendedId(IMAGES, it)) }
            VIDEO -> rest.toLongOrNull()?.let { Target.Media(ContentUris.withAppendedId(VIDEOS, it)) }
            FILE_IMAGE, FILE_VIDEO -> {
                if (rest.isEmpty() || '/' in rest || rest.startsWith(".")) return null
                val video = kind == FILE_VIDEO
                Target.Path(File(publicDir(if (video) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES), rest), video)
            }
            else -> null
        }
    }

    private fun sampled(file: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= THUMB_WIDTH) sample *= 2
        return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    private fun videoFrame(file: File): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.path)
            retriever.getFrameAtTime(0)?.let { frame ->
                val scale = THUMB_WIDTH.toFloat() / frame.width
                if (scale >= 1f) frame else frame.scale(THUMB_WIDTH, (frame.height * scale).toInt().coerceAtLeast(1)).also { frame.recycle() }
            }
        } finally {
            runCatching { retriever.release() }
        }
    }

    @Suppress("DEPRECATION")
    private fun publicDir(type: String): File = File(Environment.getExternalStoragePublicDirectory(type), "Fuse")

    /** A MediaStore file, read through its descriptor; closing it closes the descriptor. */
    private class DescriptorReader(private val pfd: ParcelFileDescriptor) : CaptureReader {
        private val input = FileInputStream(pfd.fileDescriptor)
        override val length: Long = pfd.statSize
        override fun seek(position: Long) {
            input.channel.position(position)
        }
        override fun read(buffer: ByteArray, max: Int): Int = input.read(buffer, 0, max)
        override fun close() {
            runCatching { input.close() }
            runCatching { pfd.close() }
        }
    }

    private class FileReader(private val file: RandomAccessFile) : CaptureReader {
        override val length: Long = file.length()
        override fun seek(position: Long) = file.seek(position)
        override fun read(buffer: ByteArray, max: Int): Int = file.read(buffer, 0, max)
        override fun close() = file.close()
    }

    private companion object {
        val IMAGES: Uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val VIDEOS: Uri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        const val IMAGE = "image"
        const val VIDEO = "video"
        const val FILE_IMAGE = "picture-file"
        const val FILE_VIDEO = "video-file"
        const val THUMB_WIDTH = 480
    }
}
