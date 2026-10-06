package io.github.matiyaaa.fuse.transfer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.github.matiyaaa.fuse.MainActivity
import io.github.matiyaaa.fuse.R

/**
 * Runs while Downloads moves something (a game from RomM, an upload, an emulator from the Store).
 * Android stops apps soon after they leave the screen; this keeps Fuse going so a long transfer
 * finishes while the person plays or the screen is off. Its notification says what is moving and
 * how far along it is, and opens Fuse. It ends when the queue has nothing left to move. If Android
 * won't start it, nothing breaks: transfers carry on while Fuse is open and resume where they left
 * off otherwise.
 */
class TransferService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val text = intent?.getStringExtra(EXTRA_TEXT).orEmpty()
        val progress = intent?.getIntExtra(EXTRA_PROGRESS, -1) ?: -1
        try {
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(text, progress), type)
        } catch (e: RuntimeException) {
            stopSelf()
        }
        return START_NOT_STICKY
    }

    // Android 15 limits how long this kind of service runs in a day: past that it ends, and the
    // queue picks up again where it was the next time Fuse is open.
    override fun onTimeout(startId: Int, fgsType: Int) {
        stopSelf()
    }

    private fun notification(text: String, progress: Int): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager?.getNotificationChannel(CHANNEL) == null) {
            manager?.createNotificationChannel(
                NotificationChannel(CHANNEL, "Downloads", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shown while Fuse downloads or uploads games, BIOS and apps"
                    setShowBadge(false)
                },
            )
        }
        val open = PendingIntent.getActivity(
            this, 1,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_transfer)
            .setContentTitle("Fuse Downloads")
            .setContentText(text.ifBlank { "Moving your files" })
            .setProgress(100, progress.coerceIn(0, 100), progress < 0)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_DEFERRED)
            .build()
    }

    companion object {
        private const val EXTRA_TEXT = "io.github.matiyaaa.fuse.transfer.TEXT"
        private const val EXTRA_PROGRESS = "io.github.matiyaaa.fuse.transfer.PROGRESS"
        private const val CHANNEL = "transfers"
        private const val NOTIFICATION_ID = 7_303

        /** Starts the service, or updates its notification when it already runs. */
        fun show(context: Context, text: String, progress: Int?) {
            runCatching {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, TransferService::class.java).putExtra(EXTRA_TEXT, text).putExtra(EXTRA_PROGRESS, progress ?: -1),
                )
            }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, TransferService::class.java)) }
        }
    }
}
