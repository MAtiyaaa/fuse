package io.github.matiyaaa.fuse.library.scan

import io.github.matiyaaa.fuse.library.FsAccessException
import io.github.matiyaaa.fuse.library.FsEntry
import io.github.matiyaaa.fuse.library.FsPath
import io.github.matiyaaa.fuse.library.FuseFileSystem
import io.github.matiyaaa.fuse.library.parse.FilenameParser
import io.github.matiyaaa.fuse.library.parse.NameFlags
import io.github.matiyaaa.fuse.library.parse.Serials
import io.github.matiyaaa.fuse.model.ChildContent
import io.github.matiyaaa.fuse.model.ContentKind
import io.github.matiyaaa.fuse.model.Disc
import io.github.matiyaaa.fuse.model.FilenameTags
import io.github.matiyaaa.fuse.model.FolderInterpretation
import io.github.matiyaaa.fuse.model.FolderPolicy
import io.github.matiyaaa.fuse.model.LibrarySourceId
import io.github.matiyaaa.fuse.model.LocationKind
import io.github.matiyaaa.fuse.model.Platform
import io.github.matiyaaa.fuse.model.PlatformFamily
import io.github.matiyaaa.fuse.model.ScannedGame
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.yield
import kotlin.coroutines.cancellation.CancellationException

/**
 * Games found under one folder.
 *
 * @property complete False when part of the tree could not be read. Games that were seen before
 *   but are absent now must then not be treated as removed.
 * @property foldersVisited Directories listed, for progress reporting.
 */
data class FolderScanResult(
    val games: List<ScannedGame>,
    val complete: Boolean,
    val errors: List<String> = emptyList(),
    val foldersVisited: Int = 0,
)

/**
 * Decides what the folders inside a platform folder are, following the effective [FolderPolicy].
 *
 * With [FolderPolicy.AUTO] a folder is, in this order:
 * 1. an ES-DE "directory interpreted as file" when its name carries a platform extension
 *    (`Game.cue/`, `Game.m3u/`, `Game.ps3/`): the same-named file inside is launched, otherwise
 *    the folder itself ([FolderInterpretation.FOLDER_IS_GAME]);
 * 2. a known folder structure ([FolderInterpretation.FOLDER_IS_GAME]): PS3 (`PS3_DISC.SFB`,
 *    `PS3_GAME/`, or `USRDIR/` with `EBOOT.BIN`/`PARAM.SFO`), PS Vita (`sce_sys/param.sfo`), PSP
 *    (`PSP_GAME/`), Wii U (`code/` with `content/` or `meta/`, launching the `.rpx` in `code/`), Xbox 360
 *    (`default.xex`), and PC games (a `.desktop`, `.exe`, `.bat`, `.com` or `.conf` on PC platforms);
 * 3. several unrelated titles at its root: an organisational folder, so its games are listed one
 *    by one and its subfolders are walked (ES-DE folders, RomM `{category}` levels);
 * 4. one title at its root, or a `base`/`game` folder, possibly with RomM category folders
 *    (`dlc`, `updates`, `manuals`...): one multi-file game ([FolderInterpretation.MULTI_FILE_GAME]).
 *    A folder named like a bucket ("Europe", "Hacks", "A") without category folders is still
 *    organisational, and so is a folder whose other subfolders hold games of their own;
 * 5. no game files at its root: organisational. On folder-native platforms (PS3, Wii U, Xbox,
 *    Vita, PC) a folder that yields nothing is kept as one folder game so it is never lost.
 *
 * [FolderPolicy.FOLDER_AS_GAME] makes every folder one game (the best file inside, else the
 * folder), [FolderPolicy.FOLDER_BROWSER] makes it one entry the user browses, and
 * [FolderPolicy.FILE] only counts files and walks every folder.
 *
 * RomM category folders only count directly under the game folder. Walking stops at
 * [ScanOptions.maxDepth] and never enters the same directory twice (symlink loops).
 */
class FolderInterpreter(
    private val fs: FuseFileSystem,
    private val options: ScanOptions = ScanOptions(),
) {
    private val grouper = DiscGrouper(fs)

    /**
     * Scans one platform folder: its files are games (grouped into disc sets, with loose updates and
     * DLC attached) and each subfolder is interpreted.
     *
     * @param listing The platform folder's children when the caller already listed it.
     * @param onFolder Called before each directory is listed (progress).
     */
    suspend fun scanPlatformFolder(
        platform: Platform,
        folderPath: String,
        sourceId: LibrarySourceId,
        policies: FolderPolicyResolver = FolderPolicyResolver.CatalogDefaults,
        listing: List<FsEntry>? = null,
        onFolder: suspend (String) -> Unit = {},
    ): FolderScanResult {
        val walk = Walk(platform, sourceId, policies, onFolder)
        val root = FsPath.normalize(folderPath)
        walk.visited += fs.canonical(root) ?: root
        val children = listing ?: walk.list(root) ?: return walk.result(emptyList())
        return walk.result(scanLevel(root, children, depth = 0, walk = walk))
    }

    /**
     * Interprets one folder that sits directly inside a platform folder. [depth] is 1 for such a
     * folder and grows for organisational levels below it.
     */
    suspend fun interpretFolder(
        platform: Platform,
        folder: FsEntry,
        sourceId: LibrarySourceId,
        policies: FolderPolicyResolver = FolderPolicyResolver.CatalogDefaults,
        depth: Int = 1,
    ): FolderScanResult {
        val walk = Walk(platform, sourceId, policies) {}
        return walk.result(interpret(folder, depth, walk))
    }

    private inner class Walk(
        val platform: Platform,
        val sourceId: LibrarySourceId,
        val policies: FolderPolicyResolver,
        val onFolder: suspend (String) -> Unit,
    ) {
        val visited = HashSet<String>()
        val errors = ArrayList<String>()
        var complete = true
        var folders = 0

        // Files owned by a playlist or cue sheet at a higher level (an .m3u listing "Discs/x.chd").
        val hidden = HashSet<String>()

        fun isHidden(game: ScannedGame): Boolean =
            FsPath.normalize(game.launchPath) in hidden || FsPath.normalize(game.path) in hidden

        /** Lists [path], or returns null (and marks the walk incomplete) when it can't be read. */
        suspend fun list(path: String): List<FsEntry>? {
            currentCoroutineContext().ensureActive()
            folders++
            onFolder(path)
            return try {
                fs.list(path)
            } catch (e: CancellationException) {
                throw e
            } catch (e: FsAccessException) {
                complete = false
                errors += "Cannot read ${e.path}"
                null
            } catch (e: Exception) {
                complete = false
                errors += "Cannot read $path: ${e.message ?: e::class.simpleName}"
                null
            }
        }

        fun isGameFile(entry: FsEntry): Boolean =
            !entry.isDirectory && entry.extension in platform.extensions &&
                !ScanRules.isIgnoredFile(entry.name) && !ScanRules.isBiosFile(platform, entry.name)

        fun result(games: List<ScannedGame>) = FolderScanResult(games, complete, errors.toList(), folders)
    }

    // A level whose files are individual games and whose folders are interpreted one by one.
    private suspend fun scanLevel(
        dir: String,
        children: List<FsEntry>,
        depth: Int,
        walk: Walk,
        precomputed: DiscGrouping? = null,
    ): List<ScannedGame> {
        yield()
        val grouping = precomputed ?: grouper.group(dir, children, walk::isGameFile)
        walk.hidden += grouping.hidden
        val games = ArrayList<ScannedGame>()
        for ((group, extra) in LooseContent.attach(grouping.groups)) games += fileGame(group, extra, walk)
        for (sub in children.filter { it.isDirectory }.sortedBy { it.name.lowercase() }) {
            games += interpret(sub, depth + 1, walk)
        }
        return games
    }

    /** Interprets [folder], dropping games whose files a playlist higher up already owns. */
    private suspend fun interpret(folder: FsEntry, depth: Int, walk: Walk): List<ScannedGame> =
        interpretUnfiltered(folder, depth, walk).filterNot(walk::isHidden)

    private suspend fun interpretUnfiltered(folder: FsEntry, depth: Int, walk: Walk): List<ScannedGame> {
        if (depth > options.maxDepth) return emptyList()
        if (ScanRules.isExcludedFolder(folder.name, options.extraExcludedFolders)) return emptyList()
        val canonical = fs.canonical(folder.path) ?: FsPath.normalize(folder.path)
        if (!walk.visited.add(canonical)) return emptyList()
        yield()
        val children = walk.list(folder.path) ?: return emptyList()
        return when (walk.policies.policyFor(walk.platform.id, folder.path)) {
            FolderPolicy.FOLDER_BROWSER -> listOf(browserGame(folder, children, walk))
            FolderPolicy.FOLDER_AS_GAME -> listOf(folderAsGame(folder, children, walk))
            FolderPolicy.FILE -> scanLevel(folder.path, children, depth, walk)
            FolderPolicy.AUTO -> auto(folder, children, depth, walk)
        }
    }

    private suspend fun auto(folder: FsEntry, children: List<FsEntry>, depth: Int, walk: Walk): List<ScannedGame> {
        dirAsFile(folder, children, walk)?.let { return listOf(it) }
        structure(folder, children, walk)?.let { return listOf(it) }

        val layout = Layout(children, options)
        val grouping = grouper.group(folder.path, children, walk::isGameFile)
        walk.hidden += grouping.hidden
        val titles = grouping.groups.filter { LooseContent.markerKind(it) == null }
            .map { LooseContent.titleKey(it.parsed.baseTitle) }.toSet()

        if (titles.size >= 2) return scanLevel(folder.path, children, depth, walk, grouping)
        if (titles.size == 1) {
            if (layout.categoryDirs.isNotEmpty() || layout.baseDirs.isNotEmpty()) {
                return listOf(multiFileGame(folder, children, grouping, layout, walk))
            }
            if (ScanRules.isOrganisationalName(folder.name)) return scanLevel(folder.path, children, depth, walk, grouping)
            if (layout.otherDirs.isNotEmpty()) {
                val nested = layout.otherDirs.flatMap { interpret(it, depth + 1, walk) }
                if (nested.isNotEmpty()) {
                    return LooseContent.attach(grouping.groups).map { (g, extra) -> fileGame(g, extra, walk) } + nested
                }
            }
            return listOf(multiFileGame(folder, children, grouping, layout, walk))
        }
        if (layout.baseDirs.isNotEmpty()) return listOf(multiFileGame(folder, children, grouping, layout, walk))
        val games = scanLevel(folder.path, children, depth, walk, grouping)
        if (games.isEmpty() && walk.platform.id.value in FOLDER_NATIVE) {
            return listOf(folderGame(folder, children, folder.path, FolderInterpretation.FOLDER_IS_GAME, walk))
        }
        return games
    }

    private suspend fun folderAsGame(folder: FsEntry, children: List<FsEntry>, walk: Walk): ScannedGame {
        dirAsFile(folder, children, walk)?.let { return it }
        structure(folder, children, walk)?.let { return it }
        val layout = Layout(children, options)
        val grouping = grouper.group(folder.path, children, walk::isGameFile)
        if (grouping.groups.isNotEmpty() || layout.baseDirs.isNotEmpty()) {
            val game = multiFileGame(folder, children, grouping, layout, walk)
            if (game.launchPath != folder.path) return game
        }
        return folderGame(folder, children, folder.path, FolderInterpretation.FOLDER_IS_GAME, walk)
    }

    private fun browserGame(folder: FsEntry, children: List<FsEntry>, walk: Walk): ScannedGame =
        folderGame(folder, children, folder.path, FolderInterpretation.FOLDER_BROWSER, walk)

    /**
     * Sorts a game folder's subfolders into RomM categories, base-game folders and the rest.
     * Category names win over the media exclusions here: `manuals/` or `screenshots/` directly
     * inside a game folder are RomM categories, while directly inside a platform folder they are
     * excluded media folders.
     */
    private class Layout(children: List<FsEntry>, options: ScanOptions) {
        private val dirs = children.filter { it.isDirectory && !it.name.startsWith(".") }
        val baseDirs = dirs.filter { isBaseFolder(it.name) }
        val categoryDirs = dirs.filter { it !in baseDirs && ContentKind.ofFolder(it.name) != null }
        val otherDirs = dirs.filter {
            it !in baseDirs && it !in categoryDirs && !ScanRules.isExcludedFolder(it.name, options.extraExcludedFolders)
        }
    }

    private suspend fun multiFileGame(
        folder: FsEntry,
        children: List<FsEntry>,
        grouping: DiscGrouping,
        layout: Layout,
        walk: Walk,
    ): ScannedGame {
        val title = folderTitle(folder, walk)
        val content = ArrayList<ChildContent>()
        val extraGroups = ArrayList<FileGroup>()
        var sizeBytes = directFileSize(children)
        var modifiedAt = maxOf(folder.modifiedAt, children.maxOfOrNull { it.modifiedAt } ?: 0L)

        var main = pickMain(grouping.groups.filter { LooseContent.markerKind(it) == null }, title, walk.platform)
        extraGroups += grouping.groups.filter { it !== main }
        for (baseDir in layout.baseDirs) {
            val entries = walk.list(baseDir.path) ?: continue
            sizeBytes += entries.filter { !it.isDirectory }.sumOf { it.sizeBytes }
            modifiedAt = maxOf(modifiedAt, entries.maxOfOrNull { it.modifiedAt } ?: 0L)
            val baseGrouping = grouper.group(baseDir.path, entries, walk::isGameFile)
            val candidate = if (main == null) {
                pickMain(baseGrouping.groups.filter { LooseContent.markerKind(it) == null }, title, walk.platform)
            } else {
                null
            }
            if (candidate != null) main = candidate
            extraGroups += baseGrouping.groups.filter { it !== candidate }
        }

        for (group in extraGroups) content += LooseContent.child(LooseContent.childKind(group), group)

        val grouped = grouping.groups.flatMap { g -> g.members.map { FsPath.normalize(it.path) } }.toSet()
        for (file in children.filter { !it.isDirectory }) {
            val path = FsPath.normalize(file.path)
            if (path in grouped || path in grouping.hidden || ScanRules.isIgnoredFile(file.name)) continue
            LooseContent.looseFileKind(file)?.let { content += LooseContent.child(it, file) }
        }

        for (dir in layout.categoryDirs) {
            val kind = ContentKind.ofFolder(dir.name) ?: continue
            val entries = walk.list(dir.path) ?: continue
            for (entry in entries.sortedBy { it.name.lowercase() }) {
                if (entry.name.startsWith(".") || (!entry.isDirectory && ScanRules.isIgnoredFile(entry.name))) continue
                content += LooseContent.child(kind, entry)
                sizeBytes += entry.sizeBytes
                modifiedAt = maxOf(modifiedAt, entry.modifiedAt)
            }
        }

        val folderTags = FilenameParser.parse(folder.name, hasExtension = false).tags
        return ScannedGame(
            platformId = walk.platform.id,
            sourceId = walk.sourceId,
            path = folder.path,
            kind = LocationKind.FOLDER,
            launchPath = main?.primary?.path ?: folder.path,
            title = title,
            tags = mergeTags(folderTags, main?.let { tagsOf(it) }),
            content = content,
            discs = main?.discs.orEmpty(),
            sizeBytes = sizeBytes,
            modifiedAt = modifiedAt,
            interpretation = FolderInterpretation.MULTI_FILE_GAME,
        )
    }

    /** The main game among [groups]: playlists and disc sets first, clean dumps over hacks and betas. */
    private fun pickMain(groups: List<FileGroup>, folderTitle: String, platform: Platform): FileGroup? =
        groups.maxWithOrNull(
            compareBy<FileGroup> { score(it, folderTitle, platform) }
                .thenByDescending { it.primary.name.length }
                .thenByDescending { it.primary.name.lowercase() },
        )

    private fun score(group: FileGroup, folderTitle: String, platform: Platform): Int {
        var s = when (group.kind) {
            GroupKind.PLAYLIST -> 50
            GroupKind.MULTI_DISC -> 40
            GroupKind.SINGLE -> 0
        }
        val flags = group.parsed.tags.flags
        if (NameFlags.BASE in flags) s += 20
        if (NameFlags.VERIFIED in flags) s += 10
        if (LooseContent.sameTitle(folderTitle, group.parsed.baseTitle)) s += 15
        s -= 30 * flags.count { it in DISFAVOURED_FLAGS }
        if (platform.id.value == "switch") {
            s += when (group.primary.extension) {
                "xci" -> 5
                "nsp" -> 4
                else -> 0
            }
        }
        return s
    }

    /** ES-DE "directory interpreted as file": `Game.cue/Game.cue`, or the folder itself (`Game.ps3/`). */
    private suspend fun dirAsFile(folder: FsEntry, children: List<FsEntry>, walk: Walk): ScannedGame? {
        val ext = FsPath.extension(folder.name)
        if (ext.isEmpty() || ext !in walk.platform.extensions) return null
        val inner = children.firstOrNull { !it.isDirectory && it.name.equals(folder.name, ignoreCase = true) }
        val discs = if (inner?.extension == "m3u") {
            fs.readText(inner.path)?.let { Playlists.parseM3u(it, folder.path) }.orEmpty().mapIndexed { i, path ->
                val parsed = FilenameParser.parse(FsPath.name(path))
                Disc(i + 1, parsed.tags.discNumber?.let { "Disc $it" } ?: FsPath.stem(FsPath.name(path)), path)
            }
        } else {
            emptyList()
        }
        val serial = if (inner == null) sfoSerial(folder, children, walk) else null
        return folderGame(folder, children, inner?.path ?: folder.path, FolderInterpretation.FOLDER_IS_GAME, walk, discs, serial)
    }

    /** Known "the folder is the game" layouts. */
    private suspend fun structure(folder: FsEntry, children: List<FsEntry>, walk: Walk): ScannedGame? {
        val byName = children.associateBy { it.name.lowercase() }
        fun dir(name: String) = byName[name]?.takeIf { it.isDirectory }
        fun file(name: String) = byName[name]?.takeIf { !it.isDirectory }

        suspend fun game(launch: String, serial: String? = null) =
            folderGame(folder, children, launch, FolderInterpretation.FOLDER_IS_GAME, walk, serial = serial)

        // PlayStation 3: disc layout, or PSN/HDD layout (USRDIR + PARAM.SFO / EBOOT.BIN).
        if (file("ps3_disc.sfb") != null || dir("ps3_game") != null) {
            return game(folder.path, sfoSerial(folder, children, walk))
        }
        dir("usrdir")?.let { usrdir ->
            val hasEboot = file("param.sfo") != null ||
                walk.list(usrdir.path).orEmpty().any { it.name.equals("EBOOT.BIN", ignoreCase = true) }
            if (hasEboot) return game(folder.path, sfoSerial(folder, children, walk))
        }
        // PS Vita: sce_sys/param.sfo.
        dir("sce_sys")?.let { sceSys ->
            val sfo = walk.list(sceSys.path).orEmpty().firstOrNull { it.name.equals("param.sfo", ignoreCase = true) }
            if (sfo != null) return game(folder.path, readSerial(sfo.path))
        }
        // PSP extracted disc.
        if (dir("psp_game") != null) return game(folder.path)
        // Wii U loadiine layout: code/ with content/ or meta/; Cemu launches code/*.rpx.
        dir("code")?.let { code ->
            if (dir("content") != null || dir("meta") != null) {
                val rpx = walk.list(code.path).orEmpty().filter { it.extension == "rpx" }.minByOrNull { it.name.lowercase() }
                return game(rpx?.path ?: folder.path)
            }
        }
        // Xbox 360 extracted game.
        file("default.xex")?.let { return game(it.path) }
        // PC games: a folder with a program (or exactly one shortcut) is one game. Several
        // shortcuts and no program is a folder of exported shortcuts, not a game.
        if (walk.platform.family == PlatformFamily.PC) {
            val launchables = children.filter { !it.isDirectory && it.extension in PC_LAUNCHABLE }
            val programs = launchables.count { it.extension != "desktop" }
            if (programs > 0 || launchables.size == 1) {
                return game(pcLaunchFile(folder, launchables)?.path ?: folder.path)
            }
        }
        return null
    }

    /** The one file to start a PC game folder with, or null when the choice is not clear. */
    private fun pcLaunchFile(folder: FsEntry, files: List<FsEntry>): FsEntry? {
        val title = LooseContent.titleKey(FsPath.stem(folder.name))
        fun pick(candidates: List<FsEntry>): FsEntry? = candidates.singleOrNull()
            ?: candidates.filter { LooseContent.titleKey(FsPath.stem(it.name)) == title }.singleOrNull()
        for (ext in listOf("desktop", "conf")) pick(files.filter { it.extension == ext })?.let { return it }
        val programs = files.filter { it.extension in setOf("exe", "bat", "com") && !PC_HELPER.containsMatchIn(it.name) }
        return pick(programs)
    }

    private suspend fun sfoSerial(folder: FsEntry, children: List<FsEntry>, walk: Walk): String? {
        FilenameParser.parse(folder.name, hasExtension = false).tags.serial?.let { return it }
        val candidates = buildList {
            children.firstOrNull { it.name.equals("PARAM.SFO", ignoreCase = true) }?.let { add(it.path) }
            children.firstOrNull { it.isDirectory && it.name.equals("PS3_GAME", ignoreCase = true) }?.let { ps3Game ->
                walk.list(ps3Game.path).orEmpty().firstOrNull { it.name.equals("PARAM.SFO", ignoreCase = true) }
                    ?.let { add(it.path) }
            }
        }
        return candidates.firstNotNullOfOrNull { readSerial(it) }
    }

    // PARAM.SFO is binary, but TITLE_ID is stored as plain ASCII, so a text scan finds it.
    private suspend fun readSerial(path: String): String? =
        fs.readText(path, SFO_READ_LIMIT)?.let { Serials.find(it)?.first }

    private fun folderTitle(folder: FsEntry, walk: Walk): String {
        val ext = FsPath.extension(folder.name)
        return if (ext.isNotEmpty() && ext in walk.platform.extensions) FsPath.stem(folder.name) else folder.name
    }

    private fun folderGame(
        folder: FsEntry,
        children: List<FsEntry>,
        launchPath: String,
        interpretation: FolderInterpretation,
        walk: Walk,
        discs: List<Disc> = emptyList(),
        serial: String? = null,
    ): ScannedGame {
        val tags = FilenameParser.parse(folder.name, hasExtension = false).tags
        return ScannedGame(
            platformId = walk.platform.id,
            sourceId = walk.sourceId,
            path = folder.path,
            kind = LocationKind.FOLDER,
            launchPath = launchPath,
            title = folderTitle(folder, walk),
            tags = if (tags.serial == null && serial != null) tags.copy(serial = serial) else tags,
            discs = discs,
            sizeBytes = directFileSize(children),
            modifiedAt = maxOf(folder.modifiedAt, children.maxOfOrNull { it.modifiedAt } ?: 0L),
            interpretation = interpretation,
        )
    }

    private suspend fun fileGame(group: FileGroup, extra: List<ChildContent>, walk: Walk): ScannedGame = ScannedGame(
        platformId = walk.platform.id,
        sourceId = walk.sourceId,
        path = group.primary.path,
        kind = LocationKind.FILE,
        launchPath = group.primary.path,
        title = group.title,
        tags = tagsOf(group).let { tags -> if (tags.serial == null) tags.copy(serial = injectedSerial(group.primary)) else tags },
        content = extra,
        discs = group.discs,
        sizeBytes = group.sizeBytes,
        modifiedAt = group.modifiedAt,
        interpretation = if (group.kind == GroupKind.SINGLE) FolderInterpretation.SINGLE_FILE else FolderInterpretation.MULTI_DISC,
    )

    /**
     * ES-DE's `.ps3` and `.psvita` files are tiny text files holding only a title id (for emulators
     * that boot installed titles by id). Returns that id, or null for anything else.
     */
    private suspend fun injectedSerial(file: FsEntry): String? {
        if (file.extension !in TITLE_ID_FILES || file.sizeBytes > TITLE_ID_FILE_MAX) return null
        return fs.readText(file.path, TITLE_ID_FILE_MAX.toInt())?.let { Serials.find(it)?.first }
    }

    private fun tagsOf(group: FileGroup): FilenameTags = when (group.kind) {
        GroupKind.SINGLE -> group.parsed.tags
        GroupKind.MULTI_DISC, GroupKind.PLAYLIST -> group.parsed.tags.copy(discNumber = null, discTotal = group.discs.size)
    }

    // Folder name tags win; the main file fills what the folder name doesn't say.
    private fun mergeTags(folder: FilenameTags, file: FilenameTags?): FilenameTags {
        if (file == null) return folder
        return FilenameTags(
            regions = folder.regions.ifEmpty { file.regions },
            languages = folder.languages.ifEmpty { file.languages },
            revision = folder.revision ?: file.revision,
            version = folder.version ?: file.version,
            discNumber = null,
            discTotal = file.discTotal,
            flags = (folder.flags + file.flags).distinct(),
            serial = folder.serial ?: file.serial,
        )
    }

    private companion object {
        const val SFO_READ_LIMIT = 8 * 1024

        // ES-DE's %INJECT% reads at most 4096 bytes, so real title-id files are never larger.
        const val TITLE_ID_FILE_MAX = 4096L
        val TITLE_ID_FILES = setOf("ps3", "psvita")

        /** Platforms whose games are normally folders: an unrecognised folder is still one game. */
        val FOLDER_NATIVE = setOf("ps3", "ps4", "ps5", "psvita", "wiiu", "xbox", "xbox360", "win", "dos", "scummvm")

        val PC_LAUNCHABLE = setOf("desktop", "conf", "exe", "bat", "com")

        /** Installers, uninstallers and helpers that are never the game. */
        val PC_HELPER = Regex(
            "^(unins|uninstall|setup|install|vcredist|dxsetup|directx|dotnet|crash|unitycrashhandler|ue4prereq|redist|config)|updater|crashpad",
            RegexOption.IGNORE_CASE,
        )

        val DISFAVOURED_FLAGS = setOf(
            NameFlags.HACK, NameFlags.TRANSLATION, NameFlags.BETA, NameFlags.ALPHA, NameFlags.PROTO, NameFlags.DEMO,
            NameFlags.SAMPLE, NameFlags.BAD_DUMP, NameFlags.OVERDUMP, NameFlags.UNDERDUMP, NameFlags.TRAINED,
            NameFlags.CRACKED, NameFlags.PIRATE, NameFlags.VIRUS, NameFlags.BAD_CHECKSUM, NameFlags.UPDATE, NameFlags.DLC,
        )

        /**
         * Size of the files directly in a folder game (saves and temp files excluded). Folder
         * structures such as PS3 titles are not walked for size, so their size is a lower bound.
         */
        fun directFileSize(children: List<FsEntry>): Long =
            children.filter { !it.isDirectory && !ScanRules.isIgnoredFile(it.name) }.sumOf { it.sizeBytes }

        fun isBaseFolder(name: String): Boolean {
            val n = name.trim().lowercase()
            return n == "base" || n == "base game" || n == "basegame" || ContentKind.ofFolder(n) == ContentKind.GAME
        }
    }
}
