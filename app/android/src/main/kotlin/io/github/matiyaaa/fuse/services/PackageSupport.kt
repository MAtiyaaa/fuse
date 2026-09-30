package io.github.matiyaaa.fuse.services

import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Build

/** PackageManager calls without the API 33 flag-object deprecations scattered around the app. */
internal object PackageSupport {
    fun packageInfo(pm: PackageManager, pkg: String): PackageInfo? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(pkg, 0)
        }
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }

    fun activityInfo(pm: PackageManager, component: ComponentName): ActivityInfo? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getActivityInfo(component, PackageManager.ComponentInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getActivityInfo(component, 0)
        }
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }

    /** True when [className] exists in [pkg], is enabled and can be started by other apps. */
    fun isExportedActivity(pm: PackageManager, pkg: String, className: String): Boolean {
        val info = activityInfo(pm, ComponentName(pkg, className)) ?: return false
        return info.exported && info.isEnabled
    }

    fun queryActivities(pm: PackageManager, intent: Intent): List<ResolveInfo> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(intent, 0)
        }

    fun resolveActivity(pm: PackageManager, intent: Intent, flags: Int = 0): ResolveInfo? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.resolveActivity(intent, PackageManager.ResolveInfoFlags.of(flags.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.resolveActivity(intent, flags)
        }

    fun label(pm: PackageManager, info: PackageInfo): String =
        info.applicationInfo?.loadLabel(pm)?.toString()?.trim().orEmpty().ifEmpty { info.packageName }
}
