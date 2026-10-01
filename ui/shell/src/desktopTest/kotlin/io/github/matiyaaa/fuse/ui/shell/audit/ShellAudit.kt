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
import io.github.matiyaaa.fuse.ui.shell.app.CompanionControls
import io.github.matiyaaa.fuse.ui.shell.app.CompanionPage
import io.github.matiyaaa.fuse.ui.shell.app.VolumeTarget
import io.github.matiyaaa.fuse.ui.shell.app.Spotlight
import io.github.matiyaaa.fuse.ui.shell.screenshots.ScreenshotPlatform
import io.github.matiyaaa.fuse.ui.shell.settings.settingsSections
import io.github.matiyaaa.fuse.ui.shell.store.GameQuery
import io.github.matiyaaa.fuse.ui.shell.store.LocationHint
import java.io.File
import kotlin.time.Clock
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * Settles until [text] is [shown] (or gone). Unlike [AuditDriver.waitFor] it lets the app's own
 * timers run between looks, which the capture countdown and its saved card need.
 */
private fun AuditDriver.settleUntil(text: String, shown: Boolean = true, timeoutMs: Long = 150_000) {
    val end = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < end) {
        settle(500)
        if (hasText(text) == shown) return
    }
    throw NotCovered("Waited ${timeoutMs / 1000} s for \"$text\" to ${if (shown) "show" else "go away"}")
}

/** Quick menu (Start), then its Settings row. */
internal fun AuditDriver.openSettings() {
    home()
    tap(PadButton.START)
    waitFor("Arrange Home")
    tapText("Settings")
    waitFor("Theme, motion, glass, CRT")
    settle()
}

/** A downloads folder with a game file and an APK, and the library as an SD card, for the file picker. */
private fun AuditDriver.storageForPicker() {
    val folder = File(root.parentFile, "audit-downloads").apply { mkdirs() }
    File(folder, "Quasar Drift (World).gba").writeBytes(ByteArray(2048))
    File(folder, "Ember Tactics.apk").writeBytes(ByteArray(4096))
    File(folder, "Saves").mkdirs()
    controls.storageRoots = listOf(LocationHint(folder.absolutePath, "Internal storage"), LocationHint(root.absolutePath, "SD card 4E21-9A0C"))
}

private fun sectionIndex(id: String) = settingsSections.indexOfFirst { it.id == id }.also { check(it >= 0) { "No settings section $id" } }

// ----------------------------------------------------------------------------------- overlays

internal fun AuditDriver.overlayScreens(exhaustive: Boolean) {
    scenario("overlays", "capture") {
        useLibrary(capturePlatform)
        waitFor("Continue playing")
        tap(PadButton.START)
        waitFor("Arrange Home")
        focusText("Screenshot") { if (nav(NavAction.RIGHT) != NavResult.MOVED) { nav(NavAction.DOWN); nav(NavAction.LEFT); nav(NavAction.LEFT) } }
        shoot("quick menu, Screenshot tile focused")
        tap(PadButton.A)
        settle(400)
        shoot("countdown, 3")
        // Frames render slower than real time here, so wait for what shows rather than for a time.
        settleUntil("Screenshot saved")
        shoot("saved card after the screenshot")
        settleUntil("Screenshot saved", shown = false)
        // L3 + R3 held: a recording, shown in the top line.
        router.press(PadButton.L3, InputSource.GAMEPAD)
        router.press(PadButton.R3, InputSource.GAMEPAD)
        settle(3_000)
        router.release(PadButton.L3, InputSource.GAMEPAD)
        router.release(PadButton.R3, InputSource.GAMEPAD)
        settle(1_500)
        shoot("recording, the top line's chip")
        tap(PadButton.START)
        settleUntil("recorded")
        shoot("quick menu while recording")
        tap(PadButton.B)
        // L3 + R3 tapped stops it.
        router.press(PadButton.L3, InputSource.GAMEPAD)
        router.press(PadButton.R3, InputSource.GAMEPAD)
        router.release(PadButton.L3, InputSource.GAMEPAD)
        router.release(PadButton.R3, InputSource.GAMEPAD)
        settleUntil("Recording saved")
        shoot("saved card after a recording")
        // The settings for it, in Inputs.
        openSettings()
        tap(PadButton.DPAD_DOWN, sectionIndex("inputs"))
        tap(PadButton.DPAD_RIGHT)
        tapText("Screenshots and recordings")
        settle()
        shoot("Inputs, Screenshots and recordings open")
    }

    scenario("overlays", "quick menu") {
        useLibrary()
        waitFor("Continue playing")
        tap(PadButton.START)
        waitFor("Arrange Home")
        shoot("open, first tile focused")
        // Low Power shows whether it is on while focused, before and after pressing it.
        val nextTile = {
            if (nav(NavAction.RIGHT) != NavResult.MOVED) {
                nav(NavAction.DOWN)
                nav(NavAction.LEFT)
                nav(NavAction.LEFT)
            }
        }
        focusText("Low Power", step = nextTile)
        shoot("Low Power focused, off")
        tap(PadButton.A)
        settle(500)
        shoot("Low Power focused, on")
        tap(PadButton.A)
        focusText("Find games", step = nextTile)
        shoot("Find games focused")
        tap(PadButton.B)
        if (!exhaustive) return@scenario
        tap(PadButton.START)
        waitFor("Arrange Home")
        tap(PadButton.DPAD_DOWN, 4)
        shoot("brightness slider focused")
        tap(PadButton.DPAD_DOWN, 6)
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
        tapText("Rename Display Title")
        waitFor("Display title")
        shoot("rename dialog with the on-screen keyboard")
        if (!exhaustive) return@scenario
        tap(PadButton.Y)
        tap(PadButton.L1, 4)
        shoot("caret moved back with LB")
        tap(PadButton.DPAD_DOWN, 4)
        shoot("bottom row (123, Paste, Space, Done) focused")
        tap(PadButton.DPAD_LEFT, 6)
        tap(PadButton.A)
        shoot("numbers and punctuation page")
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
    scenario("settings", "media sources") {
        useLibrary()
        openSettings()
        tap(PadButton.DPAD_DOWN, sectionIndex("media"))
        tap(PadButton.DPAD_RIGHT)
        waitFor("Fill missing art")
        shoot("filling first, sources folded")
        tapText("Sources and keys")
        waitFor("Source order")
        shoot("sources and keys open")
    }
    scenario("settings", "phone link") {
        phoneLink.state.value = io.github.matiyaaa.fuse.ui.shell.store.PhoneLinkState(
            running = true,
            addresses = listOf("http://192.168.1.20:47300/", "http://10.0.0.8:47300/"),
            username = "player",
            sessions = 1,
        )
        useLibrary { it.copy(phoneLinkEnabled = true) }
        openSettings()
        tap(PadButton.DPAD_DOWN, sectionIndex("phonelink"))
        tap(PadButton.DPAD_RIGHT)
        waitFor("Pair a phone")
        shoot("settings rows")
        tapText("Pair a phone")
        waitFor("Address in the code")
        settle(800)
        shoot("pairing code, sign-in and signed-in phones")
        tapText("Signed-in phones")
        waitFor("Sign out all phones?")
        shoot("sign out all confirmation")
        tap(PadButton.B)
        tapText("Sign-in", step = PadButton.DPAD_UP)
        waitFor("Phone Link username")
        shoot("username entry")
        tap(PadButton.B)
        phoneLink.state.value = io.github.matiyaaa.fuse.ui.shell.store.PhoneLinkState()
        useLibrary { it.copy(phoneLinkEnabled = false) }
        openSettings()
        tap(PadButton.DPAD_DOWN, sectionIndex("phonelink"))
        tap(PadButton.DPAD_RIGHT)
        tapText("Pair a phone")
        waitFor("Phone Link is off")
        settle(600)
        shoot("off")
    }

    if (!exhaustive) return

    scenario("settings", "add a game") {
        storageForPicker()
        show(androidStore)
        openSettings()
        tap(PadButton.DPAD_DOWN, sectionIndex("library"))
        tap(PadButton.DPAD_RIGHT)
        tapText("Add a game")
        waitFor("An APK file")
        shoot("an app, an APK or a game file")
        tapText("An app on this device")
        waitFor("Which app is a game?")
        shoot("apps that aren't games yet")
        tap(PadButton.B)
        tapText("Add a game")
        waitFor("An APK file")
        tapText("A game file")
        waitFor("Choose a game file")
        settle()
        shoot("file picker: storage places")
        tap(PadButton.A)
        waitFor("Up a folder")
        settle()
        shoot("file picker: a folder, folders first")
        tapText("Quasar Drift (World).gba")
        tap(PadButton.A)
        waitFor("Which system is")
        shoot("the system for a picked file")
        tap(PadButton.B)
    }

    scenario("settings", "windows: tabs, emulators and locate") {
        val folder = File(root.parentFile, "audit-emulators").apply { mkdirs() }
        File(folder, "PPSSPP").mkdirs()
        File(folder, "PPSSPP/PPSSPPWindows64.exe").writeBytes(ByteArray(1024))
        File(folder, "PPSSPP/ppsspp.ini").writeText("")
        File(folder, "Downloads").mkdirs()
        controls.storageRoots = listOf(LocationHint(folder.absolutePath, "Local Disk (D:)"))
        show(windowsStore, windowsPlatform)
        shoot("home: no Apps or Cartridge tab")
        openSettings()
        settle()
        shoot("sections: no Cartridge")
        tap(PadButton.DPAD_DOWN, sectionIndex("emulators"))
        tap(PadButton.DPAD_RIGHT)
        waitFor("Locate an emulator")
        settle()
        shoot("emulators: locate and folders")
        tapText("Locate an emulator")
        waitFor("If it missed one")
        settle()
        shoot("every emulator, found or not")
        tapText("PPSSPP")
        waitFor("Where is PPSSPP?")
        settle()
        shoot("picker: storage")
        tap(PadButton.A)
        waitFor("PPSSPP")
        tapText("PPSSPP")
        waitFor("PPSSPPWindows64.exe")
        settle()
        shoot("picker: only programs")
        tapText("PPSSPPWindows64.exe")
        waitFor("PPSSPP is ready")
        settle(800)
        shoot("located")
    }

    scenario("settings", "add an apk") {
        storageForPicker()
        show(androidStore)
        openSettings()
        tap(PadButton.DPAD_DOWN, sectionIndex("library"))
        tap(PadButton.DPAD_RIGHT)
        tapText("Add a game")
        waitFor("An APK file")
        tapText("An APK file")
        waitFor("Choose an APK")
        tap(PadButton.A)
        waitFor("Ember Tactics.apk")
        settle()
        shoot("APK picker: only APKs and folders")
    }

    scenario("settings", "storage") {
        useLibrary()
        openSettings()
        tap(PadButton.DPAD_DOWN, sectionIndex("storage"))
        tap(PadButton.DPAD_RIGHT)
        tapText("Games and space")
        waitFor("Showing")
        settle(2_500)
        shoot("drives and games by size")
        tapText("Showing")
        waitFor("Show games from")
        tap(PadButton.B)
        // Pick the two largest games.
        tapText("Showing")
        tap(PadButton.B)
        tap(PadButton.DPAD_DOWN)
        tap(PadButton.A)
        tap(PadButton.DPAD_DOWN)
        tap(PadButton.A)
        settle(600)
        shoot("two games picked")
        // The drives follow the list down, for screens without touch.
        tap(PadButton.DPAD_DOWN, 12)
        settle(900)
        shoot("further down the games, the drives scrolled along")
        tap(PadButton.DPAD_UP, 12)
        tapText("Delete 2 games", step = PadButton.DPAD_UP, substring = true)
        waitFor("This can't be undone")
        shoot("delete confirmation naming what goes")
        tap(PadButton.B)
    }

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
        tapText("Arrange Home")
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

    scenario("settings", "themes gallery") {
        useLibrary()
        // A theme someone shared, added as if from its link.
        val shared = """{"fuseTheme": 1, "name": "Deep Sea", "author": "a friend", "extends": "wave", "colors": {"background": "#051216", "surface": "#0C1E24", "surfaceRaised": "#132A31", "accent": "#3FD6C6", "onAccent": "#03201C", "text": "#ECF8F7", "textMuted": "#9DB8B6"}, "background": {"style": "wave", "secondary": "#7FB2FF"}, "focus": "glow"}"""
        val parsed = libraryStore.themes.parse(shared) as io.github.matiyaaa.fuse.model.ThemeCodec.Imported
        kotlinx.coroutines.runBlocking { libraryStore.themes.add(parsed.spec, shared, "https://example.com/deep-sea.json", apply = false) }
        openSettings()
        tap(PadButton.DPAD_DOWN, sectionIndex("appearance"))
        tap(PadButton.DPAD_RIGHT)
        waitFor("Game art")
        tapText("Theme")
        waitFor("built in")
        shoot("every theme, the one in use chosen", 1_500)
        tap(PadButton.X)
        waitFor("Copy as a theme file")
        shoot("a theme's options")
        tap(PadButton.B)
        tap(PadButton.DPAD_DOWN, 3)
        tap(PadButton.DPAD_RIGHT, 4)
        shoot("the added theme and the card that adds one", 1_200)
        tap(PadButton.A)
        waitFor("From a link or text")
        shoot("ways to add a theme")
        tap(PadButton.B)
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

    scenario("companion", "pages") {
        val store = libraryStore
        val cards = runBlocking { store.library.games(GameQuery()).first() }
        CompanionPage.current.value = 0
        view = AuditView.Companion(store, platform, DualScreenMode.LIBRARY_COMPANION)
        settle(1_200)
        Spotlight.set(cards.first { it.title == "Hollow Meridian" }.id)
        shoot("page 1, the focused game's logo over its room", 2_000)
        // A game with a logo of its own.
        val logo = java.io.File(cache, "companion-logo.png").also { AuditSystemArt.logo(it, "Velvet Orbit") }
        val velvet = cards.first { it.title == "Velvet Orbit" }
        runBlocking { store.media.setFromFile(io.github.matiyaaa.fuse.model.MediaOwner.OfGame(velvet.id), io.github.matiyaaa.fuse.model.MediaKind.LOGO, logo.absolutePath) }
        try {
            Spotlight.set(velvet.id)
            shoot("page 1, a game with its logo", 2_000)
        } finally {
            runBlocking { store.media.reset(io.github.matiyaaa.fuse.model.MediaOwner.OfGame(velvet.id), io.github.matiyaaa.fuse.model.MediaKind.LOGO) }
        }
        val collection = store.collections.collections.value.first { it.gameCount > 0 }
        Spotlight.set(collection.id)
        shoot("page 1, a collection focused", 2_000)
        CompanionPage.current.value = 1
        shoot("page 2, status", 1_500)
        CompanionPage.current.value = 2
        shoot("page 3, controls", 1_500)
        CompanionPage.current.value = 0
        Spotlight.set(null)
    }

    scenario("companion", "achievements and battery") {
        val store = libraryStore
        val velvet = runBlocking { store.library.games(GameQuery()).first().first { it.title == "Velvet Orbit" } }
        // A RetroAchievements set for the game, as Fuse caches it (the audit has no network).
        val achievements = (1..12).map { i ->
            io.github.matiyaaa.fuse.model.Achievement(
                id = 9_000L + i, gameId = 9_001L, title = listOf("First Light", "Orbit Keeper", "Velvet Touch", "Long Way Round", "Gravity Well", "No Fuel Left", "Night Run", "Star Chart", "Perfect Drift", "Event Horizon", "Last Signal", "Home Again")[i - 1],
                description = "Finish chapter $i without losing a ship",
                points = listOf(5, 10, 10, 25, 5, 10, 25, 50, 10, 25, 10, 50)[i - 1],
                badgeUrl = "", badgeLockedUrl = "",
                earnedAt = if (i <= 7) 1_700_000_000_000L + i * 86_400_000L else null,
                earnedHardcoreAt = null,
                displayOrder = i,
            )
        }
        val state = io.github.matiyaaa.fuse.model.AchievementState(
            raGameId = 9_001L, title = "Velvet Orbit", consoleName = "Dreamcast", iconUrl = null,
            total = 12, earned = 7, earnedHardcore = 0, points = 235, pointsEarned = 90, highestAward = null,
            achievements = achievements, fetchedAt = Clock.System.now().toEpochMilliseconds(),
        )
        val logo = java.io.File(cache, "companion-logo.png").also { AuditSystemArt.logo(it, "Velvet Orbit") }
        val owner = io.github.matiyaaa.fuse.model.MediaOwner.OfGame(velvet.id)
        runBlocking {
            libraryData.cache.put("retroachievements", "game:9001", state, io.github.matiyaaa.fuse.model.AchievementState.serializer(), 0L, null)
            libraryData.games.updateLinks(velvet.id) { it.copy(retroAchievementsGameId = 9_001L) }
            store.media.setFromFile(owner, io.github.matiyaaa.fuse.model.MediaKind.LOGO, logo.absolutePath)
        }
        val audit = platform as AuditPlatform
        val battery = audit.statusFlow.value
        try {
            CompanionPage.current.value = 0
            view = AuditView.Companion(store, platform, DualScreenMode.LIBRARY_COMPANION)
            settle(1_200)
            Spotlight.set(velvet.id)
            waitFor("7 of 12")
            shoot("a game with achievements", 2_000)
            val bar = textCentre("7 of 12")
            touch {
                down(bar)
                up()
            }
            waitFor("Night Run")
            shoot("the achievements list", 1_200)
            Spotlight.set(null)
            settle(800)
            CompanionPage.current.value = 1
            audit.statusFlow.value = battery.copy(batteryPercent = 64, charging = true, batteryMinutes = 52)
            shoot("status, charging", 1_500)
            audit.statusFlow.value = battery.copy(batteryPercent = 100, charging = true, batteryFull = true, batteryMinutes = null)
            shoot("status, charged", 1_500)
            audit.statusFlow.value = battery.copy(batteryPercent = 9, charging = false, batteryMinutes = 24)
            shoot("status, low", 1_500)
            audit.statusFlow.value = battery.copy(batteryMinutes = null)
            shoot("status, no estimate yet", 1_500)
            audit.statusFlow.value = battery
            CompanionPage.current.value = 2
            CompanionControls.volume = VolumeTarget.MUSIC
            shoot("controls, menu music volume", 1_500)
            CompanionControls.volume = VolumeTarget.DEVICE
            CompanionPage.current.value = 0
        } finally {
            audit.statusFlow.value = battery
            CompanionControls.screenOff = false
            runBlocking {
                store.media.reset(owner, io.github.matiyaaa.fuse.model.MediaKind.LOGO)
                libraryData.games.updateLinks(velvet.id) { it.copy(retroAchievementsGameId = null) }
            }
        }
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
