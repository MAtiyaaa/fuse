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
    val scope: CoroutineScope,
    initialSettings: AppSettings,
) {
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

    /** The last launch problems, newest last, for the diagnostics report. Kept in memory only. */
    val recentProblems = MutableStateFlow<List<Pair<Long, io.github.matiyaaa.fuse.ui.shell.store.Problem>>>(emptyList())

    fun recordProblem(problem: io.github.matiyaaa.fuse.ui.shell.store.Problem) {
        recentProblems.update { (it + (now() to problem)).takeLast(10) }
    }

    /** True once emulators were looked for: until then, "none installed" means "not known yet". */
    val emulatorsDetected = MutableStateFlow(false)

    /** Library folders that can't be read right now ([Drives]); their games show as unavailable. */
    val offline = MutableStateFlow<List<OfflineRoot>>(emptyList())

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
