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
import io.github.matiyaaa.fuse.library.recommend.RecGame
import io.github.matiyaaa.fuse.library.recommend.Recommender
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
import io.github.matiyaaa.fuse.ui.shell.store.LibraryOps
import io.github.matiyaaa.fuse.ui.shell.store.PlatformCard
import io.github.matiyaaa.fuse.ui.shell.store.PlayTimeReport
import io.github.matiyaaa.fuse.ui.shell.store.PlaytimeSummary
import io.github.matiyaaa.fuse.ui.shell.store.RunResult
import io.github.matiyaaa.fuse.ui.shell.store.SearchResults
import io.github.matiyaaa.fuse.ui.shell.store.StorageSummary
import io.github.matiyaaa.fuse.ui.shell.store.Unavailable
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** A play session Fuse started and has not closed yet. */
private data class ActiveSession(
    val id: Long,
    val gameId: GameId,
    val endsOnResume: Boolean,
    /** The save Fuse Sync captures when the game ends, when Fuse Sync is in use. */
    val save: io.github.matiyaaa.fuse.sync.SaveQuery? = null,
    /** The same, for Syncthing (no profile needed). */
    val plainSave: io.github.matiyaaa.fuse.sync.SaveQuery? = null,
    /** RomM's game and the save it keeps in step, when RomM's save sync takes part. */
    val romm: Pair<Long, io.github.matiyaaa.fuse.sync.SaveQuery>? = null,
)

/** Fuse Sync, for the saves around a game: the service and how it knows games. */
internal class SyncLaunch(val service: io.github.matiyaaa.fuse.sync.SyncService, val keys: io.github.matiyaaa.fuse.sync.ProfileDataPort)

@OptIn(ExperimentalCoroutinesApi::class)
internal class DefaultLibraryOps(
    private val ctx: StoreContext,
    private val engine: LibraryEngine,
    private val emulators: DefaultEmulatorOps,
    private val collections: DefaultCollectionOps,
    private val apps: DefaultAppOps,
    private val achievements: DefaultAchievementOps,
    private val cartridge: DefaultCartridgeOps,
    /** Changes the stored settings (the person's own setting profiles), as the store writes them. */
    private val changeSettings: suspend ((io.github.matiyaaa.fuse.data.settings.AppSettings) -> io.github.matiyaaa.fuse.data.settings.AppSettings) -> Unit = {},
    private val setCleanNames: suspend (Boolean) -> Unit,
) : LibraryOps {
    private val data = ctx.data
    private var active: ActiveSession? = null
        set(value) {
            field = value
            ctx.playing.value = value?.gameId
        }

    /** Fuse Sync, set by the store when it runs here. */
    @kotlin.concurrent.Volatile var sync: SyncLaunch? = null

    /** Syncthing, set by the store where Fuse can use it (it acts only while turned on). */
    @kotlin.concurrent.Volatile var syncthing: io.github.matiyaaa.fuse.sync.syncthing.SyncthingService? = null

    /** RomM's save sync, when RomM is set up here. */
    @kotlin.concurrent.Volatile var rommSaves: io.github.matiyaaa.fuse.sync.romm.RommSaveService? = null

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

    /** The firmware check, with the systems the user marked as set up shown as ready. */
    private val biosShown: Flow<Map<PlatformId, BiosStatus>> = combine(
        engine.bios,
        data.settings.settings.map { it.library.biosConfirmed.toSet() }.distinctUntilChanged(),
    ) { checked, confirmed ->
        val all = checked.keys + confirmed.map(::PlatformId)
        all.associateWith { id ->
            val status = checked[id] ?: if (ctx.platform(id)?.bios == null) BiosStatus.NotRequired else BiosStatus(io.github.matiyaaa.fuse.model.BiosState.UNKNOWN)
            status.confirmedIf(id.value in confirmed)
        }
    }

    override val platforms: StateFlow<List<PlatformCard>> = combine(
        data.games.platformCounts(),
        ctx.installed,
        combine(biosShown, engine.platformFolders, ::Pair),
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

    /**
     * Suggestions and the day's spotlight, from this device's library alone (see [Recommender]):
     * worked out again when games change (a scan, new details, a play) and every hour, since what
     * counts as "a while ago" moves.
     */
    @OptIn(kotlinx.coroutines.FlowPreview::class)
    private val recGames: Flow<List<RecGame<GameId>>> = data.games.observeRecommendationInputs()
        .debounce(RECOMMEND_SETTLE_MS)
        .map { rows -> rows.map { it.toRecGame() } }
        .flowOn(Dispatchers.Default)
        .shareIn(ctx.scope, SharingStarted.WhileSubscribed(RECOMMEND_KEEP_MS), replay = 1)

    private val hourly: Flow<Unit> = flow { while (true) { emit(Unit); kotlinx.coroutines.delay(HOUR_MS) } }

    private val picks: Flow<HomePicks> = combine(recGames, hourly) { games, _ ->
        val now = ctx.now()
        Recommender.recommend(games, now) to Recommender.spotlight(games, now, offsetMs = ctx.services.utcOffsetMillis())
    }.distinctUntilChanged().flatMapLatest { (forYou, spot) ->
        val ids = forYou.map { it.key } + listOfNotNull(spot?.key)
        ctx.cards(data.games.observeSummariesInOrder(ids)).map { cards ->
            val byId = cards.associateBy { it.id }
            HomePicks(
                forYou = forYou.mapNotNull { byId[it.key] },
                spotlight = spot?.let { byId[it.key] },
                reasons = (forYou + listOfNotNull(spot)).associate { it.key to it.reason },
            )
        }
    }.flowOn(Dispatchers.Default)

    override fun spotlight(platform: PlatformId): Flow<io.github.matiyaaa.fuse.ui.shell.store.Suggestion?> = combine(recGames, hourly) { games, _ ->
        Recommender.spotlight(games, ctx.now(), platform = platform.value, offsetMs = ctx.services.utcOffsetMillis())
    }.distinctUntilChanged().flatMapLatest { pick ->
        if (pick == null) {
            flowOf(null)
        } else {
            ctx.cards(data.games.observeSummariesInOrder(listOf(pick.key))).map { cards ->
                cards.firstOrNull()?.let { io.github.matiyaaa.fuse.ui.shell.store.Suggestion(it, pick.reason) }
            }
        }
    }.flowOn(Dispatchers.Default)

    /** Each series' first game's art, for the series' tile. */
    private val seriesArt: Flow<Map<io.github.matiyaaa.fuse.model.CollectionId, io.github.matiyaaa.fuse.ui.shell.store.Art>> =
        data.collections.observeSeriesLeads().flatMapLatest { leads ->
            if (leads.isEmpty()) flowOf(emptyMap()) else ctx.cards(data.games.observeSummariesInOrder(leads.values.distinct())).map { cards ->
                val byGame = cards.associateBy { it.id }
                leads.mapNotNull { (series, game) -> byGame[game]?.let { series to it.art } }.toMap()
            }
        }.distinctUntilChanged()

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
            picks.onStart { emit(HomePicks()) },
            seriesArt.onStart { emit(emptyMap()) },
        ),
    ) { v ->
        val p = v[12] as HomePicks
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
            seriesArt = v[13] as Map<io.github.matiyaaa.fuse.model.CollectionId, io.github.matiyaaa.fuse.ui.shell.store.Art>,
            pinnedApps = v[8] as List<io.github.matiyaaa.fuse.ui.shell.store.AppCard>,
            playtime = v[9] as PlaytimeSummary,
            achievements = v[10] as io.github.matiyaaa.fuse.ui.shell.store.AchievementsFeed?,
            storage = v[11] as StorageSummary?,
            forYou = p.forYou,
            spotlight = p.spotlight,
            reasons = p.reasons,
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
            // Removed for good: still remembered (a scan can't bring them back), no longer listed.
            val purged = ctx.settings.map { it.library.purgedGames.toSet() }.distinctUntilChanged()
            val listed = kotlinx.coroutines.flow.combine(set, purged) { list, gone ->
                if (query.set == io.github.matiyaaa.fuse.ui.shell.store.GameSet.REMOVED) list.filter { it.id.value !in gone } else list
            }
            return ctx.cards(listed.map { list -> list.filter { query.platform == null || it.platformId == query.platform }.sortedWith(query.sort.comparator()) })
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

    // A RomM game Fuse doesn't have is the RomM store's to describe (the second screen shows it too).
    override fun game(id: GameId): Flow<GameDetail?> = ctx.remoteDetail(id) ?: data.games.observe(id).flatMapLatest { record ->
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
                pinned = record.pinned,
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

    /** Which emulator would play [game] here, as its page says it (a RomM game not downloaded yet included). */
    internal suspend fun choiceFor(game: Game): EmulatorChoice {
        val platform = ctx.platform(game.platformId) ?: return EmulatorChoice(null, emptyList(), "Automatic", null, false)
        val installed = ctx.installed.value
        val chosen = data.scopedSettings.resolve(ScopedSettings.Emulator, game.platformId, null).value.takeIf { it.isNotBlank() }?.let(::EmulatorId)
        return choiceOf(game, platform, ctx.resolver.resolve(game, chosen, installed, ctx.host), installed)
    }

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

    override suspend fun launch(id: GameId, emulator: EmulatorId?, discPath: String?, display: LaunchDisplay?, skipSaveCheck: Boolean, playAnyway: Boolean, onStage: (io.github.matiyaaa.fuse.ui.shell.store.LaunchStage) -> Unit): LaunchOutcome =
        launchGame(id, emulator, discPath, display, skipSaveCheck, playAnyway, onStage).also { outcome ->
            if (outcome is LaunchOutcome.Problem) ctx.recordProblem(outcome.problem)
        }

    private suspend fun launchGame(
        id: GameId,
        emulator: EmulatorId?,
        discPath: String?,
        display: LaunchDisplay?,
        skipSaveCheck: Boolean,
        playAnyway: Boolean = false,
        onStage: (io.github.matiyaaa.fuse.ui.shell.store.LaunchStage) -> Unit = {},
    ): LaunchOutcome {
        var stored = data.games.get(id) ?: return LaunchOutcome.Problem(LaunchProblems.gone())
        // A computer's Steam game still kept as a shortcut file that went away moves onto Steam's library.
        if (stored.location.launchPath.endsWith(".steam", ignoreCase = true) && !exists(stored.location.launchPath) && engine.moveSteamShortcutsToLibraries()) {
            stored = data.games.get(id) ?: stored
        }
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
        // Desktop RetroArch ("linux.retroarch", "windows.retroarch"): use a core that is really there.
        // The chosen core first, then the system's cores in their usual order: with only Nestopia
        // installed, NES games play in Nestopia instead of failing to find FCEUmm.
        var usedCore = core
        if (chosen != null && ctx.host.isDesktop && chosen.id.value.substringAfter('.').startsWith("retroarch")) {
            val cores = (listOfNotNull(core) + RetroArchCores.coresFor(ctx.host, game.platformId)).distinct()
            val found = cores.firstNotNullOfOrNull { c -> ctx.services.emulators.retroArchCorePath(chosen, c)?.let { c to it } }
            if (found != null) {
                usedCore = found.first
                resolved = ctx.resolver.resolve(game, platformEmulator, installed, ctx.host, choice.copy(core = found.first, corePath = found.second))
            } else if (cores.isNotEmpty() && ctx.services.emulators.retroArchCoresVisible(chosen)) {
                // None of this system's cores is in RetroArch: another emulator for it plays it
                // (standalone mGBA for GBA), else say what to install, never RetroArch's own error.
                val others = installed.filterNot { it.id == chosen.id }
                val instead = ctx.resolver.resolve(game, platformEmulator?.takeIf { it != chosen.id }, others, ctx.host, choice)
                if (instead.installed == null || instead.plan is LaunchPlan.Unsupported) {
                    return LaunchOutcome.Problem(LaunchProblems.noCore(chosen, platform.name, platform.id, cores, id, canInstall = ctx.services.emulators.canInstallRetroArchCore(chosen)))
                }
                usedCore = null
                resolved = instead
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
                is RunResult.Failed -> LaunchOutcome.Problem(LaunchProblems.refused(installedEmulator, id, r.message, android, goBackOffer(installedEmulator), r.diagnostic, game.platformId, ctx.services.emulators.homeDir, unmetBy(installedEmulator)))
            }
            is LaunchPlan.AndroidIntent, is LaunchPlan.Command -> {
                // Fuse Sync puts the newest save in place first, or asks when both sides played.
                val save = saveQuery(id, game, installedEmulator, usedCore ?: RetroArchCores.defaultCore(ctx.host, game.platformId))
                var note: String? = null
                val link = sync
                if (save != null && link != null && !skipSaveCheck) {
                    onStage(io.github.matiyaaa.fuse.ui.shell.store.LaunchStage.CHECKING_SAVE)
                    when (val gate = link.service.beforeLaunch(save, waitForOthers = !playAnyway)) {
                        is io.github.matiyaaa.fuse.sync.LaunchGate.Conflict -> return LaunchOutcome.SaveConflict(gate.conflict)
                        is io.github.matiyaaa.fuse.sync.LaunchGate.Busy -> return LaunchOutcome.SyncBusy(gate)
                        is io.github.matiyaaa.fuse.sync.LaunchGate.Waiting -> return LaunchOutcome.SyncWaiting(gate)
                        is io.github.matiyaaa.fuse.sync.LaunchGate.Blocked -> return LaunchOutcome.Problem(
                            io.github.matiyaaa.fuse.ui.shell.store.Problem(
                                title = "${gate.title} wasn't started",
                                message = gate.why,
                                kind = io.github.matiyaaa.fuse.ui.shell.store.ProblemKind.DATA,
                                reassurance = "Every save file is kept on this device.",
                                actions = listOf(io.github.matiyaaa.fuse.ui.shell.store.ProblemAction.Retry()),
                            ),
                        )
                        is io.github.matiyaaa.fuse.sync.LaunchGate.Go -> note = gate.note
                    }
                }
                // Syncthing, where it is the one in use: its folders brought up to date, two versions asked about.
                val st = syncthing?.takeIf { ctx.data.settings.current().syncthing.enabled }
                val plainSave = if (st != null) plainSaveQuery(game, installedEmulator, usedCore ?: RetroArchCores.defaultCore(ctx.host, game.platformId)) else null
                if (st != null && plainSave != null && !skipSaveCheck) {
                    onStage(io.github.matiyaaa.fuse.ui.shell.store.LaunchStage.CHECKING_SAVE)
                    when (val gate = st.beforeLaunch(plainSave)) {
                        is io.github.matiyaaa.fuse.sync.syncthing.SyncthingGate.Conflict -> return LaunchOutcome.SyncthingConflict(gate.conflicts)
                        is io.github.matiyaaa.fuse.sync.syncthing.SyncthingGate.Go -> note = note ?: gate.note
                    }
                }
                // RomM's save sync, for a game from RomM: RomM's newer save put in place, or a choice.
                val rommSave = game.links.rommRomId?.let { romId -> rommSaves?.let { rs -> romId to plainSaveQuery(game, installedEmulator, usedCore ?: RetroArchCores.defaultCore(ctx.host, game.platformId)) } }
                // How this game's saves sync here (the same choice as for Fuse One, by the same id).
                val rommMode = io.github.matiyaaa.fuse.ui.shell.sync.SaveSyncMode.of(ctx.data.settings.current().sync, (save ?: rommSave?.second)?.game?.id ?: "")
                if (rommSave != null && !skipSaveCheck && rommMode.receives) {
                    onStage(io.github.matiyaaa.fuse.ui.shell.store.LaunchStage.CHECKING_SAVE)
                    when (val gate = rommSaves!!.beforeLaunch(rommSave.second, rommSave.first)) {
                        is io.github.matiyaaa.fuse.sync.romm.RommSaveGate.Conflict -> return LaunchOutcome.RommSaveConflict(gate.conflict)
                        is io.github.matiyaaa.fuse.sync.romm.RommSaveGate.Go -> note = note ?: gate.note
                    }
                }
                onStage(io.github.matiyaaa.fuse.ui.shell.store.LaunchStage.STARTING)
                // From here the emulator is starting: nothing calls it off any more.
                when (val r = kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { ctx.services.launcher.run(resolved, displayId) }) {
                    is RunResult.Started -> {
                        startSession(id, installedEmulator.id, r.awaitExit, save?.takeIf { link != null }, plainSave, rommSave?.takeIf { rommMode.sends })
                        if (note != null) LaunchOutcome.Synced(note) else LaunchOutcome.Started
                    }
                    // Only the app opened; which game gets played there is unknown, so no session is recorded.
                    is RunResult.OpenedAppInstead -> LaunchOutcome.OpenedAppOnly(installedEmulator.name, r.reason)
                    RunResult.NotInstalled -> notInstalled(installedEmulator, id)
                    is RunResult.Failed -> LaunchOutcome.Problem(LaunchProblems.refused(installedEmulator, id, r.message, android, goBackOffer(installedEmulator), r.diagnostic, game.platformId, ctx.services.emulators.homeDir, unmetBy(installedEmulator)))
                }
            }
        }
    }

    /** What Fuse Sync needs to find [id]'s saves, with the emulator it would start with now; null when none would. */
    internal suspend fun saveQueryFor(id: GameId): io.github.matiyaaa.fuse.sync.SaveQuery? {
        if (sync == null) return null
        val game = data.games.get(id) ?: return null
        if (ctx.installed.value.isEmpty()) emulators.detectNow()
        val settings = data.scopedSettings
        val platformEmulator = settings.resolve(ScopedSettings.Emulator, game.platformId, null).value.takeIf { it.isNotBlank() }?.let(::EmulatorId)
        val core = settings.resolve(ScopedSettings.RetroArchCore, game.platformId, id).value.takeIf { it.isNotBlank() }
        val resolved = ctx.resolver.resolve(game, platformEmulator, ctx.installed.value, ctx.host, ScopedLaunchChoice(core = core, homeDir = ctx.services.emulators.homeDir))
        val installed = resolved.installed ?: return null
        return saveQuery(id, game, installed, coreFor(installed, game, core))
    }

    /** Where [id]'s save is here, as RomM's save sync and Syncthing find it (no profile); null when nothing plays it. */
    internal suspend fun plainSaveQueryFor(id: GameId): io.github.matiyaaa.fuse.sync.SaveQuery? {
        val game = data.games.get(id) ?: return null
        val (resolved, core) = startsWith(game)
        val emulator = resolved.installed ?: return null
        return plainSaveQuery(game, emulator, coreFor(emulator, game, core))
    }

    override suspend fun localSaves(id: GameId): io.github.matiyaaa.fuse.ui.shell.store.LocalSaves = withContext(kotlinx.coroutines.Dispatchers.IO) {
        val none = io.github.matiyaaa.fuse.ui.shell.store.LocalSaves(null, emptyList())
        val game = data.games.get(id) ?: return@withContext none
        val (resolved, core) = startsWith(game)
        val emulator = resolved.installed
            ?: return@withContext none.copy(note = "No emulator plays this game here yet, so it has no saves on this device.")
        val adapter = io.github.matiyaaa.fuse.sync.SaveAdapters.forEmulator(emulator.id.value)
            ?: return@withContext none.copy(emulator = emulator.name, note = io.github.matiyaaa.fuse.sync.SaveAdapters.whyNot(emulator.id.value))
        val env = sync?.service?.saveEnvironment
            ?: return@withContext none.copy(emulator = emulator.name, note = "Fuse can't look at ${emulator.name}'s saves on this device.")
        val query = plainSaveQuery(game, emulator, coreFor(emulator, game, core))
        val spots = runCatching { adapter.locate(query, env) }.getOrDefault(emptyList())
        val saves = buildList {
            for (spot in spots) {
                if (!spot.available) continue
                for (f in spot.files) ctx.services.fs.stat(f.path)?.takeIf { !it.isDirectory }?.let { add(io.github.matiyaaa.fuse.ui.shell.store.LocalSave(f.name, f.path, spot.kind, it.sizeBytes, it.modifiedAt)) }
                // Saves made of folders (a PSP's SAVEDATA): each of this game's folders, with its newest file's time.
                val root = spot.root ?: continue
                for (folder in spot.folders) {
                    val path = "${root.trimEnd('/')}/$folder"
                    val files = runCatching { ctx.services.fs.list(path) }.getOrDefault(emptyList()).filterNot { it.isDirectory }
                    if (files.isEmpty()) continue
                    add(io.github.matiyaaa.fuse.ui.shell.store.LocalSave(folder, path, spot.kind, files.sumOf { it.sizeBytes }, files.maxOf { it.modifiedAt }))
                }
            }
        }.distinctBy { it.path }.sortedByDescending { it.modifiedAt }
        io.github.matiyaaa.fuse.ui.shell.store.LocalSaves(emulator.name, saves, note = if (saves.isEmpty()) "${emulator.name} has no save of it on this device yet." else null)
    }

    /** [game]'s saves for [emulator], as Syncthing looks for them: by the game's own name and system, no profile needed. */
    private suspend fun plainSaveQuery(game: io.github.matiyaaa.fuse.model.Game, emulator: InstalledEmulator, core: String?): io.github.matiyaaa.fuse.sync.SaveQuery =
        io.github.matiyaaa.fuse.sync.SaveQuery(
            game = householdKeyOf(game),
            platform = game.platformId.value,
            romPath = game.location.launchPath,
            emulatorId = emulator.id.value,
            emulatorPath = emulator.appId,
            core = core,
            serial = game.tags.serial,
            title = game.displayTitle,
        )

    /**
     * One game for each system in the library, with the emulator it would start in here: enough for
     * Syncthing to know every save folder in use.
     */
    internal suspend fun saveSamples(): List<io.github.matiyaaa.fuse.sync.SaveQuery> {
        if (ctx.installed.value.isEmpty()) emulators.detectNow()
        val settings = data.scopedSettings
        // Every system at once: each is a lookup and a launch plan, and a big library has many.
        return kotlinx.coroutines.coroutineScope {
            data.games.platformCounts().first().keys.map { pid ->
                async {
                    val summary = data.games.observeByPlatform(pid).first().firstOrNull() ?: return@async null
                    val game = data.games.get(summary.id) ?: return@async null
                    val platformEmulator = settings.resolve(ScopedSettings.Emulator, game.platformId, null).value.takeIf { it.isNotBlank() }?.let(::EmulatorId)
                    val core = settings.resolve(ScopedSettings.RetroArchCore, game.platformId, game.id).value.takeIf { it.isNotBlank() }
                    val resolved = ctx.resolver.resolve(game, platformEmulator, ctx.installed.value, ctx.host, ScopedLaunchChoice(core = core, homeDir = ctx.services.emulators.homeDir))
                    val installed = resolved.installed ?: return@async null
                    plainSaveQuery(game, installed, coreFor(installed, game, core))
                }
            }.awaitAll().filterNotNull()
        }
    }

    /**
     * The RetroArch core [game] plays in with [emulator]: on a computer, the [chosen] one or the
     * system's first core that is installed; elsewhere (or when Fuse can't see the cores) the chosen
     * or usual one. Its saves are kept under that core's name.
     */
    private fun coreFor(emulator: InstalledEmulator, game: io.github.matiyaaa.fuse.model.Game, chosen: String?): String? {
        val usual = chosen ?: RetroArchCores.defaultCore(ctx.host, game.platformId)
        if (!ctx.host.isDesktop || !emulator.id.value.substringAfter('.').startsWith("retroarch")) return usual
        val cores = (listOfNotNull(chosen) + RetroArchCores.coresFor(ctx.host, game.platformId)).distinct()
        return cores.firstOrNull { ctx.services.emulators.retroArchCorePath(emulator, it) != null } ?: usual
    }

    /** What Fuse Sync needs to find [game]'s saves for [emulator] here, or null without Fuse Sync. */
    private suspend fun saveQuery(id: GameId, game: io.github.matiyaaa.fuse.model.Game, emulator: InstalledEmulator, core: String?): io.github.matiyaaa.fuse.sync.SaveQuery? {
        val link = sync ?: return null
        val key = link.keys.keyOf(id.value) ?: return null
        return io.github.matiyaaa.fuse.sync.SaveQuery(
            game = key,
            platform = game.platformId.value,
            romPath = game.location.launchPath,
            emulatorId = emulator.id.value,
            emulatorPath = emulator.appId,
            core = core,
            // Emulators that keep saves by a game's id (RPCS3, PPSSPP, the 3DS and Switch) need it.
            serial = game.tags.serial,
            title = game.displayTitle,
        )
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
    private suspend fun startSession(
        game: GameId, emulator: EmulatorId, awaitExit: (suspend () -> Unit)?, save: io.github.matiyaaa.fuse.sync.SaveQuery? = null,
        plainSave: io.github.matiyaaa.fuse.sync.SaveQuery? = null, romm: Pair<Long, io.github.matiyaaa.fuse.sync.SaveQuery>? = null,
    ) {
        val startedAt = ctx.now()
        val sessionId = data.playSessions.start(game, emulator, startedAt)
        active = ActiveSession(sessionId, game, endsOnResume = awaitExit == null, save = save, plainSave = plainSave, romm = romm)
        // Fuse Sync keeps the save moving while the game runs, and tells the other devices it is being played.
        val link = sync
        if (save != null && link != null) ctx.scope.launch { runCatching { link.service.playing(save, startedAt) } }
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
        val save = active?.takeIf { it.id == sessionId }?.save
        // Syncthing sends the save the game just wrote.
        active?.takeIf { it.id == sessionId }?.plainSave?.let { q -> syncthing?.let { st -> ctx.scope.launch { runCatching { st.afterExit(q) } } } }
        // RomM's save sync sends it too, when it changed and RomM's didn't.
        active?.takeIf { it.id == sessionId }?.romm?.let { (romId, q) -> rommSaves?.let { rs -> ctx.scope.launch { delay(ROMM_SETTLE_MS); runCatching { rs.afterExit(q, romId) } } } }
        if (active?.id == sessionId) active = null
        val ended = data.playSessions.end(sessionId, ctx.now())
        // Fuse Sync counts the session and keeps the save the game just wrote.
        val link = sync
        if (save != null && link != null && ended?.endedAt != null) {
            ctx.scope.launch { runCatching { link.service.afterExit(save, ended.startedAt, ended.endedAt!!) } }
        }
    }

    private fun afterPlaying() {
        achievements.refresh(force = true)
    }

    override fun onResume() {
        ctx.governor.onForeground(true)
        // Back after a while away with no game in between (the screen was off, the device asleep):
        // the interface welcomes the user back with the startup animation.
        val pausedAt = awayFrom
        awayFrom = null
        if (pausedAt != null && active == null) io.github.matiyaaa.fuse.ui.shell.app.Away.returned(ctx.now() - pausedAt)
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

    /** When Fuse last went to the background, until it comes back. */
    @kotlin.concurrent.Volatile private var awayFrom: Long? = null

    override fun onPause() {
        awayFrom = ctx.now()
        ctx.governor.onForeground(false)
    }

    /** On start: closes a session left open when Fuse was stopped while a game ran. */
    suspend fun recoverSession() {
        data.playSessions.resumeOpen(ctx.now())
    }

    // Edits -----------------------------------------------------------------------------------------

    override suspend fun setFavorite(id: GameId, favorite: Boolean) = data.games.setFavorite(id, favorite).also { ctx.userChanged() }

    override suspend fun setHidden(id: GameId, hidden: Boolean) = data.games.setHidden(id, hidden).also { ctx.userChanged() }

    override suspend fun setPinned(id: GameId, pinned: Boolean) = data.games.setPinned(id, pinned).also { ctx.userChanged() }

    override suspend fun rename(id: GameId, title: String?) {
        // A RomM game Fuse doesn't have keeps its name with RomM.
        ctx.remoteGames?.takeIf { it.owns(id) }?.let { it.rename(id, title); return }
        data.games.rename(id, title?.trim()?.takeIf { it.isNotEmpty() })
        ctx.userChanged()
    }

    override suspend fun setEmulator(id: GameId, emulator: EmulatorId?) = data.games.setEmulatorOverride(id, emulator).also { ctx.userChanged() }

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

    override suspend fun steamGame(appId: Long): GameId? {
        val found = runCatching { engine.findSteamGames(null) }.getOrDefault(emptyList()).firstOrNull { it.appId == appId } ?: return null
        return data.games.idByPath(io.github.matiyaaa.fuse.library.FsPath.normalize(found.folder))
    }

    /** How the library changes settings it owns (the store's ordered settings writer). */
    var writeSettings: (suspend ((io.github.matiyaaa.fuse.data.settings.AppSettings) -> io.github.matiyaaa.fuse.data.settings.AppSettings) -> Unit)? = null

    override suspend fun purgeRemoved(ids: List<GameId>) {
        if (ids.isEmpty()) return
        // Only games that really are removed; a game in the library is never purged by mistake.
        val removed = data.games.observeRemoved().first().map { it.id }.toSet()
        val gone = ids.filter { it in removed }
        if (gone.isEmpty()) return
        for (id in gone) data.games.dropMedia(id)
        writeSettings?.invoke { s -> s.copy(library = s.library.copy(purgedGames = (s.library.purgedGames + gone.map { it.value }).distinct())) }
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

    /** Patches and cheats in PCSX2's, DuckStation's and PPSSPP's own files. */
    private val nativePatches by lazy { NativePatches(ctx, { emulators.detectNow() }, ::discIdentity, ::pspGameId, ::retroArchRun) }

    /** The emulator settings Fuse changes, for a game or for every game. */
    private val nativeSettings by lazy { NativeSettingsStore(ctx, { emulators.detectNow() }, ::discIdentity, ::pspGameId, nintendoGameId = ::nintendoGameId, startsIn = { startsWith(it).first.installed }, changeSettings = changeSettings) }

    override suspend fun emulatorSettings(id: GameId?, emulator: EmulatorId?) = nativeSettings.view(id, emulator)

    override suspend fun applySettingProfile(id: GameId?, emulator: EmulatorId?, profile: String) = nativeSettings.applyProfile(id, emulator, profile)

    override suspend fun saveSettingProfile(id: GameId?, emulator: EmulatorId?, name: String) = nativeSettings.saveProfile(id, emulator, name)

    override suspend fun deleteSettingProfile(profile: String) = nativeSettings.deleteProfile(profile)

    override suspend fun setEmulatorSetting(id: GameId?, emulator: EmulatorId?, setting: String, value: String?) = nativeSettings.set(id, emulator, setting, value)

    override suspend fun restoreEmulatorSettings(id: GameId?, emulator: EmulatorId?) = nativeSettings.restore(id, emulator)

    /** How [game] would start here, as [launch] resolves it, with the RetroArch core it would use (if any). */
    private suspend fun startsWith(game: io.github.matiyaaa.fuse.model.Game): Pair<ResolvedLaunch, String?> {
        if (ctx.installed.value.isEmpty()) emulators.detectNow()
        val settings = data.scopedSettings
        val platformEmulator = settings.resolve(ScopedSettings.Emulator, game.platformId, null).value.takeIf { it.isNotBlank() }?.let(::EmulatorId)
        val core = settings.resolve(ScopedSettings.RetroArchCore, game.platformId, game.id).value.takeIf { it.isNotBlank() }
        return ctx.resolver.resolve(game, platformEmulator, ctx.installed.value, ctx.host, ScopedLaunchChoice(core = core, homeDir = ctx.services.emulators.homeDir)) to core
    }

    /**
     * Going back to the version of [emulator] the Store's last update replaced, offered when that
     * update was in the last few weeks and Fuse still keeps the version before it.
     */
    /** What this computer lacks for [e] to run, read only, for a launch that failed. */
    private fun unmetBy(e: io.github.matiyaaa.fuse.model.InstalledEmulator): String? =
        runCatching { ctx.services.intelligence()?.first?.let { io.github.matiyaaa.fuse.launch.intelligence.EmulatorRequirements.forInstalled(e, it) } }.getOrNull()

    private fun goBackOffer(emulator: InstalledEmulator): io.github.matiyaaa.fuse.ui.shell.store.ProblemAction.GoBack? {
        val (key, rec) = ctx.settings.value.store.installs.entries.firstOrNull { it.value.packageName == emulator.appId }?.toPair() ?: return null
        if (rec.previous == null || ctx.now() - rec.installedAt > GO_BACK_OFFER_MS) return null
        return io.github.matiyaaa.fuse.ui.shell.store.ProblemAction.GoBack(key, rec.previousVersion?.let { "Go back to $it" } ?: "Go back to the version before")
    }

    /** The RetroArch run [game] would start with on this computer (its core and content), as [launch] resolves it; null when it starts elsewhere. */
    private suspend fun retroArchRun(game: io.github.matiyaaa.fuse.model.Game): NativePatches.RetroArchRun? {
        if (!ctx.host.isDesktop) return null
        val (resolved, core) = startsWith(game)
        val installed = resolved.installed ?: return null
        if (!installed.id.value.substringAfter('.').startsWith("retroarch")) return null
        val coreName = coreFor(installed, game, core) ?: return null
        val content = when (val t = resolved.target) {
            is LaunchTarget.File -> t.path
            is LaunchTarget.Playlist -> t.path
            else -> game.location.launchPath
        }
        return NativePatches.RetroArchRun(installed, coreName, content)
    }

    override suspend fun gamePatches(id: GameId): io.github.matiyaaa.fuse.ui.shell.store.GamePatchList = nativePatches.list(id)

    override suspend fun setGamePatch(id: GameId, name: String, on: Boolean, cheat: Boolean): Boolean = nativePatches.set(id, name, on, cheat)

    /** A person's own cheats, patches and texture packs, put where the game's emulator reads them. */
    private val nativeEnhancements by lazy {
        NativeEnhancements(ctx, { emulators.detectNow() }, ::discIdentity, ::pspGameId, ::retroArchRun) { game ->
            startsWith(game).first.installed?.let { plainSaveQuery(game, it, null) }
        }
    }

    override suspend fun enhancements(id: GameId) = nativeEnhancements.list(id)

    override suspend fun planEnhancement(id: GameId, kind: io.github.matiyaaa.fuse.launch.patches.EnhancementKind, source: String) = nativeEnhancements.plan(id, kind, source)

    override suspend fun addEnhancement(token: String) = nativeEnhancements.add(token)

    override suspend fun removeEnhancement(id: GameId, enhancement: String, force: Boolean) = nativeEnhancements.remove(id, enhancement, force)

    /** Mods from GameBanana, added through [nativeEnhancements]. */
    private val modSites by lazy { ModSites(ctx, nativeEnhancements) }

    override suspend fun modSiteGames(id: GameId, query: String?) = modSites.games(id, query)

    override suspend fun linkModSiteGame(id: GameId, game: io.github.matiyaaa.fuse.integrations.gamebanana.GbGame?) = modSites.link(id, game)

    override suspend fun modSiteMods(id: GameId, page: Int) = modSites.mods(id, page)

    override suspend fun modSitePage(id: GameId, modId: Long) = modSites.page(id, modId)

    override suspend fun fetchModSiteFile(id: GameId, modId: Long, fileId: Long, onProgress: (Float) -> Unit) = modSites.fetch(id, modId, fileId, onProgress)

    private val emuCoreX by lazy { EmuCoreXMods(ctx, nativeEnhancements, ::discIdentity) }

    override suspend fun emuCoreX(id: GameId) = emuCoreX.finds(id)

    override suspend fun fetchEmuCoreX(id: GameId, entryId: String, onProgress: (Float) -> Unit) = emuCoreX.fetch(id, entryId, onProgress)

    /**
     * A PSP game's id as PPSSPP files it (`ULUS10041`): from its name or tags, else read from its ISO
     * or EBOOT.PBP (a game folder's EBOOT.PBP too), kept by path, size and change time.
     */
    private suspend fun pspGameId(game: io.github.matiyaaa.fuse.model.Game): String? {
        io.github.matiyaaa.fuse.launch.patches.PpssppHome.gameId(game.tags.serial)?.let { return it }
        val fs = ctx.services.fs
        val launch = game.location.launchPath
        val path = when {
            game.location.kind == LocationKind.FOLDER -> FsPath.join(game.location.path, "EBOOT.PBP")
            else -> launch
        }
        val ext = Paths.extension(path).lowercase()
        if (ext != "iso" && ext != "pbp") return null
        val stat = fs.stat(path) ?: return null
        val key = "psp|$path|${stat.sizeBytes}|${stat.modifiedAt}"
        val serializer = kotlinx.serialization.serializer<String>()
        data.cache.get(DISC_CACHE, key, serializer, ctx.now())?.let { return it }
        val found = try {
            withContext(Dispatchers.Default) { discs.pspGameId(path) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } ?: return null
        data.cache.put(DISC_CACHE, key, found, serializer, ctx.now(), ttlMs = null)
        return found
    }

    /**
     * A GameCube or Wii game's id as Dolphin files it (`GALE01`): from its name or tags, else read
     * from its disc image's header, kept by path, size and change time.
     */
    private suspend fun nintendoGameId(game: io.github.matiyaaa.fuse.model.Game): String? {
        val disc = io.github.matiyaaa.fuse.library.disc.NintendoDisc
        disc.fromTag(game.tags.serial)?.let { return it }
        val path = game.discs.firstOrNull()?.path ?: game.location.launchPath
        if (Paths.extension(path).lowercase() !in setOf("iso", "gcm", "rvz", "wia", "ciso")) return null
        val stat = ctx.services.fs.stat(path) ?: return null
        val key = "nintendo|$path|${stat.sizeBytes}|${stat.modifiedAt}"
        val serializer = kotlinx.serialization.serializer<String>()
        data.cache.get(DISC_CACHE, key, serializer, ctx.now())?.let { return it }
        val head = ctx.services.fs.readBytes(path, 0, disc.HEAD) ?: return null
        val found = disc.gameId(head) ?: return null
        data.cache.put(DISC_CACHE, key, found, serializer, ctx.now(), ttlMs = null)
        return found
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
        data.cache.get(DISC_CACHE, key, serializer, ctx.now())?.let { return it.also { d -> recordContentSerial(id, d.serial) } }
        val found = try {
            withContext(Dispatchers.Default) { discs.identify(path) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } ?: return null
        data.cache.put(DISC_CACHE, key, found, serializer, ctx.now(), ttlMs = null)
        recordContentSerial(id, found.serial)
        return found
    }

    /** [game] as Fuse Identity sees it, with every serial and hash known for it. */
    internal suspend fun evidenceOf(game: io.github.matiyaaa.fuse.model.Game): io.github.matiyaaa.fuse.library.identity.GameEvidence {
        val facts = runCatching { data.identity.forGame(game.id) }.getOrDefault(emptyList())
        return GameIdentities.evidence(game, facts)
    }

    /** The key Fuse Sync, Syncthing and the household know [game] by. */
    private suspend fun householdKeyOf(game: io.github.matiyaaa.fuse.model.Game): io.github.matiyaaa.fuse.sync.GameKey =
        io.github.matiyaaa.fuse.sync.GameKey.of(evidenceOf(game))

    private val hltb by lazy { HltbStore(ctx) }

    override suspend fun howLongToBeat(id: GameId) = runCatching { hltb.forGame(id) }.getOrNull()

    override suspend fun howLongToBeatChoices(id: GameId) = runCatching { hltb.choices(id) }.getOrDefault(emptyList())

    override suspend fun chooseHowLongToBeat(id: GameId, game: io.github.matiyaaa.fuse.integrations.hltb.HltbGame?) {
        runCatching { hltb.choose(id, game) }
    }

    override suspend fun householdKeys(): Map<GameId, List<String>> {
        return data.profileState.read().games.associate { r -> GameId(r.id) to LibraryProfileData.candidatesOf(r).map { it.id } }
    }

    /** Remembers the serial read from a game itself, which Fuse Identity trusts over its name's. */
    private suspend fun recordContentSerial(id: GameId, serial: String?) {
        val value = io.github.matiyaaa.fuse.library.identity.Ids.serialOrNull(serial) ?: return
        val known = runCatching { data.identity.forGame(id) }.getOrDefault(emptyList())
            .filter { it.kind == io.github.matiyaaa.fuse.data.repo.IdentityRepository.SERIAL_CONTENT }.map { it.value }
        if (known == listOf(value)) return
        runCatching { data.identity.replace(id, io.github.matiyaaa.fuse.data.repo.IdentityRepository.SERIAL_CONTENT, listOf(value), "disc") }
    }

    /**
     * Reads the serial of every disc and folder game that hasn't had it read yet (only a few
     * kilobytes from each: a disc's header, a PARAM.SFO), so Fuse Identity knows each by the game
     * itself. Runs after scans; what was read is kept, so later runs only read new games.
     */
    internal suspend fun identifyLibrary(): Unit = identifyLock.withLock {
        val known = runCatching { data.identity.all() }.getOrDefault(emptyMap())
        val done = known.filterValues { facts -> facts.any { it.kind == io.github.matiyaaa.fuse.data.repo.IdentityRepository.SERIAL_CONTENT } }.keys
        for (platform in IDENTIFIED_PLATFORMS) {
            val ids = runCatching { data.games.inPlatform(PlatformId(platform)).map { it.first } }.getOrDefault(emptyList())
            for (id in ids) {
                if (id.value in done) continue
                kotlinx.coroutines.yield()
                val game = data.games.get(id) ?: continue
                if (game.appId != null) continue
                runCatching {
                    when (platform) {
                        "psx", "ps2" -> discIdentity(id)
                        "ps3" -> ps3TitleId(game)?.let { recordContentSerial(id, it) }
                        "ngc", "wii" -> nintendoGameId(game)?.takeIf { game.tags.serial == null }?.let { recordContentSerial(id, it) }
                        else -> null
                    }
                }
            }
        }
    }

    private val identifyLock = kotlinx.coroutines.sync.Mutex()

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

    override suspend fun addGameFolder(path: String, platform: PlatformId): List<GameId> {
        val fs = ctx.services.fs
        val entry = try {
            fs.stat(path)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } ?: return emptyList()
        if (!entry.isDirectory) return emptyList()
        val system = io.github.matiyaaa.fuse.library.PlatformCatalog.byId(platform) ?: return emptyList()
        // Read as the library scan reads a folder in a system's folder: the game with its updates,
        // DLC and other parts attached, a disc set as one game, a known folder structure as itself.
        val result = io.github.matiyaaa.fuse.library.scan.FolderInterpreter(fs).interpretFolder(system, entry, AddedGames.SOURCE)
        if (result.games.isEmpty()) return emptyList()
        // Games already here only change system (and come back if Fuse removed them).
        val known = result.games.mapNotNull { g -> data.games.idByPath(g.path)?.let { g.path to it } }.toMap()
        for (id in known.values) {
            data.games.restoreToFuse(id)
            setPlatform(id, platform)
        }
        val fresh = result.games.filter { it.path !in known }
        if (fresh.isNotEmpty()) {
            val scan = PlatformFolderScan(
                AddedGames.SOURCE, platform, AddedGames.FOLDER, 0, fresh, complete = false,
                absorbed = result.absorbed, absorbedBy = result.absorbedBy,
            )
            data.indexer.applyFolder(scan, ctx.now(), DisplayNameCleaner::clean, useCleanedForNew = ctx.settings.value.library.cleanDisplayNames)
        }
        val ids = result.games.mapNotNull { data.games.idByPath(it.path) }
        val added = ids.filter { it !in known.values }
        if (added.isNotEmpty()) afterGamesAdded(added)
        return ids
    }

    override suspend fun attachAsContent(child: GameId, owner: GameId, kind: io.github.matiyaaa.fuse.model.ContentKind): Boolean =
        data.games.attachAsContent(child, owner, kind).also { if (it) ctx.userChanged() }

    override suspend fun detachContent(child: GameId): GameId? =
        data.games.detachContent(child).also { if (it != null) ctx.userChanged() }

    override suspend fun userParts(owner: GameId) = data.games.userParts(owner)

    override suspend fun applyPatch(id: GameId, patchPath: String): io.github.matiyaaa.fuse.ui.shell.store.PatchApplied {
        val failed = io.github.matiyaaa.fuse.ui.shell.store.PatchApplied::Failed
        val game = data.games.get(id) ?: return failed("This game isn't in the library any more.")
        if (game.appId != null || game.location.kind != LocationKind.FILE) return failed("Patches apply to a game that is one file. This one is a folder or an app.")
        val path = game.location.path
        val ext = path.substringAfterLast('.', "").lowercase()
        if (ext in ARCHIVES) return failed("This game is in a .$ext archive. Patches apply to the game itself: unpack it first, then patch that.")
        val limit = io.github.matiyaaa.fuse.library.patch.RomPatches.MAX_SIZE.toInt()
        val patch = ctx.services.readFile(patchPath, 64 * 1024 * 1024) ?: return failed("Fuse couldn't read the patch.")
        val rom = ctx.services.readFile(path, limit) ?: return failed("Fuse couldn't read the game (or it is larger than the games patches are for).")
        val outcome = withContext(Dispatchers.Default) { io.github.matiyaaa.fuse.library.patch.RomPatches.apply(rom, patch) }
        fun crc(v: Long) = v.toString(16).uppercase().padStart(8, '0')
        return when (outcome) {
            is io.github.matiyaaa.fuse.library.patch.PatchOutcome.Unreadable -> failed(outcome.reason)
            is io.github.matiyaaa.fuse.library.patch.PatchOutcome.AlreadyPatched -> io.github.matiyaaa.fuse.ui.shell.store.PatchApplied.AlreadyPatched
            is io.github.matiyaaa.fuse.library.patch.PatchOutcome.WrongGame -> io.github.matiyaaa.fuse.ui.shell.store.PatchApplied.WrongGame(outcome.format.label, crc(outcome.expected), crc(outcome.actual))
            is io.github.matiyaaa.fuse.library.patch.PatchOutcome.Patched -> {
                // Named after the game and the patch, so both are plain to see, beside the game.
                val stem = FsPath.name(path).let { n -> n.substringBeforeLast('.', n) }
                val patchStem = patchPath.replace('\\', '/').substringAfterLast('/').let { n -> n.substringBeforeLast('.', n) }.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(80).trim()
                val name = "$stem - $patchStem${if (ext.isEmpty()) "" else ".$ext"}"
                val written = ctx.services.writeBeside(path, name, outcome.bytes)
                    ?: return failed("Fuse couldn't write the patched copy beside the game (the folder may not allow it, or \"$name\" is there already).")
                val added = addGameFile(written, game.platformId)
                io.github.matiyaaa.fuse.ui.shell.store.PatchApplied.Made(added, name, outcome.format.label, outcome.checked, outcome.headerRemoved)
            }
        }
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

/** How long the library stays still before suggestions are worked out again (a scan changes many games). */
private const val RECOMMEND_SETTLE_MS = 2_000L

/** How long suggestions' inputs are kept after the last screen using them goes (moving between pages). */
private const val RECOMMEND_KEEP_MS = 30_000L

/** Home's suggestions, the day's spotlight, and why each is there. */
private data class HomePicks(
    val forYou: List<GameCard> = emptyList(),
    val spotlight: GameCard? = null,
    val reasons: Map<GameId, String> = emptyMap(),
)

private fun io.github.matiyaaa.fuse.data.repo.RecommendationRow.toRecGame() = RecGame(
    key = id, platform = platformId.value, title = title, favorite = favorite, lastPlayedAt = lastPlayedAt,
    playSeconds = playSeconds, sessions = sessions, addedAt = addedAt, year = year, genres = genres,
    developer = developer, franchise = franchise, rating = rating,
)

/** Systems whose disc images say their serial in SYSTEM.CNF. */
private val DISC_PLATFORMS = setOf("ps2", "psx")

/** Systems whose games carry their own id, read by [DefaultLibraryOps.identifyLibrary]. */
private val IDENTIFIED_PLATFORMS = listOf("psx", "ps2", "ps3", "ngc", "wii")

/** Cache namespace for [io.github.matiyaaa.fuse.library.disc.DiscIdentity], keyed by path, size and change time. */
private const val DISC_CACHE = "disc.identity"

/** How long after an update Fuse offers to go back to the version before when the emulator won't start. */
private const val GO_BACK_OFFER_MS = 30L * 24 * 60 * 60 * 1000

/** Cache namespace for RPCS3 compatibility entries, by title id. */
private const val COMPAT_CACHE = "rpcs3.compat"

/** Archives a patch never applies to: it is for the game inside. */
private val ARCHIVES = setOf("zip", "7z", "rar", "gz", "chd", "rvz", "cso", "zso")

/** Emulators finish writing a moment after they close: RomM's save sync waits that long. */
private const val ROMM_SETTLE_MS = 1_500L
