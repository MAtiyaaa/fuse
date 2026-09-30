package io.github.matiyaaa.fuse.ui.shell.audit

import io.github.matiyaaa.fuse.model.CrtSettings
import io.github.matiyaaa.fuse.model.DualScreenMode
import io.github.matiyaaa.fuse.model.GlyphStyle
import io.github.matiyaaa.fuse.model.NavAction
import io.github.matiyaaa.fuse.model.PadButton
import io.github.matiyaaa.fuse.model.PerformanceProfile
import io.github.matiyaaa.fuse.model.PlatformId
import io.github.matiyaaa.fuse.model.ScanPhase
import io.github.matiyaaa.fuse.ui.designsystem.input.InputSource
import io.github.matiyaaa.fuse.ui.designsystem.input.NavResult
import io.github.matiyaaa.fuse.ui.designsystem.theme.ThemePresets
import io.github.matiyaaa.fuse.ui.shell.app.Spotlight
import io.github.matiyaaa.fuse.ui.shell.screenshots.ScreenshotPlatform
import io.github.matiyaaa.fuse.ui.shell.settings.settingsSections
import io.github.matiyaaa.fuse.ui.shell.store.GameQuery
import kotlin.time.Clock
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/** Quick menu (Start), then its Settings row. */
internal fun AuditDriver.openSettings() {
    home()
    tap(PadButton.START)
    waitFor("Arrange Home")
    choose(6)
    waitFor("Theme, motion, glass, CRT")
    settle()
}

private fun sectionIndex(id: String) = settingsSections.indexOfFirst { it.id == id }.also { check(it >= 0) { "No settings section $id" } }

// ----------------------------------------------------------------------------------- overlays

internal fun AuditDriver.overlayScreens(exhaustive: Boolean) {
    scenario("overlays", "quick menu") {
        useLibrary()
        waitFor("Continue playing")
        tap(PadButton.START)
        waitFor("Arrange Home")
        shoot("open, first tile focused")
        if (!exhaustive) return@scenario
        tap(PadButton.DPAD_DOWN, 3)
        shoot("brightness slider focused")
        tap(PadButton.DPAD_DOWN, 5)
        shoot("last row (Exit Fuse) focused")
        tap(PadButton.A)
        waitFor("Exit Fuse?")
        shoot("confirm dialog, Exit focused")
        tap(PadButton.DPAD_LEFT)
        shoot("confirm dialog, Cancel focused")
        tap(PadButton.B)
    }

    scenario("overlays", "context menu") {
        useLibrary()
        waitFor("Continue playing")
        tap(PadButton.DPAD_LEFT)
        tap(PadButton.X)
        waitFor("Game Info")
        shoot("game options on Home")
        if (!exhaustive) return@scenario
        tap(PadButton.DPAD_DOWN, 12)
        shoot("last row focused (destructive)")
    }

    scenario("overlays", "text input") {
        useLibrary()
        waitFor("Continue playing")
        tap(PadButton.DPAD_LEFT)
        tap(PadButton.X)
        waitFor("Rename Display Title")
        choose(8)
        waitFor("Display title")
        shoot("rename dialog with the on-screen keyboard")
        if (!exhaustive) return@scenario
        tap(PadButton.Y)
        tap(PadButton.DPAD_DOWN, 4)
        shoot("bottom row (Shift, Space, Delete, Done) focused")
        tap(PadButton.B)
    }

    if (!exhaustive) return
    scenario("overlays", "choice list") {
        useLibrary()
        openSettings()
        tap(PadButton.DPAD_RIGHT)
        tap(PadButton.A)
        waitFor("Crossbar")
        shoot("theme choice list with taglines")
        tap(PadButton.DPAD_DOWN, 8)
        shoot("theme choice list, last theme focused")
        tap(PadButton.B)
    }
}

// ----------------------------------------------------------------------------------- settings

internal fun AuditDriver.settingsScreens(exhaustive: Boolean) {
    if (exhaustive) {
        scenario("settings", "sections") {
            useLibrary()
            openSettings()
            shoot("section list focused (Appearance)")
        }
    }
    val sections = if (exhaustive) settingsSections else settingsSections.filter { it.id == "appearance" }
    for (s in sections) {
        scenario("settings", "section ${s.label}") {
            useLibrary()
            openSettings()
            tap(PadButton.DPAD_DOWN, sectionIndex(s.id))
            tap(PadButton.DPAD_RIGHT)
            waitFor(s.summary)
            shoot("first row focused")
            if (!exhaustive) return@scenario
            var moved = 0
            while (moved < 40 && nav(NavAction.DOWN) == NavResult.MOVED) moved++
            if (moved >= 5) shoot("last row focused")
        }
    }
    if (!exhaustive) return

    scenario("settings", "controls") {
        useLibrary()
        openSettings()
        tap(PadButton.DPAD_DOWN, sectionIndex("inputs"))
        tap(PadButton.DPAD_RIGHT)
        var steps = 0
        while (steps < 40 && nav(NavAction.DOWN) == NavResult.MOVED) steps++
        tap(PadButton.A)
        waitFor("Controller test")
        shoot("button mapping and controller test")
        router.press(PadButton.X, InputSource.GAMEPAD)
        try {
            shoot("left face button held in the controller test")
        } finally {
            router.release(PadButton.X, InputSource.GAMEPAD)
        }
        tap(PadButton.A)
        waitFor("Press a button for")
        shoot("waiting for a button (capture mode)", 900)
        settle(5_500)
    }

    scenario("settings", "licences") {
        useLibrary()
        openSettings()
        tap(PadButton.DPAD_DOWN, sectionIndex("about"))
        tap(PadButton.DPAD_RIGHT)
        tap(PadButton.DPAD_DOWN, 2)
        tap(PadButton.A)
        waitFor("built on the work of others")
        shoot("Fuse licence")
        tap(PadButton.DPAD_DOWN)
        tap(PadButton.R2)
        shoot("library licences, scrolled one page")
    }

    scenario("settings", "home arrangement") {
        useLibrary()
        home()
        tap(PadButton.START)
        waitFor("Arrange Home")
        choose(5)
        waitFor("Style, shelves, sections")
        shoot("Arrange Home from the quick menu")
    }
}

// --------------------------------------------------------------------------------- onboarding

internal fun AuditDriver.onboardingScreens(exhaustive: Boolean) {
    scenario("onboarding", "steps") {
        val platform = AuditPlatform(
            size,
            features = ScreenshotPlatform.features.copy(homeRole = true, secondScreen = true),
            homeRole = AuditPlatform.homeRole(),
        )
        show(firstRunStore, platform)
        waitFor("Welcome to Fuse")
        tap(PadButton.DPAD_LEFT)
        shoot("welcome", 3_000)
        tap(PadButton.A)
        waitFor("Tuned for this device")
        if (exhaustive) shoot("this device")
        tap(PadButton.A)
        waitFor("Make Fuse your Home screen?")
        if (exhaustive) shoot("Home screen role (optional)")
        tap(PadButton.DPAD_RIGHT)
        tap(PadButton.A)
        waitFor("Let Fuse see your games")
        if (exhaustive) shoot("storage access")
        tap(PadButton.A)
        waitFor("SD card (RomM)")
        shoot("libraries, two folders found")
        if (!exhaustive) return@scenario
        tap(PadButton.DPAD_DOWN)
        tap(PadButton.X)
        shoot("libraries, second folder unticked")
        tap(PadButton.X)
        controls.pauseListingAfter(5)
        try {
            tap(PadButton.A)
            waitFor("RomM and Cartridge")
            tap(PadButton.B)
            waitFor("Finding your games")
            shoot("libraries, while the first scan runs")
        } finally {
            controls.resumeListing()
        }
        pumpUntil("the scan to finish", 60_000) { firstRunStore.sources.scan.value.phase == ScanPhase.DONE && firstRunStore.library.platforms.value.isNotEmpty() }
        tap(PadButton.A)
        waitFor("Get games from your RomM server")
        shoot("Cartridge (optional)")
        tap(PadButton.DPAD_RIGHT)
        tap(PadButton.A)
        waitFor("emulators found")
        shoot("emulators")
        tap(PadButton.A)
        waitFor("BIOS check")
        shoot("BIOS")
        tap(PadButton.A)
        waitFor("Show your achievements?")
        shoot("achievements (optional)")
        tap(PadButton.A)
        waitFor("RetroAchievements username")
        shoot("achievements, username with the on-screen keyboard")
        tap(PadButton.B)
        tap(PadButton.DPAD_RIGHT)
        tap(PadButton.A)
        waitFor("Better artwork")
        shoot("artwork (optional)")
        tap(PadButton.DPAD_RIGHT)
        tap(PadButton.A)
        waitFor("Two screens")
        shoot("second screen")
        tap(PadButton.A)
        waitFor("Which button confirms?")
        shoot("controller test")
        router.press(PadButton.L1, InputSource.GAMEPAD)
        router.press(PadButton.X, InputSource.GAMEPAD)
        try {
            shoot("controller test, two buttons held")
        } finally {
            router.release(PadButton.X, InputSource.GAMEPAD)
            router.release(PadButton.L1, InputSource.GAMEPAD)
        }
        tap(PadButton.DPAD_RIGHT)
        tap(PadButton.A)
        waitFor("Pick a Home style")
        shoot("Home style")
        tap(PadButton.A)
        waitFor("Use up and down to try themes")
        shoot("theme")
        tap(PadButton.DPAD_DOWN)
        shoot("theme, next one tried")
        tap(PadButton.DPAD_UP)
        tap(PadButton.A)
        waitFor("Effects and battery")
        shoot("performance")
        tap(PadButton.A)
        waitFor("You're all set")
        shoot("done", 2_500)
        tap(PadButton.A)
        waitFor("Continue playing", 30_000)
        shoot("first Home after setup", 2_500)
    }
}

// -------------------------------------------------------------------------------------- looks

/** Every theme on Home, Library and a game page; effects; hint glyphs. */
internal fun AuditDriver.lookScreens() {
    for (theme in ThemePresets.all) {
        scenario("themes", theme.name) {
            useLibrary { it.copy(themeId = theme.id) }
            waitFor("Continue playing")
            tap(PadButton.DPAD_LEFT)
            shoot("Home", 2_000)
            tap(PadButton.R1)
            waitFor("Recently played")
            shoot("Library")
            home()
            tap(PadButton.X)
            waitFor("Game Info")
            choose(2)
            waitFor("Last played")
            shoot("game page")
        }
    }

    scenario("effects", "performance overlay") {
        useLibrary { it.copy(performanceOverlay = true) }
        waitFor("Continue playing")
        tap(PadButton.DPAD_LEFT)
        shoot("on Home")
        tap(PadButton.R1)
        shoot("on Library")
    }

    scenario("effects", "high contrast focus") {
        useLibrary { it.copy(highContrastFocus = true) }
        waitFor("Continue playing")
        tap(PadButton.DPAD_LEFT)
        shoot("on Home")
        tap(PadButton.R1)
        shoot("on Library")
        openSettings()
        tap(PadButton.DPAD_RIGHT)
        shoot("on Settings")
    }

    scenario("effects", "crt") {
        useLibrary { it.copy(crt = CrtSettings(enabled = true), performance = PerformanceProfile.HIGH_QUALITY) }
        waitFor("Continue playing")
        tap(PadButton.DPAD_LEFT)
        shoot("on Home")
        tap(PadButton.R1)
        shoot("on Library")
    }

    scenario("effects", "low power") {
        useLibrary { it.copy(lowPower = true) }
        waitFor("Continue playing")
        tap(PadButton.DPAD_LEFT)
        shoot("on Home")
    }

    scenario("hints", "nintendo layout") {
        useLibrary { it.copy(input = it.input.copy(swapConfirmBack = true)) }
        waitFor("Continue playing")
        tap(PadButton.DPAD_LEFT)
        shoot("Home, Xbox glyphs with the Nintendo layout")
        useLibrary { it.copy(input = it.input.copy(glyphs = GlyphStyle.NINTENDO)) }
        waitFor("Continue playing")
        tap(PadButton.DPAD_LEFT)
        shoot("Home, Nintendo glyphs")
        tap(PadButton.Y)
        waitFor("Game Info")
        shoot("options menu opened with Y on the Nintendo layout")
    }

    scenario("hints", "playstation glyphs") {
        useLibrary { it.copy(input = it.input.copy(glyphs = GlyphStyle.PLAYSTATION)) }
        waitFor("Continue playing")
        tap(PadButton.DPAD_LEFT)
        shoot("Home")
    }

    scenario("hints", "keyboard glyphs") {
        useLibrary()
        waitFor("Continue playing")
        tap(PadButton.KEY_RIGHT, source = InputSource.KEYBOARD)
        shoot("Home after a keyboard arrow press")
        tap(PadButton.KEY_SLASH, source = InputSource.KEYBOARD)
        waitFor("Games, systems, apps")
        shoot("Search after a keyboard shortcut")
    }
}

// ---------------------------------------------------------------------------------- companion

internal fun AuditDriver.companionScreens() {
    scenario("companion", "library companion") {
        val store = libraryStore
        val cards = runBlocking { store.library.games(GameQuery()).first() }
        Spotlight.set(null)
        view = AuditView.Companion(store, platform, DualScreenMode.LIBRARY_COMPANION)
        settle(2_000)
        shoot("idle, nothing focused on the main screen")
        Spotlight.set(cards.first { it.title == "Hollow Meridian" }.id)
        shoot("game focused on the main screen", 2_000)
        Spotlight.set(cards.first { it.title == "Aurora Outpost" }.id)
        shoot("game with an update and DLC focused", 2_000)
        Spotlight.set(PlatformId("psx"))
        shoot("system focused (firmware missing)", 2_000)
        // With the art pack's logo and panel (drawn by the audit; the pack itself is downloaded).
        val psx = store.library.platforms.value.first { it.platform.id == PlatformId("psx") }
        val owner = io.github.matiyaaa.fuse.model.MediaOwner.OfPlatform(psx.platform.id)
        val dir = java.io.File(cache, "system-art").apply { mkdirs() }
        val panel = java.io.File(dir, "psx-panel.png").also { AuditSystemArt.panel(it, psx.platform.accent) }
        val logo = java.io.File(dir, "psx-logo.png").also { AuditSystemArt.logo(it, psx.platform.shortName) }
        runBlocking {
            store.media.setFromFile(owner, io.github.matiyaaa.fuse.model.MediaKind.BOXART, panel.absolutePath)
            store.media.setFromFile(owner, io.github.matiyaaa.fuse.model.MediaKind.LOGO, logo.absolutePath)
        }
        try {
            Spotlight.set(null)
            settle(600)
            Spotlight.set(PlatformId("psx"))
            shoot("system focused with its logo and art panel", 2_500)
        } finally {
            runBlocking { store.media.reset(owner, null) }
        }
        Spotlight.set(PlatformId("psp"))
        shoot("system focused (no emulator)", 2_000)
        Spotlight.set(null)
    }

    scenario("companion", "now playing") {
        val store = libraryStore
        val card = runBlocking { store.library.games(GameQuery()).first().first { it.title == "Velvet Orbit" } }
        val start = Clock.System.now().toEpochMilliseconds() - 42 * 60_000L
        val session = runBlocking { libraryData.playSessions.start(card.id, io.github.matiyaaa.fuse.model.EmulatorId("linux.flycast"), start) }
        try {
            pumpUntil("the open session to show", 20_000) { store.library.home.value.playtime.currentGame != null }
            view = AuditView.Companion(store, platform, DualScreenMode.GAME_COMPANION)
            shoot("game companion while playing", 2_000)
            store.updatePrefs { it.copy(display = it.display.copy(companionShowsPerformance = true)) }
            shoot("while playing, with performance numbers", 1_500)
            store.updatePrefs { it.copy(display = it.display.copy(companionShowsPerformance = false)) }
            view = AuditView.Companion(store, platform, DualScreenMode.LIBRARY_COMPANION)
            shoot("library companion while a game runs (nothing focused)", 2_000)
        } finally {
            runBlocking { libraryData.playSessions.end(session, Clock.System.now().toEpochMilliseconds()) }
        }
    }

    scenario("companion", "idle") {
        val store = libraryStore
        Spotlight.set(null)
        view = AuditView.Companion(store, platform, DualScreenMode.GAME_COMPANION)
        shoot("game companion with nothing playing", 2_000)
    }
}

// -------------------------------------------------------------------------------- key screens

/** The screens every other size gets: enough to judge each layout at that size. */
internal fun AuditDriver.keyScreens() {
    homeFlow(exhaustive = false)
    homeChannels(exhaustive = false)
    libraryScreens(exhaustive = false)
    systemsScreens(exhaustive = false)
    gameScreens(exhaustive = false)
    searchScreens(exhaustive = false)
    overlayScreens(exhaustive = false)
    settingsScreens(exhaustive = false)
    cartridgeScreens(exhaustive = false)
    appsScreens(exhaustive = false)
    onboardingScreens(exhaustive = false)
}
