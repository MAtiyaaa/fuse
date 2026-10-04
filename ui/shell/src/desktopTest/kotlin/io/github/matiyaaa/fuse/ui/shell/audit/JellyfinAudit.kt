package io.github.matiyaaa.fuse.ui.shell.audit

import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.annotation.DelicateCoilApi
import io.github.matiyaaa.fuse.data.settings.JellyfinSettings
import io.github.matiyaaa.fuse.model.Destination
import io.github.matiyaaa.fuse.model.HomeMode
import io.github.matiyaaa.fuse.model.HomeWidget
import io.github.matiyaaa.fuse.model.PadButton
import io.github.matiyaaa.fuse.model.WidgetKind
import io.github.matiyaaa.fuse.ui.shell.platform.fuseImageLoader
import io.github.matiyaaa.fuse.ui.shell.store.UiPrefs
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain

/** Jellyfin on, pointed at the made-up server, with [more] on top. */
private fun jellyfinOn(p: UiPrefs, more: (UiPrefs) -> UiPrefs = { it }) = more(
    p.copy(jellyfin = JellyfinSettings(enabled = true, mode = "REMOTE", remoteAddress = AuditJellyfin.HOST)),
)

/** The library app with Jellyfin on and signed in, its server answering. */
private fun AuditDriver.useJellyfin(more: (UiPrefs) -> UiPrefs = { it }) {
    useLibrary { jellyfinOn(it, more) }
    val service = libraryStore.jellyfin ?: throw NotCovered("This store has no Jellyfin")
    if (service.state.value.account == null) runBlocking { service.signIn("pat", "audit").getOrThrow() }
    pumpUntil("the server to answer", 15_000) { service.state.value.base != null }
}

/**
 * Jellyfin, with a made-up server (no real library appears): its settings before and after
 * signing in, the Addons home, a library's grid and its sort, a film's page, a show's page with
 * its seasons, searching, and the Home widgets in Channels and Flow.
 */
@OptIn(DelicateCoilApi::class)
internal fun AuditDriver.jellyfinScreens() {
    // The app's own image loader, so Jellyfin's pictures come from the made-up server.
    val http = HttpClient(MockEngine { request -> AuditJellyfin.answer(this, request) ?: respondError(HttpStatusCode.NotFound) })
    SingletonImageLoader.setUnsafe(fuseImageLoader(PlatformContext.INSTANCE, File(cache, "jellyfin-images").path, http, lowMemory = false))
    try {
        scenario("jellyfin", "settings") {
            useLibrary()
            openSettings()
            focusText("Addons")
            tap(PadButton.DPAD_RIGHT)
            waitFor("Server and playback")
            shoot("Addons, Jellyfin off")
            tap(PadButton.A)
            waitFor("Not set up")
            shoot("turned on, not set up yet")
            tapText("Server and playback")
            waitFor("Outside address")
            shoot("the page before an address")
            libraryStore.updatePrefs { jellyfinOn(it) }
            val service = libraryStore.jellyfin!!
            // The address reaches the service a moment after the setting is saved.
            settle(800)
            runBlocking {
                var tries = 0
                while (service.signIn("pat", "audit").isFailure && ++tries < 10) kotlinx.coroutines.delay(300)
            }
            waitFor("Signed in as")
            settle(1_500)
            shoot("signed in, connected from outside")
            tap(PadButton.DPAD_DOWN, 9)
            shoot("playback")
            tap(PadButton.DPAD_DOWN, 8)
            shoot("sound and subtitles")
            tapText("Subtitles", step = PadButton.DPAD_UP)
            waitFor("Signs and songs only")
            shoot("a choice: subtitles")
            tap(PadButton.B)
        }

        scenario("jellyfin", "addons home") {
            useJellyfin { it.copy(cartridgeEnabled = true) }
            tab(Destination.CARTRIDGE)
            settle(900)
            // Up into Addons' tabs, then along to Jellyfin.
            tap(PadButton.DPAD_UP)
            focusText("Jellyfin") { tap(PadButton.DPAD_RIGHT) }
            tap(PadButton.DPAD_DOWN)
            waitFor("Continue watching")
            settle(2_000)
            shoot("Jellyfin's home", 1_500)
            tap(PadButton.DPAD_DOWN)
            shoot("continue watching chosen", 1_200)
            tap(PadButton.DPAD_DOWN, 2)
            shoot("libraries", 1_200)
            tap(PadButton.DPAD_DOWN, 2)
            shoot("what's new in shows", 1_200)
            tap(PadButton.X)
            waitFor("Mark")
            shoot("an item's options")
            tap(PadButton.B)
        }

        scenario("jellyfin", "library grid") {
            useJellyfin { it.copy(cartridgeEnabled = false) }
            tab(Destination.CARTRIDGE)
            waitFor("Continue watching")
            // Continue watching, Next up, then the libraries: Films first.
            tap(PadButton.DPAD_DOWN, 2)
            tap(PadButton.A)
            waitFor("A to Z")
            settle(2_000)
            shoot("Films, A to Z", 1_200)
            tap(PadButton.DPAD_RIGHT, 2)
            tap(PadButton.DPAD_DOWN, 2)
            shoot("further down the grid", 1_200)
            focusHint("Choose") { tap(PadButton.DPAD_UP) }
            tap(PadButton.A)
            waitFor("Sort by")
            shoot("sorting")
            tap(PadButton.B)
        }

        scenario("jellyfin", "film page") {
            useJellyfin { it.copy(cartridgeEnabled = false) }
            tab(Destination.CARTRIDGE)
            waitFor("Continue watching")
            // Down past Next up and the libraries to what's new in Films.
            tap(PadButton.DPAD_DOWN, 3)
            settle(900)
            tap(PadButton.A)
            waitFor("Some lines are only walked once", 20_000)
            settle(2_500)
            shoot("a film's page", 1_500)
            tap(PadButton.DPAD_DOWN, 2)
            shoot("cast", 1_200)
        }

        scenario("jellyfin", "show page") {
            useJellyfin { it.copy(cartridgeEnabled = false) }
            tab(Destination.CARTRIDGE)
            waitFor("Continue watching")
            tap(PadButton.DPAD_DOWN, 4)
            settle(900)
            tap(PadButton.A)
            waitFor("Season 1", 20_000)
            settle(2_500)
            shoot("a show's page", 1_500)
            tap(PadButton.DPAD_DOWN)
            shoot("seasons", 1_000)
            tap(PadButton.DPAD_DOWN)
            shoot("episodes", 1_200)
        }

        scenario("jellyfin", "search") {
            useJellyfin { it.copy(cartridgeEnabled = false) }
            tab(Destination.CARTRIDGE)
            waitFor("Continue watching")
            tap(PadButton.Y)
            waitFor("Search Jellyfin")
            shoot("typing a search")
            type("har")
            router.textInput?.submit()
            waitFor("results")
            settle(2_000)
            shoot("what matches \"har\"", 1_500)
        }

        scenario("jellyfin", "widgets on channels") {
            useJellyfin {
                it.copy(
                    home = it.home.copy(
                        mode = HomeMode.CHANNELS,
                        board = listOf(
                            HomeWidget("jf1", WidgetKind.JELLYFIN_CONTINUE, 0, width = 2, height = 2),
                            HomeWidget("jf2", WidgetKind.JELLYFIN_NEXT_UP, 1, width = 2, height = 1),
                            HomeWidget("jf3", WidgetKind.JELLYFIN_RECENTLY_ADDED, 2, width = 2, height = 1),
                            HomeWidget("clock", WidgetKind.CLOCK, 3, width = 1, height = 1),
                        ),
                    ),
                )
            }
            home()
            pumpUntil("the widgets to fill", 20_000) { libraryStore.homeFeed.value.media.continueWatching.isNotEmpty() }
            settle(2_500)
            shoot("Jellyfin's widgets", 1_500)
        }

        scenario("jellyfin", "widgets in flow") {
            useJellyfin {
                it.copy(home = it.home.copy(mode = HomeMode.FLOW, widgets = listOf(HomeWidget("jfrow", WidgetKind.JELLYFIN_NEXT_UP, -1)) + it.home.widgets))
            }
            home()
            pumpUntil("the widgets to fill", 20_000) { libraryStore.homeFeed.value.media.nextUp.isNotEmpty() }
            settle(1_500)
            shoot("next up as the first row", 1_500)
        }
    } finally {
        SingletonImageLoader.reset()
        http.close()
    }
}

/** An engine that "plays" a painted dusk, for the remote and the picture without a stream. */
private class AuditStillEngine : io.github.matiyaaa.fuse.ui.player.PlayerEngine {
    override val state = kotlinx.coroutines.flow.MutableStateFlow(
        io.github.matiyaaa.fuse.ui.player.EngineState(
            io.github.matiyaaa.fuse.ui.player.EngineStatus.READY, playing = true, positionMs = 2_880_000, durationMs = 7_920_000,
            bufferedMs = 3_000_000, videoWidth = 1920, videoHeight = 1080, decoder = "hevc", hardware = true,
        ),
    )
    override val cues = kotlinx.coroutines.flow.MutableStateFlow<List<io.github.matiyaaa.fuse.playback.Cue>>(emptyList())
    override fun load(source: io.github.matiyaaa.fuse.playback.PlaySource, startMs: Long, audioOrder: Int?, subtitleOrder: Int?, play: Boolean) {}
    override fun play() { state.value = state.value.copy(playing = true) }
    override fun pause() { state.value = state.value.copy(playing = false) }
    override fun seekTo(ms: Long) {}
    override fun setSpeed(speed: Float) {}
    override fun setVolume(volume: Float) {}
    override fun selectAudio(order: Int) {}
    override fun selectSubtitle(order: Int?) {}
    override fun positionMs(): Long = state.value.positionMs
    override fun capabilities(hardwareDecoding: Boolean) = io.github.matiyaaa.fuse.playback.Capabilities(emptyList(), setOf("aac"), containers = setOf("mkv"))
    override fun stop() {}
    override fun release() {}

    @androidx.compose.runtime.Composable
    override fun Video(modifier: androidx.compose.ui.Modifier) {
        androidx.compose.foundation.Canvas(modifier) {
            drawRect(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(androidx.compose.ui.graphics.Color(0xFF1B2A4A), androidx.compose.ui.graphics.Color(0xFFB4566A), androidx.compose.ui.graphics.Color(0xFFF2A65A))))
            drawCircle(androidx.compose.ui.graphics.Color(0xFFFFD58A), size.minDimension * 0.12f, androidx.compose.ui.geometry.Offset(size.width * 0.62f, size.height * 0.58f))
        }
    }
}

/** A stream with two sound tracks and subtitles, without asking the server. */
private object AuditStillResolver : io.github.matiyaaa.fuse.playback.PlaybackResolver {
    override suspend fun resolve(item: io.github.matiyaaa.fuse.playback.PlayItem, request: io.github.matiyaaa.fuse.playback.PlayRequest) = io.github.matiyaaa.fuse.playback.PlaySource(
        url = "still", method = io.github.matiyaaa.fuse.playback.PlayMethod.DIRECT_PLAY, durationMs = 7_920_000,
        audioTracks = listOf(
            io.github.matiyaaa.fuse.playback.AudioTrack("1", 1, 0, "English  ·  5.1", "eng", "eac3", 6, true),
            io.github.matiyaaa.fuse.playback.AudioTrack("2", 2, 1, "Commentary  ·  Stereo", "eng", "aac", 2),
        ),
        subtitleTracks = listOf(io.github.matiyaaa.fuse.playback.SubtitleTrack("3", 3, "English", "eng", "srt", delivery = io.github.matiyaaa.fuse.playback.SubtitleDelivery.Embedded(0))),
        audio = "1",
    )
}

/**
 * Jellyfin on two screens: the companion with a film and an episode in focus, the companion as
 * the remote while a film plays, and, flipped, the screen above with the film's picture and with
 * a film in focus.
 */
@OptIn(DelicateCoilApi::class)
internal fun AuditDriver.jellyfinDualScreens() {
    val http = HttpClient(MockEngine { request -> AuditJellyfin.answer(this, request) ?: respondError(HttpStatusCode.NotFound) })
    SingletonImageLoader.setUnsafe(fuseImageLoader(PlatformContext.INSTANCE, File(cache, "jellyfin-images").path, http, lowMemory = false))
    // The player's session runs on the main dispatcher, which a test window doesn't have.
    setMainDispatcher()
    val player = io.github.matiyaaa.fuse.ui.player.FusePlayer
    player.engineFactory = { AuditStillEngine() }
    try {
        scenario("jellyfin", "second screen") {
            useJellyfin()
            val service = libraryStore.jellyfin!!
            val film = runBlocking { service.item("m1") }
            val episode = runBlocking { service.item("s1s1e3") }
            io.github.matiyaaa.fuse.ui.shell.jellyfin.MediaFocus.put(film)
            io.github.matiyaaa.fuse.ui.shell.jellyfin.MediaFocus.put(episode)
            io.github.matiyaaa.fuse.ui.shell.app.Spotlight.set(null)
            view = AuditView.Companion(libraryStore, platform, io.github.matiyaaa.fuse.model.DualScreenMode.LIBRARY_COMPANION)
            settle(1_000)
            io.github.matiyaaa.fuse.ui.shell.app.Spotlight.set("jf:m1")
            shoot("a film in focus", 2_500)
            io.github.matiyaaa.fuse.ui.shell.app.Spotlight.set("jf:s1s1e3")
            shoot("an episode in focus", 2_500)
            player.session.start(film.toPlayItem(), AuditStillResolver, 2_880_000)
            pumpUntil("the film to start", 10_000) { player.session.source != null }
            shoot("the remote while the film plays", 2_000)
            view = AuditView.Piece { io.github.matiyaaa.fuse.ui.shell.app.ShowcaseApp(libraryStore, platform) }
            shoot("flipped: the film on the screen above", 1_500)
            player.session.stop()
            io.github.matiyaaa.fuse.ui.shell.app.Spotlight.set("jf:m1")
            shoot("flipped: a film in focus, large", 2_500)
            show(libraryStore)
        }
    } finally {
        player.session.stop()
        player.engineFactory = null
        resetMainSafely()
        SingletonImageLoader.reset()
        http.close()
    }
}

/** A main dispatcher for the player while the audit runs, unless the platform has one already. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
private fun setMainDispatcher() {
    val hasMain = runCatching { kotlinx.coroutines.Dispatchers.Main.isDispatchNeeded(kotlin.coroutines.EmptyCoroutineContext) }.isSuccess
    if (!hasMain) kotlinx.coroutines.Dispatchers.setMain(kotlinx.coroutines.Dispatchers.Unconfined)
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
private fun resetMainSafely() {
    runCatching { kotlinx.coroutines.Dispatchers.resetMain() }
}
