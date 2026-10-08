package io.github.matiyaaa.fuse

import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
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
    val listener = Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
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
                val rate = window.preferRefreshRate(display, maximum)
                app.platformUi.displayMonitor.recordWindowRate(display.displayId, rate)
            }
        }
    }
    try { awaitCancellation() } finally { window.removeOnFrameMetricsAvailableListener(listener) }
}
