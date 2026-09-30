package io.github.matiyaaa.fuse

import android.app.Activity
import android.app.ActivityOptions
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import io.github.matiyaaa.fuse.model.DisplayInfo
import io.github.matiyaaa.fuse.model.DualScreenMode
import io.github.matiyaaa.fuse.model.Support
import io.github.matiyaaa.fuse.services.DualScreenHandoff
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.lang.ref.WeakReference
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
 * Starts and stops [CompanionActivity] on the second screen to match the dual-screen setting, and
 * keeps it in front of whatever else is there (the vendor's second-screen launcher, a previous app)
 * each time Fuse comes back.
 *
 * - A display Android refused, or that put the companion on the main screen, is not tried again
 *   this session, so there is no open and close loop.
 * - Losing the display is only acted on after [GRACE_MS]: second panels report DOZE, UNKNOWN or
 *   briefly disappear while they change state.
 * - While a game that uses both screens runs, the companion stays closed until Fuse resumes.
 * - A [CompanionHomeActivity] (Fuse as the second screen's Home) is never closed from here.
 */
class CompanionScreens(private val app: FuseApplication) : DualScreenHandoff {
    private val refusedDisplays = mutableSetOf<Int>()
    private val handler = Handler(Looper.getMainLooper())
    private var fromRef: WeakReference<Activity>? = null
    private var mode: DualScreenMode = DualScreenMode.OFF
    private var graceCheck: Runnable? = null

    /** True from the launch of a dual-screen game until Fuse's main screen resumes. */
    @Volatile private var suppressed = false

    private val monitor get() = app.platformUi.displayMonitor

    /**
     * Follows [mode] and the current displays. [bringToFront] also starts the companion again when it
     * runs but is not in front on its display.
     */
    fun update(from: Activity, mode: DualScreenMode, bringToFront: Boolean = false) {
        fromRef = WeakReference(from)
        this.mode = mode
        val running = app.activities.companion
        if (!wants(mode)) {
            cancelGrace()
            if (running != null) finish(running, if (suppressed) "a game uses both screens" else "the second screen setting is off")
            return
        }
        val runningOn = running?.displayIdCompat()
        val target = monitor.secondary(preferredId = runningOn)
        if (running != null && target?.id != runningOn) {
            // Its display is gone, off or not usable: close it only if that lasts.
            startGrace()
            return
        }
        cancelGrace()
        if (target == null) return
        if (running != null) {
            if (bringToFront && !running.isInFront()) launch(from, target, "brought back in front")
            return
        }
        val home = app.activities.homeCompanion
        if (home != null && home.displayIdCompat() == target.id && (!bringToFront || home.isInFront())) return
        if (target.id in refusedDisplays) return
        if (target.canLaunchActivities == Support.NO) {
            refuse(target.id, "Android does not allow activities there")
            return
        }
        launch(from, target, "started")
    }

    /** Fuse's main screen resumed: games are over, and the companion goes back in front. */
    fun onMainResumed(from: Activity, mode: DualScreenMode) {
        if (suppressed) SecondScreenLog.add("Fuse resumed, companion allowed again")
        suppressed = false
        update(from, mode, bringToFront = true)
    }

    /** A game that draws on both screens is starting: it gets the second screen until Fuse resumes. */
    override fun beforeDualScreenGame() {
        suppressed = true
        cancelGrace()
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
        cancelGrace()
        app.activities.companion?.let { finish(it, "Fuse closed") }
    }

    private fun wants(mode: DualScreenMode): Boolean =
        SUPPORTED && !suppressed && (mode == DualScreenMode.LIBRARY_COMPANION || mode == DualScreenMode.GAME_COMPANION)

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

    private fun startGrace() {
        if (graceCheck != null) return
        val check = Runnable {
            graceCheck = null
            val running = app.activities.companion ?: return@Runnable
            val runningOn = running.displayIdCompat()
            if (monitor.secondary(preferredId = runningOn)?.id == runningOn) return@Runnable
            finish(running, "display $runningOn was gone or off for ${GRACE_MS / 1000} s")
            // Another usable screen may be there: follow the setting on it.
            fromRef?.get()?.takeUnless { it.isFinishing || it.isDestroyed }?.let { from ->
                val started = (from as? LifecycleOwner)?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.STARTED) ?: true
                if (started) update(from, mode)
            }
        }
        graceCheck = check
        handler.postDelayed(check, GRACE_MS)
    }

    private fun cancelGrace() {
        graceCheck?.let(handler::removeCallbacks)
        graceCheck = null
    }

    private fun CompanionActivity.isInFront(): Boolean = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)

    companion object {
        /** How long a display may be missing or off before the companion on it closes. */
        const val GRACE_MS = 2_000L

        /**
         * Android 10+ keeps activities on both screens resumed at once. Before that, starting the
         * companion would pause the main screen, so Fuse stays single-screen there.
         */
        val SUPPORTED: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
    }
}
