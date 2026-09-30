package io.github.matiyaaa.fuse.ui.shell.audit

import io.github.matiyaaa.fuse.model.CartridgeDownload
import io.github.matiyaaa.fuse.model.CartridgeStatus
import io.github.matiyaaa.fuse.model.Destination
import io.github.matiyaaa.fuse.model.LibraryLayout
import io.github.matiyaaa.fuse.model.MediaKind
import io.github.matiyaaa.fuse.model.MediaOwner
import io.github.matiyaaa.fuse.model.NavAction
import io.github.matiyaaa.fuse.model.PadButton
import io.github.matiyaaa.fuse.model.PlatformId
import io.github.matiyaaa.fuse.ui.designsystem.components.ToastKind
import io.github.matiyaaa.fuse.ui.designsystem.input.NavResult
import io.github.matiyaaa.fuse.ui.shell.store.GameQuery
import io.github.matiyaaa.fuse.ui.shell.store.RunResult
import java.io.File
import kotlin.time.Clock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

private fun LibraryLayout.words(): String = when (this) {
    LibraryLayout.ICON -> "Icon"
    LibraryLayout.CAPSULE -> "Capsule"
    LibraryLayout.COVER_GRID -> "Cover Grid"
    LibraryLayout.COMPACT_LIST -> "Compact List"
}

/** The number of game actions before the view actions in a library's options menu. */
private const val GAME_ACTIONS = 13

/**
 * Chooses a layout the way a user does: Options, "View as", then the layout. [shootChoice] also
 * captures the "View as" choice list.
 */
internal fun AuditDriver.viewAs(layout: LibraryLayout, shootChoice: Boolean = false) {
    tap(PadButton.X)
    waitFor("View as")
    choose(GAME_ACTIONS)
    waitFor("Cover grid")
    if (shootChoice) shoot("View as choice list open")
    choose(layout.ordinal)
    settle(600)
}

/** Opens the Library tab and moves to the filter chip at [index] (0 All, 1 Favourites, 2 Recently played, then systems). */
internal fun AuditDriver.libraryFilter(index: Int) {
    if (nav(NavAction.UP) != NavResult.MOVED) throw NotCovered("Up did not reach the filter chips")
    tap(PadButton.DPAD_RIGHT, index)
    tap(PadButton.DPAD_DOWN)
    settle(600)
}

private fun AuditDriver.platformIndex(id: String): Int =
    libraryStore.library.platforms.value.filter { it.gameCount > 0 }.indexOfFirst { it.platform.id == PlatformId(id) }
        .also { if (it < 0) throw NotCovered("System $id has no games") }

/** Search, typed on a hardware keyboard, then into the results. */
internal fun AuditDriver.search(query: String) {
    home()
    tap(PadButton.Y)
    waitFor("Games, systems, apps")
    type(query)
    settle(1_000)
}

/** A game's page, opened from Search through its options (Options, then Game Info). */
internal fun AuditDriver.openGame(title: String) {
    search(title)
    tap(PadButton.START)
    tap(PadButton.X)
    waitFor("Game Info")
    choose(2)
    waitFor("Last played")
    settle()
}

// ------------------------------------------------------------------------------------ library

internal fun AuditDriver.libraryScreens(exhaustive: Boolean) {
    scenario("library", "all") {
        useLibrary()
        libraryTab()
        waitFor("Recently played")
        tap(PadButton.DPAD_LEFT)
        shoot("Icon layout, first game focused (update and DLC badges)")
        for (layout in listOf(LibraryLayout.CAPSULE, LibraryLayout.COVER_GRID, LibraryLayout.COMPACT_LIST)) {
            viewAs(layout, shootChoice = exhaustive && layout == LibraryLayout.CAPSULE)
            shoot("${layout.words()} layout")
        }
        if (!exhaustive) return@scenario
        tap(PadButton.DPAD_DOWN, 4)
        shoot("Compact List layout, fifth game focused")
    }
    if (!exhaustive) return

    scenario("library", "favourites") {
        useLibrary()
        libraryTab()
        waitFor("Recently played")
        libraryFilter(1)
        for (layout in LibraryLayout.entries) {
            viewAs(layout)
            shoot("${layout.words()} layout")
        }
    }

    scenario("library", "recently played") {
        useLibrary()
        libraryTab()
        waitFor("Recently played")
        libraryFilter(2)
        shoot("Icon layout")
    }

    scenario("library", "badges") {
        useLibrary()
        libraryTab()
        waitFor("Recently played")
        libraryFilter(3 + platformIndex("psx"))
        tap(PadButton.DPAD_RIGHT)
        shoot("PS1 filter, multi-disc game focused (2 discs)")
        tap(PadButton.DPAD_RIGHT, 2)
        shoot("PS1 filter, multi-disc game focused (3 discs)")
        restartApp()
        libraryTab()
        waitFor("Recently played")
        viewAs(LibraryLayout.COVER_GRID)
        libraryFilter(3 + platformIndex("psx"))
        tap(PadButton.DPAD_RIGHT)
        shoot("PS1 filter, Cover Grid, multi-disc game focused")
    }

    scenario("library", "filter chips focused") {
        useLibrary()
        libraryTab()
        waitFor("Recently played")
        if (nav(NavAction.UP) != NavResult.MOVED) throw NotCovered("Up did not reach the filter chips")
        tap(PadButton.DPAD_RIGHT, 5)
        shoot("focus on a system chip")
    }

    scenario("library", "system") {
        useLibrary()
        tab(Destination.SYSTEMS)
        val grid = Grid(libraryStore.library.platforms.value.count { it.gameCount > 0 })
        grid.goTo(platformIndex("gba"))
        tap(PadButton.A)
        waitFor("Beacon Bay")
        for (layout in LibraryLayout.entries) {
            viewAs(layout)
            shoot("Game Boy Advance, ${layout.words()} layout")
        }
    }

    scenario("library", "system without emulator") {
        useLibrary()
        tab(Destination.SYSTEMS)
        val grid = Grid(libraryStore.library.platforms.value.count { it.gameCount > 0 })
        grid.goTo(platformIndex("psp"))
        tap(PadButton.A)
        waitFor("No emulator installed")
        shoot("PlayStation Portable, no emulator installed")
    }

    scenario("library", "collection") {
        useLibrary()
        search("Long Adventures")
        tap(PadButton.START)
        tap(PadButton.A)
        waitFor("Glasswing Requiem")
        for (layout in LibraryLayout.entries) {
            viewAs(layout)
            shoot("Long Adventures, ${layout.words()} layout")
        }
    }

    scenario("library", "empty collection") {
        useLibrary()
        search(AuditLibrary.EMPTY_COLLECTION)
        tap(PadButton.START)
        tap(PadButton.A)
        waitFor("This collection is empty")
        shoot("empty filter result")
    }
}

// ------------------------------------------------------------------------------------ systems

internal fun AuditDriver.systemsScreens(exhaustive: Boolean) {
    scenario("systems", "grid") {
        useLibrary()
        tab(Destination.SYSTEMS)
        waitFor("System options")
        val systems = libraryStore.library.platforms.value.filter { it.gameCount > 0 }
        tap(PadButton.DPAD_LEFT)
        shoot("default focus, ${systems.first().platform.shortName}")
        if (!exhaustive) return@scenario
        val grid = Grid(systems.size)
        for ((id, words) in listOf(
            "psx" to "PS1, firmware missing",
            "ps2" to "PS2, firmware ready",
            "psp" to "PSP, no emulator installed",
            "dc" to "Dreamcast, firmware partly found",
            "switch" to "Switch, firmware unknown (kept inside the emulator)",
        )) {
            grid.goTo(platformIndex(id))
            shoot(words)
        }
        grid.goTo(systems.lastIndex)
        shoot("last system focused")
    }
    scenario("systems", "grid with art") {
        useLibrary()
        // Pack-style art for a few systems (the pack itself is downloaded, so the audit draws its own).
        val dir = File(cache, "system-art").apply { mkdirs() }
        val systems = libraryStore.library.platforms.value.filter { it.gameCount > 0 }
        val withArt = systems.take(4)
        runBlocking {
            for (card in withArt) {
                val owner = MediaOwner.OfPlatform(card.platform.id)
                val panel = File(dir, "${card.platform.id.value}-panel.png").also { AuditSystemArt.panel(it, card.platform.accent) }
                val logo = File(dir, "${card.platform.id.value}-logo.png").also { AuditSystemArt.logo(it, card.platform.shortName) }
                libraryStore.media.setFromFile(owner, MediaKind.BOXART, panel.absolutePath)
                libraryStore.media.setFromFile(owner, MediaKind.LOGO, logo.absolutePath)
            }
        }
        try {
            tab(Destination.SYSTEMS)
            waitFor("System options")
            tap(PadButton.DPAD_LEFT)
            settle(1_200)
            shoot("art panel behind the grid, ${systems.first().platform.shortName}")
            tap(PadButton.DPAD_RIGHT, withArt.size)
            settle(1_200)
            shoot("a system without art after ones with art")
            hold(PadButton.A)
            settle(600)
            shoot("carrying a system")
            tap(PadButton.B)
        } finally {
            runBlocking { withArt.forEach { libraryStore.media.reset(MediaOwner.OfPlatform(it.platform.id), null) } }
        }
    }
    if (!exhaustive) return

    scenario("systems", "options menu") {
        useLibrary()
        tab(Destination.SYSTEMS)
        waitFor("System options")
        Grid(libraryStore.library.platforms.value.count { it.gameCount > 0 }).goTo(platformIndex("psp"))
        tap(PadButton.X)
        waitFor("System Settings")
        shoot("PSP options (no emulator installed)")
        choose(4)
        waitFor("Emulator for")
        shoot("emulator picker, nothing installed")
        tap(PadButton.B)
        tap(PadButton.X)
        waitFor("System Settings")
        tap(PadButton.DPAD_DOWN, 6)
        shoot("ROM folders row focused")
    }

    scenario("systems", "platform settings") {
        useLibrary()
        tab(Destination.SYSTEMS)
        waitFor("System options")
        tap(PadButton.X)
        waitFor("System Settings")
        choose(1)
        waitFor("Disc playlists")
        shoot("PS1, top")
        tap(PadButton.A)
        waitFor("Games use this unless")
        shoot("emulator choice list")
        tap(PadButton.B)
        tap(PadButton.DPAD_DOWN)
        tap(PadButton.A)
        waitFor("For this system only")
        shoot("inherited setting choice (Folder behaviour)")
        tap(PadButton.B)
        tap(PadButton.DPAD_DOWN, 10)
        shoot("BIOS and firmware row (missing)")
        tap(PadButton.DPAD_DOWN, 8)
        shoot("last rows")
    }

    scenario("systems", "platform settings switch") {
        useLibrary()
        tab(Destination.SYSTEMS)
        waitFor("System options")
        Grid(libraryStore.library.platforms.value.count { it.gameCount > 0 }).goTo(platformIndex("switch"))
        tap(PadButton.X)
        waitFor("System Settings")
        choose(1)
        waitFor("Disc playlists")
        tap(PadButton.DPAD_DOWN, 11)
        shoot("Switch, BIOS row (can't check)")
    }

    scenario("media", "system") {
        useLibrary()
        tab(Destination.SYSTEMS)
        waitFor("System options")
        tap(PadButton.X)
        waitFor("System Settings")
        choose(2)
        waitFor("Manage media")
        shoot("PS1 system media, Icon slot")
        tap(PadButton.DPAD_DOWN, 3)
        shoot("Background slot focused")
    }
}

// ---------------------------------------------------------------------------------- game page

internal fun AuditDriver.gameScreens(exhaustive: Boolean) {
    scenario("game", "multi-disc") {
        useLibrary()
        openGame("Hollow Meridian")
        shoot("default focus on Play")
        if (!exhaustive) return@scenario
        tap(PadButton.DPAD_DOWN)
        shoot("discs row focused")
    }
    if (!exhaustive) return

    scenario("game", "single file") {
        useLibrary()
        openGame("Emberline Saga")
        shoot("default focus on Play")
    }

    scenario("game", "dlc and updates") {
        useLibrary()
        openGame("Aurora Outpost")
        shoot("default focus on Play")
        tap(PadButton.DPAD_RIGHT, 5)
        shoot("More button focused")
    }

    scenario("game", "no emulator installed") {
        useLibrary()
        openGame("Waystation Nine")
        shoot("PSP game, nothing can run it")
    }

    scenario("game", "emulator only opens the app") {
        useLibrary()
        openGame(AuditLibrary.OPENS_APP_ONLY)
        shoot("set to Citron, which Fuse can only open")
    }

    scenario("game", "options menu") {
        useLibrary()
        openGame("Aurora Outpost")
        tap(PadButton.X)
        waitFor("Folder Behaviour")
        shoot("options menu from the game page")
        tap(PadButton.DPAD_DOWN, 11)
        shoot("options menu, last row (Remove from Fuse)")
    }

    scenario("game", "emulator picker") {
        useLibrary()
        openGame("Aurora Outpost")
        tap(PadButton.DPAD_RIGHT)
        tap(PadButton.A)
        waitFor("Use the platform's emulator")
        shoot("choice list")
        tap(PadButton.DPAD_DOWN, 3)
        shoot("an emulator that is not installed focused")
    }

    scenario("game", "collection picker") {
        useLibrary()
        openGame("Emberline Saga")
        tap(PadButton.DPAD_RIGHT, 3)
        tap(PadButton.A)
        waitFor("New Collection")
        shoot("choice list with the game's collections checked")
    }

    scenario("game", "folder behaviour picker") {
        useLibrary()
        openGame("Emberline Saga")
        tap(PadButton.X)
        waitFor("Folder Behaviour")
        choose(8)
        waitFor("Nothing on disk changes")
        shoot("choice list")
    }

    scenario("game", "rename") {
        useLibrary()
        openGame("Emberline Saga")
        tap(PadButton.X)
        waitFor("Rename Display Title")
        choose(7)
        waitFor("Display title")
        shoot("text input with the on-screen keyboard")
        tap(PadButton.X, 5)
        tap(PadButton.DPAD_DOWN)
        tap(PadButton.DPAD_RIGHT, 2)
        tap(PadButton.A)
        shoot("typing with the controller")
        tap(PadButton.B)
    }

    scenario("game", "remove confirm") {
        useLibrary()
        openGame("Emberline Saga")
        tap(PadButton.X)
        waitFor("Remove from Fuse")
        choose(11)
        waitFor("stay exactly where they are")
        shoot("destructive confirm dialog, Cancel focused")
        tap(PadButton.DPAD_RIGHT)
        shoot("confirm button focused")
        tap(PadButton.B)
    }

    scenario("media", "game") {
        useLibrary()
        // From the game's options: Play, then Manage Media.
        search("Hollow Meridian")
        tap(PadButton.START)
        tap(PadButton.X)
        waitFor("Game Info")
        choose(1)
        waitFor("Manage media")
        shoot("Search as row")
        tap(PadButton.A)
        settle(600)
        shoot("Search as keyboard")
        tap(PadButton.B)
        tap(PadButton.DPAD_DOWN)
        shoot("Identify game row")
        tap(PadButton.A)
        settle(1_500)
        shoot("Identify game with no source set up")
        tap(PadButton.DPAD_DOWN)
        shoot("Icon slot")
        tap(PadButton.DPAD_DOWN)
        tap(PadButton.A)
        waitFor("Choose a file")
        shoot("Cover slot actions")
        choose(0)
        settle(1_500)
        shoot("Find cover with no art source set up")
        tap(PadButton.X)
        waitFor("Fill art")
        shoot("fill art choice")
    }

    gap("game", "folder browser", "*", "Unreachable: nothing in the app pushes Route.FolderBrowser. A game whose folder behaviour is " +
        "\"Open as a folder\" launches the folder directly instead of opening the browser.")
    gap("game", "achievements and screenshots rows", "*", "Needs RetroAchievements data and scraped screenshots; the audit services have no network and no art.")
}

// ------------------------------------------------------------------------------------- launch

internal fun AuditDriver.launchScreens() {
    scenario("launch", "veil") {
        useLibrary()
        waitFor("Continue playing")
        tap(PadButton.DPAD_LEFT)
        settle()
        val gate = CompletableDeferred<RunResult>()
        controls.launchGate = gate
        try {
            tap(PadButton.A)
            waitFor("Starting")
            shoot("launch veil while the emulator starts", 1_800)
        } finally {
            gate.complete(RunResult.Failed("Flycast closed right away (exit code 1). Check that it runs on its own."))
            controls.launchGate = null
        }
        waitFor("Flycast closed right away")
        shoot("error toast after the launch failed", 700)
    }

    scenario("launch", "needs emulator") {
        useLibrary()
        openGame("Waystation Nine")
        tap(PadButton.A)
        waitFor("No emulator for")
        shoot("choice dialog with emulators to install")
    }

    scenario("launch", "opened app only") {
        useLibrary()
        openGame(AuditLibrary.OPENS_APP_ONLY)
        tap(PadButton.A)
        waitFor("only opens it")
        shoot("info toast after opening Citron", 900)
    }

    scenario("overlays", "toasts") {
        useLibrary()
        waitFor("Continue playing")
        tap(PadButton.DPAD_LEFT)
        tap(PadButton.X)
        waitFor("Pin to Home")
        shoot("game options menu on Home")
        choose(4)
        waitFor("Pinned to Home")
        shoot("info toast", 700)
        settle(3_500)
        // No code path in the shell shows a success or warning toast; they are drawn by the same
        // ToastHost over the live app.
        extraToasts.show("Added to Couch Racers", ToastKind.SUCCESS)
        shoot("success toast (design system host over the app)", 700)
        settle(3_500)
        extraToasts.show("Low Power Mode is on: video previews and blur are off.", ToastKind.WARNING)
        shoot("warning toast (design system host over the app)", 700)
        settle(3_500)
        val velvet = runBlocking { libraryStore.library.games(GameQuery()).first().first { it.title == "Velvet Orbit" } }
        runBlocking { libraryStore.library.setPinned(velvet.id, false) }
    }
}

// ------------------------------------------------------------------------------------- search

internal fun AuditDriver.searchScreens(exhaustive: Boolean) {
    scenario("search", "results") {
        useLibrary()
        search("station")
        waitFor("Station Radio")
        shoot("results in every category (game, systems, app, collection)")
        if (!exhaustive) return@scenario
        tap(PadButton.START)
        tap(PadButton.DPAD_DOWN, 2)
        shoot("results focused")
    }
    if (!exhaustive) return

    scenario("search", "empty") {
        useLibrary()
        search("")
        tap(PadButton.DPAD_LEFT)
        shoot("nothing typed yet")
    }

    scenario("search", "on-screen keyboard") {
        useLibrary()
        search("")
        tap(PadButton.DPAD_RIGHT, 9)
        tap(PadButton.A)
        tap(PadButton.DPAD_LEFT)
        tap(PadButton.A)
        shoot("typed po with the controller")
        tap(PadButton.DPAD_DOWN, 3)
        shoot("focus on the bottom row keys")
    }

    for ((query, words) in listOf(
        "Emberline" to "games",
        "Nintendo" to "systems",
        "Radio" to "apps",
        "Couch" to "collections",
        "Lantern" to "games and apps",
    )) {
        scenario("search", "results $words") {
            useLibrary()
            search(query)
            shoot("\"$query\"")
        }
    }

    scenario("search", "no results") {
        useLibrary()
        search("zzqx")
        waitFor("Nothing matches")
        shoot("nothing matches")
    }
}

// --------------------------------------------------------------------------------------- apps

internal fun AuditDriver.appsScreens(exhaustive: Boolean) {
    scenario("apps", "grid") {
        useLibrary()
        tab(Destination.APPS)
        waitFor("Starfall Arena")
        tap(PadButton.DPAD_LEFT)
        shoot("Games filter (default)")
        if (!exhaustive) return@scenario
        if (nav(NavAction.UP) != NavResult.MOVED) throw NotCovered("Up did not reach the filters")
        tap(PadButton.DPAD_LEFT)
        tap(PadButton.DPAD_DOWN)
        shoot("Pinned")
        if (nav(NavAction.UP) != NavResult.MOVED) throw NotCovered("Up did not reach the filters")
        tap(PadButton.DPAD_RIGHT, 2)
        shoot("filters focused, All apps")
        tap(PadButton.DPAD_DOWN)
        shoot("All apps")
        tap(PadButton.DPAD_RIGHT, 2)
        tap(PadButton.X)
        waitFor("App Info")
        shoot("app options menu")
    }
}

// ---------------------------------------------------------------------------------- cartridge

internal fun AuditDriver.cartridgeReady(): CartridgeStatus {
    val now = Clock.System.now().toEpochMilliseconds()
    fun path(folder: String, file: String) = File(root, "$folder/$file").absolutePath
    return CartridgeStatus(
        installed = true,
        version = "0.9.12",
        bridge = true,
        connected = true,
        activeDownloads = 1,
        queuedDownloads = 2,
        progress = 0.42f,
        currentTitle = "Kestrel Nine",
        currentPlatform = "genesis",
        recent = listOf(
            CartridgeDownload(101, "Brightwater Farm", "switch", path("switch", "Brightwater Farm.nsp"), now - 25 * 60_000L),
            CartridgeDownload(102, "Starfold Academy", "ngc", path("ngc", "Starfold Academy.rvz"), now - 3 * 3_600_000L),
            CartridgeDownload(103, "Sprout Squad", "gba", path("gba", "Sprout Squad.gba"), now - 27 * 3_600_000L),
            CartridgeDownload(104, "Comet Courier", "snes", null, now - 2 * 60_000L),
        ),
    )
}

internal fun AuditDriver.setCartridge(status: CartridgeStatus) {
    controls.cartridge = status
    libraryStore.cartridge.refresh()
    pumpUntil("Cartridge's status to update") { libraryStore.cartridge.status.value == status }
    settle(600)
}

internal fun AuditDriver.cartridgeScreens(exhaustive: Boolean) {
    scenario("cartridge", "installed with downloads") {
        useLibrary()
        setCartridge(cartridgeReady())
        tab(Destination.CARTRIDGE)
        waitFor("Ready to play")
        tap(PadButton.DPAD_LEFT)
        shoot("active download and recent downloads matched to games")
        if (!exhaustive) return@scenario
        tap(PadButton.DPAD_DOWN)
        shoot("recent download focused")
        tap(PadButton.DPAD_DOWN, 3)
        shoot("download not in the library yet focused")
    }
    if (!exhaustive) return

    scenario("cartridge", "not installed") {
        useLibrary()
        tab(Destination.CARTRIDGE)
        waitFor("How it works")
        tap(PadButton.DPAD_LEFT)
        shoot("not installed")
        tap(PadButton.A)
        waitFor("Couldn't reach GitHub")
        shoot("Install pressed while offline", 700)
    }

    scenario("cartridge", "installed without bridge") {
        useLibrary()
        setCartridge(CartridgeStatus(installed = true, version = "0.9.4", bridge = false))
        tab(Destination.CARTRIDGE)
        waitFor("0.9.10 or newer")
        tap(PadButton.DPAD_LEFT)
        shoot("older Cartridge without the bridge")
    }

    scenario("cartridge", "not connected") {
        useLibrary()
        setCartridge(CartridgeStatus(installed = true, version = "0.9.12", bridge = true, connected = false))
        tab(Destination.CARTRIDGE)
        waitFor("Not connected")
        tap(PadButton.DPAD_LEFT)
        shoot("installed, RomM server not reachable")
    }

    scenario("home", "flow cartridge widget") {
        useLibrary()
        setCartridge(cartridgeReady())
        restartApp()
        waitFor("Continue playing")
        val shelves = libraryStore.shelvesNow()
        val glance = shelves.indexOfFirst { it.title == "At a glance" }
        if (glance < 0) throw NotCovered("No At a glance shelf")
        tap(PadButton.DPAD_DOWN, glance)
        tap(PadButton.DPAD_RIGHT, 1)
        shoot("Cartridge widget with an active download")
    }
}
