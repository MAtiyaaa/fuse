package io.github.matiyaaa.fuse.ui.shell.audit

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.matiyaaa.fuse.library.PlatformCatalog
import io.github.matiyaaa.fuse.model.BiosStatus
import io.github.matiyaaa.fuse.model.PlatformId
import io.github.matiyaaa.fuse.ui.designsystem.shape.SquircleShape
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.FuseTheme
import io.github.matiyaaa.fuse.ui.shell.components.SystemCardArt
import io.github.matiyaaa.fuse.ui.shell.store.Art
import io.github.matiyaaa.fuse.ui.shell.store.PlatformCard
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import org.junit.Assume.assumeTrue

/**
 * A system with the pack's panel beside one whose panel Fuse made from a game's screenshot, at
 * a large tile's size, so the two read as one set. Put a pack panel and a game screenshot in the
 * audit folder as inputs/pack-panel.png and inputs/shot.png first: nothing from the pack is in the
 * repository.
 */
@OptIn(ExperimentalTestApi::class)
class SystemPanelAudit {
    @Test
    fun gameMadePanelsMatchThePack() {
        val dir = Audit.dir
        assumeTrue("Only under desktopAudit", dir != null)
        val pack = File(dir, "inputs/pack-panel.png").takeIf { it.exists() }?.absolutePath
        val shot = File(dir, "inputs/shot.png").takeIf { it.exists() }?.absolutePath
        assumeTrue("Needs a pack panel and a screenshot", pack != null && shot != null)
        fun card(id: String, art: Art) = PlatformCatalog.byId(PlatformId(id))!!.let { p ->
            PlatformCard(p, 12, art, null, true, 1, BiosStatus.NotRequired, p.defaultLayout, emptyList())
        }
        val ps4 = card("ps4", Art(boxart = pack))
        val ps5 = card("ps5", Art(boxart = shot, boxartFromGames = true))
        runDesktopComposeUiTest(1280, 720) {
            setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f)) {
                    FuseTheme {
                        Box(Modifier.fillMaxSize().background(Fuse.colors.ink).padding(40.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                                for (c in listOf(ps4, ps5)) {
                                    Box(Modifier.size(520.dp, 300.dp).clip(SquircleShape.fraction(0.08f))) { SystemCardArt(c, large = true) }
                                }
                            }
                        }
                    }
                }
            }
            repeat(20) { mainClock.advanceTimeBy(200); Thread.sleep(100) }
            val frame = onRoot().captureToImage().toAwtImage()
            val rgb = BufferedImage(frame.width, frame.height, BufferedImage.TYPE_INT_RGB)
            rgb.createGraphics().apply { drawImage(frame, 0, 0, null); dispose() }
            val file = File(dir, "system-panels.png")
            file.parentFile.mkdirs()
            ImageIO.write(rgb, "png", file)
            println("Audit shot: ${file.absolutePath}")
        }
    }
}
