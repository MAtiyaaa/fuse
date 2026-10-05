package io.github.matiyaaa.fuse.ui.shell.audit

import io.github.matiyaaa.fuse.model.PadButton

/**
 * The quick menu's grid on a device with a second screen: its widgets (Now playing, the second
 * screen's three ways, the sliders), then editing it with the controller: picking an item up and
 * carrying it, resizing, taking out, and Add.
 */
internal fun AuditDriver.quickScreens() {
    scenario("quick", "the grid") {
        useLibrary(twoScreens)
        io.github.matiyaaa.fuse.ui.shell.music.MenuMusicRemote.current = "puddleworld"
        waitFor("Continue playing")
        tap(PadButton.START)
        waitFor("Arrange Home")
        shoot("open, first tile focused")
        tap(PadButton.DPAD_DOWN)
        shoot("the second screen's three ways focused")
        tap(PadButton.DPAD_LEFT)
        settle(600)
        shoot("Off chosen")
        tap(PadButton.DPAD_RIGHT)
        settle(600)
        shoot("Fuse chosen again")
        tap(PadButton.DPAD_DOWN, 4)
        shoot("Now playing focused")
        tap(PadButton.DPAD_RIGHT)
        settle(400)
        shoot("skipped to the next song")
        tap(PadButton.B)
    }
    scenario("quick", "editing") {
        useLibrary(twoScreens)
        waitFor("Continue playing")
        tap(PadButton.START)
        waitFor("Arrange Home")
        tap(PadButton.X)
        waitFor("Edit quick menu")
        shoot("editing, first item")
        tap(PadButton.A)
        tap(PadButton.DPAD_RIGHT)
        settle(500)
        shoot("Wi-Fi carried one place along")
        tap(PadButton.A)
        tap(PadButton.X)
        settle(600)
        shoot("Wi-Fi two wide")
        tap(PadButton.X)
        settle(600)
        shoot("Wi-Fi across the menu")
        tap(PadButton.Y)
        settle(600)
        shoot("Wi-Fi taken out")
        tap(PadButton.DPAD_DOWN, 12)
        tap(PadButton.DPAD_RIGHT, 2)
        settle(300)
        shoot("the Add tile focused")
        tap(PadButton.A)
        waitFor("Add to the quick menu")
        shoot("Add")
        tap(PadButton.A)
        settle(600)
        shoot("Wi-Fi back, at the end")
        tap(PadButton.B)
        settle(400)
        shoot("done editing")
        tap(PadButton.B)
        libraryStore.updatePrefs { it.copy(quickMenu = emptyList()) }
    }
    scenario("quick", "editing by touch") {
        useLibrary(twoScreens)
        waitFor("Continue playing")
        tap(PadButton.START)
        waitFor("Arrange Home")
        tap(PadButton.X)
        waitFor("Edit quick menu")
        settle(500)
        val from = textCentre("Bluetooth")
        val to = textCentre("Controller")
        touch { down(from) }
        settle(100)
        touch { moveTo(from + (to - from) * 0.3f) }
        settle(150)
        touch { moveTo(from + (to - from) * 0.7f) }
        settle(150)
        touch { moveTo(to) }
        settle(500)
        shoot("Bluetooth dragged over Controller, the others make room")
        touch { up() }
        settle(1_000)
        shoot("dropped in its new place")
        tap(PadButton.B)
        tap(PadButton.B)
        libraryStore.updatePrefs { it.copy(quickMenu = emptyList()) }
    }
}

/** Swiping in from the left edge goes back, on a computer's touch screen. */
internal fun AuditDriver.edgeBackScreens() {
    scenario("touch", "swipe back from the edge") {
        useLibrary()
        waitFor("Continue playing")
        openGame("Hollow Meridian")
        val y = size.heightPx / 2f
        val from = androidx.compose.ui.geometry.Offset(4f, y)
        touch { down(from) }
        settle(60)
        for (i in 1..6) {
            touch { moveTo(from + androidx.compose.ui.geometry.Offset(i * 28f * size.density, i * 4f)) }
            settle(40)
        }
        settle(150)
        shoot("pulled in far enough: the arrow lights")
        touch { up() }
        waitFor("Continue playing")
        settle(600)
        shoot("back where it came from")
    }
}
