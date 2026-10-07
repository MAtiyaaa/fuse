package io.github.matiyaaa.fuse.library.storage

import io.github.matiyaaa.fuse.library.FsPath
import io.github.matiyaaa.fuse.library.FuseFileSystem
import io.github.matiyaaa.fuse.model.ChildContent
import io.github.matiyaaa.fuse.model.ContentKind
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
 *
 * The game's [content] goes with it the way RomM keeps it: an update in `update/`, DLC in `dlc/`
 * (and so on for each kind), whether it sits beside the game, loose in the game's folder or in
 * another folder altogether. The game's own file is never put in a `base/` folder: RomM makes the
 * game from it, then files the rest under it.
 */
object UploadFiles {
    private val clutter = setOf(".ds_store", "thumbs.db", "desktop.ini")

    suspend fun collect(
        fs: FuseFileSystem,
        location: GameLocation,
        discs: List<Disc>,
        content: List<ChildContent> = emptyList(),
    ): List<UploadFile> {
        val set = GameFiles.resolve(fs, location, discs)
        if (set.isEmpty) return emptyList()
        val isFolder = set.paths.size == 1 && fs.stat(set.paths.single())?.isDirectory == true
        val categorised = content.filter { it.kind != ContentKind.GAME }.associateBy { FsPath.normalize(it.path) }
        val collected = if (isFolder) {
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
        // Updates and DLC loose in the game's folder go in their own folders, as RomM keeps them.
        val files = collected.map { f ->
            val kind = categorised[FsPath.normalize(f.path)]?.kind
            if (kind != null && f.folder.isEmpty()) f.copy(folder = kind.slug) else f
        }.toMutableList()
        // Content kept elsewhere (beside a file game, or in another folder) comes along too.
        val have = files.map { FsPath.normalize(it.path) }.toHashSet()
        for (c in content) {
            if (c.kind == ContentKind.GAME || FsPath.normalize(c.path) in have) continue
            if (c.isDirectory) {
                val out = ArrayList<UploadFile>()
                walk(fs, c.path, "${c.kind.slug}/${FsPath.name(c.path)}", 0, out)
                out.filter { have.add(FsPath.normalize(it.path)) }.forEach(files::add)
            } else {
                val entry = fs.stat(c.path)?.takeIf { !it.isDirectory } ?: continue
                files += UploadFile(entry.path, FsPath.name(entry.path), folder = c.kind.slug, sizeBytes = entry.sizeBytes)
                have += FsPath.normalize(entry.path)
            }
        }
        val lead = if (location.kind == LocationKind.FOLDER || isFolder) location.launchPath else location.path
        val first = files.firstOrNull { FsPath.normalize(it.path) == FsPath.normalize(lead) }
            // A folder game without a launch file: its first file at the top.
            ?: files.firstOrNull { it.folder.isEmpty() }
            ?: return files
        // RomM makes the game from its first file, so that file is never in a base/ folder.
        val top = if (isBaseFolder(first.folder)) first.copy(folder = "") else first
        return listOf(top) + files.filter { it !== first }
    }

    private fun isBaseFolder(folder: String): Boolean =
        folder.isNotEmpty() && '/' !in folder && folder.lowercase().replace(" ", "") in setOf("base", "basegame", "game")

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
