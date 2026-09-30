package io.github.matiyaaa.fuse.platform

import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import io.github.matiyaaa.fuse.ActivityHolder
import io.github.matiyaaa.fuse.services.PackageSupport
import io.github.matiyaaa.fuse.ui.shell.platform.HomeRole
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Fuse as the Home app. The HOME intent filter lives on a disabled `HomeAlias`, so Fuse is never a
 * Home candidate until the user asks. [request] enables it and shows the system's role dialog once
 * (Android 10+) or the default-apps screen; a refusal is final until the user asks again.
 */
class AndroidHomeRole(
    context: Context,
    private val activities: ActivityHolder,
    private val scope: CoroutineScope,
) : HomeRole {
    private val appContext = context.applicationContext
    private val pm = appContext.packageManager
    private val alias = ComponentName(appContext.packageName, "${appContext.packageName}.HomeAlias")
    private val roleManager: RoleManager? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) appContext.getSystemService(RoleManager::class.java) else null

    private val _isHome = MutableStateFlow(check())
    override val isHome: StateFlow<Boolean> = _isHome.asStateFlow()

    private var requesting = false

    /** Whether this device lets apps become Home at all. */
    val available: Boolean
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) roleManager?.isRoleAvailable(RoleManager.ROLE_HOME) == true else true

    fun refresh() {
        _isHome.value = check()
    }

    override fun request() {
        if (requesting) return
        setAlias(true)
        val rm = roleManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && rm != null && rm.isRoleAvailable(RoleManager.ROLE_HOME)) {
            if (rm.isRoleHeld(RoleManager.ROLE_HOME)) {
                refresh()
                return
            }
            val requests = activities.requests
            if (requests == null) {
                openHomeSettings()
                return
            }
            requesting = true
            scope.launch(Dispatchers.Main) {
                try {
                    requests.requestRole(rm.createRequestRoleIntent(RoleManager.ROLE_HOME))
                } finally {
                    requesting = false
                    refresh()
                    // Declined: stop offering Fuse as a Home candidate. Asking again is the user's choice.
                    if (!_isHome.value) setAlias(false)
                }
            }
        } else {
            openHomeSettings()
        }
    }

    override fun openHomeSettings() {
        // Fuse only appears in the list while the alias is enabled.
        setAlias(true)
        activities.startFirst(
            Intent(Settings.ACTION_HOME_SETTINGS),
            Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS),
            Intent(Settings.ACTION_SETTINGS),
        )
    }

    override fun disable() {
        setAlias(false)
        refresh()
    }

    private fun setAlias(enabled: Boolean) {
        val state = if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        try {
            if (pm.getComponentEnabledSetting(alias) != state) {
                pm.setComponentEnabledSetting(alias, state, PackageManager.DONT_KILL_APP)
            }
        } catch (e: RuntimeException) {
            // Should not happen for Fuse's own component; the Home state simply stays as it is.
        }
    }

    private fun check(): Boolean {
        val rm = roleManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && rm != null) {
            return try {
                rm.isRoleHeld(RoleManager.ROLE_HOME)
            } catch (e: RuntimeException) {
                false
            }
        }
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val resolved = PackageSupport.resolveActivity(pm, home, PackageManager.MATCH_DEFAULT_ONLY)
        return resolved?.activityInfo?.packageName == appContext.packageName
    }
}
