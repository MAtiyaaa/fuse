package io.github.matiyaaa.fuse.ui.shell.store.impl

import io.github.matiyaaa.fuse.integrations.ApiResult
import io.github.matiyaaa.fuse.integrations.systemart.SystemArtNames
import io.github.matiyaaa.fuse.integrations.systemart.SystemArtPackClient
import io.github.matiyaaa.fuse.integrations.systemart.SystemArtStyle
import io.github.matiyaaa.fuse.model.ArtworkOption
import io.github.matiyaaa.fuse.model.MediaFillMode
import io.github.matiyaaa.fuse.model.MediaItem
import io.github.matiyaaa.fuse.model.MediaKind
import io.github.matiyaaa.fuse.model.MediaOwner
import io.github.matiyaaa.fuse.model.MediaSource
import io.github.matiyaaa.fuse.model.Platform
import io.github.matiyaaa.fuse.model.PlatformId
import io.github.matiyaaa.fuse.model.ScrapeProviderId
import io.github.matiyaaa.fuse.ui.shell.store.FillProgress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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

    /** Fills systems that have games and no art, whenever that set changes, while the setting is on. */
    fun start() {
        val data = ctx.data
        ctx.scope.launch {
            data.games.platformCounts()
                .map { counts -> counts.filterValues { it > 0 }.keys }
                .distinctUntilChanged()
                .flatMapLatest { ids ->
                    data.media.observeFor(ids.map { MediaOwner.OfPlatform(it) }).map { media ->
                        ids.filter { id -> media[MediaOwner.OfPlatform(id)].let { it == null || (it.logo == null && it.boxart == null && it.icon == null) } }
                    }
                }
                .combine(data.settings.settings.map { it.library.systemArtAuto }.distinctUntilChanged()) { bare, auto -> if (auto) bare else emptyList() }
                .collect { bare ->
                    val fresh = lock.withLock { bare.filter { attempted.add(it) } }
                    for (id in fresh) {
                        val platform = ctx.platform(id) ?: continue
                        runCatching { fetch(platform, MediaFillMode.FILL_MISSING) }
                    }
                }
        }
    }

    /** Fetches every system with games again in the current style. Art the user chose stays. */
    fun downloadAll() {
        job?.cancel()
        job = ctx.scope.launch {
            val ids = ctx.data.games.platformCounts().first().filterValues { it > 0 }.keys.toList()
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
        val name = SystemArtNames.forPlatform(platform.id, platform.folderAliases) ?: return 0
        val art = (client.fetch(name, style) as? ApiResult.Success)?.value ?: return 0
        val items = listOfNotNull(
            MediaItem(MediaKind.LOGO, MediaSource.ART_PACK, remoteUrl = art.logoUrl),
            art.artworkUrl?.let { MediaItem(MediaKind.BOXART, MediaSource.ART_PACK, remoteUrl = it) },
        )
        val added = ctx.data.media.putScraped(MediaOwner.OfPlatform(platform.id), items, mode, setOf(MediaKind.LOGO, MediaKind.BOXART))
        art.meta?.color?.let { color -> saveColor(platform.id, color) }
        return added
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
}
