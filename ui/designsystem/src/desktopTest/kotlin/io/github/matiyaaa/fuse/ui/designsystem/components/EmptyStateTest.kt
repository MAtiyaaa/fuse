package io.github.matiyaaa.fuse.ui.designsystem.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.matiyaaa.fuse.model.PerformanceProfile
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.designsystem.theme.FuseTheme
import io.github.matiyaaa.fuse.model.RenderQuality
import kotlin.test.Test

/**
 * An empty state squeezed into less room than it needs gives up its parts (its disc, then its
 * message) rather than spilling over the edge of its space: under a list that took a short panel's
 * height, the disc came out as a dark half circle cut off at the panel's foot.
 */
@OptIn(ExperimentalTestApi::class)
class EmptyStateTest {
    private fun shown(height: Dp, check: androidx.compose.ui.test.ComposeUiTest.() -> Unit) = runComposeUiTest {
        setContent {
            FuseTheme(quality = RenderQuality.of(PerformanceProfile.HIGH_QUALITY, null, lowPower = false)) {
                Box(Modifier.width(600.dp).height(height)) {
                    EmptyState(FuseIcons.History, "No saves yet", message = "Play it and its saves are kept here.", compact = true)
                }
            }
        }
        waitForIdle()
        check()
    }

    @Test
    fun withRoomItIsAllThere() = shown(400.dp) {
        onNodeWithText("No saves yet").assertIsDisplayed()
        onNodeWithText("Play it and its saves are kept here.").assertIsDisplayed()
    }

    @Test
    fun shortOfRoomItKeepsItsTitle() = shown(40.dp) {
        onNodeWithText("No saves yet").assertIsDisplayed()
        onNodeWithText("Play it and its saves are kept here.").assertIsNotDisplayed()
    }

    @Test
    fun withNoRoomNothingSpillsOut() = shown(0.dp) {
        onNodeWithText("No saves yet").assertIsNotDisplayed()
    }
}
