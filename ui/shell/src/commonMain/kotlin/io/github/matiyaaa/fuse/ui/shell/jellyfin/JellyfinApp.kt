package io.github.matiyaaa.fuse.ui.shell.jellyfin

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import io.github.matiyaaa.fuse.ui.designsystem.components.Hint
import io.github.matiyaaa.fuse.ui.designsystem.icons.HintButton
import io.github.matiyaaa.fuse.ui.fuseline.PageEffect
import io.github.matiyaaa.fuse.data.settings.JellyfinSettings
import io.github.matiyaaa.fuse.jellyfin.JellyfinArt
import io.github.matiyaaa.fuse.jellyfin.JellyfinQuality
import io.github.matiyaaa.fuse.jellyfin.JellyfinResolver
import io.github.matiyaaa.fuse.jellyfin.JellyfinService
import io.github.matiyaaa.fuse.jellyfin.MediaItem
import io.github.matiyaaa.fuse.jellyfin.MediaType
import io.github.matiyaaa.fuse.ui.designsystem.media.HeroSource
import io.github.matiyaaa.fuse.ui.player.FusePlayer
import io.github.matiyaaa.fuse.ui.player.PlayerScreen
import io.github.matiyaaa.fuse.ui.player.PlayerSettings
import io.github.matiyaaa.fuse.ui.shell.app.AppState
import kotlinx.coroutines.launch

/** Jellyfin, when this build has it. */
internal val AppState.jellyfin: JellyfinService? get() = store.jellyfin

/** The player's behaviour from the user's Jellyfin settings. */
internal fun JellyfinSettings.toPlayerSettings() = PlayerSettings(
    subtitleScale = subtitleScale,
    subtitleLift = subtitleLift,
    subtitleBackground = subtitleBackground,
    seekSeconds = seekSeconds,
    controlsTimeoutMs = controlsTimeoutSeconds * 1000L,
    autoplayNext = autoplayNext,
    rememberSpeed = rememberSpeed,
    hardwareDecoding = hardwareDecoding,
)

/** What the player changed (speed, subtitle size), kept in the Jellyfin settings. */
internal fun JellyfinSettings.with(p: PlayerSettings, speed: Float) = copy(
    subtitleScale = p.subtitleScale,
    subtitleLift = p.subtitleLift,
    subtitleBackground = p.subtitleBackground,
    speed = if (rememberSpeed) speed else this.speed,
)

private var resolverFor: Pair<JellyfinService, JellyfinResolver>? = null

/** The one resolver for the service, reading the quality settings as it goes. */
internal fun AppState.resolver(): JellyfinResolver? {
    val s = jellyfin ?: return null
    resolverFor?.let { if (it.first === s) return it.second }
    val r = JellyfinResolver(s) {
        val j = store.prefs.value.jellyfin
        JellyfinQuality(
            localMaxBitrate = j.localMaxBitrate.takeIf { it > 0 },
            remoteMaxBitrate = j.remoteMaxBitrate.takeIf { it > 0 },
            audioLanguage = j.audioLanguage.ifBlank { null },
            subtitleLanguage = j.subtitleLanguage.ifBlank { null },
            subtitleMode = j.subtitleMode,
        )
    }
    resolverFor = s to r
    return r
}

/**
 * Plays [item] in Fuse Player: an episode or film from where it was left (unless [fromStart]), a
 * song with the rest of [queue] after it. A series or season starts at its next episode.
 */
internal fun AppState.play(item: MediaItem, fromStart: Boolean = false, queue: List<MediaItem> = emptyList()) {
    val service = jellyfin ?: return
    val resolver = resolver() ?: return
    if (!FusePlayer.available) {
        toasts.show("This build of Fuse has no player")
        return
    }
    scope.launch {
        val target = when (item.type) {
            MediaType.SERIES, MediaType.SEASON -> runCatching {
                service.nextUpFor(item.seriesId ?: item.id)
                    ?: service.episodes(item.seriesId ?: item.id, if (item.type == MediaType.SEASON) item.id else null).firstOrNull { !it.played }
                    ?: service.episodes(item.seriesId ?: item.id, null).firstOrNull()
            }.getOrNull()
            else -> item
        } ?: run {
            toasts.show("Nothing to play here yet")
            return@launch
        }
        val session = FusePlayer.session
        val j = store.prefs.value.jellyfin
        session.settings = j.toPlayerSettings()
        // The picture goes to the screen asked for (Films play on), where there is another screen.
        val placement = io.github.matiyaaa.fuse.ui.player.PlayerPlacement
        placement.withMenus = !placement.canSwap || target.type == MediaType.SONG || menusOnSecondScreen == (j.playOn == "SECOND")
        val start = if (fromStart) 0 else target.resumeMs
        val list = (if (queue.isEmpty()) listOf(target) else queue).map { it.toPlayItem() }
        session.start(target.toPlayItem(), resolver, start, list)
        if (j.rememberSpeed && j.speed != 1f) session.changeSpeed(j.speed)
        playerOpen = true
    }
}

/**
 * Fuse Player over everything while it plays: full screen, its own controls. Leaving stops it and
 * tells the pages to ask the server again (resume points and watched marks changed).
 */
@Composable
internal fun MediaPlayerHost(app: AppState) {
    if (!app.playerOpen) return
    val session = FusePlayer.session
    val prefs by app.store.prefs.collectAsState()
    LaunchedEffect(Unit) { app.hints = emptyList() }
    fun exit() {
        session.stop()
        app.playerOpen = false
        app.scope.launch { app.jellyfin?.changed() }
    }
    // Stopped from the other screen's remote: the player closes here too.
    LaunchedEffect(Unit) {
        androidx.compose.runtime.snapshotFlow { session.item }.collect { if (it == null && !session.resolving) app.playerOpen = false }
    }
    val placement = io.github.matiyaaa.fuse.ui.player.PlayerPlacement
    // The picture is on the other screen: this one is its remote, and B goes back to browsing.
    if (!placement.withMenus) {
        val flippedMenus = io.github.matiyaaa.fuse.ui.shell.app.LocalShowcaseElsewhere.current
        PageEffect(Unit) {
            app.hints = listOf(
                Hint(HintButton.CONFIRM, "Play or pause"),
                Hint(HintButton.SEARCH, "Play here"),
                Hint(HintButton.OPTIONS, "Stop"),
                Hint(HintButton.BACK, "Keep browsing"),
            )
        }
        io.github.matiyaaa.fuse.ui.player.PlayerRemote(
            session, Modifier.fillMaxSize(),
            inputEnabled = !app.overlayOpen,
            onExit = ::exit,
            onSwap = { placement.swap() },
            onBrowse = { app.playerOpen = false },
            where = if (flippedMenus) "On the screen above" else "On the second screen",
        )
        return
    }
    PlayerScreen(
        session = session,
        onExit = ::exit,
        modifier = Modifier,
        inputEnabled = !app.overlayOpen,
        onSettings = { s -> app.store.updatePrefs { it.copy(jellyfin = it.jellyfin.with(s, session.speed)) } },
        fullscreen = app.platform.windowControls?.let { w -> { w.setMode(if (w.mode == io.github.matiyaaa.fuse.ui.shell.platform.WindowStyle.FULLSCREEN) io.github.matiyaaa.fuse.ui.shell.platform.WindowStyle.WINDOWED else io.github.matiyaaa.fuse.ui.shell.platform.WindowStyle.FULLSCREEN) } },
        onSwap = if (placement.canSwap) ({ placement.swap() }) else null,
    )
    // The settings may have changed in Settings meanwhile.
    LaunchedEffect(prefs.jellyfin) { session.settings = prefs.jellyfin.toPlayerSettings().copy(maxBitrate = session.settings.maxBitrate) }
}

/** An item's backdrop for the room behind the pages. */
internal fun MediaItem.hero(): HeroSource? {
    // The second screen finds the item by its room's key.
    MediaFocus.put(this)
    val art = backdrop ?: thumb ?: poster ?: return HeroSource(id = MediaFocus.keyOf(this), model = null, accent = accentOf(name), blurred = true)
    return HeroSource(id = MediaFocus.keyOf(this), model = art.sized(BACKDROP_WIDTH), accent = Color(0xFF7B8CC4), blurred = backdrop == null)
}

/** [MediaItem.hero], for code outside this package. */
internal fun heroOf(item: MediaItem): HeroSource? = item.hero()

/** Picture widths asked of the server: posters, wide cards and backdrops. */
internal const val POSTER_WIDTH = 420
internal const val WIDE_WIDTH = 640
internal const val BACKDROP_WIDTH = 1920

internal fun JellyfinArt?.at(width: Int): JellyfinArt? = this?.sized(width)
