package io.github.matiyaaa.fuse.desktop.services

import io.github.matiyaaa.fuse.desktop.system.fusePath
import io.github.matiyaaa.fuse.library.FsAccessException
import io.github.matiyaaa.fuse.library.FsEntry
import io.github.matiyaaa.fuse.library.FsPath
import io.github.matiyaaa.fuse.library.FuseFileSystem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.NotDirectoryException
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.nio.ByteBuffer

/**
 * Read-only [FuseFileSystem] over java.nio. Paths in and out use forward slashes on every system
 * (java.nio reads "C:/Games" on Windows as it is). Symlinks are reported, not walked: an entry that is a
 * link to a directory says so ([FsEntry.isDirectory] and [FsEntry.isSymlink]), and the scanner uses
 * [canonical] to skip directories it has already seen, so link loops end. Only [delete], [copy]
 * and [makeDirs] write, for games the user deletes or moves in Settings, Storage.
 */
class NioFileSystem : FuseFileSystem {

    /** The one write: deleting a game's files when the user asks in Settings, Storage. */
    override suspend fun delete(path: String): Boolean = withContext(Dispatchers.IO) {
        val root = Paths.get(path)
        if (!Files.exists(root, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return@withContext true
        try {
            // Files.walk doesn't follow links, so a link inside a game folder is removed, not what it points to.
            Files.walk(root).use { stream -> stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
            true
        } catch (e: IOException) {
            false
        } catch (e: SecurityException) {
            false
        }
    }

    /**
     * The other write: copying a game to another drive when the user moves it in Settings, Storage.
     * Links are skipped, never followed. A copy that fails part way is removed again.
     */
    override suspend fun copy(from: String, to: String, onBytes: (Long) -> Unit): Boolean = withContext(Dispatchers.IO) {
        val src = Paths.get(from)
        val dst = Paths.get(to)
        if (Files.exists(dst, LinkOption.NOFOLLOW_LINKS)) return@withContext false
        val buffer = ByteArray(COPY_BUFFER)
        fun copyOne(s: Path, d: Path): Boolean {
            if (Files.isSymbolicLink(s)) return true
            if (Files.isDirectory(s, LinkOption.NOFOLLOW_LINKS)) {
                Files.createDirectories(d)
                return Files.newDirectoryStream(s).use { children -> children.all { copyOne(it, d.resolve(it.fileName.toString())) } }
            }
            d.parent?.let { Files.createDirectories(it) }
            Files.newInputStream(s).use { input ->
                Files.newOutputStream(d, java.nio.file.StandardOpenOption.CREATE_NEW, java.nio.file.StandardOpenOption.WRITE).use { output ->
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        onBytes(n.toLong())
                    }
                }
            }
            return Files.size(d) == Files.size(s)
        }
        val ok = try {
            copyOne(src, dst)
        } catch (e: IOException) {
            false
        } catch (e: SecurityException) {
            false
        }
        if (!ok) delete(to)
        ok
    }

    override suspend fun freeSpace(path: String): Long? = withContext(Dispatchers.IO) {
        var p: Path? = Paths.get(path)
        while (p != null && !Files.exists(p)) p = p.parent
        p?.let { runCatching { Files.getFileStore(it).usableSpace }.getOrNull() }?.takeIf { it > 0 }
    }

    override suspend fun makeDirs(path: String): Boolean = withContext(Dispatchers.IO) {
        try {
            Files.createDirectories(Paths.get(path))
            true
        } catch (e: IOException) {
            false
        } catch (e: SecurityException) {
            false
        }
    }

    override suspend fun list(path: String): List<FsEntry> = withContext(Dispatchers.IO) {
        val dir = Paths.get(path)
        if (!Files.isDirectory(dir)) return@withContext emptyList()
        try {
            Files.newDirectoryStream(dir).use { stream ->
                stream.mapNotNull { child ->
                    val name = child.fileName?.toString() ?: return@mapNotNull null
                    entry(child, name, FsPath.join(path, name))
                }
            }
        } catch (e: AccessDeniedException) {
            throw FsAccessException(path, "No permission to read $path", e)
        } catch (e: NoSuchFileException) {
            emptyList()
        } catch (e: NotDirectoryException) {
            emptyList()
        } catch (e: IOException) {
            throw FsAccessException(path, "Cannot read $path", e)
        } catch (e: SecurityException) {
            throw FsAccessException(path, "No permission to read $path", e)
        }
    }

    override suspend fun stat(path: String): FsEntry? = withContext(Dispatchers.IO) {
        val p = Paths.get(path)
        // A root has no name: "/" on Linux and macOS, the drive ("C:/") on Windows.
        val name = p.fileName?.toString() ?: p.root?.toFile()?.fusePath ?: "/"
        entry(p, name, path)
    }

    override suspend fun readBytes(path: String, offset: Long, length: Int): ByteArray? = withContext(Dispatchers.IO) {
        if (offset < 0 || length < 0) return@withContext null
        try {
            java.io.RandomAccessFile(Paths.get(path).toFile(), "r").use { f ->
                if (offset >= f.length()) return@withContext ByteArray(0)
                f.seek(offset)
                val out = ByteArray(minOf(length.toLong(), f.length() - offset).toInt())
                f.readFully(out)
                out
            }
        } catch (e: IOException) {
            null
        } catch (e: SecurityException) {
            null
        }
    }

    override suspend fun readText(path: String, maxBytes: Int): String? = withContext(Dispatchers.IO) {
        val p = Paths.get(path)
        if (!Files.isRegularFile(p)) return@withContext null
        try {
            val bytes = Files.newInputStream(p).use { it.readNBytes(maxBytes.coerceAtLeast(0)) }
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (e: IOException) {
            null
        } catch (e: SecurityException) {
            null
        }
    }

    override suspend fun md5(path: String): String? = withContext(Dispatchers.IO) {
        val p = Paths.get(path)
        if (!Files.isRegularFile(p)) return@withContext null
        try {
            val digest = MessageDigest.getInstance("MD5")
            val buffer = ByteArray(256 * 1024)
            Files.newInputStream(p).use { input ->
                while (true) {
                    ensureActive()
                    val n = input.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (e: IOException) {
            null
        } catch (e: SecurityException) {
            null
        }
    }

    override suspend fun canonical(path: String): String? = withContext(Dispatchers.IO) {
        try {
            // toRealPath fails with "Too many levels of symbolic links" on a loop instead of spinning.
            Paths.get(path).toRealPath().toFile().fusePath
        } catch (e: IOException) {
            null
        } catch (e: SecurityException) {
            null
        }
    }

    private fun entry(p: Path, name: String, fullPath: String): FsEntry? {
        val own = try {
            Files.readAttributes(p, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        } catch (e: IOException) {
            return null
        } catch (e: SecurityException) {
            return null
        }
        if (!own.isSymbolicLink) {
            return FsEntry(
                name = name,
                path = fullPath,
                isDirectory = own.isDirectory,
                sizeBytes = if (own.isRegularFile) own.size() else 0,
                modifiedAt = own.lastModifiedTime().toMillis(),
                isSymlink = false,
            )
        }
        // A link: describe its target when it resolves; a dangling link is an empty non-directory.
        val target = try {
            Files.readAttributes(p, BasicFileAttributes::class.java)
        } catch (e: IOException) {
            null
        } catch (e: SecurityException) {
            null
        }
        return FsEntry(
            name = name,
            path = fullPath,
            isDirectory = target?.isDirectory == true,
            sizeBytes = if (target?.isRegularFile == true) target.size() else 0,
            modifiedAt = (target ?: own).lastModifiedTime().toMillis(),
            isSymlink = true,
        )
    }

    private companion object {
        const val COPY_BUFFER = 1024 * 1024
    }
}
