package io.github.matiyaaa.fuse.ui.shell.audit

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import io.github.matiyaaa.fuse.ui.designsystem.input.InputRouter
import io.github.matiyaaa.fuse.ui.designsystem.input.LocalInputRouter
import io.github.matiyaaa.fuse.ui.designsystem.theme.FuseTheme
import io.github.matiyaaa.fuse.ui.designsystem.theme.ThemePresets
import io.github.matiyaaa.fuse.ui.shell.app.StartupIntroOverlay
import org.junit.Assume.assumeTrue
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test

/** The startup animation, frame by frame, in Fuse's theme and a light one, for judging its timing and look. */
@OptIn(ExperimentalTestApi::class)
class IntroFramesAudit {
    @Test
    fun frames() {
        val dir = Audit.dir
        assumeTrue("Pass -P${Audit.DIR_PROPERTY}", dir != null && Audit.wants("intro", "frames"))
        for (theme in listOf(ThemePresets.Fuse, ThemePresets.Daylight)) {
            runDesktopComposeUiTest(1920, 1080) {
                mainClock.autoAdvance = false
                val router = InputRouter(kotlinx.coroutines.MainScope())
                setContent {
                    CompositionLocalProvider(LocalDensity provides Density(1.5f), LocalInputRouter provides router) {
                        FuseTheme(theme) { StartupIntroOverlay(onDone = {}) }
                    }
                }
                var at = 0L
                for (ms in listOf(0L, 160L, 420L, 760L, 1_060L, 1_220L, 1_380L, 1_620L, 1_900L, 2_200L, 2_450L)) {
                    mainClock.advanceTimeBy(ms - at)
                    at = ms
                    val out = File(dir, "intro/${theme.id}-${ms.toString().padStart(4, '0')}ms.png").apply { parentFile.mkdirs() }
                    ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", out)
                }
            }
        }
    }
}
