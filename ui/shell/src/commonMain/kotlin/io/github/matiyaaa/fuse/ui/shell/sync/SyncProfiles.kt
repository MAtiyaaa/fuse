package io.github.matiyaaa.fuse.ui.shell.sync

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import io.github.matiyaaa.fuse.ui.shell.app.AppState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Keeps who is playing on screen ([AppState.syncProfile]) and, once per start, does what Settings
 * asks for at startup: the last profile (nothing to do), "Who's playing?" every time, or a chosen
 * profile (switched to, or asked for its PIN). Nothing at all while Fuse Sync is off.
 */
@Composable
internal fun SyncProfiles(app: AppState) {
    val svc = app.store.sync.service ?: return
    val prefs by app.store.prefs.collectAsState()
    val enabled = prefs.sync.enabled
    val active by svc.activeProfile.collectAsState()
    LaunchedEffect(enabled, active) { app.syncProfile = if (enabled) active else null }
    LaunchedEffect(enabled, prefs.onboardingDone) {
        if (!enabled || !prefs.onboardingDone || app.syncStartupDone) return@LaunchedEffect
        app.syncStartupDone = true
        val c = prefs.sync
        if (c.role.isEmpty()) return@LaunchedEffect
        when (c.startup) {
            "ASK" -> app.whoAreYou = WhoMode.STARTUP
            "PROFILE" -> {
                if (c.startupProfile.isEmpty() || c.startupProfile == c.activeProfile) return@LaunchedEffect
                val profiles = withTimeoutOrNull(STARTUP_WAIT_MS) { svc.profiles.first { list -> list.isNotEmpty() } } ?: return@LaunchedEffect
                val p = profiles.firstOrNull { it.id == c.startupProfile } ?: return@LaunchedEffect
                // A PIN is asked for, never skipped.
                if (p.protected) app.whoAreYou = WhoMode.STARTUP else svc.switchTo(p.id)
            }
        }
    }
}

/** How long startup waits to hear the profiles before leaving the one in use. */
private const val STARTUP_WAIT_MS = 8_000L
