package io.github.matiyaaa.fuse.platform

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.Settings
import io.github.matiyaaa.fuse.ActivityHolder
import io.github.matiyaaa.fuse.ui.shell.store.AppUsage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Android's usage statistics, read only once the person gives Fuse Usage access in Android's
 * settings: when an emulator's screens came to the front and left it, so play time counts only the
 * time the game was on screen. Read once, as a session ends; nothing is watched meanwhile.
 */
class AndroidAppUsage(context: Context, private val activities: ActivityHolder) : AppUsage {
    private val appContext = context.applicationContext

    override fun allowed(): Boolean {
        val ops = appContext.getSystemService(AppOpsManager::class.java) ?: return false
        val mode = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), appContext.packageName)
            } else {
                @Suppress("DEPRECATION")
                ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), appContext.packageName)
            }
        }.getOrDefault(AppOpsManager.MODE_ERRORED)
        return mode == AppOpsManager.MODE_ALLOWED
    }

    override fun askForAccess() {
        // Straight to Fuse's own switch where the system has one, else the list of apps.
        activities.startFirst(
            Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, Uri.parse("package:${appContext.packageName}")),
            Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS),
        )
    }

    override suspend fun moves(packageName: String, from: Long, to: Long): List<Pair<Long, Boolean>>? = withContext(Dispatchers.IO) {
        if (!allowed() || to <= from) return@withContext null
        val stats = appContext.getSystemService(UsageStatsManager::class.java) ?: return@withContext null
        val events = runCatching { stats.queryEvents(from, to) }.getOrNull() ?: return@withContext null
        val e = UsageEvents.Event()
        val out = ArrayList<Pair<Long, Boolean>>()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            if (e.packageName != packageName) continue
            when (e.eventType) {
                CAME_TO_FRONT -> out += e.timeStamp to true
                LEFT_FRONT -> out += e.timeStamp to false
            }
        }
        out
    }

    private companion object {
        /** ACTIVITY_RESUMED and ACTIVITY_PAUSED (MOVE_TO_FOREGROUND and MOVE_TO_BACKGROUND before Android 10): the same numbers on every version. */
        const val CAME_TO_FRONT = 1
        const val LEFT_FRONT = 2
    }
}
