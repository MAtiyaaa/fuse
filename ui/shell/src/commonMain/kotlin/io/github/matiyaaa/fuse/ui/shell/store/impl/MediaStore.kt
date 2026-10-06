package io.github.matiyaaa.fuse.ui.shell.store.impl

import io.github.matiyaaa.fuse.integrations.cartridge.RommGuard
import io.github.matiyaaa.fuse.integrations.match.TitleMatcher
import io.github.matiyaaa.fuse.data.settings.SecretKeys
import io.github.matiyaaa.fuse.integrations.KeyCheck
import io.github.matiyaaa.fuse.integrations.igdb.IgdbClient
import io.github.matiyaaa.fuse.integrations.igdb.IgdbCredentials
import io.github.matiyaaa.fuse.integrations.libretro.LibretroThumbnails
import io.github.matiyaaa.fuse.integrations.scrape.FillPlanner
import io.github.matiyaaa.fuse.integrations.scrape.Guess
import io.github.matiyaaa.fuse.integrations.scrape.IgdbSource
import io.github.matiyaaa.fuse.integrations.scrape.LibretroSource
import io.github.matiyaaa.fuse.integrations.scrape.ScrapeCoordinator
import io.github.matiyaaa.fuse.integrations.scrape.ScrapeOutcome
import io.github.matiyaaa.fuse.integrations.scrape.ScrapeRequest
import io.github.matiyaaa.fuse.integrations.scrape.ScrapeSource
import io.github.matiyaaa.fuse.integrations.scrape.SteamGridDbSource
import io.github.matiyaaa.fuse.integrations.scrape.TheGamesDbSource
import io.github.matiyaaa.fuse.integrations.steamgriddb.SteamGridDbClient
import io.github.matiyaaa.fuse.integrations.thegamesdb.TheGamesDbClient
import io.github.matiyaaa.fuse.library.FsPath
import io.github.matiyaaa.fuse.library.parse.DisplayNameCleaner
import io.github.matiyaaa.fuse.library.parse.SearchTitles
import io.github.matiyaaa.fuse.model.ArtworkOption
import io.github.matiyaaa.fuse.model.Game
import io.github.matiyaaa.fuse.model.GameId
import io.github.matiyaaa.fuse.model.GameMetadata
import io.github.matiyaaa.fuse.model.MediaFillMode
import io.github.matiyaaa.fuse.model.MediaItem
import io.github.matiyaaa.fuse.model.MediaKind
import io.github.matiyaaa.fuse.model.MediaOwner
import io.github.matiyaaa.fuse.model.MediaSet
import io.github.matiyaaa.fuse.model.MediaSource
import io.github.matiyaaa.fuse.model.MetadataSource
import io.github.matiyaaa.fuse.model.PlatformId
import io.github.matiyaaa.fuse.model.ProviderStatus
import io.github.matiyaaa.fuse.model.ScopedSettings
import io.github.matiyaaa.fuse.model.ScrapeCandidate
import io.github.matiyaaa.fuse.model.ScrapeProviderId
import io.github.matiyaaa.fuse.model.ScrapeQuery
import io.github.matiyaaa.fuse.ui.shell.store.ArtworkResult
import io.github.matiyaaa.fuse.ui.shell.store.FillChoice
import io.github.matiyaaa.fuse.ui.shell.store.FillProgress
import io.github.matiyaaa.fuse.ui.shell.store.IdentifyResult
import io.github.matiyaaa.fuse.ui.shell.store.MediaOps
import io.github.matiyaaa.fuse.ui.shell.store.SearchTitle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Artwork and metadata. Providers are only asked when the user starts something (a fill, a search
 * for art, a match), and only the configured ones. Custom art (source USER) is never replaced by a
 * fill; Reset is the only way to remove it.
 */
internal class DefaultMediaOps(
    private val ctx: StoreContext,
    private val credentials: DefaultCredentialOps,
) : MediaOps {
    private val media = ctx.data.media
    private var fillJob: Job? = null
    private var cached: Pair<Set<ScrapeProviderId>, ScrapeCoordinator>? = null

    private val progress = MutableStateFlow<FillProgress?>(null)
    override val fillProgress: StateFlow<FillProgress?> = progress

    private val systemArt = SystemArtStore(ctx)
    override val systemArtProgress: StateFlow<FillProgress?> = systemArt.progress

    /** Starts fetching system art for systems that have none (see [SystemArtStore]). */
    fun startSystemArt() = systemArt.start()

    override fun downloadSystemArt() = systemArt.downloadAll()

    override suspend fun panelGames(platform: PlatformId) = systemArt.panelGames(platform)

    override suspend fun panelPictures(game: GameId) = systemArt.panelPictures(game)

    override suspend fun setSystemPanel(platform: PlatformId, option: ArtworkOption) = systemArt.setPanel(platform, option)

    override suspend fun autoSystemPanel(platform: PlatformId) = systemArt.autoPanel(platform)

    override suspend fun restoreDefaultSystemArt(platforms: List<PlatformId>): io.github.matiyaaa.fuse.ui.shell.store.ArtUndo {
        val ids = platforms.ifEmpty { ctx.data.games.platformCounts().first().filterValues { it > 0 }.keys.toList() }
        return systemArt.restoreDefault(ids)
    }

    override suspend fun undoSystemArt(undo: io.github.matiyaaa.fuse.ui.shell.store.ArtUndo) {
        (undo as? SystemArtUndo)?.let { systemArt.undo(it) }
    }

    private val checks = MutableStateFlow<Map<ScrapeProviderId, KeyCheck?>>(emptyMap())
    override val keyChecks: StateFlow<Map<ScrapeProviderId, KeyCheck?>> = checks
    private var checkJob: Job? = null

    override val providers: StateFlow<List<ProviderStatus>> = combine(ctx.settings, credentials.stored) { settings, keys ->
        val order = settings.scraping.effectiveOrder()
        val disabled = settings.scraping.disabledProviders.toSet()
        (order + ScrapeProviderId.entries.filter { it in disabled }).distinct().map { id ->
            val configured = when (id) {
                ScrapeProviderId.STEAMGRIDDB -> SecretKeys.SGDB_API_KEY in keys
                ScrapeProviderId.IGDB -> SecretKeys.IGDB_CLIENT_ID in keys && SecretKeys.IGDB_CLIENT_SECRET in keys
                ScrapeProviderId.THEGAMESDB -> SecretKeys.TGDB_API_KEY in keys
                ScrapeProviderId.SCREENSCRAPER -> false
                else -> true
            }
            val note = when (id) {
                ScrapeProviderId.LOCAL -> "Art saved next to your games (ES-DE, RetroBat and Cartridge layouts)"
                ScrapeProviderId.ROMM -> "Art Cartridge saved from your RomM server, read from your library folders"
                ScrapeProviderId.LIBRETRO -> "Box art and screenshots by exact No-Intro name; no account needed"
                ScrapeProviderId.SCREENSCRAPER -> "Needs Fuse's own ScreenScraper developer registration, which is still pending"
                ScrapeProviderId.STEAMGRIDDB -> if (configured) null else "Add your SteamGridDB API key"
                ScrapeProviderId.IGDB -> if (configured) null else "Add your Twitch client ID and secret"
                ScrapeProviderId.THEGAMESDB -> if (configured) null else "Add your TheGamesDB API key"
            }
            ProviderStatus(id, enabled = id !in disabled, configured = configured, note = note)
        }
    }.resilient().stateIn(ctx.scope, SharingStarted.Eagerly, emptyList())

    private var autoFillJob: Job? = null

    /** Drops cached clients after credentials change, tests the keys again and looks for art the new source may have. */
    fun invalidate() {
        cached = null
        // A short wait lets the IGDB id and secret, saved one after the other, be tested together.
        runChecks(delayMs = 800)
        // A new source may have what the others didn't.
        autoFillJob?.cancel()
        autoFillJob = ctx.scope.launch {
            kotlinx.coroutines.delay(DefaultFuseStore.AUTO_FILL_DELAY_MS)
            autoFill()
        }
    }

    override fun checkKeys() = runChecks(delayMs = 0)

    private fun runChecks(delayMs: Long) {
        checkJob?.cancel()
        checkJob = ctx.scope.launch {
            kotlinx.coroutines.delay(delayMs)
            val http = ctx.services.http
            val tests = buildMap<ScrapeProviderId, suspend () -> KeyCheck> {
                credentials.get(SecretKeys.SGDB_API_KEY)?.let { key -> put(ScrapeProviderId.STEAMGRIDDB) { SteamGridDbClient(http, key).verifyKey() } }
                val id = credentials.get(SecretKeys.IGDB_CLIENT_ID)
                val secret = credentials.get(SecretKeys.IGDB_CLIENT_SECRET)
                if (id != null && secret != null) put(ScrapeProviderId.IGDB) { IgdbClient(http, IgdbCredentials(id, secret)).verifyCredentials() }
                credentials.get(SecretKeys.TGDB_API_KEY)?.let { key -> put(ScrapeProviderId.THEGAMESDB) { TheGamesDbClient(http, key).verifyKey() } }
            }
            checks.value = tests.keys.associateWith { null }
            for ((provider, test) in tests) {
                val result = try {
                    test()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    KeyCheck.Failed("The check could not run (${e::class.simpleName})")
                }
                checks.value = checks.value + (provider to result)
            }
        }
    }

    private suspend fun coordinator(): Pair<Set<ScrapeProviderId>, ScrapeCoordinator> {
        cached?.let { return it }
        val http = ctx.services.http
        val sources = ArrayList<ScrapeSource>()
        val configured = LinkedHashSet<ScrapeProviderId>()
        sources += LibretroSource(LibretroThumbnails(http))
        configured += ScrapeProviderId.LIBRETRO
        credentials.get(SecretKeys.SGDB_API_KEY)?.let { key ->
            sources += SteamGridDbSource(SteamGridDbClient(http, key))
            configured += ScrapeProviderId.STEAMGRIDDB
        }
        val igdbId = credentials.get(SecretKeys.IGDB_CLIENT_ID)
        val igdbSecret = credentials.get(SecretKeys.IGDB_CLIENT_SECRET)
        if (igdbId != null && igdbSecret != null) {
            sources += IgdbSource(IgdbClient(http, IgdbCredentials(igdbId, igdbSecret)))
            configured += ScrapeProviderId.IGDB
        }
        credentials.get(SecretKeys.TGDB_API_KEY)?.let { key ->
            sources += TheGamesDbSource(TheGamesDbClient(http, key))
            configured += ScrapeProviderId.THEGAMESDB
        }
        return (configured to ScrapeCoordinator(sources)).also { cached = it }
    }

    private suspend fun query(game: Game): ScrapeQuery {
        val settings = ctx.settings.value.scraping
        val searchAs = searchAs(game)
        return ScrapeQuery(
            title = searchAs ?: defaultSearchTitle(game),
            platform = game.platformId,
            platformName = ctx.platformName(game.platformId),
            // A name the user typed replaces the file name too, so a badly named file can't outrank it.
            fileName = if (searchAs == null) FsPath.name(game.location.path) else null,
            regions = game.tags.regions,
            year = game.metadata.releaseYear,
            sizeBytes = game.location.sizeBytes.takeIf { it > 0 },
            preferredLanguage = settings.preferredLanguage,
            preferredRegion = settings.preferredRegion,
            alsoKnownAs = if (searchAs == null) otherNames(game) else emptyList(),
        )
    }

    /**
     * The game's other names, searched when its search name finds nothing sure: the names it was
     * given, the file name as a search name and its other spellings ([SearchTitles.alternatives]),
     * then the display names.
     */
    private fun otherNames(game: Game): List<String> {
        val title = defaultSearchTitle(game)
        val original = game.titles.original
        return (
            listOfNotNull(game.titles.metadata, game.titles.custom, SearchTitles.clean(original)) +
                SearchTitles.alternatives(original) +
                listOfNotNull(game.titles.cleaned, DisplayNameCleaner.clean(original))
            ).map { it.trim() }.filter { it.isNotEmpty() && !it.equals(title, ignoreCase = true) }.distinct()
    }

    /** The name the user set for searches, if any. */
    private suspend fun searchAs(game: Game): String? =
        ctx.data.scopedSettings.resolve(ScopedSettings.SearchTitle, game.platformId, game.id).value.trim().ifEmpty { null }

    /**
     * Their own name, then a name a provider gave, then the file name made into a search name with
     * today's rules ([SearchTitles]: tags, serials, versions, release groups and list numbers go).
     */
    private fun defaultSearchTitle(game: Game): String =
        game.titles.custom ?: game.titles.metadata
            ?: SearchTitles.clean(game.titles.original).ifBlank { game.titles.cleaned ?: game.titles.original }

    override suspend fun searchTitle(game: GameId): SearchTitle? {
        val g = gameOf(game) ?: return null
        return SearchTitle(current = searchAs(g) ?: defaultSearchTitle(g), custom = searchAs(g) != null, default = defaultSearchTitle(g))
    }

    private suspend fun request(game: Game, kinds: Set<MediaKind>, metadata: Boolean, collectAll: Boolean): Pair<ScrapeRequest, ScrapeCoordinator> {
        val (configured, coordinator) = coordinator()
        val strictness = ctx.data.scopedSettings.resolve(ScopedSettings.Matching, game.platformId, game.id).value
        val request = ScrapeRequest(
            query = query(game),
            priority = ctx.settings.value.scraping.effectiveOrder(),
            configured = configured,
            strictness = strictness,
            artworkKinds = kinds,
            wantMetadata = metadata,
            collectAllArtwork = collectAll,
        )
        return request to coordinator
    }

    // ------------------------------------------------------------------ the library's games, or a server's

    /** [id] as a game: the library's, or one a server has that Fuse shows without having ([RemoteGames]). */
    private suspend fun gameOf(id: GameId): Game? {
        val r = ctx.remoteGames
        return if (r != null && r.owns(id)) r.get(id) else ctx.data.games.get(id)
    }

    private fun remoteOf(id: GameId): RemoteGames? = ctx.remoteGames?.takeIf { it.owns(id) }

    private suspend fun updateLinks(id: GameId, transform: (io.github.matiyaaa.fuse.model.ExternalLinks) -> io.github.matiyaaa.fuse.model.ExternalLinks) {
        remoteOf(id)?.let { it.updateLinks(id, transform); return }
        ctx.data.games.updateLinks(id, transform)
    }

    private suspend fun applyMetadata(id: GameId, metadata: GameMetadata, titleFromMetadata: String?, onlyFillEmpty: Boolean) {
        remoteOf(id)?.let { it.applyMetadata(id, metadata, titleFromMetadata, onlyFillEmpty); it.changed(id); return }
        ctx.data.games.applyMetadata(id, metadata, titleFromMetadata = titleFromMetadata, onlyFillEmpty = onlyFillEmpty)
    }

    private suspend fun replaceMetadata(id: GameId, metadata: GameMetadata?, titleMetadata: String?) {
        remoteOf(id)?.let { it.replaceMetadata(id, metadata, titleMetadata); it.changed(id); return }
        ctx.data.games.replaceMetadata(id, metadata = metadata, titleMetadata = titleMetadata)
    }

    /** A server's game whose art changed is drawn again where it shows. */
    private fun artChanged(owner: MediaOwner) {
        val id = (owner as? MediaOwner.OfGame)?.id ?: return
        remoteOf(id)?.changed(id)
    }

    override fun media(owner: MediaOwner): Flow<MediaSet> = media.observe(owner)

    override suspend fun artworkOptions(owner: MediaOwner, kind: MediaKind): ArtworkResult {
        if (owner is MediaOwner.OfPlatform) {
            val platform = ctx.platform(owner.id) ?: return ArtworkResult.Unavailable("Fuse doesn't know this system.")
            val options = systemArt.options(platform, kind)
            return if (options.isNotEmpty()) {
                ArtworkResult.Options(options)
            } else {
                ArtworkResult.Unavailable(
                    if (kind == MediaKind.LOGO || kind == MediaKind.BOXART) "The system art pack has nothing for ${platform.name}, or it couldn't be reached."
                    else "System art comes as a logo and a cover-style panel. For other slots, choose an image from a file.",
                )
            }
        }
        val gameId = (owner as? MediaOwner.OfGame)?.id
            ?: return ArtworkResult.Unavailable("Online artwork is found for games and systems. Here, choose an image from a file.")
        val game = gameOf(gameId) ?: return ArtworkResult.Unavailable("This game is no longer in your library.")
        val (request, coordinator) = request(game, setOf(kind), metadata = false, collectAll = true)
        // A game identified before is looked up by its ids, so it is never asked about again.
        coordinator.known(request, knownIds(game))?.let { known ->
            remember(game, known)
            val art = known.artwork.filter { it.kind == kind }
            if (art.isNotEmpty()) return ArtworkResult.Options(art)
        }
        // Otherwise art is found by the game's name: when several games match, the best guess's art
        // is offered, with a way to say it's another game.
        return when (val outcome = coordinator.scrape(request, guess = Guess.BEST)) {
            is ScrapeOutcome.Accepted -> outcome.artwork.filter { it.kind == kind }
                .let {
                    if (it.isEmpty()) {
                        ArtworkResult.Unavailable("No ${kind.label()} found for this game.")
                    } else {
                        if (!outcome.guessed) remember(game, outcome)
                        ArtworkResult.Options(it, guess = outcome.candidate.takeIf { outcome.guessed })
                    }
                }
            is ScrapeOutcome.NeedsReview -> ArtworkResult.NeedsMatch(outcome.candidates)
            is ScrapeOutcome.NotFound -> ArtworkResult.Unavailable(
                when {
                    outcome.searched.isEmpty() -> "No artwork source is set up. Add a SteamGridDB key in Settings."
                    (kind == MediaKind.ICON || kind == MediaKind.SQUARE) && ScrapeProviderId.STEAMGRIDDB !in outcome.searched ->
                        "${if (kind == MediaKind.ICON) "Icons come" else "Square box art comes"} from SteamGridDB. Add a SteamGridDB key in Settings, Art and details."
                    else -> "No ${kind.label()} found for this game."
                },
            )
            is ScrapeOutcome.ProviderErrors -> ArtworkResult.Unavailable(outcome.errors.firstOrNull()?.let { "${it.provider.displayName}: ${it.failure.message}" } ?: "The artwork sources couldn't be reached.")
        }
    }

    /** The game's id in each provider it was identified in before. */
    private fun knownIds(game: Game): Map<ScrapeProviderId, String> = buildMap {
        game.links.steamGridDbGameId?.let { put(ScrapeProviderId.STEAMGRIDDB, it.toString()) }
        game.links.igdbId?.let { put(ScrapeProviderId.IGDB, it.toString()) }
    }

    /**
     * Keeps the game's ids in the providers [outcome] got it from, so its art is looked up directly
     * next time. A guess only keeps its SteamGridDB id: that is art, while IGDB also brings details.
     */
    private suspend fun remember(game: Game, outcome: ScrapeOutcome.Accepted) {
        val links = outcome.links + (outcome.candidate.provider to outcome.candidate.providerGameId)
        val sgdb = links[ScrapeProviderId.STEAMGRIDDB]?.toLongOrNull()
        val igdb = links[ScrapeProviderId.IGDB]?.toLongOrNull()?.takeIf { !outcome.guessed }
        if ((sgdb == null || sgdb == game.links.steamGridDbGameId) && (igdb == null || igdb == game.links.igdbId)) return
        updateLinks(game.id) { l -> l.copy(steamGridDbGameId = sgdb ?: l.steamGridDbGameId, igdbId = igdb ?: l.igdbId) }
    }

    override suspend fun apply(owner: MediaOwner, option: ArtworkOption) {
        media.setCustom(owner, option.kind, localPath = null, remoteUrl = option.url, width = option.width, height = option.height)
        artChanged(owner)
    }

    override suspend fun setFromFile(owner: MediaOwner, kind: MediaKind, path: String) {
        media.setCustom(owner, kind, localPath = path)
        artChanged(owner)
    }

    override suspend fun adjust(owner: MediaOwner, kind: MediaKind, focusX: Float, focusY: Float, zoom: Float) {
        media.adjust(owner, kind, focusX.coerceIn(0f, 1f), focusY.coerceIn(0f, 1f), zoom.coerceIn(1f, 4f))
        artChanged(owner)
    }

    /** Removes custom art ([kind] null: every kind) so found or scraped art shows again. */
    override suspend fun reset(owner: MediaOwner, kind: MediaKind?) {
        val kinds = kind?.let(::listOf) ?: MediaKind.entries
        kinds.forEach { media.resetCustom(owner, it) }
        artChanged(owner)
    }

    override fun fill(mode: MediaFillMode, kinds: Set<MediaKind>, platform: PlatformId?, game: GameId?) =
        startFill(FillJob(mode, kinds, platform, game, everything = false))

    override fun fillEverything(platform: PlatformId?) {
        startFill(FillJob(MediaFillMode.FILL_MISSING, FILLABLE, platform, game = null, everything = true))
        // System logos and panels come from the art pack, alongside the games.
        systemArt.start()
    }

    /**
     * Looks for missing art and details by itself, after scans and when a source is added: every
     * kind of art, for games that still lack some. Games a recent fill already looked for are
     * skipped, so it only works when there is something new to try. Never interrupts a fill.
     */
    fun autoFill() {
        if (!ctx.settings.value.scraping.autoFill) return
        if (fillJob?.isActive == true) return
        startFill(FillJob(MediaFillMode.FILL_MISSING, FILLABLE, platform = null, game = null, everything = false, auto = true))
    }

    /** New games, filled first and on their own worker (see [fillNew]). */
    private val fresh = Channel<GameId>(Channel.UNLIMITED)
    private var freshWorker: Job? = null

    /** New games waiting for or being filled; library-wide fills leave them to that worker. */
    private val freshPending = MutableStateFlow<Set<GameId>>(emptySet())

    /**
     * Identifies new games and fills their art and details straight away, newest first in line: a
     * download, a game added by hand or an app made a game shows its art within moments instead of
     * waiting for the next library-wide fill. Runs beside any fill (never cancels or waits on one);
     * art Cartridge brought from RomM is kept, since only missing art is looked for.
     */
    fun fillNew(ids: Collection<GameId>) {
        if (ids.isEmpty() || !ctx.settings.value.scraping.autoFill) return
        val list = ids.distinct()
        freshPending.update { it + list }
        list.forEach { fresh.trySend(it) }
        if (freshWorker?.isActive == true) return
        freshWorker = ctx.scope.launch {
            for (id in fresh) {
                try {
                    fillFresh(id)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    // One game failing never holds up the others.
                } finally {
                    freshPending.update { it - id }
                }
            }
        }
    }

    private suspend fun fillFresh(id: GameId) {
        // A game downloaded from RomM takes what was found for it there; only what is still missing is looked for.
        runCatching { ctx.adoptArt(id) }
        val g = gameOf(id) ?: return
        if (!ctx.data.scopedSettings.resolve(ScopedSettings.ScrapeEnabled, g.platformId, g.id).value) return
        val (configured, coordinator) = coordinator()
        val priority = ctx.settings.value.scraping.effectiveOrder()
        val kinds = FILLABLE intersect coordinator.availableKinds(priority, configured)
        val details = coordinator.providesMetadata(priority, configured)
        if (kinds.isEmpty() && !details) return
        fillOne(g, FillJob(MediaFillMode.FILL_MISSING, kinds, g.platformId, game = g.id, everything = false, auto = true), kinds, details, configured)
    }

    /** A server's games waiting for art and details ([fillRemote]), each in line once. */
    private val remoteQueue = Channel<GameId>(Channel.UNLIMITED)
    private val remoteQueued = MutableStateFlow<Set<GameId>>(emptySet())
    private var remoteWorker: Job? = null

    /**
     * Finds art and details for games a server has and Fuse shows ([RemoteGames]), first in [ids]
     * first, with the library's sources and order: what is found is kept, and what the sources
     * didn't have is remembered as for the library, so a game is looked for once, not every time
     * the list is shown. Runs beside any library fill, one game at a time.
     */
    fun fillRemote(ids: List<GameId>) {
        if (ids.isEmpty() || !ctx.settings.value.scraping.autoFill) return
        val fresh = ids.distinct().filter { it !in remoteQueued.value }
        if (fresh.isEmpty()) return
        remoteQueued.update { it + fresh }
        fresh.forEach { remoteQueue.trySend(it) }
        if (remoteWorker?.isActive == true) return
        remoteWorker = ctx.scope.launch {
            for (id in remoteQueue) {
                try {
                    val g = gameOf(id) ?: continue
                    if (!ctx.data.scopedSettings.resolve(ScopedSettings.ScrapeEnabled, g.platformId, g.id).value) continue
                    val (configured, coordinator) = coordinator()
                    val priority = ctx.settings.value.scraping.effectiveOrder()
                    val kinds = FILLABLE intersect coordinator.availableKinds(priority, configured)
                    val details = coordinator.providesMetadata(priority, configured)
                    if (kinds.isEmpty() && !details) continue
                    fillOne(g, FillJob(MediaFillMode.FILL_MISSING, kinds, platform = null, game = null, everything = false, auto = true), kinds, details, configured)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    // One game failing never holds up the others.
                } finally {
                    remoteQueued.update { it - id }
                }
            }
        }
    }

    override fun cancelFill() {
        val job = fillJob ?: return
        if (!job.isActive) return
        job.cancel()
        progress.value = progress.value?.copy(current = null, finished = true, cancelled = true)
    }

    /**
     * Runs [job] over its games, several at once (one in Low Power), each within the providers' own
     * rate limits. Progress is published after every game, so the screens update as art arrives.
     */
    private fun startFill(job: FillJob) {
        if (job.kinds.isEmpty()) return
        fillJob?.cancel()
        fillJob = ctx.scope.launch {
            val (configured, coordinator) = coordinator()
            val priority = ctx.settings.value.scraping.effectiveOrder()
            // Asking for art no source has is what made fills crawl over games that looked done.
            val kinds = job.kinds intersect coordinator.availableKinds(priority, configured)
            val details = coordinator.providesMetadata(priority, configured)
            val listed: List<GameId> = when {
                job.game != null -> listOf(job.game)
                job.everything -> gamesIn(job.platform)
                // Missing screenshots alone don't make a game worth searching for.
                job.mode == MediaFillMode.FILL_MISSING -> if (kinds.isEmpty()) emptyList() else media.gamesMissing(MediaKind.needed(kinds), job.platform).keys.toList()
                else -> gamesIn(job.platform)
            }
            // A bulk "fill missing" leaves out games it recently looked for and can't do better on,
            // so the count shows real work; the ones waiting for a choice are listed straight away.
            if (!job.auto && listed.size > 1) progress.value = FillProgress(0, listed.size, "Checking what's missing", 0, finished = false)
            val waiting = ArrayList<FillChoice>()
            // New games have their own worker; a fill of the whole library leaves them to it.
            val busy = if (job.game == null) freshPending.value else emptySet()
            val targets = if (job.remembers) {
                listed.filter { id ->
                    if (id in busy) return@filter false
                    val g = gameOf(id) ?: return@filter false
                    when (val tried = coveredBy(g, job, kinds, details, configured)) {
                        null -> true
                        else -> {
                            if (tried.choice) waiting += FillChoice(g.id, g.displayTitle)
                            false
                        }
                    }
                }
            } else {
                listed
            }
            // An automatic fill with nothing new to try stays out of sight.
            if (job.auto && targets.isEmpty()) return@launch
            val lock = Mutex()
            var state = FillProgress(0, targets.size, null, 0, finished = targets.isEmpty(), needsYou = waiting, automatic = job.auto)
            progress.value = state
            if (targets.isEmpty()) return@launch
            val queue = Channel<GameId>(Channel.UNLIMITED)
            targets.forEach { queue.trySend(it) }
            queue.close()
            val workers = if (ctx.settings.value.performance.lowPowerMode) 1 else FILL_WORKERS
            coroutineScope {
                repeat(workers.coerceAtMost(targets.size)) {
                    launch {
                        for (id in queue) {
                            val g = gameOf(id)
                            val enabled = g != null && ctx.data.scopedSettings.resolve(ScopedSettings.ScrapeEnabled, g.platformId, g.id).value
                            if (g != null && enabled) {
                                lock.withLock { state = state.copy(current = g.displayTitle); progress.value = state }
                            }
                            val result = if (g == null || !enabled) {
                                GameFill()
                            } else {
                                try {
                                    fillOne(g, job, kinds, details, configured)
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Throwable) {
                                    GameFill()
                                }
                            }
                            val paused = coordinator.resting().keys.map { it.displayName }
                            lock.withLock {
                                state = state.copy(
                                    done = state.done + 1,
                                    added = state.added + result.added,
                                    details = state.details + if (result.details) 1 else 0,
                                    needsYou = if (result.needsChoice && g != null) state.needsYou + FillChoice(g.id, g.displayTitle) else state.needsYou,
                                    paused = paused,
                                )
                                progress.value = state
                            }
                        }
                    }
                }
            }
            progress.value = state.copy(current = null, finished = true)
        }
    }

    private suspend fun gamesIn(platform: PlatformId?): List<GameId> =
        (if (platform != null) ctx.data.games.observeByPlatform(platform) else ctx.data.games.observeAll()).first().map { it.id }

    /**
     * What a recent bulk fill already looked for [game] and didn't find, when asking again (same
     * search name, no new source, nothing new wanted) could only find the same nothing; else null.
     */
    private suspend fun coveredBy(game: Game, job: FillJob, kinds: Set<MediaKind>, detailSources: Boolean, configured: Set<ScrapeProviderId>): Tried? {
        if (!job.remembers) return null
        if (!ctx.data.scopedSettings.resolve(ScopedSettings.ScrapeEnabled, game.platformId, game.id).value) return Tried.Skip
        val plan = FillPlanner.plan(media.get(MediaOwner.OfGame(game.id)), job.mode, kinds)
        val needed = plan.fetch intersect MediaKind.needed(kinds)
        val wantDetails = detailSources && job.wantsDetails && game.metadata.lacksDetails()
        if (needed.isEmpty() && !wantDetails) return Tried.Skip
        val tried = ctx.data.cache.getOrNull(FILL_TRIED, game.id.value.toString(), ctx.now())?.let(Tried::decode) ?: return null
        return tried.takeIf { it.covers(searchAs(game) ?: defaultSearchTitle(game), configured, needed, wantDetails) }
    }

    /**
     * Scrapes one game and stores what the plan allows. A bulk "fill missing" remembers what the
     * sources didn't have (for [TRIED_DAYS] days, per search name and set of sources) and doesn't
     * ask again; a new key, another search name or filling this one game asks again. Nothing is
     * remembered when a source failed or was resting, since it may have had the rest.
     */
    private suspend fun fillOne(game: Game, job: FillJob, kinds: Set<MediaKind>, detailSources: Boolean, configured: Set<ScrapeProviderId>): GameFill {
        val owner = MediaOwner.OfGame(game.id)
        val plan = FillPlanner.plan(media.get(owner), job.mode, kinds)
        // Screenshots come along when the game is searched anyway, but never cause a search.
        val needed = if (job.mode == MediaFillMode.FILL_MISSING) plan.fetch intersect MediaKind.needed(kinds) else plan.fetch
        val wantDetails = detailSources && job.wantsDetails && game.metadata.lacksDetails()
        if (needed.isEmpty() && !wantDetails) return GameFill()
        val (request, coordinator) = request(game, plan.fetch, metadata = true, collectAll = false)
        val remember = job.remembers
        val key = game.id.value.toString()
        if (job.game != null) ctx.data.cache.remove(FILL_TRIED, key)
        if (remember) {
            val tried = ctx.data.cache.getOrNull(FILL_TRIED, key, ctx.now())?.let(Tried::decode)
            if (tried != null && tried.covers(request.query.title, configured, needed, wantDetails)) return GameFill(needsChoice = tried.choice)
        }
        // A game identified before skips the search; one Fuse can't be sure of still gets art when a
        // match has its very name (art is cosmetic), while its details wait for the user to pick.
        val outcome = coordinator.known(request, knownIds(game))?.takeIf { known -> known.artwork.any { it.kind in needed } || (wantDetails && known.metadata != null) }
            ?: coordinator.scrape(request, guess = Guess.EXACT_TITLE)
        return when (outcome) {
            is ScrapeOutcome.Accepted -> {
                val added = store(game, outcome, job.mode, plan.fetch)
                val found = outcome.artwork.map { it.kind }.toSet()
                val missing = needed - found
                val choice = outcome.guessed && wantDetails
                when {
                    !remember -> Unit
                    outcome.errors.isNotEmpty() -> ctx.data.cache.remove(FILL_TRIED, key)
                    missing.isNotEmpty() || (wantDetails && outcome.metadata == null) ->
                        remember(key, Tried(request.query.title, configured, missing, details = wantDetails && outcome.metadata == null, choice = choice))
                    else -> ctx.data.cache.remove(FILL_TRIED, key)
                }
                GameFill(added, details = outcome.metadata != null && wantDetails, needsChoice = choice)
            }
            is ScrapeOutcome.NeedsReview -> {
                if (remember && outcome.errors.isEmpty()) remember(key, Tried(request.query.title, configured, needed, details = wantDetails, choice = true))
                GameFill(needsChoice = true)
            }
            is ScrapeOutcome.NotFound -> {
                if (remember && outcome.errors.isEmpty()) remember(key, Tried(request.query.title, configured, needed, details = wantDetails, choice = false))
                GameFill()
            }
            // Offline, a quota or every source resting: nothing to remember, the next fill tries again.
            is ScrapeOutcome.ProviderErrors -> GameFill()
        }
    }

    private suspend fun remember(key: String, tried: Tried) =
        ctx.data.cache.put(FILL_TRIED, key, tried.encode(), ctx.now(), TRIED_DAYS * 24L * 60 * 60 * 1000)

    private suspend fun store(game: Game, outcome: ScrapeOutcome.Accepted, mode: MediaFillMode, kinds: Set<MediaKind>): Int {
        val items = outcome.artwork
            .filter { it.kind in kinds }
            .groupBy { it.kind }
            .flatMap { (_, options) -> options.take(if (options.first().kind == MediaKind.SCREENSHOT) 6 else 1) }
            .mapIndexed { index, option ->
                MediaItem(option.kind, option.provider.mediaSource(), remoteUrl = option.url, width = option.width, height = option.height, order = index)
            }
        val added = if (items.isEmpty()) 0 else media.putScraped(MediaOwner.OfGame(game.id), items, mode, kinds)
        if (added > 0) {
            remoteOf(game.id)?.changed(game.id)
            ctx.gameArtFound.update { it + 1 }
        }
        outcome.metadata?.let { meta ->
            applyMetadata(
                game.id,
                meta.copy(source = meta.source ?: outcome.candidate.provider.metadataSource()),
                // A confident match also names the game properly; an existing name is kept when only filling.
                titleFromMetadata = outcome.candidate.title,
                onlyFillEmpty = mode == MediaFillMode.FILL_MISSING,
            )
        }
        remember(game, outcome)
        return added
    }

    override suspend fun identify(game: GameId): IdentifyResult {
        val g = gameOf(game) ?: return IdentifyResult.Unavailable("This game is no longer in your library.")
        val (request, coordinator) = request(g, emptySet(), metadata = true, collectAll = false)
        // Only these search by name; the keyless sources look art up by file name.
        if (request.configured.none { it in namedSearch }) {
            return IdentifyResult.Unavailable("Identify game searches SteamGridDB, IGDB and TheGamesDB. Add a key for one of them in Settings, Art and details.")
        }
        return when (val outcome = coordinator.candidates(request.copy(priority = request.priority.filter { it in namedSearch }, maxCandidates = 12))) {
            is ScrapeOutcome.NeedsReview -> IdentifyResult.Matches(request.query.title, outcome.candidates)
            is ScrapeOutcome.NotFound -> IdentifyResult.Unavailable(
                if (outcome.searched.isEmpty()) "No source is set up. Add a SteamGridDB, IGDB or TheGamesDB key in Settings, Art and details."
                else "Nothing found for \"${request.query.title}\". Try another search name.",
            )
            is ScrapeOutcome.ProviderErrors -> IdentifyResult.Unavailable(outcome.errors.firstOrNull()?.let { "${it.provider.displayName}: ${it.failure.message}" } ?: "The sources couldn't be reached.")
            is ScrapeOutcome.Accepted -> IdentifyResult.Matches(request.query.title, listOf(outcome.candidate))
        }
    }

    override suspend fun resetDetails(game: GameId): Boolean {
        val g = gameOf(game) ?: return false
        replaceMetadata(game, metadata = null, titleMetadata = null)
        val owner = MediaOwner.OfGame(game)
        for (source in SCRAPED_SOURCES) ctx.data.media.removeSource(owner, source)
        if (g.links.rommRomId != null) updateLinks(game) { it.copy(rommRomId = null) }
        // RomM's details come back only if they fit the game (CartridgeDetails), and a fill may try again.
        ctx.data.cache.remove("cartridge.romm", "g${game.value}")
        ctx.data.cache.remove("cartridge.romm.before", "g${game.value}")
        ctx.data.cache.remove(FILL_TRIED, game.value.toString())
        return true
    }

    override suspend fun followRename(game: GameId, previous: String): Boolean {
        val g = gameOf(game) ?: return false
        val name = g.titles.custom ?: return false
        val theirs = g.titles.metadata
        val hasDetails = theirs != null || g.metadata != GameMetadata()
        val fits = { a: String, b: String -> TitleMatcher.titleSimilarity(a, b).score >= RommGuard.MIN_TITLE }
        val stale = when {
            !hasDetails -> false
            // Details carry the name they were found under: they stay while it reads like the new one.
            theirs != null -> !fits(name, theirs)
            // Without one, a new name that isn't the old one says they were another game's.
            else -> !fits(name, previous)
        }
        if (stale) resetDetails(game) else if (hasDetails) return false
        ctx.data.cache.remove(FILL_TRIED, game.value.toString())
        fill(MediaFillMode.FILL_MISSING, MediaKind.Fillable, game = game)
        return true
    }

    override suspend fun acceptCandidate(game: GameId, candidate: ScrapeCandidate): Boolean {
        val g = gameOf(game) ?: return false
        val plan = FillPlanner.plan(media.get(MediaOwner.OfGame(game)), MediaFillMode.REPLACE_ALL, FILLABLE)
        val (request, coordinator) = request(g, plan.fetch, metadata = true, collectAll = false)
        val outcome = coordinator.accept(request, candidate) as? ScrapeOutcome.Accepted ?: return false
        ctx.data.cache.remove(FILL_TRIED, game.value.toString())
        store(g, outcome, MediaFillMode.REPLACE_ALL, plan.fetch)
        applyMetadata(
            game,
            (outcome.metadata ?: GameMetadata()).copy(source = candidate.provider.metadataSource()),
            titleFromMetadata = candidate.title,
            onlyFillEmpty = false,
        )
        return true
    }
}

/** Art that came from a source rather than from the user or the game's folder. */
private val SCRAPED_SOURCES = listOf(
    MediaSource.ROMM, MediaSource.STEAMGRIDDB, MediaSource.IGDB, MediaSource.THEGAMESDB, MediaSource.SCREENSCRAPER, MediaSource.LIBRETRO,
)

private val namedSearch = setOf(ScrapeProviderId.STEAMGRIDDB, ScrapeProviderId.IGDB, ScrapeProviderId.THEGAMESDB)

/** Every art kind a fill can find (videos and borders come from elsewhere). */
internal val FILLABLE = MediaKind.Fillable

/** Games worked on at once. Each provider still keeps to its own rate limit. */
private const val FILL_WORKERS = 3

/** Cache namespace of what a fill looked for and didn't find, per game. */
internal const val FILL_TRIED = "fill.tried"
private const val TRIED_DAYS = 14

private data class FillJob(
    val mode: MediaFillMode,
    val kinds: Set<MediaKind>,
    val platform: PlatformId?,
    val game: GameId?,
    val everything: Boolean,
    /** Started by Fuse itself (after a scan), not by the user. */
    val auto: Boolean = false,
) {
    /** A bulk "fill missing" remembers what it didn't find, so the next one can skip the game. */
    val remembers: Boolean get() = game == null && mode == MediaFillMode.FILL_MISSING

    /** Details are filled for one game, for "Fill everything" and for automatic fills. */
    val wantsDetails: Boolean get() = everything || game != null || auto
}

private data class GameFill(val added: Int = 0, val details: Boolean = false, val needsChoice: Boolean = false)

private fun GameMetadata.lacksDetails(): Boolean = description == null || releaseYear == null || genres.isEmpty()

/** What a fill asked the sources for and didn't get, so the next bulk fill can skip the game. */
private data class Tried(
    val title: String,
    val providers: Set<ScrapeProviderId>,
    val kinds: Set<MediaKind>,
    val details: Boolean,
    val choice: Boolean,
) {
    /** True when asking again (same name, no new source) could only find the same nothing. */
    fun covers(title: String, configured: Set<ScrapeProviderId>, fetch: Set<MediaKind>, details: Boolean): Boolean =
        title == this.title && providers.containsAll(configured) && kinds.containsAll(fetch) && (!details || this.details || choice)

    fun encode(): String = listOf(
        title.replace("\n", " "),
        providers.joinToString(",") { it.name },
        kinds.joinToString(",") { it.name },
        details.toString(),
        choice.toString(),
    ).joinToString("\n")

    companion object {
        /** Stands for a game with nothing to look for (or scraping off for it), which a fill skips. */
        val Skip = Tried("", emptySet(), emptySet(), details = false, choice = false)

        fun decode(text: String): Tried? = runCatching {
            val parts = text.split("\n")
            if (parts.size != 5) return null
            fun <T> set(value: String, parse: (String) -> T): Set<T> = value.split(",").filter { it.isNotEmpty() }.map(parse).toSet()
            Tried(parts[0], set(parts[1], ScrapeProviderId::valueOf), set(parts[2], MediaKind::valueOf), parts[3].toBoolean(), parts[4].toBoolean())
        }.getOrNull()
    }
}

private fun MediaKind.label(): String = when (this) {
    MediaKind.SQUARE -> "box art"
    MediaKind.ICON -> "icons"
    MediaKind.BOXART -> "covers"
    MediaKind.GRID -> "grid art"
    MediaKind.HERO -> "hero art"
    MediaKind.LOGO -> "logos"
    MediaKind.SCREENSHOT -> "screenshots"
    MediaKind.VIDEO -> "videos"
    MediaKind.BORDER -> "borders"
}

private fun ScrapeProviderId.mediaSource(): MediaSource = when (this) {
    ScrapeProviderId.LOCAL -> MediaSource.LOCAL_FOLDER
    ScrapeProviderId.ROMM -> MediaSource.ROMM
    ScrapeProviderId.STEAMGRIDDB -> MediaSource.STEAMGRIDDB
    ScrapeProviderId.IGDB -> MediaSource.IGDB
    ScrapeProviderId.THEGAMESDB -> MediaSource.THEGAMESDB
    ScrapeProviderId.SCREENSCRAPER -> MediaSource.SCREENSCRAPER
    ScrapeProviderId.LIBRETRO -> MediaSource.LIBRETRO
}

private fun ScrapeProviderId.metadataSource(): MetadataSource? = when (this) {
    ScrapeProviderId.LOCAL -> MetadataSource.LOCAL
    ScrapeProviderId.ROMM -> MetadataSource.ROMM
    ScrapeProviderId.STEAMGRIDDB -> MetadataSource.STEAMGRIDDB
    ScrapeProviderId.IGDB -> MetadataSource.IGDB
    ScrapeProviderId.THEGAMESDB -> MetadataSource.THEGAMESDB
    ScrapeProviderId.SCREENSCRAPER -> MetadataSource.SCREENSCRAPER
    ScrapeProviderId.LIBRETRO -> null
}
