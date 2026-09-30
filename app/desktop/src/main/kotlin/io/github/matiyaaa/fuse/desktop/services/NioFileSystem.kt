package io.github.matiyaaa.fuse.desktop.services

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
 * Read-only [FuseFileSystem] over java.nio. Symlinks are reported, not walked: an entry that is a
 * link to a directory says so ([FsEntry.isDirectory] and [FsEntry.isSymlink]), and the scanner uses
 * [canonical] to skip directories it has already seen, so link loops end. Only [delete] writes.
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
        val name = p.fileName?.toString() ?: "/"
        entry(p, name, path)
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
            Paths.get(path).toRealPath().toString()
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
}
