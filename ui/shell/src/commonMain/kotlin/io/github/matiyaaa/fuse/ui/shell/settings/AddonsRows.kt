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
    val syncthing = app.store.syncthing
    val romm = if (app.store.romm.supported) rommRowsFor(app, prefs.romm) else null
    // With one addon there is nothing to choose between: its rows are the section.
    if (store == null && cartridge == null && sync == null && syncthing == null && romm == null && jellyfin != null) return jellyfin
    return buildList {
        if (romm != null) {
            val st = app.store.romm.state.collectAsState().value
            addAll(app.group(ADDONS_ROMM, "Fuse RomM", FuseIcons.LibraryBig, summary = io.github.matiyaaa.fuse.ui.shell.romm.rommStatus(st, prefs.romm).second, detail = "Your RomM server's games, downloads and uploads, inside Fuse") {
                romm.map { it.copy(id = "romm.${it.id}") }
            })
        }
        if (sync != null) {
            val s = app.store.sync.service!!.status.collectAsState().value
            addAll(app.group(ADDONS_SYNC, "Fuse Sync", FuseIcons.RefreshCcw, summary = if (prefs.sync.enabled) io.github.matiyaaa.fuse.ui.shell.sync.syncWords(s).title else "Off", detail = "Your saves, play time, library and settings on every device") {
                sync.map { it.copy(id = "sync.${it.id}") }
            })
        }
        if (syncthing != null) {
            val st = syncthing.state.collectAsState().value
            val on = st !is io.github.matiyaaa.fuse.sync.syncthing.SyncthingState.Off
            val words = io.github.matiyaaa.fuse.ui.shell.sync.syncthingWords(st)
            addAll(app.group(ADDONS_SYNCTHING, "Syncthing", FuseIcons.FolderSync, summary = words.second, detail = "Your emulators' save folders, through the Syncthing you already run") {
                listOf(
                    toggleRow("syncthing.enabled", "Use Syncthing", FuseIcons.Power, on, "For people who run it already. Fuse Sync is the one Fuse recommends") { v ->
                        io.github.matiyaaa.fuse.ui.shell.sync.useSyncthing(app, syncthing, v)
                    },
                    MenuAction(
                        "syncthing.page", if (st is io.github.matiyaaa.fuse.sync.syncthing.SyncthingState.Connected) "Devices and Save Folders" else "Set Up Syncthing", FuseIcons.FolderSync,
                        detail = words.third,
                        trailing = Trailing.Value(words.second),
                        onSelect = { app.go(Route.SyncthingSettings) },
                    ),
                )
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

/**
 * Fuse RomM's rows in Addons: the switch (which turns Cartridge off in Fuse while it's on, each
 * keeping its own setup), then its own page.
 */
@Composable
private fun rommRowsFor(app: AppState, r: io.github.matiyaaa.fuse.data.settings.FuseRommSettings): List<MenuAction> {
    val st = app.store.romm.state.collectAsState().value
    val (_, title, detail) = io.github.matiyaaa.fuse.ui.shell.romm.rommStatus(st, r)
    return buildList {
        add(toggleRow("enabled", "Use Fuse RomM", FuseIcons.Power, r.enabled, "The Fuse RomM native integration. Turning it on turns Cartridge off in Fuse; both keep their setup") { v ->
            app.scope.launch { app.store.romm.setEnabled(v) }
        })
        add(MenuAction(
            "page", if (r.enabled && !r.configured) "Set Up Fuse RomM" else "Server, Library, Transfers and BIOS", FuseIcons.Server,
            detail = detail,
            trailing = Trailing.Value(title),
            onSelect = { if (r.enabled && !r.configured) app.go(Route.RommSetup()) else app.go(Route.RommSettings) },
        ))
    }
}

/** Settings, Downloads: how every transfer behaves, wherever it comes from. */
@Composable
fun downloadsRows(app: AppState): List<MenuAction> {
    val dl = app.store.prefs.collectAsState().value.downloads
    return buildList {
        add(MenuAction("open", "Open Downloads", FuseIcons.Download, detail = "Everything moving, waiting, finished or failed", trailing = Trailing.Chevron, onSelect = { app.go(Route.Downloads) }))
        addAll(io.github.matiyaaa.fuse.ui.shell.romm.transferRows(app, dl, { t -> app.store.updatePrefs { it.copy(downloads = t(it.downloads)) } }, section = "Transfers"))
    }
}

/** The Fuse Sync rows of Addons: the switch, then its own page for everything else. */
@Composable
private fun syncRowsFor(app: AppState, service: io.github.matiyaaa.fuse.sync.SyncService, c: io.github.matiyaaa.fuse.data.settings.SyncSettings): List<MenuAction> {
    val status = service.status.collectAsState().value
    val words = io.github.matiyaaa.fuse.ui.shell.sync.syncWords(if (c.enabled) status else io.github.matiyaaa.fuse.sync.SyncStatus.Off)
    return buildList {
        add(toggleRow("enabled", "Use Fuse Sync", FuseIcons.Power, c.enabled, "Your saves, play time, library and settings on every device, from a host of your own") { v ->
            if (v) app.scope.launch { app.store.sync.setEnabled(true) } else io.github.matiyaaa.fuse.ui.shell.sync.turnOffFuseSync(app)
        })
        add(MenuAction(
            "page", if (c.enabled && c.role.isEmpty()) "Set Up Fuse Sync" else "Profiles, Devices and What Syncs", FuseIcons.RefreshCcw,
            detail = words.detail,
            trailing = Trailing.Value(words.title),
            onSelect = { app.go(Route.SyncSettings) },
        ))
        if (c.enabled && c.role.isNotEmpty()) {
            add(MenuAction(
                "newProfile", "New Profile", FuseIcons.UserPlus,
                detail = "Someone else who plays here: their own library, saves, Home and theme",
                trailing = Trailing.Chevron,
                onSelect = { app.whoAreYou = io.github.matiyaaa.fuse.ui.shell.sync.WhoMode.ADD },
            ))
        }
    }
}

/** Addons' groups, by the ids that open them (from search, or a link to the Store's or Cartridge's settings). */
const val ADDONS_SYNC = "addons.sync"
const val ADDONS_SYNCTHING = "addons.syncthing"
const val ADDONS_JELLYFIN = "addons.jellyfin"
const val ADDONS_STORE = "addons.store"
const val ADDONS_CARTRIDGE = "addons.cartridge"
const val ADDONS_ROMM = "addons.romm"

/** What the Addons section's row in the list shows: only when Jellyfin needs something. */
@Composable
fun addonsStatus(app: AppState): Trailing {
    val service = app.store.jellyfin ?: return Trailing.None
    val j = app.store.prefs.collectAsState().value.jellyfin
    val s: JellyfinState = service.state.collectAsState().value
    return if (j.enabled && (s.authRequired || (s.account != null && s.offline))) Trailing.Badge("1") else Trailing.None
}
