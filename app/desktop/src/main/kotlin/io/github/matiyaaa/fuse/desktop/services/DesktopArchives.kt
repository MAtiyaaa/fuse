package io.github.matiyaaa.fuse.desktop.services

import io.github.matiyaaa.fuse.desktop.system.Processes
import io.github.matiyaaa.fuse.ui.shell.store.Archives
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.zip.ZipFile

/**
 * Zip and 7z unpacked by Fuse itself; rar through the first program here that unpacks it (unrar,
 * 7-Zip as `7z` or `7zz`, unar, then bsdtar, which reads rar through libarchive). Every archive is
 * unpacked into a hidden folder beside its destination first, checked (nothing outside it, no
 * links), then moved into place whole.
 */
internal class DesktopArchives(
    /** Finds a program on PATH; the system's unless a test says otherwise. */
    private val which: (String) -> String? = Processes::which,
) : Archives {
    override suspend fun kinds(): Set<String> = withContext(Dispatchers.IO) { setOf("zip", "7z") + if (rarTool() != null) setOf("rar") else emptySet() }

    override suspend fun list(archive: String): List<Pair<String, Long?>>? = withContext(Dispatchers.IO) {
        val f = File(archive)
        runCatching {
            when (kind(f)) {
                "zip" -> ZipFile.builder().setFile(f).get().use { z -> z.entries.asSequence().filter { !it.isDirectory }.map { it.name to it.size.takeIf { s -> s >= 0 } }.toList() }
                "7z" -> SevenZFile.builder().setFile(f).get().use { z -> z.entries.filter { !it.isDirectory && !it.isAntiItem }.map { it.name to it.size } }
                else -> null
            }
        }.getOrNull()
    }

    override suspend fun unpack(archive: String, folder: String, onProgress: (Float) -> Unit): Result<List<String>> = withContext(Dispatchers.IO) {
        val source = File(archive)
        val dest = File(folder)
        val work = File(dest.parentFile ?: return@withContext Result.failure(IOException("Nowhere to unpack it.")), ".${dest.name}.fuse-unpacking")
        runCatching {
            if (!source.isFile) throw IOException("${source.name} isn't there any more.")
            if (dest.exists()) throw IOException("${dest.name} is already there, so Fuse left it as it is.")
            work.deleteRecursively()
            work.mkdirs()
            when (kind(source)) {
                "zip" -> zip(source, work, onProgress)
                "7z" -> sevenZip(source, work, onProgress)
                "rar" -> rar(source, work)
                else -> throw IOException("Fuse doesn't unpack ${source.name}.")
            }
            ensureActive()
            check(work)
            Files.move(work.toPath(), dest.toPath(), StandardCopyOption.ATOMIC_MOVE)
            onProgress(1f)
            dest.walkTopDown().filter { it.isFile }.map { it.path }.toList()
        }.onFailure { work.deleteRecursively() }
    }

    private fun kind(f: File) = f.extension.lowercase().takeIf { it in setOf("zip", "7z", "rar") }

    private fun zip(archive: File, into: File, onProgress: (Float) -> Unit) {
        ZipFile.builder().setFile(archive).get().use { z ->
            val entries = z.entries.toList()
            val total = entries.sumOf { it.size.coerceAtLeast(0) }.coerceAtLeast(1)
            var done = 0L
            for (e in entries) {
                if (e.isUnixSymlink) continue
                val out = target(into, e.name)
                if (e.isDirectory) { out.mkdirs(); continue }
                out.parentFile.mkdirs()
                done += copy(z.getInputStream(e), out)
                onProgress((done.toFloat() / total).coerceAtMost(0.99f))
            }
        }
    }

    private fun sevenZip(archive: File, into: File, onProgress: (Float) -> Unit) {
        SevenZFile.builder().setFile(archive).get().use { z ->
            val total = z.entries.sumOf { it.size }.coerceAtLeast(1)
            var done = 0L
            while (true) {
                val e = z.nextEntry ?: break
                if (e.isAntiItem) continue
                val out = target(into, e.name)
                if (e.isDirectory) { out.mkdirs(); continue }
                out.parentFile.mkdirs()
                done += copy(z.getInputStream(e), out)
                onProgress((done.toFloat() / total).coerceAtMost(0.99f))
            }
        }
    }

    /** The program that unpacks rar here, as the command that unpacks [archive] into [into]. */
    private fun rarTool(archive: File? = null, into: File? = null): List<String>? {
        val a = archive?.path.orEmpty()
        val d = into?.path.orEmpty()
        which("unrar")?.let { return listOf(it, "x", "-o-", "-y", "-idq", a, d + File.separator) }
        (which("7z") ?: which("7zz"))?.let { return listOf(it, "x", "-y", "-bd", "-o$d", a) }
        which("unar")?.let { return listOf(it, "-q", "-f", "-D", "-o", d, a) }
        which("bsdtar")?.let { return listOf(it, "-x", "-f", a, "-C", d) }
        return null
    }

    private fun rar(archive: File, into: File) {
        val command = rarTool(archive, into) ?: throw IOException("Unpacking a rar needs unrar, 7-Zip, unar or bsdtar, and none is installed here.")
        val result = Processes.run(command, timeoutMs = RAR_MS, withErrors = true) ?: throw IOException("${File(command.first()).name} didn't start.")
        if (result.exitCode != 0) throw IOException("${File(command.first()).name} couldn't unpack ${archive.name}.")
    }

    /** Nothing that came out of an archive may be a link or lie outside [into]. */
    private fun check(into: File) {
        val root = into.canonicalFile.toPath()
        Files.walk(into.toPath()).use { s ->
            for (p in s) {
                if (Files.isSymbolicLink(p)) throw IOException("The archive holds a link, so Fuse stopped.")
                if (!p.toFile().canonicalFile.toPath().startsWith(root)) throw IOException("The archive names a place outside its folder, so Fuse stopped.")
            }
        }
    }

    private fun target(into: File, name: String): File {
        val out = File(into, name).canonicalFile
        if (!out.toPath().startsWith(into.canonicalFile.toPath())) throw IOException("The archive names a place outside its folder, so Fuse stopped.")
        return out
    }

    private fun copy(input: InputStream, out: File): Long = input.use { i -> out.outputStream().use { o -> i.copyTo(o, 256 * 1024) } }

    private companion object {
        const val RAR_MS = 60L * 60 * 1000
    }
}
