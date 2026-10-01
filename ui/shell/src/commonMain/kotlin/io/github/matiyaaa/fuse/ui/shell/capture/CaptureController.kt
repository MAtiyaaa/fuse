package io.github.matiyaaa.fuse.ui.shell.capture

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.matiyaaa.fuse.ui.designsystem.components.ToastKind
import io.github.matiyaaa.fuse.ui.designsystem.input.ComboGesture
import io.github.matiyaaa.fuse.ui.shell.platform.CaptureResult
import io.github.matiyaaa.fuse.ui.shell.platform.Haptics
import io.github.matiyaaa.fuse.ui.shell.platform.RecordingReady
import io.github.matiyaaa.fuse.ui.shell.platform.ScreenCapture
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * Screenshots and recordings of Fuse's screen. From the quick menu both start after a three second
 * countdown, so the menu is out of the way; L3 + R3 takes a screenshot at once and, held, starts a
 * recording. While a recording runs, either way of asking stops it. Everything of the capture's own
 * (the countdown, the flash, the saved card) is off the screen when a picture or recording starts.
 */
@Stable
class CaptureController(
    private val capture: ScreenCapture,
    private val scope: CoroutineScope,
    private val haptics: Haptics,
    /** Shows a short message (a toast). */
    private val notify: (String, ToastKind) -> Unit,
    /** Whether recordings should include sound (the user's setting). */
    private val withSound: () -> Boolean,
    private val clock: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val zone: TimeZone = TimeZone.currentSystemDefault(),
) {
    sealed interface State {
        data object Idle : State

        /** Counting down to a screenshot or a recording; [secondsLeft] runs 3, 2, 1. */
        data class Countdown(val recording: Boolean, val secondsLeft: Int) : State

        /** Waiting for the system's permission to record. */
        data object Preparing : State

        /** A picture or recording is starting: the capture's own overlay is hidden. */
        data object Capturing : State

        data class Recording(val since: Long) : State

        /** The recording is being finished and saved. */
        data object Saving : State
    }

    var state: State by mutableStateOf(State.Idle)
        private set

    /** The capture just saved, shown in a corner for a moment. */
    var saved: CaptureResult? by mutableStateOf(null)
        private set

    /** Counts screenshots taken, so the overlay flashes for each. */
    var shots: Int by mutableIntStateOf(0)
        private set

    val recording: Boolean get() = state is State.Recording

    /** Where screenshots and recordings are saved, for the settings. */
    val places: Pair<String, String> get() = capture.picturesPlace to capture.videosPlace

    private var job: Job? = null
    private var watch: Job? = null
    private var savedHide: Job? = null
    private var silentNoted = false

    init {
        scope.launch { capture.leftFuse.collect { onBackground() } }
        scope.launch {
            capture.stoppedElsewhere.collect { result ->
                if (state is State.Recording) {
                    watch?.cancel()
                    state = State.Idle
                    show(result)
                }
            }
        }
    }

    /** A screenshot: after the countdown when [delayed] (the quick menu), else at once. */
    fun screenshot(delayed: Boolean) {
        if (state != State.Idle) return
        job = scope.launch {
            if (delayed) countdown(recording = false)
            takeScreenshot()
        }
    }

    /** Starts a recording (after the countdown when [delayed]), or stops the one running. */
    fun toggleRecording(delayed: Boolean) {
        when (state) {
            is State.Recording -> stopRecording()
            State.Idle -> job = scope.launch { record(delayed) }
            else -> Unit
        }
    }

    /** L3 + R3: a tap takes a screenshot, a hold records; while recording, either stops it. */
    fun onCombo(gesture: ComboGesture) {
        when {
            state is State.Recording -> stopRecording()
            state != State.Idle -> Unit
            gesture == ComboGesture.TAP -> screenshot(delayed = false)
            else -> toggleRecording(delayed = false)
        }
    }

    /**
     * Fuse left the screen (a game started, Home was pressed): only Fuse is ever captured, so a
     * countdown is called off and a recording is saved. Waiting on the system's permission prompt
     * is not leaving.
     */
    fun onBackground() {
        when (val s = state) {
            is State.Countdown -> {
                job?.cancel()
                state = State.Idle
                // A recording that was granted but never started gives its permission back.
                if (s.recording) scope.launch { runCatching { capture.stopRecording() } }
            }
            is State.Recording -> stopRecording()
            else -> Unit
        }
    }

    /** Stops and saves the recording; [note] says why when Fuse stopped it by itself. */
    fun stopRecording(note: String? = null) {
        if (state !is State.Recording) return
        watch?.cancel()
        state = State.Saving
        job = scope.launch {
            val result = try {
                capture.stopRecording()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                CaptureResult(capture.videosPlace, video = true, failure = "The recording couldn't be saved")
            }
            state = State.Idle
            haptics.confirm()
            note?.let { notify(it, ToastKind.INFO) }
            result?.let(::show)
        }
    }

    private suspend fun countdown(recording: Boolean) {
        for (s in COUNTDOWN_SECONDS downTo 1) {
            state = State.Countdown(recording, s)
            haptics.tick()
            delay(1_000)
        }
    }

    /** Hides the capture's own overlay and gives the screen a moment to redraw without it. */
    private suspend fun clearTheScreen() {
        savedHide?.cancel()
        saved = null
        state = State.Capturing
        delay(CLEAR_MS)
    }

    private suspend fun takeScreenshot() {
        clearTheScreen()
        val result = try {
            capture.screenshot(fileName())
        } catch (e: CancellationException) {
            state = State.Idle
            throw e
        } catch (e: Throwable) {
            CaptureResult(capture.picturesPlace, failure = "The screenshot couldn't be saved")
        }
        state = State.Idle
        if (result.failure == null) {
            shots++
            haptics.confirm()
        }
        show(result)
    }

    private suspend fun record(delayed: Boolean) {
        state = State.Preparing
        val ready = try {
            capture.prepareRecording(withSound())
        } catch (e: CancellationException) {
            state = State.Idle
            throw e
        } catch (e: Throwable) {
            RecordingReady.UNAVAILABLE
        }
        when (ready) {
            RecordingReady.REFUSED -> {
                state = State.Idle
                notify("Recording cancelled", ToastKind.INFO)
                return
            }
            RecordingReady.UNAVAILABLE -> {
                state = State.Idle
                notify("Recording isn't available right now", ToastKind.WARNING)
                return
            }
            RecordingReady.READY_SILENT -> if (withSound() && !silentNoted) {
                silentNoted = true
                notify("Recording without sound: Fuse wasn't allowed to capture it", ToastKind.INFO)
            }
            RecordingReady.READY -> Unit
        }
        if (delayed) countdown(recording = true)
        clearTheScreen()
        try {
            capture.startRecording(fileName())
        } catch (e: Throwable) {
            state = State.Idle
            runCatching { capture.stopRecording() }
            notify("The recording couldn't start", ToastKind.ERROR)
            return
        }
        state = State.Recording(clock())
        haptics.confirm()
        watch = scope.launch { limits() }
    }

    /** Stops a recording that runs too long or would fill the storage. */
    private suspend fun limits() {
        val start = clock()
        while (true) {
            delay(CHECK_MS)
            val free = capture.freeBytes()
            if (free != null && free < MIN_FREE_BYTES) {
                stopRecording("Storage is almost full, so the recording stopped")
                return
            }
            if (clock() - start >= MAX_RECORDING_MS) {
                stopRecording("Recordings stop after 30 minutes")
                return
            }
        }
    }

    private fun show(result: CaptureResult) {
        if (result.failure != null) {
            notify(result.failure, ToastKind.ERROR)
            return
        }
        saved = result
        savedHide?.cancel()
        savedHide = scope.launch {
            delay(SAVED_MS)
            saved = null
        }
    }

    /** "Fuse 2026-10-01 13-45-02": sorts by time, and reads well in a gallery. */
    private fun fileName(): String {
        val t = Instant.fromEpochMilliseconds(clock()).toLocalDateTime(zone)
        fun two(n: Int) = n.toString().padStart(2, '0')
        return "Fuse ${t.year}-${two(t.month.ordinal + 1)}-${two(t.day)} ${two(t.hour)}-${two(t.minute)}-${two(t.second)}"
    }

    companion object {
        const val COUNTDOWN_SECONDS = 3

        /** Long enough for a frame or two without the overlay to reach the screen. */
        const val CLEAR_MS = 150L

        /** How long the saved card stays in the corner. */
        const val SAVED_MS = 3_500L

        const val CHECK_MS = 5_000L
        const val MAX_RECORDING_MS = 30 * 60_000L
        const val MIN_FREE_BYTES = 300L * 1024 * 1024
    }
}
