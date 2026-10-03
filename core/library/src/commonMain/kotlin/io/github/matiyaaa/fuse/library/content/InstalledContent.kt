package io.github.matiyaaa.fuse.library.content

import io.github.matiyaaa.fuse.library.FsPath
import io.github.matiyaaa.fuse.library.FuseFileSystem
import io.github.matiyaaa.fuse.library.disc.ParamSfo
import kotlin.coroutines.cancellation.CancellationException

/** The emulators whose own storage Fuse reads to know what is installed. */
enum class ContentEmulator(val label: String) {
    RPCS3("RPCS3"),
    VITA3K("Vita3K"),
    AZAHAR("Azahar"),
}

/** One title folder in an emulator's storage, as its PARAM.SFO describes it. */
data class InstalledTitle(
    val titleId: String,
    val dir: String,
    val version: String?,
    val category: String?,
    val title: String?,
)

/**
 * What an emulator's storage holds, read from disk:
 * - RPCS3 (`dev_hdd0`): `game/<titleId>/PARAM.SFO` (games, and the updates installed over them) and
 *   the licences in `home/<user>/exdata` (`<contentId>.rap`, `.edat`);
 * - Vita3K (its pref path): `ux0/app/<titleId>`, `ux0/patch/<titleId>`,
 *   `ux0/addcont/<titleId>/<label>`, and the licences in `ux0/license/<titleId>/<contentId>.rif`
 *   (or `sce_sys/package/work.bin` inside a NoNpDrm game).
 *
 * [roots] are the storage folders that were found. Empty means none could be read (not set up yet,
 * or another app's private folder on Android), so nothing can be said about what is installed.
 */
data class InstalledContent(
    val emulator: ContentEmulator,
    val roots: List<String>,
    val games: Map<String, InstalledTitle> = emptyMap(),
    val patches: Map<String, InstalledTitle> = emptyMap(),
    /** Vita add-ons as `<titleId>/<label>` (the last 16 characters of the content id). */
    val addons: Set<String> = emptySet(),
    /** Content ids with a licence in place (PS3 `.rap`, Vita `.rif`), upper case. */
    val licences: Set<String> = emptySet(),
    /** Vita titles whose licence is inside the game (`work.bin`). */
    val workBin: Set<String> = emptySet(),
    /** Licence files by content id, where Fuse can read one back (Vita `.rif`, for a zRIF). */
    val licenceFiles: Map<String, String> = emptyMap(),
    /** The file an installed title starts from, by title id (Azahar: its first content's `.app`). */
    val bootFiles: Map<String, String> = emptyMap(),
) {
    val readable: Boolean get() = roots.isNotEmpty()

    /** The version installed for [titleId]: the update's when one is in place, else the game's. */
    fun versionOf(titleId: String): String? {
        val game = games[titleId]
        val patch = patches[titleId]
        return listOfNotNull(game?.version, patch?.version).maxWithOrNull(PsPackages::compareVersions)
    }

    fun hasLicence(contentId: String): Boolean = contentId.uppercase() in licences

    companion object {
        /** Nothing found (no storage folder could be read). */
        fun none(emulator: ContentEmulator) = InstalledContent(emulator, emptyList())
    }
}

/** Reads [InstalledContent] from storage folders through a [FuseFileSystem]; never writes. */
class InstalledContentReader(private val fs: FuseFileSystem) {

    /** RPCS3's `dev_hdd0` folders among [candidates] (those with a `game` folder), read. */
    suspend fun rpcs3(candidates: List<String>): InstalledContent {
        val roots = candidates.map(FsPath::normalize).distinct().filter { isDir(FsPath.join(it, "game")) }
        val games = LinkedHashMap<String, InstalledTitle>()
        val licences = HashSet<String>()
        for (root in roots) {
            for (dir in list(FsPath.join(root, "game")).filter { it.isDirectory }) {
                if (!PsPackages.TITLE_ID.matches(dir.name)) continue
                val sfo = sfo(FsPath.join(dir.path, "PARAM.SFO")) ?: continue
                // The folder is only that title when its own PARAM.SFO says so.
                if (sfo["TITLE_ID"]?.trim() != dir.name) continue
                games.putIfAbsent(dir.name, InstalledTitle(dir.name, dir.path, sfo["APP_VER"]?.trim() ?: sfo["VERSION"]?.trim(), sfo["CATEGORY"]?.trim(), sfo["TITLE"]?.trim()))
            }
            for (user in list(FsPath.join(root, "home")).filter { it.isDirectory }) {
                for (f in list(FsPath.join(user.path, "exdata"))) {
                    if (f.isDirectory) continue
                    val ext = f.extension
                    if (ext == "rap" && f.sizeBytes >= Licences.RAP_SIZE) licences += FsPath.stem(f.name).uppercase()
                    if (ext == "edat") licences += FsPath.stem(f.name).uppercase()
                }
            }
        }
        return InstalledContent(ContentEmulator.RPCS3, roots, games, licences = licences)
    }

    /** Vita3K's pref paths among [candidates] (those with a `ux0` folder), read. */
    suspend fun vita3k(candidates: List<String>): InstalledContent {
        val roots = candidates.map(FsPath::normalize).distinct().filter { isDir(FsPath.join(it, "ux0")) }
        val games = LinkedHashMap<String, InstalledTitle>()
        val patches = LinkedHashMap<String, InstalledTitle>()
        val addons = HashSet<String>()
        val licences = HashSet<String>()
        val files = HashMap<String, String>()
        val workBin = HashSet<String>()
        for (root in roots) {
            val ux0 = FsPath.join(root, "ux0")
            for ((folder, into) in listOf("app" to games, "patch" to patches)) {
                for (dir in list(FsPath.join(ux0, folder)).filter { it.isDirectory }) {
                    val sfo = sfo(FsPath.join(dir.path, "sce_sys/param.sfo")) ?: continue
                    if (sfo["TITLE_ID"]?.trim() != dir.name) continue
                    into.putIfAbsent(dir.name, InstalledTitle(dir.name, dir.path, sfo["APP_VER"]?.trim(), sfo["CATEGORY"]?.trim(), sfo["TITLE"]?.trim()))
                    if (folder == "app" && fs.stat(FsPath.join(dir.path, "sce_sys/package/work.bin")) != null) workBin += dir.name
                }
            }
            for (title in list(FsPath.join(ux0, "addcont")).filter { it.isDirectory }) {
                for (label in list(title.path).filter { it.isDirectory }) addons += "${title.name}/${label.name}"
            }
            // ux0/license/<titleId>/<contentId>.rif, and ux0/license/app/<titleId>/ from older layouts.
            val licenceDirs = list(FsPath.join(ux0, "license")).filter { it.isDirectory }
            val nested = licenceDirs.filter { it.name == "app" || it.name == "addcont" }.flatMap { list(it.path).filter { d -> d.isDirectory } }
            for (dir in licenceDirs + nested) {
                for (f in list(dir.path)) {
                    if (f.isDirectory || f.extension != "rif") continue
                    val id = FsPath.stem(f.name).uppercase()
                    if (PsPackages.CONTENT_ID.matches(id)) {
                        licences += id
                        files.putIfAbsent(id, f.path)
                    }
                }
            }
        }
        return InstalledContent(ContentEmulator.VITA3K, roots, games, patches, addons, licences, workBin, files)
    }

    /**
     * Azahar's SD cards (its `sdmc` folders) among [candidates]: every installed title under
     * `Nintendo 3DS/<id0>/<id1>/title/<high>/<low>`, with the version its TMD gives. Games, their
     * updates and their DLC are all keyed by their own title id.
     */
    suspend fun azahar(candidates: List<String>): InstalledContent {
        val roots = candidates.map(FsPath::normalize).distinct().filter { isDir(FsPath.join(it, "Nintendo 3DS")) }
        val titles = LinkedHashMap<String, InstalledTitle>()
        val boot = HashMap<String, String>()
        for (root in roots) {
            for (high in listOf(Cia.GAME, Cia.UPDATE, Cia.DLC)) {
                val group = FsPath.parent(Cia.titleDir(root, high + "00000000")) ?: continue
                for (dir in list(group).filter { it.isDirectory && it.name.length == 8 }) {
                    val content = FsPath.join(dir.path, "content")
                    val tmdFile = list(content).firstOrNull { !it.isDirectory && it.extension == "tmd" } ?: continue
                    val tmd = fs.readBytes(tmdFile.path, 0, 0x10000)?.let(Cia::tmd) ?: continue
                    val id = tmd.titleId
                    if (id != (high + dir.name).uppercase()) continue
                    titles.putIfAbsent(id, InstalledTitle(id, dir.path, tmd.version, null, null))
                    tmd.contents[0]?.let { cid ->
                        val app = FsPath.join(content, ((cid and 0xFFFFFFFFL) + 0x100000000L).toString(16).substring(1) + ".app")
                        if (fs.stat(app) != null) boot.putIfAbsent(id, app)
                    }
                }
            }
        }
        return InstalledContent(ContentEmulator.AZAHAR, roots, titles, bootFiles = boot)
    }

    private suspend fun sfo(path: String): Map<String, String>? =
        fs.readBytes(path, 0, 64 * 1024)?.let(ParamSfo::strings)?.takeIf { it.isNotEmpty() }

    private suspend fun isDir(path: String): Boolean = fs.stat(path)?.isDirectory == true

    private suspend fun list(path: String) = try {
        fs.list(path)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        emptyList()
    }
}
