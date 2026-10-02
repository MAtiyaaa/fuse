package io.github.matiyaaa.fuse.library.scan

import io.github.matiyaaa.fuse.library.FsPath
import io.github.matiyaaa.fuse.library.FuseFileSystem
import io.github.matiyaaa.fuse.model.Disc

/** A file a playlist or cue sheet names that isn't there. */
data class MissingReference(
    /** The .m3u, .cue or .gdi that names it. */
    val owner: String,
    /** Where it was looked for. */
    val path: String,
    /** The disc it belongs to, when the game has several. */
    val disc: Disc? = null,
)

/**
 * Checks that the files a game's playlist or sheets name are all there: every disc of a multi-disc
 * game, and every track a .cue or .gdi lists. Read-only; only small text files are read. A sheet
 * that can't be read is not reported (unknown is not missing).
 */
class PlaylistCheck(private val fs: FuseFileSystem) {
    suspend fun check(launchPath: String, discs: List<Disc>): List<MissingReference> {
        val out = ArrayList<MissingReference>()
        val owner = launchPath.takeIf { FsPath.extension(it) == "m3u" }
        // Discs first: a missing disc says more than its missing tracks.
        for (disc in discs) {
            if (fs.stat(disc.path) == null) {
                out += MissingReference(owner ?: disc.path, disc.path, disc)
                continue
            }
            out += sheet(disc.path, disc)
        }
        if (discs.isEmpty()) out += sheet(launchPath, null)
        return out.distinctBy { it.path }
    }

    /** Tracks a .cue or .gdi at [path] names that aren't there. */
    private suspend fun sheet(path: String, disc: Disc?): List<MissingReference> {
        val ext = FsPath.extension(path)
        if (ext != "cue" && ext != "gdi") return emptyList()
        val text = fs.readText(path) ?: return emptyList()
        val dir = FsPath.parent(path) ?: return emptyList()
        val refs = if (ext == "cue") Playlists.parseCue(text, dir) else Playlists.parseGdi(text, dir)
        return refs.filter { fs.stat(it) == null }.map { MissingReference(path, it, disc) }
    }
}
