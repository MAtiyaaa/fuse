package io.github.matiyaaa.fuse.ui.shell.capture

import io.github.matiyaaa.fuse.ui.designsystem.components.ToastKind
import io.github.matiyaaa.fuse.ui.designsystem.input.ComboGesture
import io.github.matiyaaa.fuse.ui.shell.capture.CaptureController.State
import io.github.matiyaaa.fuse.ui.shell.platform.CaptureResult
import io.github.matiyaaa.fuse.ui.shell.platform.Haptics
import io.github.matiyaaa.fuse.ui.shell.platform.RecordingReady
import io.github.matiyaaa.fuse.ui.shell.platform.ScreenCapture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone

@OptIn(ExperimentalCoroutinesApi::class)
class CaptureControllerTest {
    private class FakeCapture : ScreenCapture {
        val calls = mutableListOf<String>()
        var ready = RecordingReady.READY
        var free: Long? = 10L * 1024 * 1024 * 1024
        var recording = false
        override val picturesPlace = "Pictures/Fuse"
        override val videosPlace = "Movies/Fuse"
        override val stoppedElsewhere = MutableSharedFlow<CaptureResult>()
        override val leftFuse = MutableSharedFlow<Unit>()

        override suspend fun screenshot(name: String): CaptureResult {
            calls += "shot $name"
            return CaptureResult(picturesPlace)
        }

        override suspend fun prepareRecording(withSound: Boolean): RecordingReady {
            calls += "prepare sound=$withSound"
            return ready
        }

        override fun startRecording(name: String) {
            calls += "start $name"
            recording = true
        }

        override suspend fun stopRecording(): CaptureResult? {
            calls += "stop"
            val was = recording
            recording = false
            return if (was) CaptureResult(videosPlace, video = true) else null
        }

        override fun freeBytes(): Long? = free
    }

    private class Harness(scope: TestScope, sound: Boolean = true) {
        val capture = FakeCapture()
        val notes = mutableListOf<String>()
        val controller = CaptureController(
            capture = capture,
            scope = scope.backgroundScope,
            haptics = Haptics.None,
            notify = { m, _: ToastKind -> notes += m },
            withSound = { sound },
            // 2026-10-01 13:45:02 UTC at the start of the test.
            clock = { 1_790_862_302_000L + scope.testScheduler.currentTime },
            zone = TimeZone.UTC,
        )
    }

    @Test
    fun theQuickMenuCountsDownThreeSecondsThenTakesTheScreenshot() = runTest {
        val h = Harness(this)
        h.controller.screenshot(delayed = true)
        runCurrent()
        assertEquals(State.Countdown(recording = false, secondsLeft = 3), h.controller.state)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(State.Countdown(recording = false, secondsLeft = 2), h.controller.state)
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(State.Capturing, h.controller.state)
        assertTrue(h.capture.calls.isEmpty(), "nothing is captured while the overlay may still show")
        advanceTimeBy(CaptureController.CLEAR_MS + 1)
        runCurrent()
        assertEquals(listOf("shot Fuse 2026-10-01 13-45-05"), h.capture.calls)
        assertEquals(State.Idle, h.controller.state)
        assertEquals(1, h.controller.shots)
        assertNotNull(h.controller.saved)
        advanceTimeBy(CaptureController.SAVED_MS + 1)
        runCurrent()
        assertNull(h.controller.saved)
    }

    @Test
    fun theComboTakesTheScreenshotAtOnce() = runTest {
        val h = Harness(this)
        h.controller.onCombo(ComboGesture.TAP)
        advanceTimeBy(CaptureController.CLEAR_MS + 1)
        runCurrent()
        assertEquals(listOf("shot Fuse 2026-10-01 13-45-02"), h.capture.calls)
    }

    @Test
    fun holdingTheComboRecordsAndTappingItStops() = runTest {
        val h = Harness(this)
        h.controller.onCombo(ComboGesture.HOLD)
        advanceTimeBy(CaptureController.CLEAR_MS + 1)
        runCurrent()
        assertIs<State.Recording>(h.controller.state)
        assertEquals(listOf("prepare sound=true", "start Fuse 2026-10-01 13-45-02"), h.capture.calls)
        // A tap while recording stops it rather than taking a screenshot.
        h.controller.onCombo(ComboGesture.TAP)
        runCurrent()
        assertEquals(State.Idle, h.controller.state)
        assertEquals("stop", h.capture.calls.last())
        assertEquals(true, h.controller.saved?.video)
    }

    @Test
    fun theQuickMenuRecordingWaitsForThePermissionThenCountsDown() = runTest {
        val h = Harness(this)
        h.controller.toggleRecording(delayed = true)
        runCurrent()
        assertEquals(State.Countdown(recording = true, secondsLeft = 3), h.controller.state)
        assertEquals(listOf("prepare sound=true"), h.capture.calls)
        advanceTimeBy(3_000 + CaptureController.CLEAR_MS + 1)
        runCurrent()
        assertIs<State.Recording>(h.controller.state)
        // The tile again stops it.
        h.controller.toggleRecording(delayed = true)
        runCurrent()
        assertEquals(State.Idle, h.controller.state)
    }

    @Test
    fun leavingFuseSavesTheRecordingAndCallsOffACountdown() = runTest {
        val h = Harness(this)
        h.controller.onCombo(ComboGesture.HOLD)
        advanceTimeBy(CaptureController.CLEAR_MS + 1)
        runCurrent()
        h.capture.leftFuse.emit(Unit)
        runCurrent()
        assertEquals(State.Idle, h.controller.state)
        assertEquals("stop", h.capture.calls.last())

        h.capture.calls.clear()
        h.controller.screenshot(delayed = true)
        runCurrent()
        h.controller.onBackground()
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(State.Idle, h.controller.state)
        assertTrue(h.capture.calls.isEmpty())
    }

    @Test
    fun aGrantedRecordingThatNeverStartedGivesItsPermissionBack() = runTest {
        val h = Harness(this)
        h.controller.toggleRecording(delayed = true)
        runCurrent()
        h.controller.onBackground()
        runCurrent()
        assertEquals(listOf("prepare sound=true", "stop"), h.capture.calls)
        assertEquals(State.Idle, h.controller.state)
    }

    @Test
    fun recordingsStopAfterThirtyMinutesOrWhenStorageRunsLow() = runTest {
        val h = Harness(this)
        h.controller.onCombo(ComboGesture.HOLD)
        advanceTimeBy(CaptureController.CLEAR_MS + 1)
        runCurrent()
        advanceTimeBy(CaptureController.MAX_RECORDING_MS + CaptureController.CHECK_MS)
        runCurrent()
        assertEquals(State.Idle, h.controller.state)
        assertTrue(h.notes.any { "30 minutes" in it })

        h.controller.onCombo(ComboGesture.HOLD)
        advanceTimeBy(CaptureController.CLEAR_MS + 1)
        runCurrent()
        h.capture.free = 10L * 1024 * 1024
        advanceTimeBy(CaptureController.CHECK_MS + 1)
        runCurrent()
        assertEquals(State.Idle, h.controller.state)
        assertTrue(h.notes.any { "Storage is almost full" in it })
    }

    @Test
    fun aRefusedPromptGoesBackToIdleAndSaysSo() = runTest {
        val h = Harness(this)
        h.capture.ready = RecordingReady.REFUSED
        h.controller.toggleRecording(delayed = true)
        runCurrent()
        assertEquals(State.Idle, h.controller.state)
        assertEquals(listOf("Recording cancelled"), h.notes)
        assertEquals(listOf("prepare sound=true"), h.capture.calls)
    }

    @Test
    fun aRefusedSoundRecordsSilentlyAndSaysSoOnce() = runTest {
        val h = Harness(this)
        h.capture.ready = RecordingReady.READY_SILENT
        repeat(2) {
            h.controller.onCombo(ComboGesture.HOLD)
            advanceTimeBy(CaptureController.CLEAR_MS + 1)
            runCurrent()
            assertIs<State.Recording>(h.controller.state)
            h.controller.onCombo(ComboGesture.TAP)
            runCurrent()
        }
        assertEquals(1, h.notes.count { "without sound" in it })
    }

    @Test
    fun soundOffInSettingsAsksForNoSound() = runTest {
        val h = Harness(this, sound = false)
        h.capture.ready = RecordingReady.READY_SILENT
        h.controller.onCombo(ComboGesture.HOLD)
        advanceTimeBy(CaptureController.CLEAR_MS + 1)
        runCurrent()
        assertEquals("prepare sound=false", h.capture.calls.first())
        assertTrue(h.notes.isEmpty())
    }

    @Test
    fun aRecordingTheSystemStoppedIsShownAsSaved() = runTest {
        val h = Harness(this)
        h.controller.onCombo(ComboGesture.HOLD)
        advanceTimeBy(CaptureController.CLEAR_MS + 1)
        runCurrent()
        h.capture.stoppedElsewhere.emit(CaptureResult("Movies/Fuse", video = true))
        runCurrent()
        assertEquals(State.Idle, h.controller.state)
        assertEquals("Movies/Fuse", h.controller.saved?.place)
    }

    @Test
    fun nothingNewStartsWhileACountdownRuns() = runTest {
        val h = Harness(this)
        h.controller.screenshot(delayed = true)
        runCurrent()
        h.controller.onCombo(ComboGesture.TAP)
        h.controller.toggleRecording(delayed = false)
        advanceTimeBy(3_000 + CaptureController.CLEAR_MS + 1)
        runCurrent()
        assertEquals(1, h.capture.calls.size)
    }
}
