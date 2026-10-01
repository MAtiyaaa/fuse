package io.github.matiyaaa.fuse.capture

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.IntentCompat
import io.github.matiyaaa.fuse.FuseApplication
import io.github.matiyaaa.fuse.MainActivity
import io.github.matiyaaa.fuse.R

/**
 * The foreground service Android requires while Fuse records its screen. It shows the "Fuse is
 * recording" notification with a Stop button, and turns the permission the user just gave into the
 * projection [AndroidScreenCapture] records with. It starts before the projection is asked for, as
 * Android 14 requires, and ends with the recording.
 */
class CaptureService : Service() {
    private val capture: AndroidScreenCapture get() = (application as FuseApplication).platformUi.capture

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            capture.stopFromElsewhere()
            return START_NOT_STICKY
        }
        val code = intent?.getIntExtra(EXTRA_CODE, Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        val data = intent?.let { IntentCompat.getParcelableExtra(it, EXTRA_DATA, Intent::class.java) }
        val projection: MediaProjection? = try {
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), type)
            if (data == null) null else getSystemService(MediaProjectionManager::class.java)?.getMediaProjection(code, data)
        } catch (e: RuntimeException) {
            // Android refused: the permission was already used, or the service may not run now.
            null
        }
        capture.onProjection(projection)
        if (projection == null) stopSelf()
        return START_NOT_STICKY
    }

    private fun notification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager?.getNotificationChannel(CHANNEL) == null) {
            manager?.createNotificationChannel(
                NotificationChannel(CHANNEL, "Screen recording", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shown while Fuse records its screen"
                    setShowBadge(false)
                },
            )
        }
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, CaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_capture)
            .setContentTitle("Fuse is recording its screen")
            .setContentText("Leaving Fuse stops and saves the recording")
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .setOngoing(true)
            .setSilent(true)
            .setUsesChronometer(true)
            .setShowWhen(true)
            .setWhen(System.currentTimeMillis())
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        const val EXTRA_CODE = "io.github.matiyaaa.fuse.capture.CODE"
        const val EXTRA_DATA = "io.github.matiyaaa.fuse.capture.DATA"
        private const val ACTION_STOP = "io.github.matiyaaa.fuse.capture.STOP"
        private const val CHANNEL = "capture"
        private const val NOTIFICATION_ID = 7_301
    }
}
