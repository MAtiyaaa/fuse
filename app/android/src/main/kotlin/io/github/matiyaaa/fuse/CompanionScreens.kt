package io.github.matiyaaa.fuse

import android.app.Activity
import android.app.ActivityOptions
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Build
import io.github.matiyaaa.fuse.model.DualScreenMode
import io.github.matiyaaa.fuse.model.Support

/**
 * Starts and stops [CompanionActivity] on the second screen to match the dual-screen setting. If
 * Android refuses that display, Fuse quietly stays single-screen for it rather than retrying.
 */
class CompanionScreens(private val app: FuseApplication) {
    private val refusedDisplays = mutableSetOf<Int>()

    fun update(from: Activity, mode: DualScreenMode) {
        val wanted = SUPPORTED && (mode == DualScreenMode.LIBRARY_COMPANION || mode == DualScreenMode.GAME_COMPANION)
        val target = app.platformUi.displayMonitor.secondary()
        val running = app.activities.companion
        if (!wanted || target == null) {
            running?.finish()
            return
        }
        if (running != null && running.displayIdCompat() == target.id) return
        running?.finish()
        if (target.id in refusedDisplays || target.canLaunchActivities == Support.NO) return
        val intent = Intent(from, CompanionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val options = ActivityOptions.makeBasic().setLaunchDisplayId(target.id)
        try {
            from.startActivity(intent, options.toBundle())
        } catch (e: SecurityException) {
            refusedDisplays += target.id
        } catch (e: ActivityNotFoundException) {
            refusedDisplays += target.id
        } catch (e: IllegalArgumentException) {
            refusedDisplays += target.id
        }
    }

    fun stop() {
        app.activities.companion?.finish()
    }

    companion object {
        /**
         * Android 10+ keeps activities on both screens resumed at once. Before that, starting the
         * companion would pause the main screen, so Fuse stays single-screen there.
         */
        val SUPPORTED: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
    }
}
