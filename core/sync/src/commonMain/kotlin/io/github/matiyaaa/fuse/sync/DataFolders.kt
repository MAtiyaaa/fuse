package io.github.matiyaaa.fuse.sync

/**
 * Finds an emulator's data folder wherever the person put it. On Android many emulators ask for a
 * folder on first start (Azahar, PPSSPP, Dolphin, DuckStation, ARMSX2, Flycast) and keep the
 * answer in their own private settings, which no other app can read. So Fuse looks for the layout
 * only that emulator's folder has (its [marker]) in the person's storage and on every card: two
 * levels down everywhere, three under folders named for emulation. System folders (photos, music,
 * other apps' private storage) are never entered. What it finds is kept a while by the environment,
 * so a launch never walks storage twice.
 */
internal object DataFolders {
    /** Folders that never hold an emulator's data, skipped by name (case ignored). */
    private val SKIP = setOf(
        "android", "dcim", "pictures", "movies", "music", "notifications", "ringtones", "alarms",
        "podcasts", "audiobooks", "recordings", "screenshots", "lost.dir", "\$recycle.bin",
    )

    /** How deep below a storage root a data folder may be: two levels anywhere, three under an emulation-named folder. */
    private const val PLAIN_LEVEL = 2
    private const val MAX_LEVEL = 3

    /** Names that suggest emulation, under which Fuse looks one level deeper. */
    private val DEEPER = listOf("emu", "emulation", "emulator", "roms", "saves", "games", "retro", "handheld")

    /**
     * The data folders of [family] (several when the person has more than one), best first: one
     * holding the game's save ([holds]) before the others, then the most recently changed.
     * [marker] says whether a folder is one; it may answer with the folder to use (a subfolder),
     * or null when it isn't one. [names] are words the emulator's folder is often named with.
     */
    fun find(
        env: SaveEnvironment,
        family: String,
        names: List<String>,
        holds: ((String) -> Boolean)? = null,
        marker: (String) -> String?,
    ): List<String> {
        if (env.host != "ANDROID") return emptyList()
        val found = env.remember("datafolders:$family") { search(env, names, marker) }
        if (found.size < 2) return found
        val times = found.associateWith { env.modified(it) ?: 0L }
        return found.sortedWith(compareByDescending<String> { holds?.invoke(it) == true }.thenByDescending { times[it] ?: 0L })
    }

    private fun search(env: SaveEnvironment, names: List<String>, marker: (String) -> String?): List<String> {
        val hints = DEEPER + names.map { it.lowercase() }
        val out = LinkedHashSet<String>()
        // [level] is how far below the storage root [dir] is; [named] when a folder on the way
        // (or this one) is named for emulation, which allows one level more.
        fun visit(dir: String, level: Int, named: Boolean) {
            marker(dir)?.let { out += it; return }
            if (level >= MAX_LEVEL || (level >= PLAIN_LEVEL && !named)) return
            for (child in env.list(dir)) {
                if (child.startsWith(".") || child.lowercase() in SKIP) continue
                val path = "$dir/$child"
                if (!env.isDirectory(path)) continue
                visit(path, level + 1, named || hints.any { it in child.lowercase() })
            }
        }
        for (root in env.storageRoots()) {
            if (!env.isDirectory(root)) continue
            visit(root, 0, false)
        }
        return out.toList()
    }
}
