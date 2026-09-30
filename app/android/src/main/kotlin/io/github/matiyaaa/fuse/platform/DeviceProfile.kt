package io.github.matiyaaa.fuse.platform

import android.app.ActivityManager
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display
import io.github.matiyaaa.fuse.model.CapabilityProfile

/**
 * Measures what the device can comfortably do from documented system information only (memory,
 * cores, the display's modes, the declared media performance class). No device-name lists.
 */
object DeviceProfile {
    fun measure(context: Context): CapabilityProfile {
        val am = context.getSystemService(ActivityManager::class.java)
        val memory = ActivityManager.MemoryInfo().also { am?.getMemoryInfo(it) }
        val dm = context.getSystemService(DisplayManager::class.java)
        val display = dm?.getDisplay(Display.DEFAULT_DISPLAY)
        val mode = display?.mode
        val metrics = context.resources.displayMetrics
        return CapabilityProfile(
            cpuCores = Runtime.getRuntime().availableProcessors(),
            totalRamMb = memory.totalMem / (1024 * 1024),
            isLowRamDevice = am?.isLowRamDevice == true,
            maxRefreshRate = display?.supportedModes
                ?.filter { mode == null || (it.physicalWidth == mode.physicalWidth && it.physicalHeight == mode.physicalHeight) }
                ?.maxOfOrNull { it.refreshRate }
                ?: display?.refreshRate ?: 60f,
            screenWidthPx = mode?.physicalWidth ?: metrics.widthPixels,
            screenHeightPx = mode?.physicalHeight ?: metrics.heightPixels,
            densityDpi = metrics.densityDpi,
            displayCount = dm?.displays?.size ?: 1,
            performanceClass = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.VERSION.MEDIA_PERFORMANCE_CLASS else 0,
            // Compose blur (RenderEffect) exists from Android 12.
            supportsBlur = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S,
            gpuRenderer = null,
        )
    }
}
