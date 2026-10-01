package io.github.matiyaaa.fuse.services

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.widget.Toast
import io.github.matiyaaa.fuse.FuseApplication

/**
 * Receives [PackageInstaller] session results. When Android needs the user's confirmation it hands
 * over its own confirmation screen, which is started here; Fuse never confirms on the user's behalf.
 * Not exported: only the system, through Fuse's own PendingIntent, can reach it.
 */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = confirmationIntent(intent) ?: return
                val app = context.applicationContext as? FuseApplication
                val started = app?.activities?.start(confirm) ?: false
                if (!started) {
                    try {
                        context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    } catch (e: RuntimeException) {
                        Toast.makeText(context, "Open Fuse to finish the installation.", Toast.LENGTH_LONG).show()
                    }
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                // Fuse's own update: this runs in the new version, which opens itself again.
                if (intent.getBooleanExtra(EXTRA_SELF_UPDATE, false)) UpdateRelaunch.onUpdated(context)
                else Toast.makeText(context, "Installed.", Toast.LENGTH_SHORT).show()
            }
            PackageInstaller.STATUS_FAILURE_ABORTED -> UpdateRelaunch.disarm(context) // The user cancelled.
            else -> {
                UpdateRelaunch.disarm(context)
                Toast.makeText(context, failureMessage(status), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun confirmationIntent(intent: Intent): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_INTENT)
        }

    private fun failureMessage(status: Int): String = when (status) {
        PackageInstaller.STATUS_FAILURE_CONFLICT ->
            "Install failed: it conflicts with the installed app (signed with a different key?)."
        PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "Install failed: the app isn't compatible with this device."
        PackageInstaller.STATUS_FAILURE_STORAGE -> "Install failed: not enough storage."
        PackageInstaller.STATUS_FAILURE_INVALID -> "Install failed: the download isn't a valid app."
        PackageInstaller.STATUS_FAILURE_BLOCKED -> "Install was blocked by the device."
        else -> "Install failed."
    }

    companion object {
        /** Set on Fuse's own update, so the new version knows to open itself. */
        const val EXTRA_SELF_UPDATE = "io.github.matiyaaa.fuse.SELF_UPDATE"
    }
}
