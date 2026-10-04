package io.github.matiyaaa.fuse.library.content

import io.github.matiyaaa.fuse.library.FsEntry
import io.github.matiyaaa.fuse.library.FsPath
import io.github.matiyaaa.fuse.library.FuseFileSystem
import io.github.matiyaaa.fuse.library.disc.ParamSfo
import kotlin.coroutines.cancellation.CancellationException

/** A Vita game kept as a `.vpk` or `.zip` (a NoNpDrm dump or homebrew), as its `sce_sys/param.sfo` describes it. */
data class VitaArchive(
    val path: String,
    val titleId: String,
    val version: String?,
    val category: String?,
    val title: String?,
    val sizeBytes: Long,
) {
    val fileName: String get() = FsPath.name(path)
}

/** A zRIF key found in a text file, and the content id it is for. */
data class ZrifKey(val zrif: String, val contentId: String, val path: String) {
    val titleId: String get() = contentId.substring(7, 16)
}

/** Every installable file and licence found for a game. Files that couldn't be read are listed in [unreadable]. */
data class ContentSources(
    val packages: List<PsPackage> = emptyList(),
    val archives: List<VitaArchive> = emptyList(),
    val licences: List<Licence> = emptyList(),
    val keys: List<ZrifKey> = emptyList(),
    val unreadable: List<String> = emptyList(),
    /** Nintendo 3DS installables. */
    val cias: List<CiaFile> = emptyList(),
    /** 3DS cartridge images and the game title id each holds (they play as they are; their updates install). */
    val cartridges: Map<String, String> = emptyMap(),
) {
    val isEmpty: Boolean get() = packages.isEmpty() && archives.isEmpty() && cias.isEmpty()

    /** The title ids these files are for. */
    val titleIds: Set<String> get() = (packages.mapNotNull { it.titleId } + archives.map { it.titleId } + cias.map { it.gameId } + cartridges.values).toSet()
}

/**
 * Finds a game's packages, archives and licences on disk. Folders are searched [maxDepth] levels
 * deep; files are read only as far as their headers (a few kilobytes), so an SD card or a USB
 * drive works the same as internal storage.
 */
class ContentSourceReader(private val fs: FuseFileSystem, private val maxDepth: Int = 2) {
    private val zip = ZipReader(fs)

    /** Files under [paths] (files are taken as they are, folders are walked). */
    suspend fun collect(paths: List<String>): List<FsEntry> {
        val out = LinkedHashMap<String, FsEntry>()
        val seen = HashSet<String>()
        suspend fun walk(dir: String, depth: Int) {
            val canonical = fs.canonical(dir) ?: FsPath.normalize(dir)
            if (!seen.add(canonical)) return
            for (e in list(dir).sortedBy { it.name.lowercase() }) {
                if (e.name.startsWith(".")) continue
                if (e.isDirectory) {
                    if (depth < maxDepth) walk(e.path, depth + 1)
                } else {
                    out.putIfAbsent(FsPath.normalize(e.path), e)
                }
            }
        }
        for (p in paths.distinct()) {
            val e = fs.stat(p) ?: continue
            if (e.isDirectory) walk(e.path, 0) else out.putIfAbsent(FsPath.normalize(e.path), e)
        }
        return out.values.toList()
    }

    /** What [files] are. Anything that isn't a package, a Vita archive or a licence is passed over. */
    suspend fun read(files: List<FsEntry>): ContentSources {
        val packages = ArrayList<PsPackage>()
        val archives = ArrayList<VitaArchive>()
        val licences = ArrayList<Licence>()
        val keys = ArrayList<ZrifKey>()
        val unreadable = ArrayList<String>()
        val cias = ArrayList<CiaFile>()
        val cartridges = LinkedHashMap<String, String>()
        for (f in files) {
            val name = f.name.lowercase()
            when {
                f.extension == "pkg" -> PsPackages.read(fs, f.path)?.let(packages::add) ?: run { unreadable += f.path }
                f.extension == "cia" -> Cia.read(fs, f.path)?.let(cias::add) ?: run { unreadable += f.path }
                f.extension == "3ds" || f.extension == "cci" -> Cia.cartridgeId(fs, f.path)?.let { cartridges[f.path] = it }
                f.extension == "vpk" || f.extension == "zip" -> vitaArchive(f)?.let(archives::add)
                // A .rap is 16 bytes; one under another name is still a licence, matched later.
                f.extension == "rap" -> if (f.sizeBytes == Licences.RAP_SIZE) {
                    licences += Licences.rap(f.path, f.sizeBytes) ?: Licence(LicenceKind.RAP, f.path, null)
                } else {
                    unreadable += f.path
                }
                f.extension == "edat" -> fs.readBytes(f.path, 0, 0x100)?.let(Licences::npd)?.let { licences += Licence(LicenceKind.EDAT, f.path, it.contentId) }
                f.extension == "rif" || name == "work.bin" -> if (f.sizeBytes in 0x40..1024) {
                    fs.readBytes(f.path, 0, f.sizeBytes.toInt())?.let { bytes ->
                        val id = Licences.rifContentId(bytes)
                        licences += Licence(LicenceKind.RIF, f.path, id, id?.let { Licences.zrifOf(bytes) })
                    }
                }
                Licences.isKeyText(f.name) && f.sizeBytes in 1..MAX_KEY_TEXT -> {
                    val text = fs.readText(f.path, MAX_KEY_TEXT.toInt()) ?: continue
                    for ((zrif, contentId) in Licences.zrifsIn(text)) keys += ZrifKey(zrif, contentId, f.path)
                }
            }
        }
        return ContentSources(packages, archives, licences, keys.distinctBy { it.zrif }, unreadable, cias, cartridges)
    }

    /** A `.vpk`/`.zip` holding a Vita title (its `sce_sys/param.sfo`), or null for any other archive. */
    suspend fun vitaArchive(f: FsEntry): VitaArchive? {
        val entries = try {
            zip.entries(f.path)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } ?: return null
        // The game's own param.sfo: sce_sys/param.sfo at the root, or in a title folder a few levels
        // down (a zipped title folder, "app/PCSA00029/", "ux0/app/PCSA00029/"). The game's own wins
        // over an update's or DLC's packed beside it, then the shallowest.
        val sfos = entries.filter { SFO_ANYWHERE.matches(it.name.replace('\\', '/').lowercase()) }
        val entry = sfos.minWithOrNull(compareBy({ sfoRank(it.name) }, { it.name.count { c -> c == '/' || c == '\\' } })) ?: return null
        val sfo = zip.read(f.path, entry, MAX_SFO)?.let(ParamSfo::strings)
        // A param.sfo packed in a way Fuse can't unpack still says where the title is: its folder is named with its id.
        val id = sfo?.get("TITLE_ID")?.trim()?.takeIf { PsPackages.TITLE_ID.matches(it) }
            ?: entry.name.replace('\\', '/').split('/').firstOrNull { PsPackages.TITLE_ID.matches(it.uppercase()) }?.uppercase()
            ?: return null
        if (sfo == null) return VitaArchive(f.path, id, null, categoryFromPath(entry.name), null, f.sizeBytes)
        return VitaArchive(f.path, id, sfo["APP_VER"]?.trim(), sfo["CATEGORY"]?.trim()?.lowercase(), sfo["TITLE"]?.trim()?.replace('\n', ' '), f.sizeBytes)
    }

    private suspend fun list(path: String) = try {
        fs.list(path)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        emptyList()
    }

    private companion object {
        const val MAX_KEY_TEXT = 4L * 1024 * 1024
        const val MAX_SFO = 64 * 1024
        /** sce_sys/param.sfo at the root or up to three folders down. */
        val SFO_ANYWHERE = Regex("^(?:[^/]+/){0,3}sce_sys/param\\.sfo$")

        /** Where a param.sfo sits: a game's own folder first, then an update's, then DLC's. */
        fun sfoRank(name: String): Int {
            val n = name.replace('\\', '/').lowercase()
            return when {
                n.contains("addcont/") -> 2
                n.contains("patch/") -> 1
                else -> 0
            }
        }

        /** "gp" for a title under patch/, "ac" under addcont/, else a game. */
        fun categoryFromPath(name: String): String? = when (sfoRank(name)) {
            2 -> "ac"
            1 -> "gp"
            else -> null
        }
    }
}
