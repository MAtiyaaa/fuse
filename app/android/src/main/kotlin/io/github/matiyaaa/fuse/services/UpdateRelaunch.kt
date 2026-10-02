package io.github.matiyaaa.fuse.services

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.os.Build
import androidx.core.app.NotificationCompat
import io.github.matiyaaa.fuse.MainActivity
import io.github.matiyaaa.fuse.R

/**
 * Brings Fuse back after it is updated. Android stops an app to replace it and starts nothing
 * afterwards, so after "Restart and update" (or any update while Fuse is the Home app) the user was
 * left on the system's own screen. Fuse marks its own update just before handing it to the
 * installer; once the new version is in, Android tells it ([UpdatedReceiver], and the installer's
 * result), and Fuse opens itself, once. The Home app may start itself from the background, and so
 * may an app allowed to display over other apps; Android 10 and later stop any other from doing so,
 * so there Fuse also leaves a notice, "Fuse is up to date", one tap from opening it. The notice goes
 * away as soon as Fuse is open.
 */
object UpdateRelaunch {
    private const val PREFS = "fuse.update"
    private const val ARMED_AT = "armedAt"
    private const val WINDOW_MS = 30 * 60_000L
    private const val CHANNEL = "fuse.updates"
    private const val NOTICE_ID = 4_201

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
            // Not allowed from the background here; the notice below opens it.
        }
        // Android may quietly refuse a start from the background: the notice is the way back then.
        if (!home && !canDrawOverlays(context)) showReopenNotice(context)
    }

    /** Fuse is open: the notice that would have opened it has done its job. */
    fun clearReopenNotice(context: Context) {
        context.getSystemService(NotificationManager::class.java)?.cancel(NOTICE_ID)
    }

    private fun showReopenNotice(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        if (manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "Updates", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "When Fuse has updated itself and is ready to open again"
                    setShowBadge(false)
                },
            )
        }
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val version = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
        val notice = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_updated)
            .setContentTitle("Fuse is up to date")
            .setContentText(if (version != null) "Version $version is installed. Tap to open Fuse." else "Tap to open Fuse.")
            .setContentIntent(open)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .build()
        try {
            manager.notify(NOTICE_ID, notice)
        } catch (e: SecurityException) {
            // Notifications are off for Fuse.
        }
    }

    private fun canDrawOverlays(context: Context): Boolean =
        android.provider.Settings.canDrawOverlays(context)

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
