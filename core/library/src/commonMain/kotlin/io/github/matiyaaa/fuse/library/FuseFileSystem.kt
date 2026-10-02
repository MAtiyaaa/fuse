package io.github.matiyaaa.fuse.library

/**
 * One file or directory as reported by a [FuseFileSystem].
 *
 * @property name Last path segment, exactly as on disk.
 * @property path Absolute path using "/" as the separator. For entries returned by
 *   [FuseFileSystem.list] this is the parent path joined with [name] (symlinks are not resolved).
 * @property isDirectory True for directories, and for symlinks that point at a directory.
 * @property sizeBytes File size, or 0 for directories and when unknown.
 * @property modifiedAt Last modification time in epoch millis, or 0 when unknown.
 * @property isSymlink True when the entry itself is a symbolic link.
 */
data class FsEntry(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val sizeBytes: Long = 0,
    val modifiedAt: Long = 0,
    val isSymlink: Boolean = false,
) {
    /** Lower-case extension of [name] without the dot, or "" when there is none. */
    val extension: String get() = FsPath.extension(name)
}

/**
 * Thrown by [FuseFileSystem.list] when a directory exists but cannot be read, for example another
 * app's `Android/data` folder on Android 11+ or a folder without read permission. Callers treat
 * the location as "unknown" rather than "empty".
 */
class FsAccessException(val path: String, message: String? = null, cause: Throwable? = null) :
    Exception(message ?: "Cannot read $path", cause)

/**
 * Read-only file access used by the scanner and the BIOS checker. The apps implement it (java.io
 * on Linux, java.io or SAF on Android); tests use an in-memory fake. Fuse never writes through it.
 *
 * Paths always use "/" as the separator (Android and Linux both do). Implementations must be safe
 * to call from any coroutine and must not block the caller's thread (switch to an IO dispatcher
 * internally when needed).
 */
interface FuseFileSystem {
    /**
     * Direct children of the directory at [path], in no particular order. Returns an empty list
     * when [path] does not exist or is not a directory.
     *
     * @throws FsAccessException when [path] is a directory that cannot be read.
     */
    suspend fun list(path: String): List<FsEntry>

    /** The entry at [path] (following symlinks for [FsEntry.isDirectory]), or null when it does not exist. */
    suspend fun stat(path: String): FsEntry?

    /**
     * Reads at most [maxBytes] bytes of a text file (UTF-8, invalid bytes replaced), or null when
     * the file does not exist or cannot be read. Used for .m3u, .cue, .gdi and shortcut files.
     */
    suspend fun readText(path: String, maxBytes: Int = DEFAULT_TEXT_LIMIT): String?

    /**
     * Up to [length] bytes of the file at [path] from [offset] (fewer at its end), or null when it
     * can't be read or this file system doesn't read bytes. Used to identify discs (a PS2 image's
     * SYSTEM.CNF and boot program); nothing is ever written.
     */
    suspend fun readBytes(path: String, offset: Long, length: Int): ByteArray? = null

    /** Lower-case hex MD5 of the file's content, or null when unsupported or unreadable. */
    suspend fun md5(path: String): String?

    /**
     * The canonical path with every symlink resolved, or null when it cannot be determined.
     * The scanner uses it to avoid walking the same directory twice (symlink loops).
     */
    suspend fun canonical(path: String): String?

    /**
     * Deletes the file at [path], or the folder with everything in it (links are removed, never
     * followed). Only for the user's explicit "delete these games" in Settings, Storage; scanning
     * never writes. Returns false when it could not (no permission, read-only storage); a path that
     * is already gone counts as deleted.
     */
    suspend fun delete(path: String): Boolean = false

    /** Joins [parent] and [child] with a single "/". */
    fun join(parent: String, child: String): String = FsPath.join(parent, child)

    companion object {
        /** Default cap for [readText]; ES-DE injects at most 4096 bytes, playlists are small. */
        const val DEFAULT_TEXT_LIMIT: Int = 256 * 1024
    }
}
