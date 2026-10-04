package io.github.matiyaaa.fuse.ui.shell.audit

import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import io.github.matiyaaa.fuse.model.Destination
import io.github.matiyaaa.fuse.model.DualScreenMode
import io.github.matiyaaa.fuse.model.HomeLayoutConfig
import io.github.matiyaaa.fuse.model.HomeMode
import io.github.matiyaaa.fuse.model.PadButton

/**
 * What 0.2.7 changed, one quick pass each: Undo and Reset while arranging Home, the Library's
 * gentler fold, the game page's time played as a stop of its own, the top line reached from a
 * pushed page and from Search, Settings without a second screen, and the second screen over the
 * main screen's background.
 */
internal fun AuditDriver.swapScreens() {
    scenario("swap", "arranging home") {
        useLibrary { it.copy(home = HomeLayoutConfig(mode = HomeMode.CHANNELS)) }
        waitFor("Continue playing")
        tap(PadButton.DPAD_LEFT)
        hold(PadButton.A)
        waitFor("Put down")
        tap(PadButton.A)
        // A change to take back: the chosen widget made taller while X is held.
        router.press(PadButton.X, io.github.matiyaaa.fuse.ui.designsystem.input.InputSource.GAMEPAD)
        settle(400)
        tap(PadButton.DPAD_DOWN)
        router.release(PadButton.X, io.github.matiyaaa.fuse.ui.designsystem.input.InputSource.GAMEPAD)
        settle(600)
        shoot("arranging, Undo and Reset at the top right")
        tap(PadButton.DPAD_UP, 4)
        settle(400)
        shoot("up past the top row reaches Undo")
        tap(PadButton.A)
        settle(600)
        shoot("the move taken back")
        tap(PadButton.DPAD_RIGHT)
        tap(PadButton.A)
        waitFor("Put Home back as it came?")
        shoot("Reset asks first")
        tap(PadButton.B)
        tap(PadButton.B)
    }

    scenario("swap", "library folded") {
        useLibrary()
        tab(Destination.LIBRARY)
        waitFor("All")
        settle(800)
        shoot("the library at rest")
        tap(PadButton.DPAD_DOWN, 3)
        settle(1_200)
        shoot("folded: the stage one line, the tiles nearly their size")
    }

    scenario("swap", "system page up to the tabs") {
        useLibrary()
        tab(Destination.SYSTEMS)
        waitFor("System options")
        tap(PadButton.A)
        settle(1_200)
        tap(PadButton.DPAD_UP, 4)
        settle(600)
        shoot("up from a system's games reaches the top line")
    }

    scenario("swap", "search up to the tabs") {
        useLibrary()
        home()
        tap(PadButton.Y)
        waitFor("Games, systems, apps")
        tap(PadButton.DPAD_RIGHT, 12)
        settle(400)
        shoot("right at the keys' edge stays on the keys")
        tap(PadButton.DPAD_UP, 8)
        settle(600)
        shoot("up from the keys reaches Search in the top line")
    }

    scenario("swap", "time played on the game page") {
        useLibrary()
        openGame("Emberline Saga")
        tap(PadButton.DPAD_DOWN)
        settle(900)
        shoot("time played chosen")
        tap(PadButton.DPAD_DOWN, 6)
        settle(900)
        shoot("the details, every card in view")
    }

    scenario("swap", "release notes") {
        useLibrary()
        openSettings()
        focusText("About")
        tap(PadButton.DPAD_RIGHT)
        focusText("What's new in this version")
        tap(PadButton.A)
        waitFor("WHAT'S NEW")
        shoot("the notes of the version running", 1_200)
        tap(PadButton.DPAD_DOWN, 3)
        shoot("read through, card by card", 900)
        tap(PadButton.DPAD_DOWN, 6)
        shoot("further down", 900)
        tap(PadButton.B)
    }

    scenario("swap", "screen settings with a second screen") {
        useLibrary(twoScreens)
        openSettings()
        focusText("Screen and sound")
        tap(PadButton.DPAD_RIGHT)
        settle(600)
        focusText("Which way round")
        shoot("which way round, in the second screen's group")
        tap(PadButton.A)
        settle(800)
        shoot("its two choices")
        tap(PadButton.B)
    }

    scenario("swap", "screen settings without a second screen") {
        useLibrary()
        openSettings()
        focusText("Screen and sound")
        tap(PadButton.DPAD_RIGHT)
        settle(600)
        repeat(30) { tap(PadButton.DPAD_DOWN) }
        settle(600)
        shoot("no second screen is mentioned")
    }
}

/** The second screen over the main screen's background, at its own size. */
internal fun AuditDriver.swapCompanion() {
    scenario("swap", "second screen background") {
        // A theme with a drawn scene, so the room behind is there to see.
        useLibrary { it.copy(themeId = "starlight") }
        view = AuditView.Companion(libraryStore, platform, DualScreenMode.LIBRARY_COMPANION)
        settle(1_500)
        shoot("the main screen's room behind the second screen", 1_500)
        libraryStore.updatePrefs { it.copy(display = it.display.copy(companionFollowsBackground = false)) }
        settle(800)
        shoot("turned off: its own dark backdrop")
        libraryStore.updatePrefs { it.copy(display = it.display.copy(companionFollowsBackground = true)) }
        useLibrary()
    }
}

/** Setup's opening, frame by frame. */
internal fun AuditDriver.setupOpening() {
    scenario("swap", "setup opening") {
        view = AuditView.Piece { io.github.matiyaaa.fuse.ui.shell.onboarding.SetupOpening(onDone = {}) }
        settle(900)
        shoot("the spark catches the line", 0)
        settle(800)
        shoot("running the fuse, shedding embers", 0)
        settle(900)
        shoot("the frame traces as the camera pulls back", 0)
        settle(900)
        shoot("the mark nearly its size", 0)
        settle(500)
        shoot("ignition", 0)
        settle(700)
        shoot("the ring and the tiles", 0)
        settle(900)
        shoot("the wordmark burning in", 0)
        settle(700)
        shoot("the lockup", 0)
    }
}

/** Setup's welcome mark drawing itself in, until its spark is lit. */
internal fun AuditDriver.welcomeMark() {
    scenario("swap", "welcome mark") {
        view = AuditView.Piece { io.github.matiyaaa.fuse.ui.shell.onboarding.Ignition() }
        shoot("lit", 4_000)
    }
    scenario("swap", "controller pad") {
        view = AuditView.Piece { androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.padding(40.dp)) { io.github.matiyaaa.fuse.ui.shell.onboarding.ControllerTest() } }
        router.press(PadButton.L1, io.github.matiyaaa.fuse.ui.designsystem.input.InputSource.GAMEPAD)
        shoot("left bumper held", 800)
        router.release(PadButton.L1, io.github.matiyaaa.fuse.ui.designsystem.input.InputSource.GAMEPAD)
    }
}

/** Flipped: the menus on the lower screen (its own size), the stage left to the screen above. */
internal fun AuditDriver.flippedMenus() {
    scenario("flipped", "menus below") {
        useLibrary()
        show(libraryStore, twoScreens, flipped = true)
        waitFor("Continue playing")
        shoot("Home on the lower screen", 1_500)
        tab(io.github.matiyaaa.fuse.model.Destination.LIBRARY)
        settle(1_200)
        shoot("the Library, its stage one line")
        tab(io.github.matiyaaa.fuse.model.Destination.SYSTEMS)
        settle(1_200)
        shoot("Systems")
        show(libraryStore)
    }
}

/** Flipped: the screen above, showing what the menus below have chosen. */
internal fun AuditDriver.flippedShowcase() {
    scenario("flipped", "the screen above") {
        useLibrary()
        openGame("Emberline Saga")
        settle(800)
        view = AuditView.Piece { io.github.matiyaaa.fuse.ui.shell.app.ShowcaseApp(libraryStore, platform) }
        shoot("a game, large", 2_000)
        io.github.matiyaaa.fuse.ui.shell.app.Spotlight.set(null)
        shoot("nothing chosen: the time and the games played last", 1_500)
        show(libraryStore)
    }
}
