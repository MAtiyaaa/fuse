package io.github.matiyaaa.fuse.ui.shell.audit

import io.github.matiyaaa.fuse.data.settings.SyncSettings
import io.github.matiyaaa.fuse.model.Destination
import io.github.matiyaaa.fuse.model.HomeLayoutConfig
import io.github.matiyaaa.fuse.model.HomeMode
import io.github.matiyaaa.fuse.model.PadButton
import io.github.matiyaaa.fuse.sync.SyncStatus
import io.github.matiyaaa.fuse.ui.shell.store.RunResult
import io.github.matiyaaa.fuse.ui.shell.store.UiPrefs
import kotlinx.coroutines.runBlocking

/** Fuse Sync as the library store made it. */
private val AuditDriver.sync: AuditSync
    get() {
        libraryStore
        return controls.sync ?: throw NotCovered("This store has no Fuse Sync")
    }

/** Fuse Sync back to off and never set up, before a scenario sets its own state. */
private fun AuditDriver.syncOff() {
    val s = sync
    runBlocking { libraryData.settings.update { it.copy(sync = SyncSettings()) } }
    s.status.value = SyncStatus.Off
    s.profiles.value = emptyList()
    s.activeProfile.value = null
    s.host.value = null
    s.devices.value = emptyList()
    s.activity.value = emptyList()
}

/** The library app with Fuse Sync set up as [asHost] (this PC the host) or a Steam Deck connected to it. */
private fun AuditDriver.useSync(asHost: Boolean, playing: String? = "mo", prefs: (UiPrefs) -> UiPrefs = { it }) {
    syncOff()
    sync.household(asHost, playing)
    useLibrary(prefs = prefs)
    settle(600)
}

/** Settings, Addons, Fuse Sync's page (through Addons, as a person gets there). */
private fun AuditDriver.openSyncPage() {
    openSettings()
    focusText("Addons")
    tap(PadButton.DPAD_RIGHT)
    waitFor("Your saves, play time, library and settings on every device")
    tapText("Fuse Sync")
    focusAny("Set Up Fuse Sync", "Profiles, Devices and What Syncs")
    tap(PadButton.A)
    settle(900)
}

/**
 * Fuse Sync by Fuse, every screen: off (and nothing of it anywhere), turned on, the two ways in,
 * setting up a host to "Fuse Sync is ready", connecting from "Fuse Sync found" to the code and
 * "Connected", Who's playing (people, a PIN, a new profile), the settings once set up as a host and
 * as a device, the Sync tab, the top line's profile, a save conflict, a game's save history, the
 * Home scope while arranging, and Add a Device.
 */
internal fun AuditDriver.syncScreens() {
    scenario("sync", "off and turning on") {
        syncOff()
        useLibrary()
        openSettings()
        focusText("Addons")
        tap(PadButton.DPAD_RIGHT)
        waitFor("Your saves, play time, library and settings on every device")
        shoot("Addons, with Fuse Sync among them")
        tapText("Fuse Sync")
        waitFor("Use Fuse Sync")
        shoot("Fuse Sync off: its switch, and nothing else")
        tapText("Use Fuse Sync")
        waitFor("Set Up Fuse Sync")
        shoot("on, not set up yet")
        tapText("Set Up Fuse Sync")
        waitFor("Make This the Host")
        shoot("its page: make this the host, or connect")
    }

    scenario("sync", "setting up a host") {
        syncOff()
        runBlocking { sync.setEnabled(true) }
        useLibrary()
        openSyncPage()
        tapText("Make This the Host")
        waitFor("Make this computer the host")
        shoot("what being the host means, its name, and keeping it running", 1_200)
        tap(PadButton.DPAD_DOWN, 2)
        shoot("Make This the Host chosen")
        tap(PadButton.A)
        waitFor("Fuse Sync is ready")
        shoot("Fuse Sync is ready, with the code for other devices", 1_600)
    }

    scenario("sync", "connecting") {
        syncOff()
        runBlocking { sync.setEnabled(true) }
        useLibrary()
        openSyncPage()
        tapText("Connect to a Host")
        waitFor("Fuse Sync found")
        shoot("Fuse Sync found on this network", 1_000)
        tap(PadButton.A)
        waitFor("Enter the code from Gaming PC")
        shoot("the host's code, before typing")
        type("K7Q2")
        shoot("half the code typed")
        type("M9XD")
        tap(PadButton.DPAD_DOWN, 2)
        shoot("the whole code, Connect chosen")
        tap(PadButton.A)
        waitFor("Connected to Gaming PC")
        shoot("connected, next: who's playing", 1_400)
        tap(PadButton.A)
        waitFor("Who's playing?")
        shoot("who's playing, right after connecting", 1_200)
    }

    scenario("sync", "who's playing") {
        useSync(asHost = false)
        openSyncPage()
        tapText("Playing As")
        waitFor("Who's playing?")
        shoot("everyone on the host, Mo playing here", 1_200)
        tap(PadButton.DPAD_RIGHT)
        shoot("Sam chosen, with a PIN")
        tap(PadButton.A)
        waitFor("Enter your PIN")
        shoot("Sam's PIN pad")
        type("1111")
        router.textInput?.submit()
        settle(900)
        shoot("a wrong PIN shakes and says so")
        type("24")
        shoot("two digits in")
        tap(PadButton.B)
        settle(500)
        tap(PadButton.DPAD_RIGHT, 2)
        shoot("Add Profile chosen")
        tap(PadButton.A)
        waitFor("New Profile")
        shoot("a new profile: name, avatar, PIN", 1_000)
        tap(PadButton.DPAD_DOWN)
        tap(PadButton.DPAD_RIGHT, 3)
        shoot("choosing an avatar")
        tap(PadButton.B)
    }

    scenario("sync", "settings as the host") {
        useSync(asHost = true)
        openSyncPage()
        waitFor("Playing As")
        shoot("set up as the host: who's playing and what syncs", 1_200)
        tap(PadButton.DPAD_DOWN, 8)
        shoot("the connection and this device")
        tap(PadButton.DPAD_DOWN, 4)
        shoot("the host: devices and keeping it running")
        tap(PadButton.DPAD_DOWN, 6)
        shoot("leaving")
        tapText("Devices", step = PadButton.DPAD_UP)
        waitFor("Every device connected to this host")
        shoot("the host's devices")
        tap(PadButton.B)
        tapText("Add a Device", step = PadButton.DPAD_UP)
        waitFor("Works once, for ten minutes")
        shoot("Add a Device: the code", 1_000)
        tap(PadButton.B)
    }

    scenario("sync", "settings on a device") {
        useSync(asHost = false)
        openSyncPage()
        waitFor("Playing As")
        shoot("a device connected to the host", 1_200)
        tapText("Home on This Device")
        waitFor("All devices on this profile")
        shoot("Home: the profile's everywhere, or this device's own")
        tap(PadButton.B)
        tapText("At Startup", step = PadButton.DPAD_UP)
        waitFor("Ask who's playing")
        shoot("who Fuse starts as")
        tap(PadButton.B)
        tapText("Profiles", step = PadButton.DPAD_UP)
        waitFor("Add Profile")
        shoot("the profiles on the host")
        tap(PadButton.A)
        waitFor("Change Avatar")
        shoot("one profile's options")
        tap(PadButton.B)
    }

    scenario("sync", "sync tab") {
        useSync(asHost = true)
        tab(Destination.CARTRIDGE)
        // Up into Addons' tabs, then along to Sync.
        tap(PadButton.DPAD_UP)
        focusText("Sync") { tap(PadButton.DPAD_RIGHT) }
        tap(PadButton.DPAD_DOWN)
        waitFor("Sync Now")
        shoot("Addons, Sync: the hub", 1_500)
        tap(PadButton.DPAD_RIGHT)
        shoot("Switch Profile chosen")
        tap(PadButton.DPAD_DOWN)
        shoot("every game on the host, with its saves")
        tap(PadButton.A)
        waitFor("Kept on the host")
        shoot("one game: play time by device and every version", 1_000)
        tap(PadButton.A)
        waitFor("Kept as")
        shoot("a version's files and where they are kept")
        tap(PadButton.B)
    }

    scenario("sync", "top line") {
        useSync(asHost = false)
        waitFor("Continue playing")
        shoot("who's playing, at the far end of the top line", 1_200)
        tap(PadButton.DPAD_UP)
        tap(PadButton.R1, 8)
        // Past the last tab: Search, Settings, the status, then at the far end who is playing.
        tap(PadButton.DPAD_RIGHT, 6)
        settle(400)
        shoot("the stick reaches the profile")
        tap(PadButton.A)
        waitFor("Who's playing?")
        shoot("opened from the top line")
        tap(PadButton.B)
    }

    scenario("sync", "save conflict") {
        useSync(asHost = false)
        waitFor("Continue playing")
        tap(PadButton.DPAD_LEFT)
        settle()
        sync.conflictNext = AuditSync.conflict()
        controls.launchResult = RunResult.Failed("The audit never starts games.")
        tap(PadButton.A)
        waitFor("Which save should")
        shoot("both played since they last synced: which save to use", 1_400)
        tap(PadButton.DPAD_RIGHT)
        shoot("Use Fuse Sync chosen")
        tap(PadButton.B)
    }

    scenario("sync", "save history") {
        useSync(asHost = false)
        openGame("Emberline Saga")
        tap(PadButton.X)
        tapText("Save History")
        waitFor("Save History")
        settle(1_000)
        shoot("a game's saves through time, from every device")
        tap(PadButton.DPAD_DOWN)
        tap(PadButton.A)
        waitFor("Use This Save")
        shoot("a version's options")
        tap(PadButton.B)
        tap(PadButton.R1)
        settle(600)
        shoot("no save states yet")
    }

    scenario("sync", "home scope") {
        useSync(asHost = false) { it.copy(home = HomeLayoutConfig(mode = HomeMode.CHANNELS)) }
        waitFor("Continue playing")
        tap(PadButton.DPAD_LEFT)
        hold(PadButton.A)
        waitFor("Put down")
        tap(PadButton.A)
        settle(500)
        shoot("arranging: This Device or All Devices beside Add widget and Done")
        tap(PadButton.DPAD_UP, 4)
        tap(PadButton.DPAD_RIGHT, 3)
        shoot("the controller reaches the scope at the top")
        tap(PadButton.A)
        settle(800)
        shoot("this device's own Home")
        tap(PadButton.B)
    }
}

/** Syncthing as the library store made it. */
private val AuditDriver.syncthing: AuditSyncthing
    get() {
        libraryStore
        return controls.syncthing ?: throw NotCovered("This store has no Syncthing")
    }

/** Settings, Addons, Syncthing's page (through Addons, as a person gets there). */
private fun AuditDriver.openSyncthingPage() {
    openSettings()
    focusText("Addons")
    tap(PadButton.DPAD_RIGHT)
    waitFor("Your emulators' save folders, through the Syncthing you already run")
    tapText("Syncthing")
    focusAny("Set Up Syncthing", "Devices and Save Folders")
    tap(PadButton.A)
    settle(900)
}

/**
 * Syncthing, every state of its page: off (pointing at Fuse Sync), not found, found and needing its
 * key, and connected with devices, one asking to join, and the save folders Fuse shares.
 */
internal fun AuditDriver.syncthingScreens() {
    scenario("syncthing", "off") {
        syncOff()
        runBlocking { syncthing.setEnabled(false) }
        useLibrary()
        openSettings()
        focusText("Addons")
        tap(PadButton.DPAD_RIGHT)
        waitFor("Your emulators' save folders, through the Syncthing you already run")
        shoot("Addons, with Syncthing beside Fuse Sync")
        tapText("Syncthing")
        waitFor("Set Up Syncthing")
        shoot("Syncthing's group, off")
        focusText("Set Up Syncthing")
        tap(PadButton.A)
        waitFor("Fuse Sync Instead")
        shoot("its page, off: what it is, and Fuse Sync instead", 1_000)
    }

    scenario("syncthing", "not found") {
        syncOff()
        syncthing.finds = io.github.matiyaaa.fuse.sync.syncthing.SyncthingState.NotFound(installed = false)
        runBlocking { syncthing.setEnabled(true) }
        useLibrary()
        openSyncthingPage()
        waitFor("Get Syncthing")
        shoot("not found: get it, look again, or its address", 1_000)
    }

    scenario("syncthing", "needs its key") {
        syncOff()
        syncthing.finds = io.github.matiyaaa.fuse.sync.syncthing.SyncthingState.NeedsKey("http://127.0.0.1:8384")
        runBlocking { syncthing.setEnabled(true) }
        useLibrary()
        openSyncthingPage()
        waitFor("Enter Its API Key")
        shoot("found, and where to find its key", 1_000)
    }

    scenario("syncthing", "connected") {
        syncOff()
        runBlocking { syncthing.setEnabled(true) }
        syncthing.household()
        useLibrary()
        openSyncthingPage()
        waitFor("Device ID")
        shoot("connected: this device, the devices, the save folders", 1_400)
        tap(PadButton.DPAD_DOWN, 5)
        shoot("further down: the save folders")
        tap(PadButton.DPAD_DOWN, 8)
        shoot("around a game, and leaving")
        runBlocking { syncthing.setEnabled(false) }
    }

    scenario("syncthing", "addons tab") {
        syncOff()
        runBlocking { syncthing.setEnabled(true) }
        syncthing.household()
        useLibrary()
        tab(Destination.CARTRIDGE)
        // Syncthing in use, so Addons has its tab (and no Sync tab beside it).
        tap(PadButton.DPAD_UP)
        focusText("Syncthing") { tap(PadButton.DPAD_RIGHT) }
        tap(PadButton.DPAD_DOWN)
        waitFor("Look Over Now")
        shoot("Addons, Syncthing: devices, folders and what is still coming in", 1_500)
        tap(PadButton.DPAD_DOWN)
        shoot("the save folders it shares")
        runBlocking { syncthing.setEnabled(false) }
    }
}
