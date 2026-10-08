package io.github.matiyaaa.fuse.library.scan

import io.github.matiyaaa.fuse.library.FsAccessException
import io.github.matiyaaa.fuse.library.FsEntry
import io.github.matiyaaa.fuse.library.FsPath
import io.github.matiyaaa.fuse.library.FuseFileSystem
import io.github.matiyaaa.fuse.library.content.Cia
import io.github.matiyaaa.fuse.library.content.ContentSourceReader
import io.github.matiyaaa.fuse.library.content.PackageKind
import io.github.matiyaaa.fuse.library.content.PsPackages
import io.github.matiyaaa.fuse.library.disc.ParamSfo
import io.github.matiyaaa.fuse.library.parse.FilenameParser
import io.github.matiyaaa.fuse.library.parse.NameFlags
import io.github.matiyaaa.fuse.library.parse.Serials
import io.github.matiyaaa.fuse.library.parse.SwitchNames
import io.github.matiyaaa.fuse.model.ChildContent
import io.github.matiyaaa.fuse.model.ContentKind
import io.github.matiyaaa.fuse.model.Disc
import io.github.matiyaaa.fuse.model.FilenameTags
import io.github.matiyaaa.fuse.model.FolderInterpretation
import io.github.matiyaaa.fuse.model.FolderPolicy
import io.github.matiyaaa.fuse.model.LibrarySourceId
import io.github.matiyaaa.fuse.model.LocationKind
import io.github.matiyaaa.fuse.model.MediaKind
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
    /** Folders that were read and are not games themselves (a game found earlier there no longer is one). */
    val listed: Set<String> = emptySet(),
    /** Emulator data folders skipped whole: nothing below them is a game. */
    val skipped: Set<String> = emptySet(),
    /** Files that were games of their own and now belong to another game (a copy of it, its update or DLC). */
    val absorbed: Set<String> = emptySet(),
    val absorbedBy: Map<String, String> = emptyMap(),
)

/** A folder the person keeps one system's updates or DLC in, outside its games folder. */
data class ContentFolder(val kind: ContentKind, val path: String)

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
 * PlayStation 3 and Vita packages are told apart by their headers ([PsPackages]): an update or DLC
 * package belongs to the game package (or disc, or folder) with the same title id, a `.zip` on the
 * Vita is only a game when it holds a Vita title, and a package game carries its title id, which is
 * what it starts with once installed.
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
    private val contentReader = ContentSourceReader(fs)

    /** What a package file is, from its header: its title id, and UPDATE or DLC for content that isn't the game. */
    private data class PackageInfo(val titleId: String?, val kind: ContentKind?, val title: String?, val notAGame: Boolean = false, val serial: Boolean = true)

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
        contentFolders: List<ContentFolder> = emptyList(),
        onFolder: suspend (String) -> Unit = {},
    ): FolderScanResult {
        val walk = Walk(platform, sourceId, policies, onFolder)
        val root = FsPath.normalize(folderPath)
        walk.visited += fs.canonical(root) ?: root
        val children = listing ?: walk.list(root) ?: return walk.result(emptyList())
        // A platform-folder source may point directly at an extracted title, not its parent.
        // Establish the title boundary before treating any internal resources as candidate games.
        val rootPolicy = policies.policyFor(platform.id, root)
        val rootTitle = if (platform.id.value in STRUCTURED_ROOTS &&
            rootPolicy in setOf(FolderPolicy.AUTO, FolderPolicy.FOLDER_AS_GAME)
        ) {
            fs.stat(root)?.let { structure(it, children, walk) }
        } else null
        val games = rootTitle?.let(::listOf) ?: scanLevel(root, children, depth = 0, walk = walk)
        if (!walk.switch) return walk.result(games)
        // Folders the person keeps updates and DLC in, anywhere: their files join their games.
        val extra = ArrayList<ScannedGame>()
        for (folder in contentFolders) {
            val path = FsPath.normalize(folder.path)
            if (path.isEmpty() || path == root || path.startsWith("$root/")) continue
            extra += contentFiles(path, folder.kind, 0, walk)
        }
        return walk.result(SwitchContent.consolidate(games + extra, walk::sizeOf, walk.forced, walk.absorbed, walk.absorbedBy))
    }

    /** Every Switch file in [dir] (and its folders, a few levels down), each of [kind]. */
    private suspend fun contentFiles(dir: String, kind: ContentKind, depth: Int, walk: Walk): List<ScannedGame> {
        if (depth > 3 || !walk.visited.add(fs.canonical(dir) ?: dir)) return emptyList()
        val children = walk.list(dir) ?: return emptyList()
        val grouping = grouper.group(dir, children, walk::isGameFile)
        val out = ArrayList<ScannedGame>()
        for (group in grouping.groups) {
            walk.forced[FsPath.normalize(group.primary.path)] = kind
            out += fileGame(group, emptyList(), walk)
        }
        for (sub in children.filter { it.isDirectory && !it.name.startsWith(".") }) out += contentFiles(sub.path, kind, depth + 1, walk)
        return out
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
        val listed = HashSet<String>()
        val skipped = HashSet<String>()
        val errors = ArrayList<String>()
        var complete = true
        var folders = 0

        // Files owned by a playlist or cue sheet at a higher level (an .m3u listing "Discs/x.chd").
        val hidden = HashSet<String>()

        /** Package headers read during this walk, by path. */
        val packages = HashMap<String, PackageInfo>()

        /** Switch names follow their own rules (see [SwitchContent]). */
        val switch = platform.id.value in SwitchContent.platforms

        /** Each game file's own size, by path. */
        val sizes = HashMap<String, Long>()

        /** Files from folders set aside for updates or DLC, with that kind. */
        val forced = HashMap<String, ContentKind>()

        /** Files merged into another game (see [FolderScanResult.absorbed]). */
        val absorbed = HashSet<String>()
        val absorbedBy = HashMap<String, String>()
        val folderContentKinds = HashMap<String, ContentKind>()

        fun sizeOf(path: String): Long? = sizes[FsPath.normalize(path)]

        /** UPDATE or DLC for a package that isn't a game, else what its name says. */
        fun kindOf(group: FileGroup): ContentKind? {
            packages[group.primary.path]?.kind?.let { return it }
            if (!switch) return LooseContent.markerKind(group)
            forced[FsPath.normalize(group.primary.path)]?.let { return it }
            return SwitchContent.kindOf(group.primary.name, FsPath.parent(group.primary.path)?.let(FsPath::name))
        }

        /** What tells titles apart: a package's title id, else the name. */
        fun titleKeyOf(group: FileGroup): String = packages[group.primary.path]?.titleId
            ?: if (switch) SwitchContent.titleKey(group.primary.name) else LooseContent.titleKey(group.parsed.baseTitle)

        fun isHidden(game: ScannedGame): Boolean =
            FsPath.normalize(game.launchPath) in hidden || FsPath.normalize(game.path) in hidden

        /** Lists [path], or returns null (and marks the walk incomplete) when it can't be read. */
        suspend fun list(path: String): List<FsEntry>? {
            currentCoroutineContext().ensureActive()
            folders++
            onFolder(path)
            return try {
                fs.list(path).also { listed += path }
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
            (!entry.isDirectory && entry.extension in platform.extensions &&
                !ScanRules.isIgnoredFile(entry.name) && !ScanRules.isBiosFile(platform, entry.name))
                .also { if (it) sizes[FsPath.normalize(entry.path)] = entry.sizeBytes }

        fun result(games: List<ScannedGame>): FolderScanResult {
            val gamePaths = games.map { it.path }.toSet()
            return FolderScanResult(games, complete, errors.toList(), folders, listed - gamePaths, skipped.toSet(), absorbed - gamePaths, absorbedBy.toMap())
        }
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
        for ((group, extra) in attach(readPackages(grouping.groups, walk), walk)) games += fileGame(group, extra, walk)
        val native = walk.platform.id.value in FOLDER_NATIVE
        for (sub in children.filter { it.isDirectory }.sortedBy { it.name.lowercase() }) {
            // An emulator's own data folders (saves, caches, system files) are never games.
            if (native && isEmulatorData(sub.name)) {
                walk.skipped += sub.path
                continue
            }
            // Its storage folders (Cemu's mlc01/usr/title/00050000) only lead to the games inside.
            games += interpret(sub, if (native && isEmulatorStorage(sub.name)) depth else depth + 1, walk)
        }
        val folded = foldUpdates(games, walk)
        return if (walk.switch) SwitchContent.consolidate(folded, walk::sizeOf, walk.forced, walk.absorbed, walk.absorbedBy) else folded
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
        val native = walk.platform.id.value in FOLDER_NATIVE
        if (native && isEmulatorStorage(folder.name)) return scanLevel(folder.path, children, depth, walk)

        val layout = Layout(children, options)
        val grouped = grouper.group(folder.path, children, walk::isGameFile)
        val grouping = grouped.copy(groups = readPackages(grouped.groups, walk))
        walk.hidden += grouping.hidden
        val titles = grouping.groups.filter { walk.kindOf(it) == null }.map { walk.titleKeyOf(it) }.toSet()

        if (titles.size >= 2) return scanLevel(folder.path, children, depth, walk, grouping)
        if (titles.size == 1) {
            if (layout.categoryDirs.isNotEmpty() || layout.baseDirs.isNotEmpty()) {
                return listOf(multiFileGame(folder, children, grouping, layout, walk))
            }
            if (ScanRules.isOrganisationalName(folder.name)) return scanLevel(folder.path, children, depth, walk, grouping)
            if (layout.otherDirs.isNotEmpty()) {
                val nested = layout.otherDirs.flatMap { interpret(it, depth + 1, walk) }
                if (nested.isNotEmpty()) {
                    val here = attach(grouping.groups, walk).map { (g, extra) -> fileGame(g, extra, walk) } + nested
                    return if (walk.switch) SwitchContent.consolidate(here, walk::sizeOf, walk.forced, walk.absorbed, walk.absorbedBy) else here
                }
            }
            return listOf(multiFileGame(folder, children, grouping, layout, walk))
        }
        if (layout.baseDirs.isNotEmpty()) return listOf(multiFileGame(folder, children, grouping, layout, walk))
        val games = scanLevel(folder.path, children, depth, walk, grouping)
        // Only a folder right inside the system's folder is a game for having nothing Fuse knows in
        // it; deeper ones are a game's own folders (data, audio, saves), which once made thousands.
        if (games.isEmpty() && native && depth <= 1) {
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

        var main = pickMain(grouping.groups.filter { walk.kindOf(it) == null }, title, walk.platform)
        extraGroups += grouping.groups.filter { it !== main }
        for (baseDir in layout.baseDirs) {
            val entries = walk.list(baseDir.path) ?: continue
            sizeBytes += entries.filter { !it.isDirectory }.sumOf { it.sizeBytes }
            modifiedAt = maxOf(modifiedAt, entries.maxOfOrNull { it.modifiedAt } ?: 0L)
            val baseGroups = readPackages(grouper.group(baseDir.path, entries, walk::isGameFile).groups, walk)
            val candidate = if (main == null) {
                pickMain(baseGroups.filter { walk.kindOf(it) == null }, title, walk.platform)
            } else {
                null
            }
            if (candidate != null) main = candidate
            extraGroups += baseGroups.filter { it !== candidate }
        }

        for (group in extraGroups) content += LooseContent.child(walk.packages[group.primary.path]?.kind ?: childKind(group, main, walk), group)

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
            tags = mergeTags(folderTags, main?.let { m -> tagsOf(m).let { t -> if (t.serial == null) t.copy(serial = walk.packages[m.primary.path]?.takeIf { it.serial }?.titleId) else t } }),
            content = content,
            discs = main?.discs.orEmpty(),
            sizeBytes = sizeBytes,
            modifiedAt = modifiedAt,
            interpretation = FolderInterpretation.MULTI_FILE_GAME,
        )
    }

    /**
     * What a file beside a game's main file is. On Switch its name decides (an update or DLC), and a
     * smaller file named after the game with something more in brackets (`Game [New Uniform Set]`)
     * is DLC; elsewhere the usual markers.
     */
    private fun childKind(group: FileGroup, main: FileGroup?, walk: Walk): ContentKind {
        if (!walk.switch) return LooseContent.childKind(group)
        walk.kindOf(group)?.let { return it }
        val name = SwitchNames.read(group.primary.name)
        if (main != null && name.extra && group.sizeBytes < main.sizeBytes &&
            name.key == SwitchNames.read(main.primary.name).key
        ) {
            return ContentKind.DLC
        }
        return LooseContent.childKind(group)
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
        if (platform.id.value in SwitchContent.platforms) {
            s += when (group.primary.extension) {
                "xci", "xcz" -> 5
                "nsp", "nsz" -> 4
                else -> 0
            }
            // "Game [New Uniform Set]" beside "Game" is something for the game, not the game.
            if (SwitchNames.read(group.primary.name).extra) s -= 20
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

        suspend fun game(launch: String, serial: String? = null): ScannedGame {
            // All descendants belong to this recognized title, regardless of their names/extensions.
            // This also retires legacy resource entries even when no deeper directory was walked.
            walk.skipped += children.filter { it.isDirectory }.map { it.path }
            return folderGame(folder, children, launch, FolderInterpretation.FOLDER_IS_GAME, walk, serial = serial)
        }

        // PlayStation 3: disc layout, or PSN/HDD layout (USRDIR + PARAM.SFO / EBOOT.BIN).
        if (file("ps3_disc.sfb") != null || dir("ps3_game") != null) {
            return game(folder.path, sfoSerial(folder, children, walk))
        }
        dir("usrdir")?.let { usrdir ->
            val hasEboot = file("param.sfo") != null ||
                walk.list(usrdir.path).orEmpty().any { it.name.equals("EBOOT.BIN", ignoreCase = true) }
            if (hasEboot) return game(folder.path, sfoSerial(folder, children, walk))
        }
        // PS Vita and PS4: sce_sys/param.sfo. PS5: sce_sys/param.json beside eboot.bin.
        dir("sce_sys")?.let { sceSys ->
            val inside = walk.list(sceSys.path).orEmpty()
            val media = sceSysMedia(inside, walk)
            val sfo = inside.firstOrNull { it.name.equals("param.sfo", ignoreCase = true) }
            if (sfo != null) {
                val metadata = fs.readBytes(sfo.path, 0, SFO_READ_LIMIT)?.let(ParamSfo::strings).orEmpty()
                if (walk.platform.id.value == "ps4") {
                    // shadPS4 src/core/libraries/app_content/app_content.cpp validates CATEGORY ac
                    // under the configured addcont/title-id directory. Keep that folder in place.
                    when (metadata["CATEGORY"]?.lowercase()) {
                        "gp" -> walk.folderContentKinds[folder.path] = ContentKind.UPDATE
                        "ac" -> walk.folderContentKinds[folder.path] = ContentKind.DLC
                    }
                }
                return game(folder.path, metadata["TITLE_ID"] ?: readSerial(sfo.path)).copy(localMedia = media)
            }
            val json = inside.firstOrNull { it.name.equals("param.json", ignoreCase = true) }
            if (json != null) {
                // PS5 emulators start eboot.bin itself (SharpEmu takes nothing else; KytyPS5 takes either).
                val eboot = file("eboot.bin")?.path ?: folder.path
                return game(eboot, FilenameParser.parse(folder.name, hasExtension = false).tags.serial ?: readSerial(json.path)).copy(localMedia = media)
            }
        }
        // An incomplete modern dump may already have the launchable entry point while its
        // metadata is missing. Its resources are still owned by the title, never loose games.
        if (walk.platform.id.value in setOf("ps4", "ps5")) {
            file("eboot.bin")?.let { return game(it.path, sfoSerial(folder, children, walk)) }
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
        // An original Xbox title owns its extracted resources. Keep the directory launch
        // target: xemu requires a disc image and must not be handed an XBE as though it were one.
        if (walk.platform.id.value == "xbox" && file("default.xbe") != null) return game(folder.path)
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

    /**
     * The game's own art from its sce_sys folder: on PS4 and PS5 icon0.png is the 512 pixel square
     * tile the console shows and pic1.png (else pic0.png) the full-screen backdrop behind it; on the
     * Vita icon0.png is the small bubble icon and pic0.png the backdrop.
     */
    private fun sceSysMedia(inside: List<FsEntry>, walk: Walk): Map<MediaKind, String> {
        fun png(name: String) = inside.firstOrNull { !it.isDirectory && it.name.equals(name, ignoreCase = true) }?.path
        val icon = png("icon0.png")
        val backdrop = png("pic1.png") ?: png("pic0.png")
        return buildMap {
            if (icon != null) {
                put(MediaKind.ICON, icon)
                if (walk.platform.id.value != "psvita") put(MediaKind.SQUARE, icon)
            }
            if (backdrop != null) put(MediaKind.HERO, backdrop)
        }
    }

    /**
     * PS4 and PS5 dumps keep an update beside its game, as `CUSA00001-UPDATE` or `CUSA00001-patch`
     * (shadPS4's layout): one game with an update, not two games. An update with no game beside it
     * stays as it is.
     */
    private fun foldUpdates(games: List<ScannedGame>, walk: Walk): List<ScannedGame> {
        if (walk.platform.id.value != "ps4" && walk.platform.id.value != "ps5") return games
        val updates = games.filter { it.path in walk.folderContentKinds || UPDATE_FOLDER.matches(FsPath.name(it.path)) }
        if (updates.isEmpty()) return games
        val byName = games.associateBy { FsPath.name(it.path).lowercase() }
        val folded = HashMap<String, List<ChildContent>>()
        val absorbed = HashSet<String>()
        for (u in updates) {
            val base = UPDATE_FOLDER.matchEntire(FsPath.name(u.path))?.groupValues?.get(1)?.lowercase()
            val candidates = games.filter { it !== u && it !in updates }
            val named = base?.let(byName::get)?.takeIf { it in candidates &&
                (u.tags.serial == null || it.tags.serial == null || u.tags.serial == it.tags.serial) }
            val owner = named ?: candidates.singleOrNull { it.tags.serial != null && it.tags.serial == u.tags.serial }
            if (owner == null) continue
            val kind = walk.folderContentKinds[u.path] ?: ContentKind.UPDATE
            folded[owner.path] = folded[owner.path].orEmpty() + ChildContent(kind, FsPath.name(u.path), u.path, isDirectory = true, sizeBytes = u.sizeBytes)
            absorbed += u.path
            walk.absorbedBy[u.path] = owner.path
        }
        return games.filter { it.path !in absorbed }.map { g -> folded[g.path]?.let { g.copy(content = g.content + it) } ?: g }
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

    /**
     * Reads the header of each package among [groups] (PS3 and Vita only; a few kilobytes each) and
     * returns the groups that are games or game content: a `.zip` without a Vita title is left out.
     */
    private suspend fun readPackages(groups: List<FileGroup>, walk: Walk): List<FileGroup> {
        val id = walk.platform.id.value
        if (id != "ps3" && id != "psvita" && id != "3ds" && id != "new-nintendo-3ds") return groups
        val threeDs = id == "3ds" || id == "new-nintendo-3ds"
        return groups.filter { g ->
            val f = g.primary
            val info = walk.packages[f.path] ?: (if (threeDs) {
                // A 3DS update or DLC .cia joins its game by the game's title id (never shown as a serial).
                when (f.extension) {
                    "cia" -> Cia.read(fs, f.path)?.let { c ->
                        PackageInfo(c.gameId, if (c.kind == PackageKind.UPDATE) ContentKind.UPDATE else if (c.kind == PackageKind.DLC) ContentKind.DLC else null, null, serial = false)
                    }
                    "3ds", "cci" -> Cia.cartridgeId(fs, f.path)?.let { PackageInfo(it, null, null, serial = false) }
                    else -> null
                }
            } else when (f.extension) {
                "pkg" -> PsPackages.read(fs, f.path)?.let { p ->
                    PackageInfo(
                        p.titleId,
                        when (p.kind) {
                            PackageKind.UPDATE -> ContentKind.UPDATE
                            PackageKind.DLC -> ContentKind.DLC
                            else -> null
                        },
                        p.title,
                    )
                }
                "vpk", "zip" -> contentReader.vitaArchive(f)?.let { a ->
                    PackageInfo(a.titleId, if (a.category == "gp") ContentKind.UPDATE else if (a.category == "ac") ContentKind.DLC else null, a.title)
                    // A zip Fuse can't look inside (packed with a method it doesn't unpack, or laid out
                    // its own way) is still a game when it is game-sized; small ones are notes and photos.
                } ?: PackageInfo(null, null, null, notAGame = f.extension == "zip" && f.sizeBytes < MIN_VITA_ZIP)
                else -> null
            })?.also { walk.packages[f.path] = it }
            info?.notAGame != true
        }
    }

    /**
     * [LooseContent.attach], then each update and DLC package goes to the game with its title id
     * (a package game, a disc or a folder named with it). One whose game isn't here stays on its own.
     */
    private fun attach(groups: List<FileGroup>, walk: Walk): List<Pair<FileGroup, List<ChildContent>>> {
        // Switch updates and DLC join their games over the whole system at once (SwitchContent).
        if (walk.switch) return groups.map { it to emptyList() }
        if (walk.packages.isEmpty()) return LooseContent.attach(groups)
        val extras = groups.filter { walk.packages[it.primary.path]?.kind != null }
        val rest = groups.filter { g -> extras.none { it === g } }
        val attached = LooseContent.attach(rest).map { (g, c) -> g to c.toMutableList() }
        val alone = ArrayList<Pair<FileGroup, List<ChildContent>>>()
        for (x in extras) {
            val info = walk.packages.getValue(x.primary.path)
            val owner = info.titleId?.let { id ->
                attached.firstOrNull { (g, _) -> (walk.packages[g.primary.path]?.titleId ?: g.parsed.tags.serial?.uppercase()) == id }
            }
            if (owner != null) owner.second += LooseContent.child(info.kind!!, x) else alone += x to emptyList()
        }
        return attached + alone
    }

    private suspend fun fileGame(group: FileGroup, extra: List<ChildContent>, walk: Walk): ScannedGame = ScannedGame(
        platformId = walk.platform.id,
        sourceId = walk.sourceId,
        path = group.primary.path,
        kind = LocationKind.FILE,
        launchPath = group.primary.path,
        title = packageTitle(group, walk) ?: group.title,
        tags = tagsOf(group).let { tags -> if (tags.serial == null) tags.copy(serial = walk.packages[group.primary.path]?.takeIf { it.serial }?.titleId ?: injectedSerial(group.primary)) else tags },
        content = extra,
        discs = group.discs,
        sizeBytes = group.sizeBytes,
        modifiedAt = group.modifiedAt,
        interpretation = if (group.kind == GroupKind.SINGLE) FolderInterpretation.SINGLE_FILE else FolderInterpretation.MULTI_DISC,
    )

    /** A Vita package's own title, for a file named only with its content id or title id. */
    private fun packageTitle(group: FileGroup, walk: Walk): String? {
        val title = walk.packages[group.primary.path]?.title ?: return null
        return title.takeIf { CODE_NAME.containsMatchIn(group.title) && !LooseContent.sameTitle(group.title, title) }
    }

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

        /** A name that is a content id or a bare title id (`UP9000-PCSA00001_00-...`, `PCSA00001`). */
        val CODE_NAME = Regex("^([A-Z]{2}\\d{4}-)?[A-Z]{4}\\d{5}([_\\s-]|$)")

        // ES-DE's %INJECT% reads at most 4096 bytes, so real title-id files are never larger.
        const val TITLE_ID_FILE_MAX = 4096L
        val TITLE_ID_FILES = setOf("ps3", "psvita")

        /** The smallest .zip in a Vita folder taken as a game without reading a title from it. */
        const val MIN_VITA_ZIP = 24L * 1024 * 1024

        /**
         * Folders emulators keep beside their games when their storage is copied into a library:
         * saves, caches, system titles and firmware. Never games, never walked.
         */
        val EMULATOR_DATA = setOf(
            "save", "saves", "savedata", "boss", "sys", "dev_flash", "dev_flash2", "dev_flash3", "dev_usb000",
            "dev_bdvd", "disc_cache", "crash_report", "caches", "cache", "shadercache", "shader_cache", "logs",
            "temp", "tmp", "home", "photo", "screenshots", "captures", "trophy", "license", "licenses",
        )

        /** Emulator storage folders that only lead to the games inside them (Cemu, RPCS3, Vita3K). */
        val EMULATOR_STORAGE = setOf("mlc01", "dev_hdd0", "ux0", "usr", "title", "app")

        private val TITLE_GROUP = Regex("^[0-9a-f]{8}$", RegexOption.IGNORE_CASE)

        fun isEmulatorData(name: String): Boolean = name.trim().lowercase() in EMULATOR_DATA

        /** Storage folders, and Wii U title groups such as `00050000`. */
        fun isEmulatorStorage(name: String): Boolean {
            val n = name.trim().lowercase()
            return n in EMULATOR_STORAGE || TITLE_GROUP.matches(n)
        }

        /** Platforms whose games are normally folders: an unrecognised folder is still one game. */
        val UPDATE_FOLDER = Regex("(?i)^(.+?)[-_ ](?:update|patch|upd)$")
        val FOLDER_NATIVE = setOf("ps3", "ps4", "ps5", "psvita", "wiiu", "xbox", "xbox360", "win", "dos", "scummvm")
        // These markers establish one title even when its folder is the configured source root.
        // PC roots deliberately remain collections: one program does not own adjacent game folders.
        val STRUCTURED_ROOTS = setOf("ps3", "ps4", "ps5", "psvita", "psp", "wiiu", "xbox", "xbox360")

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
