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
import io.github.matiyaaa.fuse.ui.shell.store.Art
import io.github.matiyaaa.fuse.ui.shell.store.FuseServices
import io.github.matiyaaa.fuse.ui.shell.store.GameCard
import kotlin.time.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen
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
    val installed = MutableStateFlow<List<InstalledEmulator>>(emptyList())

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

    fun summaryToCard(summary: GameSummary, media: MediaSet?): GameCard {
        val platform = platform(summary.platformId)
        return GameCard(
            id = summary.id,
            platformId = summary.platformId,
            title = summary.displayTitle,
            platformShort = platform?.shortName ?: summary.platformId.value.uppercase(),
            accent = platform?.accent ?: DEFAULT_ACCENT,
            art = media?.let(Art::from) ?: Art.None,
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
        )
    }

    /** Turns game summaries into cards with their artwork, following both games and media changes. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun cards(summaries: Flow<List<GameSummary>>): Flow<List<GameCard>> = summaries
        .distinctUntilChanged()
        .flatMapLatest { list ->
            if (list.isEmpty()) {
                flowOf(emptyList())
            } else {
                data.media.observeFor(list.map { MediaOwner.OfGame(it.id) }).map { media ->
                    list.map { summaryToCard(it, media[MediaOwner.OfGame(it.id)]) }
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
