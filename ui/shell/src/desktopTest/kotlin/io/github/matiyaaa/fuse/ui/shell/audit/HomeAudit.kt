package io.github.matiyaaa.fuse.ui.shell.audit

import io.github.matiyaaa.fuse.model.Destination
import io.github.matiyaaa.fuse.model.HomeLayoutConfig
import io.github.matiyaaa.fuse.model.HomeMode
import io.github.matiyaaa.fuse.model.HomeWidget
import io.github.matiyaaa.fuse.model.LibrarySourceKind
import io.github.matiyaaa.fuse.model.NavAction
import io.github.matiyaaa.fuse.model.PadButton
import io.github.matiyaaa.fuse.model.ScanPhase
import io.github.matiyaaa.fuse.model.WidgetKind
import io.github.matiyaaa.fuse.ui.designsystem.input.NavResult
import io.github.matiyaaa.fuse.ui.shell.home.Shelf
import io.github.matiyaaa.fuse.ui.shell.home.ShelfItem
import io.github.matiyaaa.fuse.ui.shell.home.ShelfStyle
import io.github.matiyaaa.fuse.ui.shell.home.buildShelves
import io.github.matiyaaa.fuse.ui.shell.home.title
import io.github.matiyaaa.fuse.ui.shell.store.FuseStore
import java.io.File
import kotlinx.coroutines.runBlocking

/** Every kind of Home shelf in one Flow layout, with the small widgets side by side in "At a glance". */
internal val EveryShelf: List<HomeWidget> = listOf(
    WidgetKind.CONTINUE_PLAYING,
    WidgetKind.PINNED_GAMES,
    WidgetKind.RECENTLY_PLAYED,
    WidgetKind.SYSTEMS,
    WidgetKind.COLLECTIONS,
    WidgetKind.FAVORITES,
    WidgetKind.RECENTLY_ADDED,
    WidgetKind.PLAYTIME_WEEK,
    WidgetKind.PLAYTIME_TOTAL,
    WidgetKind.MOST_PLAYED,
    WidgetKind.STORAGE,
    WidgetKind.CARTRIDGE_DOWNLOADS,
    WidgetKind.CLOCK,
    WidgetKind.PINNED_APPS,
).mapIndexed { i, k -> HomeWidget(id = k.name.lowercase(), kind = k, order = i) }

internal fun FuseStore.shelvesNow(): List<Shelf> = buildShelves(
    prefs.value.home.widgets,
    library.home.value,
    achievements.configured.value,
    cartridge.status.value.installed,
)

private fun ShelfStyle.words(): String = when (this) {
    ShelfStyle.WIDE -> "wide game tiles"
    ShelfStyle.ICON -> "game icons"
    ShelfStyle.SYSTEM -> "system tiles"
    ShelfStyle.APP -> "app icons"
    ShelfStyle.WIDGETS -> "widgets"
    ShelfStyle.COLLECTION -> "collection tiles"
}

/** Home in Flow mode: first focus, each shelf, every widget, arranging, and the section tabs. */
internal fun AuditDriver.homeFlow(exhaustive: Boolean) {
    scenario("home", "flow") {
        useLibrary()
        waitFor("Continue playing")
        if (exhaustive) shoot("first frame before any button press", 2_500)
        // A press that goes nowhere (left of the first tile) so the hints show controller buttons.
        tap(PadButton.DPAD_LEFT)
        shoot("default focus", 2_500)
        if (!exhaustive) return@scenario
        val shelves = libraryStore.shelvesNow()
        for (i in 1 until shelves.size) {
            if (nav(NavAction.DOWN) != NavResult.MOVED) throw NotCovered("Could not move down to shelf ${shelves[i].title}")
            shoot("shelf ${i + 1} of ${shelves.size}, ${shelves[i].title} (${shelves[i].style.words()})")
        }
    }

    // At every size: the stick reaching the tabs must not squeeze the clock (0.0.2 on the AYN).
    scenario("home", "flow section tabs") {
        useLibrary()
        waitFor("Continue playing")
        if (nav(NavAction.UP) != NavResult.MOVED) throw NotCovered("Up from the first shelf did not reach the tabs")
        shoot("focus moved up into the section tabs")
        tap(PadButton.DPAD_RIGHT)
        shoot("tabs focused, moved to Library")
        tap(PadButton.DPAD_DOWN)
        shoot("back down into Library")
    }
    if (!exhaustive) return

    scenario("home", "flow every shelf type") {
        useLibrary { it.copy(home = HomeLayoutConfig(widgets = EveryShelf)) }
        waitFor("Continue playing")
        tap(PadButton.DPAD_LEFT)
        val shelves = libraryStore.shelvesNow()
        val seen = mutableSetOf(ShelfStyle.WIDE)
        for (i in 1 until shelves.size) {
            if (nav(NavAction.DOWN) != NavResult.MOVED) throw NotCovered("Could not move down to shelf ${shelves[i].title}")
            val shelf = shelves[i]
            if (shelf.style == ShelfStyle.WIDGETS) {
                shelf.items.forEachIndexed { j, item ->
                    if (j > 0 && nav(NavAction.RIGHT) != NavResult.MOVED) throw NotCovered("Could not move to widget ${j + 1}")
                    val kind = (item as ShelfItem.Widget).kind
                    shoot("At a glance, ${kind.title()} widget focused")
                }
            } else if (shelf.style !in seen || shelf.title == "Pinned" || shelf.title == "Recently played") {
                shoot("${shelf.title} (${shelf.style.words()})")
            }
            seen += shelf.style
        }
    }

    scenario("home", "flow arranging") {
        useLibrary()
        waitFor("Continue playing")
        tap(PadButton.DPAD_DOWN)
        hold(PadButton.A)
        waitFor("Moving")
        shoot("holding A on the Systems shelf picks it up")
        tap(PadButton.DPAD_DOWN)
        shoot("shelf moved down one place")
        tap(PadButton.A)
        shoot("put down")
    }
    scenario("home", "posters") {
        useLibrary { it.copy(gameArt = io.github.matiyaaa.fuse.model.GameArtStyle.POSTER) }
        waitFor("Continue playing")
        tap(PadButton.DPAD_DOWN, 2)
        shoot("Home with posters, a poster shelf focused")
        tab(io.github.matiyaaa.fuse.model.Destination.LIBRARY)
        waitFor("All")
        tap(PadButton.DPAD_DOWN)
        shoot("the library grid with posters")
    }
    scenario("home", "flow drag by touch") {
        useLibrary()
        waitFor("Continue playing")
        // A shelf's title (set in capitals) is its handle: Continue playing goes below Systems.
        val from = textCentre("CONTINUE PLAYING")
        val to = textCentre("SYSTEMS") + androidx.compose.ui.geometry.Offset(0f, 60f)
        touch { down(from) }
        advanceExactly(600)
        shoot("held by its title, the shelf lifts on a panel")
        touch { moveTo(from + (to - from) * 0.5f) }
        settle(150)
        touch { moveTo(to) }
        settle(500)
        shoot("dragged down, the shelves below make room")
        touch { up() }
        settle(1_200)
        shoot("dropped in its new place")
    }
}

/** Home in Channels mode: the board, moving around it, and carrying a channel. */
internal fun AuditDriver.homeChannels(exhaustive: Boolean) {
    scenario("home", "channels") {
        useLibrary { it.copy(home = HomeLayoutConfig(mode = HomeMode.CHANNELS, widgets = AuditSamples.channelBoard)) }
        waitFor("Continue playing")
        tap(PadButton.DPAD_LEFT)
        shoot("board, default focus", 2_000)
        if (!exhaustive) return@scenario
        tap(PadButton.DPAD_DOWN)
        shoot("board, second row focused")
        tap(PadButton.DPAD_RIGHT)
        shoot("board, next channel focused")
        hold(PadButton.A)
        waitFor("Put down")
        shoot("holding A picks the channel up")
        tap(PadButton.DPAD_RIGHT)
        shoot("carried channel moved right")
        tap(PadButton.A)
        shoot("put down")
    }
    scenario("home", "channels drag by touch") {
        useLibrary { it.copy(home = HomeLayoutConfig(mode = HomeMode.CHANNELS, widgets = AuditSamples.channelBoard)) }
        waitFor("Continue playing")
        // Hold the channel's tile, just above its name.
        val from = textCentre("Continue playing") + androidx.compose.ui.geometry.Offset(0f, -80f)
        // Past the middle of the next channel along, so it takes that place.
        val to = from + androidx.compose.ui.geometry.Offset(1_100f, 0f)
        touch { down(from) }
        advanceExactly(600)
        shoot("held, the channel floats over its shadow")
        touch { moveTo(from + (to - from) * 0.5f) }
        settle(150)
        touch { moveTo(to) }
        settle(500)
        shoot("dragged right, the others slide over")
        touch { up() }
        settle(1_200)
        shoot("dropped")
    }
    if (!exhaustive) return
    scenario("home", "channels default widgets") {
        useLibrary { it.copy(home = HomeLayoutConfig(mode = HomeMode.CHANNELS)) }
        waitFor("Continue playing")
        tap(PadButton.DPAD_LEFT)
        shoot("board with the default widget list")
    }
}

/** Home with no games: no folder yet, a folder with nothing in it, and a scan in progress. */
internal fun AuditDriver.homeEmpty() {
    scenario("home", "empty") {
        val store = runBlocking { AuditLibrary.emptyStore(cache, controls, scope) }
        show(store)
        waitFor("find your games")
        tap(PadButton.DPAD_LEFT)
        shoot("no games folder yet")
        tap(PadButton.DPAD_RIGHT)
        shoot("Run setup focused")

        tap(PadButton.R1)
        shoot("Library with no games")
        tap(PadButton.R1)
        shoot("Systems with no games")
        home()

        val empty = File(cache, "Empty Folder").apply { mkdirs() }
        runBlocking { store.sources.add(empty.absolutePath, LibrarySourceKind.ROMS_ROOT) }
        waitFor("No games found yet")
        shoot("a folder with nothing Fuse recognises")

        // Listing pauses after a few folders, so the scan is caught while it runs.
        controls.pauseListingAfter(6)
        try {
            runBlocking { store.sources.add(root.absolutePath, LibrarySourceKind.ROMS_ROOT) }
            pumpUntil("the scan to start") { store.sources.scan.value.phase in setOf(ScanPhase.DISCOVERING, ScanPhase.SCANNING) }
            waitFor("Looking for your games")
            shoot("while a scan is running")
        } finally {
            controls.resumeListing()
        }
        pumpUntil("the scan to finish", 60_000) { store.sources.scan.value.phase == ScanPhase.DONE && store.library.home.value.recentlyAdded.isNotEmpty() }
        shoot("right after the first scan", 2_500)
    }
}

/** Home's tabs, reached from Home: kept here so every group can go back to a known place. */
internal fun AuditDriver.libraryTab() = tab(Destination.LIBRARY)
