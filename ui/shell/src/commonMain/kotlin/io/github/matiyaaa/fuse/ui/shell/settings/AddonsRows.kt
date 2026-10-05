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
import kotlinx.coroutines.launch

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
    val sync = app.store.sync.service?.let { syncRowsFor(app, it, prefs.sync) }
    // With one addon there is nothing to choose between: its rows are the section.
    if (store == null && cartridge == null && sync == null && jellyfin != null) return jellyfin
    return buildList {
        if (sync != null) {
            val s = app.store.sync.service!!.status.collectAsState().value
            addAll(app.group(ADDONS_SYNC, "Fuse Sync", FuseIcons.RefreshCcw, summary = if (prefs.sync.enabled) io.github.matiyaaa.fuse.ui.shell.sync.syncWords(s).title else "Off", detail = "Your saves, play time, library and settings on every device") {
                sync.map { it.copy(id = "sync.${it.id}") }
            })
        }
        if (jellyfin != null) {
            addAll(app.group("addons.jellyfin", "Jellyfin", FuseIcons.Clapperboard, summary = if (prefs.jellyfin.enabled) jellyfinStatus?.title else "Off", detail = "Your films, shows and music, played in Fuse Player") {
                jellyfin.map { it.copy(id = "jellyfin.${it.id}") }
            })
        } else {
            add(infoRow("none", "Jellyfin isn't part of this build", icon = FuseIcons.Clapperboard))
        }
        if (store != null) {
            val on = prefs.storeEnabled
            addAll(app.group("addons.store", "Store", FuseIcons.Store, summary = when { !on -> "Off"; prefs.storeVariant == null && !app.store.appStore.state.value.desktop -> "Not set up"; else -> "On" }, detail = "Apps and emulators, their updates and where the catalogue comes from") {
                listOf(toggleRow("store.enabled", "Use the Store", FuseIcons.Power, on, if (on) "The Store in Addons, and its update checks" else "Off: no Store in Addons, and nothing is checked") { v ->
                    app.store.updatePrefs { it.copy(storeEnabled = v) }
                }) + if (on) store.map { it.copy(id = "store.${it.id}", section = null) } else emptyList()
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

/** The Fuse Sync rows of Addons: the switch, then its own page for everything else. */
@Composable
private fun syncRowsFor(app: AppState, service: io.github.matiyaaa.fuse.sync.SyncService, c: io.github.matiyaaa.fuse.data.settings.SyncSettings): List<MenuAction> {
    val status = service.status.collectAsState().value
    val words = io.github.matiyaaa.fuse.ui.shell.sync.syncWords(if (c.enabled) status else io.github.matiyaaa.fuse.sync.SyncStatus.Off)
    return buildList {
        add(toggleRow("enabled", "Use Fuse Sync", FuseIcons.Power, c.enabled, "Your saves, play time, library and settings on every device, from a host of your own") { v ->
            app.scope.launch { app.store.sync.setEnabled(v) }
        })
        add(MenuAction(
            "page", if (c.enabled && c.role.isEmpty()) "Set Up Fuse Sync" else "Profiles, Devices and What Syncs", FuseIcons.RefreshCcw,
            detail = words.detail,
            trailing = Trailing.Value(words.title),
            onSelect = { app.go(Route.SyncSettings) },
        ))
    }
}

/** Addons' groups, by the ids that open them (from search, or a link to the Store's or Cartridge's settings). */
const val ADDONS_SYNC = "addons.sync"
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
