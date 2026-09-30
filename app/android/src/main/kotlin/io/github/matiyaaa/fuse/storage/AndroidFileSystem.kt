package io.github.matiyaaa.fuse.storage

import android.os.Build
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructStat
import io.github.matiyaaa.fuse.library.FsAccessException
import io.github.matiyaaa.fuse.library.FsEntry
import io.github.matiyaaa.fuse.library.FuseFileSystem
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.security.MessageDigest

/**
 * Read-only [FuseFileSystem] over `java.io` and `stat`. With All files access every folder on shared
 * storage is readable; without it, or for other apps' `Android/data` on Android 11+, listing a
 * folder that exists throws [FsAccessException] so the scanner reports "unknown", never "empty".
 * Only [delete] writes, for games the user deletes in Settings, Storage.
 */
class AndroidFileSystem(
    private val volumes: () -> List<Volume>,
    private val ownPackage: String,
    /** Whether Fuse may read every file on shared storage (All files access, or READ_EXTERNAL_STORAGE before Android 11). */
    private val hasStorageAccess: () -> Boolean,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : FuseFileSystem {

    override suspend fun list(path: String): List<FsEntry> = withContext(io) {
        if (isPrivateAppFolder(path)) throw FsAccessException(path, "Android does not let other apps read $path")
        val self = try {
            Os.lstat(path)
        } catch (e: ErrnoException) {
            if (e.errno == OsConstants.EACCES || e.errno == OsConstants.EPERM) throw FsAccessException(path, cause = e)
            return@withContext emptyList()
        }
        if (!isDirectory(path, self)) return@withContext emptyList()
        // Without storage access Android still lists folders and media but hides other files, so the
        // listing would look complete while missing every game: report it as unreadable instead.
        if (isOnSharedStorage(path) && !hasStorageAccess()) {
            throw FsAccessException(path, "Fuse needs All files access to read $path")
        }
        val names = try {
            File(path).list()
        } catch (e: SecurityException) {
            null
        } ?: throw FsAccessException(path)
        val base = path.trimEnd('/')
        names.mapNotNull { name -> entry(name, "$base/$name") }
    }

    /**
     * The one write: deleting a game's files when the user asks in Settings, Storage. Links are
     * removed, never followed, so nothing outside the game's folder can go.
     */
    override suspend fun delete(path: String): Boolean = withContext(io) {
        if (isPrivateAppFolder(path)) return@withContext false
        fun remove(p: String): Boolean {
            val st = try {
                Os.lstat(p)
            } catch (e: ErrnoException) {
                return e.errno == OsConstants.ENOENT
            }
            if (OsConstants.S_ISDIR(st.st_mode)) {
                val names = File(p).list() ?: return false
                if (!names.all { remove("${p.trimEnd('/')}/$it") }) return false
            }
            return try {
                Os.remove(p)
                true
            } catch (e: ErrnoException) {
                e.errno == OsConstants.ENOENT
            }
        }
        try {
            remove(path)
        } catch (e: SecurityException) {
            false
        }
    }

    override suspend fun stat(path: String): FsEntry? = withContext(io) {
        entry(path.trimEnd('/').substringAfterLast('/').ifEmpty { path }, path)
    }

    override suspend fun readText(path: String, maxBytes: Int): String? = withContext(io) {
        if (maxBytes <= 0) return@withContext ""
        try {
            val file = File(path)
            if (!file.isFile) return@withContext null
            FileInputStream(file).use { input ->
                val buffer = ByteArray(minOf(maxBytes.toLong(), file.length().coerceAtLeast(1)).toInt().coerceAtLeast(1))
                var total = 0
                while (total < buffer.size) {
                    val n = input.read(buffer, total, buffer.size - total)
                    if (n < 0) break
                    total += n
                }
                // Invalid UTF-8 is replaced with U+FFFD by the decoder.
                String(buffer, 0, total, Charsets.UTF_8)
            }
        } catch (e: IOException) {
            null
        } catch (e: SecurityException) {
            null
        }
    }

    override suspend fun md5(path: String): String? = withContext(io) {
        try {
            val digest = MessageDigest.getInstance("MD5")
            FileInputStream(path).use { input ->
                val buffer = ByteArray(128 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                }
            }
            digest.digest().toHex()
        } catch (e: IOException) {
            null
        } catch (e: SecurityException) {
            null
        }
    }

    override suspend fun canonical(path: String): String? = withContext(io) {
        try {
            File(path).canonicalPath
        } catch (e: IOException) {
            null
        } catch (e: SecurityException) {
            null
        }
    }

    private fun entry(name: String, path: String): FsEntry? {
        val link = lstat(path) ?: return null
        val isLink = OsConstants.S_ISLNK(link.st_mode)
        // Follow symlinks for type and size; a broken link is shown as an empty file.
        val target = if (isLink) followStat(path) ?: link else link
        val isDir = OsConstants.S_ISDIR(target.st_mode)
        return FsEntry(
            name = name,
            path = path,
            isDirectory = isDir,
            sizeBytes = if (isDir) 0 else target.st_size,
            modifiedAt = target.mtimeMillis(),
            isSymlink = isLink,
        )
    }

    private fun isDirectory(path: String, st: StructStat): Boolean =
        if (OsConstants.S_ISLNK(st.st_mode)) followStat(path)?.let { OsConstants.S_ISDIR(it.st_mode) } == true else OsConstants.S_ISDIR(st.st_mode)

    private fun lstat(path: String): StructStat? = try {
        Os.lstat(path)
    } catch (e: ErrnoException) {
        null
    }

    private fun followStat(path: String): StructStat? = try {
        Os.stat(path)
    } catch (e: ErrnoException) {
        null
    }

    private fun StructStat.mtimeMillis(): Long = st_mtim.tv_sec * 1000 + st_mtim.tv_nsec / 1_000_000

    /** On internal storage or a card, outside Fuse's own app-specific folders. */
    private fun isOnSharedStorage(path: String): Boolean = volumes().any { v ->
        val root = v.root.trimEnd('/')
        StoragePaths.isInside(path, root) &&
            OWN_FOLDERS.none { StoragePaths.isInside(path, "$root/$it/$ownPackage") }
    }

    /** Other apps' `Android/data` and `Android/obb` folders, which Android 11+ hides from Fuse. */
    private fun isPrivateAppFolder(path: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        return volumes().any { v ->
            val root = v.root.trimEnd('/')
            listOf("$root/Android/data", "$root/Android/obb").any { hidden ->
                StoragePaths.isInside(path, hidden) && !StoragePaths.isInside(path, "$hidden/$ownPackage")
            }
        }
    }

    private companion object {
        val OWN_FOLDERS = listOf("Android/data", "Android/obb", "Android/media")
    }
}

internal fun ByteArray.toHex(): String {
    val chars = "0123456789abcdef"
    val out = StringBuilder(size * 2)
    for (b in this) {
        val v = b.toInt() and 0xff
        out.append(chars[v ushr 4]).append(chars[v and 0x0f])
    }
    return out.toString()
}
