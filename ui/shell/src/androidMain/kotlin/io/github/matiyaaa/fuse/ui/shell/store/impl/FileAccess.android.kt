package io.github.matiyaaa.fuse.ui.shell.store.impl

import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal actual fun openByteSource(path: String): ClosableByteSource? = try {
    val file = RandomAccessFile(File(path), "r")
    object : ClosableByteSource {
        override val size: Long = file.length()

        override suspend fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int =
            withContext(Dispatchers.IO) {
                if (position >= size) return@withContext -1
                file.seek(position)
                file.read(buffer, offset, length)
            }

        override fun close() = file.close()
    }
} catch (e: Exception) {
    null
}

internal actual fun volumeSpace(path: String): Pair<Long, Long>? = try {
    val file = File(path)
    val total = file.totalSpace
    if (total <= 0L) null else file.usableSpace to total
} catch (e: SecurityException) {
    null
}

/**
 * The ROM inside a .zip, read like a file: the only file in it, or the largest when there are
 * several (other files are readmes and covers). Reads go forward through the entry; going back
 * starts it again. Null when [path] is not a zip or holds no file.
 */
internal actual fun openZippedRom(path: String): ClosableByteSource? {
    if (!path.endsWith(".zip", ignoreCase = true)) return null
    val zip = try {
        ZipFile(File(path))
    } catch (e: Exception) {
        return null
    }
    val entry: ZipEntry = zip.entries().asSequence().filter { !it.isDirectory && it.size != 0L }.maxByOrNull { it.size }
        ?: run {
            zip.close()
            return null
        }
    return object : ClosableByteSource {
        override val size: Long = entry.size.takeIf { it >= 0 } ?: Long.MAX_VALUE
        private var stream: InputStream? = null
        private var at = 0L

        override suspend fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int =
            withContext(Dispatchers.IO) {
                if (position >= size) return@withContext -1
                if (stream == null || position < at) {
                    stream?.close()
                    stream = zip.getInputStream(entry)
                    at = 0
                }
                val input = stream!!
                while (at < position) {
                    val skipped = input.skip(position - at)
                    if (skipped <= 0) {
                        if (input.read() < 0) return@withContext -1
                        at++
                    } else {
                        at += skipped
                    }
                }
                val n = input.read(buffer, offset, length)
                if (n > 0) at += n
                n
            }

        override fun close() {
            stream?.close()
            zip.close()
        }
    }
}
