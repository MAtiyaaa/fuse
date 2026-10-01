package io.github.matiyaaa.fuse.ui.shell.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.compose.LocalPlatformContext
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.size.Precision
import coil3.size.Scale
import io.github.matiyaaa.fuse.model.LibraryLayout
import io.github.matiyaaa.fuse.ui.designsystem.media.heroDecodePx
import io.github.matiyaaa.fuse.ui.designsystem.media.heroRequest
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.shell.components.LocalTileMetrics
import io.github.matiyaaa.fuse.ui.shell.store.GameCard
import io.github.matiyaaa.fuse.ui.shell.store.GameQuery
import io.github.matiyaaa.fuse.ui.shell.store.HomeFeed
import io.github.matiyaaa.fuse.ui.shell.store.PlatformCard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore

/** The sizes art is drawn at, in pixels (each a square the image fits in). */
private data class WarmSizes(val icon: Int, val cover: Int, val capsule: Int, val logo: Int, val panel: Int, val hero: Int, val small: Int)

/**
 * One image to get ready: decoded into memory at [px], or only fetched to disk ([memory] false). A
 * [hero] is decoded exactly as the background decodes it, so it is found in memory there.
 */
private data class Warm(val model: Any, val px: Int, val memory: Boolean, val hero: Boolean = false) {
    /** What the decoded bitmap may take (an upper bound: art is rarely square, backgrounds are wide). */
    val bytes: Long get() = if (hero) px.toLong() * px * 9 / 16 * 4 else px.toLong() * px * 4
}

/**
 * Gets art ready before anything asks for it, so opening a system or moving onto a game shows its
 * art at once instead of loading it. A moment after start, and again whenever the library or its art
 * changes, it decodes into memory at the size each is drawn: every system's logo and art, Home's
 * games, then each system's games in the order systems are shown (their tile art for the system's
 * layout, and the first logos). Backgrounds and anything past the memory budget are fetched to disk,
 * so nothing waits on the network later. A few at a time, nearest first; less in Low Power.
 */
@OptIn(FlowPreview::class)
@Composable
internal fun ArtWarmup(app: AppState) {
    val context = LocalPlatformContext.current
    val density = LocalDensity.current
    val metrics = LocalTileMetrics.current
    val prefs by app.store.prefs.collectAsState()
    val lowPower = prefs.lowPower
    val heroPx = Fuse.quality.heroDecodePx
    val sizes = with(density) {
        WarmSizes(
            icon = (metrics.icon * 1.4f).roundToPx(),
            cover = (metrics.coverWidth * 1.45f / 0.72f).roundToPx(),
            capsule = (metrics.capsuleWidth * 1.3f).roundToPx(),
            logo = 360.dp.roundToPx(),
            panel = 480.dp.roundToPx(),
            hero = heroPx,
            small = 160.dp.roundToPx(),
        )
    }
    LaunchedEffect(sizes, lowPower) {
        delay(START_DELAY_MS)
        val loader = SingletonImageLoader.get(context)
        val budget = ((loader.memoryCache?.maxSize ?: 0L) * if (lowPower) LOW_POWER_SHARE else MEMORY_SHARE).toLong()
        var used = 0L
        val inMemory = HashSet<Any>()
        val onDisk = HashSet<Any>()
        val gate = Semaphore(if (lowPower) 1 else PARALLEL)
        // A fill's progress means new art: plan again once it pauses.
        combine(app.store.library.platforms, app.store.library.home, app.store.media.fillProgress) { systems, home, _ -> systems to home }
            .debounce(REPLAN_DELAY_MS)
            .collectLatest { (systems, home) ->
                val plan = plan(app, systems, home, sizes, lowPower)
                coroutineScope {
                    for (w in plan) {
                        val memory = w.memory && w.model !in inMemory && used + w.bytes <= budget
                        when {
                            memory -> {
                                inMemory += w.model
                                used += w.bytes
                            }
                            // Only art from the network gains from being fetched ahead; files are on the device already.
                            w.model in inMemory || !isRemote(w.model) || !onDisk.add(w.model) -> continue
                        }
                        gate.acquire()
                        launch {
                            try {
                                loader.execute(if (memory && w.hero) heroRequest(context, w.model, w.px) else request(context, w, memory, sizes.small))
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Throwable) {
                                // A broken image just isn't ready early; the screen shows its fallback.
                            } finally {
                                gate.release()
                            }
                        }
                    }
                }
            }
    }
}

/**
 * Everything to get ready, most needed first: systems' logos and art, Home's games (tiles, logos,
 * then the backgrounds their rooms show), then each system's games in the order systems are shown
 * (tiles and first logos, then the rooms of the first games). The rest goes to disk.
 */
private suspend fun plan(app: AppState, systems: List<PlatformCard>, home: HomeFeed, px: WarmSizes, lowPower: Boolean): List<Warm> = buildList {
    val bySystem = systems.associateBy { it.platform.id }
    // Systems: what Systems and each system's page show first. A system's background is also every
    // one of its games' placeholder, so it goes into memory.
    for (s in systems) {
        s.art.logo?.let { add(Warm(it, px.logo, memory = true)) }
        s.art.boxart?.let { add(Warm(it, px.panel, memory = true)) }
    }
    for (s in systems) s.art.hero?.let { add(Warm(it, px.hero, memory = !lowPower, hero = true)) }
    // Home: its games' tiles and logos, then the backgrounds of their rooms.
    val homeGames = (home.continuePlaying + home.pinnedGames + home.recentlyPlayed + home.favorites + home.recentlyAdded + home.mostPlayed).distinctBy { it.id }
    for (g in homeGames) {
        tileArt(g, LibraryLayout.ICON)?.let { add(Warm(it, px.icon, memory = true)) }
        g.art.logo?.let { add(Warm(it, px.logo, memory = true)) }
    }
    homeGames.take(if (lowPower) 2 else HOME_ROOMS).forEach { g -> roomArt(g.art, bySystem[g.platformId])?.let { add(Warm(it, px.hero, memory = true, hero = true)) } }
    // Each system's games, in the order systems are shown.
    val rooms = ArrayList<Warm>()
    val later = ArrayList<Warm>()
    for (s in systems) {
        val games = app.store.library.games(GameQuery(platform = s.platform.id)).first()
        val size = when (s.layout) {
            LibraryLayout.COVER_GRID -> px.cover
            LibraryLayout.CAPSULE -> px.capsule
            else -> px.icon
        }
        games.forEach { g -> tileArt(g, s.layout)?.let { add(Warm(it, size, memory = true)) } }
        games.forEachIndexed { i, g ->
            g.art.logo?.let { if (i < LOGOS_PER_SYSTEM) add(Warm(it, px.logo, memory = true)) else later += Warm(it, px.logo, memory = false) }
            // The first games' rooms in memory, the rest on disk.
            roomArt(g.art, s)?.let { if (i < ROOMS_PER_SYSTEM && !lowPower) rooms += Warm(it, px.hero, memory = true, hero = true) else later += Warm(it, px.hero, memory = false) }
            // The other kinds a game page or another layout shows.
            listOfNotNull(g.art.boxart, g.art.grid, g.art.square).forEach { later += Warm(it, size, memory = false) }
        }
    }
    addAll(rooms)
    addAll(later)
}

/** The art a tile shows in [layout] (as the library draws it). */
private fun tileArt(g: GameCard, layout: LibraryLayout): Any? = when (layout) {
    LibraryLayout.COVER_GRID -> g.art.boxart ?: g.art.grid ?: g.art.square ?: g.art.icon
    LibraryLayout.CAPSULE -> g.art.hero ?: g.art.grid ?: g.art.boxart
    else -> g.art.tile
}

private fun isRemote(model: Any): Boolean = model is String && (model.startsWith("https://") || model.startsWith("http://"))

/** Decoded at its drawn size into memory, or fetched to disk and decoded small (cheap) and dropped. */
private fun request(context: PlatformContext, w: Warm, memory: Boolean, small: Int): ImageRequest {
    val b = ImageRequest.Builder(context).data(w.model)
    return if (memory) {
        b.size(w.px, w.px).precision(Precision.INEXACT).scale(Scale.FIT).build()
    } else {
        b.size(small, small).memoryCachePolicy(CachePolicy.DISABLED).build()
    }
}

/** Let the first screen draw before anything else loads. */
private const val START_DELAY_MS = 700L

/** Changes come in bursts (a fill stores art game by game). */
private const val REPLAN_DELAY_MS = 1_500L

/** Share of the image memory cache the warm-up may fill; the screens keep the rest. */
private const val MEMORY_SHARE = 0.6
private const val LOW_POWER_SHARE = 0.25
private const val PARALLEL = 3
private const val LOGOS_PER_SYSTEM = 12
private const val ROOMS_PER_SYSTEM = 24
private const val HOME_ROOMS = 8
