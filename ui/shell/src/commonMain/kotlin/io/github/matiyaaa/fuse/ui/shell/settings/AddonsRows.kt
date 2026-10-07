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
import io.github.matiyaaa.fuse.ui.shell.app.openStreaming
import io.github.matiyaaa.fuse.ui.shell.stream.addStreamHost
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
        // The household's games on its other devices: what this device shares, what it takes, and how they move.
        if (sync != null && prefs.sync.enabled) {
            val reach = app.store.reach.state.collectAsState().value
            val reachRows = remoteLibraryRows(app, prefs.sync)
            addAll(app.group("addons.reach", "Remote Library", FuseIcons.MonitorSmartphone, summary = when {
                !reach.supported -> "Waiting for the host"
                reach.games == 0 -> "Nothing new"
                reach.games == 1 -> "1 game"
                else -> "${reach.games} games"
            }, detail = "Every device's games, sent between them, through Fuse Sync") {
                reachRows.map { it.copy(id = "reach.${it.id}") }
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
        if (app.store.streaming.supported) {
            val s = prefs.streaming
            val hosts = app.store.streaming.hosts.collectAsState().value
            addAll(app.group("addons.streaming", "Streaming", FuseIcons.MonitorPlay, summary = if (!s.enabled) "Off" else if (s.hosts.isEmpty()) "No computers" else "${s.hosts.size} ${if (s.hosts.size == 1) "computer" else "computers"}", detail = "Play from a computer at home with Moonlight, waking it when it sleeps") {
                buildList {
                    add(toggleRow("streaming.enabled", "Stream from a Computer", FuseIcons.Power, s.enabled, "A Streaming tab in Addons for the computers running Sunshine or Apollo") { v ->
                        app.store.updatePrefs { it.copy(streaming = it.streaming.copy(enabled = v)) }
                    })
                    if (s.enabled) {
                        val client = app.store.streaming.client
                        add(infoRow("streaming.client", if (client != null) "Moonlight is installed" else "Moonlight isn't installed", detail = if (client != null) "Fuse starts it on the app you choose. Pair it with each computer once, in Moonlight" else "Get Moonlight, pair it with your computer once, then stream from Fuse", icon = if (client != null) FuseIcons.CircleCheck else FuseIcons.Warning))
                        for (h in hosts) add(MenuAction(
                            "streaming.host.${h.host.id}", h.host.name, FuseIcons.Monitor,
                            detail = listOfNotNull(h.host.address, h.host.mac.takeIf { it.isNotBlank() }?.let { "wakes by $it" }, "${h.host.apps.size} apps").joinToString("  ·  "),
                            trailing = Trailing.Value(when (h.online) { true -> "Ready"; false -> if (h.canWake) "Asleep" else "Off"; null -> "" }),
                            onSelect = { app.openStreaming() },
                        ))
                        add(MenuAction("streaming.add", "Add a Computer", FuseIcons.Plus, detail = "By its address on your network", onSelect = { app.addStreamHost() }))
                        add(app.choiceRow(
                            "streaming.wait", "Wait for a Computer to Wake", FuseIcons.Clock, s.wakeWaitSeconds.toString(),
                            listOf("45" to "45 seconds", "75" to "75 seconds", "120" to "2 minutes", "180" to "3 minutes"),
                            detail = "How long Fuse waits before saying it didn't wake",
                        ) { v -> app.store.updatePrefs { it.copy(streaming = it.streaming.copy(wakeWaitSeconds = v.toInt())) } })
                    }
                }
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
const val ADDONS_REACH = "addons.reach"
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

/**
 * The Remote Library's rows: whether this device's games are listed for the others, whether it
 * takes games they send, where those land, whether games may pass through the host, and whether
 * the others' games show in the RomM tab.
 */
@Composable
private fun remoteLibraryRows(app: AppState, s: io.github.matiyaaa.fuse.data.settings.SyncSettings): List<MenuAction> {
    val reach = app.store.reach.state.collectAsState().value
    fun set(change: (io.github.matiyaaa.fuse.data.settings.SyncSettings) -> io.github.matiyaaa.fuse.data.settings.SyncSettings) {
        app.scope.launch { app.store.sync.configure(change) }
    }
    return buildList {
        if (!reach.supported) add(infoRow("host", "Update Fuse on the host computer", detail = "The Remote Library needs Fuse 0.3.8 or later on the computer that hosts Fuse Sync", icon = FuseIcons.Info))
        add(MenuAction(
            "open", "Open the Remote Library", FuseIcons.LibraryBig,
            detail = io.github.matiyaaa.fuse.ui.shell.reach.householdWords(reach),
            trailing = Trailing.Chevron, onSelect = { app.go(Route.HouseholdLibrary) },
        ))
        add(toggleRow("share", "Share This Device's Games", FuseIcons.Share, s.shareLibrary, if (s.shareLibrary) "Your other devices see them, and can fetch them while Fuse is open here" else "Off: no other device sees or fetches this device's games") { v ->
            set { it.copy(shareLibrary = v) }
        })
        add(toggleRow("accept", "Let Other Devices Send Games Here", FuseIcons.Download, s.acceptSends, if (s.acceptSends) "Any of your devices can send a game here, now or once this device is back" else "Off: games come here only when you ask for them here") { v ->
            set { it.copy(acceptSends = v) }
        })
        add(MenuAction(
            "folder", "Where Games Sent Here Go", FuseIcons.FolderOpen,
            detail = if (s.receiveFolder.isBlank()) "Each system's folder, as Fuse RomM's downloads choose it" else s.receiveFolder,
            trailing = Trailing.Value(if (s.receiveFolder.isBlank()) "Automatic" else "Chosen"),
            onSelect = {
                app.choice = io.github.matiyaaa.fuse.ui.shell.app.ChoiceSpec(
                    title = "Where Games Sent Here Go",
                    message = "Each game goes into its system's folder inside the folder you choose, or the system folder Fuse already knows.",
                    icon = FuseIcons.FolderOpen,
                    options = listOf(
                        MenuAction("auto", "Automatic", FuseIcons.Wand, detail = "Each system's folder, as Fuse RomM's downloads choose it", onSelect = { app.choice = null; set { it.copy(receiveFolder = "") } }),
                        MenuAction("pick", "Choose a Folder", FuseIcons.FolderSearch, onSelect = {
                            app.choice = null
                            app.scope.launch {
                                val path = app.platform.storage.pickFolder("Where should games sent here go?") ?: return@launch
                                app.store.sync.configure { it.copy(receiveFolder = path) }
                            }
                        }),
                    ),
                )
            },
        ))
        add(toggleRow("relay", "Pass Games Through the Host", FuseIcons.Router, s.relay, if (s.relay) "When two devices can't reach each other (one away from home), the host passes the game on, keeping none of it" else "Off: games move only between devices that reach each other directly") { v ->
            set { it.copy(relay = v) }
        })
        if (app.store.romm.supported) add(toggleRow("romm", "Show Other Devices' Games in RomM", FuseIcons.LibraryBig, s.showInRomm, "Not on RomM, from another device, at the foot of the RomM tab and in each system") { v ->
            set { it.copy(showInRomm = v) }
        })
    }
}
