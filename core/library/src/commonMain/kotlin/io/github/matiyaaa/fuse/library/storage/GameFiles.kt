package io.github.matiyaaa.fuse.library.storage

import io.github.matiyaaa.fuse.library.FsPath
import io.github.matiyaaa.fuse.library.FuseFileSystem
import io.github.matiyaaa.fuse.library.scan.Playlists
import io.github.matiyaaa.fuse.model.Disc
import io.github.matiyaaa.fuse.model.FolderInterpretation
import io.github.matiyaaa.fuse.model.GameLocation
import io.github.matiyaaa.fuse.model.LocationKind

/**
 * What a game is made of on disk: [paths] to delete (files, or one folder for folder games) and
 * their total size. Missing entries are left out.
 */
data class GameFileSet(val paths: List<String>, val sizeBytes: Long, val fileCount: Int) {
    val isEmpty: Boolean get() = paths.isEmpty()
}

/**
 * Finds every file that belongs to a game, the way the scanner grouped it: a folder game is its
 * folder; a disc set is each disc; a .m3u owns the files it lists, a .cue or .gdi its tracks, a
 * .ccd its .img and .sub, and a .mds its .mdf. Save files and other things next to the game are
 * never included.
 */
object GameFiles {
    private val folderKinds = setOf(
        FolderInterpretation.FOLDER_IS_GAME,
        FolderInterpretation.MULTI_FILE_GAME,
        FolderInterpretation.FOLDER_BROWSER,
    )

    suspend fun resolve(fs: FuseFileSystem, location: GameLocation, discs: List<Disc>): GameFileSet {
        if (location.kind == LocationKind.FOLDER || location.interpretation in folderKinds) {
            val (size, count) = walk(fs, location.path, depth = 0)
            return if (count == 0 && fs.stat(location.path) == null) GameFileSet(emptyList(), 0, 0)
            else GameFileSet(listOf(location.path), size, count)
        }
        val seen = LinkedHashSet<String>()
        suspend fun add(path: String, depth: Int) {
            val key = FsPath.normalize(path)
            if (!seen.add(key) || depth > 3) return
            val dir = FsPath.parent(path) ?: ""
            val base = FsPath.name(path)
            when (base.substringAfterLast('.', "").lowercase()) {
                "m3u", "m3u8" -> fs.readText(path)?.let { Playlists.parseM3u(it, dir) }.orEmpty().forEach { add(it, depth + 1) }
                "cue" -> fs.readText(path)?.let { Playlists.parseCue(it, dir) }.orEmpty().forEach { add(it, depth + 1) }
                "gdi" -> fs.readText(path)?.let { Playlists.parseGdi(it, dir) }.orEmpty().forEach { add(it, depth + 1) }
                "ccd" -> listOf("img", "sub").forEach { add(FsPath.join(dir, base.substringBeforeLast('.') + "." + it), depth + 1) }
                "mds" -> add(FsPath.join(dir, base.substringBeforeLast('.') + ".mdf"), depth + 1)
            }
        }
        add(location.path, 0)
        discs.forEach { add(it.path, 0) }
        var size = 0L
        val present = ArrayList<String>()
        for (key in seen) {
            val entry = fs.stat(key) ?: continue
            if (entry.isDirectory) continue
            present += entry.path
            size += entry.sizeBytes
        }
        return GameFileSet(present, size, present.size)
    }

    /** Total size and file count under a folder (a few levels of links are not followed). */
    private suspend fun walk(fs: FuseFileSystem, path: String, depth: Int): Pair<Long, Int> {
        if (depth > 24) return 0L to 0
        val children = runCatching { fs.list(path) }.getOrDefault(emptyList())
        var size = 0L
        var count = 0
        for (c in children) {
            if (c.isDirectory) {
                val (s, n) = walk(fs, c.path, depth + 1)
                size += s
                count += n
            } else {
                size += c.sizeBytes
                count++
            }
        }
        return size to count
    }
}
