package io.github.matiyaaa.fuse.desktop.services

import io.github.matiyaaa.fuse.integrations.obtainium.DesktopAssetKind
import org.apache.commons.compress.archivers.ArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * The tarballs and 7-Zip archives emulators publish for Linux, unpacked as they are: files keep
 * their runnable bit, and nothing may land outside the folder or be a link out of it.
 */
internal object LinuxArchives {
    private const val MAX_ENTRIES = 100_000
    private const val MAX_BYTES = 8L * 1024 * 1024 * 1024

    fun unpack(archive: File, into: File, kind: DesktopAssetKind) {
        into.mkdirs()
        when (kind) {
            DesktopAssetKind.TAR -> tar(archive, into)
            DesktopAssetKind.SEVEN_ZIP -> sevenZip(archive, into)
            else -> throw IOException("Fuse doesn't unpack that kind of file here.")
        }
    }

    private fun tar(archive: File, into: File) {
        val name = archive.name.lowercase()
        archive.inputStream().buffered().use { raw ->
            val decompressed: InputStream = when {
                name.endsWith(".xz") -> XZCompressorInputStream(raw)
                else -> GzipCompressorInputStream(raw)
            }
            TarArchiveInputStream(decompressed).use { tar ->
                var count = 0
                var total = 0L
                while (true) {
                    val entry: TarArchiveEntry = tar.nextEntry ?: break
                    if (++count > MAX_ENTRIES) throw IOException("The archive has too many files.")
                    // Links are left out: a program's folder never needs one pointing elsewhere.
                    if (entry.isSymbolicLink || entry.isLink) continue
                    val out = target(into, entry)
                    if (entry.isDirectory) { out.mkdirs(); continue }
                    if (!entry.isFile) continue
                    out.parentFile?.mkdirs()
                    total += copy(tar, out, MAX_BYTES - total)
                    if (entry.mode and 0b001_001_001 != 0) out.setExecutable(true, false)
                }
            }
        }
    }

    private fun sevenZip(archive: File, into: File) {
        SevenZFile.builder().setFile(archive).get().use { z ->
            var count = 0
            var total = 0L
            while (true) {
                val entry = z.nextEntry ?: break
                if (++count > MAX_ENTRIES) throw IOException("The archive has too many files.")
                val out = target(into, entry)
                if (entry.isDirectory) { out.mkdirs(); continue }
                if (entry.isAntiItem) continue
                out.parentFile?.mkdirs()
                total += copy(z.getInputStream(entry), out, MAX_BYTES - total)
                // 7-Zip keeps a Unix mode in the high bits of its attributes when it has one.
                val mode = if (entry.hasWindowsAttributes) (entry.windowsAttributes ushr 16) else 0
                if (mode and 0b001_001_001 != 0 || out.name.endsWith(".AppImage", ignoreCase = true)) out.setExecutable(true, false)
            }
        }
    }

    private fun target(into: File, entry: ArchiveEntry): File {
        val out = File(into, entry.name).canonicalFile
        if (!out.toPath().startsWith(into.canonicalFile.toPath())) throw IOException("The archive names a place outside its folder, so Fuse stopped.")
        return out
    }

    private fun copy(input: InputStream, out: File, room: Long): Long {
        var written = 0L
        out.outputStream().use { o ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                written += n
                if (written > room) throw IOException("The archive unpacks to more than Fuse allows.")
                o.write(buffer, 0, n)
            }
        }
        return written
    }

    /**
     * The program to start in an unpacked folder: a runnable file near the top whose name is the
     * program's ("DOSBox Staging" finds dosbox-staging, or dosbox), else the largest runnable one.
     */
    fun programIn(dir: File, name: String): File? {
        val runnable = dir.walkTopDown().maxDepth(3).filter { it.isFile && it.canExecute() && '.' !in it.name.removeSuffix(".x86_64") && !it.name.endsWith(".so") }.toList()
            .ifEmpty { dir.walkTopDown().maxDepth(3).filter { it.isFile && it.canExecute() }.toList() }
        val key = name.lowercase().filter { it.isLetterOrDigit() }
        fun plain(f: File) = f.name.lowercase().filter { it.isLetterOrDigit() }
        return runnable.firstOrNull { plain(it) == key }
            ?: runnable.firstOrNull { key.startsWith(plain(it)) && plain(it).length >= 4 }
            ?: runnable.firstOrNull { plain(it).startsWith(key) }
            ?: runnable.maxByOrNull { it.length() }
    }
}
