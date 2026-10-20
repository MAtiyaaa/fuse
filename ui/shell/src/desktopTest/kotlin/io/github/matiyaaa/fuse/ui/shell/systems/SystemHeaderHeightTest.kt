package io.github.matiyaaa.fuse.ui.shell.systems

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.matiyaaa.fuse.library.PlatformCatalog
import io.github.matiyaaa.fuse.model.BiosState
import io.github.matiyaaa.fuse.model.BiosStatus
import io.github.matiyaaa.fuse.model.GameId
import io.github.matiyaaa.fuse.model.LibraryLayout
import io.github.matiyaaa.fuse.model.PlatformId
import io.github.matiyaaa.fuse.ui.designsystem.input.InputRouter
import io.github.matiyaaa.fuse.ui.designsystem.input.LocalInputRouter
import io.github.matiyaaa.fuse.ui.designsystem.theme.FuseTheme
import io.github.matiyaaa.fuse.ui.designsystem.theme.ThemePresets
import io.github.matiyaaa.fuse.ui.shell.store.Art
import io.github.matiyaaa.fuse.ui.shell.store.GameCard
import io.github.matiyaaa.fuse.ui.shell.store.PlatformCard
import io.github.matiyaaa.fuse.ui.shell.store.SystemActivity
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The Systems screen's header is one height for every system: played or not, its play line still
 * loading, firmware missing or not, on a wide screen and on a phone held upright. Moving between
 * systems never moves the grid below it (a system with the covers of its games played last once made
 * it taller than one without).
 */
@OptIn(ExperimentalTestApi::class)
class SystemHeaderHeightTest {
    private val gba = PlatformCatalog.byId(PlatformId("gba"))!!
    private fun card(bios: BiosState = BiosState.READY) =
        PlatformCard(gba, 12, Art(), "mGBA", true, 1, BiosStatus(bios), LibraryLayout.ICON, emptyList())

    private val played = SystemActivity(
        seconds = 7_200, played = 3,
        recent = (1..4).map { GameCard(GameId(it.toLong()), gba.id, "Game $it", "GBA", gba.accent, Art()) },
        mostPlayed = listOf(GameCard(GameId(1), gba.id, "Game 1", "GBA", gba.accent, Art())),
    )

    private fun heightOf(width: Dp, card: PlatformCard, activity: SystemActivity?): Float {
        var height = 0f
        runDesktopComposeUiTest(1600, 1000) {
            val router = InputRouter(kotlinx.coroutines.MainScope())
            setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f), LocalInputRouter provides router) {
                    FuseTheme(ThemePresets.Fuse) {
                        Box(Modifier.width(width).testTag("header")) {
                            SystemHeader(card, compact = false, widthFraction = 1f, activity = activity, playLine = true)
                        }
                    }
                }
            }
            waitForIdle()
            height = onNodeWithTag("header").fetchSemanticsNode().size.height.toFloat()
        }
        return height
    }

    @Test
    fun everySystemIsOneHeightWideOrNarrow() {
        for (width in listOf(1200.dp, 360.dp)) {
            val heights = mapOf(
                "still loading" to heightOf(width, card(), null),
                "none played" to heightOf(width, card(), SystemActivity()),
                "played, with games played last" to heightOf(width, card(), played),
                "firmware missing" to heightOf(width, card(BiosState.MISSING), played),
            )
            assertEquals(1, heights.values.toSet().size, "at $width: $heights")
        }
    }
}
