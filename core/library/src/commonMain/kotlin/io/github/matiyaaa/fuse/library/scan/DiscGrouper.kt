package io.github.matiyaaa.fuse.library.scan

import io.github.matiyaaa.fuse.library.FsEntry
import io.github.matiyaaa.fuse.library.FsPath
import io.github.matiyaaa.fuse.library.FuseFileSystem
import io.github.matiyaaa.fuse.library.parse.FilenameParser
import io.github.matiyaaa.fuse.library.parse.ParsedName
import io.github.matiyaaa.fuse.model.Disc

/** How a [FileGroup] was formed. */
enum class GroupKind {
    /** One file is one game. */
    SINGLE,

    /** Sibling files with disc markers ("(Disc 1)", "(CD2)", "(Disk A)") grouped into one game. */
    MULTI_DISC,

    /** An .m3u playlist that lists the discs; the listed files are hidden. */
    PLAYLIST,
}

/**
 * One game formed from the files of a single directory.
 *
 * @property title Original title: the file stem, the playlist stem, or the stem without its disc
 *   marker for grouped discs ("Final Fantasy VII (Europe)").
 * @property primary The file to launch: the playlist, the first disc, or the single file.
 * @property discs Ordered discs for multi-disc games, empty otherwise.
 * @property members Every file of this directory that belongs to the game (tracks included).
 * @property parsed Parsed name of [primary] (of the first disc for grouped discs).
 */
data class FileGroup(
    val title: String,
    val primary: FsEntry,
    val kind: GroupKind,
    val discs: List<Disc>,
    val members: List<FsEntry>,
    val parsed: ParsedName,
) {
    val sizeBytes: Long get() = members.sumOf { it.sizeBytes }
    val modifiedAt: Long get() = members.maxOfOrNull { it.modifiedAt } ?: primary.modifiedAt
}

/**
 * Result of grouping one directory's files.
 *
 * @property groups Games, in name order.
 * @property hidden Normalised paths of files that belong to another file (cue/gdi tracks, ccd/mds
 *   companions, playlist entries) and must not be listed as games.
 */
data class DiscGrouping(val groups: List<FileGroup>, val hidden: Set<String>)

/**
 * Groups the files of one directory into games: .m3u playlists own the files they list, .cue and
 * .gdi sheets own their tracks, .ccd owns its .img/.sub and .mds its .mdf, and sibling files that
 * only differ by a disc marker become one multi-disc game. Only reads small text files.
 */
class DiscGrouper(private val fs: FuseFileSystem) {
    /**
     * @param entries Direct children of [directory] (folders are ignored).
     * @param isGameFile Decides which files can be games, normally "has a platform extension".
     */
    suspend fun group(directory: String, entries: List<FsEntry>, isGameFile: (FsEntry) -> Boolean): DiscGrouping {
        val files = entries.filter { !it.isDirectory && !ScanRules.isIgnoredFile(it.name) }
        val byPath = files.associateBy { FsPath.normalize(it.path) }
        val byLowerName = files.associateBy { it.name.lowercase() }
        val hidden = LinkedHashSet<String>()
        // Files a sheet or playlist owns, so sizes can include them.
        val owned = HashMap<String, List<FsEntry>>()

        for (file in files) {
            val refs = when (file.extension) {
                "cue" -> fs.readText(file.path)?.let { Playlists.parseCue(it, directory) }.orEmpty()
                "gdi" -> fs.readText(file.path)?.let { Playlists.parseGdi(it, directory) }.orEmpty()
                "ccd" -> companions(file, byLowerName, "img", "sub")
                "mds" -> companions(file, byLowerName, "mdf")
                else -> continue
            }
            val own = refs.filter { FsPath.normalize(it) != FsPath.normalize(file.path) }
            hidden.addAll(own.map(FsPath::normalize))
            owned[file.path] = own.mapNotNull { byPath[FsPath.normalize(it)] }
        }

        val groups = ArrayList<FileGroup>()
        val playlistOwned = HashSet<String>()
        for (file in files.filter { it.extension == "m3u" }) {
            val refs = fs.readText(file.path)?.let { Playlists.parseM3u(it, directory) }.orEmpty()
            if (refs.isEmpty()) continue
            val normalizedRefs = refs.map(FsPath::normalize)
            playlistOwned.add(FsPath.normalize(file.path))
            hidden.addAll(normalizedRefs)
            val memberFiles = normalizedRefs.mapNotNull { byPath[it] }
            val discs = normalizedRefs.mapIndexed { index, path ->
                val parsed = FilenameParser.parse(FsPath.name(path))
                val label = parsed.tags.discNumber?.let { "Disc $it" } ?: FsPath.stem(FsPath.name(path))
                Disc(number = index + 1, label = label, path = path)
            }
            groups += FileGroup(
                title = FsPath.stem(file.name),
                primary = file,
                kind = GroupKind.PLAYLIST,
                discs = discs,
                members = listOf(file) + memberFiles + memberFiles.flatMap { owned[it.path].orEmpty() },
                parsed = FilenameParser.parse(file.name),
            )
        }

        val candidates = files.filter { f ->
            val p = FsPath.normalize(f.path)
            p !in hidden && p !in playlistOwned && isGameFile(f)
        }

        // Discs of one release share their name minus the disc marker, and their extension.
        val parsedByFile = candidates.associateWith { FilenameParser.parse(it.name) }
        val (withDisc, withoutDisc) = candidates.partition { parsedByFile.getValue(it).tags.discNumber != null }
        val discSets = withDisc.groupBy { f -> FilenameParser.withoutDisc(parsedByFile.getValue(f)).lowercase() to f.extension }

        for ((_, set) in discSets) {
            if (set.size == 1) {
                val file = set.single()
                groups += single(file, parsedByFile.getValue(file), owned)
                continue
            }
            val ordered = set.sortedWith(compareBy({ parsedByFile.getValue(it).discSort ?: 0 }, { it.name.lowercase() }))
            val first = ordered.first()
            val firstParsed = parsedByFile.getValue(first)
            groups += FileGroup(
                title = FilenameParser.withoutDisc(firstParsed),
                primary = first,
                kind = GroupKind.MULTI_DISC,
                discs = ordered.mapIndexed { index, f ->
                    val n = parsedByFile.getValue(f).tags.discNumber ?: (index + 1)
                    Disc(number = index + 1, label = "Disc $n", path = f.path)
                },
                members = ordered + ordered.flatMap { owned[it.path].orEmpty() },
                parsed = firstParsed,
            )
        }
        for (file in withoutDisc) groups += single(file, parsedByFile.getValue(file), owned)

        return DiscGrouping(groups.sortedBy { it.title.lowercase() }, hidden)
    }

    private fun single(file: FsEntry, parsed: ParsedName, owned: Map<String, List<FsEntry>>) = FileGroup(
        title = parsed.stem,
        primary = file,
        kind = GroupKind.SINGLE,
        discs = emptyList(),
        members = listOf(file) + owned[file.path].orEmpty(),
        parsed = parsed,
    )

    private fun companions(file: FsEntry, byLowerName: Map<String, FsEntry>, vararg exts: String): List<String> {
        val stem = FsPath.stem(file.name).lowercase()
        return exts.mapNotNull { byLowerName["$stem.$it"]?.path }
    }
}
