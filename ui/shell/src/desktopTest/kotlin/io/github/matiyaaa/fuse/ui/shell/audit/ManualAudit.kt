package io.github.matiyaaa.fuse.ui.shell.audit

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import io.github.matiyaaa.fuse.data.FuseData
import io.github.matiyaaa.fuse.data.db.DesktopDatabase
import io.github.matiyaaa.fuse.model.LibrarySourceKind
import io.github.matiyaaa.fuse.model.PadButton
import io.github.matiyaaa.fuse.ui.shell.platform.ManualDocument
import io.github.matiyaaa.fuse.ui.shell.platform.ManualOpened
import io.github.matiyaaa.fuse.ui.shell.platform.ManualReader
import io.github.matiyaaa.fuse.ui.shell.platform.PlatformUi
import io.github.matiyaaa.fuse.ui.shell.store.FakeServices
import io.github.matiyaaa.fuse.ui.shell.store.FuseStore
import io.github.matiyaaa.fuse.ui.shell.store.GameQuery
import io.github.matiyaaa.fuse.ui.shell.store.createFuseStore
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * A game's manual: a GBA game kept in its own folder with its manual beside it, the game page
 * offering it, and the reader (a page, the next, zoomed in, a page marked, the pages to go to).
 */
internal fun AuditDriver.manualScreens() {
    scenario("game", "manual") {
        val store = manualStore()
        show(store, platform = ManualPlatform(platform))
        search("Lantern Coast")
        tap(PadButton.START)
        tap(PadButton.X)
        tapText("Game Info")
        waitFor("Press to read it here")
        // Down to the details: the extras card is the first of their second line.
        tap(PadButton.DPAD_DOWN, 3)
        settle(500)
        shoot("the game page: its manual among its extras", 1_000)
        tap(PadButton.A)
        waitFor("Page 1 of 6", timeoutMs = 20_000)
        shoot("the manual, its first page", 2_000)
        tap(PadButton.R1)
        waitFor("Page 2 of 6")
        shoot("the next page", 1_500)
        tap(PadButton.X)
        shoot("zoomed in", 1_500)
        tap(PadButton.X, 2)
        tap(PadButton.Y)
        shoot("the page marked", 1_500)
        tap(PadButton.A)
        waitFor("Go to a page")
        shoot("pages to go to", 1_500)
        tap(PadButton.B)
    }
}

/** A computer with one GBA game in a folder of its own, its manual (a PDF) beside it. */
private fun AuditDriver.manualStore(): FuseStore = runBlocking {
    val base = File(cache, "manual-audit").apply { deleteRecursively(); mkdirs() }
    val game = File(base, "Games/gba/Lantern Coast").apply { mkdirs() }
    File(game, "Lantern Coast (USA).gba").writeBytes(ByteArray(1024) { (it % 251).toByte() })
    File(game, "Lantern Coast Manual.pdf").writeBytes("%PDF-1.4 audit".encodeToByteArray())
    val services = FakeServices(FuseData(DesktopDatabase.open(File(base, "fuse.db").absolutePath)), File(base, "cache").apply { mkdirs() })
    val store = createFuseStore(services, scope)
    store.updatePrefs { it.copy(onboardingDone = true) }
    store.sources.add(File(base, "Games").absolutePath, LibrarySourceKind.ROMS_ROOT)
    withTimeout(30_000) { store.library.games(GameQuery()).first { it.isNotEmpty() } }
    store
}

/** The audit's platform, with a manual reader drawing the pages of a printed manual. */
private class ManualPlatform(base: PlatformUi) : PlatformUi by base {
    override val manuals: ManualReader = object : ManualReader {
        override suspend fun open(path: String): ManualOpened = ManualOpened.Ready(PrintedManual)
    }
}

/** Six pages laid out like a game's printed manual: a cover, controls, then text and pictures. */
private object PrintedManual : ManualDocument {
    override val pageCount = 6

    override suspend fun page(index: Int, maxWidthPx: Int, maxHeightPx: Int): ImageBitmap {
        // A booklet page, 5:7.
        val scale = minOf(maxWidthPx / 500f, maxHeightPx / 700f)
        val w = (500 * scale).toInt()
        val h = (700 * scale).toInt()
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.scale(scale.toDouble(), scale.toDouble())
        g.color = Color(0xFBF7EE)
        g.fillRect(0, 0, 500, 700)
        val ink = Color(0x23262E)
        val accent = Color(0x1F7A8C)
        if (index == 0) {
            g.color = accent
            g.fillRect(0, 0, 500, 420)
            g.color = Color(0xFBF7EE)
            g.font = Font(Font.SANS_SERIF, Font.BOLD, 52)
            g.drawString("LANTERN", 48, 200)
            g.drawString("COAST", 48, 262)
            g.font = Font(Font.SANS_SERIF, Font.PLAIN, 20)
            g.drawString("Instruction Booklet", 50, 310)
            g.color = ink
            g.font = Font(Font.SANS_SERIF, Font.PLAIN, 16)
            g.drawString("GAME BOY ADVANCE", 50, 640)
        } else {
            g.color = ink
            g.font = Font(Font.SANS_SERIF, Font.BOLD, 26)
            g.drawString(listOf("", "Controls", "Starting Out", "The Lighthouses", "Items", "Credits")[index], 48, 84)
            g.color = accent
            g.stroke = BasicStroke(3f)
            g.drawLine(48, 100, 200, 100)
            if (index == 1) {
                // A drawing of the console with its buttons named.
                g.color = Color(0xD9D4C7)
                g.fillRoundRect(90, 150, 320, 170, 60, 60)
                g.color = ink
                g.fillOval(140, 210, 50, 50)
                g.fillOval(320, 205, 34, 34)
                g.fillOval(360, 230, 34, 34)
                g.font = Font(Font.SANS_SERIF, Font.PLAIN, 15)
                listOf("+ Control Pad: walk, choose", "A Button: talk, light a lantern", "B Button: run, go back", "L / R: turn the map", "START: the pause menu").forEachIndexed { i, s -> g.drawString(s, 60, 380 + i * 34) }
            } else {
                g.font = Font(Font.SERIF, Font.PLAIN, 15)
                var y = 140
                repeat(9) { line ->
                    val words = "The coast has gone dark, and the keepers of its lighthouses are missing. Light each lantern to bring a ship home safely."
                    g.drawString(words.take(52 - (line % 3) * 6), 48, y)
                    y += 26
                }
                g.color = Color(0xD9E7E6)
                g.fillRect(48, y + 10, 404, 170)
                g.color = accent
                g.fillPolygon(intArrayOf(230, 250, 270), intArrayOf(y + 150, y + 40, y + 150), 3)
                g.color = ink
                g.font = Font(Font.SANS_SERIF, Font.ITALIC, 13)
                g.drawString("The first lighthouse, on the northern cape.", 48, y + 205)
            }
            g.font = Font(Font.SANS_SERIF, Font.PLAIN, 13)
            g.drawString("${index + 1}", 245, 670)
        }
        g.dispose()
        return img.toComposeImageBitmap()
    }

    override fun close() = Unit
}
