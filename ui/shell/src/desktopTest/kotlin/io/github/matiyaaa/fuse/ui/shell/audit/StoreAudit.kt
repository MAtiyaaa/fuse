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
        store.updatePrefs { it.copy(storeVariant = null) }
        show(store)
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
        show(store)
        tab(Destination.CARTRIDGE)
        waitFor("Standard edition")
        tap(PadButton.DPAD_DOWN)
        settle(1_200)
        shoot("updates shelf focused")
        tap(PadButton.X)
        waitFor("View source")
        shoot("an app's options")
        tap(PadButton.B)
        waitGone("View source")
        tap(PadButton.DPAD_DOWN)
        shoot("next shelf")
        tap(PadButton.DPAD_DOWN, 2)
        shoot("a category shelf", 900)
        tap(PadButton.DPAD_RIGHT, 3)
        shoot("further along a shelf")
    }

    scenario("addons", "app page with an update") {
        show(store)
        tab(Destination.CARTRIDGE)
        waitFor("Standard edition")
        tap(PadButton.DPAD_DOWN)
        settle(800)
        tap(PadButton.A)
        waitFor("What's new")
        shoot("an installed app with an update", 1_200)
        tap(PadButton.DPAD_DOWN, 3)
        shoot("details and release notes", 900)
        tap(PadButton.B)
    }

    scenario("addons", "installing") {
        show(store)
        tab(Destination.CARTRIDGE)
        waitFor("Standard edition")
        tap(PadButton.DPAD_DOWN, 4)
        settle(800)
        tap(PadButton.A)
        waitFor("Install")
        settle(1_200)
        shoot("an app not installed")
        tap(PadButton.A)
        waitFor("Downloading")
        shoot("downloading, 42 percent", 1_500)
        tap(PadButton.B)
        settle(900)
        shoot("the shelf with the download and the top line's activity")
    }

    scenario("addons", "tabs and cartridge") {
        show(store)
        tab(Destination.CARTRIDGE)
        waitFor("Standard edition")
        tap(PadButton.DPAD_UP)
        shoot("Addons tabs focused")
        tap(PadButton.DPAD_LEFT)
        waitFor("Your RomM library, on this device")
        shoot("Cartridge inside Addons, not installed")
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
