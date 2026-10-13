package io.github.matiyaaa.fuse.sync

/**
 * Find Where It Saves: a test run that shows Fuse where a game's emulator really keeps its save.
 * Before the game starts, the folders where its save could be are noted (every file and when it
 * last changed); the person makes one save in the game and quits; the files that appeared or
 * changed meanwhile are the save. Fuse then learns the game's folders (a save whose id it can't
 * read), or the emulator's save folder (one it keeps somewhere other than where Fuse looked), the
 * same as choosing it in Settings, Save folders. Anything it can't use is said, and nothing is
 * changed.
 *
 * Only a bounded set of folders is watched ([MAX_DEPTH] levels, [MAX_FILES] files): where the
 * emulator's adapter expects the save and one level above, the emulator's chosen save folder, and
 * the game's own folder (where many emulators keep saves beside the game).
 */
object SaveProbe {
    const val MAX_DEPTH = 4
    const val MAX_FILES = 20_000

    /** Files that change when an emulator closes and are never a save: settings, logs, pictures. */
    private val NOT_SAVES = listOf(
        ".cfg", ".ini", ".json", ".xml", ".toml", ".yaml", ".yml", ".txt", ".log", ".lock",
        ".db-journal", ".png", ".jpg", ".jpeg", ".bmp", ".webp",
    )

    /** The folders watched and every file in them with when it last changed. [truncated] when [MAX_FILES] was reached. */
    data class Snapshot(val roots: List<String>, val times: Map<String, Long>, val truncated: Boolean = false)

    /** What a test run showed. */
    sealed interface Finding {
        /** Nothing appeared or changed in the folders watched. */
        data object Nothing : Finding

        /** The save went where Fuse already looks: [paths]. */
        data class Known(val paths: List<String>) : Finding

        /** A save whose game id can't be read: its own folders in [spot]'s learning folder are [folders]. */
        data class Learned(val format: String, val folders: List<String>, val inFolder: String) : Finding

        /** The emulator keeps its saves in [folder], not where Fuse looked: chosen as its save folder, [paths] are the save. */
        data class Folder(val folder: String, val paths: List<String>) : Finding

        /** Files changed ([seen], at most a few) but in no way Fuse can keep in step yet. */
        data class Unusable(val seen: List<String>) : Finding
    }

    /** The folders to watch for [q]'s save, outermost only (a folder inside another is covered by it). */
    fun roots(q: SaveQuery, env: SaveEnvironment, adapter: SaveAdapter? = SaveAdapters.forEmulator(q.emulatorId)): List<String> {
        val spots = adapter?.let { a -> runCatching { a.locate(q, env) }.getOrDefault(emptyList()) }.orEmpty()
        val near = LinkedHashSet<String>()
        for (s in spots) {
            s.root?.let(near::add)
            s.learnIn?.let(near::add)
            s.files.mapNotNullTo(near) { parent(it.path) }
            near += s.mounts.values
        }
        val all = LinkedHashSet<String>()
        for (p in near) {
            all += p
            // One level up, for a sibling folder the emulator uses instead (saves sorted by core, a renamed folder).
            parent(p)?.takeIf { narrow(it, env) }?.let(all::add)
        }
        SaveAdapters.chosen(env, q)?.let(all::add)
        parent(q.romPath.replace('\\', '/'))?.let(all::add)
        val existing = all.map { it.trimEnd('/') }.filter { it.isNotEmpty() && bounded(it, env) && env.isDirectory(it) }.distinct()
        return existing.filter { r -> existing.none { o -> o != r && r.startsWith("$o/") } }
    }

    /** Every file in [roots] and when it last changed. */
    fun snapshot(roots: List<String>, env: SaveEnvironment): Snapshot {
        val times = LinkedHashMap<String, Long>()
        var truncated = false
        fun walk(dir: String, depth: Int) {
            for (name in env.list(dir)) {
                if (times.size >= MAX_FILES) { truncated = true; return }
                val path = "$dir/$name"
                if (env.isDirectory(path)) {
                    if (depth < MAX_DEPTH && !name.startsWith(".") && !SaveNoise.folder(name)) walk(path, depth + 1)
                } else {
                    times[path] = env.modified(path) ?: 0L
                }
            }
        }
        for (r in roots) walk(r, 1)
        return Snapshot(roots, times, truncated)
    }

    /** The files in [before]'s folders that appeared or changed since, that could be a save. */
    fun changed(before: Snapshot, env: SaveEnvironment): List<String> {
        val now = snapshot(before.roots, env)
        return now.times.filter { (path, at) ->
            val name = path.substringAfterLast('/')
            val was = before.times[path]
            (was == null || at > was) && !SaveNoise.file(name) && NOT_SAVES.none { name.lowercase().endsWith(it) }
        }.keys.sorted()
    }

    /** What [changed] (from a test run of [q]) says about where its save is. */
    fun conclude(q: SaveQuery, env: SaveEnvironment, changed: List<String>, adapter: SaveAdapter? = SaveAdapters.forEmulator(q.emulatorId)): Finding {
        if (changed.isEmpty()) return Finding.Nothing
        val spots = adapter?.let { a -> runCatching { a.locate(q, env) }.getOrDefault(emptyList()) }.orEmpty()
        // A save whose game id can't be read: the game's own folders where each game has one.
        for (s in spots) {
            val inFolder = s.learnIn ?: continue
            if (s.available) continue
            val names = changed.filter { it.startsWith("$inFolder/") }.map { it.removePrefix("$inFolder/").substringBefore('/') }.distinct()
            if (names.size in 1..LEARN_MAX && names.none { it.contains('.') && changed.contains("$inFolder/$it") }) {
                return Finding.Learned(s.format, names, inFolder)
            }
        }
        // Where Fuse looks already.
        val known = changed.filter { p -> spots.any { s -> holds(s, p) } }
        if (known.isNotEmpty()) return Finding.Known(known)
        // Another folder: the one that, chosen as the emulator's save folder, makes the save one Fuse finds.
        if (adapter != null) {
            val folders = changed.mapNotNull(::parent).groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { it.key }
            for (folder in folders) {
                val chosen = object : SaveEnvironment by env {
                    override fun saveFolder(emulator: String): String = folder
                }
                val there = runCatching { adapter.locate(q, chosen) }.getOrDefault(emptyList())
                val paths = changed.filter { p -> there.any { s -> holds(s, p) } }
                if (paths.isNotEmpty()) return Finding.Folder(folder, paths)
            }
        }
        return Finding.Unusable(changed.take(5))
    }

    /** Whether [path] is one of [spot]'s files. */
    private fun holds(spot: SaveSpot, path: String): Boolean {
        if (!spot.available) return false
        if (spot.files.any { it.path == path }) return true
        if (spot.mounts.values.any { path.startsWith("$it/") }) return true
        val root = spot.root ?: return false
        if (!path.startsWith("$root/")) return false
        val inside = path.removePrefix("$root/")
        if (spot.folders.isNotEmpty() && spot.folders.none { inside.startsWith("$it/") || inside == it }) return false
        return spot.only == null || inside.substringAfterLast('/').startsWith(spot.only)
    }

    private fun parent(path: String): String? = path.substringBeforeLast('/', "").takeIf { it.isNotEmpty() }

    /** Whether [dir] can be watched at all: never the home folder, a folder above it, or one just below the system's root. */
    private fun bounded(dir: String, env: SaveEnvironment): Boolean {
        val home = env.home.replace('\\', '/').trimEnd('/')
        if (home.isNotEmpty() && (dir == home || "$home/".startsWith("$dir/"))) return false
        return (home.isNotEmpty() && dir.startsWith("$home/")) || dir.trim('/').split('/').size >= 2
    }

    /**
     * Whether [dir] is narrow enough to watch as a folder above where the save is expected: at least
     * two levels inside the home folder, or three below the system's root.
     */
    private fun narrow(dir: String, env: SaveEnvironment): Boolean {
        val home = env.home.replace('\\', '/').trimEnd('/')
        val depth = dir.trim('/').split('/').size
        return when {
            home.isNotEmpty() && (dir == home || dir.startsWith("$home/")) -> dir != home && dir.removePrefix("$home/").split('/').size >= 2
            else -> depth >= 3
        }
    }

    /** At most this many of a game's own folders are learned from one run, as one game makes. */
    private const val LEARN_MAX = 4
}
