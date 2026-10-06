package io.github.matiyaaa.fuse.ui.shell.store.impl

import io.github.matiyaaa.fuse.integrations.ApiResult
import io.github.matiyaaa.fuse.integrations.systemart.SystemArtNames
import io.github.matiyaaa.fuse.integrations.systemart.SystemArtPackClient
import io.github.matiyaaa.fuse.integrations.systemart.SystemArtStyle
import io.github.matiyaaa.fuse.model.ArtworkOption
import io.github.matiyaaa.fuse.model.GameId
import io.github.matiyaaa.fuse.model.MediaFillMode
import io.github.matiyaaa.fuse.model.MediaItem
import io.github.matiyaaa.fuse.model.MediaKind
import io.github.matiyaaa.fuse.model.MediaOwner
import io.github.matiyaaa.fuse.model.MediaSet
import io.github.matiyaaa.fuse.model.MediaSource
import io.github.matiyaaa.fuse.model.Platform
import io.github.matiyaaa.fuse.model.PlatformId
import io.github.matiyaaa.fuse.model.ScrapeProviderId
import io.github.matiyaaa.fuse.ui.shell.store.Art
import io.github.matiyaaa.fuse.ui.shell.store.FillProgress
import io.github.matiyaaa.fuse.ui.shell.store.GamePanels
import io.github.matiyaaa.fuse.ui.shell.store.PanelGame
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * System logos, artwork and brand colours from the Art Book Next pack, fetched on demand and never
 * bundled. Art goes in as platform media with source [MediaSource.ART_PACK], so art the user chose
 * is never replaced; brand colours are kept in settings and used instead of Fuse's generated accent.
 *
 * By default every system with games and no art gets it once per session; "Download system art for
 * all systems" fetches every system again in the chosen style.
 */
internal class SystemArtStore(private val ctx: StoreContext) {
    private val client by lazy { SystemArtPackClient(ctx.services.http) }
    private val attempted = mutableSetOf<PlatformId>()
    private val lock = Mutex()
    private var job: Job? = null

    private val progressState = MutableStateFlow<FillProgress?>(null)
    val progress: StateFlow<FillProgress?> = progressState

    private val style: SystemArtStyle
        get() = SystemArtStyle.entries.firstOrNull { it.name == ctx.settings.value.library.systemArtStyle } ?: SystemArtStyle.CLASSIC

    /** The "From your games" style: the pack's logos, with every system's panel made from its games' screenshots. */
    private val gamesStyle: Boolean get() = ctx.settings.value.library.systemArtStyle == GAMES_STYLE

    /** Fills systems that have games and no art, whenever that set changes, while the setting is on. */
    fun start() {
        // Panels picked from a game's screenshot are cut like the pack's wherever systems are drawn.
        GamePanels.models = ctx.settings.value.library.systemPanels.values.toSet()
        ctx.scope.launch {
            ctx.data.settings.settings.map { it.library.systemPanels.values.toSet() }.distinctUntilChanged().collect { GamePanels.models = it }
        }
        followGamesArt()
        giveOwnLogos()
        val data = ctx.data
        ctx.scope.launch {
            data.games.platformCounts()
                .map { counts -> counts.filterValues { it > 0 }.keys }
                // RomM's systems show on its tab with the same art, games here or not.
                .combine(ctx.shownPlatforms) { here, shown -> here + shown }
                .distinctUntilChanged()
                .flatMapLatest { ids ->
                    data.media.observeFor(ids.map { MediaOwner.OfPlatform(it) }).map { media ->
                        ids.filter { id -> media[MediaOwner.OfPlatform(id)].let { it == null || (it.logo == null && it.boxart == null && it.icon == null) } }
                    }
                }
                .combine(data.settings.settings.map { it.library.systemArtAuto to it.library.systemArtDefault.toSet() }.distinctUntilChanged()) { bare, (auto, kept) ->
                    // Systems put back to Fuse's own art keep it.
                    if (auto) bare.filter { it.value !in kept } else emptyList()
                }
                .collect { bare ->
                    val fresh = lock.withLock { bare.filter { attempted.add(it) } }
                    for (id in fresh) {
                        val platform = ctx.platform(id) ?: continue
                        runCatching { fetch(platform, MediaFillMode.FILL_MISSING) }
                    }
                }
        }
    }

    /**
     * Systems the pack has no logo for that Fuse drew one for (the PlayStation 5) get Fuse's, as
     * soon as they show, unless they already have a logo (the person's own, say).
     */
    private fun giveOwnLogos() {
        val data = ctx.data
        ctx.scope.launch {
            data.games.platformCounts()
                .map { counts -> counts.filterValues { it > 0 }.keys }
                .combine(ctx.shownPlatforms) { here, shown -> (here + shown).filter { it.value in OWN_LOGOS } }
                .distinctUntilChanged()
                .collect { ids ->
                    for (id in ids) runCatching {
                        if (data.media.get(MediaOwner.OfPlatform(id)).logo == null) ownLogo(id)?.let { data.media.putScraped(MediaOwner.OfPlatform(id), listOf(it), MediaFillMode.FILL_MISSING, setOf(MediaKind.LOGO)) }
                    }
                }
        }
    }

    /** Fuse's own logo for [id], as a file it can draw, or null when it has none. */
    private suspend fun ownLogo(id: PlatformId): MediaItem? {
        val resource = OWN_LOGOS[id.value] ?: return null
        val path = ctx.services.cacheFile("system-logos/${id.value}.svg") { io.github.matiyaaa.fuse.ui.designsystem.res.Res.readBytes(resource) } ?: return null
        return MediaItem(MediaKind.LOGO, MediaSource.ART_PACK, localPath = path)
    }

    /**
     * Systems the pack has nothing for take their art from their games, which often get theirs
     * later (a RomM server's games are filled after they are listed): looked at again, a moment
     * after games' art was found, while they are still bare.
     */
    private fun followGamesArt() {
        ctx.scope.launch {
            ctx.gameArtFound.collectLatest {
                delay(GAME_ART_SETTLE_MS)
                val lib = ctx.settings.value.library
                if (!lib.systemArtAuto) return@collectLatest
                val ids = ctx.data.games.platformCounts().first().filterValues { it > 0 }.keys + ctx.shownPlatforms.value
                // Systems the pack lacks, or every system in the From your games style.
                val games = lib.systemArtStyle == GAMES_STYLE
                val targets = ids.mapNotNull { ctx.platform(it) }
                    .filter { p -> p.id.value !in lib.systemArtDefault && (games || SystemArtNames.forPlatform(p.id, p.folderAliases) == null) }
                if (targets.isEmpty()) return@collectLatest
                val media = ctx.data.media.observeFor(targets.map { MediaOwner.OfPlatform(it.id) }).first()
                for (p in targets) {
                    val m = media[MediaOwner.OfPlatform(p.id)]
                    if (m == null || (m.boxart == null && m.icon == null && m.square == null)) runCatching { fromGames(p, MediaFillMode.FILL_MISSING) }
                }
            }
        }
    }

    /** Fetches every system with games again in the current style. Art the user chose stays. */
    fun downloadAll() {
        job?.cancel()
        job = ctx.scope.launch {
            // Asked for every system: the ones put back to Fuse's art take the pack again too.
            if (ctx.settings.value.library.systemArtDefault.isNotEmpty()) {
                ctx.settings.value = ctx.data.settings.update { it.copy(library = it.library.copy(systemArtDefault = emptyList())) }
            }
            val ids = (ctx.data.games.platformCounts().first().filterValues { it > 0 }.keys + ctx.shownPlatforms.value).toList()
            var added = 0
            progressState.value = FillProgress(0, ids.size, null, 0, finished = ids.isEmpty())
            ids.forEachIndexed { i, id ->
                val platform = ctx.platform(id)
                if (platform != null) {
                    progressState.value = FillProgress(i, ids.size, platform.name, added, finished = false)
                    added += try {
                        fetch(platform, MediaFillMode.REPLACE_ALL)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        0
                    }
                }
            }
            progressState.value = FillProgress(ids.size, ids.size, null, added, finished = true)
        }
    }

    /**
     * Logo and artwork for [platform], plus its brand colour. Returns how many items were stored; 0
     * when the pack has no such system or it can't be reached.
     */
    private suspend fun fetch(platform: Platform, mode: MediaFillMode): Int {
        val name = SystemArtNames.forPlatform(platform.id, platform.folderAliases) ?: return fromGames(platform, mode) +
            (ownLogo(platform.id)?.let { ctx.data.media.putScraped(MediaOwner.OfPlatform(platform.id), listOf(it), mode, setOf(MediaKind.LOGO)) } ?: 0)
        // From your games: the pack's logo and colour, the panel from the system's own screenshots.
        val games = gamesStyle
        val art = (client.fetch(name, if (games) SystemArtStyle.CLASSIC else style) as? ApiResult.Success)?.value
            ?: return if (games) fromGames(platform, mode) else 0
        val items = listOfNotNull(
            MediaItem(MediaKind.LOGO, MediaSource.ART_PACK, remoteUrl = art.logoUrl),
            art.artworkUrl?.takeIf { !games }?.let { MediaItem(MediaKind.BOXART, MediaSource.ART_PACK, remoteUrl = it) },
        )
        var added = ctx.data.media.putScraped(MediaOwner.OfPlatform(platform.id), items, mode, if (games) setOf(MediaKind.LOGO) else setOf(MediaKind.LOGO, MediaKind.BOXART))
        art.meta?.color?.let { color -> saveColor(platform.id, color) }
        if (games) added += fromGames(platform, mode)
        return added
    }

    /** [platform]'s games with a screenshot or a background, the most recently played here first, then the RomM server's. */
    private suspend fun gamesWithPictures(platform: PlatformId): List<Pair<GameId, MediaSet>> {
        val here = ctx.data.games.observeAll().first()
            .filter { it.platformId == platform && !it.isApp && !it.removed }
            .sortedByDescending { it.lastPlayedAt ?: 0L }
            .map { it.id }
        val ids = here + runCatching { ctx.remoteGamesOn(platform) }.getOrDefault(emptyList())
        if (ids.isEmpty()) return emptyList()
        val media = ctx.data.media.observeFor(ids.map { MediaOwner.OfGame(it) }).first()
        return ids.mapNotNull { id -> media[MediaOwner.OfGame(id)]?.takeIf { it.screenshots.isNotEmpty() || it.hero != null }?.let { id to it } }
    }

    /** For the Media page: [platform]'s games to take a panel from, with their art and how many pictures each has. */
    suspend fun panelGames(platform: PlatformId): List<PanelGame> {
        val titles = ctx.data.games.observeAll().first().associate { it.id to it.displayTitle }
        return gamesWithPictures(platform).map { (id, m) ->
            val title = titles[id] ?: ctx.remoteGames?.takeIf { it.owns(id) }?.get(id)?.displayTitle ?: "Game"
            PanelGame(id, title, Art.from(m), m.screenshots.size + (if (m.hero != null) 1 else 0))
        }
    }

    /** [game]'s screenshots, then its background, as options for a system's panel. */
    suspend fun panelPictures(game: GameId): List<ArtworkOption> {
        val m = ctx.data.media.get(MediaOwner.OfGame(game))
        return (m.screenshots + listOfNotNull(m.hero)).mapNotNull { item ->
            item.model?.let { ArtworkOption(ScrapeProviderId.LOCAL, MediaKind.BOXART, it, thumbUrl = it, width = item.width, height = item.height, style = if (item.kind == MediaKind.HERO) "Background" else "Screenshot") }
        }
    }

    /** Makes [option] [platform]'s panel, picked by the person: kept, and cut like the pack's. */
    suspend fun setPanel(platform: PlatformId, option: ArtworkOption) {
        val model = option.url
        // Marked first, so the panel is cut from the moment it shows.
        GamePanels.models = GamePanels.models + model
        ctx.settings.value = ctx.data.settings.update { it.copy(library = it.library.copy(systemPanels = it.library.systemPanels + (platform.value to model))) }
        val local = !model.startsWith("http://") && !model.startsWith("https://")
        ctx.data.media.setCustom(MediaOwner.OfPlatform(platform), MediaKind.BOXART, localPath = model.takeIf { local }, remoteUrl = model.takeIf { !local }, width = option.width, height = option.height)
    }

    /** [platform]'s panel goes back to the one Fuse takes from its games by itself. */
    suspend fun autoPanel(platform: PlatformId) {
        val owner = MediaOwner.OfPlatform(platform)
        ctx.settings.value = ctx.data.settings.update { it.copy(library = it.library.copy(systemPanels = it.library.systemPanels - platform.value, systemArtDefault = it.library.systemArtDefault - platform.value)) }
        ctx.data.media.resetCustom(owner, MediaKind.BOXART)
        ctx.platform(platform)?.let { fromGames(it, MediaFillMode.REPLACE_SELECTED) }
    }

    /**
     * For a system the pack has nothing for (PlayStation 5, Switch 2): the same look, with a
     * screenshot of one of its own games (the most recently played here first, then the RomM
     * server's), else its background, as the artwork panel, drawn cut to the pack's slanted shape
     * at the pack's place and size. 0 while none of its games has art yet; [start] looks again as
     * their art arrives.
     */
    private suspend fun fromGames(platform: Platform, mode: MediaFillMode): Int {
        val pick = gamesWithPictures(platform.id).firstNotNullOfOrNull { (_, m) -> m.screenshots.firstOrNull() ?: m.hero } ?: return 0
        val item = MediaItem(
            MediaKind.BOXART, MediaSource.GAME_ART, localPath = pick.localPath, remoteUrl = pick.remoteUrl,
            width = pick.width, height = pick.height, focusX = pick.focusX, focusY = pick.focusY,
        )
        val kinds = setOf(MediaKind.BOXART)
        return ctx.data.media.putScraped(MediaOwner.OfPlatform(platform.id), listOf(item), if (mode == MediaFillMode.REPLACE_ALL) MediaFillMode.REPLACE_SELECTED else mode, kinds)
    }

    private suspend fun saveColor(id: PlatformId, rgb: Long) {
        // Pack colours are RGB; Fuse accents are opaque ARGB.
        val argb = 0xFF000000L or (rgb and 0xFFFFFFL)
        if (ctx.settings.value.library.systemColors[id.value] == argb) return
        ctx.settings.value = ctx.data.settings.update { it.copy(library = it.library.copy(systemColors = it.library.systemColors + (id.value to argb))) }
    }

    /** Every style's artwork and the logo for [platform], for the system's Media screen. */
    suspend fun options(platform: Platform, kind: MediaKind): List<ArtworkOption> {
        val name = SystemArtNames.forPlatform(platform.id, platform.folderAliases) ?: return emptyList()
        val urls = client.urls(name)
        return when (kind) {
            MediaKind.LOGO -> listOf(option(MediaKind.LOGO, urls.logo, "Logo"))
            MediaKind.BOXART -> {
                val styles = (client.availableStyles(name) as? ApiResult.Success)?.value ?: listOf(SystemArtStyle.CLASSIC)
                styles.map { option(MediaKind.BOXART, urls.artwork(it), it.displayName) }
            }
            else -> emptyList()
        }
    }

    private fun option(kind: MediaKind, url: String, style: String) =
        // The provider id is only a label here; the author names the pack in the art browser.
        ArtworkOption(ScrapeProviderId.LOCAL, kind, url, thumbUrl = null, width = null, height = null, style = style, author = "Art Book Next")

    /**
     * Puts [ids] back to Fuse's own art: their downloaded and chosen art is removed (the files stay)
     * and their pack colours go, and nothing is downloaded for them by itself after. Returns what
     * was there, for [undo].
     */
    suspend fun restoreDefault(ids: List<PlatformId>): SystemArtUndo {
        val before = ids.associateWith { ctx.data.media.rows(MediaOwner.OfPlatform(it)) }
        val colors = ctx.settings.value.library.systemColors.filterKeys { k -> ids.any { it.value == k } }
        val wasDefault = ctx.settings.value.library.systemArtDefault
        for (id in ids) ctx.data.media.clearOwner(MediaOwner.OfPlatform(id))
        ctx.settings.value = ctx.data.settings.update { s ->
            s.copy(library = s.library.copy(
                systemColors = s.library.systemColors - ids.map { it.value }.toSet(),
                systemArtDefault = (s.library.systemArtDefault + ids.map { it.value }).distinct(),
            ))
        }
        return SystemArtUndo(before, colors, wasDefault)
    }

    /** Puts back what [restoreDefault] removed. */
    suspend fun undo(u: SystemArtUndo) {
        for ((id, rows) in u.rows) ctx.data.media.putBack(MediaOwner.OfPlatform(id), rows)
        ctx.settings.value = ctx.data.settings.update { s ->
            s.copy(library = s.library.copy(systemColors = s.library.systemColors + u.colors, systemArtDefault = u.wasDefault))
        }
    }
}

/** What restoring Fuse's art replaced, kept for Undo. */
class SystemArtUndo internal constructor(
    internal val rows: Map<PlatformId, List<io.github.matiyaaa.fuse.data.db.Media>>,
    internal val colors: Map<String, Long>,
    internal val wasDefault: List<String>,
) : io.github.matiyaaa.fuse.ui.shell.store.ArtUndo {
    override val count: Int get() = rows.count { it.value.isNotEmpty() }
}

/** [io.github.matiyaaa.fuse.data.settings.LibrarySettings.systemArtStyle] for panels from the system's own games. */
internal const val GAMES_STYLE = "GAMES"

/** How long after games' art was found that systems drawn from it look again (art comes in bursts). */
private const val GAME_ART_SETTLE_MS = 2_000L

/** Logos Fuse drew for systems the art pack has none for, by platform id, to the bundled file. */
private val OWN_LOGOS = mapOf("ps5" to "files/system-logos/ps5.svg")
