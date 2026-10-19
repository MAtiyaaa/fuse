package io.github.matiyaaa.fuse.ui.shell.audit

import io.github.matiyaaa.fuse.model.Destination
import io.github.matiyaaa.fuse.model.PadButton

/**
 * Addons on the audit's Android device: the Store's first choice, the catalogue as shelves, an
 * app's page with an update, a download in progress, the tabs, Cartridge inside Addons, and the
 * Store's settings. The catalogue is the real pack; see [AuditStore].
 */
internal fun AuditDriver.addonsScreens() {
    val store = androidStore
    scenario("addons", "store first run") {
        // Cartridge is on here, so the Store tab is moved first, as a person would.
        store.updatePrefs { it.copy(addonsOrder = listOf("STORE")) }
        store.updatePrefs { it.copy(storeVariant = null) }
        // The editions differ only for a second screen, so the choice is asked on a two-screen device.
        show(store, AuditPlatform(size, features = io.github.matiyaaa.fuse.ui.shell.screenshots.ScreenshotPlatform.features.copy(secondScreen = true)))
        tab(Destination.CARTRIDGE)
        waitFor("Choose your Store")
        shoot("choose an edition, Standard recommended")
        tap(PadButton.DPAD_RIGHT)
        shoot("Dual-Screen focused")
        tap(PadButton.DPAD_LEFT)
        tap(PadButton.A)
        waitFor("Standard edition")
        settle(1_500)
        shoot("the catalogue after choosing", 1_200)
    }

    scenario("addons", "store shelves") {
        // Cartridge is on here, so the Store tab is moved first, as a person would; Cartridge was
        // chosen for RomM in setup, so the Store puts it in the spotlight.
        store.updatePrefs { it.copy(addonsOrder = listOf("STORE"), rommAnswer = "CARTRIDGE") }
        show(store)
        tab(Destination.CARTRIDGE)
        waitFor("Standard edition")
        settle(1_200)
        shoot("the Store, opened")
        tap(PadButton.DPAD_DOWN)
        settle(900)
        shoot("the spotlight: what your library needs most")
        tap(PadButton.DPAD_DOWN, 3)
        settle(900)
        shoot("emulators by maker", 900)
        tap(PadButton.X)
        waitFor("View source")
        shoot("an app's options")
        tap(PadButton.B)
        waitGone("View source")
        tap(PadButton.DPAD_RIGHT, 3)
        shoot("further along a shelf")
        store.updatePrefs { it.copy(rommAnswer = "") }
    }

    scenario("addons", "updates in one place") {
        store.updatePrefs { it.copy(addonsOrder = listOf("STORE")) }
        show(store)
        tab(Destination.CARTRIDGE)
        waitFor("Standard edition")
        // Search, then Updates along the top row.
        tap(PadButton.DPAD_RIGHT)
        tap(PadButton.A)
        waitFor("Update All (2)")
        settle(900)
        shoot("every installed app's updates, Update All first")
        // Update All says every update it would make before it starts.
        tap(PadButton.A)
        waitFor("Update 2 Apps?")
        settle(900)
        shoot("Update All, each update said first")
        tap(PadButton.B)
        waitGone("Update 2 Apps?")
        tap(PadButton.DPAD_DOWN, 2)
        tap(PadButton.A)
        waitFor("Leave Out of Update All")
        shoot("an app's choices: now, left out, pre-releases")
        tapText("Leave Out of Update All")
        waitFor("Update All (1)")
        settle(600)
        shoot("one left out of Update All")
        // Back as it was, for the scenarios after this one.
        tap(PadButton.A)
        waitFor("Put Back in Update All")
        tapText("Put Back in Update All")
        waitFor("Update All (2)")
    }

    scenario("addons", "app page with an update") {
        store.updatePrefs { it.copy(addonsOrder = listOf("STORE")) }
        show(store)
        tab(Destination.CARTRIDGE)
        waitFor("Standard edition")
        // Search, then Updates along the top row.
        tap(PadButton.DPAD_RIGHT)
        tap(PadButton.A)
        waitFor("Update All (2)")
        tap(PadButton.DPAD_DOWN, 2)
        tap(PadButton.A)
        waitFor("Details and What's New")
        tapText("Details and What's New")
        waitFor("What's new")
        shoot("an installed app with an update", 1_200)
        tap(PadButton.DPAD_DOWN, 3)
        shoot("details and release notes", 900)
        tap(PadButton.B)
    }

    scenario("addons", "installing") {
        store.updatePrefs { it.copy(addonsOrder = listOf("STORE"), rommAnswer = "CARTRIDGE") }
        show(store)
        tab(Destination.CARTRIDGE)
        waitFor("Standard edition")
        tap(PadButton.DPAD_DOWN)
        settle(800)
        // The spotlight's own button installs it, right there.
        tap(PadButton.A)
        waitFor("Cancel")
        shoot("downloading from the spotlight", 1_500)
        tap(PadButton.X)
        waitFor("Details")
        tapText("Details")
        waitFor("Cancel")
        settle(900)
        shoot("its page while it downloads")
        tap(PadButton.B)
        settle(900)
        shoot("the Store with the download and the top line's activity")
        store.updatePrefs { it.copy(rommAnswer = "") }
    }

    scenario("addons", "tabs and cartridge") {
        // The usual order: Cartridge first, then the Store.
        store.updatePrefs { it.copy(addonsOrder = emptyList()) }
        show(store)
        tab(Destination.CARTRIDGE)
        waitFor("Your RomM library, on this device")
        shoot("Cartridge inside Addons, not installed")
        tap(PadButton.DPAD_UP)
        shoot("Addons tabs focused")
        // Held A lifts the tab; Right carries it past the Store, and the order is kept.
        hold(PadButton.A)
        waitFor("Done")
        shoot("a tab lifted to move")
        tap(PadButton.DPAD_RIGHT)
        settle(600)
        shoot("the tab carried past the Store")
        tap(PadButton.A)
        settle(400)
        check(store.prefs.value.addonsOrder.firstOrNull() == "STORE") { "The order wasn't kept: ${store.prefs.value.addonsOrder}" }
        store.updatePrefs { it.copy(addonsOrder = emptyList()) }
    }

    scenario("addons", "store settings") {
        show(store)
        openSettings()
        tap(PadButton.DPAD_DOWN, sectionIndex("addons"))
        tap(PadButton.DPAD_RIGHT)
        waitFor("Apps and emulators, their updates and where the catalogue comes from")
        shoot("Addons: Jellyfin, the Store and Cartridge")
        tapText("Store")
        waitFor("Catalogue source")
        tap(PadButton.DPAD_RIGHT)
        shoot("the Store's settings")
    }
}
