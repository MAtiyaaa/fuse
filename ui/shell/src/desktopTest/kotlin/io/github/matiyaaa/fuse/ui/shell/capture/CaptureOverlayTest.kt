package io.github.matiyaaa.fuse.ui.shell.capture

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import io.github.matiyaaa.fuse.ui.designsystem.input.ComboGesture
import io.github.matiyaaa.fuse.ui.designsystem.theme.FuseTheme
import io.github.matiyaaa.fuse.ui.shell.platform.CaptureResult
import io.github.matiyaaa.fuse.ui.shell.platform.Haptics
import io.github.matiyaaa.fuse.ui.shell.platform.RecordingReady
import io.github.matiyaaa.fuse.ui.shell.platform.ScreenCapture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.MutableSharedFlow

/** What the overlay draws while a capture is taken: nothing of its own, not even a card fading out. */
@OptIn(ExperimentalTestApi::class)
class CaptureOverlayTest {
    private class Shots : ScreenCapture {
        var taken = 0
        override val picturesPlace = "Pictures/Fuse"
        override val videosPlace = "Movies/Fuse"
        override val stoppedElsewhere = MutableSharedFlow<CaptureResult>()
        override val leftFuse = MutableSharedFlow<Unit>()
        override suspend fun screenshot(name: String): CaptureResult {
            taken++
            return CaptureResult(picturesPlace)
        }
        override suspend fun prepareRecording(withSound: Boolean) = RecordingReady.UNAVAILABLE
        override fun startRecording(name: String) = Unit
        override suspend fun stopRecording(): CaptureResult? = null
    }

    @Test
    fun theLastCardIsGoneTheMomentTheNextScreenshotStarts() = runComposeUiTest {
        val shots = Shots()
        lateinit var controller: CaptureController
        mainClock.autoAdvance = false
        setContent {
            val scope = rememberCoroutineScope()
            controller = remember { CaptureController(shots, scope, Haptics.None, notify = { _, _ -> }, withSound = { false }) }
            FuseTheme {
                Box(Modifier.size(960.dp, 540.dp)) { CaptureOverlay(controller) }
            }
        }
        runOnIdle { controller.onCombo(ComboGesture.TAP) }
        mainClock.advanceTimeBy(CaptureController.CLEAR_MS + 500)
        assertEquals(1, shots.taken)
        onNodeWithText("Screenshot saved").assertExists()

        // A second screenshot right away, as when the shots in the README were taken.
        runOnIdle { controller.onCombo(ComboGesture.TAP) }
        mainClock.advanceTimeBy(32)
        assertEquals(CaptureController.State.Capturing, controller.state)
        onNodeWithText("Screenshot saved").assertDoesNotExist()

        mainClock.advanceTimeBy(CaptureController.CLEAR_MS + 500)
        assertEquals(2, shots.taken)
        onNodeWithText("Screenshot saved").assertExists()
    }
}
