package io.github.matiyaaa.fuse.ui.shell.store.impl

import io.github.matiyaaa.fuse.data.repo.CollectionKey
import io.github.matiyaaa.fuse.launch.ChoiceSource
import io.github.matiyaaa.fuse.launch.IdFiles
import io.github.matiyaaa.fuse.launch.Paths
import io.github.matiyaaa.fuse.launch.ResolvedLaunch
import io.github.matiyaaa.fuse.launch.RetroArchCores
import io.github.matiyaaa.fuse.launch.ScopedLaunchChoice
import io.github.matiyaaa.fuse.library.FsPath
import io.github.matiyaaa.fuse.library.parse.DisplayNameCleaner
import io.github.matiyaaa.fuse.library.parse.FilenameParser
import io.github.matiyaaa.fuse.library.scan.ScanRules
import io.github.matiyaaa.fuse.model.AddedGames
import io.github.matiyaaa.fuse.model.AppFilter
import io.github.matiyaaa.fuse.model.BiosStatus
import io.github.matiyaaa.fuse.model.EmulatorId
import io.github.matiyaaa.fuse.model.FolderInterpretation
import io.github.matiyaaa.fuse.model.FolderPolicy
import io.github.matiyaaa.fuse.model.Game
import io.github.matiyaaa.fuse.model.GameId
import io.github.matiyaaa.fuse.model.InstalledEmulator
import io.github.matiyaaa.fuse.model.LaunchDisplay
import io.github.matiyaaa.fuse.model.LaunchPlan
import io.github.matiyaaa.fuse.model.LaunchTarget
import io.github.matiyaaa.fuse.model.LibraryLayout
import io.github.matiyaaa.fuse.model.LocationKind
import io.github.matiyaaa.fuse.model.MediaOwner
import io.github.matiyaaa.fuse.model.Platform
import io.github.matiyaaa.fuse.model.PlatformFolderScan
import io.github.matiyaaa.fuse.model.PlatformId
import io.github.matiyaaa.fuse.model.ScanPhase
import io.github.matiyaaa.fuse.model.ScanScope
import io.github.matiyaaa.fuse.model.ScannedGame
import io.github.matiyaaa.fuse.model.ScopedSettings
import io.github.matiyaaa.fuse.model.SortOrder
import io.github.matiyaaa.fuse.ui.shell.store.Art
import io.github.matiyaaa.fuse.ui.shell.store.BrowseEntry
import io.github.matiyaaa.fuse.ui.shell.store.BrowseListing
import io.github.matiyaaa.fuse.ui.shell.store.ContentNote
import io.github.matiyaaa.fuse.ui.shell.store.EmulatorChoice
import io.github.matiyaaa.fuse.ui.shell.store.GameCard
import io.github.matiyaaa.fuse.ui.shell.store.GameDetail
import io.github.matiyaaa.fuse.ui.shell.store.GameQuery
import io.github.matiyaaa.fuse.ui.shell.store.HomeFeed
import io.github.matiyaaa.fuse.ui.shell.store.LaunchOutcome
import io.github.matiyaaa.fuse.ui.shell.store.Unavailable
import io.github.matiyaaa.fuse.ui.shell.store.LibraryOps
import io.github.matiyaaa.fuse.ui.shell.store.PlatformCard
import io.github.matiyaaa.fuse.ui.shell.store.PlaytimeSummary
import io.github.matiyaaa.fuse.ui.shell.store.RunResult
import io.github.matiyaaa.fuse.ui.shell.store.PlayTimeReport
import io.github.matiyaaa.fuse.ui.shell.store.SearchResults
import io.github.matiyaaa.fuse.ui.shell.store.StorageSummary
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** A play session Fuse started and has not closed yet. */
private data class ActiveSession(val id: Long, val gameId: GameId, val endsOnResume: Boolean)

@OptIn(ExperimentalCoroutinesApi::class)
internal class DefaultLibraryOps(
    private val ctx: StoreContext,
    private val engine: LibraryEngine,
    private val emulators: DefaultEmulatorOps,
    private val collections: DefaultCollectionOps,
    private val apps: DefaultAppOps,
    private val achievements: DefaultAchievementOps,
    private val cartridge: DefaultCartridgeOps,
    private val setCleanNames: suspend (Boolean) -> Unit,
) : LibraryOps {
    private val data = ctx.data
    private var active: ActiveSession? = null

    // Platforms ------------------------------------------------------------------------------------

    /** The platform-level emulator choice and layout for each platform that has games. */
    private val platformChoices: Flow<Map<PlatformId, Pair<String, LibraryLayout>>> = data.games.platformCounts()
        .map { it.keys.sortedBy(PlatformId::value) }
        .distinctUntilChanged()
        .flatMapLatest { ids ->
            if (ids.isEmpty()) {
                flowOf(emptyMap())
            } else {
                combine(
                    ids.map { id ->
                        combine(
                            data.scopedSettings.observeResolved(ScopedSettings.Emulator, id, null),
                            data.scopedSettings.observeResolved(ScopedSettings.Layout, id, null),
                        ) { emulator, layout -> id to (emulator.value to layout.value) }
                    },
                ) { pairs -> pairs.toMap() }
            }
        }

    private val platformArt: Flow<Map<PlatformId, Art>> = data.games.platformCounts()
        .map { it.keys }
        .distinctUntilChanged()
        .flatMapLatest { ids ->
            data.media.observeFor(ids.map { MediaOwner.OfPlatform(it) }).map { media ->
                media.mapNotNull { (owner, set) -> (owner as? MediaOwner.OfPlatform)?.let { it.id to Art.from(set) } }.toMap()
            }
        }

    private val systemOrder: Flow<Pair<List<String>, Map<String, Long>>> =
        combine(ctx.systemOrder, data.settings.settings.map { it.library.systemColors }.distinctUntilChanged(), ::Pair).distinctUntilChanged()

    override val platforms: StateFlow<List<PlatformCard>> = combine(
        data.games.platformCounts(),
        ctx.installed,
        combine(engine.bios, engine.platformFolders, ::Pair),
        platformChoices,
        combine(platformArt, systemOrder, ::Pair),
    ) { counts, installed, (bios, folders), choices, (art, orderAndColors) ->
        val (order, colors) = orderAndColors
        // The user's order first (hold confirm on a system to move it), then catalog order.
        val catalogOrder = ctx.platforms.all.withIndex().associate { (i, p) -> p.id to i + order.size }
        val userOrder = order.withIndex().associate { (i, id) -> PlatformId(id) to i }
        counts.filterValues { it > 0 }.keys
            .mapNotNull(ctx::platform)
            .sortedBy { userOrder[it.id] ?: catalogOrder[it.id] ?: Int.MAX_VALUE }
            .map { p ->
                val (chosen, layout) = choices[p.id] ?: ("" to p.defaultLayout)
                // Shortcut openers count only where the priority list names them (Steam, PC games).
                val named = ctx.registry.priority(p.id, ctx.host).toSet()
                val candidates = ctx.registry.forPlatform(p.id, ctx.host).filter { !it.shortcutsOnly || it.id in named }
                // Built-in adapters (Android apps) need nothing installed.
                val installedIds = installed.map { it.id }.toSet() + candidates.filter { it.builtIn }.map { it.id }
                val chosenId = chosen.takeIf { it.isNotBlank() }?.let(::EmulatorId)
                val effective = chosenId ?: candidates.firstOrNull { it.id in installedIds && !it.opensAppOnly }?.id
                    ?: candidates.firstOrNull { it.id in installedIds }?.id
                val effectiveInstalled = installed.firstOrNull { it.id == effective }
                PlatformCard(
                    // A brand colour from the system art pack replaces Fuse's generated accent.
                    platform = colors[p.id.value]?.let { p.copy(accent = it) } ?: p,
                    gameCount = counts[p.id] ?: 0,
                    art = art[p.id] ?: Art.None,
                    emulatorName = effectiveInstalled?.name ?: effective?.let { ctx.registry[it]?.name },
                    emulatorInstalled = effectiveInstalled != null || effective in installedIds,
                    installedEmulators = candidates.count { it.id in installedIds },
                    bios = bios[p.id] ?: if (p.bios == null) BiosStatus.NotRequired else BiosStatus(io.github.matiyaaa.fuse.model.BiosState.UNKNOWN),
                    layout = layout,
                    romFolders = folders[p.id].orEmpty(),
                    emulatorChosen = chosenId,
                )
            }
    }.flowOn(Dispatchers.Default).resilient().stateIn(ctx.scope, SharingStarted.Eagerly, emptyList())

    // Home ------------------------------------------------------------------------------------------

    private val playtime: Flow<PlaytimeSummary> = combine(
        data.playSessions.totalSeconds(),
        data.playSessions.secondsSince(ctx.weekStart()),
        data.playSessions.dailyTotals(7, ctx.services.utcOffsetMillis()),
        data.playSessions.openSession().mapLatest { open -> open?.let { ctx.card(it.gameId)?.to(it.startedAt) } },
    ) { totals, week, daily, current ->
        PlaytimeSummary(totals.totalSeconds, week, daily.map { it.seconds }, current?.first, current?.second)
    }

    private val storage: Flow<StorageSummary?> = combine(
        engine.sources,
        engine.scan.filter { it.phase == ScanPhase.DONE || it.phase == ScanPhase.IDLE },
    ) { sources, _ -> sources.firstOrNull { it.enabled } }
        .mapLatest { source ->
            source?.let { s -> volumeSpace(s.path)?.let { (free, total) -> StorageSummary(s.label, free, total) } }
        }
        .flowOn(Dispatchers.Default)

    /**
     * Played in the last two weeks, minus games taken off the shelf (until they are played again).
     * The two-week window is recomputed hourly so it moves while Fuse stays open.
     */
    private val continuePlaying: Flow<List<GameCard>> = combine(
        flow { while (true) { emit(Unit); kotlinx.coroutines.delay(HOUR_MS) } }
            .flatMapLatest { ctx.cards(data.games.observeContinuePlaying(days = 14, limit = 40)) },
        data.settings.settings.map { it.home.continueDismissed }.distinctUntilChanged(),
    ) { cards, dismissed ->
        cards.filter { c -> dismissed[c.id.value.toString()]?.let { at -> (c.lastPlayedAt ?: 0) > at } ?: true }.take(20)
    }

    override val home: StateFlow<HomeFeed> = combine(
        listOf(
            continuePlaying,
            ctx.cards(data.games.observeRecentlyPlayed(20)),
            ctx.cards(data.games.observeRecentlyAdded(20)),
            ctx.cards(data.games.observeFavorites()),
            ctx.cards(data.games.observePinned()),
            ctx.cards(data.games.observeMostPlayed(20)),
            platforms,
            collections.collections,
            if (apps.supported) apps.apps(AppFilter.PINNED) else flowOf(emptyList()),
            playtime.onStart { emit(PlaytimeSummary()) },
            achievements.feed,
            storage.onStart { emit(null) },
        ),
    ) { v ->
        @Suppress("UNCHECKED_CAST")
        HomeFeed(
            continuePlaying = v[0] as List<GameCard>,
            recentlyPlayed = v[1] as List<GameCard>,
            recentlyAdded = v[2] as List<GameCard>,
            favorites = v[3] as List<GameCard>,
            pinnedGames = v[4] as List<GameCard>,
            mostPlayed = v[5] as List<GameCard>,
            systems = v[6] as List<PlatformCard>,
            collections = v[7] as List<io.github.matiyaaa.fuse.model.GameCollection>,
            pinnedApps = v[8] as List<io.github.matiyaaa.fuse.ui.shell.store.AppCard>,
            playtime = v[9] as PlaytimeSummary,
            achievements = v[10] as io.github.matiyaaa.fuse.ui.shell.store.AchievementsFeed?,
            storage = v[11] as StorageSummary?,
        )
    }.flowOn(Dispatchers.Default).resilient().stateIn(ctx.scope, SharingStarted.Eagerly, HomeFeed())

    // Lists and search ------------------------------------------------------------------------------

    override fun games(query: GameQuery): Flow<List<GameCard>> {
        val games = data.games
        if (query.set != io.github.matiyaaa.fuse.ui.shell.store.GameSet.LIBRARY) {
            val set = when (query.set) {
                io.github.matiyaaa.fuse.ui.shell.store.GameSet.MISSING -> games.observeMissing()
                io.github.matiyaaa.fuse.ui.shell.store.GameSet.HIDDEN -> games.observeHidden()
                else -> games.observeRemoved()
            }
            return ctx.cards(set.map { list -> list.filter { query.platform == null || it.platformId == query.platform }.sortedWith(query.sort.comparator()) })
        }
        val summaries = when {
            query.collection != null -> data.collections.observeGames(CollectionKey.Manual(query.collection))
            query.favoritesOnly -> games.observeFavorites()
            query.platform != null -> games.observeByPlatform(query.platform, query.sort, query.includeHidden)
            else -> games.observeAll(query.sort, query.includeHidden)
        }
        val sorted = if (query.collection != null || query.favoritesOnly) {
            summaries.map { list -> list.filter { query.platform == null || it.platformId == query.platform }.sortedWith(query.sort.comparator()) }
        } else {
            summaries
        }
        return ctx.cards(sorted)
    }

    private val searcher by lazy {
        LibrarySearcher(
            ctx, platforms, collections.collections, engine.status,
            apps = { q -> if (apps.supported) apps.search(q) else emptyList() },
            collectionsShown = { ctx.settings.value.library.collectionsEnabled },
        )
    }

    override fun search(query: String): Flow<SearchResults> = flow { emit(searcher.search(query)) }.flowOn(Dispatchers.Default)

    // Game page -------------------------------------------------------------------------------------

    override fun game(id: GameId): Flow<GameDetail?> = data.games.observe(id).flatMapLatest { record ->
        val game = record?.game ?: return@flatMapLatest flowOf(null)
        val platform = ctx.platform(game.platformId) ?: return@flatMapLatest flowOf(null)
        val weekStart = ctx.weekStart()
        combine(
            data.media.observe(MediaOwner.OfGame(id)),
            ctx.installed,
            data.scopedSettings.observeResolved(ScopedSettings.Emulator, game.platformId, null),
            combine(data.collections.observeCollectionsOf(id), collections.collections) { ids, all -> all.filter { it.id in ids } },
            combine(data.playSessions.sessions(id), achievements.gameState(game)) { sessions, ra ->
                sessions.filter { it.endedAt != null && it.startedAt >= weekStart }.sumOf { it.durationSeconds ?: 0 } to ra
            },
        ) { media, installed, platformEmulator, cols, (week, ra) -> Detail(media, installed, platformEmulator, cols, week, ra) }
            .combine(ctx.offline) { parts, roots -> parts to roots.takeIf { game.appId == null }?.firstOrNull { it.holds(game.location.path) } }
            .map { (parts, away) ->
            val (media, installed, platformEmulator, cols, week, ra) = parts
            val resolved = ctx.resolver.resolve(
                game,
                platformEmulator.value.takeIf { it.isNotBlank() }?.let(::EmulatorId),
                installed,
                ctx.host,
            )
            GameDetail(
                game = game,
                platform = platform,
                media = media,
                art = Art.from(media),
                emulator = choiceOf(game, platform, resolved, installed),
                contentNotes = contentNotes(game, resolved),
                achievements = ra,
                collections = cols,
                secondsThisWeek = week,
                unavailable = away?.let { Unavailable(it.driveLabel, it.state) },
                missing = record.missing,
            )
        }
    }.flowOn(Dispatchers.Default)

    /** What the game page combines before it knows the game's drive. */
    private data class Detail(
        val media: io.github.matiyaaa.fuse.model.MediaSet,
        val installed: List<InstalledEmulator>,
        val platformEmulator: io.github.matiyaaa.fuse.model.Resolved<String>,
        val cols: List<io.github.matiyaaa.fuse.model.GameCollection>,
        val week: Long,
        val ra: io.github.matiyaaa.fuse.model.AchievementState?,
    )

    private fun choiceOf(game: Game, platform: Platform, resolved: ResolvedLaunch, installed: List<InstalledEmulator>): EmulatorChoice {
        val name = resolved.installed?.name ?: resolved.adapter?.name
        val summary = when (val plan = resolved.plan) {
            is LaunchPlan.AndroidIntent, is LaunchPlan.Command -> when (val target = resolved.target) {
                is LaunchTarget.Directory -> "Opens the game folder in $name"
                is LaunchTarget.Playlist -> if (target.generated) "Starts every disc in $name through a playlist Fuse keeps in its cache" else "Starts the disc playlist in $name"
                is LaunchTarget.TitleId -> "Starts the installed title ${target.id} in $name"
                is LaunchTarget.Shortcut -> "Starts the shortcut in $name"
                else -> "Starts in $name"
            }
            is LaunchPlan.OpenAppOnly -> "Opens $name; choose the game there. ${plan.reason}"
            is LaunchPlan.Unsupported -> plan.reason
        }
        val homepage = resolved.adapter?.homepage
            ?: ctx.registry.forPlatform(platform.id, ctx.host).firstNotNullOfOrNull { it.homepage }
        return EmulatorChoice(
            selected = resolved.installed,
            alternatives = ctx.resolver.candidates(game, installed, ctx.host).map { it.installed },
            source = when (resolved.source) {
                ChoiceSource.GAME -> "Game"
                ChoiceSource.PLATFORM -> "Platform"
                ChoiceSource.PRIORITY -> "Automatic"
                ChoiceSource.NONE -> "None"
            },
            launchSummary = if (resolved.installed == null) "No installed emulator can open ${platform.name} games yet" else summary,
            canLaunch = resolved.startsGame || resolved.plan is LaunchPlan.OpenAppOnly,
            emulatorHomepage = homepage,
        )
    }

    private fun contentNotes(game: Game, resolved: ResolvedLaunch): List<ContentNote> {
        val adapter = resolved.adapter
        if (adapter != null) {
            return ctx.resolver.contentPlan(game, adapter).entries.map { ContentNote(it.kind, it.items.size, it.message) }
        }
        return game.content.groupBy { it.kind }.map { (kind, items) ->
            ContentNote(kind, items.size, "Found next to the game. Choose an emulator to see how it is used.")
        }
    }

    // Launching -------------------------------------------------------------------------------------

    override suspend fun launch(id: GameId, emulator: EmulatorId?, discPath: String?, display: LaunchDisplay?): LaunchOutcome =
        launchGame(id, emulator, discPath, display).also { outcome ->
            if (outcome is LaunchOutcome.Problem) ctx.recordProblem(outcome.problem)
        }

    private suspend fun launchGame(id: GameId, emulator: EmulatorId?, discPath: String?, display: LaunchDisplay?): LaunchOutcome {
        val stored = data.games.get(id) ?: return LaunchOutcome.Problem(LaunchProblems.gone())
        val platform = ctx.platform(stored.platformId) ?: return LaunchOutcome.Problem(LaunchProblems.unknownSystem(stored.platformId))
        // A game on a drive that is out says which drive to connect, before anything is tried.
        if (stored.appId == null) {
            engine.drives.offlineFor(stored.location.path)?.let { root ->
                return LaunchOutcome.Problem(LaunchProblems.unavailable(root, stored.displayTitle, root.lastSeenAt?.let(::describeWhen)))
            }
            if (discPath == null && stored.location.path.let(FsPath::isAbsolute) && !exists(stored.location.launchPath)) {
                return LaunchOutcome.Problem(LaunchProblems.fileMissing(stored.displayTitle, stored.location.launchPath))
            }
        }

        var game = stored
        if (discPath != null) {
            game = game.copy(
                location = game.location.copy(
                    kind = LocationKind.FILE,
                    path = discPath,
                    launchPath = discPath,
                    interpretation = FolderInterpretation.SINGLE_FILE,
                ),
                discs = emptyList(),
            )
        }
        if (emulator != null) game = game.copy(emulatorOverride = emulator)
        // A package plays only once it is installed: say so, a button away from installing it. An
        // installed 3DS .cia starts from its installed title, which is what Azahar plays.
        if (discPath == null && io.github.matiyaaa.fuse.launch.InstallOnlyFiles.matches(stored.platformId, stored.location.launchPath)) {
            notInstalled(id)?.let { return LaunchOutcome.Problem(it) }
            installedBoot(id)?.let { boot -> game = game.copy(location = game.location.copy(launchPath = boot)) }
        }

        if (ctx.installed.value.isEmpty()) emulators.detectNow()
        val installed = ctx.installed.value
        val settings = data.scopedSettings
        val platformEmulator = settings.resolve(ScopedSettings.Emulator, game.platformId, null).value
            .takeIf { it.isNotBlank() }?.let(::EmulatorId)
        val core = settings.resolve(ScopedSettings.RetroArchCore, game.platformId, id).value.takeIf { it.isNotBlank() }
        // Asking happens before this (the interface asks and passes the answer); here it means the main screen.
        val display = (display ?: settings.resolve(ScopedSettings.LaunchScreen, game.platformId, id).value)
            .let { if (it == LaunchDisplay.ASK) LaunchDisplay.PRIMARY else it }
        val displayId = if (display == LaunchDisplay.SECONDARY) ctx.services.launcher.secondaryDisplayId() else null
        var choice = ScopedLaunchChoice(
            core = core,
            generateM3u = settings.resolve(ScopedSettings.GenerateM3u, game.platformId, id).value,
            display = display,
            displayId = displayId,
            homeDir = ctx.services.emulators.homeDir,
        )
        if (IdFiles.needsContent(game.location)) {
            choice = choice.copy(injectedText = ctx.services.fs.readText(game.location.launchPath, IdFiles.MAX_BYTES))
        }
        var resolved = ctx.resolver.resolve(game, platformEmulator, installed, ctx.host, choice)
        val chosen = resolved.installed
        // Desktop RetroArch ("linux.retroarch", "windows.retroarch"): use the core file that is really there.
        if (chosen != null && ctx.host.isDesktop && chosen.id.value.substringAfter('.').startsWith("retroarch")) {
            val coreName = core ?: RetroArchCores.defaultCore(ctx.host, game.platformId)
            val corePath = coreName?.let { ctx.services.emulators.retroArchCorePath(chosen, it) }
            if (corePath != null) {
                resolved = ctx.resolver.resolve(game, platformEmulator, installed, ctx.host, choice.copy(core = coreName, corePath = corePath))
            }
        }

        val installedEmulator = resolved.installed
        if (installedEmulator == null) {
            val suggestions = ctx.registry.forPlatform(game.platformId, ctx.host).filterNot { it.opensAppOnly }.take(3)
                .map { it.name to emulators.homepage(it.id) }
            return LaunchOutcome.Problem(LaunchProblems.noEmulator(platform.name, platform.id, suggestions))
        }
        val android = ctx.host == io.github.matiyaaa.fuse.model.Host.ANDROID
        return when (val plan = resolved.plan) {
            is LaunchPlan.Unsupported -> LaunchOutcome.Problem(LaunchProblems.unsupported(plan.reason, id))
            is LaunchPlan.OpenAppOnly -> when (val r = ctx.services.launcher.openApp(plan.appId)) {
                is RunResult.Started -> LaunchOutcome.OpenedAppOnly(installedEmulator.name, plan.reason)
                is RunResult.OpenedAppInstead -> LaunchOutcome.OpenedAppOnly(installedEmulator.name, r.reason)
                RunResult.NotInstalled -> notInstalled(installedEmulator, id)
                is RunResult.Failed -> LaunchOutcome.Problem(LaunchProblems.refused(installedEmulator, id, r.message, android))
            }
            is LaunchPlan.AndroidIntent, is LaunchPlan.Command -> when (val r = ctx.services.launcher.run(resolved, displayId)) {
                is RunResult.Started -> {
                    startSession(id, installedEmulator.id, r.awaitExit)
                    LaunchOutcome.Started
                }
                // Only the app opened; which game gets played there is unknown, so no session is recorded.
                is RunResult.OpenedAppInstead -> LaunchOutcome.OpenedAppOnly(installedEmulator.name, r.reason)
                RunResult.NotInstalled -> notInstalled(installedEmulator, id)
                is RunResult.Failed -> LaunchOutcome.Problem(LaunchProblems.refused(installedEmulator, id, r.message, android))
            }
        }
    }

    private fun notInstalled(emulator: InstalledEmulator, game: GameId): LaunchOutcome {
        emulators.refresh()
        return LaunchOutcome.Problem(LaunchProblems.emulatorGone(emulator, game))
    }

    private suspend fun exists(path: String): Boolean = try {
        ctx.services.fs.stat(path) != null
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // Unknown is not missing: let the emulator try.
        true
    }

    /** "today at 2:14 PM" style wording for when a drive was last seen. */
    private fun describeWhen(at: Long): String = TimeWords.relative(at, ctx.now(), ctx.services.utcOffsetMillis())

    /**
     * Opens an honest play session: it ends when the emulator process exits (Linux) or when Fuse
     * comes back to the front (Android). Nothing is estimated.
     */
    private suspend fun startSession(game: GameId, emulator: EmulatorId, awaitExit: (suspend () -> Unit)?) {
        val sessionId = data.playSessions.start(game, emulator, ctx.now())
        active = ActiveSession(sessionId, game, endsOnResume = awaitExit == null)
        if (awaitExit != null) {
            ctx.scope.launch {
                try {
                    awaitExit()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // The process handle failed; close the session now rather than never.
                }
                endSession(sessionId)
                afterPlaying()
            }
        }
    }

    private suspend fun endSession(sessionId: Long) {
        if (active?.id == sessionId) active = null
        data.playSessions.end(sessionId, ctx.now())
    }

    private fun afterPlaying() {
        achievements.refresh(force = true)
    }

    override fun onResume() {
        ctx.scope.launch {
            val session = active
            if (session != null && session.endsOnResume) {
                endSession(session.id)
                afterPlaying()
            }
            emulators.refreshIfStale()
            apps.refreshInstalled()
            cartridge.refreshOnResume()
            ctx.resumeHooks.value.forEach { it() }
            // A card may have gone in or out while a game ran.
            engine.refreshDrives()
        }
    }

    override fun onPause() = Unit

    /** On start: closes a session left open when Fuse was stopped while a game ran. */
    suspend fun recoverSession() {
        data.playSessions.resumeOpen(ctx.now())
    }

    // Edits -----------------------------------------------------------------------------------------

    override suspend fun setFavorite(id: GameId, favorite: Boolean) = data.games.setFavorite(id, favorite)

    override suspend fun setHidden(id: GameId, hidden: Boolean) = data.games.setHidden(id, hidden)

    override suspend fun setPinned(id: GameId, pinned: Boolean) = data.games.setPinned(id, pinned)

    override suspend fun rename(id: GameId, title: String?) = data.games.rename(id, title?.trim()?.takeIf { it.isNotEmpty() })

    override suspend fun setEmulator(id: GameId, emulator: EmulatorId?) = data.games.setEmulatorOverride(id, emulator)

    override suspend fun setFolderPolicy(id: GameId, policy: FolderPolicy?) {
        val game = data.games.get(id) ?: return
        data.games.setFolderPolicyOverride(id, policy)
        // The folder is scanned as the system it belongs to, whatever system the game is filed under.
        engine.rescan(ScanScope.PLATFORM, game.scannedPlatformId ?: game.platformId)
    }

    /** An installed app played as a game goes back to being an app; a file game leaves Fuse only. */
    override suspend fun removeFromFuse(id: GameId) {
        val appId = data.games.get(id)?.appId
        if (appId != null) apps.setKind(appId, io.github.matiyaaa.fuse.model.AppKind.APP) else data.games.removeFromFuse(id)
    }

    /** Called after games joined the library outside a scan, so they are identified and filled at once. */
    var onGamesAdded: (List<GameId>) -> Unit = {}

    /**
     * Says why a package game can't start yet (it isn't in its emulator), or null. Set by the store
     * to Fuse's content installs; only asked for games whose file is a package.
     */
    var notInstalled: suspend (GameId) -> io.github.matiyaaa.fuse.ui.shell.store.Problem? = { null }

    /** The installed title a package game starts from instead of its package, when there is one. */
    var installedBoot: suspend (GameId) -> String? = { null }

    private fun afterGamesAdded(ids: List<GameId>) = onGamesAdded(ids)

    override suspend fun restore(id: GameId) {
        data.games.restoreToFuse(id)
        data.games.setHidden(id, false)
    }

    override suspend fun forgetMissing(id: GameId) {
        data.games.forgetMissing(id)
    }

    override suspend fun previewCleanNames(): List<Pair<String, String>> =
        data.titleCleanup.preview(DisplayNameCleaner::clean).map { it.before to it.after }

    override suspend fun applyCleanNames(enabled: Boolean) {
        if (enabled) data.titleCleanup.apply(DisplayNameCleaner::clean) else data.titleCleanup.disable()
        setCleanNames(enabled)
    }

    override suspend fun undoCleanNames(): Boolean = data.titleCleanup.undoLast() != null

    override suspend fun rpcs3Compatibility(id: GameId): io.github.matiyaaa.fuse.ui.shell.store.CompatibilityAnswer {
        val game = data.games.get(id) ?: return io.github.matiyaaa.fuse.ui.shell.store.CompatibilityAnswer.NoTitleId
        val titleId = ps3TitleId(game) ?: return io.github.matiyaaa.fuse.ui.shell.store.CompatibilityAnswer.NoTitleId
        val compat = io.github.matiyaaa.fuse.integrations.rpcs3.Rpcs3Compatibility
        val serializer = io.github.matiyaaa.fuse.integrations.rpcs3.Rpcs3Compat.serializer()
        data.cache.get(COMPAT_CACHE, titleId, serializer, ctx.now())?.let { return io.github.matiyaaa.fuse.ui.shell.store.CompatibilityAnswer.Listed(it) }
        val body = try {
            kotlinx.coroutines.withTimeout(15_000) {
                val response = ctx.services.http.get(compat.url(titleId))
                if (!response.status.isSuccess()) null else response.bodyAsText()
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } ?: return io.github.matiyaaa.fuse.ui.shell.store.CompatibilityAnswer.Unreachable
        val entry = compat.parse(body, titleId) ?: return io.github.matiyaaa.fuse.ui.shell.store.CompatibilityAnswer.NotListed(titleId)
        data.cache.put(COMPAT_CACHE, titleId, entry, serializer, ctx.now(), ttlMs = 7 * 24 * HOUR_MS)
        return io.github.matiyaaa.fuse.ui.shell.store.CompatibilityAnswer.Listed(entry)
    }

    /** Everything needed to read or change one game's PCSX2 patches. */
    private class PatchContext(
        val files: io.github.matiyaaa.fuse.ui.shell.store.EmulatorFiles,
        val settingsPath: String,
        val game: io.github.matiyaaa.fuse.launch.patches.IniText,
        val owned: Set<String>,
        val statuses: List<io.github.matiyaaa.fuse.launch.patches.PatchStatus>,
        val view: io.github.matiyaaa.fuse.ui.shell.store.Pcsx2PatchList.Ready,
    )

    private suspend fun patchContext(id: GameId): Pair<PatchContext?, io.github.matiyaaa.fuse.ui.shell.store.Pcsx2PatchList.Unavailable?> {
        fun no(title: String, reason: String) = null to io.github.matiyaaa.fuse.ui.shell.store.Pcsx2PatchList.Unavailable(title, reason)
        val files = ctx.services.emulatorFiles ?: return no("Not on this device", "PCSX2's patches can be changed from Fuse on a computer. On this device, change them in the emulator.")
        if (ctx.installed.value.isEmpty()) emulators.detectNow()
        val pcsx2 = ctx.installed.value.firstOrNull { it.id.value.substringAfter('.') == "pcsx2" }
            ?: return no("PCSX2 isn't installed", "Install PCSX2, start it once so it sets up its folders, and its patches show here.")
        val home = files.pcsx2(pcsx2) ?: return no("PCSX2 isn't set up yet", "Start PCSX2 once and finish its first-run setup, then come back.")
        val disc = discIdentity(id) ?: return no(
            "Fuse can't read this disc",
            "Patches are filed under the game's serial and CRC, which Fuse reads from ISO and BIN images. Compressed images (CHD, CSO) aren't read; change their patches in PCSX2.",
        )
        val crc = disc.crc ?: return no("Not a PS2 disc", "This image doesn't start a PS2 program, so PCSX2 has no patches for it.")
        val rules = io.github.matiyaaa.fuse.launch.patches.Pcsx2PatchRules
        val parse = io.github.matiyaaa.fuse.launch.patches.Pnach
        val fs = ctx.services.fs
        // Patch files on disk come first, as in PCSX2; a name already listed isn't listed again.
        val patches = LinkedHashMap<String, io.github.matiyaaa.fuse.launch.patches.PnachPatch>()
        var unlabelled = false
        val onDisk = try { fs.list(home.patches) } catch (e: CancellationException) { throw e } catch (e: Exception) { emptyList() }
        for (f in onDisk.filter { !it.isDirectory && rules.matchesPnach(it.name, disc.serial, crc) }.sortedBy { it.name }) {
            val text = fs.readText(f.path, 1024 * 1024) ?: continue
            if (parse.hasUnlabelled(text)) unlabelled = true
            parse.parse(text).forEach { patches.putIfAbsent(it.name, it) }
        }
        var bundledRead = home.patchesZip != null
        if (!unlabelled && home.patchesZip != null) {
            for (entry in rules.zipEntries(disc.serial, crc)) {
                val text = files.zipText(home.patchesZip!!, entry) ?: continue
                parse.parse(text).forEach { patches.putIfAbsent(it.name, it) }
                break
            }
        }
        if (unlabelled) bundledRead = true
        val settingsPath = "${home.gameSettings}/${rules.gameSettingsName(disc.serial, crc)}"
        val game = io.github.matiyaaa.fuse.launch.patches.IniText(fs.readText(settingsPath, 1024 * 1024) ?: "")
        val global = fs.readText(home.settingsFile, 1024 * 1024)?.let { io.github.matiyaaa.fuse.launch.patches.IniText(it) }
        // A patch taken out in PCSX2 since Fuse turned it on is no longer Fuse's.
        val enabled = game.values(rules.SECTION, rules.ENABLE).toSet()
        val recorded = data.owned.get(PATCH_OWNERSHIP, settingsPath)
        val owned = recorded.filter { it in enabled }.toSet()
        // Forgotten for good, so turning it on again in PCSX2 later makes it the user's.
        if (owned != recorded) data.owned.set(PATCH_OWNERSHIP, settingsPath, owned)
        val statuses = rules.states(patches.values.toList(), game, global, owned)
        val view = io.github.matiyaaa.fuse.ui.shell.store.Pcsx2PatchList.Ready(disc.serial, disc.crcText ?: "", statuses, bundledRead)
        return PatchContext(files, settingsPath, game, owned, statuses, view) to null
    }

    override suspend fun pcsx2Patches(id: GameId): io.github.matiyaaa.fuse.ui.shell.store.Pcsx2PatchList {
        val (context, unavailable) = patchContext(id)
        return context?.view ?: unavailable!!
    }

    override suspend fun setPcsx2Patch(id: GameId, name: String, on: Boolean): Boolean {
        val context = patchContext(id).first ?: return false
        val status = context.statuses.firstOrNull { it.patch.name == name } ?: return false
        val owned = io.github.matiyaaa.fuse.launch.patches.Pcsx2PatchRules.change(status, on, context.game, context.owned) ?: return false
        if (!context.files.write(context.settingsPath, context.game.toString())) return false
        data.owned.set(PATCH_OWNERSHIP, context.settingsPath, owned)
        return true
    }

    /** A PS3 game's title id: from its name, else from the PARAM.SFO of a game folder. */
    private suspend fun ps3TitleId(game: io.github.matiyaaa.fuse.model.Game): String? {
        val compat = io.github.matiyaaa.fuse.integrations.rpcs3.Rpcs3Compatibility
        game.tags.serial?.uppercase()?.replace("-", "")?.takeIf(compat::isTitleId)?.let { return it }
        if (game.location.kind != LocationKind.FOLDER) return null
        for (rel in listOf("PS3_GAME/PARAM.SFO", "PARAM.SFO")) {
            val bytes = ctx.services.fs.readBytes(FsPath.join(game.location.path, rel), 0, 64 * 1024) ?: continue
            io.github.matiyaaa.fuse.library.disc.ParamSfo.strings(bytes)["TITLE_ID"]?.trim()?.uppercase()?.takeIf(compat::isTitleId)?.let { return it }
        }
        return null
    }

    private val discs by lazy { io.github.matiyaaa.fuse.library.disc.PlayStationDisc(ctx.services.fs) }

    override suspend fun discIdentity(id: GameId): io.github.matiyaaa.fuse.library.disc.DiscIdentity? {
        val game = data.games.get(id) ?: return null
        if (game.platformId.value !in DISC_PLATFORMS || game.appId != null) return null
        val path = discImage(game) ?: return null
        val stat = ctx.services.fs.stat(path) ?: return null
        val key = "$path|${stat.sizeBytes}|${stat.modifiedAt}"
        val serializer = io.github.matiyaaa.fuse.library.disc.DiscIdentity.serializer()
        data.cache.get(DISC_CACHE, key, serializer, ctx.now())?.let { return it }
        val found = try {
            withContext(Dispatchers.Default) { discs.identify(path) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } ?: return null
        data.cache.put(DISC_CACHE, key, found, serializer, ctx.now(), ttlMs = null)
        return found
    }

    /** The image to read: the first disc, and for a cue sheet the track file it names first. */
    private suspend fun discImage(game: io.github.matiyaaa.fuse.model.Game): String? {
        val path = game.discs.firstOrNull()?.path ?: game.location.launchPath
        val ext = Paths.extension(path).lowercase()
        if (ext in setOf("iso", "bin", "img")) return path
        if (ext != "cue") return null
        val cue = ctx.services.fs.readText(path, 64 * 1024) ?: return null
        val name = Regex("""(?im)^\s*FILE\s+"([^"]+)"""").find(cue)?.groupValues?.get(1) ?: return null
        val dir = FsPath.parent(path) ?: return null
        return FsPath.join(dir, name.replace('\\', '/'))
    }

    override fun playTime(): Flow<PlayTimeReport> = flow {
        val offset = ctx.services.utcOffsetMillis()
        val day = (ctx.now() + offset).floorDiv(TimeWords.DAY_MS)
        val today = day * TimeWords.DAY_MS - offset
        val month = TimeWords.firstOfMonth(day) * TimeWords.DAY_MS - offset
        emitAll(
            combine(data.playSessions.report(today, ctx.weekStart(), month), data.playSessions.totalSeconds(), platforms) { r, totals, cards ->
                val byId = cards.associateBy { it.platform.id }
                val games = ctx.cardsOnce(r.monthGames.take(10).mapNotNull { data.games.summary(it.first) })
                    .associateBy { it.id }
                PlayTimeReport(
                    todaySeconds = r.todaySeconds,
                    weekSeconds = r.weekSeconds,
                    monthSeconds = r.monthSeconds,
                    trackedSeconds = totals.trackedSeconds,
                    importedSeconds = totals.importedSeconds,
                    days = r.days.map { it.seconds },
                    month = TimeWords.monthName(day),
                    games = r.monthGames.take(10).mapNotNull { (id, s) -> games[id]?.let { it to s } },
                    systems = r.platforms.mapNotNull { (id, s) -> byId[PlatformId(id)]?.let { it to s } },
                    loaded = true,
                )
            },
        )
    }.flowOn(Dispatchers.Default)

    override suspend fun launchCandidates(id: GameId): List<String> {
        val game = data.games.get(id) ?: return emptyList()
        val platform = ctx.platform(game.platformId) ?: return emptyList()
        val root = if (game.location.kind == LocationKind.FOLDER) game.location.path else FsPath.parent(game.location.path) ?: return emptyList()
        val extensions = platform.extensions.map { it.lowercase() }.toSet()
        val fs = ctx.services.fs
        val found = ArrayList<String>()
        suspend fun walk(dir: String, depth: Int) {
            val entries = try {
                fs.list(dir)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return
            }
            for (entry in entries.sortedBy { it.name.lowercase() }) {
                if (entry.isDirectory) {
                    if (depth < 2 && entry.name.lowercase() !in ScanRules.excludedFolderNames) walk(entry.path, depth + 1)
                } else if (extensions.isEmpty() || Paths.extension(entry.name).lowercase() in extensions) {
                    found += entry.path
                }
            }
        }
        walk(root, 0)
        return found
    }

    override suspend fun setPlatform(id: GameId, platform: PlatformId?) {
        val game = data.games.get(id) ?: return
        // Back to the folder's system clears the choice, so later folder changes apply again.
        val chosen = platform?.takeUnless { it == (game.scannedPlatformId ?: game.platformId) && game.appId == null }
        data.games.setPlatformOverride(id, chosen)
        // Art found under the old system may be another game: the next fill looks again.
        ctx.data.cache.remove(FILL_TRIED, id.value.toString())
    }

    override suspend fun addGameFile(path: String, platform: PlatformId): GameId? {
        val fs = ctx.services.fs
        val entry = try {
            fs.stat(path)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } ?: return null
        if (entry.isDirectory) return null
        val game = ScannedGame(
            platformId = platform,
            sourceId = AddedGames.SOURCE,
            path = entry.path,
            kind = LocationKind.FILE,
            launchPath = entry.path,
            title = FsPath.stem(entry.name),
            tags = FilenameParser.parse(entry.name).tags,
            sizeBytes = entry.sizeBytes,
            modifiedAt = entry.modifiedAt,
        )
        val known = data.games.idByPath(entry.path)
        // A game already in the library only changes system; one Fuse removed comes back.
        if (known != null) {
            data.games.restoreToFuse(known)
            setPlatform(known, platform)
            return known
        }
        val scan = PlatformFolderScan(AddedGames.SOURCE, platform, AddedGames.FOLDER, 0, listOf(game), complete = false)
        data.indexer.applyFolder(scan, ctx.now(), DisplayNameCleaner::clean, useCleanedForNew = ctx.settings.value.library.cleanDisplayNames)
        val id = data.games.idByPath(entry.path) ?: return null
        afterGamesAdded(listOf(id))
        return id
    }

    override suspend fun browse(path: String?): BrowseListing {
        val roots = runCatching { ctx.services.locations.storageRoots() }.getOrDefault(emptyList())
        if (path == null) {
            return BrowseListing(null, null, roots.map { BrowseEntry(it.path, it.label, isDirectory = true) })
        }
        val trail = trailOf(path, roots)
        val entries = try {
            ctx.services.fs.list(path)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return BrowseListing(path, parentOf(path, roots), emptyList(), error = "Fuse can't read this folder.", trail = trail)
        }
        val shown = entries
            .filter { !it.name.startsWith(".") }
            .sortedWith(compareBy<io.github.matiyaaa.fuse.library.FsEntry>({ !it.isDirectory }, { it.name.lowercase() }))
            .map { BrowseEntry(it.path, it.name, it.isDirectory, it.sizeBytes) }
        return BrowseListing(path, parentOf(path, roots), shown, trail = trail)
    }

    /** The storage place holding [path] by name, then the folders below it; the path's own parts outside any. */
    private fun trailOf(path: String, roots: List<io.github.matiyaaa.fuse.ui.shell.store.LocationHint>): List<String> {
        val p = path.trimEnd('/')
        val root = roots.filter { r -> val rp = r.path.trimEnd('/'); p == rp || p.startsWith("$rp/") || rp.isEmpty() }
            .maxByOrNull { it.path.length }
        val rest = if (root == null) p else p.removePrefix(root.path.trimEnd('/'))
        return listOfNotNull(root?.label) + rest.split('/').filter { it.isNotEmpty() }
    }

    /** The folder above [path], or null (the storage places) at a storage root. */
    private fun parentOf(path: String, roots: List<io.github.matiyaaa.fuse.ui.shell.store.LocationHint>): String? {
        val trimmed = path.trimEnd('/')
        if (roots.any { it.path.trimEnd('/') == trimmed }) return null
        return FsPath.parent(trimmed)?.takeIf { it.isNotEmpty() }
    }
}

private fun SortOrder.comparator(): Comparator<io.github.matiyaaa.fuse.data.repo.GameSummary> = when (this) {
    SortOrder.TITLE -> compareBy { it.sortKey }
    SortOrder.RECENTLY_PLAYED -> compareByDescending<io.github.matiyaaa.fuse.data.repo.GameSummary> { it.lastPlayedAt ?: 0 }.thenBy { it.sortKey }
    SortOrder.RECENTLY_ADDED -> compareByDescending<io.github.matiyaaa.fuse.data.repo.GameSummary> { it.addedAt }.thenBy { it.sortKey }
    SortOrder.MOST_PLAYED -> compareByDescending<io.github.matiyaaa.fuse.data.repo.GameSummary> { it.totalSeconds }.thenBy { it.sortKey }
    SortOrder.RELEASE_YEAR -> compareBy<io.github.matiyaaa.fuse.data.repo.GameSummary> { it.releaseYear ?: Int.MAX_VALUE }.thenBy { it.sortKey }
}

private const val HOUR_MS = 3_600_000L

/** Systems whose disc images say their serial in SYSTEM.CNF. */
private val DISC_PLATFORMS = setOf("ps2", "psx")

/** Cache namespace for [io.github.matiyaaa.fuse.library.disc.DiscIdentity], keyed by path, size and change time. */
private const val DISC_CACHE = "disc.identity"

/** Cache namespace for RPCS3 compatibility entries, by title id. */
private const val COMPAT_CACHE = "rpcs3.compat"

/** Where Fuse records the PCSX2 patches it turned on itself, by game settings file. */
private const val PATCH_OWNERSHIP = "pcsx2.patches"
