package io.github.matiyaaa.fuse.services

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import io.github.matiyaaa.fuse.MainActivity

/**
 * Brings Fuse back after it is updated. Android stops an app to replace it and starts nothing
 * afterwards, so after "Restart and update" (or any update while Fuse is the Home app) the user was
 * left on the system's own screen. Fuse marks its own update just before handing it to the
 * installer; once the new version is in, Android tells it ([UpdatedReceiver], and the installer's
 * result), and Fuse opens itself, once. The Home app may start itself from the background; other
 * apps may be stopped from doing so on Android 10 and later, so there it is a best effort.
 */
object UpdateRelaunch {
    private const val PREFS = "fuse.update"
    private const val ARMED_AT = "armedAt"
    private const val WINDOW_MS = 30 * 60_000L

    /** Called just before Fuse's own update goes to the installer. */
    fun arm(context: Context) {
        prefs(context).edit().putLong(ARMED_AT, System.currentTimeMillis()).commit()
    }

    /** The update was cancelled or failed: nothing to reopen. */
    fun disarm(context: Context) {
        prefs(context).edit().remove(ARMED_AT).commit()
    }

    /**
     * True when Fuse should open itself: it was updated after it marked its own update (within half
     * an hour), or it is the Home app, which must come back after any update.
     */
    fun shouldRelaunch(armedAt: Long, lastUpdateTime: Long, now: Long, isHome: Boolean): Boolean {
        if (isHome) return true
        return armedAt > 0 && now - armedAt in 0..WINDOW_MS && lastUpdateTime >= armedAt
    }

    /** The new version is installed: opens Fuse once, when [shouldRelaunch] says so. */
    fun onUpdated(context: Context) {
        val prefs = prefs(context)
        val armedAt = prefs.getLong(ARMED_AT, 0L)
        val home = isHome(context)
        if (armedAt == 0L && !home) return
        // Once, whichever of the two notices comes first.
        prefs.edit().remove(ARMED_AT).commit()
        val updated = try {
            context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
        } catch (e: PackageManager.NameNotFoundException) {
            return
        }
        if (!shouldRelaunch(armedAt, updated, System.currentTimeMillis(), home)) return
        try {
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: RuntimeException) {
            // Not allowed from the background here; Fuse opens next time the user starts it.
        }
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun isHome(context: Context): Boolean {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val resolved = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.resolveActivity(home, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()))
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)
            }
        } catch (e: RuntimeException) {
            null
        }
        return resolved?.activityInfo?.packageName == context.packageName
    }
}

/** Android's notice that this app was just replaced by a new version (sent only to the app itself). */
class UpdatedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) UpdateRelaunch.onUpdated(context)
    }
}
