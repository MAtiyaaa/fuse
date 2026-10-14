package io.github.matiyaaa.fuse

import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import android.view.FrameMetrics
import android.view.Window
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
internal suspend fun followWindowRefresh(window: Window, app: FuseApplication, store: FuseStore, fallback: Display? = null): Unit = coroutineScope {
    val manager = window.context.getSystemService(DisplayManager::class.java)
    fun activeDisplay(): Display? = window.decorView.display ?: fallback
        ?: manager?.getDisplay(Display.DEFAULT_DISPLAY)
    var previous = 0L
    val intervals = ArrayDeque<Long>()
    var publishedAt = 0L
    val hold = RefreshHold(window)
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
 * Holds a window at its chosen refresh rate only while something moves.
 *
 * A window's preferred display mode fixes the screen's lowest and highest rate to that mode's, so
 * a phone whose screen can slow down to a few times a second over a still picture (LTPO panels)
 * kept refreshing at 120 Hz while Fuse sat still. After [IDLE_MS] with no input and nothing drawn,
 * the request is let go and the phone does as it likes with a still screen; the next button, touch
 * or drawn frame asks for the rate again before anything moves (input comes before the frame it
 * causes), so movement always gets the full rate.
 */
internal class RefreshHold(private val window: Window) {
    var pin: () -> Float? = { null }
    private var lastActive = SystemClock.uptimeMillis()
    private var released = false
    private var releasedAt = 0L
    private val handler = Handler(Looper.getMainLooper())
    private val check = object : Runnable {
        override fun run() {
            if (!released && SystemClock.uptimeMillis() - lastActive >= IDLE_MS) release()
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
        lastActive = SystemClock.uptimeMillis()
        released = false
        return pin()
    }

    /** A button, stick or touch: the rate comes back before the frame it causes. */
    fun active() {
        lastActive = SystemClock.uptimeMillis()
        if (released) pinNow()
    }

    /** On the main thread, where frame metrics arrive: a frame was drawn (an animation not caused by input). The one drawn as the request is let go doesn't count. */
    fun drew() {
        if (released && SystemClock.uptimeMillis() - releasedAt < SETTLE_MS) return
        active()
    }

    private fun release() {
        released = true
        releasedAt = SystemClock.uptimeMillis()
        val attributes = window.attributes
        if (attributes.preferredDisplayModeId != 0) {
            attributes.preferredDisplayModeId = 0
            window.attributes = attributes
        }
    }

    companion object {
        private const val IDLE_MS = 3_000L
        private const val CHECK_MS = 1_000L
        private const val SETTLE_MS = 500L
        private val holds = WeakHashMap<Window, RefreshHold>()

        /** Input reached [window]. Main thread. */
        fun active(window: Window?) {
            window?.let { holds[it]?.active() }
        }
    }
}
