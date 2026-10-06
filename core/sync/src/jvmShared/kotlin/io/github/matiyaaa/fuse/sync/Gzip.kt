package io.github.matiyaaa.fuse.sync

import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** Gzip for the host's larger JSON answers (only between a host and a device that both take it). */
internal object Gzip {
    fun pack(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(bytes.size / 4 + 64)
        GZIPOutputStream(out).use { it.write(bytes) }
        return out.toByteArray()
    }

    /** [bytes] unpacked, reading at most [limit] bytes so a bad answer can't fill memory. */
    fun unpack(bytes: ByteArray, limit: Long): ByteArray {
        val out = ByteArrayOutputStream(bytes.size * 4)
        GZIPInputStream(bytes.inputStream()).use { input ->
            val buf = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
                if (total > limit) throw java.io.IOException("The host's answer is too large.")
                out.write(buf, 0, n)
            }
        }
        return out.toByteArray()
    }
}
