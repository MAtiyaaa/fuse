package io.github.matiyaaa.fuse.ui.shell.store.impl

import io.github.matiyaaa.fuse.data.repo.GameSummary
import io.github.matiyaaa.fuse.data.settings.AppSettings
import io.github.matiyaaa.fuse.launch.AdapterRegistry
import io.github.matiyaaa.fuse.launch.LaunchResolver
import io.github.matiyaaa.fuse.launch.PlaylistGenerator
import io.github.matiyaaa.fuse.library.PlatformCatalog
import io.github.matiyaaa.fuse.library.PlatformLookup
import io.github.matiyaaa.fuse.model.GameId
import io.github.matiyaaa.fuse.model.InstalledEmulator
import io.github.matiyaaa.fuse.model.MediaOwner
import io.github.matiyaaa.fuse.model.MediaSet
import io.github.matiyaaa.fuse.model.Platform
import io.github.matiyaaa.fuse.model.PlatformId
import io.github.matiyaaa.fuse.ui.shell.store.AppIconModel
import io.github.matiyaaa.fuse.ui.shell.store.Art
import io.github.matiyaaa.fuse.ui.shell.store.FuseServices
import io.github.matiyaaa.fuse.ui.shell.store.GameCard
import io.github.matiyaaa.fuse.ui.shell.store.Unavailable
import kotlin.time.Clock
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/** State and helpers shared by every part of the default store. */
internal class StoreContext(
    val services: FuseServices,
    scope: CoroutineScope,
    initialSettings: AppSettings,
) {
    /**
     * Where the store's background work runs: the given scope, with one difference. A background
     * job that fails (the database closed under it as the app quits, a network that went away) is
     * let go quietly instead of reaching the platform's crash handler, unless the app gave a
     * handler of its own. Each caller already reports the failures a person needs to see.
     */
    val scope: CoroutineScope = CoroutineScope(
        // Fuseline 4.2: the store's work runs on worker threads wherever it is started from. A
        // computer's app hands over its window's scope, so every collector (series, picks, the
        // library's cards) used to run on the thread that draws, and a large library stalled it
        // for seconds; Android always gave a worker scope, and the same code runs there.
        scope.coroutineContext + Dispatchers.Default +
            (scope.coroutineContext[CoroutineExceptionHandler] ?: CoroutineExceptionHandler { _, _ -> }),
    )

    val data = services.data
    val host = services.host
    val platforms: PlatformLookup = PlatformCatalog
    val registry: AdapterRegistry = AdapterRegistry.Default

    /** Generated disc playlists go to Fuse's cache, one folder per game, never next to the ROMs. */
    val resolver = LaunchResolver(
        registry,
        PlaylistGenerator { request ->
            val name = request.fileName.replace('/', '_').replace('\\', '_')
            services.writeCacheFile("playlists/${request.gameId.value}/$name", request.content)
        },
    )

    /** Latest saved settings; written only through [DefaultFuseStore]. */
    val settings = MutableStateFlow(initialSettings)

    /**
     * The user's order of systems as the preferences hold it, ahead of the database: a system moved
     * by hand shows in its new place on the next frame, never after the write. Set by [DefaultFuseStore].
     */
    val systemOrder = MutableStateFlow(initialSettings.library.systemOrder)
    val installed = MutableStateFlow<List<InstalledEmulator>>(emptyList())

    /** The game being played on this device right now, if any (transfers ease off or pause meanwhile). */
    val playing = MutableStateFlow<GameId?>(null)

    /** Strong household identity evidence, supplied by Reach before an external scraper search. */
    var householdProviderClaims: suspend (io.github.matiyaaa.fuse.model.Game) -> List<io.github.matiyaaa.fuse.model.ProviderClaim> = { emptyList() }

    /** Art and details another device of the household found for a game, supplied by Reach before any provider is asked. */
    var householdFill: suspend (io.github.matiyaaa.fuse.model.Game) -> HouseholdFound? = { null }

    /** The last launch problems, newest last, for the diagnostics report. Kept in memory only. */
    val recentProblems = MutableStateFlow<List<Pair<Long, io.github.matiyaaa.fuse.ui.shell.store.Problem>>>(emptyList())

    /** Fuse Engine's Performance Governor: what Fuse is doing now, and what optional work that allows. */
    val governor = PerformanceGovernor(scope, playing, ::now) {
        io.github.matiyaaa.fuse.ui.fuseline.FramePacing.lastInputNanos.takeIf { it != Long.MIN_VALUE }
            ?.let { io.github.matiyaaa.fuse.ui.fuseline.monotonicNanos() - it }
    }

    fun recordProblem(problem: io.github.matiyaaa.fuse.ui.shell.store.Problem) {
        recentProblems.update { (it + (now() to problem)).takeLast(10) }
    }

    /**
     * The last games that started, newest last, for the diagnostics report beside the problems: a
     * report then shows what works as well as what didn't (the emulator, how it was handed the
     * game, how long that took). Kept in memory only.
     */
    val recentLaunches = MutableStateFlow<List<LaunchReport>>(emptyList())

    fun recordLaunch(report: LaunchReport) {
        recentLaunches.update { (it + report).takeLast(10) }
    }

    /**
     * The person changed something of theirs (a favourite, a name, a collection, a setting): Fuse
     * Sync sends it soon. Set by [DefaultFuseStore]; nothing while Fuse Sync is off.
     */
    @kotlin.concurrent.Volatile var onUserChange: () -> Unit = {}

    fun userChanged() = onUserChange()

    /** Called whenever Fuse comes back to the front (after a game, or another app). */
    val resumeHooks = MutableStateFlow<List<() -> Unit>>(emptyList())

    /** True once emulators were looked for: until then, "none installed" means "not known yet". */
    val emulatorsDetected = MutableStateFlow(false)

    /** Library folders that can't be read right now ([Drives]); their games show as unavailable. */
    val offline = MutableStateFlow<List<OfflineRoot>>(emptyList())

    /** Systems Fuse shows without having games for them (a RomM server's), so they get system art too. */
    val shownPlatforms = MutableStateFlow<Set<PlatformId>>(emptySet())

    /**
     * Games with a port attached that show in another system too (Ports or Android, [io.github.matiyaaa.fuse.model.ScopedSettings.PortShowsIn]),
     * by game: that system. Set by the Android ports.
     */
    val portPlaces = MutableStateFlow<Map<GameId, PlatformId>>(emptyMap())

    /** Games Fuse shows but doesn't have (RomM's and other devices', by negative id), set by the stores that keep them. */
    @kotlin.concurrent.Volatile var remoteGames: RemoteGames? = null

    /** Adds [games] to [remoteGames] beside any already there (RomM's, the household's). */
    fun addRemoteGames(games: RemoteGames) {
        val now = remoteGames
        remoteGames = if (now == null) games else RemoteGamesHub(listOf(now, games))
    }

    /** Every game Fuse shows but doesn't have, on [platform] or anywhere: for filling art and details. */
    @kotlin.concurrent.Volatile var remoteIds: suspend (io.github.matiyaaa.fuse.model.PlatformId?) -> List<io.github.matiyaaa.fuse.model.GameId> = { emptyList() }

    /** The games Fuse shows but doesn't have on [PlatformId] (a RomM server's), set by the store that keeps them. */
    @kotlin.concurrent.Volatile var remoteGamesOn: suspend (PlatformId) -> List<GameId> = { emptyList() }

    /** A game Fuse shows but doesn't have, as its page would show it; null for the library's own games. */
    @kotlin.concurrent.Volatile var remoteDetail: (GameId) -> kotlinx.coroutines.flow.Flow<io.github.matiyaaa.fuse.ui.shell.store.GameDetail?>? = { null }

    /**
     * Gives a game just added to the library the art already found for it elsewhere (the RomM game
     * it was downloaded from), so it isn't looked for again. True when it took some. Set by RomM.
     */
    @kotlin.concurrent.Volatile var adoptArt: suspend (io.github.matiyaaa.fuse.model.GameId) -> Boolean = { false }

    /** Goes up whenever art was found for a game, so what is drawn from games' art can follow. */
    val gameArtFound = MutableStateFlow(0)

    fun now(): Long = Clock.System.now().toEpochMilliseconds()

    fun platform(id: PlatformId): Platform? = platforms.byId(id)

    fun platformName(id: PlatformId): String = platform(id)?.name ?: id.value

    /** Midnight at the start of the current week (Monday), local time, as epoch millis. */
    fun weekStart(): Long {
        val offset = services.utcOffsetMillis()
        val local = now() + offset
        val day = local.floorDiv(DAY_MS)
        // 1970-01-01 was a Thursday: day 0 -> index 3 with Monday = 0.
        val weekday = ((day + 3) % 7 + 7) % 7
        return (day - weekday) * DAY_MS - offset
    }

    fun summaryToCard(summary: GameSummary, media: MediaSet?, offlineRoots: List<OfflineRoot> = offline.value): GameCard {
        val platform = platform(summary.platformId)
        val away = if (offlineRoots.isEmpty() || summary.isApp) null else offlineRoots.firstOrNull { it.holds(summary.folderPath) }
        return GameCard(
            id = summary.id,
            platformId = summary.platformId,
            title = summary.displayTitle,
            platformShort = platform?.shortName ?: summary.platformId.value.uppercase(),
            accent = platform?.accent ?: DEFAULT_ACCENT,
            art = media?.let(Art::from)?.let(::withAppIcon) ?: Art.None,
            favorite = summary.favorite,
            lastPlayedAt = summary.lastPlayedAt,
            playSeconds = summary.totalSeconds,
            addedAt = summary.addedAt,
            year = summary.releaseYear,
            updates = summary.updateCount,
            dlc = summary.dlcCount,
            discs = summary.discCount,
            missing = summary.missing,
            isApp = summary.isApp,
            unavailable = away?.let { Unavailable(it.driveLabel, it.state) },
            customTitle = summary.titles.custom != null,
            pinned = summary.pinned,
        )
    }

    /** An Android game's own icon (stored as "app-icon:<package>") as the app list's image model. */
    private fun withAppIcon(art: Art): Art {
        val ref = (art.icon as? String)?.let(AppIconModel::parse) ?: return art
        val apps = services.apps
        val model = apps?.apps?.value?.firstOrNull { it.packageName == ref.packageName }?.let(apps::iconModel) ?: ref
        return art.copy(icon = model)
    }

    /** Turns game summaries into cards with their artwork, following both games and media changes. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun cards(summaries: Flow<List<GameSummary>>): Flow<List<GameCard>> = summaries
        .distinctUntilChanged()
        .flatMapLatest { list ->
            if (list.isEmpty()) {
                flowOf(emptyList())
            } else {
                combine(data.media.observeFor(list.map { MediaOwner.OfGame(it.id) }), offline) { media, roots ->
                    list.map { summaryToCard(it, media[MediaOwner.OfGame(it.id)], roots) }
                }
            }
        }
        .distinctUntilChanged()
        .flowOn(Dispatchers.Default)

    suspend fun cardsOnce(summaries: List<GameSummary>): List<GameCard> {
        if (summaries.isEmpty()) return emptyList()
        val media = data.media.mediaFor(summaries.map { MediaOwner.OfGame(it.id) })
        return summaries.map { summaryToCard(it, media[MediaOwner.OfGame(it.id)]) }
    }

    suspend fun card(id: GameId): GameCard? = data.games.summary(id)?.let { cardsOnce(listOf(it)).firstOrNull() }

    companion object {
        const val DAY_MS = 86_400_000L
        const val DEFAULT_ACCENT = 0xFF8A93A6
    }
}

/**
 * Keeps a long-lived flow alive through a failed database read: it restarts with a short backoff
 * instead of leaving the interface frozen on its last value.
 */
internal fun <T> Flow<T>.resilient(): Flow<T> = retryWhen { cause, attempt ->
    if (cause is CancellationException) {
        false
    } else {
        delay(minOf(2_000L, 200L * (attempt + 1)))
        true
    }
}

/**
 * Games Fuse shows without having them (a RomM server's), found, filled and edited like any game:
 * Fuse's own name, details and provider links for them are kept here, and their art in the media
 * table under their (negative) id, so nothing is asked for twice.
 */
internal interface RemoteGames {
    /** Whether [id] is one of these. */
    fun owns(id: io.github.matiyaaa.fuse.model.GameId): Boolean

    /** The game as Fuse knows it: the server's details under Fuse's own; null when gone. */
    suspend fun get(id: io.github.matiyaaa.fuse.model.GameId): io.github.matiyaaa.fuse.model.Game?

    /** As [io.github.matiyaaa.fuse.data.repo.GameRepository.applyMetadata]. */
    suspend fun applyMetadata(id: io.github.matiyaaa.fuse.model.GameId, metadata: io.github.matiyaaa.fuse.model.GameMetadata, titleFromMetadata: String?, onlyFillEmpty: Boolean)

    /** As [io.github.matiyaaa.fuse.data.repo.GameRepository.replaceMetadata]. */
    suspend fun replaceMetadata(id: io.github.matiyaaa.fuse.model.GameId, metadata: io.github.matiyaaa.fuse.model.GameMetadata?, titleMetadata: String?)

    suspend fun updateLinks(id: io.github.matiyaaa.fuse.model.GameId, transform: (io.github.matiyaaa.fuse.model.ExternalLinks) -> io.github.matiyaaa.fuse.model.ExternalLinks)

    /** The user's own name for it; null puts the found one back. */
    suspend fun rename(id: io.github.matiyaaa.fuse.model.GameId, title: String?)

    /** Its art or details changed (a fill, a pick): the lists showing it draw it again. */
    fun changed(id: io.github.matiyaaa.fuse.model.GameId)
}

/** Several kinds of [RemoteGames] as one: each id goes to the one that owns it. */
internal class RemoteGamesHub(private val parts: List<RemoteGames>) : RemoteGames {
    private fun of(id: io.github.matiyaaa.fuse.model.GameId): RemoteGames? = parts.firstOrNull { it.owns(id) }
    override fun owns(id: io.github.matiyaaa.fuse.model.GameId): Boolean = of(id) != null
    override suspend fun get(id: io.github.matiyaaa.fuse.model.GameId): io.github.matiyaaa.fuse.model.Game? = of(id)?.get(id)
    override suspend fun applyMetadata(id: io.github.matiyaaa.fuse.model.GameId, metadata: io.github.matiyaaa.fuse.model.GameMetadata, titleFromMetadata: String?, onlyFillEmpty: Boolean) {
        of(id)?.applyMetadata(id, metadata, titleFromMetadata, onlyFillEmpty)
    }
    override suspend fun replaceMetadata(id: io.github.matiyaaa.fuse.model.GameId, metadata: io.github.matiyaaa.fuse.model.GameMetadata?, titleMetadata: String?) {
        of(id)?.replaceMetadata(id, metadata, titleMetadata)
    }
    override suspend fun updateLinks(id: io.github.matiyaaa.fuse.model.GameId, transform: (io.github.matiyaaa.fuse.model.ExternalLinks) -> io.github.matiyaaa.fuse.model.ExternalLinks) {
        of(id)?.updateLinks(id, transform)
    }
    override suspend fun rename(id: io.github.matiyaaa.fuse.model.GameId, title: String?) {
        of(id)?.rename(id, title)
    }
    override fun changed(id: io.github.matiyaaa.fuse.model.GameId) {
        of(id)?.changed(id)
    }
}

/** What another device of the household found for a game: its art as the providers gave it, and its details when whole. */
internal data class HouseholdFound(
    val art: List<io.github.matiyaaa.fuse.model.ArtworkOption>,
    val metadata: io.github.matiyaaa.fuse.model.GameMetadata?,
    val title: String,
)

/**
 * One game that started ([StoreContext.recordLaunch]): when, what, in which emulator (and core),
 * how Fuse handed it over ([how], as Android's intent or the program it ran), the kind of file
 * it was given, how long from the press to the emulator taking it ([tookMs]), and what Fuse Sync
 * or RomM said about the save, if anything.
 */
internal data class LaunchReport(
    val at: Long,
    val title: String,
    val system: String,
    val emulator: String,
    val core: String?,
    val how: String,
    val target: String,
    val tookMs: Long,
    val saveNote: String? = null,
) {
    fun line(whenText: String): String = buildString {
        append(whenText).append(": ").append(title).append(" (").append(system).append(") in ").append(emulator)
        core?.let { append(", core ").append(it) }
        append(", ").append(how).append(", ").append(target).append(", started in ").append(tookMs).append(" ms")
        saveNote?.let { append("\n    ").append(it) }
    }

    companion object {
        /** How [plan] hands the game over, without the person's paths: the app and activity, or the program's name. */
        fun how(plan: io.github.matiyaaa.fuse.model.LaunchPlan): String = when (plan) {
            is io.github.matiyaaa.fuse.model.LaunchPlan.AndroidIntent -> "intent to " + plan.packageName + (plan.activity?.let { "/" + it.substringAfterLast('.') } ?: "") + (plan.action?.let { ", " + it.substringAfterLast('.') } ?: "")
            is io.github.matiyaaa.fuse.model.LaunchPlan.Command -> "ran " + (plan.argv.firstOrNull()?.let(io.github.matiyaaa.fuse.library.FsPath::name) ?: "a program") + " with ${plan.argv.size - 1} arguments"
            is io.github.matiyaaa.fuse.model.LaunchPlan.OpenAppOnly -> "opened the app"
            is io.github.matiyaaa.fuse.model.LaunchPlan.Unsupported -> "not started"
        }

        /** The kind of thing the emulator was given, by name only. */
        fun target(target: io.github.matiyaaa.fuse.model.LaunchTarget?): String = when (target) {
            null -> "no file"
            is io.github.matiyaaa.fuse.model.LaunchTarget.Playlist -> if (target.generated) "a playlist Fuse made" else "a playlist"
            is io.github.matiyaaa.fuse.model.LaunchTarget.TitleId -> "title ${target.id}"
            is io.github.matiyaaa.fuse.model.LaunchTarget.Directory -> "a folder"
            is io.github.matiyaaa.fuse.model.LaunchTarget.Shortcut -> "a shortcut"
            is io.github.matiyaaa.fuse.model.LaunchTarget.App -> "an app"
            is io.github.matiyaaa.fuse.model.LaunchTarget.File -> "a ." + io.github.matiyaaa.fuse.library.FsPath.name(target.path).substringAfterLast('.', "file").lowercase() + " file"
        }
    }
}
