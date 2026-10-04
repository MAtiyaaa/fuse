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
            runBlocking { service.signIn("pat", "audit").getOrThrow() }
            waitFor("Signed in as")
            settle(1_500)
            shoot("signed in, connected from outside")
            tap(PadButton.DPAD_DOWN, 9)
            shoot("playback")
            tap(PadButton.DPAD_DOWN, 8)
            shoot("sound and subtitles")
            tapText("Subtitles")
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
            tap(PadButton.DPAD_UP, 4)
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
            waitFor("Northlight Pictures", 20_000)
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
