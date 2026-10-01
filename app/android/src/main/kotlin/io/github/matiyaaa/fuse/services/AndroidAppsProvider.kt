package io.github.matiyaaa.fuse.services

import android.app.ActivityOptions
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherApps
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.UserHandle
import android.provider.Settings
import android.view.Display
import androidx.core.net.toUri
import io.github.matiyaaa.fuse.ActivityHolder
import io.github.matiyaaa.fuse.model.AppEntry
import io.github.matiyaaa.fuse.ui.shell.store.AppIconModel
import io.github.matiyaaa.fuse.ui.shell.store.AppsProvider
import io.github.matiyaaa.fuse.ui.shell.store.RunResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Launchable apps from [LauncherApps] for this user, excluding Fuse. The list is refreshed when apps
 * are installed, updated or removed. Visibility comes from the manifest's MAIN/LAUNCHER `<queries>`.
 */
class AndroidAppsProvider(
    context: Context,
    private val scope: CoroutineScope,
    private val activities: ActivityHolder,
    /** Frees the second screen for an app opened there. */
    private val dualScreen: DualScreenHandoff? = null,
) : AppsProvider {
    private val appContext = context.applicationContext
    private val launcherApps = appContext.getSystemService(LauncherApps::class.java)
    private val user: UserHandle = Process.myUserHandle()
    private val _apps = MutableStateFlow<List<AppEntry>>(emptyList())
    override val apps: StateFlow<List<AppEntry>> = _apps.asStateFlow()
    private var refreshJob: Job? = null

    private val callback = object : LauncherApps.Callback() {
        override fun onPackageRemoved(packageName: String, user: UserHandle) = refresh()
        override fun onPackageAdded(packageName: String, user: UserHandle) = refresh()
        override fun onPackageChanged(packageName: String, user: UserHandle) = refresh()
        override fun onPackagesAvailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) = refresh()
        override fun onPackagesUnavailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) = refresh()
    }

    init {
        launcherApps?.registerCallback(callback, Handler(Looper.getMainLooper()))
        refresh()
    }

    override fun iconModel(entry: AppEntry): Any = AppIconModel(entry.packageName)

    /** Reloads the list; bursts of package events are coalesced. */
    override fun refresh() {
        refreshJob?.cancel()
        refreshJob = scope.launch(Dispatchers.IO) {
            delay(250)
            _apps.value = load()
        }
    }

    private fun load(): List<AppEntry> {
        val list = try {
            launcherApps?.getActivityList(null, user).orEmpty()
        } catch (e: SecurityException) {
            emptyList()
        }
        return list
            .filter { it.applicationInfo.packageName != appContext.packageName }
            .map { info ->
                val app = info.applicationInfo
                AppEntry(
                    id = "${app.packageName}/${info.componentName.className}",
                    label = info.label?.toString()?.trim().orEmpty().ifEmpty { app.packageName },
                    packageName = app.packageName,
                    isGame = isGame(app),
                    installedAt = info.firstInstallTime.takeIf { it > 0 },
                )
            }
            .distinctBy { it.id }
            .sortedBy { it.label.lowercase() }
    }

    @Suppress("DEPRECATION")
    private fun isGame(app: ApplicationInfo): Boolean =
        app.category == ApplicationInfo.CATEGORY_GAME || (app.flags and ApplicationInfo.FLAG_IS_GAME) != 0

    override suspend fun launch(entry: AppEntry, displayId: Int?): RunResult = withContext(Dispatchers.Main) {
        val apps = launcherApps ?: return@withContext RunResult.Failed("Android didn't give Fuse its app list.")
        val className = entry.id.substringAfter('/', "")
        val component = if (className.isNotEmpty()) {
            ComponentName(entry.packageName, className)
        } else {
            apps.getActivityList(entry.packageName, user).firstOrNull()?.componentName
                ?: return@withContext RunResult.NotInstalled
        }
        try {
            if (!apps.isActivityEnabled(component, user)) return@withContext RunResult.NotInstalled
            val other = displayId != null && displayId != Display.DEFAULT_DISPLAY
            if (other) dualScreen?.beforeSecondScreenLaunch()
            try {
                apps.startMainActivity(component, user, null, options(displayId).toBundle())
            } catch (e: SecurityException) {
                // Android refused the other screen: open on this one instead.
                if (!other) throw e
                dualScreen?.dualScreenGameFailed()
                apps.startMainActivity(component, user, null, options(null).toBundle())
            }
            RunResult.Started(null)
        } catch (e: ActivityNotFoundException) {
            RunResult.NotInstalled
        } catch (e: SecurityException) {
            RunResult.Failed("Android didn't let Fuse open ${entry.displayTitle}.")
        } catch (e: IllegalArgumentException) {
            RunResult.NotInstalled
        }
    }

    private fun options(displayId: Int?): ActivityOptions {
        val options = activities.revealOptions() ?: ActivityOptions.makeBasic()
        if (displayId != null) options.launchDisplayId = displayId
        return options
    }

    override fun openInfo(entry: AppEntry) {
        activities.start(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${entry.packageName}".toUri())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
