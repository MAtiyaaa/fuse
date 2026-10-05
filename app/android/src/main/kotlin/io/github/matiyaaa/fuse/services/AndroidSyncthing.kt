package io.github.matiyaaa.fuse.services

import android.content.Context
import android.content.Intent
import io.github.matiyaaa.fuse.sync.syncthing.SyncthingInstall
import io.github.matiyaaa.fuse.sync.syncthing.SyncthingPlatform

/**
 * Syncthing on Android is Syncthing-Fork (the original app was retired): Fuse finds it by its
 * package, starts it the way automation apps do (its "start" broadcast, else by opening it), and
 * reaches its API on this device's own address.
 */
internal class AndroidSyncthing(private val context: Context) : SyncthingPlatform {
    override val host = "ANDROID"

    override val install = SyncthingInstall(
        name = "Syncthing-Fork",
        url = "https://f-droid.org/packages/com.github.catfriend1.syncthingfork/",
        note = "Install Syncthing-Fork (F-Droid or the Play Store), open it once so it starts, and come back.",
        canStart = true,
    )

    override fun installedApp(): String? = PACKAGES.firstOrNull { p ->
        runCatching { context.packageManager.getLaunchIntentForPackage(p) != null }.getOrDefault(false)
    }

    override fun startApp(): Boolean {
        val pkg = installedApp() ?: return false
        // Its own "start" broadcast (works when its experimental "Service control by broadcast" is on)...
        runCatching { context.sendBroadcast(Intent("$pkg.action.START").setPackage(pkg)) }
        // ...and opening it, which always starts it.
        return runCatching {
            context.packageManager.getLaunchIntentForPackage(pkg)?.let { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } != null
        }.getOrDefault(false)
    }

    companion object {
        /** Syncthing-Fork as F-Droid and the Play Store name it, and the retired original. */
        val PACKAGES = listOf("com.github.catfriend1.syncthingfork", "com.github.catfriend1.syncthingandroid", "com.nutomic.syncthingandroid")
    }
}
