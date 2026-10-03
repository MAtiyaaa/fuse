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

/**
 * The startup animation, frame by frame, in Fuse's theme and a light one, for judging its timing and
 * look; on a 16:9 screen, a 4:3 handheld and a phone held upright, so the lockup is seen to fit.
 */
@OptIn(ExperimentalTestApi::class)
class IntroFramesAudit {
    @Test
    fun frames() {
        val dir = Audit.dir
        assumeTrue("Pass -P${Audit.DIR_PROPERTY}", dir != null && Audit.wants("intro", "frames"))
        val screens = listOf(Triple("tv", 1920, 1080), Triple("handheld", 1024, 768), Triple("phone", 1080, 2400))
        for (theme in listOf(ThemePresets.Fuse, ThemePresets.Daylight)) for ((screen, w, h) in screens) {
            val times = if (screen == "tv") listOf(0L, 160L, 520L, 900L, 1_180L, 1_300L, 1_500L, 1_700L, 1_900L, 2_100L, 2_300L, 2_550L, 2_900L) else listOf(1_000L, 2_100L, 2_550L)
            runDesktopComposeUiTest(w, h) {
                mainClock.autoAdvance = false
                val router = InputRouter(kotlinx.coroutines.MainScope())
                setContent {
                    CompositionLocalProvider(LocalDensity provides Density(1.5f), LocalInputRouter provides router) {
                        FuseTheme(theme) { StartupIntroOverlay(onDone = {}) }
                    }
                }
                var at = 0L
                for (ms in times) {
                    mainClock.advanceTimeBy(ms - at)
                    at = ms
                    val out = File(dir, "intro/${theme.id}-$screen-${ms.toString().padStart(4, '0')}ms.png").apply { parentFile.mkdirs() }
                    ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", out)
                }
            }
        }
    }
}
