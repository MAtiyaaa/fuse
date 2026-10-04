package io.github.matiyaaa.fuse.ui.shell.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import io.github.matiyaaa.fuse.jellyfin.JellyfinState
import io.github.matiyaaa.fuse.ui.designsystem.components.MenuAction
import io.github.matiyaaa.fuse.ui.designsystem.components.Trailing
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.shell.app.AppState
import io.github.matiyaaa.fuse.ui.shell.app.Route
import io.github.matiyaaa.fuse.ui.shell.jellyfin.jellyfinStatus

/** Settings, Addons: what Fuse can bring in from elsewhere. Today, a Jellyfin server. */
@Composable
fun addonsRows(app: AppState): List<MenuAction> {
    val service = app.store.jellyfin ?: return listOf(infoRow("none", "Jellyfin isn't part of this build", icon = FuseIcons.Clapperboard))
    val prefs = app.store.prefs.collectAsState().value
    val state = service.state.collectAsState().value
    val j = prefs.jellyfin
    val status = jellyfinStatus(state, j)
    return buildList {
        labelled("Jellyfin") {
            add(toggleRow("jellyfin", "Jellyfin", FuseIcons.Clapperboard, j.enabled, "Films, shows and music from your Jellyfin server in Addons, played in Fuse Player") { v ->
                app.store.updatePrefs { it.copy(jellyfin = it.jellyfin.copy(enabled = v)) }
            })
            add(MenuAction(
                "jellyfin.page", "Server and playback", FuseIcons.Server,
                detail = status.detail,
                trailing = Trailing.Value(status.title),
                onSelect = { app.go(Route.JellyfinSettings) },
            ))
        }
    }
}

/** What the Addons section's row in the list shows: only when Jellyfin needs something. */
@Composable
fun addonsStatus(app: AppState): Trailing {
    val service = app.store.jellyfin ?: return Trailing.None
    val j = app.store.prefs.collectAsState().value.jellyfin
    val s: JellyfinState = service.state.collectAsState().value
    return if (j.enabled && (s.authRequired || (s.account != null && s.offline))) Trailing.Badge("1") else Trailing.None
}
