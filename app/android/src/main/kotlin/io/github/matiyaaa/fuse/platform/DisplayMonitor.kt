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
import io.github.matiyaaa.fuse.SecondScreenLog
import io.github.matiyaaa.fuse.model.DisplayInfo
import io.github.matiyaaa.fuse.model.Support
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Connected displays, kept current through a [DisplayManager.DisplayListener].
 *
 * [DisplayInfo.isOn] means "usable": every state but OFF. Dual-screen handhelds report their second
 * panel as DOZE or UNKNOWN while it changes state, and treating that as gone would close and reopen
 * the companion screen.
 */
class DisplayMonitor(context: Context) {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(DisplayManager::class.java)

    /** Ids of displays that look like screen recording, casting or developer overlays. */
    @Volatile private var virtualIds: Set<Int> = emptySet()
    private var lastSummary: String? = null

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

    /**
     * The display a companion screen should use. [preferredId] (the display the companion is on now)
     * wins while it is still usable, so a screen that appears later never takes its place. Otherwise
     * a real screen comes before a virtual one (recording, casting), which is only used when nothing
     * else exists, and a presentation display before any other.
     */
    fun secondary(preferredId: Int? = null): DisplayInfo? {
        val others = _displays.value.filter { !it.isPrimary && it.isOn }
        if (preferredId != null) others.firstOrNull { it.id == preferredId }?.let { return it }
        val real = others.filter { it.id !in virtualIds }
        val pool = real.filter { it.canLaunchActivities != Support.NO }.ifEmpty { real }.ifEmpty { others }
        return pool.firstOrNull { it.isPresentation } ?: pool.firstOrNull()
    }

    private fun read(): List<DisplayInfo> {
        val dm = manager ?: return emptyList()
        val presentationIds = dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION).map { it.displayId }.toSet()
        val displays = dm.displays.toList()
        virtualIds = displays.filter(::looksVirtual).map { it.displayId }.toSet()
        logChanges(displays)
        return displays.map { d ->
            val mode = d.mode
            DisplayInfo(
                id = d.displayId,
                name = d.name ?: "Display ${d.displayId}",
                widthPx = mode.physicalWidth,
                heightPx = mode.physicalHeight,
                refreshRate = mode.refreshRate,
                isPrimary = d.displayId == Display.DEFAULT_DISPLAY,
                isPresentation = d.displayId in presentationIds || (d.flags and Display.FLAG_PRESENTATION) != 0,
                isOn = d.state != Display.STATE_OFF,
                canLaunchActivities = canLaunchOn(d.displayId),
            )
        }
    }

    /**
     * Private displays belong to one app and never take other windows. Recording and casting apps
     * create public virtual displays, which Android does not mark as virtual for apps, so their usual
     * names are matched too. Built-in second panels are neither.
     */
    private fun looksVirtual(d: Display): Boolean {
        if (d.displayId == Display.DEFAULT_DISPLAY) return false
        if ((d.flags and Display.FLAG_PRIVATE) != 0) return true
        val name = d.name?.lowercase().orEmpty()
        return VIRTUAL_NAME_HINTS.any { it in name }
    }

    /** Adds the display list to [SecondScreenLog] when it changes. */
    private fun logChanges(displays: List<Display>) {
        val summary = displays.joinToString("; ") { d ->
            val virtual = if (d.displayId in virtualIds) " virtual" else ""
            "#${d.displayId} ${d.name} ${stateName(d.state)} flags=0x${Integer.toHexString(d.flags)}$virtual"
        }
        if (summary == lastSummary) return
        lastSummary = summary
        SecondScreenLog.add("Displays: ${summary.ifEmpty { "none" }}")
    }

    private fun stateName(state: Int): String = when (state) {
        Display.STATE_ON -> "on"
        Display.STATE_OFF -> "off"
        Display.STATE_DOZE -> "doze"
        Display.STATE_DOZE_SUSPEND -> "doze-suspend"
        Display.STATE_ON_SUSPEND -> "on-suspend"
        Display.STATE_VR -> "vr"
        else -> "unknown"
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

    private companion object {
        val VIRTUAL_NAME_HINTS = listOf("virtual", "record", "capture", "cast", "mirror", "overlay", "projection")
    }
}
