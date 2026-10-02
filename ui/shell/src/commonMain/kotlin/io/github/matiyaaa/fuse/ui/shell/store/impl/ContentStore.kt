package io.github.matiyaaa.fuse.ui.shell.store.impl

import io.github.matiyaaa.fuse.launch.EmulatorAdapter
import io.github.matiyaaa.fuse.launch.InstallOnlyFiles
import io.github.matiyaaa.fuse.library.FsPath
import io.github.matiyaaa.fuse.library.content.ContentEmulator
import io.github.matiyaaa.fuse.library.content.ContentPlan
import io.github.matiyaaa.fuse.library.content.ContentPlanner
import io.github.matiyaaa.fuse.library.content.ContentSourceReader
import io.github.matiyaaa.fuse.library.content.ContentSources
import io.github.matiyaaa.fuse.library.content.InstalledContent
import io.github.matiyaaa.fuse.library.content.InstalledContentReader
import io.github.matiyaaa.fuse.library.content.ItemRole
import io.github.matiyaaa.fuse.library.content.LicenceSource
import io.github.matiyaaa.fuse.library.content.Licences
import io.github.matiyaaa.fuse.library.content.PlanItem
import io.github.matiyaaa.fuse.library.content.PsPackages
import io.github.matiyaaa.fuse.model.Game
import io.github.matiyaaa.fuse.model.GameId
import io.github.matiyaaa.fuse.model.Host
import io.github.matiyaaa.fuse.model.InstalledEmulator
import io.github.matiyaaa.fuse.model.LocationKind
import io.github.matiyaaa.fuse.model.StorageVolume
import io.github.matiyaaa.fuse.ui.shell.store.ContentOps
import io.github.matiyaaa.fuse.ui.shell.store.GameContentView
import io.github.matiyaaa.fuse.ui.shell.store.InstallMode
import io.github.matiyaaa.fuse.ui.shell.store.InstallProgress
import io.github.matiyaaa.fuse.ui.shell.store.InstallReport
import io.github.matiyaaa.fuse.ui.shell.store.InstallerRun
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.cancellation.CancellationException

/**
 * Fuse's own installs of PlayStation 3 and Vita content, with nothing of RomM or Cartridge in the way.
 *
 * Everything an install does goes through the emulator's own documented installer: RPCS3's
 * `--headless --installpkg` (once per file; `.rap` and `.edat` licences are copied into exdata by
 * RPCS3 itself) and Vita3K's `--pkg <file> --zrif <key>`, or a `.vpk`/`.zip` given to Vita3K as its
 * game. Fuse never writes into an emulator's storage, its settings or its saves; the only file Fuse
 * writes is a copy of a licence under the name RPCS3 looks for, in Fuse's own folder.
 *
 * A step only counts as installed when Fuse reads it back from the emulator's storage afterwards
 * (RPCS3 always exits 0, so its exit code says nothing). The first step that didn't take stops the
 * run; running it again plans afresh, so what got in is never installed twice.
 */
internal class DefaultContentOps(
    private val ctx: StoreContext,
    private val emulators: DefaultEmulatorOps,
    private val volumes: () -> List<StorageVolume> = { emptyList() },
) : ContentOps {
    private val lock = Mutex()
    private val progressState = MutableStateFlow<InstallProgress?>(null)
    override val progress: StateFlow<InstallProgress?> = progressState
    private var running: Job? = null

    /** The emulator that installs a game's content, and where its storage is. */
    private class Target(val kind: ContentEmulator, val adapter: EmulatorAdapter?, val installed: InstalledEmulator?, val storage: List<String>)

    override suspend fun view(id: GameId, picked: Map<String, String>, keys: Map<String, String>): GameContentView? {
        val game = ctx.data.games.get(id) ?: return null
        val kind = when (game.platformId.value) {
            "ps3" -> ContentEmulator.RPCS3
            "psvita" -> ContentEmulator.VITA3K
            else -> return null
        }
        val sources = sources(game)
        if (sources.isEmpty) return null
        val target = target(game, kind)
        val installed = installedContent(target)
        val recorded = recorded(sources)
        val plan = ContentPlanner.plan(kind, withKeys(sources, keys), installed, recorded, picked, playsWithoutInstall = !InstallOnlyFiles.matches(game.platformId, game.location.launchPath))
        val mode = when {
            target.adapter != null && target.installed != null && ctx.services.emulatorFiles != null -> InstallMode.FUSE
            ctx.host == Host.ANDROID && target.installed != null -> InstallMode.GUIDED
            else -> InstallMode.UNAVAILABLE
        }
        val platform = ctx.platforms.byId(game.platformId)?.name ?: game.platformId.value
        val note = when (mode) {
            InstallMode.FUSE -> if (!plan.storageReadable) "${target.installed!!.name} hasn't been set up yet. Start it once, then come back: Fuse reads its storage to know what is installed." else null
            InstallMode.GUIDED -> "${target.installed!!.name} installs from its own menu. Fuse can't look inside its storage on this device, so check there that each file went in."
            InstallMode.UNAVAILABLE -> if (ctx.host == Host.ANDROID) {
                "Install an emulator for the $platform first, then install these files from its menu."
            } else {
                "Install ${kind.label} to install this game. Fuse finds it on its own."
            }
        }
        return GameContentView(
            gameId = id,
            title = game.displayTitle,
            platformName = platform,
            emulatorId = target.installed?.id,
            emulatorName = target.installed?.name ?: kind.label,
            mode = mode,
            plan = plan,
            note = note,
            places = places(),
            unreadable = sources.unreadable,
        )
    }

    override suspend fun install(id: GameId, picked: Map<String, String>, keys: Map<String, String>): InstallReport = lock.withLock {
        val done = ArrayList<PlanItem>()
        // Its own job, so stopping it (cancel) never cancels whoever asked for it.
        val job = ctx.scope.async { run(id, picked, keys, done) }
        running = job
        try {
            job.await()
        } catch (e: CancellationException) {
            if (!job.isCancelled || currentCoroutineContext()[Job]?.isActive == false) throw e
            InstallReport(done.toList(), cancelled = true)
        } finally {
            running = null
            progressState.value = null
            ctx.services.emulatorFiles?.clearStaged()
        }
    }

    override fun cancel() {
        running?.cancel()
    }

    private suspend fun run(id: GameId, picked: Map<String, String>, keys: Map<String, String>, done: MutableList<PlanItem>): InstallReport {
        val view = view(id, picked, keys) ?: return InstallReport(emptyList(), message = "This game has nothing to install.")
        val files = ctx.services.emulatorFiles
        val game = ctx.data.games.get(id) ?: return InstallReport(emptyList(), message = "The game is gone from the library.")
        val target = target(game, view.plan.emulator)
        if (view.mode != InstallMode.FUSE || files == null || target.adapter == null || target.installed == null) {
            return InstallReport(emptyList(), message = view.note ?: "Installing isn't available here.")
        }
        if (!view.plan.storageReadable) return InstallReport(emptyList(), message = view.note)
        val steps = view.plan.toInstall
        if (steps.isEmpty()) return InstallReport(emptyList())
        for ((i, item) in steps.withIndex()) {
            progressState.value = InstallProgress(id, i + 1, steps.size, item)
            if (!ctx.services.fs.exists(item.path)) {
                return InstallReport(done.toList(), item, "${item.fileName} isn't there any more. Connect its drive and try again.")
            }
            val key = if (view.plan.emulator == ContentEmulator.VITA3K && !item.archive) zrifFor(item, keys) else null
            if (view.plan.emulator == ContentEmulator.VITA3K && !item.archive && key == null) {
                return InstallReport(done.toList(), item, "${item.fileName} needs its zRIF. Add it, then install again.")
            }
            // RPCS3 copies a licence under the file's own name, so a .rap under any other goes in as a copy with the right one.
            val installAs = item.installAs
            val file = if (item.role == ItemRole.LICENCE && installAs != null && installAs != item.fileName) {
                files.stage(item.path, installAs) ?: return InstallReport(done.toList(), item, "Fuse couldn't make a copy of ${item.fileName} named $installAs.")
            } else {
                item.path
            }
            val command = target.adapter.packageInstall(target.installed, file, key)
                ?: return InstallReport(done.toList(), item, "${target.installed.name} can't install ${item.fileName}.")
            val startedAt = ctx.now()
            val result = files.runInstaller(
                InstallerRun(
                    command.argv,
                    command.workingDir,
                    // Vita3K starts the game it installed from a .vpk; on Linux it does so with no window.
                    env = if (item.archive && ctx.host == Host.LINUX) mapOf("QT_QPA_PLATFORM" to "offscreen") else emptyMap(),
                    stopWhen = if (item.archive) VITA_ARCHIVE_DONE else null,
                ),
            ) { line -> progressState.value = InstallProgress(id, i + 1, steps.size, item, scrub(line, key).take(160)) }
                ?: return InstallReport(done.toList(), item, "Fuse can't run installers on this device.")
            if (!result.started) {
                return InstallReport(done.toList(), item, "${target.installed.name} didn't start. Check it still opens, then try again.", scrub(result.output, key))
            }
            val after = installedContent(target)
            if (!verified(item, after, startedAt)) {
                val said = scrub(result.output, key).lines().filter { it.isNotBlank() }.takeLast(12).joinToString("\n")
                val why = if (result.timedOut) "took too long and was stopped" else "finished, but ${item.fileName} isn't in its storage"
                return InstallReport(done.toList(), item, "${target.installed.name} $why. Nothing after it was installed.", said.ifEmpty { null })
            }
            record(item)
            done += item
        }
        return InstallReport(done.toList())
    }

    /** Whether [item] is in the emulator now, as its storage shows. */
    private suspend fun verified(item: PlanItem, after: InstalledContent, startedAt: Long): Boolean {
        val id = item.titleId
        return when (item.role) {
            ItemRole.LICENCE -> (item.contentId ?: item.installAs?.let { FsPath.stem(it) })?.let(after::hasLicence) == true
            // On PS3 a "GD" folder is a disc game's update, not the game; a Vita game's own category is "gd".
            ItemRole.GAME -> id != null && after.games[id]?.let { after.emulator == ContentEmulator.VITA3K || it.category?.uppercase() != "GD" } == true
            ItemRole.UPDATE -> when {
                id == null -> false
                item.version != null -> after.versionOf(id)?.let { PsPackages.compareVersions(it, item.version) >= 0 } == true
                after.emulator == ContentEmulator.VITA3K -> after.patches[id] != null
                else -> changedSince(after.games[id]?.dir, startedAt)
            }
            ItemRole.DLC -> when {
                id == null -> false
                after.emulator == ContentEmulator.VITA3K -> "$id/${item.contentId?.takeLast(16)}" in after.addons
                // PS3 DLC goes into the game's own folder: something in it has to be new.
                else -> changedSince(after.games[id]?.dir, startedAt)
            }
        }
    }

    /** True when [dir], its USRDIR or a folder in it changed after [since]. */
    private suspend fun changedSince(dir: String?, since: Long): Boolean {
        dir ?: return false
        val fs = ctx.services.fs
        val margin = since - 2_000
        val usrdir = FsPath.join(dir, "USRDIR")
        val candidates = listOf(dir, usrdir) + fs.listSafe(usrdir).filter { it.isDirectory }.map { it.path } + fs.listSafe(dir).map { it.path }
        return candidates.any { (fs.stat(it)?.modifiedAt ?: 0) >= margin }
    }

    /** The zRIF for a Vita package: the one found or pasted, else read from its licence file. */
    private suspend fun zrifFor(item: PlanItem, keys: Map<String, String>): String? {
        val licence = item.licence
        licence?.zrif?.let { return it }
        item.contentId?.let { cid -> keys[cid.uppercase()]?.trim()?.takeIf { it.isNotEmpty() }?.let { return it } }
        if (licence?.source == LicenceSource.BUILT_IN) return null
        val path = licence?.path ?: return null
        val fs = ctx.services.fs
        val size = fs.stat(path)?.sizeBytes ?: return null
        // A .rif (or work.bin) becomes the zRIF Vita3K takes; a text file gives the key it holds for this content.
        if (size in 0x40..1024 && (path.endsWith(".rif", true) || FsPath.name(path).equals("work.bin", true))) {
            return fs.readBytes(path, 0, size.toInt())?.let(Licences::zrifOf)
        }
        val text = fs.readText(path, 4 * 1024 * 1024) ?: return null
        return Licences.zrifsIn(text).firstOrNull { it.second.equals(licence.contentId, true) }?.first
    }

    /** Pasted zRIFs join the keys found on disk (only those that decode, for their own content). */
    private fun withKeys(sources: ContentSources, keys: Map<String, String>): ContentSources {
        if (keys.isEmpty()) return sources
        val pasted = keys.values.mapNotNull { z -> Licences.zrifContentId(z.trim())?.let { io.github.matiyaaa.fuse.library.content.ZrifKey(z.trim(), it, "") } }
        return sources.copy(keys = (pasted + sources.keys).distinctBy { it.contentId })
    }

    /**
     * The game's packages and licences: its own file or folder (two levels deep), the files the
     * scanner attached to it, and for a single file game the files beside it, kept only when they
     * are for this game's title id (another game's packages share the folder).
     */
    private suspend fun sources(game: Game): ContentSources {
        val fs = ctx.services.fs
        val own = ContentSourceReader(fs)
        val paths = buildList {
            add(game.location.path)
            game.content.forEach { add(it.path) }
        }
        val files = own.collect(paths).toMutableList()
        val beside = if (game.location.kind == LocationKind.FILE) FsPath.parent(game.location.path) else null
        if (beside != null) {
            val near = ContentSourceReader(fs, maxDepth = 0).collect(listOf(beside))
            files += near.filter { f -> files.none { it.path == f.path } && (f.extension in NEAR_EXTENSIONS || Licences.isKeyText(f.name)) }
        }
        val all = own.read(files)
        val ids = (all.packages.filter { p -> p.path == game.location.launchPath || game.content.any { it.path == p.path } }.mapNotNull { it.titleId } +
            all.archives.filter { a -> a.path == game.location.launchPath }.map { it.titleId } +
            listOfNotNull(game.tags.serial?.uppercase())).toSet()
        if (ids.isEmpty()) return all
        val inFolder = beside == null
        return all.copy(
            packages = all.packages.filter { it.titleId in ids },
            archives = all.archives.filter { it.titleId in ids },
            // A .rap under another name is only this game's when it is in the game's own folder.
            licences = all.licences.filter { l -> l.titleId?.let { it in ids } ?: (inFolder || FsPath.parent(l.path) != beside) },
            keys = all.keys.filter { it.titleId in ids },
            unreadable = all.unreadable.filter { p -> inFolder || FsPath.parent(p) != beside || ids.any { p.contains(it, ignoreCase = true) } },
        )
    }

    private suspend fun target(game: Game, kind: ContentEmulator): Target {
        if (ctx.installed.value.isEmpty()) emulators.detectNow()
        val installed = ctx.installed.value
        val forPlatform = ctx.registry.forPlatform(game.platformId, ctx.host)
        // The game's own emulator first, then any that installs packages, then any that plays the system.
        val installers = forPlatform.filter { it.packageExtensions.isNotEmpty() }
        val chosen = game.emulatorOverride?.let { o -> installers.firstOrNull { it.id == o } }
        val adapter = (listOfNotNull(chosen) + installers).firstOrNull { a -> installed.any { it.id == a.id } }
        val inst = adapter?.let { a -> installed.firstOrNull { it.id == a.id } }
            ?: forPlatform.firstNotNullOfOrNull { a -> installed.firstOrNull { it.id == a.id } }
        val files = ctx.services.emulatorFiles
        val storage = if (adapter != null && inst != null && files != null) {
            when (kind) {
                ContentEmulator.RPCS3 -> files.rpcs3Storage(inst)
                ContentEmulator.VITA3K -> files.vita3kStorage(inst)
            }
        } else {
            emptyList()
        }
        return Target(kind, adapter?.takeIf { inst?.id == it.id }, inst, storage)
    }

    private suspend fun installedContent(target: Target): InstalledContent {
        val reader = InstalledContentReader(ctx.services.fs)
        return when (target.kind) {
            ContentEmulator.RPCS3 -> reader.rpcs3(target.storage)
            ContentEmulator.VITA3K -> reader.vita3k(target.storage)
        }
    }

    /** Files Fuse installed and saw take (PS3 DLC can't be told apart in RPCS3's storage otherwise). */
    private suspend fun recorded(sources: ContentSources): Map<String, Long> =
        (sources.packages.map { it.path } + sources.licences.map { it.path }).mapNotNull { p ->
            ctx.data.cache.entry(RECORDS, p)?.valueJson?.toLongOrNull()?.let { p to it }
        }.toMap()

    private suspend fun record(item: PlanItem) {
        ctx.data.cache.put(RECORDS, item.path, item.sizeBytes.toString(), ctx.now(), ttlMs = null)
    }

    private fun places(): List<Pair<String, String>> = volumes().filter { it.removable }.flatMap { v ->
        v.mountPaths.map { FsPath.normalize(it) to v.label.ifBlank { "Removable drive" } }
    }.sortedByDescending { it.first.length }

    private suspend fun io.github.matiyaaa.fuse.library.FuseFileSystem.listSafe(path: String) = try {
        list(path)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        emptyList()
    }

    private suspend fun io.github.matiyaaa.fuse.library.FuseFileSystem.exists(path: String) = stat(path) != null

    private fun scrub(text: String, key: String?): String = if (key.isNullOrBlank()) text else text.replace(key, "(zRIF)")

    private companion object {
        const val RECORDS = "content.installed"
        val NEAR_EXTENSIONS = setOf("pkg", "rap", "edat", "rif", "vpk", "zip")

        /** What Vita3K prints once a .vpk/.zip is in (or can't be): it would start the game next (main.cpp). */
        val VITA_ARCHIVE_DONE = Regex("installed successfully|will auto-boot|not a supported content|installation failed|already installed|Failed to refresh apps list", RegexOption.IGNORE_CASE)
    }
}
