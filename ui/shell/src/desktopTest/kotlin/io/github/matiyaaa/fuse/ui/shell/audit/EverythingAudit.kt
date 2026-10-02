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
}
