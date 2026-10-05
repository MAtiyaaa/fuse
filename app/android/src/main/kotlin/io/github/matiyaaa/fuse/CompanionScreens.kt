package io.github.matiyaaa.fuse

import android.app.Activity
import android.app.ActivityOptions
import android.content.ActivityNotFoundException
import android.content.Intent
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import io.github.matiyaaa.fuse.model.DisplayInfo
import io.github.matiyaaa.fuse.model.DualScreenMode
import io.github.matiyaaa.fuse.model.Support
import io.github.matiyaaa.fuse.services.DualScreenHandoff
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * Recent second-screen events (companion started, closed and why, refused displays, display list
 * changes), newest last, for the "Second screen status" row in Settings. Lives for the process only.
 */
object SecondScreenLog {
    private const val MAX_ENTRIES = 60
    private val time = DateTimeFormatter.ofPattern("HH:mm:ss")
    private val _entries = MutableStateFlow<List<String>>(emptyList())
    val entries: StateFlow<List<String>> = _entries.asStateFlow()

    fun add(event: String) {
        val line = "${LocalTime.now().format(time)} $event"
        _entries.update { (it + line).takeLast(MAX_ENTRIES) }
    }
}

/**
 * Fuse's companion on the second screen, following the dual-screen setting.
 *
 * - It is a [CompanionPresentation] made by the main activity, the way Cartridge does it: AYN
 *   handhelds show a Presentation on the second panel where a separate activity never appeared.
 * - It stays when Fuse goes to the background for a game or an app Fuse opened, showing the game
 *   being played, and never takes the controller. Its controls page then offers Hide, which frees
 *   the second screen until Fuse is back.
 * - The second screen belongs to the app in front: when another app comes to the front on its own
 *   (Home, recent apps; Cartridge with its own second screen), the companion steps aside, and when
 *   Fuse is back in front it is shown again, on top of whatever another app left there.
 * - While a game that uses both screens runs, or something was opened on the second screen,
 *   nothing is shown there until Fuse resumes.
 * - Where a display refuses the Presentation, "Companion while playing" falls back to a
 *   [CompanionActivity] started just before a game (Android only lets a visible app start
 *   activities). A display that refused it, or that put it on the main screen, is not tried again
 *   this session.
 * - A [CompanionHomeActivity] (Fuse as the second screen's Home) is never closed from here.
 * - Flipped ("Menus below"): the same Presentation carries Fuse's own menus ([menus], made by the
 *   main activity, which keeps the controller), and the main screen shows the showcase instead
 *   ([flipped]). While Fuse is away the companion is drawn over the menus, which wait underneath.
 *   Where no second screen takes the Presentation, the menus stay on the main screen.
 */
class CompanionScreens(private val app: FuseApplication) : DualScreenHandoff {
    private val refusedDisplays = mutableSetOf<Int>()
    private val refusedPresentation = mutableSetOf<Int>()
    private val closedBySystem = mutableMapOf<Int, MutableList<Long>>()
    private var presentation: CompanionPresentation? = null
    private var mode: DualScreenMode = DualScreenMode.OFF

    /** The person chose "Menus below". */
    private var flippedWanted = false

    /** The menus are on the second screen now: the main screen shows the showcase. */
    private val _flipped = MutableStateFlow(false)
    val flipped: StateFlow<Boolean> = _flipped.asStateFlow()

    /** Fuse's menus for the second screen, from the main activity (which owns the controller input). */
    var menus: (@Composable () -> Unit)? = null

    /** Said once a session: "Menus below" was chosen but no second screen took them. */
    private var flippedRefusedLogged = false

    /** True from the launch of a dual-screen game until Fuse's main screen resumes. */
    @Volatile private var suppressed = false

    /** Hidden from its controls page while Fuse was in the background; back when Fuse resumes. */
    private var hiddenUntilBack = false

    /** When Fuse last opened something itself (elapsed realtime), to tell its own launches from another app coming to the front. */
    @Volatile private var launchedAt = 0L

    /** True while Fuse's main screen is stopped and the companion carries on alone. */
    private val _away = MutableStateFlow(false)
    val away: StateFlow<Boolean> = _away.asStateFlow()

    private val monitor get() = app.platformUi.displayMonitor

    /**
     * The main screen turned off while the companion holds the second one on: a lid closed (the
     * AYN Thor), or the power button. The companion lets go of keeping the screen on, so the device
     * sleeps as it would without Fuse instead of draining the battery with its lid shut.
     */
    @Volatile private var mainOff = false

    private val mainScreenWatch = object : android.hardware.display.DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            if (displayId != android.view.Display.DEFAULT_DISPLAY) return
            val dm = app.getSystemService(android.hardware.display.DisplayManager::class.java) ?: return
            val state = dm.getDisplay(android.view.Display.DEFAULT_DISPLAY)?.state ?: return
            val off = state == android.view.Display.STATE_OFF || state == android.view.Display.STATE_DOZE || state == android.view.Display.STATE_DOZE_SUSPEND
            if (off == mainOff) return
            mainOff = off
            if (off) {
                presentation?.setAway(true)
                SecondScreenLog.add("Main screen off (lid closed, power, or only the second screen on): the second screen may sleep")
            } else if (!_away.value) {
                presentation?.setAway(false)
            }
            // Only the second screen on (the AYN menu's Mode, or the top screen locked off): the
            // menus follow it there, and come back when the main screen does.
            val main = app.activities.main
            if (main != null && main.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) update(main, mode, flippedWanted)
        }
    }

    init {
        try {
            app.getSystemService(android.hardware.display.DisplayManager::class.java)
                ?.registerDisplayListener(mainScreenWatch, android.os.Handler(android.os.Looper.getMainLooper()))
        } catch (e: RuntimeException) {
            // No display service: nothing to watch.
        }
    }

    /** The device is awake (not a lid closed or the power button): a screen turned off on purpose. */
    private fun interactive(): Boolean =
        app.getSystemService(android.os.PowerManager::class.java)?.isInteractive ?: true

    /** Follows [mode] and the current displays. Called on the main thread while [from] is started. */
    fun update(from: Activity, mode: DualScreenMode, flipped: Boolean = flippedWanted) {
        this.mode = mode
        this.flippedWanted = flipped
        if (!wants(mode)) {
            val reason = if (suppressed) "a game uses both screens" else "the second screen setting is off"
            hidePresentation(reason)
            app.activities.companion?.let { finish(it, reason) }
            return
        }
        val main = from as? ComponentActivity ?: return
        if (!main.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return
        _away.value = false
        // Fuse is in front: the Presentation takes over from a companion activity left by a game.
        app.activities.companion?.let { finish(it, "Fuse is in front again") }
        showPresentation(main)
        // The menus go where a screen is on: below when chosen and the second screen is on, and
        // below too while the main screen is off and the second one isn't (only one is in use).
        val belowOn = presentation?.let { p -> monitor.displays.value.any { it.id == p.display.displayId && it.isOn } } == true
        val menusBelow = menus != null && belowOn && (flippedWanted || (mainOff && interactive()))
        if (flippedWanted && presentation != null && !belowOn && _flipped.value) {
            SecondScreenLog.add("The second screen is off: the menus come up to the main screen until it is back")
        }
        if (flippedWanted && !menusBelow && !flippedRefusedLogged) {
            flippedRefusedLogged = true
            SecondScreenLog.add("Menus below was chosen, but no second screen took them: they stay on the main screen")
        }
        // With the main screen off the second may sleep as usual, unless the menus are on it now.
        presentation?.setAway(mainOff && !menusBelow)
        if (_flipped.value != menusBelow) {
            _flipped.value = menusBelow
            SecondScreenLog.add(if (menusBelow) "Menus moved to the second screen" else "Menus back on the main screen")
        }
    }

    /** Fuse's main screen resumed: games are over, and the companion comes back. */
    fun onMainResumed(from: Activity, mode: DualScreenMode, flipped: Boolean = flippedWanted) {
        if (suppressed || hiddenUntilBack) SecondScreenLog.add("Fuse resumed, companion allowed again")
        suppressed = false
        hiddenUntilBack = false
        // Back from the background: another app may have put its own second screen over Fuse's
        // meanwhile, so Fuse's is shown again, on top.
        if (_away.value && presentation != null) hidePresentation("Fuse is in front again, shown on top")
        update(from, mode, flipped)
    }

    /**
     * Fuse went to the background, for a game or an app it opened: the companion stays beside it.
     * Two-screen games and launches on the second screen have already put it away.
     */
    fun onMainStopped() {
        _away.value = true
        val current = presentation ?: return
        if (SystemClock.elapsedRealtime() - launchedAt > HANDOFF_MS) {
            // Fuse didn't open what is in front now: that app's second screen, not Fuse's.
            hidePresentation("another app is in front")
            return
        }
        current.setAway(true)
        SecondScreenLog.add("Companion stays on display ${current.display.displayId} while Fuse is in the background")
    }

    /** The main activity is gone without Fuse closing: the Presentation it made goes with it. */
    fun onMainDestroyed() {
        hidePresentation("Fuse's main screen closed")
    }

    /** Hide on the companion's controls page: the second screen is free until Fuse is back. */
    fun hideUntilBack() {
        hiddenUntilBack = true
        hidePresentation("hidden until Fuse is back")
    }

    override fun beforeLaunch() {
        launchedAt = SystemClock.elapsedRealtime()
    }

    /** A game that uses one screen is starting: in "companion during games" mode it gets the companion. */
    override fun beforeGame() {
        // The Presentation stays beside the game; an activity is only for displays that refused it.
        if (presentation?.isShowing == true) return
        if (mode != DualScreenMode.GAME_COMPANION || suppressed || !ACTIVITY_SUPPORTED) return
        val from = app.activities.main ?: return
        val target = monitor.secondary() ?: return
        if (target.id in refusedDisplays) return
        if (target.canLaunchActivities == Support.NO) {
            refuse(target.id, "Android does not allow activities there")
            return
        }
        launch(from, target, "started for the game")
    }

    /** A game that draws on both screens is starting: it gets the second screen until Fuse resumes. */
    override fun beforeDualScreenGame() {
        suppressed = true
        hidePresentation("a game uses both screens")
        app.activities.companion?.let { finish(it, "a game uses both screens") }
    }

    override fun dualScreenGameFailed() {
        suppressed = false
        SecondScreenLog.add("The game did not start, companion allowed again")
        val from = app.activities.main ?: return
        if (from.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) update(from, mode)
    }

    /** The companion found itself on the main screen instead of [displayId]; that display is not tried again. */
    fun onLandedOnMainScreen(displayId: Int) {
        if (displayId >= 0) {
            refuse(displayId, "Android opened the companion on the main screen")
        } else {
            SecondScreenLog.add("Companion closed: it was opened on the main screen")
        }
    }

    fun stop() {
        _away.value = false
        hidePresentation("Fuse closed")
        app.activities.companion?.let { finish(it, "Fuse closed") }
    }

    private fun wants(mode: DualScreenMode): Boolean =
        !suppressed && !hiddenUntilBack && (flippedWanted || mode == DualScreenMode.LIBRARY_COMPANION || mode == DualScreenMode.GAME_COMPANION)

    private fun showPresentation(main: ComponentActivity) {
        presentation?.let { current ->
            val id = current.display.displayId
            // A dozing or switched-off panel keeps it: it is there again when the panel wakes.
            if (current.isShowing && monitor.displays.value.any { it.id == id }) return
            hidePresentation("display $id is gone")
        }
        val target = monitor.presentationTarget(mainDisplay = main.displayIdCompat()) ?: return
        if (target.id in refusedPresentation) return
        val display = app.getSystemService(DisplayManager::class.java)?.getDisplay(target.id) ?: return
        var made: CompanionPresentation? = null
        var lastShown: Boolean? = null
        val shown = CompanionPresentation(
            main, display,
            onShown = onShown@{ visible ->
                // Only while it is the companion (not while Fuse closes it), and only changes after
                // the first time, so a device that hides it from a background app shows up in
                // Settings, Second screen status.
                if (made == null || presentation !== made) return@onShown
                if (lastShown != null && lastShown != visible) {
                    SecondScreenLog.add(if (visible) "Companion visible again on display ${target.id}" else "Android hid the companion on display ${target.id}")
                }
                lastShown = visible
            },
        ) {
            val fuseAway by away.collectAsState()
            val menusHere by flipped.collectAsState()
            val ownMenus = menus
            if (menusHere && ownMenus != null) {
                // Flipped: Fuse's menus here; while a game is in front, its companion over them.
                Box(Modifier.fillMaxSize()) {
                    ownMenus()
                    if (fuseAway) CompanionContent(app, onHide = ::hideUntilBack)
                }
            } else {
                CompanionContent(app, onHide = if (fuseAway) ::hideUntilBack else null)
            }
        }
        made = shown
        shown.setOnDismissListener {
            // Closed by Android (display removed or changed), not by hidePresentation.
            if (presentation === shown) {
                presentation = null
                _flipped.value = false
                closedBySystem(target.id)
            }
        }
        try {
            presentation = shown
            shown.show()
            SecondScreenLog.add("Companion shown on display ${target.id} (${target.name})")
        } catch (e: RuntimeException) {
            // InvalidDisplayException, BadTokenException: this display does not take it.
            presentation = null
            if (refusedPresentation.add(target.id)) SecondScreenLog.add("Display ${target.id} refused the companion: ${e::class.simpleName}")
        }
    }

    private fun hidePresentation(reason: String) {
        val current = presentation ?: return
        presentation = null
        if (_flipped.value) {
            _flipped.value = false
            SecondScreenLog.add("Menus back on the main screen")
        }
        SecondScreenLog.add("Companion closed on display ${current.display.displayId}: $reason")
        try {
            current.dismiss()
        } catch (e: RuntimeException) {
            // Its window is already gone with the display.
        }
    }

    /** A display whose Presentation Android keeps closing is left alone, so there is no open and close loop. */
    private fun closedBySystem(displayId: Int) {
        val now = SystemClock.elapsedRealtime()
        val recent = closedBySystem.getOrPut(displayId) { mutableListOf() }
        recent.removeAll { now - it > LOOP_WINDOW_MS }
        recent += now
        SecondScreenLog.add("Android closed the companion on display $displayId")
        if (recent.size >= LOOP_LIMIT && refusedPresentation.add(displayId)) {
            SecondScreenLog.add("Display $displayId closed the companion $LOOP_LIMIT times: not tried again this session")
        }
    }

    private fun launch(from: Activity, target: DisplayInfo, what: String) {
        val intent = Intent(from, CompanionActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            .putExtra(CompanionActivity.EXTRA_TARGET_DISPLAY, target.id)
        val options = ActivityOptions.makeBasic().setLaunchDisplayId(target.id)
        try {
            from.startActivity(intent, options.toBundle())
            SecondScreenLog.add("Companion $what on display ${target.id} (${target.name})")
        } catch (e: SecurityException) {
            refuse(target.id, "SecurityException")
        } catch (e: ActivityNotFoundException) {
            refuse(target.id, "ActivityNotFoundException")
        } catch (e: IllegalArgumentException) {
            refuse(target.id, "IllegalArgumentException")
        }
    }

    private fun refuse(displayId: Int, reason: String) {
        if (refusedDisplays.add(displayId)) SecondScreenLog.add("Display $displayId refused for this session: $reason")
    }

    private fun finish(companion: CompanionActivity, reason: String) {
        if (companion.isDisplayHome) return
        SecondScreenLog.add("Companion closed on display ${companion.displayIdCompat()}: $reason")
        companion.finish()
    }

    companion object {
        /** How long a display may be missing or off before a companion activity on it closes. */
        const val GRACE_MS = 2_000L

        private const val LOOP_WINDOW_MS = 15_000L

        /** How soon after Fuse opens something its main screen stops, for that to count as Fuse's doing. */
        private const val HANDOFF_MS = 6_000L
        private const val LOOP_LIMIT = 3

        /**
         * Android 10+ keeps activities on both screens resumed at once. Before that, a companion
         * activity would pause the game, so only the Presentation (Fuse in front) is used there.
         */
        val ACTIVITY_SUPPORTED: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
    }
}
