package io.github.matiyaaa.fuse.ui.shell.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import io.github.matiyaaa.fuse.jellyfin.JellyfinState
import io.github.matiyaaa.fuse.ui.designsystem.components.MenuAction
import io.github.matiyaaa.fuse.ui.designsystem.components.Trailing
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseMarks
import io.github.matiyaaa.fuse.ui.shell.app.AppState
import io.github.matiyaaa.fuse.ui.shell.app.Route
import io.github.matiyaaa.fuse.ui.shell.jellyfin.jellyfinStatus

/**
 * Settings, Addons: what Fuse brings in from elsewhere, each in a group that opens in place:
 * Jellyfin (your films, shows and music), the Store (apps and emulators), and Cartridge where it
 * runs. With Jellyfin alone, its rows are the section.
 */
@Composable
fun addonsRows(app: AppState): List<MenuAction> {
    val prefs = app.store.prefs.collectAsState().value
    val service = app.store.jellyfin
    val jellyfin = service?.let { jellyfinRowsFor(app, it, prefs.jellyfin) }
    val jellyfinStatus = service?.let { jellyfinStatus(it.state.collectAsState().value, prefs.jellyfin) }
    val store = if (app.store.appStore.supported) storeRows(app) else null
    val cartridge = if (app.platform.features.cartridge) cartridgeRows(app) else null
    // With one addon there is nothing to choose between: its rows are the section.
    if (store == null && cartridge == null && jellyfin != null) return jellyfin
    return buildList {
        if (jellyfin != null) {
            addAll(app.group("addons.jellyfin", "Jellyfin", FuseIcons.Clapperboard, summary = if (prefs.jellyfin.enabled) jellyfinStatus?.title else "Off", detail = "Your films, shows and music, played in Fuse Player") {
                jellyfin.map { it.copy(id = "jellyfin.${it.id}") }
            })
        } else {
            add(infoRow("none", "Jellyfin isn't part of this build", icon = FuseIcons.Clapperboard))
        }
        if (store != null) {
            addAll(app.group("addons.store", "Store", FuseIcons.Store, summary = if (prefs.storeVariant == null) "Not set up" else null, detail = "Apps and emulators, their updates and where the catalogue comes from") {
                store.map { it.copy(id = "store.${it.id}", section = null) }
            })
        }
        if (cartridge != null) {
            addAll(app.group("addons.cartridge", "Cartridge", FuseMarks.Cartridge, summary = if (prefs.cartridgeEnabled) "On" else "Off", detail = "Downloads from your RomM server, straight into your library") {
                cartridge.map { it.copy(id = "cartridge.${it.id}", section = null) }
            })
        }
    }
}

/** The Jellyfin rows of Addons: the switch, then its own page for the server and playback. */
@Composable
private fun jellyfinRowsFor(app: AppState, service: io.github.matiyaaa.fuse.jellyfin.JellyfinService, j: io.github.matiyaaa.fuse.data.settings.JellyfinSettings): List<MenuAction> {
    val state = service.state.collectAsState().value
    val status = jellyfinStatus(state, j)
    return buildList {
        add(toggleRow("enabled", "Use Jellyfin", FuseIcons.Power, j.enabled, "Films, shows and music from your Jellyfin server in Addons, played in Fuse Player") { v ->
            app.store.updatePrefs { it.copy(jellyfin = it.jellyfin.copy(enabled = v)) }
        })
        add(MenuAction(
            "page", "Server and playback", FuseIcons.Server,
            detail = status.detail,
            trailing = Trailing.Value(status.title),
            onSelect = { app.go(Route.JellyfinSettings) },
        ))
    }
}

/** Addons' groups, by the ids that open them (from search, or a link to the Store's or Cartridge's settings). */
const val ADDONS_JELLYFIN = "addons.jellyfin"
const val ADDONS_STORE = "addons.store"
const val ADDONS_CARTRIDGE = "addons.cartridge"

/** What the Addons section's row in the list shows: only when Jellyfin needs something. */
@Composable
fun addonsStatus(app: AppState): Trailing {
    val service = app.store.jellyfin ?: return Trailing.None
    val j = app.store.prefs.collectAsState().value.jellyfin
    val s: JellyfinState = service.state.collectAsState().value
    return if (j.enabled && (s.authRequired || (s.account != null && s.offline))) Trailing.Badge("1") else Trailing.None
}
