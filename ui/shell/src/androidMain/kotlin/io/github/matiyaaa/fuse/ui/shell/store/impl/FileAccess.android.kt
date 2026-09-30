package io.github.matiyaaa.fuse.ui.shell.store.impl

import java.io.File
import java.io.RandomAccessFile
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
