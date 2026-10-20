package io.github.matiyaaa.fuse.launch.extras

/**
 * Xenia Canary's game patches, from the xenia-canary/game-patches release as its README says to
 * install them: `game-patches.7z` holds a `patches` folder of `<TITLE ID> - <title>.patch.toml`
 * files, which go in Xenia's storage root (beside xenia_canary.exe when it runs portable, as Windows
 * builds do, else `Xenia` in Documents or the data folder: src/xenia/app/xenia_main.cc), read while
 * `apply_patches` is on (src/xenia/patcher/patch_db.cc). Each patch is a `[[patch]]` with its
 * `name` and `is_enabled`, all off as released.
 *
 * The README says to delete the old folder first, which loses the patches a person turned on;
 * Fuse turns the same patches (same file, same name) on again in the new files ([carryOver]).
 */
object XeniaPatches {
    const val URL = "https://github.com/xenia-canary/game-patches/releases/latest/download/game-patches.7z"
    const val FOLDER = "patches"
    const val MAX_ARCHIVE = 64L * 1024 * 1024

    private val FILE = Regex("^[0-9A-Fa-f]{8} - .+\\.patch\\.toml$")
    private val NAME = Regex("^\\s*name\\s*=\\s*\"(.*)\"\\s*(#.*)?$")
    private val ENABLED = Regex("^(\\s*is_enabled\\s*=\\s*)(true|false)(\\s*(#.*)?)$")

    /** The patch files in the archive's [entries], each with its name in Xenia's `patches` folder. */
    fun files(entries: List<String>): List<Pair<String, String>> = entries.mapNotNull { e ->
        val path = e.replace('\\', '/')
        if (path.split('/').any { it == ".." } || path.startsWith("/")) return@mapNotNull null
        val name = path.substringAfterLast('/')
        // Only from a `patches` folder (or the top), never from anything else the archive carries.
        val folder = path.substringBeforeLast('/', "")
        if (folder.isNotEmpty() && folder.substringAfterLast('/') != FOLDER) return@mapNotNull null
        if (FILE.matches(name)) e to name else null
    }

    /** The names of the patches turned on in [text] (a .patch.toml). */
    fun enabled(text: String): Set<String> {
        val on = HashSet<String>()
        var name: String? = null
        for (line in text.lineSequence()) {
            if (line.trim() == "[[patch]]") name = null
            NAME.find(line)?.let { name = it.groupValues[1] }
            ENABLED.find(line)?.let { m -> if (m.groupValues[2] == "true") name?.let(on::add) }
        }
        return on
    }

    /** [fresh] with the patches named in [on] turned on, everything else as released. */
    fun carryOver(fresh: String, on: Set<String>): String {
        if (on.isEmpty()) return fresh
        var name: String? = null
        return fresh.lineSequence().joinToString("\n") { line ->
            if (line.trim() == "[[patch]]") name = null
            NAME.find(line)?.let { name = it.groupValues[1] }
            val m = ENABLED.find(line)
            if (m != null && name in on) m.groupValues[1] + "true" + m.groupValues[3] else line
        }
    }
}
