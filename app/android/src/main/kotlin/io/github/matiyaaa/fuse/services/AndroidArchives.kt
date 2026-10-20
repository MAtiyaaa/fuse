package io.github.matiyaaa.fuse.services

import io.github.matiyaaa.fuse.ui.shell.store.Archives
import java.io.File
import java.io.IOException
import java.util.zip.ZipFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Zip archives unpacked on Android with the system's own zip reader: into a hidden folder beside
 * the destination first, nothing outside it, then moved into place whole. 7z and rar aren't read
 * here (no program for them on Android), and Fuse says so where one lands.
 */
internal class AndroidArchives : Archives {
    override suspend fun kinds(): Set<String> = setOf("zip")

    override suspend fun list(archive: String): List<Pair<String, Long?>>? = withContext(Dispatchers.IO) {
        runCatching {
            if (!archive.endsWith(".zip", ignoreCase = true)) return@runCatching null
            ZipFile(archive).use { z -> z.entries().toList().filter { !it.isDirectory }.map { it.name to it.size.takeIf { s -> s >= 0 } } }
        }.getOrNull()
    }

    override suspend fun unpack(archive: String, folder: String, onProgress: (Float) -> Unit): Result<List<String>> = withContext(Dispatchers.IO) {
        val dest = File(folder)
        val work = File(dest.parentFile ?: return@withContext Result.failure(IOException("Nowhere to unpack it.")), ".${dest.name}.fuse-unpacking")
        runCatching {
            if (!archive.endsWith(".zip", ignoreCase = true)) throw IOException("Only zip archives are unpacked on Android.")
            if (dest.exists()) throw IOException("${dest.name} is already there, so Fuse left it as it is.")
            work.deleteRecursively()
            work.mkdirs()
            val root = work.canonicalFile.toPath()
            ZipFile(archive).use { z ->
                val entries = z.entries().toList()
                val total = entries.sumOf { it.size.coerceAtLeast(0) }.coerceAtLeast(1)
                var done = 0L
                for (e in entries) {
                    val out = File(work, e.name).canonicalFile
                    if (!out.toPath().startsWith(root)) throw IOException("The archive names a place outside its folder, so Fuse stopped.")
                    if (e.isDirectory) { out.mkdirs(); continue }
                    out.parentFile?.mkdirs()
                    done += z.getInputStream(e).use { i -> out.outputStream().use { o -> i.copyTo(o, 256 * 1024) } }
                    onProgress((done.toFloat() / total).coerceAtMost(0.99f))
                }
            }
            if (!work.renameTo(dest)) throw IOException("The unpacked files couldn't be put in place.")
            onProgress(1f)
            dest.walkTopDown().filter { it.isFile }.map { it.path }.toList()
        }.onFailure { work.deleteRecursively() }
    }
}
