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
 * profile (switched to, or asked for its PIN). Profiles work with Fuse Sync or without it (this
 * device's own); with neither on and no profiles, nothing at all.
 */
@Composable
internal fun SyncProfiles(app: AppState) {
    val svc = app.store.sync.service ?: return
    val prefs by app.store.prefs.collectAsState()
    val active by svc.activeProfile.collectAsState()
    val profiles by svc.profiles.collectAsState()
    val on = prefs.sync.enabled || profiles.isNotEmpty()
    LaunchedEffect(on, active) { app.syncProfile = if (on) active else null }
    LaunchedEffect(on, profiles.size) { app.syncProfileCount = if (on) profiles.size else 0 }
    LaunchedEffect(on, prefs.onboardingDone, profiles.isNotEmpty()) {
        if (!on || !prefs.onboardingDone || app.syncStartupDone) return@LaunchedEffect
        val c = prefs.sync
        // Nothing to choose from yet: a host still to set up, and no profiles of this device's own.
        if (c.role.isEmpty() && profiles.isEmpty()) return@LaunchedEffect
        app.syncStartupDone = true
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
