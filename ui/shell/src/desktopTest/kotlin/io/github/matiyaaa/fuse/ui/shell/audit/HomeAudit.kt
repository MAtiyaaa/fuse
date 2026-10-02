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

/** Every kind of Home row in one Flow layout (the widgets that aren't rows are skipped, as on a device). */
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
                    shoot("${shelf.title}, ${kind.title()} widget focused")
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
        // Holding A on a shelf of games picks the whole shelf up; up and down place it.
        tap(PadButton.DPAD_LEFT)
        hold(PadButton.A)
        waitFor("Up and down to place it")
        shoot("holding A on a shelf of games picks the shelf up")
        tap(PadButton.DPAD_DOWN)
        shoot("shelf moved down one place")
        tap(PadButton.A)
        waitGone("Up and down to place it")
        shoot("put down")
        // On the Systems shelf, holding A picks up just that system; left and right place it.
        focusHint("Hold to move") { tap(PadButton.DPAD_UP) }
        hold(PadButton.A)
        if (runCatching { waitFor("Left and right to place it", 3_000) }.isFailure) {
            // Recorded, so a hold that stops working shows in the manifest, then reached the other way.
            gap("home", "flow arranging", "holding A on a system", "Holding A on the Systems shelf did not pick the system up; it was moved from its options instead")
            tap(PadButton.X)
            tapText("Move this system")
            waitFor("Left and right to place it")
        }
        shoot("a system picked up")
        tap(PadButton.DPAD_RIGHT)
        shoot("system moved right one place")
        tap(PadButton.A)
        waitGone("Left and right to place it")
        shoot("system put down")
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

/** Home in Channels mode: the widget board, arranging it with the controller and by touch, and every widget at every size. */
internal fun AuditDriver.homeChannels(exhaustive: Boolean) {
    scenario("home", "board") {
        useLibrary { it.copy(home = HomeLayoutConfig(mode = HomeMode.CHANNELS)) }
        waitFor("Continue playing")
        tap(PadButton.DPAD_LEFT)
        shoot("the board as it comes", 2_000)
        if (!exhaustive) return@scenario
        tap(PadButton.DPAD_RIGHT)
        shoot("clock focused")
        tap(PadButton.DPAD_DOWN, 2)
        shoot("further down, the board scrolls")
        tap(PadButton.DPAD_UP, 2)
        tap(PadButton.DPAD_LEFT)
        // Arranging with the controller: hold A to pick up, the D-pad to move.
        hold(PadButton.A)
        waitFor("Put down")
        shoot("holding A arranges the board and picks the widget up")
        tap(PadButton.DPAD_RIGHT)
        shoot("carried widget moved one place on")
        tap(PadButton.A)
        shoot("put down, still arranging")
        // Hold Options (X): the chosen widget turns to resizing; the D-pad grows it while held.
        router.press(PadButton.X, io.github.matiyaaa.fuse.ui.designsystem.input.InputSource.GAMEPAD)
        settle(400)
        shoot("holding X, the widget shows it can be resized")
        tap(PadButton.DPAD_RIGHT)
        settle(300)
        tap(PadButton.DPAD_DOWN)
        shoot("X held with right and down, the others make room")
        tap(PadButton.DPAD_LEFT, 3)
        shoot("shrunk to its smallest, a further press bumps")
        router.release(PadButton.X, io.github.matiyaaa.fuse.ui.designsystem.input.InputSource.GAMEPAD)
        settle(300)
        shoot("let go, the new size kept and still arranging")
        tap(PadButton.B)
        shoot("done arranging")
    }
    scenario("home", "board by touch") {
        useLibrary { it.copy(home = HomeLayoutConfig(mode = HomeMode.CHANNELS)) }
        waitFor("Continue playing")
        // Hold a widget: the board starts arranging and the widget lifts under the finger.
        val from = textCentre("STORAGE") + androidx.compose.ui.geometry.Offset(0f, 60f)
        touch { down(from) }
        advanceExactly(700)
        shoot("held, the board arranges and the widget lifts")
        val to = from + androidx.compose.ui.geometry.Offset(-700f, 330f)
        touch { moveTo(from + (to - from) * 0.5f) }
        settle(150)
        touch { moveTo(to) }
        settle(500)
        shoot("dragged, the others spring to their new places")
        touch { up() }
        settle(1_200)
        shoot("dropped, the board still arranging")
        // Drag a corner to resize: the size follows the finger cell by cell.
        touch { down(textCentre("STORAGE")); up() }
        settle(400)
        shoot("chosen while arranging, its handles show")
        val grip = describedBounds("Resize Storage from its corner").first().center
        touch { down(grip) }
        settle(100)
        touch { moveTo(grip + androidx.compose.ui.geometry.Offset(150f, 120f)) }
        settle(150)
        touch { moveTo(grip + androidx.compose.ui.geometry.Offset(330f, 290f)) }
        settle(600)
        shoot("a corner dragged, the widget grows a cell each way")
        touch { moveTo(grip + androidx.compose.ui.geometry.Offset(2_000f, 290f)) }
        settle(600)
        shoot("dragged past the edge, it stops at the largest that fits")
        touch { up() }
        settle(1_000)
        shoot("let go, the new size kept")
    }
}

/**
 * Every widget at every size its face is designed for: one cell, a strip, a column, a square of
 * four, and the large shapes (three by two, four by one, four by three, two by three).
 */
internal fun AuditDriver.widgetGallery() {
    val pages = listOf(
        "small, wide, tall and square" to listOf(1 to 1, 2 to 1, 1 to 2, 2 to 2),
        "full strip, three by two and a column" to listOf(4 to 1, 3 to 2, 1 to 2),
        "two by three and four by two" to listOf(2 to 3, 2 to 2, 2 to 1),
        "four by three" to listOf(4 to 3),
    )
    for (kind in WidgetKind.entries) {
        scenario("widgets", kind.title()) {
            useLibrary { it.copy(home = HomeLayoutConfig(mode = HomeMode.CHANNELS, board = emptyList())) }
            controls.cartridge = AuditSamples.cartridgeBusy
            libraryStore.cartridge.refresh()
            for ((name, sizes) in pages) {
                val board = sizes.mapIndexed { i, (w, h) -> HomeWidget("${kind.name.lowercase()}$i", kind, i, width = w, height = h) }
                libraryStore.updatePrefs { it.copy(home = HomeLayoutConfig(mode = HomeMode.CHANNELS, board = board)) }
                settle(1_500)
                tap(PadButton.DPAD_LEFT)
                shoot(name, 1_500)
            }
        }
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

        tab(Destination.LIBRARY)
        shoot("Library with no games")
        tab(Destination.SYSTEMS)
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
