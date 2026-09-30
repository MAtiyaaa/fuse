package io.github.matiyaaa.fuse.ui.shell.store.impl

import io.github.matiyaaa.fuse.data.settings.SecretKeys
import io.github.matiyaaa.fuse.integrations.ApiResult
import io.github.matiyaaa.fuse.integrations.KeyCheck
import io.github.matiyaaa.fuse.integrations.igdb.IgdbClient
import io.github.matiyaaa.fuse.integrations.igdb.IgdbCredentials
import io.github.matiyaaa.fuse.integrations.libretro.LibretroThumbnails
import io.github.matiyaaa.fuse.integrations.scrape.FillPlanner
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
import io.github.matiyaaa.fuse.model.ArtworkOption
import io.github.matiyaaa.fuse.model.Game
import io.github.matiyaaa.fuse.model.GameId
import io.github.matiyaaa.fuse.model.MediaFillMode
import io.github.matiyaaa.fuse.model.MediaItem
import io.github.matiyaaa.fuse.model.MediaKind
import io.github.matiyaaa.fuse.model.MediaOwner
import io.github.matiyaaa.fuse.model.MediaSet
import io.github.matiyaaa.fuse.model.MediaSource
import io.github.matiyaaa.fuse.model.MetadataSource
import io.github.matiyaaa.fuse.model.PlatformId
import io.github.matiyaaa.fuse.model.ProviderStatus
import io.github.matiyaaa.fuse.model.ScrapeCandidate
import io.github.matiyaaa.fuse.model.ScrapeProviderId
import io.github.matiyaaa.fuse.model.ScrapeQuery
import io.github.matiyaaa.fuse.model.ScopedSettings
import io.github.matiyaaa.fuse.ui.shell.store.ArtworkResult
import io.github.matiyaaa.fuse.ui.shell.store.FillProgress
import io.github.matiyaaa.fuse.ui.shell.store.MediaOps
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

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

    /** Drops cached clients after credentials change, and tests the keys again shortly after. */
    fun invalidate() {
        cached = null
        // A short wait lets the IGDB id and secret, saved one after the other, be tested together.
        runChecks(delayMs = 800)
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
                } catch (e: Exception) {
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
        return ScrapeQuery(
            // The user's own name first, then a name a provider gave, then the cleaned file name.
            title = game.titles.custom ?: game.titles.metadata ?: game.titles.cleaned ?: game.titles.original,
            platform = game.platformId,
            platformName = ctx.platformName(game.platformId),
            fileName = FsPath.name(game.location.path),
            regions = game.tags.regions,
            year = game.metadata.releaseYear,
            sizeBytes = game.location.sizeBytes.takeIf { it > 0 },
            preferredLanguage = settings.preferredLanguage,
            preferredRegion = settings.preferredRegion,
        )
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
        val game = ctx.data.games.get(gameId) ?: return ArtworkResult.Unavailable("This game is no longer in your library.")
        val (request, coordinator) = request(game, setOf(kind), metadata = false, collectAll = true)
        return when (val outcome = coordinator.scrape(request)) {
            is ScrapeOutcome.Accepted -> outcome.artwork.filter { it.kind == kind }
                .let { if (it.isEmpty()) ArtworkResult.Unavailable("No ${kind.label()} found for this game.") else ArtworkResult.Options(it) }
            is ScrapeOutcome.NeedsReview -> ArtworkResult.NeedsMatch(outcome.candidates)
            is ScrapeOutcome.NotFound -> ArtworkResult.Unavailable(
                when {
                    outcome.searched.isEmpty() -> "No artwork source is set up. Add a SteamGridDB key in Settings."
                    kind == MediaKind.ICON && ScrapeProviderId.STEAMGRIDDB !in outcome.searched ->
                        "Icons come from SteamGridDB. Add a SteamGridDB key in Settings, Media and Scraping."
                    else -> "No ${kind.label()} found for this game."
                },
            )
            is ScrapeOutcome.ProviderErrors -> ArtworkResult.Unavailable(outcome.errors.firstOrNull()?.let { "${it.provider.displayName}: ${it.failure.message}" } ?: "The artwork sources couldn't be reached.")
        }
    }

    override suspend fun apply(owner: MediaOwner, option: ArtworkOption) {
        media.setCustom(owner, option.kind, localPath = null, remoteUrl = option.url, width = option.width, height = option.height)
    }

    override suspend fun setFromFile(owner: MediaOwner, kind: MediaKind, path: String) {
        media.setCustom(owner, kind, localPath = path)
    }

    override suspend fun adjust(owner: MediaOwner, kind: MediaKind, focusX: Float, focusY: Float, zoom: Float) {
        media.adjust(owner, kind, focusX.coerceIn(0f, 1f), focusY.coerceIn(0f, 1f), zoom.coerceIn(1f, 4f))
    }

    /** Removes custom art ([kind] null: every kind) so found or scraped art shows again. */
    override suspend fun reset(owner: MediaOwner, kind: MediaKind?) {
        val kinds = kind?.let(::listOf) ?: MediaKind.entries
        kinds.forEach { media.resetCustom(owner, it) }
    }

    override fun fill(mode: MediaFillMode, kinds: Set<MediaKind>, platform: PlatformId?, game: GameId?) {
        if (kinds.isEmpty()) return
        fillJob?.cancel()
        fillJob = ctx.scope.launch {
            val targets: List<GameId> = when {
                game != null -> listOf(game)
                mode == MediaFillMode.FILL_MISSING -> media.gamesMissing(kinds, platform).keys.toList()
                platform != null -> ctx.data.games.observeByPlatform(platform).first().map { it.id }
                else -> ctx.data.games.observeAll().first().map { it.id }
            }
            var done = 0
            var added = 0
            progress.value = FillProgress(0, targets.size, null, 0, finished = targets.isEmpty())
            for (id in targets) {
                val g = ctx.data.games.get(id)
                if (g != null && ctx.data.scopedSettings.resolve(ScopedSettings.ScrapeEnabled, g.platformId, g.id).value) {
                    progress.value = FillProgress(done, targets.size, g.displayTitle, added, finished = false)
                    added += try {
                        fillOne(g, mode, kinds)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        0
                    }
                }
                done++
            }
            progress.value = FillProgress(done, targets.size, null, added, finished = true)
        }
    }

    /** Scrapes one game and stores what the plan allows. Returns the number of items added. */
    private suspend fun fillOne(game: Game, mode: MediaFillMode, kinds: Set<MediaKind>): Int {
        val owner = MediaOwner.OfGame(game.id)
        val plan = FillPlanner.plan(media.get(owner), mode, kinds)
        if (plan.isEmpty) return 0
        val (request, coordinator) = request(game, plan.fetch, metadata = true, collectAll = false)
        val outcome = coordinator.scrape(request) as? ScrapeOutcome.Accepted ?: return 0
        return store(game, outcome, mode, plan.fetch)
    }

    private suspend fun store(game: Game, outcome: ScrapeOutcome.Accepted, mode: MediaFillMode, kinds: Set<MediaKind>): Int {
        val items = outcome.artwork
            .filter { it.kind in kinds }
            .groupBy { it.kind }
            .flatMap { (_, options) -> options.take(if (options.first().kind == MediaKind.SCREENSHOT) 6 else 1) }
            .mapIndexed { index, option ->
                MediaItem(option.kind, option.provider.mediaSource(), remoteUrl = option.url, width = option.width, height = option.height, order = index)
            }
        val added = if (items.isEmpty()) 0 else media.putScraped(MediaOwner.OfGame(game.id), items, mode, kinds)
        outcome.metadata?.let { meta ->
            ctx.data.games.applyMetadata(
                game.id,
                meta.copy(source = meta.source ?: outcome.candidate.provider.metadataSource()),
                // A confident match also names the game properly; an existing name is kept when only filling.
                titleFromMetadata = outcome.candidate.title,
                onlyFillEmpty = mode == MediaFillMode.FILL_MISSING,
            )
        }
        val candidate = outcome.candidate
        candidate.providerGameId.toLongOrNull()?.let { providerId ->
            ctx.data.games.updateLinks(game.id) { links ->
                when (candidate.provider) {
                    ScrapeProviderId.STEAMGRIDDB -> links.copy(steamGridDbGameId = providerId)
                    ScrapeProviderId.IGDB -> links.copy(igdbId = providerId)
                    else -> links
                }
            }
        }
        return added
    }

    override suspend fun candidates(game: GameId): List<ScrapeCandidate> {
        val g = ctx.data.games.get(game) ?: return emptyList()
        val (request, coordinator) = request(g, emptySet(), metadata = true, collectAll = false)
        return when (val outcome = coordinator.scrape(request.copy(strictness = io.github.matiyaaa.fuse.model.MatchStrictness.EXACT))) {
            is ScrapeOutcome.Accepted -> listOf(outcome.candidate)
            is ScrapeOutcome.NeedsReview -> outcome.candidates
            else -> emptyList()
        }
    }

    override suspend fun acceptCandidate(game: GameId, candidate: ScrapeCandidate) {
        val g = ctx.data.games.get(game) ?: return
        val kinds = setOf(MediaKind.BOXART, MediaKind.GRID, MediaKind.HERO, MediaKind.LOGO, MediaKind.ICON, MediaKind.SCREENSHOT)
        val plan = FillPlanner.plan(media.get(MediaOwner.OfGame(game)), MediaFillMode.REPLACE_ALL, kinds)
        val (request, coordinator) = request(g, plan.fetch, metadata = true, collectAll = false)
        val outcome = coordinator.accept(request, candidate) as? ScrapeOutcome.Accepted ?: return
        store(g, outcome, MediaFillMode.REPLACE_ALL, plan.fetch)
        ctx.data.games.applyMetadata(
            game,
            (outcome.metadata ?: io.github.matiyaaa.fuse.model.GameMetadata()).copy(source = candidate.provider.metadataSource()),
            titleFromMetadata = candidate.title,
            onlyFillEmpty = false,
        )
    }
}

private fun MediaKind.label(): String = when (this) {
    MediaKind.ICON -> "icons"
    MediaKind.BOXART -> "box art"
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
