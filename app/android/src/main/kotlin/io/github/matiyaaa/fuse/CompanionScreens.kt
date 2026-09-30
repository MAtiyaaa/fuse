package io.github.matiyaaa.fuse

import android.app.Activity
import android.app.ActivityOptions
import android.content.ActivityNotFoundException
import android.content.Intent
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.SystemClock
import androidx.activity.ComponentActivity
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
 * - While Fuse is in front it is a [CompanionPresentation] owned by the main activity, the way
 *   Cartridge does it: AYN handhelds show a Presentation on the second panel where a separate
 *   activity never appeared. It closes when Fuse goes to the background, so games and other apps
 *   get the second screen, and comes back with Fuse.
 * - With "Game on the main screen, companion on the second", a game that uses one screen also gets
 *   a [CompanionActivity] on the second screen, started just before the game (Android only lets a
 *   visible app start activities). A display that refused it, or that put it on the main screen, is
 *   not tried again this session.
 * - While a game that uses both screens runs, nothing is shown there until Fuse resumes.
 * - A [CompanionHomeActivity] (Fuse as the second screen's Home) is never closed from here.
 */
class CompanionScreens(private val app: FuseApplication) : DualScreenHandoff {
    private val refusedDisplays = mutableSetOf<Int>()
    private val refusedPresentation = mutableSetOf<Int>()
    private val closedBySystem = mutableMapOf<Int, MutableList<Long>>()
    private var presentation: CompanionPresentation? = null
    private var mode: DualScreenMode = DualScreenMode.OFF

    /** True from the launch of a dual-screen game until Fuse's main screen resumes. */
    @Volatile private var suppressed = false

    private val monitor get() = app.platformUi.displayMonitor

    /** Follows [mode] and the current displays. Called on the main thread while [from] is started. */
    fun update(from: Activity, mode: DualScreenMode) {
        this.mode = mode
        if (!wants(mode)) {
            val reason = if (suppressed) "a game uses both screens" else "the second screen setting is off"
            hidePresentation(reason)
            app.activities.companion?.let { finish(it, reason) }
            return
        }
        val main = from as? ComponentActivity ?: return
        if (!main.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return
        // Fuse is in front: the Presentation takes over from a companion activity left by a game.
        app.activities.companion?.let { finish(it, "Fuse is in front again") }
        showPresentation(main)
    }

    /** Fuse's main screen resumed: games are over, and the companion comes back. */
    fun onMainResumed(from: Activity, mode: DualScreenMode) {
        if (suppressed) SecondScreenLog.add("Fuse resumed, companion allowed again")
        suppressed = false
        update(from, mode)
    }

    /** Fuse went to the background: the second screen is free for the game or app in front. */
    fun onMainStopped() {
        hidePresentation("Fuse went to the background")
    }

    /** A game that uses one screen is starting: in "companion during games" mode it gets the companion. */
    override fun beforeGame() {
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
        hidePresentation("Fuse closed")
        app.activities.companion?.let { finish(it, "Fuse closed") }
    }

    private fun wants(mode: DualScreenMode): Boolean =
        !suppressed && (mode == DualScreenMode.LIBRARY_COMPANION || mode == DualScreenMode.GAME_COMPANION)

    private fun showPresentation(main: ComponentActivity) {
        presentation?.let { current ->
            val id = current.display.displayId
            // A dozing or switched-off panel keeps it: it is there again when the panel wakes.
            if (current.isShowing && monitor.displays.value.any { it.id == id }) return
            hidePresentation("display $id is gone")
        }
        val target = monitor.presentationTarget() ?: return
        if (target.id in refusedPresentation) return
        val display = app.getSystemService(DisplayManager::class.java)?.getDisplay(target.id) ?: return
        val shown = CompanionPresentation(main, display) { CompanionContent(app) }
        shown.setOnDismissListener {
            // Closed by Android (display removed or changed), not by hidePresentation.
            if (presentation === shown) {
                presentation = null
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
        private const val LOOP_LIMIT = 3

        /**
         * Android 10+ keeps activities on both screens resumed at once. Before that, a companion
         * activity would pause the game, so only the Presentation (Fuse in front) is used there.
         */
        val ACTIVITY_SUPPORTED: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
    }
}
