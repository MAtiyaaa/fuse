package io.github.matiyaaa.fuse.platform

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Display
import io.github.matiyaaa.fuse.CompanionActivity
import io.github.matiyaaa.fuse.model.DisplayInfo
import io.github.matiyaaa.fuse.model.Support
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Connected displays, kept current through a [DisplayManager.DisplayListener]. */
class DisplayMonitor(context: Context) {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(DisplayManager::class.java)
    private val _displays = MutableStateFlow(read())
    val displays: StateFlow<List<DisplayInfo>> = _displays.asStateFlow()

    private val listener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = refresh()
        override fun onDisplayRemoved(displayId: Int) = refresh()
        override fun onDisplayChanged(displayId: Int) = refresh()
    }

    init {
        manager?.registerDisplayListener(listener, Handler(Looper.getMainLooper()))
    }

    fun refresh() {
        _displays.value = read()
    }

    /** The display a companion screen should use: a presentation display first, else any other screen that is on. */
    fun secondary(): DisplayInfo? {
        val others = _displays.value.filter { !it.isPrimary && it.isOn }
        return others.firstOrNull { it.isPresentation } ?: others.firstOrNull()
    }

    private fun read(): List<DisplayInfo> {
        val dm = manager ?: return emptyList()
        val presentationIds = dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION).map { it.displayId }.toSet()
        return dm.displays.map { d ->
            val mode = d.mode
            DisplayInfo(
                id = d.displayId,
                name = d.name ?: "Display ${d.displayId}",
                widthPx = mode.physicalWidth,
                heightPx = mode.physicalHeight,
                refreshRate = mode.refreshRate,
                isPrimary = d.displayId == Display.DEFAULT_DISPLAY,
                isPresentation = d.displayId in presentationIds || (d.flags and Display.FLAG_PRESENTATION) != 0,
                isOn = d.state == Display.STATE_ON || d.state == Display.STATE_ON_SUSPEND || d.state == Display.STATE_VR,
                canLaunchActivities = canLaunchOn(d.displayId),
            )
        }
    }

    private fun canLaunchOn(displayId: Int): Support {
        if (displayId == Display.DEFAULT_DISPLAY) return Support.YES
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return Support.UNKNOWN
        val am = appContext.getSystemService(ActivityManager::class.java) ?: return Support.UNKNOWN
        return try {
            val probe = Intent(appContext, CompanionActivity::class.java)
            if (am.isActivityStartAllowedOnDisplay(appContext, displayId, probe)) Support.YES else Support.NO
        } catch (e: RuntimeException) {
            Support.UNKNOWN
        }
    }
}
