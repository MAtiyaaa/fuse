package io.github.matiyaaa.fuse.library.scan

import io.github.matiyaaa.fuse.library.FsPath
import io.github.matiyaaa.fuse.library.parse.SwitchNames
import io.github.matiyaaa.fuse.library.parse.SwitchTitleKind
import io.github.matiyaaa.fuse.model.ChildContent
import io.github.matiyaaa.fuse.model.ContentKind
import io.github.matiyaaa.fuse.model.LocationKind
import io.github.matiyaaa.fuse.model.ScannedGame

/**
 * A Switch library as people keep it: each game with its updates and DLC beside it, in folders of
 * their own, flat in one folder, in `updates/` and `dlc/` folders, or all of these at once, with
 * copies of the same files in several places. One pass over a system's games:
 *
 * 1. Copies of the same game (the same title id, or the same name, and the same size) become one:
 *    the copy in a folder of its own, with the most beside it, stays, and takes the others' content.
 *    A different version or region is a different game and stays.
 * 2. Each update and DLC that stands on its own joins its game, wherever it is: by title id first,
 *    then by name, in its own folder first. One whose game isn't anywhere stays a game of its own,
 *    so nothing disappears.
 * 3. A game's updates and DLC are told apart the same way, so a copy of an update counts once.
 *
 * Everything merged into another game is reported in [absorbed]: entries Fuse kept for those
 * files before (as games) are forgotten instead of shown as missing.
 */
internal object SwitchContent {
    val platforms = setOf("switch", "switch-2")

    /** UPDATE or DLC for a Switch file, from its name, or from the folder holding it (`updates/`, `dlc/`). */
    fun kindOf(fileName: String, parentName: String?): ContentKind? {
        parentName?.let { folderKind(it) }?.let { return it }
        return when (SwitchNames.read(fileName).kind) {
            SwitchTitleKind.UPDATE -> ContentKind.UPDATE
            SwitchTitleKind.DLC -> ContentKind.DLC
            else -> null
        }
    }

    /** UPDATE or DLC for a folder that holds only those (`updates`, `Update`, `DLC`, `dlcs`). */
    fun folderKind(name: String): ContentKind? =
        ContentKind.ofFolder(name)?.takeIf { it == ContentKind.UPDATE || it == ContentKind.DLC }

    /** The title words a Switch file's name gives its game. */
    fun titleKey(fileName: String): String = SwitchNames.read(fileName).key

    /**
     * Brings [games] (one system's, from one walk) together as described above. [sizeOf] gives a
     * game file's own size (a folder game's size counts everything in it). [forced] gives the kind
     * of files from folders the person set aside for updates or DLC.
     */
    fun consolidate(
        games: List<ScannedGame>,
        sizeOf: (String) -> Long?,
        forced: Map<String, ContentKind> = emptyMap(),
        absorbed: MutableSet<String>,
        absorbedBy: MutableMap<String, String> = mutableMapOf(),
    ): List<ScannedGame> {
        if (games.size < 2) return games
        val names = HashMap<String, SwitchNames.Name>()
        fun nameOf(path: String) = names.getOrPut(path) { SwitchNames.read(FsPath.name(path)) }
        fun kindOf(g: ScannedGame): ContentKind? {
            if (g.kind != LocationKind.FILE) return null
            forced[FsPath.normalize(g.path)]?.let { return it }
            FsPath.parent(g.path)?.let { folderKind(FsPath.name(it)) }?.let { return it }
            return when (nameOf(g.launchPath).kind) {
                SwitchTitleKind.UPDATE -> ContentKind.UPDATE
                SwitchTitleKind.DLC -> ContentKind.DLC
                else -> null
            }
        }

        val loose = games.filter { kindOf(it) != null && it.content.isEmpty() }.toMutableList()
        val looseSet = loose.map { it.path }.toSet()
        var bases = games.filter { it.path !in looseSet }

        // A pile of games (a folder holding many) is the copy to lose.
        val crowd = games.groupingBy { FsPath.parent(it.path).orEmpty() }.eachCount()

        // 1. Copies of the same game.
        val keepers = LinkedHashMap<String, ScannedGame>()
        for (g in bases.sortedWith(keeperOrder(crowd))) {
            val id = identity(g, nameOf(g.launchPath), sizeOf(g.launchPath) ?: g.sizeBytes)
            val kept = keepers[id]
            if (kept == null) {
                keepers[id] = g
            } else {
                keepers[id] = kept.copy(content = kept.content + g.content)
                absorbed += g.path
                absorbedBy[g.path] = kept.path
            }
        }
        bases = keepers.values.toList()

        // A smaller file named after a game with more in brackets ("Game [New Uniform Set]") is its DLC.
        val dlcLike = bases.filter { g ->
            val n = nameOf(g.launchPath)
            g.kind == LocationKind.FILE && g.content.isEmpty() && n.kind == null && n.extra && n.titleId == null &&
                bases.any { o -> o !== g && !nameOf(o.launchPath).extra && nameOf(o.launchPath).key == n.key && (sizeOf(o.launchPath) ?: o.sizeBytes) > g.sizeBytes }
        }
        if (dlcLike.isNotEmpty()) {
            val moved = dlcLike.map { it.path }.toSet()
            bases = bases.filter { it.path !in moved }
            loose += dlcLike
        }
        fun kindOfLoose(g: ScannedGame): ContentKind = kindOf(g) ?: ContentKind.DLC

        // What each game already holds, for knowing a copy of it elsewhere.
        val held = HashMap<String, ScannedGame>()
        for (b in bases) for (c in b.content) {
            if (c.kind != ContentKind.UPDATE && c.kind != ContentKind.DLC) continue
            held.putIfAbsent(copyKey(c.kind, nameOf(c.path), c.sizeBytes), b)
        }

        // 2. Updates and DLC on their own join their game.
        val extra = HashMap<String, MutableList<ChildContent>>()
        val alone = ArrayList<ScannedGame>()
        for (x in loose) {
            val kind = kindOfLoose(x)
            val owner = held[copyKey(kind, nameOf(x.launchPath), x.sizeBytes)] ?: ownerOf(x, bases, ::nameOf)
            if (owner == null) {
                alone += x
                continue
            }
            extra.getOrPut(owner.path) { mutableListOf() } +=
                ChildContent(kind, FsPath.name(x.path), x.path, isDirectory = false, sizeBytes = x.sizeBytes)
            absorbed += x.path
            absorbedBy[x.path] = owner.path
        }

        // 3. Each game's content once; copies of loose updates and DLC with no game, once.
        val merged = bases.map { g ->
            val all = g.content + extra[g.path].orEmpty()
            if (all.isEmpty()) g else g.copy(content = distinctContent(g, all, ::nameOf, absorbed, absorbedBy))
        }
        val single = LinkedHashMap<String, ScannedGame>()
        for (x in alone.sortedWith(keeperOrder(crowd))) {
            val key = copyKey(kindOfLoose(x), nameOf(x.launchPath), x.sizeBytes)
            val kept = single[key]
            if (kept != null) {
                absorbed += x.path
                absorbedBy[x.path] = kept.path
            } else single[key] = x
        }
        val keptPaths = (merged.map { it.path } + single.values.map { it.path }).toSet()
        absorbed.removeAll(keptPaths)
        // Consolidation may run at several folder levels. Resolve redirects to the final title.
        for (path in absorbedBy.keys.toList()) {
            var owner = absorbedBy.getValue(path)
            val visited = hashSetOf(path)
            while (visited.add(owner)) owner = absorbedBy[owner] ?: break
            if (owner in keptPaths && path !in keptPaths) absorbedBy[path] = owner
        }
        absorbedBy.keys.removeAll(keptPaths)
        // Keep the order games were found in.
        val byPath = (merged + single.values).associateBy { it.path }
        return games.mapNotNull { byPath[it.path] }
    }

    /** What makes two copies the same game: its title id (or name), version and size. */
    private fun identity(g: ScannedGame, n: SwitchNames.Name, size: Long): String {
        val who = n.titleId ?: n.fullKey.ifEmpty { FsPath.name(g.path).lowercase() }
        val version = n.version?.takeUnless { it == "0" }.orEmpty()
        return "$who|$version|$size|${n.bundle}"
    }

    /** Two copies of one update or DLC: the same kind, id (or name), version and size. */
    private fun copyKey(kind: ContentKind, n: SwitchNames.Name, size: Long): String =
        "$kind|${n.titleId ?: n.fullKey}|${n.version.orEmpty()}|$size"

    /** The copy to keep comes first: a folder of its own, a folder named for it, a folder with fewer games, more content. */
    private fun keeperOrder(crowd: Map<String, Int>): Comparator<ScannedGame> =
        compareByDescending<ScannedGame> { it.kind == LocationKind.FOLDER }
            .thenByDescending { ownFolder(it) }
            .thenBy { crowd[FsPath.parent(it.path).orEmpty()] ?: 0 }
            .thenByDescending { it.content.size }
            .thenBy { it.path.lowercase() }

    /** True when the folder a file game sits in is named after it (`Balatro/Balatro.nsp`), not a pile of games. */
    private fun ownFolder(g: ScannedGame): Boolean {
        val parent = FsPath.parent(g.path) ?: return false
        return LooseContent.sameTitle(FsPath.name(parent), SwitchNames.read(FsPath.name(g.launchPath)).key)
    }

    private fun ownerOf(x: ScannedGame, bases: List<ScannedGame>, nameOf: (String) -> SwitchNames.Name): ScannedGame? {
        val n = nameOf(x.launchPath)
        val parent = FsPath.parent(x.path)
        // Beside it first (its game's file in the same folder, or the game folder it sits in), and a
        // plain game before one file holding everything.
        fun nearest(candidates: List<ScannedGame>): ScannedGame? =
            candidates.sortedWith(
                compareByDescending<ScannedGame> { FsPath.parent(it.launchPath) == parent || it.path == parent }
                    .thenBy { nameOf(it.launchPath).bundle },
            ).firstOrNull()

        n.baseId?.let { baseId ->
            nearest(bases.filter { nameOf(it.launchPath).titleId == baseId })?.let { return it }
        }
        val key = n.key
        if (key.isEmpty()) return null
        val keyed = bases.map { it to nameOf(it.launchPath).key.ifEmpty { SwitchNames.read(FsPath.name(it.path)).key } }
        // The same title, then the longest title its name starts with, then the one title starting with its name.
        nearest(keyed.filter { it.second == key }.map { it.first })?.let { return it }
        keyed.filter { (_, k) -> k.isNotEmpty() && key.startsWith("$k ") }
            .groupBy { it.second.length }.maxByOrNull { it.key }?.value?.map { it.first }
            ?.let { nearest(it) }?.let { return it }
        val longer = keyed.filter { (_, k) -> k.startsWith("$key ") }.map { it.first }
        return longer.singleOrNull()
    }

    /** [all] without copies: the same kind, id (or name), version and size count once, the one in the game's folder first. */
    private fun distinctContent(
        game: ScannedGame,
        all: List<ChildContent>,
        nameOf: (String) -> SwitchNames.Name,
        absorbed: MutableSet<String>,
        absorbedBy: MutableMap<String, String>,
    ): List<ChildContent> {
        val inside = all.sortedByDescending { it.path.startsWith(game.path.trimEnd('/') + "/") }
        val seen = HashSet<String>()
        val out = ArrayList<ChildContent>()
        for (c in inside) {
            if (c.kind != ContentKind.UPDATE && c.kind != ContentKind.DLC) {
                if (seen.add("other|${c.path}")) out += c
                continue
            }
            val key = copyKey(c.kind, nameOf(c.path), c.sizeBytes)
            if (seen.add(key)) out += c else {
                absorbed += c.path
                absorbedBy[c.path] = game.path
            }
        }
        // Keep the order they were found in.
        return all.filter { c -> out.any { it === c } }
    }
}
