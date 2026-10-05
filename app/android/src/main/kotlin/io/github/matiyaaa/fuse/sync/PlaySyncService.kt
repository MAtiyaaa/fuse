package io.github.matiyaaa.fuse.sync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.github.matiyaaa.fuse.MainActivity
import io.github.matiyaaa.fuse.R

/**
 * Runs while a game Fuse Sync follows is being played. Android stops apps in the background soon
 * after they leave the screen; this keeps Fuse running so it can send the save as soon as the game
 * writes it. When the screen goes off (the lid closes), Fuse looks at the save at once and sends
 * it, holding the device awake only for those few seconds. It ends when the save is sent after
 * the game stops.
 */
class PlaySyncService : Service() {
    private var receiver: BroadcastReceiver? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val title = intent?.getStringExtra(EXTRA_TITLE).orEmpty()
        try {
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(title), type)
        } catch (e: RuntimeException) {
            // Android wouldn't let it start now: the save still goes when Fuse is back.
            stopSelf()
            return START_NOT_STICKY
        }
        if (receiver == null) {
            val r = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (intent.action == Intent.ACTION_SCREEN_OFF) sendNow()
                }
            }
            ContextCompat.registerReceiver(this, r, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED)
            receiver = r
        }
        return START_NOT_STICKY
    }

    /** The screen went off mid-game: send what the game saved, awake just long enough. */
    private fun sendNow() {
        val send = onScreenOff ?: return
        val lock = getSystemService(PowerManager::class.java)?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "fuse:sync-save")
        runCatching { lock?.acquire(SEND_AWAKE_MS) }
        send { runCatching { if (lock?.isHeld == true) lock.release() } }
    }

    // Android 15 limits how long this kind of service runs in a day: past that, it ends quietly.
    override fun onTimeout(startId: Int, fgsType: Int) {
        stopSelf()
    }

    override fun onDestroy() {
        receiver?.let { runCatching { unregisterReceiver(it) } }
        receiver = null
        super.onDestroy()
    }

    private fun notification(title: String): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager?.getNotificationChannel(CHANNEL) == null) {
            manager?.createNotificationChannel(
                NotificationChannel(CHANNEL, "Fuse Sync while playing", NotificationManager.IMPORTANCE_MIN).apply {
                    description = "Shown while Fuse Sync keeps a game's save in step"
                    setShowBadge(false)
                },
            )
        }
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_sync_save)
            .setContentTitle(if (title.isBlank()) "Keeping your save in step" else "Keeping $title's save in step")
            .setContentText("Fuse Sync sends it to your host as soon as the game saves")
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_DEFERRED)
            .build()
    }

    companion object {
        private const val EXTRA_TITLE = "io.github.matiyaaa.fuse.sync.TITLE"
        private const val CHANNEL = "sync-play"
        private const val NOTIFICATION_ID = 7_302
        private const val SEND_AWAKE_MS = 30_000L

        /** Set by the app: looks at the playing game's save and sends it, then calls back. */
        @Volatile var onScreenOff: ((done: () -> Unit) -> Unit)? = null

        fun start(context: Context, title: String) {
            runCatching { ContextCompat.startForegroundService(context, Intent(context, PlaySyncService::class.java).putExtra(EXTRA_TITLE, title)) }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, PlaySyncService::class.java)) }
        }
    }
}
