package io.github.matiyaaa.fuse.ui.shell.audit

import io.github.matiyaaa.fuse.model.Destination
import io.github.matiyaaa.fuse.model.PadButton
import io.github.matiyaaa.fuse.model.StorageVolume
import io.github.matiyaaa.fuse.model.VolumeKind
import java.io.File

private const val GB = 1_000_000_000L

/**
 * What 0.2.6 added, one quick pass each: searching Settings, marking firmware as set up, a new SD
 * card offered for games, moving a game to it, and adding an app to the Store by its address.
 */
internal fun AuditDriver.detailScreens() {
    scenario("details", "settings search") {
        useLibrary()
        openSettings()
        // Settings opens on Appearance; the search row is just above it.
        tap(PadButton.DPAD_UP)
        settle(400)
        shoot("the search row above Appearance")
        tap(PadButton.A)
        type("con")
        router.textInput?.submit()
        waitFor("places in Settings")
        shoot("what matches \"con\"")
        tap(PadButton.A)
        settle(900)
        shoot("landed on the first one")
    }

    scenario("details", "firmware marked as set up") {
        useLibrary()
        tab(Destination.SYSTEMS)
        waitFor("System options")
        tap(PadButton.X)
        tapText("System Settings")
        waitFor("Disc playlists")
        focusText("BIOS and firmware")
        tap(PadButton.A)
        waitFor("Fuse's check")
        shoot("what can be done about missing firmware")
        tapText("It's set up")
        settle(900)
        shoot("marked as set up by you")
        // Put back, so later scenarios see the check as it is.
        tap(PadButton.A)
        tapText("Go by Fuse's check")
        settle(600)
    }

    val internal = StorageVolume("android:primary", "Internal storage", listOf(root.parentFile.absolutePath), VolumeKind.INTERNAL, totalBytes = 256 * GB, freeBytes = 118 * GB)
    val cardDir = File(root.parentFile, "audit-new-card").apply { mkdirs() }
    val card = StorageVolume("android:7A11-0C3E", "SanDisk SD card", listOf(cardDir.absolutePath), VolumeKind.SD_CARD, removable = true, totalBytes = 512 * GB, freeBytes = 480 * GB)

    // The question a new card brings waits a moment after it mounts, which this harness's clock
    // doesn't run; Storage offers the same setup for any card without games folders.
    scenario("details", "a new card offered for games") {
        controls.drives = listOf(internal, card)
        libraryStore.sources.refreshDrives()
        try {
            useLibrary { it.copy(drivesAsked = listOf(card.id)) }
            openSettings()
            tap(PadButton.DPAD_DOWN, sectionIndex("storage"))
            tap(PadButton.DPAD_RIGHT)
            tapText("Games and space")
            waitFor("Set up SanDisk SD card for games")
            settle(1_500)
            shoot("Storage offers to set the card up")
            focusText("Set up SanDisk SD card for games") { tap(PadButton.DPAD_UP) }
            tap(PadButton.A)
            waitFor("Set it up for games")
            settle(600)
            shoot("how it is set up")
            tap(PadButton.B)
        } finally {
            controls.drives = emptyList()
            libraryStore.sources.refreshDrives()
        }
    }

    scenario("details", "a game moved to the card") {
        controls.drives = listOf(internal, card)
        libraryStore.sources.refreshDrives()
        try {
            useLibrary { it.copy(drivesAsked = listOf(card.id)) }
            waitFor("Continue playing")
            tap(PadButton.DPAD_LEFT)
            tap(PadButton.X)
            focusText("Move to Another Drive")
            shoot("Move to Another Drive in a game's options")
            tap(PadButton.A)
            waitFor("Move ")
            settle(600)
            shoot("where it can go")
            tap(PadButton.B)
        } finally {
            controls.drives = emptyList()
            libraryStore.sources.refreshDrives()
        }
    }

    scenario("details", "store add an app") {
        androidStore.updatePrefs { it.copy(storeVariant = io.github.matiyaaa.fuse.model.StoreVariant.STANDARD) }
        show(androidStore)
        tab(Destination.CARTRIDGE)
        waitFor("Standard edition")
        settle(800)
        focusText("Add an app") { tap(PadButton.DPAD_RIGHT) }
        shoot("Add an app beside the Store's buttons")
        tap(PadButton.A)
        type("github.com/example/handy")
        router.textInput?.submit()
        waitFor("Which shelf?")
        shoot("the shelf it goes on")
        tap(PadButton.B)
    }

    scenario("details", "status on a cable") {
        useLibrary()
        val audit = platform as AuditPlatform
        val before = audit.statusFlow.value
        try {
            home()
            audit.statusFlow.value = before.copy(wifi = io.github.matiyaaa.fuse.model.ConnectionState.OFF, ethernet = true, charging = false, batteryPercent = 52)
            settle(600)
            audit.statusFlow.value = audit.statusFlow.value.copy(charging = true)
            shoot("Ethernet and the battery just plugged in", 500)
            settle(2_500)
            shoot("the battery settled on charging")
        } finally {
            audit.statusFlow.value = before
        }
    }

    scenario("details", "standby") {
        view = AuditView.Piece { io.github.matiyaaa.fuse.ui.shell.app.StandbyScreen(clock24h = false) {} }
        settle(400)
        shoot("fading in")
        settle(2_000)
        shoot("standby, the clock dim")
        useLibrary()
    }

    scenario("details", "store on a computer") {
        show(windowsStore, windowsPlatform)
        home()
        // Windows has no Apps tab, so the Store's tab is found by name rather than by place.
        repeat(6) { if (!hasText("Emulators for this computer")) tap(PadButton.R1) }
        waitFor("Emulators for this computer")
        settle(1_500)
        shoot("the Store on Windows", 1_200)
        tap(PadButton.DPAD_DOWN)
        settle(900)
        shoot("a shelf focused")
        tap(PadButton.A)
        settle(1_500)
        shoot("a program's page")
        tap(PadButton.B)
    }
}
