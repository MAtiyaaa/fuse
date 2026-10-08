package io.github.matiyaaa.fuse.library.steam

import io.github.matiyaaa.fuse.library.FsPath
import io.github.matiyaaa.fuse.library.FuseFileSystem
import kotlinx.coroutines.CancellationException

/** A game Steam has installed: its app id, its name, and where its folder is. */
data class SteamGame(
    val appId: Long,
    val name: String,
    /** The game's own folder (`<library>/steamapps/common/<installdir>`). */
    val folder: String,
    /** The Steam library it is in (the folder holding `steamapps`). */
    val library: String,
    val sizeBytes: Long = 0,
    /** Installation provenance from LastOwner; never treated as proof of ownership or authentication. */
    val lastInstalledBy: Set<String> = emptySet(),
)

/**
 * Valve's KeyValues text format, as `libraryfolders.vdf` and `appmanifest_*.acf` use it: quoted (or
 * bare) keys and values, nested blocks in braces, `//` comments. Duplicate keys keep the last.
 */
object Vdf {
    /** The document as nested maps: a value is a String or another map. Malformed input gives what parsed. */
    fun parse(text: String): Map<String, Any> {
        val tokens = tokens(text)
        var i = 0
        fun block(): Map<String, Any> {
            val out = LinkedHashMap<String, Any>()
            while (i < tokens.size) {
                val key = tokens[i++]
                if (key == "}") return out
                if (key == "{") continue
                val next = tokens.getOrNull(i) ?: break
                if (next == "{") {
                    i++
                    out[key.lowercase()] = block()
                } else if (next != "}") {
                    i++
                    out[key.lowercase()] = next
                }
            }
            return out
        }
        return block()
    }

    private fun tokens(text: String): List<String> {
        val out = ArrayList<String>()
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            when {
                ch.isWhitespace() -> i++
                ch == '/' && text.getOrNull(i + 1) == '/' -> {
                    while (i < text.length && text[i] != '\n') i++
                }
                ch == '{' || ch == '}' -> {
                    out += ch.toString()
                    i++
                }
                ch == '"' -> {
                    val sb = StringBuilder()
                    i++
                    while (i < text.length && text[i] != '"') {
                        if (text[i] == '\\' && i + 1 < text.length) {
                            val e = text[i + 1]
                            sb.append(if (e == 'n') '\n' else if (e == 't') '\t' else e)
                            i += 2
                        } else {
                            sb.append(text[i++])
                        }
                    }
                    i++
                    out += sb.toString()
                }
                else -> {
                    val start = i
                    while (i < text.length && !text[i].isWhitespace() && text[i] != '{' && text[i] != '}' && text[i] != '"') i++
                    out += text.substring(start, i)
                }
            }
        }
        return out
    }
}

/**
 * Finds the games Steam has installed, on any drive: it reads each Steam install's list of library
 * folders (`steamapps/libraryfolders.vdf`), looks for libraries on the given drives too (a
 * `SteamLibrary` folder at a drive's root, as Steam suggests when adding one), and reads every app
 * manifest in them. Only fully installed games are returned; Steam's own tools (Proton, the Linux
 * runtimes, redistributables) are left out. Reads only.
 */
class SteamLibraryReader(private val fs: FuseFileSystem) {
    /**
     * Every Steam library folder reachable from [steamRoots] (Steam installs) and [drives] (mount
     * points or drive roots, searched a level or two down for common library folder names).
     */
    suspend fun libraries(steamRoots: List<String>, drives: List<String> = emptyList()): List<String> {
        val found = LinkedHashSet<String>()
        suspend fun addIfLibrary(path: String) {
            val norm = FsPath.normalize(path)
            if (norm in found) return
            if (fs.stat(FsPath.join(norm, "steamapps"))?.isDirectory == true) found += norm
        }
        for (root in steamRoots) {
            addIfLibrary(root)
            for (vdf in listOf("steamapps/libraryfolders.vdf", "config/libraryfolders.vdf")) {
                val text = fs.readText(FsPath.join(root, vdf), VDF_LIMIT) ?: continue
                for (path in libraryPaths(text)) addIfLibrary(path)
            }
        }
        for (drive in drives) {
            for (candidate in DRIVE_FOLDERS) addIfLibrary(FsPath.join(drive, candidate))
        }
        return found.toList()
    }

    /** The installed games in [libraries], by name, each app once. */
    suspend fun games(libraries: List<String>): List<SteamGame> {
        val games = LinkedHashMap<Long, SteamGame>()
        for (library in libraries) {
            val apps = FsPath.join(library, "steamapps")
            val manifests = try {
                fs.list(apps).filter { !it.isDirectory && it.name.startsWith("appmanifest_") && it.name.endsWith(".acf") }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                continue
            }
            for (m in manifests) {
                val text = fs.readText(m.path, MANIFEST_LIMIT) ?: continue
                val game = manifest(text, library) ?: continue
                val previous = games[game.appId]
                games[game.appId] = previous?.copy(lastInstalledBy = previous.lastInstalledBy + game.lastInstalledBy) ?: game
            }
        }
        return games.values.sortedBy { it.name.lowercase() }
    }

    companion object {
        private const val VDF_LIMIT = 256 * 1024
        private const val MANIFEST_LIMIT = 64 * 1024

        /** Folders on a drive where people (and Steam's own suggestion) keep a library. */
        val DRIVE_FOLDERS = listOf(
            "SteamLibrary", "Steam", "Games/Steam", "Games/SteamLibrary",
            "Program Files (x86)/Steam", "Program Files/Steam",
        )

        /** Steam's own tools, which install like games but aren't. */
        private val TOOL_IDS = setOf(
            228980L, // Steamworks Common Redistributables
            1070560L, 1391110L, 1628350L, 4183110L, // Steam Linux Runtime 1.0, 2.0 (soldier), 3.0 (sniper), 4.0
            1493710L, 2180100L, // Proton Experimental, Proton Hotfix
            250820L, // SteamVR
            1826330L, // Proton EasyAntiCheat Runtime
            1161040L, // Proton BattlEye Runtime
        )
        private val TOOL_NAMES = listOf("proton", "steam linux runtime", "steamworks", "redistributable", "steamvr", "steam audio")

        /** Whether the app is one of Steam's tools rather than a game. */
        fun isTool(appId: Long, name: String): Boolean {
            if (appId in TOOL_IDS) return true
            val n = name.lowercase()
            return TOOL_NAMES.any { n.startsWith(it) } || "redistributable" in n
        }

        /** The library folders a `libraryfolders.vdf` lists (old style: numbered keys holding the path). */
        fun libraryPaths(text: String): List<String> {
            val root = Vdf.parse(text)
            val folders = root["libraryfolders"] as? Map<*, *> ?: return emptyList()
            return folders.entries.mapNotNull { (key, value) ->
                if ((key as? String)?.toIntOrNull() == null) return@mapNotNull null
                when (value) {
                    is String -> value
                    is Map<*, *> -> value["path"] as? String
                    else -> null
                }
            }.map { it.replace('\\', '/') }
        }

        /** The game an `appmanifest_*.acf` describes, or null for tools and games not fully installed. */
        fun manifest(text: String, library: String): SteamGame? {
            val state = Vdf.parse(text)["appstate"] as? Map<*, *> ?: return null
            val appId = (state["appid"] as? String)?.toLongOrNull() ?: return null
            val name = (state["name"] as? String)?.trim().orEmpty().ifEmpty { return null }
            val flags = (state["stateflags"] as? String)?.toIntOrNull() ?: 0
            // Bit 4 is "fully installed"; a game still downloading or uninstalled isn't playable yet.
            if (flags and 4 == 0) return null
            if (isTool(appId, name)) return null
            val dir = (state["installdir"] as? String).orEmpty()
            val size = (state["sizeondisk"] as? String)?.toLongOrNull() ?: 0
            val owner = (state["lastowner"] as? String)?.takeIf { it.length == 17 && it.toULongOrNull() != null }
            return SteamGame(appId, name, FsPath.join(library, "steamapps", "common", dir), library, size, setOfNotNull(owner))
        }

        /** A file name for the game's shortcut: its name with characters file systems refuse taken out. */
        fun shortcutName(game: SteamGame): String {
            val clean = game.name.replace(Regex("""[\\/:*?"<>|]"""), " ").replace(Regex("\\s+"), " ").trim().trimEnd('.')
            return "${clean.ifEmpty { "Steam ${game.appId}" }}.steam"
        }
    }
}
