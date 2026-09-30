package io.github.matiyaaa.fuse.library.scan

import io.github.matiyaaa.fuse.library.FsEntry
import io.github.matiyaaa.fuse.library.FsPath
import io.github.matiyaaa.fuse.library.parse.NameFlags
import io.github.matiyaaa.fuse.library.parse.Serials
import io.github.matiyaaa.fuse.model.ChildContent
import io.github.matiyaaa.fuse.model.ContentKind

/**
 * Heuristics for update, DLC and other related files that sit next to a game instead of in RomM
 * category folders (the usual Switch layout):
 * - a name marked `[UPD]`, `(Update)`, a Switch update version (`[v131072]`, any positive
 *   multiple of 65536) or a Switch update title id (`...800`) is an update;
 * - a name marked `[DLC]`, `(Add-On)` or a Switch DLC title id is DLC;
 * - it belongs to the base game with the matching Switch base title id, otherwise to the base
 *   game whose title is the longest prefix of its own ("Zelda BOTW" owns "Zelda BOTW - Master Trials").
 * Anything that matches no base game stays a game of its own, so nothing disappears.
 */
internal object LooseContent {
    private val patchExtensions = setOf("ips", "bps", "ups", "xdelta", "ppf", "aps")
    private val manualExtensions = setOf("pdf", "cbz", "cbr")
    private val cheatExtensions = setOf("cht")

    /** UPDATE or DLC for marked groups, null for ordinary games. */
    fun markerKind(group: FileGroup): ContentKind? = when {
        group.parsed.isUpdate -> ContentKind.UPDATE
        group.parsed.isDlc -> ContentKind.DLC
        else -> null
    }

    /** The child kind of a non-main file inside a multi-file game folder. */
    fun childKind(group: FileGroup): ContentKind {
        markerKind(group)?.let { return it }
        val flags = group.parsed.tags.flags
        return when {
            NameFlags.TRANSLATION in flags -> ContentKind.TRANSLATION
            NameFlags.HACK in flags -> ContentKind.HACK
            NameFlags.DEMO in flags || NameFlags.SAMPLE in flags -> ContentKind.DEMO
            NameFlags.PROTO in flags || NameFlags.BETA in flags || NameFlags.ALPHA in flags -> ContentKind.PROTOTYPE
            else -> ContentKind.GAME
        }
    }

    /** Child kind for a non-game file at the root of a multi-file game (patches, manuals, cheats). */
    fun looseFileKind(file: FsEntry): ContentKind? = when (file.extension) {
        in patchExtensions -> ContentKind.PATCH
        in manualExtensions -> ContentKind.MANUAL
        in cheatExtensions -> ContentKind.CHEAT
        else -> null
    }

    fun child(kind: ContentKind, group: FileGroup) = ChildContent(
        kind = kind,
        name = group.primary.name,
        path = group.primary.path,
        isDirectory = false,
        sizeBytes = group.sizeBytes,
    )

    fun child(kind: ContentKind, entry: FsEntry) = ChildContent(
        kind = kind,
        name = entry.name,
        path = entry.path,
        isDirectory = entry.isDirectory,
        sizeBytes = entry.sizeBytes,
    )

    /**
     * Attaches marked update/DLC groups among [groups] (the files of one directory) to their base
     * game. Returns every remaining game with the children it received.
     */
    fun attach(groups: List<FileGroup>): List<Pair<FileGroup, List<ChildContent>>> {
        val bases = groups.filter { markerKind(it) == null }
        val children = LinkedHashMap<FileGroup, MutableList<ChildContent>>()
        bases.forEach { children[it] = mutableListOf() }
        val leftovers = ArrayList<FileGroup>()
        for (marked in groups.filter { markerKind(it) != null }) {
            val owner = ownerOf(marked, bases)
            if (owner == null) {
                leftovers += marked
            } else {
                children.getValue(owner) += child(markerKind(marked)!!, marked)
            }
        }
        return children.map { (g, c) -> g to c.toList() } + leftovers.map { it to emptyList() }
    }

    private fun ownerOf(marked: FileGroup, bases: List<FileGroup>): FileGroup? {
        marked.parsed.tags.serial?.let(Serials::switchBaseTitleId)?.let { baseId ->
            bases.firstOrNull { it.parsed.tags.serial == baseId }?.let { return it }
        }
        val key = titleKey(marked.parsed.baseTitle)
        return bases
            .filter { base -> titleKey(base.parsed.baseTitle).let { it.isNotEmpty() && (key == it || key.startsWith("$it ")) } }
            .maxByOrNull { titleKey(it.parsed.baseTitle).length }
    }

    /** Lower-case alphanumeric words, for comparing titles loosely. */
    fun titleKey(title: String): String =
        title.lowercase().map { if (it.isLetterOrDigit()) it else ' ' }.joinToString("").split(' ')
            .filter { it.isNotEmpty() }.joinToString(" ")

    /** True when a folder's name and a game title name the same thing (either contains the other). */
    fun sameTitle(folderName: String, title: String): Boolean {
        val a = titleKey(folderName)
        val b = titleKey(title)
        if (a.isEmpty() || b.isEmpty()) return false
        return a == b || a.contains(b) || b.contains(a)
    }

    fun isHidden(path: String, hidden: Set<String>) = FsPath.normalize(path) in hidden
}
