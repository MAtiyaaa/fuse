package io.github.matiyaaa.fuse

import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import android.view.FrameMetrics
import android.view.Window
import io.github.matiyaaa.fuse.ui.player.FusePlayer
import io.github.matiyaaa.fuse.ui.shell.store.FuseStore
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.WeakHashMap

/**
 * Every Fuse window owns its request and observed cadence. No cross-display timestamps and no
 * performance-quality coupling. FrameMetrics is passive: an idle window is never forced to draw.
 * Sources: Android Display.supportedModes and Window.OnFrameMetricsAvailableListener reference.
 */
internal suspend fun followWindowRefresh(window: Window, app: FuseApplication, store: FuseStore, fallback: Display? = null, main: Boolean = false): Unit = coroutineScope {
    val manager = window.context.getSystemService(DisplayManager::class.java)
    fun activeDisplay(): Display? = window.decorView.display ?: fallback
        ?: manager?.getDisplay(Display.DEFAULT_DISPLAY)
    var previous = 0L
    val intervals = ArrayDeque<Long>()
    var publishedAt = 0L
    val hold = RefreshHold(window, main)
    val listener = Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
        hold.drew()
        val now = metrics.getMetric(FrameMetrics.VSYNC_TIMESTAMP)
        val gap = now - previous
        previous = now
        if (gap in 2_000_000L..100_000_000L) {
            intervals.addLast(gap)
            if (intervals.size > 32) intervals.removeFirst()
            if (now - publishedAt >= 1_000_000_000L && intervals.size >= 4) {
                publishedAt = now
                val measured = (1e9 / intervals.min()).toFloat()
                activeDisplay()?.let { app.platformUi.displayMonitor.recordWindowRate(it.displayId, null, measured) }
            }
        } else if (gap > 100_000_000L) intervals.clear()
    }
    window.addOnFrameMetricsAvailableListener(listener, Handler(Looper.getMainLooper()))
    launch {
        combine(
            store.prefs.map { it.display.maxRefreshRate }.distinctUntilChanged(),
            app.platformUi.displays.map { displays -> displays.map { listOf(it.id, it.widthPx, it.heightPx, it.supportedRefreshRates) } }.distinctUntilChanged(),
        ) { maximum, _ -> maximum }.collect { maximum ->
            activeDisplay()?.let { display ->
                hold.pin = { window.preferRefreshRate(display, maximum) }
                hold.calm = { window.preferCalmRate(display, maximum) }
                val rate = hold.pinNow()
                app.platformUi.displayMonitor.recordWindowRate(display.displayId, rate)
            }
        }
    }
    hold.start()
    try { awaitCancellation() } finally {
        hold.stop()
        window.removeOnFrameMetricsAvailableListener(listener)
    }
}

/**
 * Holds a window at its chosen refresh rate only while something moves, and only at the full rate
 * while the person is doing something.
 *
 * A window's preferred display mode fixes the screen's lowest and highest rate to that mode's, so
 * a phone whose screen can slow down to a few times a second over a still picture (LTPO panels)
 * kept refreshing at 120 Hz while Fuse sat still. After [IDLE_MS] with no input and nothing drawn,
 * the request is let go and the phone does as it likes with a still screen; the next button, touch
 * or drawn frame asks for the rate again before anything moves (input comes before the frame it
 * causes), so movement always gets the full rate.
 *
 * A moving background kept frames coming, so a handheld left on Home held its screen at 120 Hz for
 * as long as it sat there, for a room that changes 30 times a second at most: the screen, its
 * controller and every layer over the room worked four times as often as anything changed. After
 * [CALM_MS] with no input while only that goes on, the window asks for a rate near 60 Hz instead
 * (only one the screen switches to without a blink, Android 12 and later); the next input brings
 * the full rate back first. While a film's picture is on screen the rate is never lowered.
 */
internal class RefreshHold(private val window: Window, private val main: Boolean = false) {
    var pin: () -> Float? = { null }

    /** Asks for the calmer rate: the rate, or null when the screen has none ([preferCalmRate]). */
    var calm: () -> Float? = { null }

    private enum class Mode { FULL, CALM, RELEASED }

    private var mode = Mode.FULL
        set(value) {
            field = value
            publish()
        }
    private var fullHz: Float? = null
    private var calmHz: Float? = null
    private var lastInput = SystemClock.uptimeMillis()
    private var lastDrawn = lastInput
    private var releasedAt = 0L
    private val handler = Handler(Looper.getMainLooper())
    private val check = object : Runnable {
        override fun run() {
            val now = SystemClock.uptimeMillis()
            when (mode) {
                Mode.FULL -> when {
                    now - lastInput < IDLE_MS -> Unit
                    now - lastDrawn >= IDLE_MS -> release()
                    now - lastInput >= CALM_MS && mayCalm() -> mode = Mode.CALM
                }
                Mode.CALM -> if (now - lastDrawn >= IDLE_MS) release() else if (FusePlayer.showingVideo) pinNow()
                Mode.RELEASED -> Unit
            }
            handler.postDelayed(this, CHECK_MS)
        }
    }

    fun start() {
        holds[window] = this
        handler.postDelayed(check, CHECK_MS)
    }

    fun stop() {
        handler.removeCallbacks(check)
        holds.remove(window)
    }

    fun pinNow(): Float? {
        lastInput = SystemClock.uptimeMillis()
        lastDrawn = lastInput
        fullHz = pin() ?: fullHz
        mode = Mode.FULL
        return fullHz
    }

    /** What this window asks of the screen now, for System health's Power and heat. */
    private fun publish() {
        val state = when (mode) {
            Mode.FULL -> io.github.matiyaaa.fuse.ui.shell.platform.RefreshFact.State.FULL
            Mode.CALM -> io.github.matiyaaa.fuse.ui.shell.platform.RefreshFact.State.CALM
            Mode.RELEASED -> io.github.matiyaaa.fuse.ui.shell.platform.RefreshFact.State.RESTING
        }
        if (main) asked.value = io.github.matiyaaa.fuse.ui.shell.platform.RefreshFact(state, fullHz, calmHz)
    }

    /** A button, stick or touch: the full rate comes back before the frame it causes. */
    fun active() {
        lastInput = SystemClock.uptimeMillis()
        if (mode != Mode.FULL) pinNow()
    }

    /**
     * On the main thread, where frame metrics arrive: a frame was drawn (an animation not caused by
     * input). The one drawn as the request is let go doesn't count. Movement after a still spell
     * that no input started is decoration (or art arriving): it goes on at the calmer rate.
     */
    fun drew() {
        val now = SystemClock.uptimeMillis()
        if (mode == Mode.RELEASED && now - releasedAt < SETTLE_MS) return
        lastDrawn = now
        if (mode != Mode.RELEASED) return
        if (now - lastInput >= CALM_MS && mayCalm()) mode = Mode.CALM else pinNow()
    }

    private fun mayCalm(): Boolean = !FusePlayer.showingVideo && calm()?.also { calmHz = it } != null

    private fun release() {
        mode = Mode.RELEASED
        releasedAt = SystemClock.uptimeMillis()
        val attributes = window.attributes
        if (attributes.preferredDisplayModeId != 0) {
            attributes.preferredDisplayModeId = 0
            window.attributes = attributes
        }
    }

    companion object {
        private const val IDLE_MS = 3_000L
        private const val CALM_MS = 10_000L
        private const val CHECK_MS = 1_000L
        private const val SETTLE_MS = 500L
        private val holds = WeakHashMap<Window, RefreshHold>()

        /** What the main window asks of its screen now (null before it has asked). Main thread writes. */
        val asked = kotlinx.coroutines.flow.MutableStateFlow<io.github.matiyaaa.fuse.ui.shell.platform.RefreshFact?>(null)

        /** Input reached [window]. Main thread. */
        fun active(window: Window?) {
            window?.let { holds[it]?.active() }
        }
    }
}
