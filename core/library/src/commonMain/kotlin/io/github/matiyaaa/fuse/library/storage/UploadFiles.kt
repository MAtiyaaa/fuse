package io.github.matiyaaa.fuse.library.storage

import io.github.matiyaaa.fuse.library.FsPath
import io.github.matiyaaa.fuse.library.FuseFileSystem
import io.github.matiyaaa.fuse.model.Disc
import io.github.matiyaaa.fuse.model.GameLocation
import io.github.matiyaaa.fuse.model.LocationKind
import io.github.matiyaaa.fuse.model.UploadFile

/**
 * The files to upload for a game, as RomM should keep them: the file that names the game first
 * (the launch file, or the .m3u of a disc set), then the rest. A file game brings its discs and
 * tracks, found the way [GameFiles] finds them. A folder game brings everything inside it, each
 * file with its folder relative to the game ("dlc", "update", "dlc/Extra"), so RomM files DLC and
 * updates under the game. System clutter (".DS_Store", "Thumbs.db", "._" files) is left out.
 */
object UploadFiles {
    private val clutter = setOf(".ds_store", "thumbs.db", "desktop.ini")

    suspend fun collect(fs: FuseFileSystem, location: GameLocation, discs: List<Disc>): List<UploadFile> {
        val set = GameFiles.resolve(fs, location, discs)
        if (set.isEmpty) return emptyList()
        val isFolder = set.paths.size == 1 && fs.stat(set.paths.single())?.isDirectory == true
        val files = if (isFolder) {
            val root = set.paths.single()
            val out = ArrayList<UploadFile>()
            walk(fs, root, "", 0, out)
            out
        } else {
            set.paths.mapNotNull { path ->
                val entry = fs.stat(path)?.takeIf { !it.isDirectory } ?: return@mapNotNull null
                UploadFile(entry.path, FsPath.name(entry.path), folder = "", sizeBytes = entry.sizeBytes)
            }
        }
        val lead = if (location.kind == LocationKind.FOLDER || isFolder) location.launchPath else location.path
        val first = files.firstOrNull { FsPath.normalize(it.path) == FsPath.normalize(lead) }
            // A folder game without a launch file: its first file at the top.
            ?: files.firstOrNull { it.folder.isEmpty() }
        return if (first == null) files else listOf(first) + (files - first)
    }

    private suspend fun walk(fs: FuseFileSystem, dir: String, rel: String, depth: Int, out: MutableList<UploadFile>) {
        if (depth > 12) return
        val children = runCatching { fs.list(dir) }.getOrDefault(emptyList()).sortedBy { it.name.lowercase() }
        // Files at each level come before the folders below it.
        for (c in children.filter { !it.isDirectory }) {
            if (isClutter(c.name)) continue
            out += UploadFile(c.path, c.name, rel, c.sizeBytes)
        }
        for (c in children.filter { it.isDirectory }) {
            if (c.name.startsWith(".")) continue
            walk(fs, c.path, if (rel.isEmpty()) c.name else "$rel/${c.name}", depth + 1, out)
        }
    }

    private fun isClutter(name: String): Boolean = name.lowercase() in clutter || name.startsWith("._")
}
