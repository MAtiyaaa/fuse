package io.github.matiyaaa.fuse.ui.shell.audit

import io.github.matiyaaa.fuse.model.CartridgeDownload
import io.github.matiyaaa.fuse.model.CartridgeQueueItem
import io.github.matiyaaa.fuse.model.CartridgeStatus
import io.github.matiyaaa.fuse.model.CartridgeUploadItem
import io.github.matiyaaa.fuse.model.Destination
import io.github.matiyaaa.fuse.model.LibraryLayout
import io.github.matiyaaa.fuse.model.MediaKind
import io.github.matiyaaa.fuse.model.MediaOwner
import io.github.matiyaaa.fuse.model.NavAction
import io.github.matiyaaa.fuse.model.PadButton
import io.github.matiyaaa.fuse.model.PlatformId
import io.github.matiyaaa.fuse.model.QueueState
import io.github.matiyaaa.fuse.model.ScanScope
import io.github.matiyaaa.fuse.model.UploadState
import io.github.matiyaaa.fuse.ui.designsystem.components.ToastKind
import io.github.matiyaaa.fuse.ui.designsystem.input.NavResult
import io.github.matiyaaa.fuse.ui.shell.store.GameQuery
import io.github.matiyaaa.fuse.ui.shell.store.GameSet
import io.github.matiyaaa.fuse.ui.shell.store.RunResult
import java.io.File
import kotlin.time.Clock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

private fun LibraryLayout.words(): String = when (this) {
    LibraryLayout.ICON -> "Grid"
    LibraryLayout.CAPSULE -> "Capsule"
    LibraryLayout.COVER_GRID -> "Cover Grid"
    LibraryLayout.COMPACT_LIST -> "Compact List"
}


/**
 * Chooses a layout the way a user does: Options, "View as", then the layout. [shootChoice] also
 * captures the "View as" choice list.
 */
internal fun AuditDriver.viewAs(layout: LibraryLayout, shootChoice: Boolean = false) {
    tap(PadButton.X)
    // "View as" sits below the game actions, often past the bottom of the menu.
    waitFor("Manage Media")
    tapText("View as")
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
    tapText("Game Info")
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
        // Past the first row the stage folds and the logo rises, leaving three rows.
        tap(PadButton.DPAD_DOWN, 2)
        shoot("Icon layout, scrolled, stage folded", 1_500)
        tap(PadButton.DPAD_UP, 2)
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

    scenario("library", "system folding") {
        useLibrary()
        // Enough Super Nintendo games for three rows, so there are rows to move down.
        val snes = File(root, "snes")
        val extra = (1..14).map { File(snes, "Folding Test ${'A' + it - 1}.sfc").apply { writeBytes(ByteArray(512)) } }
        try {
            libraryStore.sources.rescan(ScanScope.PLATFORM, PlatformId("snes"))
            pumpUntil("the extra games") { libraryStore.library.platforms.value.firstOrNull { it.platform.id == PlatformId("snes") }?.gameCount == 20 }
            val systems = libraryStore.library.platforms.value.filter { it.gameCount > 0 }
            tab(Destination.SYSTEMS)
            Grid(systems.size).goTo(platformIndex("snes"))
            tap(PadButton.A)
            waitFor("20 games")
            viewAs(LibraryLayout.ICON)
            settle(900)
            shoot("Super Nintendo, first row")
            tap(PadButton.DPAD_DOWN)
            settle(900)
            shoot("Super Nintendo, second row: the header folded away")
            tap(PadButton.DPAD_DOWN)
            settle(900)
            shoot("Super Nintendo, third row")
            tap(PadButton.DPAD_UP, 2)
            settle(900)
            shoot("Super Nintendo, back on the first row")
        } finally {
            // Gone from disk, then forgotten, so later screens see the library as it was.
            extra.forEach { it.delete() }
            libraryStore.sources.rescan(ScanScope.PLATFORM, PlatformId("snes"))
            pumpUntil("the extra games to go") { libraryStore.library.platforms.value.firstOrNull { it.platform.id == PlatformId("snes") }?.gameCount == 6 }
            runBlocking {
                val missing = withTimeout(10_000) { libraryStore.library.games(GameQuery(set = GameSet.MISSING)).first { list -> list.count { it.title.startsWith("Folding Test") } == extra.size } }
                missing.filter { it.title.startsWith("Folding Test") }.forEach { libraryStore.library.forgetMissing(it.id) }
            }
        }
    }

    scenario("library", "box art") {
        useLibrary()
        // One game each with square box art, only an icon, only a portrait cover, and nothing.
        val gba = runBlocking { libraryStore.library.games(GameQuery()).first().filter { it.platformId == PlatformId("gba") }.sortedBy { it.title } }
        val dir = File(cache, "box-art").apply { mkdirs() }
        runBlocking {
            gba.getOrNull(0)?.let { g ->
                val f = File(dir, "square.png").also { AuditCovers.square(it, g.title, g.accent) }
                libraryStore.media.setFromFile(MediaOwner.OfGame(g.id), MediaKind.SQUARE, f.absolutePath)
            }
            gba.getOrNull(1)?.let { g ->
                val f = File(dir, "icon.png").also { AuditIcons.write(it, g.title, g.accent, 1) }
                libraryStore.media.setFromFile(MediaOwner.OfGame(g.id), MediaKind.ICON, f.absolutePath)
            }
            gba.getOrNull(2)?.let { g ->
                val f = File(dir, "cover.png").also { AuditCovers.cover(it, g.title, g.accent) }
                libraryStore.media.setFromFile(MediaOwner.OfGame(g.id), MediaKind.BOXART, f.absolutePath)
            }
        }
        tab(Destination.SYSTEMS)
        val grid = Grid(libraryStore.library.platforms.value.count { it.gameCount > 0 })
        grid.goTo(platformIndex("gba"))
        tap(PadButton.A)
        waitFor("Beacon Bay")
        settle(1_200)
        shoot("Box art, icon, cover only and no art")
        viewAs(LibraryLayout.COMPACT_LIST)
        settle(800)
        shoot("The same games as a list")
    }

    scenario("library", "system with art") {
        useLibrary()
        val gba = libraryStore.library.platforms.value.first { it.platform.id == PlatformId("gba") }
        val owner = MediaOwner.OfPlatform(gba.platform.id)
        val dir = File(cache, "system-art").apply { mkdirs() }
        val panel = File(dir, "gba-panel.png").also { AuditSystemArt.panel(it, gba.platform.accent) }
        val logo = File(dir, "gba-logo.png").also { AuditSystemArt.logo(it, gba.platform.shortName) }
        runBlocking {
            libraryStore.media.setFromFile(owner, MediaKind.BOXART, panel.absolutePath)
            libraryStore.media.setFromFile(owner, MediaKind.LOGO, logo.absolutePath)
        }
        try {
            tab(Destination.SYSTEMS)
            val grid = Grid(libraryStore.library.platforms.value.count { it.gameCount > 0 })
            grid.goTo(platformIndex("gba"))
            settle(1_200)
            shoot("Systems with the Game Boy Advance focused")
            tap(PadButton.A)
            waitFor("Beacon Bay")
            settle(1_200)
            shoot("its page keeps the logo and the art panel")
            tap(PadButton.DPAD_RIGHT)
            settle(1_200)
            shoot("the next game, same background")
        } finally {
            runBlocking { libraryStore.media.reset(owner, null) }
        }
    }

    scenario("collections", "grid") {
        useLibrary()
        tab(Destination.LIBRARY)
        waitFor("All")
        if (nav(NavAction.UP) != NavResult.MOVED) throw NotCovered("Up did not reach the Library header")
        tapText("Collections", step = PadButton.DPAD_RIGHT)
        waitFor("New collection")
        settle(1_200)
        shoot("your collections, the first one focused")
        tap(PadButton.X)
        waitFor("Add or remove games")
        shoot("options for a collection")
        tapText("Add or remove games")
        waitFor("Select games to add")
        shoot("picking its games")
        tap(PadButton.B)
        waitFor("New collection")
        // Up from the first row reaches the views; Right shows the series.
        tap(PadButton.DPAD_LEFT, 3)
        if (nav(NavAction.UP) != NavResult.MOVED) throw NotCovered("Up did not reach the Collections views")
        shoot("the views focused")
        // Along the header to New collection, at the end of the line.
        tap(PadButton.DPAD_RIGHT, 2)
        shoot("new collection focused")
        tap(PadButton.A)
        waitFor("Collection name")
        shoot("naming a new collection")
        tap(PadButton.B)
        settle(800)
        tap(PadButton.DPAD_LEFT)
        tap(PadButton.DPAD_DOWN)
        settle(1_200)
        shoot("the series view")
    }

    scenario("library", "system without emulator") {
        useLibrary()
        tab(Destination.SYSTEMS)
        val systems = libraryStore.library.platforms.value.filter { it.gameCount > 0 }
        val bare = systems.firstOrNull { !it.emulatorInstalled } ?: throw NotCovered("Every system in the audit library has an emulator")
        val grid = Grid(systems.size)
        grid.goTo(platformIndex(bare.platform.id.value))
        tap(PadButton.A)
        waitFor("No emulator installed")
        shoot("${bare.platform.name}, no emulator installed")
    }

    scenario("library", "collection") {
        useLibrary()
        search("Long Adventures")
        tap(PadButton.START)
        tap(PadButton.A)
        // A collection of the user's own can be edited from its header.
        waitFor("Add or remove games")
        settle(1_000)
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
        waitFor("Arrange")
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
            waitFor("Arrange")
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
            tap(PadButton.B)
        } finally {
            runBlocking { withArt.forEach { libraryStore.media.reset(MediaOwner.OfPlatform(it.platform.id), null) } }
        }
    }
    scenario("systems", "move by touch") {
        useLibrary()
        tab(Destination.SYSTEMS)
        waitFor("Arrange")
        val systems = libraryStore.library.platforms.value.filter { it.gameCount > 0 }
        // Home stays composed behind the tab with its own system names: the cards are the topmost.
        val from = textCentre(systems[0].platform.shortName, topmost = true)
        val to = textCentre(systems[2].platform.shortName, topmost = true)
        touch { down(from) }
        advanceExactly(800)
        shoot("held, lifted under the finger")
        touch { moveTo(from + (to - from) * 0.5f) }
        settle(150)
        touch { moveTo(to) }
        settle(500)
        shoot("dragged over the third system, the others make room")
        touch { up() }
        settle(1_200)
        shoot("dropped in its new place")
        shoot("still arranging, the bar to add a system or finish")
        tap(PadButton.B)
    }
    scenario("systems", "arranged") {
        useLibrary()
        val systems = libraryStore.library.platforms.value.filter { it.gameCount > 0 }
        val before = libraryStore.prefs.value.systemsBoard
        fun tile(i: Int, w: Int, h: Int) = io.github.matiyaaa.fuse.model.HomeWidget("system.${systems[i].platform.id.value}", io.github.matiyaaa.fuse.model.WidgetKind.SYSTEMS, i, target = systems[i].platform.id.value, width = w, height = h)
        // The first system large, the second wide, the rest one card each; two on a second page.
        val first = systems.indices.filter { it < systems.size - 2 }.map { i -> when (i) { 0 -> tile(i, 2, 2); 1 -> tile(i, 2, 1); else -> tile(i, 1, 1) } }
        val second = listOf(tile(systems.size - 2, 1, 1), tile(systems.size - 1, 2, 1))
        libraryStore.updatePrefs { it.copy(systemsBoard = io.github.matiyaaa.fuse.model.HomeLayoutConfig(board = first, pages = listOf(io.github.matiyaaa.fuse.model.HomePage("page2", second)))) }
        try {
            tab(Destination.SYSTEMS)
            waitFor("Arrange")
            settle(1_000)
            shoot("a large system, a wide one and the rest, on the first of two pages")
            nav(NavAction.PAGE_NEXT)
            settle(1_000)
            shoot("the second page")
            nav(NavAction.PAGE_PREVIOUS)
            hold(PadButton.A)
            settle(800)
            shoot("arranging: carrying the chosen system")
            tap(PadButton.B)
            settle(400)
            shoot("arranging: handles, remove badges, Undo and Reset")
            tap(PadButton.B)
        } finally {
            libraryStore.updatePrefs { it.copy(systemsBoard = before) }
        }
    }

    scenario("systems", "editing on a two-screen handheld") {
        useLibrary()
        // As a board made larger twice came back through Fuse Sync from a device on an older version.
        val systems = libraryStore.library.platforms.value.filter { it.gameCount > 0 }
        val before = libraryStore.prefs.value.systemsBoard
        libraryStore.updatePrefs {
            it.copy(systemsBoard = io.github.matiyaaa.fuse.model.HomeLayoutConfig(
                board = systems.mapIndexed { i, c -> io.github.matiyaaa.fuse.model.HomeWidget("system.${c.platform.id.value}", io.github.matiyaaa.fuse.model.WidgetKind.SYSTEMS, i, target = c.platform.id.value, width = if (i == 2) 6 else 9, height = 4) },
                grain = 3,
            ))
        }
        show(libraryStore, twoScreens)
        tab(Destination.SYSTEMS)
        waitFor("Arrange")
        settle(1_500)
        shoot("at rest")
        hold(PadButton.A)
        settle(800)
        tap(PadButton.B)
        settle(600)
        shoot("arranging")
        // Options held, left: the chosen system narrows to a small square.
        router.press(PadButton.X, io.github.matiyaaa.fuse.ui.designsystem.input.InputSource.GAMEPAD)
        settle(700)
        tap(PadButton.DPAD_LEFT)
        router.release(PadButton.X, io.github.matiyaaa.fuse.ui.designsystem.input.InputSource.GAMEPAD)
        settle(800)
        shoot("one system made small")
        tap(PadButton.DPAD_RIGHT)
        tap(PadButton.A)
        tap(PadButton.DPAD_RIGHT)
        settle(600)
        shoot("carrying the next one along")
        tap(PadButton.A)
        tap(PadButton.B)
        settle(800)
        shoot("done")
        libraryStore.updatePrefs { it.copy(systemsBoard = before) }
        show(libraryStore)
    }

    scenario("systems", "small tiles") {
        useLibrary()
        val systems = libraryStore.library.platforms.value.filter { it.gameCount > 0 }
        val before = libraryStore.prefs.value
        fun tile(i: Int, w: Int, h: Int) = io.github.matiyaaa.fuse.model.HomeWidget("system.${systems[i].platform.id.value}", io.github.matiyaaa.fuse.model.WidgetKind.SYSTEMS, i, target = systems[i].platform.id.value, width = w, height = h)
        // Every system a small square, the first two as cards, one tall: the way a shelf of systems looks.
        val board = systems.indices.map { i -> when (i) { 0 -> tile(i, 3, 2); 1 -> tile(i, 3, 4); else -> tile(i, 2, 2) } }
        libraryStore.updatePrefs {
            it.copy(
                systemsBoard = io.github.matiyaaa.fuse.model.HomeLayoutConfig(board = board, grain = 3),
                systemTiles = mapOf(systems[3].platform.id.value to io.github.matiyaaa.fuse.model.SystemTileLook(pattern = io.github.matiyaaa.fuse.model.TilePattern.WAVES, tone = io.github.matiyaaa.fuse.model.TileTone.LIGHT)),
            )
        }
        try {
            tab(Destination.SYSTEMS)
            waitFor("Arrange")
            settle(2_500)
            shoot("small tiles beside cards")
            libraryStore.updatePrefs { it.copy(systemTileStep = 1) }
            settle(1_500)
            shoot("smaller: one more in a row")
            libraryStore.updatePrefs { it.copy(systemTileStep = 0) }
            hold(PadButton.A)
            settle(800)
            tap(PadButton.DPAD_RIGHT)
            settle(600)
            shoot("carrying a system along: the next trades places with it")
            tap(PadButton.A)
            settle(600)
            shoot("put down, still arranging")
            tap(PadButton.B)
        } finally {
            libraryStore.updatePrefs { it.copy(systemsBoard = before.systemsBoard, systemTiles = before.systemTiles, systemTileStep = before.systemTileStep) }
        }
    }

    if (!exhaustive) return

    scenario("systems", "options menu") {
        useLibrary()
        tab(Destination.SYSTEMS)
        waitFor("Arrange")
        Grid(libraryStore.library.platforms.value.count { it.gameCount > 0 }).goTo(platformIndex("psp"))
        tap(PadButton.X)
        waitFor("System Settings")
        shoot("PSP options (no emulator installed)")
        tapText("Emulator")
        waitFor("Emulator for")
        shoot("emulator picker, nothing installed")
        tap(PadButton.B)
        tap(PadButton.X)
        waitFor("System Settings")
        focusText("ROM Folders")
        shoot("ROM folders row focused")
    }

    scenario("systems", "platform settings") {
        useLibrary()
        tab(Destination.SYSTEMS)
        waitFor("Arrange")
        tap(PadButton.X)
        tapText("System Settings")
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
        waitFor("Arrange")
        Grid(libraryStore.library.platforms.value.count { it.gameCount > 0 }).goTo(platformIndex("switch"))
        tap(PadButton.X)
        tapText("System Settings")
        waitFor("Disc playlists")
        tap(PadButton.DPAD_DOWN, 11)
        shoot("Switch, BIOS row (can't check)")
    }

    scenario("media", "system") {
        useLibrary()
        tab(Destination.SYSTEMS)
        waitFor("Arrange")
        tap(PadButton.X)
        tapText("Change System Media")
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

    // At every size: the art preview must fit a handheld screen.
    scenario("media", "game") {
        useLibrary()
        // From the game's options: Play, then Manage Media.
        search("Hollow Meridian")
        tap(PadButton.START)
        tap(PadButton.X)
        tapText("Manage Media")
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
        shoot("Box art slot")
        tap(PadButton.DPAD_DOWN)
        shoot("Icon slot")
        tap(PadButton.DPAD_DOWN)
        shoot("Cover slot preview fits the screen")
        tap(PadButton.A)
        waitFor("Choose a file")
        shoot("Cover slot actions")
        choose(0)
        settle(1_500)
        shoot("Find cover with no art source set up")
        tap(PadButton.X)
        waitFor("Fill art")
        shoot("fill art choice")
        tap(PadButton.B)
        // Down to the last slot, then all the way back: the header and every row come back.
        repeat(9) { tap(PadButton.DPAD_DOWN) }
        settle(600)
        shoot("last slot focused")
        repeat(10) { tap(PadButton.DPAD_UP) }
        settle(800)
        shoot("back at the top, everything shown again")
    }
    if (!exhaustive) return

    scenario("game", "single file") {
        useLibrary()
        openGame("Emberline Saga")
        shoot("default focus on Play")
        tap(PadButton.DPAD_UP)
        shoot("up from Play")
        tap(PadButton.DPAD_DOWN, 2)
        shoot("down to the description")
        tap(PadButton.DPAD_DOWN, 8)
        shoot("at the very end", 1_500)
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
        focusText("Remove from Fuse")
        shoot("options menu, last row (Remove from Fuse)")
    }

    // The pickers open from the game's options, which name them, so they are reached whatever order
    // the page's own buttons take.
    scenario("game", "emulator picker") {
        useLibrary()
        openGame("Aurora Outpost")
        tap(PadButton.X)
        tapText("Emulator")
        waitFor("Use the system's emulator")
        shoot("choice list")
        // Not installed, or (where Fuse can be shown one) not found yet.
        focusAny("Not installed", "Not found")
        shoot("an emulator that is not installed focused")
    }

    scenario("game", "collection picker") {
        useLibrary()
        openGame("Emberline Saga")
        tap(PadButton.X)
        tapText("Add to Collection")
        waitFor("New Collection")
        shoot("choice list with the game's collections checked")
    }

    scenario("game", "folder behaviour picker") {
        useLibrary()
        openGame("Emberline Saga")
        tap(PadButton.X)
        tapText("Folder Behaviour")
        waitFor("Nothing on disk changes")
        shoot("choice list")
    }

    scenario("game", "rename") {
        useLibrary()
        openGame("Emberline Saga")
        tap(PadButton.X)
        tapText("Rename Display Title")
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
        tapText("Remove from Fuse")
        waitFor("stay exactly where they are")
        shoot("destructive confirm dialog, Cancel focused")
        tap(PadButton.DPAD_RIGHT)
        shoot("confirm button focused")
        tap(PadButton.B)
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
        shoot("problem sheet after the launch failed", 700)
    }

    scenario("game", "system") {
        useLibrary()
        openGame("Emberline Saga")
        tap(PadButton.X)
        tapText("System")
        waitFor("System for")
        shoot("a game's system: its folder's first, then every other")
        tap(PadButton.B)
    }

    scenario("launch", "which screen") {
        useLibrary(twoScreens)
        openGame("Emberline Saga")
        tap(PadButton.A)
        waitFor("PLAY ON WHICH SCREEN?")
        shoot("top or bottom, just this time")
        tap(PadButton.DPAD_RIGHT)
        settle(400)
        shoot("the bottom screen chosen")
        tap(PadButton.DPAD_DOWN)
        tap(PadButton.A)
        settle(400)
        shoot("always for this game ticked")
        tap(PadButton.DPAD_RIGHT)
        tap(PadButton.A)
        settle(400)
        shoot("always for the system ticked instead")
        tap(PadButton.B)
        // Game options offer the screen too.
        tap(PadButton.X)
        tapText("Screen")
        waitFor("Open Emberline Saga on")
        shoot("a game's own screen")
        tap(PadButton.B)
    }

    scenario("launch", "which screen for an app") {
        useLibrary(twoScreens)
        tab(Destination.APPS)
        waitFor("All apps")
        tap(PadButton.DPAD_LEFT)
        tap(PadButton.A)
        waitFor("OPEN ON WHICH SCREEN?")
        shoot("an app's icon whole on the lit screen")
        tap(PadButton.B)
    }

    scenario("launch", "needs emulator") {
        useLibrary()
        openGame("Waystation Nine")
        tap(PadButton.A)
        waitFor("No emulator for")
        shoot("problem sheet with emulators to get")
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
        tapText("Pin to Home")
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
        waitFor("All apps")
        tap(PadButton.DPAD_LEFT)
        shoot("All apps (default)")
        if (!exhaustive) return@scenario
        if (nav(NavAction.UP) != NavResult.MOVED) throw NotCovered("Up did not reach the filters")
        tap(PadButton.DPAD_LEFT, 2)
        tap(PadButton.DPAD_DOWN)
        shoot("Pinned")
        if (nav(NavAction.UP) != NavResult.MOVED) throw NotCovered("Up did not reach the filters")
        tap(PadButton.DPAD_RIGHT)
        shoot("filters focused, Emulators")
        tap(PadButton.DPAD_DOWN)
        shoot("Emulators")
        // Leaving the tab and coming back returns to the same list.
        tab(Destination.HOME)
        tab(Destination.APPS)
        settle(800)
        shoot("back on Apps, still on Emulators")
        if (nav(NavAction.UP) != NavResult.MOVED) throw NotCovered("Up did not reach the filters")
        tap(PadButton.DPAD_RIGHT)
        tap(PadButton.DPAD_DOWN)
        shoot("All apps")
        tap(PadButton.DPAD_RIGHT, 2)
        tap(PadButton.X)
        waitFor("App Info")
        shoot("app options menu")
        tapText("Type")
        waitFor("What is")
        shoot("an app's type: game, app or emulator")
        tap(PadButton.B)
    }

    scenario("android", "games") {
        show(androidStore)
        tab(Destination.SYSTEMS)
        waitFor("Android")
        shoot("Android in Systems")
        tap(PadButton.A)
        waitFor("7 games")
        settle(1_200)
        shoot("the Android system: game apps with their icons")
        tap(PadButton.X)
        waitFor("Android, or back to being an app")
        shoot("an Android game's options")
        tapText("System")
        waitFor("System for")
        shoot("an Android game's system")
        tap(PadButton.B)
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
        queue = listOf(
            CartridgeQueueItem(201, "Kestrel Nine", "genesis", QueueState.DOWNLOADING, 1_300_000, 3_100_000),
            CartridgeQueueItem(202, "Lantern Keep", "psx", QueueState.QUEUED, 0, 540_000_000),
            CartridgeQueueItem(203, "Tidal Circuit", "n64", QueueState.PAUSED, 8_000_000, 16_000_000),
        ),
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
        // Rows are found by what the hint line offers for them, so a new row in between never
        // throws the walk off: past the active download to the systems, then the recent downloads.
        focusHint("Browse in Cartridge")
        shoot("a system focused, to browse it on RomM")
        tap(PadButton.X)
        waitFor("Browse PS1 in Cartridge")
        shoot("a system's options")
        tap(PadButton.B)
        waitGone("Browse PS1 in Cartridge")
        focusHint("Play")
        shoot("recent download focused")
        focusHint("Open in Cartridge") { tap(PadButton.DPAD_RIGHT) }
        shoot("download not in the library yet focused")
        focusHint("Choose") { tap(PadButton.DPAD_UP) }
        tapText("Search", step = PadButton.DPAD_RIGHT)
        waitFor("Search RomM")
        shoot("search RomM with the keyboard")
        tap(PadButton.B)
    }

    scenario("cartridge", "uploads") {
        useLibrary()
        val now = Clock.System.now().toEpochMilliseconds()
        setCartridge(
            cartridgeReady().copy(
                protocol = 3, activeDownloads = 0, queuedDownloads = 0, progress = null, currentTitle = null, queue = emptyList(),
                uploads = listOf(
                    CartridgeUploadItem("u3", "Hollow Meridian", "psx", UploadState.UPLOADING, 212_000_000, 540_000_000, files = 3, updatedAt = now),
                    CartridgeUploadItem("u2", "Beacon Bay", "gba", UploadState.DONE, 8_000_000, 8_000_000, files = 1, romId = 88, updatedAt = now - 60_000),
                    CartridgeUploadItem("u1", "Tidal Circuit", "n64", UploadState.FAILED, 0, 16_000_000, files = 1, error = "RomM has no N64 console yet", updatedAt = now - 120_000),
                ),
            ),
        )
        tab(Destination.CARTRIDGE)
        waitFor("Uploading to RomM")
        tap(PadButton.DPAD_LEFT)
        shoot("an upload going, one done and one failed")
        tap(PadButton.DPAD_RIGHT, 6)
        shoot("last action focused, the row scrolled")
        if (!exhaustive) return@scenario
        tapText("Upload", step = PadButton.DPAD_LEFT)
        waitFor("Upload a game to RomM")
        shoot("pick a system to upload from")
        tapText("Game Boy Advance")
        waitFor("Upload a Game Boy Advance game")
        shoot("pick a game to upload")
    }
    if (!exhaustive) return

    scenario("cartridge", "not installed") {
        useLibrary()
        // The tab shows once Cartridge is installed; before that the quick menu's tile opens the page.
        home()
        tap(PadButton.START)
        waitFor("Find games")
        val tile = textCentre("Cartridge")
        touch {
            down(tile)
            up()
        }
        waitFor("Your RomM library, on this device")
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
        waitFor("Update Cartridge")
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

    scenario("home", "board cartridge widget") {
        useLibrary {
            it.copy(
                home = io.github.matiyaaa.fuse.model.HomeLayoutConfig(
                    mode = io.github.matiyaaa.fuse.model.HomeMode.CHANNELS,
                    board = listOf(
                        io.github.matiyaaa.fuse.model.HomeWidget("cartridge", io.github.matiyaaa.fuse.model.WidgetKind.CARTRIDGE_DOWNLOADS, 0, width = 2, height = 1),
                        io.github.matiyaaa.fuse.model.HomeWidget("cartridge2", io.github.matiyaaa.fuse.model.WidgetKind.CARTRIDGE_DOWNLOADS, 1, width = 2, height = 2),
                    ),
                ),
            )
        }
        setCartridge(cartridgeReady())
        restartApp()
        waitFor("Cartridge")
        tap(PadButton.DPAD_LEFT)
        shoot("Cartridge widget with an active download")
    }
}
