package io.github.matiyaaa.fuse.ui.shell.store.impl

import io.github.matiyaaa.fuse.integrations.KeyCheck
import io.github.matiyaaa.fuse.library.FsPath
import io.github.matiyaaa.fuse.library.scan.PlaylistCheck
import io.github.matiyaaa.fuse.library.storage.Volumes
import io.github.matiyaaa.fuse.model.BiosState
import io.github.matiyaaa.fuse.model.ScanPhase
import io.github.matiyaaa.fuse.model.SourceState
import io.github.matiyaaa.fuse.model.SourceStatus
import io.github.matiyaaa.fuse.ui.shell.store.HealthIssue
import io.github.matiyaaa.fuse.ui.shell.store.HealthOps
import io.github.matiyaaa.fuse.ui.shell.store.HealthReport
import io.github.matiyaaa.fuse.ui.shell.store.PlatformCard
import io.github.matiyaaa.fuse.ui.shell.store.Problem
import io.github.matiyaaa.fuse.ui.shell.store.ProblemAction
import io.github.matiyaaa.fuse.ui.shell.store.ProblemKind
import io.github.matiyaaa.fuse.ui.shell.store.Severity
import io.github.matiyaaa.fuse.ui.shell.store.UpdateState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch

/**
 * System health over the store's own state. The cheap checks (folders and drives, emulators,
 * firmware, provider keys, missing games, updates) follow that state as it changes; the game-file
 * checks (every disc a playlist names, every track a sheet lists) read small text files, so they run
 * after scans and when asked, in the background, and never more than one at a time. Library Health
 * (pictures whose files are gone, files nothing uses, covers) reads far more, so it runs only when
 * asked: when System health is opened, or after a repair.
 */
internal class DefaultHealthOps(
    private val ctx: StoreContext,
    private val engine: LibraryEngine,
    private val library: DefaultLibraryOps,
    private val media: DefaultMediaOps,
    private val updates: DefaultUpdateOps,
    private val storedKeys: () -> Set<String>,
    private val cartridge: () -> io.github.matiyaaa.fuse.model.CartridgeStatus,
) : HealthOps {
    private val state = MutableStateFlow(HealthReport())
    override val report: StateFlow<HealthReport> = state

    private var setup: List<HealthIssue> = emptyList()
    private var files: List<HealthIssue> = emptyList()

    /** Library Health's findings, from the last time System health was opened or a check asked for. */
    private var libraryFound: List<HealthIssue> = emptyList()
    private val found = java.util.concurrent.ConcurrentHashMap<String, List<HealthIssue>>()
    private val recheck = Channel<Unit>(Channel.CONFLATED)
    private var filesJob: Job? = null

    fun start() {
        ctx.scope.launch(Dispatchers.Default) {
            for (request in recheck) {
                // Changes come in bursts (a scan updates folders, systems and firmware together).
                delay(SETTLE_MS)
                while (recheck.tryReceive().isSuccess) Unit
                setup = try {
                    setupIssues()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    setup
                }
                publish()
            }
        }
        ctx.scope.launch {
            merge(
                engine.status.map { 0 },
                library.platforms.map { 1 },
                engine.bios.map { 2 },
                ctx.emulatorsDetected.map { 3 },
                media.keyChecks.map { 4 },
                updates.state.map { 5 },
                ctx.data.games.observeMissing().map { 6 },
                ctx.offline.map { 7 },
            ).collect { recheck.trySend(Unit) }
        }
        ctx.scope.launch {
            // Library Health isn't part of this: it reads every picture's file and the whole library, so it
            // runs when System health is opened or asks, never on the scan every start makes.
            engine.scan.map { it.phase }.distinctUntilChanged().collect { if (it == ScanPhase.DONE) checkFiles(library = false) }
        }
    }

    override fun check() {
        recheck.trySend(Unit)
        checkFiles(library = true)
    }

    override fun setFound(key: String, issues: List<HealthIssue>) {
        if (issues.isEmpty()) found.remove(key) else found[key] = issues
        publish()
    }

    private fun publish() {
        val all = (setup + files + libraryFound + found.values.flatten()).sortedWith(compareByDescending<HealthIssue> { it.problem.severity }.thenBy { it.id })
        state.value = state.value.copy(issues = all)
    }

    // --------------------------------------------------------------------------------- setup

    private suspend fun setupIssues(): List<HealthIssue> = buildList {
        val statuses = engine.status.value
        addAll(folderIssues(statuses))
        addAll(duplicateFolders(statuses))
        if (ctx.emulatorsDetected.value) addAll(emulatorIssues(library.platforms.value))
        addAll(firmwareIssues(library.platforms.value))
        addAll(overrideIssues())
        missingGames()?.let(::add)
        addAll(keyIssues())
        updateIssue()?.let(::add)
        lowSpace(statuses)?.let(::add)
    }

    private fun folderIssues(statuses: List<SourceStatus>): List<HealthIssue> = statuses.filter { it.source.enabled }.mapNotNull { s ->
        val src = s.source
        val id = "folder.${src.id.value}"
        val where = "Library folder: ${src.path}"
        val problem = when (s.state) {
            SourceState.ONLINE -> null
            SourceState.OFFLINE -> Problem(
                title = "${s.driveLabel} isn't connected",
                message = "The games in ${src.label} are kept, with their art and play time. They come back as they were when the drive is connected." +
                    (src.volume?.lastSeenAt?.let { " Last seen ${TimeWords.relative(it, ctx.now(), ctx.services.utcOffsetMillis())}." } ?: ""),
                kind = ProblemKind.DRIVE,
                severity = Severity.INFO,
                reassurance = "No action is needed.",
                actions = listOf(ProblemAction.CheckDrives(), ProblemAction.OpenStorage()),
                details = where,
            )
            SourceState.NO_ACCESS -> Problem(
                title = "Fuse can't read ${src.label}",
                message = "The folder is there, but Fuse no longer has permission to read it, so its games can't be scanned or started.",
                kind = ProblemKind.ACCESS,
                severity = Severity.BROKEN,
                actions = listOf(ProblemAction.GrantAccess(), ProblemAction.RemoveSource(src.id)),
                details = where,
            )
            SourceState.FOLDER_MISSING -> Problem(
                title = "${src.label} wasn't found",
                message = "${s.driveLabel} is connected, but the library folder isn't on it any more. It may have been renamed or moved. Its games stay in Fuse until you decide.",
                kind = ProblemKind.FILE,
                actions = listOf(ProblemAction.CheckDrives("Look again"), ProblemAction.RemoveSource(src.id)),
                details = where,
            )
            SourceState.OTHER_DRIVE -> Problem(
                title = "A different drive is where ${s.driveLabel} was",
                message = "${src.label} lives on ${src.volume?.label ?: "another drive"}, but the drive connected now isn't that one. If it holds the same library (a card you reformatted or copied), tell Fuse.",
                kind = ProblemKind.DRIVE,
                actions = listOf(ProblemAction.AdoptDrive(src.id), ProblemAction.CheckDrives()),
                details = where,
            )
            SourceState.MOVED -> Problem(
                title = "${src.label} moved",
                message = "${s.driveLabel} is now at ${s.relinkTo}, where some of these games were also added on their own, so Fuse didn't merge them. Remove one of the two folders to keep a single library.",
                kind = ProblemKind.DRIVE,
                actions = listOf(ProblemAction.RemoveSource(src.id), ProblemAction.OpenSettings("library", "Library folders")),
                details = where,
            )
        } ?: return@mapNotNull null
        HealthIssue(id, problem)
    }

    /** Two folders that are the same place on disk, which would show every game twice. */
    private suspend fun duplicateFolders(statuses: List<SourceStatus>): List<HealthIssue> {
        val online = statuses.filter { it.source.enabled && it.state == SourceState.ONLINE }.map { it.source }
        if (online.size < 2) return emptyList()
        val canonical = online.associateWith { src -> runCatching { ctx.services.fs.canonical(src.path) }.getOrNull() }
        // One folder inside another counts too: the inner one's games are found twice.
        val nested = online.flatMap { a -> online.filter { b -> a != b && Volumes.relativeTo(FsPath.normalize(canonical[a] ?: a.path), FsPath.normalize(canonical[b] ?: b.path))?.isNotEmpty() == true }.map { b -> listOf(b, a) } }
        return (Volumes.duplicateRoots(canonical) + nested).distinctBy { g -> g.map { it.id.value }.sorted() }.map { group ->
            val (first, second) = group.first() to group[1]
            HealthIssue(
                "folder.same.${group.joinToString(".") { it.id.value.toString() }}",
                Problem(
                    title = "Two library folders overlap",
                    message = "${first.label} and ${second.label} lead to the same games on disk, so they may show up twice. Remove one of them.",
                    kind = ProblemKind.DATA,
                    reassurance = null,
                    actions = listOf(ProblemAction.RemoveSource(second.id, "Remove ${second.label}"), ProblemAction.OpenSettings("library", "Library folders")),
                    details = group.joinToString("\n") { "${it.label}: ${it.path}" + (canonical[it]?.let { c -> " (really $c)" } ?: "") },
                ),
            )
        }
    }

    private fun emulatorIssues(platforms: List<PlatformCard>): List<HealthIssue> = platforms.mapNotNull { p ->
        if (p.platform.id.value == "android" || p.emulatorInstalled) return@mapNotNull null
        val name = p.platform.name
        val chosen = p.emulatorChosen
        val homepage = (chosen ?: ctx.registry.forPlatform(p.platform.id, ctx.host).firstOrNull { !it.opensAppOnly }?.id)?.let { ctx.registry[it]?.homepage }
        val problem = if (chosen != null) {
            val emu = ctx.registry[chosen]?.name ?: p.emulatorName ?: chosen.value
            Problem(
                title = "$emu not found",
                message = "$name is set to start in $emu, but $emu isn't installed any more. Choose another emulator, or install it again.",
                kind = ProblemKind.EMULATOR,
                actions = listOfNotNull(
                    ProblemAction.PickEmulator(platform = p.platform.id),
                    homepage?.let { ProblemAction.OpenLink(it, "Get $emu") },
                ),
            )
        } else {
            val suggestions = ctx.registry.forPlatform(p.platform.id, ctx.host).filterNot { it.opensAppOnly }.take(2).map { it.name }
            Problem(
                title = "No emulator for $name",
                message = "${p.gameCount} ${if (p.gameCount == 1) "game is" else "games are"} waiting." +
                    (if (suggestions.isEmpty()) "" else " Install ${suggestions.joinToString(" or ")} and Fuse picks it up by itself."),
                kind = ProblemKind.EMULATOR,
                reassurance = null,
                actions = listOfNotNull(homepage?.let { ProblemAction.OpenLink(it, "Get ${suggestions.firstOrNull() ?: "an emulator"}") }, ProblemAction.OpenSystem(p.platform.id, "$name settings")),
            )
        }
        HealthIssue("emulator.${p.platform.id.value}", problem, platform = p.platform.id)
    }

    /** Firmware Fuse looked for and knows is missing. Unknown (a folder it can't read) is never reported. */
    private fun firmwareIssues(platforms: List<PlatformCard>): List<HealthIssue> = platforms.mapNotNull { p ->
        val romm = ctx.settings.value.romm.let { it.enabled && it.configured }
        val bios = p.bios
        val (severity, title) = when (bios.state) {
            BiosState.MISSING -> Severity.ATTENTION to "${p.platform.name} firmware missing"
            BiosState.PARTIAL -> Severity.INFO to "Some ${p.platform.name} firmware is missing"
            else -> return@mapNotNull null
        }
        HealthIssue(
            "bios.${p.platform.id.value}",
            Problem(
                title = title,
                message = "Fuse looked in your firmware folders and didn't find " +
                    bios.missing.take(3).joinToString(", ") + (if (bios.missing.size > 3) " and ${bios.missing.size - 3} more" else "") +
                    ". Many ${p.platform.shortName} games need it to start. Fuse never ships firmware: dump it from your own console" +
                    (if (romm) ", or bring it from your RomM server." else "."),
                kind = ProblemKind.FIRMWARE,
                severity = severity,
                reassurance = null,
                actions = listOfNotNull(
                    ProblemAction.BiosFromRomm(p.platform.id, p.platform.name).takeIf { romm },
                    ProblemAction.OpenSystem(p.platform.id, "${p.platform.name} settings"),
                ),
                details = "Searched:\n" + bios.searched.joinToString("\n").ifEmpty { "(no folders)" },
            ),
            platform = p.platform.id,
        )
    }

    /** Games set to an emulator of their own that isn't installed any more, as one finding. */
    private suspend fun overrideIssues(): List<HealthIssue> {
        if (!ctx.emulatorsDetected.value) return emptyList()
        val installed = ctx.installed.value.map { it.id }.toSet() + ctx.registry.forHost(ctx.host).filter { it.builtIn }.map { it.id }
        val gone = ctx.data.games.emulatorOverrides().filterValues { it !in installed && ctx.registry[it]?.host == ctx.host }
        if (gone.isEmpty()) return emptyList()
        val emulators = gone.values.distinct().mapNotNull { ctx.registry[it]?.name }
        return gone.entries.take(GAME_ISSUES_MAX).map { (game, emu) ->
            val name = ctx.registry[emu]?.name ?: emu.value
            HealthIssue(
                "override.${game.value}",
                Problem(
                    title = "${ctx.data.games.summary(game)?.displayTitle ?: "A game"} is set to $name",
                    message = "$name isn't installed any more, so this game can't start the way it's set to. Choose another emulator for it.",
                    kind = ProblemKind.EMULATOR,
                    actions = listOf(ProblemAction.PickEmulator(game = game), ProblemAction.OpenGame(game, "Game page")),
                    details = "Emulators no longer installed: ${emulators.joinToString()}",
                ),
                game = game,
            )
        }
    }

    /** Games the last scans didn't find, leaving out ones whose drive is simply out. */
    private suspend fun missingGames(): HealthIssue? {
        val roots = ctx.offline.value
        val missing = stillMissing(ctx.data.games.observeMissing().first(), ctx.data.games.observeAll(includeHidden = true).first()).filterNot { s -> roots.any { it.holds(s.folderPath) } }
        if (missing.isEmpty()) return null
        val n = missing.size
        return HealthIssue(
            "games.missing",
            Problem(
                title = if (n == 1) "1 game's file wasn't found" else "$n games' files weren't found",
                message = "The last scan didn't find ${if (n == 1) "it" else "them"} where ${if (n == 1) "it was" else "they were"}. Fuse keeps everything about ${if (n == 1) "it" else "them"}, so ${if (n == 1) "it comes" else "they come"} back as before if the files return.",
                kind = ProblemKind.FILE,
                severity = Severity.INFO,
                reassurance = "Fuse never deletes a game because its file is gone.",
                actions = listOf(ProblemAction.ShowMissing(), ProblemAction.Rescan()),
                details = missing.take(20).joinToString("\n") { it.displayTitle } + if (n > 20) "\n..." else "",
            ),
        )
    }

    private fun keyIssues(): List<HealthIssue> {
        val checks = media.keyChecks.value
        val paused = media.fillProgress.value?.takeIf { !it.finished }?.paused.orEmpty()
        return checks.mapNotNull { (provider, check) ->
            val rejected = check as? KeyCheck.Rejected ?: return@mapNotNull null
            HealthIssue(
                "key.${provider.name}",
                Problem(
                    title = "${provider.displayName} didn't accept your key",
                    message = "Art and details from ${provider.displayName} are skipped until the key works. The other sources carry on.",
                    kind = ProblemKind.ACCOUNT,
                    actions = listOf(ProblemAction.OpenSettings("media", "Sources and keys", group = "media.sources")),
                    details = rejected.reason,
                ),
            )
        } + paused.map { source ->
            HealthIssue(
                "quota.$source",
                Problem(
                    title = "$source is resting",
                    message = "It ran out of requests for now. The other sources carry on, and $source is asked again later.",
                    kind = ProblemKind.NETWORK,
                    severity = Severity.INFO,
                    reassurance = null,
                ),
            )
        }
    }

    private fun updateIssue(): HealthIssue? {
        val failed = updates.state.value as? UpdateState.Failed ?: return null
        return HealthIssue(
            "update",
            Problem(
                title = "${failed.release.name} didn't download",
                message = "Fuse keeps running the version you have. Try again when the connection is steady.",
                kind = ProblemKind.NETWORK,
                reassurance = "Nothing was installed or changed.",
                actions = listOf(ProblemAction.OpenSettings("about", "Updates")),
                details = failed.message,
            ),
        )
    }

    /** A drive with a library on it that is nearly full. */
    private fun lowSpace(statuses: List<SourceStatus>): HealthIssue? {
        val full = statuses.mapNotNull { it.volume }.distinctBy { it.id }.firstOrNull { v ->
            v.totalBytes > 0 && !v.readOnly && (v.freeBytes < LOW_SPACE_BYTES || v.freeBytes.toDouble() / v.totalBytes < LOW_SPACE_SHARE)
        } ?: return null
        return HealthIssue(
            "space.${full.id}",
            Problem(
                title = "${full.label} is nearly full",
                message = "Downloads, recordings and emulator saves need room to land. See what takes the most space in Storage.",
                kind = ProblemKind.DRIVE,
                severity = Severity.INFO,
                reassurance = null,
                actions = listOf(ProblemAction.OpenStorage()),
            ),
        )
    }

    // ---------------------------------------------------------------------------------- files

    private fun checkFiles(library: Boolean) {
        // A look at the library asked for while a scan's check runs is made right after it.
        if (library) libraryWanted = true
        if (filesJob?.isActive == true) return
        filesJob = ctx.scope.launch(Dispatchers.Default) {
            val withLibrary = libraryWanted.also { libraryWanted = false }
            state.value = state.value.copy(checking = true)
            try {
                val roots = ctx.offline.value
                val check = PlaylistCheck(ctx.services.fs)
                val found = ArrayList<HealthIssue>()
                for ((id, launch) in ctx.data.games.playlistGames(FILE_CHECKS_MAX)) {
                    if (roots.any { it.holds(launch) }) continue
                    val game = ctx.data.games.get(id) ?: continue
                    val missing = try {
                        check.check(game.location.launchPath, game.discs)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        continue
                    }
                    if (missing.isEmpty()) continue
                    val first = missing.first()
                    val title = game.displayTitle
                    val what = first.disc?.let { "$title: ${it.label} missing" } ?: "$title: a track is missing"
                    found += HealthIssue(
                        "files.${id.value}",
                        Problem(
                            title = what,
                            message = "${FsPath.name(first.owner)} names ${FsPath.name(first.path)}, but that file isn't there." +
                                (if (missing.size > 1) " ${missing.size - 1} more ${if (missing.size == 2) "file is" else "files are"} missing too." else "") +
                                " The game may not start, or stop when it asks for the missing part.",
                            kind = ProblemKind.FILE,
                            reassurance = null,
                            actions = listOf(ProblemAction.OpenGame(id, "Game page"), ProblemAction.Rescan()),
                            details = missing.joinToString("\n") { "${FsPath.name(it.owner)} -> ${it.path}" },
                        ),
                        platform = game.platformId,
                        game = id,
                    )
                    if (found.size >= GAME_ISSUES_MAX) break
                }
                files = found
                if (withLibrary) libraryFound = ctx.work.run(BackgroundWork.Job.LIBRARY_HEALTH) { libraryHealth() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The files are checked again after the next scan.
            } finally {
                state.value = state.value.copy(checking = false, checkedAt = ctx.now())
                publish()
            }
            if (libraryWanted) checkFiles(library = true)
        }
    }

    @Volatile private var libraryWanted = false

    // ------------------------------------------------------------------------- library health

    /** Pictures found gone by the last look, for [repairPictures]. */
    @Volatile private var gonePictures: List<io.github.matiyaaa.fuse.data.health.LibraryHealthRepository.LocalPicture> = emptyList()

    /**
     * Fuse 0.4.5, Library Health: pictures whose files are gone, pictures kept for games no longer in
     * the library, games without a cover, and the same file found twice. Each says what a repair would
     * change before it does; nothing is merged, and no game's own file is ever touched.
     */
    private suspend fun libraryHealth(): List<HealthIssue> = buildList {
        val health = ctx.data.libraryHealth
        val gone = kotlinx.coroutines.withContext(Dispatchers.IO) { health.localPictures().filterNot { java.io.File(it.path).exists() } }
        gonePictures = gone
        if (gone.isNotEmpty()) {
            val (web, forgotten, chosen) = health.plan(gone)
            val n = gone.size
            add(HealthIssue(
                "library.pictures.gone",
                Problem(
                    title = if (n == 1) "A picture's file is gone" else "$n pictures' files are gone",
                    message = "Fuse kept ${if (n == 1) "this picture" else "these pictures"} as files that aren't there any more (a cache cleared, a folder moved), so ${if (n == 1) "it shows" else "they show"} as blank.",
                    kind = ProblemKind.FILE,
                    severity = Severity.ATTENTION,
                    reassurance = buildList {
                        if (web > 0) add("$web will be downloaded again when shown")
                        if (forgotten > 0) add("$forgotten will be forgotten, so the next art fill can find others")
                        if (chosen > 0) add("$chosen you chose yourself stay as they are")
                    }.joinToString(". ") + ". No game's files are touched.",
                    actions = listOf(ProblemAction.RepairPictures()),
                    details = gone.take(20).joinToString("\n") { it.path } + if (n > 20) "\n..." else "",
                ),
            ))
        }
        val orphans = health.orphanPictures()
        if (orphans > 0) {
            add(HealthIssue(
                "library.pictures.orphan",
                Problem(
                    title = if (orphans == 1) "A picture belongs to no game" else "$orphans pictures belong to no game",
                    message = "They were kept for games that are no longer in your library at all. Tidying up forgets them; the picture files stay where they are.",
                    kind = ProblemKind.DATA,
                    severity = Severity.INFO,
                    reassurance = "Games in your library, missing or not, keep all of their art.",
                    actions = listOf(ProblemAction.TidyPictures()),
                ),
            ))
        }
        val noCover = runCatching { ctx.data.media.missingCounts(setOf(io.github.matiyaaa.fuse.model.MediaKind.BOXART))[io.github.matiyaaa.fuse.model.MediaKind.BOXART] ?: 0 }.getOrDefault(0)
        if (noCover > 0) {
            add(HealthIssue(
                "library.covers",
                Problem(
                    title = if (noCover == 1) "1 game has no cover" else "$noCover games have no cover",
                    message = "Art and details can look for ${if (noCover == 1) "it" else "them"}: by name on the sources you use, and on RomM or Fuse One where you have them.",
                    kind = ProblemKind.FILE,
                    severity = Severity.INFO,
                    reassurance = null,
                    actions = listOf(ProblemAction.FindArt(), ProblemAction.OpenSettings("media", "Art and details")),
                ),
            ))
        }
        val twice = health.sameFileTwice()
        if (twice.isNotEmpty()) {
            val titles = ctx.data.games.observeAll(includeHidden = true).first().associateBy { it.id }
            val n = twice.sumOf { it.size }
            add(HealthIssue(
                "library.twice",
                Problem(
                    title = if (twice.size == 1) "A game is in your library twice" else "${twice.size} games are in your library twice",
                    message = "The same file (same name, same size, same system) is in two places, so it shows twice. Fuse never merges games by itself: if the copies aren't wanted, remove one from its folder.",
                    kind = ProblemKind.FILE,
                    severity = Severity.INFO,
                    reassurance = "Nothing was changed.",
                    actions = listOf(ProblemAction.OpenGame(twice.first().first(), "Open the first")),
                    details = twice.take(20).joinToString("\n") { group -> group.joinToString("  |  ") { id -> titles[id]?.let { "${it.displayTitle} (${it.folderPath})" } ?: "#${id.value}" } } +
                        if (twice.size > 20) "\n... $n copies in all" else "",
                ),
            ))
        }
    }

    override suspend fun repairPictures(): Int {
        // Looked at again first: a file back since the last look is left as it is.
        val gone = kotlinx.coroutines.withContext(Dispatchers.IO) { gonePictures.filterNot { java.io.File(it.path).exists() } }
        val changed = ctx.data.libraryHealth.repairGone(gone)
        check()
        return changed
    }

    override suspend fun tidyPictures(): Int = ctx.data.libraryHealth.removeOrphanPictures().also { check() }

    // ----------------------------------------------------------------------------- diagnostics

    override suspend fun diagnostics(device: List<String>, crash: String?): String {
        val settings = ctx.settings.value
        val home = ctx.services.emulators.homeDir
        val keys = storedKeys()
        val cart = cartridge()
        val platforms = library.platforms.value
        val sections = listOf(
            DiagnosticsReport.Section(
                "Fuse",
                listOf(
                    "Version: ${ctx.services.appVersion}",
                    "Host: ${ctx.host}",
                    "Database schema: ${io.github.matiyaaa.fuse.data.db.FuseDatabase.Schema.version}",
                ) + device,
            ),
            DiagnosticsReport.Section(
                "System health",
                state.value.issues.map { "[${it.problem.severity}] ${it.problem.title}" },
            ),
            DiagnosticsReport.Section(
                "Drives",
                engine.volumes.value.map { v ->
                    "${v.label}: ${v.kind}${if (v.removable) ", removable" else ""}${if (v.readOnly) ", read only" else ""}, " +
                        "${v.fsType ?: "unknown filesystem"}, ${gb(v.freeBytes)} free of ${gb(v.totalBytes)}, " +
                        "id ${v.id.substringBefore(':')}, at ${v.mountPaths.joinToString()}"
                },
            ),
            DiagnosticsReport.Section(
                "Library folders",
                engine.status.value.map { s ->
                    "${s.source.label} (${s.source.kind}${if (!s.source.enabled) ", off" else ""}): ${s.state}, on ${s.driveLabel}, ${s.source.path}"
                },
            ),
            DiagnosticsReport.Section(
                "Systems",
                platforms.map { p ->
                    "${p.platform.name}: ${p.gameCount} games, emulator ${p.emulatorName ?: "none"}" +
                        "${if (p.emulatorInstalled) "" else " (not installed)"}${if (p.emulatorChosen != null) ", chosen" else ""}, firmware ${p.bios.state}"
                },
            ),
            DiagnosticsReport.Section(
                "Emulators found",
                ctx.installed.value.map { e ->
                    "${e.name} [${e.id.value}] via ${e.detectedVia}${e.version?.let { v -> ", version $v" } ?: ""}${if (e.isFamilyMatch) ", recognised as a build of it" else ""}: ${e.appId}"
                }.ifEmpty { listOf(if (ctx.emulatorsDetected.value) "None" else "Not looked for yet") },
            ),
            DiagnosticsReport.Section(
                "Integrations",
                listOf(
                    "RetroAchievements: ${if (io.github.matiyaaa.fuse.data.settings.SecretKeys.RA_API_KEY in keys) "connected" else "not connected"}",
                    "Provider keys stored: " + keys.filterNot { it.startsWith("ra.") }.map { it.substringBefore('.') }.distinct().sorted().joinToString().ifEmpty { "none" },
                    "Cartridge: ${if (!settings.cartridge.enabled) "off" else if (cart.installed) "installed ${cart.version ?: ""}, bridge ${cart.bridge}, protocol ${cart.protocol}" else "not installed"}",
                    "Phone Link: ${if (settings.library.phoneLinkEnabled) "on" else "off"}",
                    "Updates: ${if (settings.updates.checkForUpdates) "checked" else "not checked"}, channel ${settings.updates.channel}, state ${updates.state.value::class.simpleName}",
                ),
            ),
            DiagnosticsReport.Section(
                "Fuse Engine",
                ctx.governor.current().let { state ->
                    val p = io.github.matiyaaa.fuse.ui.shell.store.EnginePolicy.of(state)
                    listOf(
                        "Performance Governor: ${state.name.lowercase()}",
                        "Decorative motion ${p.motion.name.lowercase()}, art and details by itself ${if (p.artwork) "allowed" else "held"}, looking for emulators ${if (p.detection) "allowed" else "held"}",
                    )
                },
            ),
            DiagnosticsReport.Section(
                "It launched",
                ctx.recentLaunches.value.map { it.line(TimeWords.relative(it.at, ctx.now(), ctx.services.utcOffsetMillis())) }
                    .ifEmpty { listOf("No game started since Fuse opened") },
            ),
            DiagnosticsReport.Section(
                "Recent launch problems",
                ctx.recentProblems.value.map { (at, p) -> "${TimeWords.relative(at, ctx.now(), ctx.services.utcOffsetMillis())}: ${p.title}" + (p.details?.let { "\n    $it" } ?: "") },
            ),
        ) + listOfNotNull(crash?.let { DiagnosticsReport.Section("Last crash", it.lines()) })
        return DiagnosticsReport.render(sections, home)
    }

    private fun gb(bytes: Long): String = if (bytes <= 0) "?" else "${(bytes / 100_000_000L) / 10.0} GB"

    private companion object {
        const val SETTLE_MS = 400L
        const val FILE_CHECKS_MAX = 5_000
        const val GAME_ISSUES_MAX = 100
        const val LOW_SPACE_BYTES = 2_000_000_000L
        const val LOW_SPACE_SHARE = 0.02
    }
}
