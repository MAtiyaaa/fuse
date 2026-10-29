package io.github.matiyaaa.fuse.library.movein

import io.github.matiyaaa.fuse.library.FsPath
import io.github.matiyaaa.fuse.library.FuseFileSystem

/**
 * Finds the launchers the person used before Fuse on this device, where each keeps its own files,
 * and reads what they say: ES-DE's gamelists and custom collections (and gamelists kept beside the
 * games, as Batocera and RetroBat do), RetroArch's runtime logs and favourites, Pegasus's
 * favourites, and Steam's play time. It only reads, and only these files.
 *
 * [host] is LINUX, MACOS, WINDOWS or ANDROID; [home] the person's home folder; [offsetMs] the
 * local time's offset from UTC, for the launchers that keep local times.
 */
class MoveInFinder(
    private val fs: FuseFileSystem,
    private val host: String,
    private val home: String,
    private val offsetMs: Long = 0,
    /** Windows's `%APPDATA%` (Roaming) and `%LOCALAPPDATA%`, where its launchers keep their files. */
    private val appData: String? = null,
    private val localAppData: String? = null,
) {
    /**
     * What each of [sources] says, from where it keeps its files here; [libraryRoots] are the
     * library's folders (whose system folders may hold gamelists of their own), [steamRoots] each
     * Steam install. A launcher with nothing to say is left out.
     */
    suspend fun find(sources: Set<MoveInSource>, libraryRoots: List<String> = emptyList(), steamRoots: List<String> = emptyList()): List<MoveInFind> = buildList {
        // One launcher that can't be read never keeps the others from being found.
        if (MoveInSource.ESDE in sources) addAll(runCatching { esDe(libraryRoots) }.getOrDefault(emptyList()))
        if (MoveInSource.RETROARCH in sources) runCatching { retroArch() }.getOrNull()?.let(::add)
        if (MoveInSource.PEGASUS in sources) runCatching { pegasus() }.getOrNull()?.let(::add)
        if (MoveInSource.STEAM in sources) runCatching { steam(steamRoots) }.getOrNull()?.let(::add)
    }

    private suspend fun exists(path: String) = runCatching { fs.stat(path) }.getOrNull() != null

    /** A folder's entries; none where it isn't, or can't be read. */
    private suspend fun list(path: String) = runCatching { fs.list(path) }.getOrDefault(emptyList())

    private suspend fun read(path: String, limit: Int) = runCatching { fs.readText(path, limit) }.getOrNull()

    /** ES-DE's own folder wherever it keeps one, then gamelists beside the games. */
    private suspend fun esDe(libraryRoots: List<String>): List<MoveInFind> {
        val homes = when (host) {
            "ANDROID" -> listOf("/storage/emulated/0/ES-DE")
            "WINDOWS" -> listOfNotNull("$home/ES-DE", appData?.let { "$it/ES-DE" }, "$home/.emulationstation")
            else -> listOf(
                "$home/ES-DE", "$home/.emulationstation",
                "$home/.var/app/org.es_de.frontend/config/ES-DE",
                "$home/.var/app/net.retrodeck.retrodeck/config/ES-DE",
            )
        }
        val out = ArrayList<MoveInFind>()
        for (dir in homes.distinct()) {
            val lists = FsPath.join(dir, "gamelists")
            val games = list(lists).filter { it.isDirectory }.flatMap { sys ->
                read(FsPath.join(sys.path, GAMELIST), GAMELIST_LIMIT)?.let { EsDeGamelist.parse(it, sys.name, offsetMs) }.orEmpty()
            }
            val collections = list(FsPath.join(dir, "collections")).filter { !it.isDirectory && it.name.startsWith("custom-") && it.name.endsWith(".cfg") }
                .mapNotNull { f -> read(f.path, COLLECTION_LIMIT)?.let { EsDeCollection.parse(f.name, it) } }
            if (games.isNotEmpty() || collections.isNotEmpty()) out += MoveInFind(MoveInSource.ESDE, dir, games, collections)
        }
        // Batocera and RetroBat keep each system's gamelist in its own folder of games.
        val beside = libraryRoots.distinct().flatMap { root ->
            list(root).filter { it.isDirectory }.flatMap { sys ->
                read(FsPath.join(sys.path, GAMELIST), GAMELIST_LIMIT)?.let { EsDeGamelist.parse(it, sys.name, offsetMs) }.orEmpty()
            }
        }
        if (beside.isNotEmpty()) out += MoveInFind(MoveInSource.ESDE, libraryRoots.joinToString(", "), beside)
        return out
    }

    /** RetroArch's folder, its runtime logs (aggregate, and by core) and its favourites. */
    private suspend fun retroArch(): MoveInFind? {
        val bases = when (host) {
            "ANDROID" -> listOf("/storage/emulated/0/RetroArch", "/storage/emulated/0/Android/data/com.retroarch/files")
            "WINDOWS" -> listOfNotNull(appData?.let { "$it/RetroArch" }, "C:/RetroArch-Win64", "C:/RetroArch")
            "MACOS" -> listOf("$home/Library/Application Support/RetroArch")
            else -> listOf(
                "$home/.config/retroarch",
                "$home/.var/app/org.libretro.RetroArch/config/retroarch",
                "$home/snap/retroarch/current/.config/retroarch",
            )
        }
        val base = bases.firstOrNull { exists(FsPath.join(it, "retroarch.cfg")) } ?: bases.firstOrNull { exists(it) } ?: return null
        val cfg = read(FsPath.join(base, "retroarch.cfg"), CONFIG_LIMIT).orEmpty().lineSequence().mapNotNull { line ->
            val i = line.indexOf('=')
            if (i <= 0 || line.trimStart().startsWith("#")) null else line.substring(0, i).trim() to line.substring(i + 1).trim().trim('"')
        }.toMap()
        fun dir(key: String, default: String) = cfg[key]?.takeIf { it.isNotBlank() && it != "default" }?.let { expand(it, base) } ?: default
        val playlists = dir("playlist_directory", FsPath.join(base, "playlists"))
        val logs = dir("runtime_log_directory", FsPath.join(playlists, "logs"))
        val games = ArrayList<MovedGame>()
        suspend fun readLogs(folder: String) {
            for (f in list(folder)) {
                if (f.isDirectory || !f.name.endsWith(".lrtl")) continue
                read(f.path, LOG_LIMIT)?.let { RetroArchRuntime.parse(it, f.name.removeSuffix(".lrtl"), offsetMs) }?.let(games::add)
            }
        }
        readLogs(logs)
        for (core in list(logs).filter { it.isDirectory }) readLogs(core.path)
        val favourites = cfg["content_favorites_path"]?.takeIf { it.isNotBlank() && it != "default" }?.let { expand(it, base) }
            ?: FsPath.join(base, "content_favorites.lpl")
        read(favourites, PLAYLIST_LIMIT)?.let { games += RetroArchFavourites.parse(it) }
        return MoveInFind(MoveInSource.RETROARCH, base, games).takeIf { games.isNotEmpty() }
    }

    private suspend fun pegasus(): MoveInFind? {
        val places = when (host) {
            "ANDROID" -> listOf("/storage/emulated/0/pegasus-frontend")
            "WINDOWS" -> listOfNotNull(localAppData?.let { "$it/pegasus-frontend" })
            "MACOS" -> listOf("$home/Library/Preferences/pegasus-frontend")
            else -> listOf("$home/.config/pegasus-frontend", "$home/.var/app/org.pegasus_frontend.Pegasus/config/pegasus-frontend")
        }
        for (dir in places) {
            val games = read(FsPath.join(dir, "favorites.txt"), PLAYLIST_LIMIT)?.let(PegasusFavourites::parse).orEmpty()
            if (games.isNotEmpty()) return MoveInFind(MoveInSource.PEGASUS, dir, games)
        }
        return null
    }

    /** Every account's own count, the largest kept for a game more than one account played. */
    private suspend fun steam(steamRoots: List<String>): MoveInFind? {
        val games = HashMap<Long, MovedGame>()
        var where: String? = null
        for (root in steamRoots.distinct()) {
            for (user in list(FsPath.join(root, "userdata")).filter { it.isDirectory }) {
                val text = read(FsPath.join(user.path, "config", "localconfig.vdf"), STEAM_LIMIT) ?: continue
                where = where ?: root
                for (g in SteamPlaytime.parse(text)) {
                    val had = games[g.steamAppId!!]
                    if (had == null || g.seconds > had.seconds) games[g.steamAppId] = g.copy(lastPlayedAt = listOfNotNull(g.lastPlayedAt, had?.lastPlayedAt).maxOrNull())
                }
            }
        }
        return where?.let { MoveInFind(MoveInSource.STEAM, it, games.values.toList()) }?.takeIf { it.games.isNotEmpty() }
    }

    /** A RetroArch folder setting: `~` is home, `:` the config's own folder. */
    private fun expand(path: String, base: String): String = when {
        path.startsWith("~/") -> "$home/${path.drop(2)}"
        path.startsWith(":") -> FsPath.join(base, path.drop(1).trimStart('/', '\\'))
        else -> path.replace('\\', '/')
    }

    private companion object {
        const val GAMELIST = "gamelist.xml"
        const val GAMELIST_LIMIT = 16 * 1024 * 1024
        const val COLLECTION_LIMIT = 1024 * 1024
        const val CONFIG_LIMIT = 512 * 1024
        const val LOG_LIMIT = 4 * 1024
        const val PLAYLIST_LIMIT = 4 * 1024 * 1024
        const val STEAM_LIMIT = 16 * 1024 * 1024
    }
}
