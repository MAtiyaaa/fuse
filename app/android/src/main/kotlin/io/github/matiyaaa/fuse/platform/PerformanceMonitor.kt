package io.github.matiyaaa.fuse.platform

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.view.FrameMetrics
import android.view.Window
import io.github.matiyaaa.fuse.ActivityHolder
import io.github.matiyaaa.fuse.model.PerformanceMetric
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Real, measurable metrics only, sampled once a second while something shows them:
 * - Fuse's own frame rate and frame time, from the frames its window actually drew (FrameMetrics);
 * - free memory (ActivityManager), battery temperature (battery broadcast), the thermal state
 *   (PowerManager), and a CPU temperature only when a thermal zone is readable.
 * Android has no public way to read another app's frame rate, so games' FPS is never shown.
 */
class PerformanceMonitor(
    context: Context,
    private val activities: ActivityHolder,
    private val status: SystemStatusMonitor,
    scope: CoroutineScope,
) {
    private val appContext = context.applicationContext
    private val am = appContext.getSystemService(ActivityManager::class.java)
    private val power = appContext.getSystemService(PowerManager::class.java)

    private val frames = AtomicInteger()
    private val frameNanos = AtomicLong()
    private var metricsThread: HandlerThread? = null
    private var attachedWindow: Window? = null
    private val frameListener = Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
        if (metrics.getMetric(FrameMetrics.FIRST_DRAW_FRAME) == 0L) {
            frames.incrementAndGet()
            frameNanos.addAndGet(metrics.getMetric(FrameMetrics.TOTAL_DURATION))
        }
    }

    private var cpuZone: File? = null
    private var cpuZoneScanned = false

    val metrics: StateFlow<List<PerformanceMetric>> = flow {
        try {
            while (true) {
                withContext(Dispatchers.Main) { attachToWindow() }
                frames.set(0)
                frameNanos.set(0)
                delay(SAMPLE_MS)
                emit(withContext(Dispatchers.IO) { sample() })
            }
        } finally {
            withContext(Dispatchers.Main + kotlinx.coroutines.NonCancellable) { detach() }
        }
    }.stateIn(scope, SharingStarted.WhileSubscribed(2_000), emptyList())

    private fun attachToWindow() {
        val window = activities.main?.window
        if (window === attachedWindow) return
        detach()
        if (window == null) return
        val thread = metricsThread ?: HandlerThread("FuseFrameMetrics").also { it.start(); metricsThread = it }
        try {
            window.addOnFrameMetricsAvailableListener(frameListener, Handler(thread.looper))
            attachedWindow = window
        } catch (e: RuntimeException) {
            attachedWindow = null
        }
    }

    private fun detach() {
        val window = attachedWindow ?: return
        try {
            window.removeOnFrameMetricsAvailableListener(frameListener)
        } catch (e: RuntimeException) {
            // The window is gone.
        }
        attachedWindow = null
    }

    private fun sample(): List<PerformanceMetric> = buildList {
        val drawn = frames.get()
        val fps = (drawn * 1000L / SAMPLE_MS).toInt()
        if (attachedWindow != null) {
            add(
                PerformanceMetric(
                    key = "fuse_fps",
                    label = "Fuse frame rate",
                    value = if (drawn == 0) "Idle" else "$fps fps",
                    source = "Frames drawn by Fuse's window",
                ),
            )
            if (drawn > 0) {
                val ms = frameNanos.get() / drawn / 1_000_000.0
                add(PerformanceMetric("fuse_frame_time", "Fuse frame time", String.format(Locale.US, "%.1f ms", ms), source = "FrameMetrics"))
            }
        }
        am?.let { manager ->
            val info = ActivityManager.MemoryInfo().also(manager::getMemoryInfo)
            if (info.totalMem > 0) {
                val gb = 1024.0 * 1024 * 1024
                add(
                    PerformanceMetric(
                        key = "memory",
                        label = "Memory",
                        value = String.format(Locale.US, "%.1f of %.1f GB free", info.availMem / gb, info.totalMem / gb),
                        fraction = (1f - info.availMem.toFloat() / info.totalMem).coerceIn(0f, 1f),
                        source = "Android memory info",
                    ),
                )
            }
        }
        status.batteryTemperatureC?.let {
            add(PerformanceMetric("battery_temp", "Battery", String.format(Locale.US, "%.1f °C", it), source = "Battery status"))
        }
        cpuTemperature()?.let {
            add(PerformanceMetric("cpu_temp", "CPU", String.format(Locale.US, "%.0f °C", it), source = "Thermal zone"))
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && power != null) {
            val thermal = power.currentThermalStatus
            add(
                PerformanceMetric(
                    key = "thermal",
                    label = "Thermal state",
                    value = thermalName(thermal),
                    fraction = (thermal / 6f).coerceIn(0f, 1f),
                    source = "Android thermal service",
                ),
            )
        }
    }

    private fun thermalName(status: Int): String = when (status) {
        PowerManager.THERMAL_STATUS_NONE -> "Normal"
        PowerManager.THERMAL_STATUS_LIGHT -> "Warm"
        PowerManager.THERMAL_STATUS_MODERATE -> "Hot"
        PowerManager.THERMAL_STATUS_SEVERE -> "Throttling"
        PowerManager.THERMAL_STATUS_CRITICAL -> "Critical"
        PowerManager.THERMAL_STATUS_EMERGENCY, PowerManager.THERMAL_STATUS_SHUTDOWN -> "Emergency"
        else -> "Unknown"
    }

    /** A readable CPU thermal zone in degrees Celsius, or null (most devices deny access). */
    private fun cpuTemperature(): Float? {
        if (!cpuZoneScanned) {
            cpuZoneScanned = true
            cpuZone = try {
                File("/sys/class/thermal").listFiles { f -> f.name.startsWith("thermal_zone") }.orEmpty()
                    .sortedBy { it.name.removePrefix("thermal_zone").toIntOrNull() ?: Int.MAX_VALUE }
                    .firstOrNull { zone ->
                        val type = File(zone, "type").readTextOrNull()?.lowercase().orEmpty()
                        ("cpu" in type || "soc" in type) && File(zone, "temp").readTextOrNull()?.trim()?.toFloatOrNull() != null
                    }
                    ?.let { File(it, "temp") }
            } catch (e: SecurityException) {
                null
            }
        }
        val raw = cpuZone?.readTextOrNull()?.trim()?.toFloatOrNull() ?: return null
        val celsius = if (raw > 1000f) raw / 1000f else raw
        return celsius.takeIf { it in 1f..150f }
    }

    private fun File.readTextOrNull(): String? = try {
        if (canRead()) readText() else null
    } catch (e: Exception) {
        null
    }

    private companion object {
        const val SAMPLE_MS = 1_000L
    }
}
