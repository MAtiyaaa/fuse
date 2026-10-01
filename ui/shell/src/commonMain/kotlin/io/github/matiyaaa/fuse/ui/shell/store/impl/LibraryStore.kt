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
import io.github.matiyaaa.fuse.library.scan.ScanRules
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
import io.github.matiyaaa.fuse.model.PlatformId
import io.github.matiyaaa.fuse.model.ScanPhase
import io.github.matiyaaa.fuse.model.ScanScope
import io.github.matiyaaa.fuse.model.ScopedSettings
import io.github.matiyaaa.fuse.model.SortOrder
import io.github.matiyaaa.fuse.ui.shell.store.Art
import io.github.matiyaaa.fuse.ui.shell.store.ContentNote
import io.github.matiyaaa.fuse.ui.shell.store.EmulatorChoice
import io.github.matiyaaa.fuse.ui.shell.store.GameCard
import io.github.matiyaaa.fuse.ui.shell.store.GameDetail
import io.github.matiyaaa.fuse.ui.shell.store.GameQuery
import io.github.matiyaaa.fuse.ui.shell.store.HomeFeed
import io.github.matiyaaa.fuse.ui.shell.store.LaunchOutcome
import io.github.matiyaaa.fuse.ui.shell.store.LibraryOps
import io.github.matiyaaa.fuse.ui.shell.store.PlatformCard
import io.github.matiyaaa.fuse.ui.shell.store.PlaytimeSummary
import io.github.matiyaaa.fuse.ui.shell.store.RunResult
import io.github.matiyaaa.fuse.ui.shell.store.SearchResults
import io.github.matiyaaa.fuse.ui.shell.store.StorageSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
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
        data.settings.settings.map { it.library.systemOrder to it.library.systemColors }.distinctUntilChanged()

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
                val candidates = ctx.registry.forPlatform(p.id, ctx.host)
                val installedIds = installed.map { it.id }.toSet()
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
                    emulatorInstalled = effectiveInstalled != null,
                    installedEmulators = candidates.count { it.id in installedIds },
                    bios = bios[p.id] ?: if (p.bios == null) BiosStatus.NotRequired else BiosStatus(io.github.matiyaaa.fuse.model.BiosState.UNKNOWN),
                    layout = layout,
                    romFolders = folders[p.id].orEmpty(),
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

    override fun search(query: String): Flow<SearchResults> = flow {
        val q = query.trim()
        if (q.isEmpty()) {
            emit(SearchResults())
            return@flow
        }
        val games = ctx.cardsOnce(data.games.search(q, limit = 60))
        val systems = platforms.value.filter {
            it.platform.name.contains(q, ignoreCase = true) ||
                it.platform.shortName.contains(q, ignoreCase = true) ||
                it.platform.id.value.equals(q, ignoreCase = true)
        }
        val appCards = if (apps.supported) apps.search(q) else emptyList()
        val cols = collections.collections.value.filter { it.name.contains(q, ignoreCase = true) }
        emit(SearchResults(q, games, systems, appCards, cols))
    }.flowOn(Dispatchers.Default)

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
        ) { media, installed, platformEmulator, cols, (week, ra) ->
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
            )
        }
    }.flowOn(Dispatchers.Default)

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

    override suspend fun launch(id: GameId, emulator: EmulatorId?, discPath: String?, display: LaunchDisplay?): LaunchOutcome {
        val stored = data.games.get(id) ?: return LaunchOutcome.Failed("This game is no longer in your library.")
        val platform = ctx.platform(stored.platformId) ?: return LaunchOutcome.Failed("Fuse doesn't know this system.")
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
        if (chosen != null && chosen.id.value.startsWith("retroarch") && ctx.host == io.github.matiyaaa.fuse.model.Host.LINUX) {
            val coreName = core ?: RetroArchCores.defaultCore(ctx.host, game.platformId)
            val corePath = coreName?.let { ctx.services.emulators.retroArchCorePath(chosen, it) }
            if (corePath != null) {
                resolved = ctx.resolver.resolve(game, platformEmulator, installed, ctx.host, choice.copy(core = coreName, corePath = corePath))
            }
        }

        val installedEmulator = resolved.installed
        if (installedEmulator == null) {
            val suggestions = ctx.registry.forPlatform(game.platformId, ctx.host).filterNot { it.opensAppOnly }.take(3).map { it.name }
            return LaunchOutcome.NeedsEmulator(platform.name, suggestions)
        }
        return when (val plan = resolved.plan) {
            is LaunchPlan.Unsupported -> LaunchOutcome.Unsupported(plan.reason)
            is LaunchPlan.OpenAppOnly -> when (val r = ctx.services.launcher.openApp(plan.appId)) {
                is RunResult.Started -> LaunchOutcome.OpenedAppOnly(installedEmulator.name, plan.reason)
                is RunResult.OpenedAppInstead -> LaunchOutcome.OpenedAppOnly(installedEmulator.name, r.reason)
                RunResult.NotInstalled -> notInstalled(installedEmulator)
                is RunResult.Failed -> LaunchOutcome.Failed(r.message)
            }
            is LaunchPlan.AndroidIntent, is LaunchPlan.Command -> when (val r = ctx.services.launcher.run(resolved, displayId)) {
                is RunResult.Started -> {
                    startSession(id, installedEmulator.id, r.awaitExit)
                    LaunchOutcome.Started
                }
                // Only the app opened; which game gets played there is unknown, so no session is recorded.
                is RunResult.OpenedAppInstead -> LaunchOutcome.OpenedAppOnly(installedEmulator.name, r.reason)
                RunResult.NotInstalled -> notInstalled(installedEmulator)
                is RunResult.Failed -> LaunchOutcome.Failed(r.message)
            }
        }
    }

    private fun notInstalled(emulator: InstalledEmulator): LaunchOutcome {
        emulators.refresh()
        return LaunchOutcome.Failed("${emulator.name} isn't installed any more. Pick another emulator for this game.")
    }

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
        engine.rescan(ScanScope.PLATFORM, game.platformId)
    }

    override suspend fun removeFromFuse(id: GameId) = data.games.removeFromFuse(id)

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
}

private fun SortOrder.comparator(): Comparator<io.github.matiyaaa.fuse.data.repo.GameSummary> = when (this) {
    SortOrder.TITLE -> compareBy { it.sortKey }
    SortOrder.RECENTLY_PLAYED -> compareByDescending<io.github.matiyaaa.fuse.data.repo.GameSummary> { it.lastPlayedAt ?: 0 }.thenBy { it.sortKey }
    SortOrder.RECENTLY_ADDED -> compareByDescending<io.github.matiyaaa.fuse.data.repo.GameSummary> { it.addedAt }.thenBy { it.sortKey }
    SortOrder.MOST_PLAYED -> compareByDescending<io.github.matiyaaa.fuse.data.repo.GameSummary> { it.totalSeconds }.thenBy { it.sortKey }
    SortOrder.RELEASE_YEAR -> compareBy<io.github.matiyaaa.fuse.data.repo.GameSummary> { it.releaseYear ?: Int.MAX_VALUE }.thenBy { it.sortKey }
}

private const val HOUR_MS = 3_600_000L
