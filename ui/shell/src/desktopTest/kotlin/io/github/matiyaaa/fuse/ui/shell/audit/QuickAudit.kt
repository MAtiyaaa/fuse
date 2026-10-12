package io.github.matiyaaa.fuse.ui.shell.audit

import io.github.matiyaaa.fuse.model.PadButton
import io.github.matiyaaa.fuse.transfer.TransferDirection
import io.github.matiyaaa.fuse.transfer.TransferItem
import io.github.matiyaaa.fuse.transfer.TransferKind
import io.github.matiyaaa.fuse.transfer.TransferLive
import io.github.matiyaaa.fuse.transfer.TransferPhase
import io.github.matiyaaa.fuse.transfer.TransferSettings
import io.github.matiyaaa.fuse.transfer.TransferStatus
import io.github.matiyaaa.fuse.transfer.TransferSummary
import io.github.matiyaaa.fuse.ui.shell.store.FuseStore
import io.github.matiyaaa.fuse.ui.shell.store.GameQuery
import io.github.matiyaaa.fuse.ui.shell.store.TransferAction
import io.github.matiyaaa.fuse.ui.shell.store.TransferRow
import io.github.matiyaaa.fuse.ui.shell.store.TransfersOps
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

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
        openQuickMenu()
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
        openQuickMenu()
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
        openQuickMenu()
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
    quickDownloadScreens()
}

/** The quick menu's Downloads section with things moving: progress, what is waiting, what finished. */
internal fun AuditDriver.quickDownloadScreens() {
    scenario("quick", "downloads moving") {
        val base = libraryStore
        val velvet = runBlocking { base.library.games(GameQuery()).first().first { it.title == "Velvet Orbit" } }
        val gb = 1L shl 30
        val down = TransferItem(
            "q1", "romm:rom:7", "romm", TransferDirection.DOWNLOAD, TransferKind.GAME, "Velvet Orbit", platform = "dc",
            target = "SD card", totalBytes = 4 * gb, doneBytes = 2 * gb, status = TransferStatus.ACTIVE, phase = TransferPhase.TRANSFERRING,
        )
        val queued = down.copy(id = "q2", key = "romm:rom:8", title = "Hollow Meridian", platform = "psx", totalBytes = 700L shl 20, doneBytes = 0, status = TransferStatus.QUEUED, phase = null, order = 1)
        val done = down.copy(id = "q3", key = "romm:rom:9", title = "Lantern Coast", platform = "gba", totalBytes = 16L shl 20, doneBytes = 16L shl 20, status = TransferStatus.DONE, phase = null, finishedAt = 1)
        val rows = MutableStateFlow(listOf(TransferRow(down, emptyList(), game = velvet.id), TransferRow(queued, emptyList()), TransferRow(done, emptyList())))
        val live = mapOf("q1" to TransferLive(2 * gb + (300L shl 20), 4 * gb, 24L shl 20, 74))
        val ops = object : TransfersOps {
            override val rows = rows
            override val summary = MutableStateFlow(TransferSummary(activeDownloads = 1, queued = 1))
            override val settings = MutableStateFlow(TransferSettings())
            override fun live(id: String) = MutableStateFlow(live[id] ?: TransferLive())
            override fun act(id: String, action: TransferAction) = Unit
            override fun pauseAll() = Unit
            override fun resumeAll() = Unit
            override fun clearFinished() = Unit
        }
        show(object : FuseStore by base { override val transfers = ops })
        waitFor("Continue playing")
        openQuickMenu("Downloads")
        settle(800)
        shoot("a download moving, one waiting its turn, one finished")
        tap(PadButton.DPAD_DOWN, 2)
        shoot("Pause everything focused")
        tap(PadButton.B)
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
