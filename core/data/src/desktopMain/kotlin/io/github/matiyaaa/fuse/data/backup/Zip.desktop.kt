package io.github.matiyaaa.fuse.data.backup

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

internal actual fun zip(entries: List<Pair<String, ByteArray>>): ByteArray {
    val out = ByteArrayOutputStream()
    ZipOutputStream(out).use { zip ->
        for ((name, bytes) in entries) {
            zip.putNextEntry(ZipEntry(name))
            zip.write(bytes)
            zip.closeEntry()
        }
    }
    return out.toByteArray()
}

internal actual fun unzip(bytes: ByteArray, maxTotalBytes: Long): Map<String, ByteArray>? = try {
    val entries = LinkedHashMap<String, ByteArray>()
    var total = 0L
    ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val entry = zip.nextEntry ?: break
            if (entry.isDirectory) continue
            val out = ByteArrayOutputStream()
            while (true) {
                val n = zip.read(buffer)
                if (n < 0) break
                total += n
                // A zip that inflates past what any backup holds is refused, not unpacked.
                if (total > maxTotalBytes) return null
                out.write(buffer, 0, n)
            }
            entries[entry.name] = out.toByteArray()
        }
    }
    entries
} catch (e: Exception) {
    null
}
