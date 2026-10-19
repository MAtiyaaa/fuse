package io.github.matiyaaa.fuse.ui.shell.audit

import io.github.matiyaaa.fuse.model.LibrarySourceKind
import io.github.matiyaaa.fuse.model.PadButton
import io.github.matiyaaa.fuse.model.SourceState
import io.github.matiyaaa.fuse.model.StorageVolume
import io.github.matiyaaa.fuse.model.VolumeKind
import io.github.matiyaaa.fuse.ui.shell.store.GameQuery
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

private const val GB = 1_000_000_000L

/**
 * The screens 0.2.0 added or reworked: drives (several at once, one taken out), the problem sheet,
 * System health, backups and the rest, each at M.
 */
internal fun AuditDriver.everythingScreens() {
    scenario("drives", "storage across drives") {
        val internal = StorageVolume("android:primary", "Internal storage", listOf(root.parentFile.absolutePath), VolumeKind.INTERNAL, totalBytes = 256 * GB, freeBytes = 118 * GB)
        val card = StorageVolume("android:4E21-9A0C", "SanDisk SD card", listOf(sd.absolutePath), VolumeKind.SD_CARD, removable = true, totalBytes = 1_000 * GB, freeBytes = 684 * GB)
        AuditLibrary.writeSdCard(sd)
        controls.drives = listOf(internal, card)
        useLibrary()
        val store = libraryStore
        val source = runBlocking {
            val added = store.sources.add(sd.absolutePath, LibrarySourceKind.ROMS_ROOT)
            withTimeout(30_000) { store.library.games(GameQuery()).first { it.size == AuditLibrary.gameCount + 2 } }
            store.sources.refreshDrives()
            withTimeout(10_000) { store.sources.status.first { list -> list.any { it.volume?.id == card.id } } }
            added
        }
        try {
            openSettings()
            tap(PadButton.DPAD_DOWN, sectionIndex("storage"))
            tap(PadButton.DPAD_RIGHT)
            tapText("Games and space")
            waitFor("All drives")
            settle(2_500)
            shoot("two drives as filters above the systems")
            tap(PadButton.DPAD_LEFT)
            focusText("SanDisk SD card")
            tap(PadButton.A)
            settle(1_500)
            shoot("only the card's games, with its card")

            // The device's games, all picked at once, on their way to the card.
            focusText("Internal storage", step = { tap(PadButton.DPAD_UP) })
            tap(PadButton.A)
            settle(1_200)
            tap(PadButton.DPAD_RIGHT)
            tapText("Select all", substring = true)
            settle(600)
            shoot("every game on the device picked, ready to move")
            tapText("Move ", step = PadButton.DPAD_UP, substring = true)
            waitFor("games to")
            settle(600)
            shoot("move to: the card, its space and where they go")
            tap(PadButton.B)
            tap(PadButton.DPAD_LEFT)
            focusText("Internal storage", step = { tap(PadButton.DPAD_UP) })
            tap(PadButton.A)
            settle(800)

            // The card comes out.
            controls.drives = listOf(internal)
            store.sources.refreshDrives()
            runBlocking { withTimeout(10_000) { store.sources.status.first { list -> list.any { it.state == SourceState.OFFLINE } } } }
            store.storage.refresh()
            settle(2_500)
            shoot("the card out: its games kept and listed as last seen")

            openGame("Paper Lanterns")
            shoot("game page of a game on the card that is out")
            tap(PadButton.A)
            waitFor("SanDisk SD card unavailable")
            shoot("problem sheet: connect the drive", 900)
            tapText("Technical details")
            settle(600)
            shoot("problem sheet with its technical details")
            tap(PadButton.B)
        } finally {
            controls.drives = emptyList()
            runBlocking { source?.let { store.sources.remove(it) } }
            store.sources.refreshDrives()
        }
    }

    scenario("safe mode", "after starts that failed") {
        useLibrary { it.withTheme(io.github.matiyaaa.fuse.ui.designsystem.theme.ThemePresets.byId("crt")) }
        show(libraryStore, safeMode = io.github.matiyaaa.fuse.ui.shell.app.SafeMode(io.github.matiyaaa.fuse.ui.shell.app.SafeMode.Reason.REPEATED_FAILURES, 3))
        waitFor("Fuse started in safe mode")
        shoot("the safe mode sheet over Home", 900)
        tapText("Close")
        settle(800)
        shoot("Home in safe mode, with its chip in the top line")
    }

    scenario("health", "system health") {
        useLibrary()
        openSettings()
        tap(PadButton.DPAD_DOWN, sectionIndex("health"))
        tap(PadButton.DPAD_RIGHT)
        waitFor("Diagnostics report")
        settle(2_500)
        shoot("findings, most serious first")
        tapText("PlayStation firmware missing")
        waitFor("PlayStation settings")
        shoot("a finding opened", 900)
        tap(PadButton.B)
        tapText("Diagnostics report")
        waitFor("Check it before you share it")
        settle(900)
        shoot("the diagnostics report to read before saving")
        tap(PadButton.DPAD_DOWN, 6)
        settle(600)
        shoot("the report scrolled")
        tap(PadButton.B)
    }

    scenario("health", "a system's page") {
        useLibrary()
        tab(io.github.matiyaaa.fuse.model.Destination.SYSTEMS)
        settle()
        tap(PadButton.X)
        waitFor("System Settings")
        tapText("System Settings")
        settle(1_500)
        shoot("a system with what needs attention first")
    }

    scenario("backup", "backup and restore") {
        useLibrary()
        val store = libraryStore
        val picker = platform as AuditPlatform
        val made = runBlocking {
            val game = store.library.games(GameQuery()).first().first()
            store.library.setFavorite(game.id, true)
            store.backup.create()
        } ?: error("No backup was made")
        openSettings()
        tap(PadButton.DPAD_DOWN, sectionIndex("backup"))
        tap(PadButton.DPAD_RIGHT)
        waitFor("Back up now")
        settle(1_200)
        shoot("make one, restore one")

        picker.pickedFile = io.github.matiyaaa.fuse.ui.shell.platform.OpenedFile(made.name, made.bytes)
        tapText("Restore a backup")
        waitFor("Restore this backup?")
        settle(900)
        shoot("what the backup holds, and the parts to restore", 900)
        tapText("Look and Home")
        waitFor("Put settings back")
        settle(600)
        shoot("restored, with settings able to go back")

        picker.pickedFile = io.github.matiyaaa.fuse.ui.shell.platform.OpenedFile("notes.txt", "hello".encodeToByteArray())
        tapText("Restore a backup")
        waitFor("That isn't a Fuse backup")
        settle(600)
        shoot("a file that isn't a backup", 900)
        tap(PadButton.B)
        picker.pickedFile = null
    }

    scenario("search2", "filters, suggestions and settings") {
        useLibrary()
        search("")
        tap(PadButton.DPAD_LEFT)
        settle(900)
        shoot("nothing typed: filters to start with")
        search("platform:")
        settle(900)
        shoot("a filter being typed lists its values")
        search("platform:snes ")
        settle(900)
        shoot("a system filter as a chip")
        search("year:1990s fav:yes year:soon ")
        settle(900)
        shoot("several filters, one unreadable")
        search("emberlin")
        settle(900)
        shoot("a name with a letter missing")
        search("vibration")
        settle(900)
        shoot("settings found by name")
    }

    scenario("emulators", "settings for every game") {
        useLibrary()
        openSettings()
        tap(PadButton.DPAD_DOWN, sectionIndex("systems"))
        tap(PadButton.DPAD_RIGHT)
        // The emulator's own row (by its exact name; systems name it in their details too).
        tapText("DuckStation")
        waitFor("How Fuse starts it")
        tapText("Settings for Every Game")
        waitFor("settings for every game")
        settle(900)
        shoot("an emulator's own settings, grouped as it groups them", 900)
        tap(PadButton.DPAD_DOWN)
        tap(PadButton.A)
        waitFor("Internal resolution")
        settle(600)
        shoot("a setting's values, the one in use checked", 600)
        tap(PadButton.DPAD_DOWN)
        tap(PadButton.A)
        waitFor("settings for every game")
        focusText("Put Back What Fuse Changed")
        settle(600)
        shoot("changed, with a way to put back exactly what Fuse changed", 600)
        tap(PadButton.A)
        settle(900)
        tap(PadButton.B)
    }

    scenario("emulators", "every setting") {
        useLibrary()
        openSettings()
        tap(PadButton.DPAD_DOWN, sectionIndex("systems"))
        tap(PadButton.DPAD_RIGHT)
        tapText("DuckStation")
        waitFor("How Fuse starts it")
        tapText("Settings for Every Game")
        waitFor("settings for every game")
        tapText("Every Setting")
        waitFor("Every DuckStation setting")
        settle(900)
        shoot("every setting: the emulator's own parts, with search", 900)
        tapText("GPU")
        waitFor("GPU for every game")
        settle(600)
        shoot("a part's settings, each by its kind", 600)
        focusText("Renderer")
        tap(PadButton.A)
        waitFor("Kept as Renderer under GPU")
        settle(600)
        shoot("a choice, with this system's values only", 600)
        // Back goes up a level at a time: the part, then every part.
        tap(PadButton.B)
        waitFor("GPU for every game")
        tap(PadButton.B)
        waitFor("Every DuckStation setting")
        tapText("Search Every Setting")
        waitFor("Search DuckStation's settings")
        type("scale")
        router.textInput?.submit()
        waitFor("in DuckStation's settings")
        settle(600)
        shoot("what a search found, by part", 600)
        focusText("Resolution Scale")
        tap(PadButton.A)
        waitFor("A whole number")
        settle(600)
        shoot("a number, typed, with what the emulator reads", 600)
        tap(PadButton.B)
        waitFor("in DuckStation's settings")
        tap(PadButton.B)
        waitFor("Every DuckStation setting")
        // A search that finds nothing says so, and what to try instead.
        tapText("Search Every Setting")
        waitFor("Search DuckStation's settings")
        type("wobble")
        router.textInput?.submit()
        waitFor("Nothing called")
        settle(600)
        shoot("a search that found nothing, and what to try", 600)
        tap(PadButton.B)
        waitFor("Every DuckStation setting")
        // Opened from DuckStation's own picks: back to them.
        tap(PadButton.B)
        waitFor("settings for every game")
        tap(PadButton.B)
    }

    scenario("emulators", "an emulator's page and choosing one for a game") {
        useLibrary()
        // Settings, Systems: the emulators found here, then one's sheet with what Fuse has checked.
        openSettings()
        tap(PadButton.DPAD_DOWN, sectionIndex("systems"))
        tap(PadButton.DPAD_RIGHT)
        val first = runBlocking { libraryStore.emulators.installed.value.minByOrNull { it.name.lowercase() } } ?: throw NotCovered("No emulators in the audit library")
        tapText(first.name, substring = true)
        waitFor("How Fuse starts it")
        settle(900)
        shoot("an emulator's page", 900)
        focusText("Launch verified")
        settle(400)
        shoot("what Fuse has checked on this device, step by step", 600)
        tap(PadButton.B)
        openGame("Emberline Saga")
        tap(PadButton.X)
        tapText("Emulator")
        waitFor("Emulator for")
        settle(900)
        shoot("emulators for one game, with why some can't run it", 900)
    }

    scenario("settings2", "a folded setting found by search") {
        useLibrary { it.copy(crt = it.crt.copy(enabled = true)) }
        search("scanlines")
        waitFor("CRT effect")
        tap(PadButton.START)
        tap(PadButton.A)
        // The tuning under the switch opens with it, so the slider asked for is right there.
        waitFor("Curvature")
        settle(900)
        shoot("CRT tuning unfolded under its switch")
    }

    scenario("reading", "extra large text, kept clear of a TV's edges") {
        useLibrary { it.copy(textScale = 1.3f, screenMargin = 4) }
        settle(1_500)
        shoot("Home")
        openGame("Emberline Saga")
        settle(900)
        shoot("a game's page")
        tap(PadButton.B)
        tab(io.github.matiyaaa.fuse.model.Destination.LIBRARY)
        settle(1_200)
        shoot("the library")
        openSettings()
        tap(PadButton.DPAD_RIGHT)
        settle(900)
        shoot("settings")
    }

    scenario("reading", "large text") {
        useLibrary { it.copy(textScale = 1.15f) }
        settle(1_500)
        shoot("Home")
        openSettings()
        tap(PadButton.DPAD_RIGHT)
        settle(900)
        shoot("settings")
    }

    scenario("playtime", "where the time went") {
        useLibrary()
        openGame("Emberline Saga")
        // From Play down to the detail cards, then across to Play history.
        tap(PadButton.DPAD_DOWN)
        tap(PadButton.DPAD_RIGHT)
        tap(PadButton.A)
        waitFor("Last 30 days")
        settle(1_500)
        shoot("today, this week, this month, all time, days, games and systems")
    }
}
