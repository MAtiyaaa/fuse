package io.github.matiyaaa.fuse.ui.shell.romm

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import io.github.matiyaaa.fuse.data.settings.DownloadSettings
import io.github.matiyaaa.fuse.data.settings.FuseRommSettings
import io.github.matiyaaa.fuse.integrations.net.NetRoute
import io.github.matiyaaa.fuse.integrations.net.RouteMode
import io.github.matiyaaa.fuse.model.NavAction
import io.github.matiyaaa.fuse.romm.RommDeviceCode
import io.github.matiyaaa.fuse.ui.designsystem.components.FText
import io.github.matiyaaa.fuse.ui.designsystem.components.Hint
import io.github.matiyaaa.fuse.ui.designsystem.components.MenuAction
import io.github.matiyaaa.fuse.ui.designsystem.components.MenuList
import io.github.matiyaaa.fuse.ui.designsystem.components.Panel
import io.github.matiyaaa.fuse.ui.designsystem.components.Spinner
import io.github.matiyaaa.fuse.ui.designsystem.components.StatusDot
import io.github.matiyaaa.fuse.ui.designsystem.components.ToastKind
import io.github.matiyaaa.fuse.ui.designsystem.components.Trailing
import io.github.matiyaaa.fuse.ui.designsystem.components.handleMenuAction
import io.github.matiyaaa.fuse.ui.designsystem.effects.rememberReveal
import io.github.matiyaaa.fuse.ui.designsystem.effects.reveal
import io.github.matiyaaa.fuse.ui.designsystem.focus.LinearSelection
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.designsystem.icons.HintButton
import io.github.matiyaaa.fuse.ui.designsystem.input.InputLayer
import io.github.matiyaaa.fuse.ui.designsystem.input.NavResult
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.Size
import io.github.matiyaaa.fuse.ui.designsystem.theme.Space
import io.github.matiyaaa.fuse.ui.shell.app.AppState
import io.github.matiyaaa.fuse.ui.shell.app.ChoiceSpec
import io.github.matiyaaa.fuse.ui.shell.app.ConfirmSpec
import io.github.matiyaaa.fuse.ui.shell.app.FocusZone
import io.github.matiyaaa.fuse.ui.shell.app.Route
import io.github.matiyaaa.fuse.ui.shell.app.TextInputSpec
import io.github.matiyaaa.fuse.ui.shell.app.rememberRouteState
import io.github.matiyaaa.fuse.ui.shell.settings.choiceRow
import io.github.matiyaaa.fuse.ui.shell.settings.infoRow
import io.github.matiyaaa.fuse.ui.shell.settings.toggleRow
import io.github.matiyaaa.fuse.ui.shell.store.RommLink
import io.github.matiyaaa.fuse.ui.shell.store.RommState
import kotlinx.coroutines.launch

private class RommSettingsState {
    val sel = LinearSelection()
    var testing by mutableStateOf(false)
    var test by mutableStateOf<String?>(null)
    var bios by mutableStateOf(false)
    var looking by mutableStateOf(false)

    /** RomM on this computer, where Docker or Podman is installed here; null where it isn't. */
    var home by mutableStateOf<io.github.matiyaaa.fuse.ui.shell.store.RommHomeView?>(null)
}

/**
 * Looks for RomM on this network, then offers what answered along with typing an address (always
 * there, for a server Fuse can't see) and clearing it. [looking] says a search is under way.
 */
internal fun chooseRommHome(app: AppState, current: String, looking: (Boolean) -> Unit, keep: (String) -> Unit) {
    looking(true)
    app.scope.launch {
        val found = runCatching { app.store.romm.discover() }.getOrDefault(emptyList())
        looking(false)
        app.choice = ChoiceSpec(
            title = "Home address",
            icon = FuseIcons.Home,
            message = if (found.isEmpty()) "No RomM server answered on this network. Type its address instead, like 192.168.1.20:8080." else "RomM on this network",
            options = found.map { r ->
                MenuAction("found.${r.address}", r.address.substringAfter("://"), FuseIcons.Server, detail = "RomM ${r.version}", trailing = Trailing.Check(r.address == current), onSelect = {
                    app.choice = null
                    keep(r.address)
                })
            } + MenuAction("type", "Type an address", FuseIcons.Keyboard, detail = "For a server Fuse can't see from here", onSelect = {
                app.choice = null
                app.textInput = TextInputSpec("Home address", current, "192.168.1.20:8080", capitalize = false) { v -> keep(v.trim()) }
            }) + listOfNotNull(
                MenuAction("clear", "Clear the home address", FuseIcons.Eraser, onSelect = {
                    app.choice = null
                    keep("")
                }).takeIf { current.isNotBlank() },
            ),
        )
    }
}

internal fun rommStatus(s: RommState, p: FuseRommSettings): Triple<Boolean?, String, String> = when {
    !p.enabled -> Triple(null, "Off", if (p.configured) "Its setup is kept: turn it on to reconnect" else "Your RomM server's games, inside Fuse")
    s.link == RommLink.NOT_SET_UP -> Triple(null, "Not set up", "Add your server's address, then pair")
    s.link == RommLink.SIGNED_OUT -> Triple(false, "Signed out", "RomM no longer accepts Fuse's sign-in. Pair again")
    s.link == RommLink.OFFLINE -> Triple(false, "Can't reach the server", "Browsing what Fuse kept; it keeps trying")
    s.link == RommLink.CONNECTING -> Triple(null, "Connecting", "Asking your home and outside addresses")
    s.scan != null -> Triple(true, "RomM is scanning", scanText(s.scan))
    // "At home" only for an address that is on this network; a public server reached by the address
    // typed first (which lands in the home slot) is simply connected.
    s.route == NetRoute.LOCAL && io.github.matiyaaa.fuse.ui.shell.onboarding.looksAtHome(p.localAddress) ->
        Triple(true, "Connected at home", listOfNotNull(s.account.ifBlank { null }, s.version.ifBlank { null }?.let { "RomM $it" }).joinToString("  ·  "))
    s.route == NetRoute.LOCAL -> Triple(true, "Connected", listOfNotNull(s.account.ifBlank { null }, s.version.ifBlank { null }?.let { "RomM $it" }).joinToString("  ·  "))
    else -> Triple(true, "Connected from outside", listOfNotNull(s.account.ifBlank { null }, s.version.ifBlank { null }?.let { "RomM $it" }).joinToString("  ·  "))
}

/** How far a scan RomM runs has come, in words: games looked at of all, and new ones. */
internal fun scanText(s: io.github.matiyaaa.fuse.romm.RommScanStats): String = listOfNotNull(
    if (s.totalRoms > 0) "${s.scannedRoms} of ${s.totalRoms} games looked at" else "Starting",
    s.newRoms.takeIf { it > 0 }?.let { if (it == 1) "1 new" else "$it new" },
).joinToString("  ·  ")

/**
 * Settings, Addons, RomM: the RomM native integration. Connection (Auto recommended, home
 * and outside addresses, the account), the library and where games land, how transfers behave, BIOS
 * from the server, and uploads. Turning it off keeps all of it.
 */
@Composable
fun RommSettingsScreen(app: AppState) {
    val prefs by app.store.prefs.collectAsState()
    val state by app.store.romm.state.collectAsState()
    val p = prefs.romm
    val page = rememberRouteState(app.navigator, "romm.settings") { RommSettingsState() }
    LaunchedEffect(page) { page.home = runCatching { app.store.romm.homeServer() }.getOrNull() }
    val reveal = rememberReveal()
    fun set(change: (FuseRommSettings) -> FuseRommSettings) = app.store.updatePrefs { it.copy(romm = change(it.romm)) }
    fun setDl(change: (DownloadSettings) -> DownloadSettings) = app.store.updatePrefs { it.copy(downloads = change(it.downloads)) }
    val rows = rommRows(app, page, state, p, prefs.downloads, prefs.cartridgeEnabled, ::set, ::setDl)
    page.sel.keepOn(rows.map { it.id })
    page.sel.clamp(rows.size)
    LaunchedEffect(Unit) {
        app.hero = null
        app.hints = listOf(Hint(HintButton.CONFIRM, "Change"), Hint(HintButton.BACK, "Back"))
    }
    InputLayer(enabled = app.focusZone == FocusZone.CONTENT && !app.overlayOpen) { e ->
        if (e.action == NavAction.LEFT) NavResult.BLOCKED else handleMenuAction(e, rows, page.sel)
    }
    val (ok, title, detail) = rommStatus(state, p)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val compact = maxHeight < Size.touch * 12
        Column(Modifier.fillMaxSize().padding(horizontal = Space.gutter)) {
            Spacer(Modifier.height(Size.hudHeight + if (compact) Space.s else Space.l))
            Row(Modifier.reveal(reveal, 0), verticalAlignment = Alignment.CenterVertically) {
                RommMark(if (compact) Size.thumb else Size.thumbL)
                Spacer(Modifier.width(Space.l))
                Column(verticalArrangement = Arrangement.spacedBy(Space.xxs)) {
                    FText("RomM", if (compact) Fuse.type.title else Fuse.type.display, maxLines = 1)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusDot(ok)
                        Spacer(Modifier.width(Space.s))
                        FText(title, Fuse.type.bodyStrong, maxLines = 1)
                        if (detail.isNotBlank()) FText("  ·  $detail", Fuse.type.body, color = Fuse.colors.textMuted, maxLines = 1)
                    }
                }
            }
            Spacer(Modifier.height(if (compact) Space.m else Space.l))
            Panel(Modifier.widthIn(max = Size.touch * 18).weight(1f).padding(bottom = Size.hintHeight + Space.s).fillMaxHeight().reveal(reveal, 1)) {
                MenuList(rows, page.sel, modifier = Modifier.padding(Space.s), showSelection = app.focusZone == FocusZone.CONTENT)
            }
        }
    }
}

private fun modeLabel(m: String) = when (m) {
    "LOCAL" -> "Home only"
    "REMOTE" -> "Outside only"
    else -> "Automatic"
}

private fun rommRows(
    app: AppState,
    page: RommSettingsState,
    state: RommState,
    p: FuseRommSettings,
    dl: DownloadSettings,
    cartridgeOn: Boolean,
    set: ((FuseRommSettings) -> FuseRommSettings) -> Unit,
    setDl: ((DownloadSettings) -> DownloadSettings) -> Unit,
): List<MenuAction> = buildList {
    val ops = app.store.romm
    add(toggleRow(
        "enabled", "RomM", FuseIcons.LibraryBig, p.enabled,
        if (cartridgeOn && !p.enabled) "Turning it on turns Cartridge off in Fuse (Cartridge keeps its own setup)" else "Your RomM server's games, downloads and uploads, inside Fuse",
    ) { v -> app.scope.launch { ops.setEnabled(v) } })
    if (!p.enabled) {
        add(infoRow("off", "Nothing runs while it's off", detail = if (p.configured) "Its server, sign-in and preferences are kept for when it's on again" else "No requests and no Addons tab until you turn it on", icon = FuseIcons.ShieldCheck))
        return@buildList
    }

    // Connection --------------------------------------------------------------------------------
    val connection = "Connection"
    add(app.choiceRow(
        "mode", "Connection", FuseIcons.Network, p.mode,
        listOf("AUTO" to "Automatic", "LOCAL" to "Home only", "REMOTE" to "Outside only"),
        detail = "Automatic is recommended: home when it answers, outside otherwise",
        optionDetail = {
            when (it) {
                "LOCAL" -> "Only the home address, on the same network"
                "REMOTE" -> "Only the outside address (a tunnel or VPN)"
                else -> "Home when it answers, outside away, back home by itself. Downloads carry on either way"
            }
        },
    ) { v -> set { it.copy(mode = v) } }.copy(section = connection))
    add(MenuAction(
        "local", "Home address", FuseIcons.Home, detail = "Your RomM server on this network. Fuse looks for it, or type it",
        trailing = Trailing.Value(if (page.looking) "Looking" else p.localAddress.ifBlank { "Not set" }), section = connection,
        onSelect = {
            if (page.looking) return@MenuAction
            chooseRommHome(app, p.localAddress, { page.looking = it }) { a -> set { it.copy(localAddress = a) } }
        },
    ))
    add(MenuAction("remote", "Outside address", FuseIcons.Globe, detail = "For away from home: your server's address through a tunnel or VPN", trailing = Trailing.Value(p.remoteAddress.ifBlank { "Not set" }), section = connection, onSelect = {
        app.textInput = TextInputSpec("Outside address", p.remoteAddress, "https://romm.example.com", capitalize = false) { v -> set { it.copy(remoteAddress = v.trim()) } }
    }))
    add(MenuAction(
        "test", "Test connection", FuseIcons.Signal, detail = page.test ?: "Asks each address whether RomM answers",
        trailing = if (page.testing) Trailing.Value("Testing") else Trailing.None, section = connection,
        onSelect = {
            if (page.testing) return@MenuAction
            page.testing = true
            app.scope.launch {
                val t = ops.test(p.localAddress, p.remoteAddress, runCatching { RouteMode.valueOf(p.mode) }.getOrDefault(RouteMode.AUTO))
                page.testing = false
                page.test = t.problem ?: listOfNotNull(
                    t.localOk?.let { "Home: ${if (it) "answers" else "no answer"}" },
                    t.remoteOk?.let { "Outside: ${if (it) "answers" else "no answer"}" },
                    t.version?.let { "RomM $it" },
                ).joinToString("  ·  ")
            }
        },
    ))
    add(MenuAction(
        "access", "Cloudflare Access", FuseIcons.ShieldCheck,
        detail = when {
            state.accessHost.isBlank() -> "For an outside address behind Cloudflare Access: its service token, sent only there"
            state.accessHost != io.github.matiyaaa.fuse.romm.RommAccess.hostOf(p.remoteAddress) -> "Kept for ${state.accessHost}, which isn't the outside address now. Add the token again"
            else -> "Its service token goes only to ${state.accessHost}, over https"
        },
        trailing = Trailing.Value(if (state.accessHost.isBlank()) "Off" else "On"), section = connection,
        onSelect = { accessFlow(app, state.accessHost) },
    ))
    add(infoRow("route", "In use now", value = when (state.route) { NetRoute.LOCAL -> "Home"; NetRoute.REMOTE -> "Outside"; null -> "None" }, icon = FuseIcons.Router).copy(section = connection))
    if (state.version.isNotBlank()) add(infoRow("version", "Server version", value = "RomM ${state.version}", icon = FuseIcons.Server).copy(section = connection))
    val account = "Account"
    if (p.account.isNotBlank() && state.link != RommLink.SIGNED_OUT) {
        add(infoRow("account", "Signed in as ${p.account}", value = if (state.canUpload) "Read and upload" else "Read only", icon = FuseIcons.CircleUser).copy(section = account))
        add(MenuAction("reconnect", "Reconnect", FuseIcons.RefreshCcw, detail = "Asks the server again now", section = account, onSelect = { ops.refresh() }))
    }
    add(MenuAction(
        "pair", if (p.account.isBlank() || state.link == RommLink.SIGNED_OUT) "Sign in" else "Pair again", FuseIcons.Link,
        detail = "Pair from RomM with a short code, or use a pairing code or token. To upload, allow uploads while pairing",
        trailing = Trailing.Chevron, section = account,
        onSelect = { app.go(Route.RommSetup(pairing = true)) },
    ))
    if (p.account.isNotBlank()) add(MenuAction("signout", "Forget sign-in", FuseIcons.LogOut, detail = "Removes Fuse's token from this device. Addresses and preferences stay", destructive = true, section = account, onSelect = {
        app.confirm = ConfirmSpec("Forget RomM's sign-in?", "Fuse removes its RomM token from this device. Your server and this device's games stay as they are; the library Fuse kept stays to browse.", "Forget", true) {
            app.scope.launch { ops.signOut() }
        }
    }))

    // RomM on this computer ------------------------------------------------------------------------
    page.home?.let { h ->
        val section = "On This Computer"
        if (h.spec == null) {
            add(MenuAction(
                "home.setup", "Set Up RomM on This Computer", FuseIcons.Server,
                detail = "Runs RomM here with the ${h.engine} you have, your library read-only to it",
                trailing = Trailing.Chevron, section = section, onSelect = { setUpHomeFlow(app, h.engine) },
            ))
        } else {
            add(infoRow(
                "home.state", "RomM on This Computer", value = if (h.running) "Running" else "Stopped",
                detail = "${h.address}  ·  ${h.engine}  ·  your library read-only to it", icon = FuseIcons.Server,
            ).copy(section = section))
            add(MenuAction(
                "home.toggle", if (h.running) "Stop RomM" else "Start RomM", if (h.running) FuseIcons.Power else FuseIcons.Play,
                detail = if (h.running) "Everything it keeps stays, for the next start" else "With the ${h.engine} you have, as it was set up",
                section = section,
                onSelect = {
                    app.scope.launch {
                        val spec = h.spec
                        val done = if (h.running) ops.stopHome() else ops.startHome(spec.library, spec.port, spec.thisComputerOnly).map { }
                        done.onSuccess { app.toasts.show(if (h.running) "RomM on this computer stopped" else "RomM on this computer is running", ToastKind.SUCCESS, icon = FuseIcons.Server) }
                            .onFailure { app.toasts.show(it.message ?: "That didn't work", ToastKind.WARNING) }
                        page.home = runCatching { ops.homeServer() }.getOrNull()
                    }
                },
            ))
        }
    }

    // Saves --------------------------------------------------------------------------------------
    // One way moves this device's saves: RomM, Fuse One or Syncthing, chosen in one place.
    if (state.canSyncSaves || state.link != RommLink.ONLINE) {
        add(io.github.matiyaaa.fuse.ui.shell.sync.saveProviderRow(app, section = "Saves", id = "saves"))
    } else {
        add(MenuAction("saves", "Save Sync", FuseIcons.Save, detail = "Needs RomM 5 and a sign-in that may sync saves: pair with RomM again", enabled = false, section = "Saves"))
    }

    // Your changes -------------------------------------------------------------------------------
    val changes = "Your Changes"
    add(toggleRow(
        "changes", "Send Changes to RomM", FuseIcons.CloudUpload, p.sendChanges,
        when {
            p.sendChanges && state.changesWaiting > 0 -> if (state.changesWaiting == 1) "1 change waits to go to RomM" else "${state.changesWaiting} changes wait to go to RomM"
            p.sendChanges -> "Favourites, games you hide, the time you play and trophies earned in emulators go to RomM, for games from RomM"
            state.link == RommLink.ONLINE && !state.canSendChanges -> "Needs RomM 5.4 and a sign-in that may send changes: pair again"
            else -> "Favourites, games you hide, the time you play and trophies earned in emulators, for games from RomM. Off until you turn it on"
        },
    ) { v ->
        app.scope.launch {
            ops.setSendChanges(v)
                .onSuccess { if (v) app.toasts.show("Your changes go to RomM from now on", ToastKind.SUCCESS, icon = FuseIcons.CloudUpload) }
                .onFailure { app.toasts.show(it.message ?: "RomM didn't take that", ToastKind.WARNING) }
        }
    }.copy(section = changes))
    if (p.sendChanges && state.changesWaiting > 0) add(MenuAction(
        "changes.now", "Send Them Now", FuseIcons.CloudUpload, detail = "They go by themselves once RomM answers", section = changes,
        onSelect = {
            app.scope.launch {
                val sent = ops.sendChangesNow()
                app.toasts.show(if (sent > 0) (if (sent == 1) "1 change went to RomM" else "$sent changes went to RomM") else "RomM didn't take them yet. They go by themselves once it does", if (sent > 0) ToastKind.SUCCESS else ToastKind.WARNING)
            }
        },
    ))

    // Library -----------------------------------------------------------------------------------
    val library = "Library"
    add(MenuAction("sync", "Refresh library now", FuseIcons.RefreshCcw, detail = state.syncing?.let { "Syncing ${it.label.lowercase()}" } ?: "Brings in what changed on the server", section = library, onSelect = { ops.refresh() }))
    add(MenuAction("full", "Read the whole library again", FuseIcons.ListRestart, detail = "Slower; for when the server was rebuilt", section = library, onSelect = { ops.refresh(full = true) }))
    add(toggleRow("mirror", "Keep the library for offline", FuseIcons.Database, p.offlineMirror, "Browse your server's games while it's away") { v -> set { it.copy(offlineMirror = v) } }.copy(section = library))
    add(app.choiceRow("refresh.every", "Look for changes", FuseIcons.Timer, p.refreshMinutes, listOf(15 to "Every 15 minutes", 30 to "Every 30 minutes", 60 to "Every hour", 360 to "Every 6 hours", 1440 to "Once a day")) { v -> set { it.copy(refreshMinutes = v) } }.copy(section = library))
    add(MenuAction(
        "root", "Where games go", FuseIcons.FolderOpen,
        detail = "A system's games go to its folder in your library; new systems get a folder here",
        trailing = Trailing.Value(p.libraryRoot.ifBlank { "Your first library" }.let { shortFolder(it) }), section = library,
        onSelect = { chooseRoot(app, p, set) },
    ))
    if (p.systemFolders.isNotEmpty()) add(MenuAction("systems", "Folders by system", FuseIcons.Folder, detail = p.systemFolders.entries.joinToString(", ") { it.key }, trailing = Trailing.Value("${p.systemFolders.size}"), section = library, onSelect = {
        app.choice = ChoiceSpec(title = "Folders by system", message = "Choose one to go back to its folder in your library", icon = FuseIcons.Folder, options = p.systemFolders.map { (k, v) ->
            MenuAction("sf.$k", k, FuseIcons.Folder, detail = v, onSelect = { app.choice = null; set { it.copy(systemFolders = it.systemFolders - k) } })
        })
    }))
    add(toggleRow("scan", "Add games straight away", FuseIcons.ScanSearch, p.scanAfterDownload, "Looks at a downloaded game's folder at once, so it's ready to play") { v -> set { it.copy(scanAfterDownload = v) } }.copy(section = library))
    add(app.choiceRow("new", "New games on the server", FuseIcons.Sparkles, p.newGames, listOf("BADGE" to "Mark them new", "NOTIFY" to "Mark them and tell me", "QUIET" to "Say nothing")) { v -> set { it.copy(newGames = v) } }.copy(section = library))
    add(infoRow("art", "Game art follows Fuse", detail = "RomM's covers show the way your Game art setting says, box art or posters", icon = FuseIcons.GalleryThumbnails).copy(section = library))

    // Transfers ---------------------------------------------------------------------------------
    transferRows(app, dl, setDl, section = "Transfers").forEach(::add)

    // BIOS --------------------------------------------------------------------------------------
    val bios = "BIOS and firmware"
    add(MenuAction(
        "bios.needed", "Download All Needed BIOS", FuseIcons.Chip,
        detail = if (page.bios) "Looking at what this device needs" else "Only what the systems you play here are missing, from your server",
        trailing = if (page.bios) Trailing.Value("Looking") else Trailing.Chevron, section = bios,
        onSelect = { if (!page.bios) biosFlow(app, page, all = false) },
    ))
    add(MenuAction(
        "bios.all", "Download All Available BIOS", FuseIcons.Chip,
        detail = "Every BIOS your server has for systems in your library. Files you have are never replaced",
        section = bios, onSelect = { if (!page.bios) biosFlow(app, page, all = true) },
    ))
    add(toggleRow("bios.place", "Put BIOS where each emulator reads it", FuseIcons.FolderSync, p.placeBios, "Otherwise into the BIOS folder below") { v -> set { it.copy(placeBios = v) } }.copy(section = bios))
    add(MenuAction("bios.folder", "BIOS folder", FuseIcons.Folder, detail = "Where BIOS go when an emulator has no folder of its own", trailing = Trailing.Value(p.biosFolder.ifBlank { "Automatic" }.let { shortFolder(it) }), section = bios, onSelect = {
        app.textInput = TextInputSpec("BIOS folder", p.biosFolder, "Leave empty for automatic", capitalize = false) { v -> set { it.copy(biosFolder = v.trim()) } }
    }))

    // Upload ------------------------------------------------------------------------------------
    val upload = "Upload"
    // A state, not a heading: on or off, and what that means.
    add(infoRow("upload.can", "Uploads", value = if (state.canUpload) "On" else "Off", detail = if (state.canUpload) "RomM lets Fuse send games to it" else "Read only. Pair again and allow uploads to send games to RomM", icon = if (state.canUpload) FuseIcons.CloudUpload else FuseIcons.Lock).copy(section = upload))
    add(toggleRow("upload.confirm", "Show what will be sent first", FuseIcons.ListChecks, p.confirmUploads, "Every file, its folder and the total, before anything goes") { v -> set { it.copy(confirmUploads = v) } }.copy(section = upload))
    add(toggleRow("upload.scan", "Ask RomM to add uploads", FuseIcons.ScanSearch, p.scanAfterUpload, if (state.canScan) "RomM scans the system after an upload" else "Needs scan permission when pairing") { v -> set { it.copy(scanAfterUpload = v) } }.copy(section = upload))
}

/** Downloads' own settings, shared by Settings, Downloads and RomM's page. */
internal fun transferRows(app: AppState, dl: DownloadSettings, setDl: ((DownloadSettings) -> DownloadSettings) -> Unit, section: String?): List<MenuAction> {
    val counts = (1..5).map { it to "$it at once" }
    val limits = listOf(0 to "No limit", 1024 to "1 MB/s", 2048 to "2 MB/s", 5120 to "5 MB/s", 10240 to "10 MB/s", 25600 to "25 MB/s", 51200 to "50 MB/s")
    return listOf(
        app.choiceRow("dl.max", "Simultaneous downloads", FuseIcons.Download, if (dl.chosen) dl.maxDownloads else 0, listOf(0 to "Automatic") + counts,
            detail = "Up to five at once") { v -> setDl { if (v == 0) it.copy(chosen = false) else it.copy(maxDownloads = v, chosen = true) } },
        app.choiceRow("ul.max", "Simultaneous uploads", FuseIcons.Upload, if (dl.chosen) dl.maxUploads else 0, listOf(0 to "Automatic") + counts,
            detail = "Counted apart from downloads, up to five") { v -> setDl { if (v == 0) it.copy(chosen = false) else it.copy(maxUploads = v, chosen = true) } },
        app.choiceRow("dl.limit", "Bandwidth limit", FuseIcons.Gauge, limits.minBy { kotlin.math.abs(it.first - dl.bandwidthKbps) }.first, limits,
            detail = "For every transfer together") { v -> setDl { it.copy(bandwidthKbps = v) } },
        toggleRow(
            "dl.play", "Downloads and uploads while playing", FuseIcons.Gamepad, dl.transfersWhilePlaying,
            if (dl.transfersWhilePlaying) "They carry on at full speed while you play" else "They wait while you play, and carry on by themselves when the game closes",
        ) { v -> setDl { it.withTransfersWhilePlaying(v) } },
        toggleRow("dl.wifi", "Only on Wi-Fi", FuseIcons.Wifi, dl.wifiOnly, "Waits for Wi-Fi or a cable rather than using mobile data") { v -> setDl { it.copy(wifiOnly = v) } },
        toggleRow("dl.charging", "Only while charging", FuseIcons.Zap, dl.chargingOnly, "On battery, transfers wait for a charger. Saves always go: they are small") { v -> setDl { it.copy(chargingOnly = v) } },
        app.choiceRow("dl.battery", "On battery, wait below", FuseIcons.BatteryLow, dl.minBattery, listOf(0 to "Never wait", 20 to "20%", 30 to "30%", 50 to "50%"),
            detail = "Transfers carry on once plugged in") { v -> setDl { it.copy(minBattery = v) } },
        app.choiceRow("dl.quiet", "Quiet hours", FuseIcons.Moon, dl.quietFrom to dl.quietTo, quietHours(app.store.prefs.value.clock24h).let { q -> if (q.any { it.first == dl.quietFrom to dl.quietTo }) q else q + ((dl.quietFrom to dl.quietTo) to quietLabel(dl.quietFrom, dl.quietTo, app.store.prefs.value.clock24h)) },
            detail = "Transfers wait in these hours and carry on after") { (from, to) -> setDl { it.copy(quietFrom = from, quietTo = to) } },
        toggleRow("dl.resume", "Carry on unfinished transfers", FuseIcons.Play, dl.resumeOnStart, "When Fuse starts, from where they stopped") { v -> setDl { it.copy(resumeOnStart = v) } },
        app.choiceRow("dl.keep", "Keep finished ones listed", FuseIcons.History, dl.keepFinishedDays, listOf(0 to "Until Fuse restarts", 1 to "A day", 7 to "A week", 30 to "A month")) { v -> setDl { it.copy(keepFinishedDays = v) } },
    ).map { if (section != null) it.copy(section = section) else it }
}

/** Quiet hours offered, as minutes since midnight from and to; "None" first. */
private fun quietHours(clock24h: Boolean): List<Pair<Pair<Int, Int>, String>> =
    listOf(0 to 0, 22 * 60 to 7 * 60, 23 * 60 to 7 * 60, 0 to 8 * 60, 1 * 60 to 6 * 60, 9 * 60 to 17 * 60)
        .map { (from, to) -> (from to to) to quietLabel(from, to, clock24h) }

private fun quietLabel(from: Int, to: Int, clock24h: Boolean): String =
    if (from == to) "None" else "${clockText(from, clock24h)} to ${clockText(to, clock24h)}"

private fun clockText(minute: Int, clock24h: Boolean): String {
    val h = minute / 60 % 24
    val m = minute % 60
    if (clock24h) return "${h.toString().padStart(2, '0')}:${m.toString().padStart(2, '0')}"
    if (h == 0 && m == 0) return "Midnight"
    val h12 = if (h % 12 == 0) 12 else h % 12
    return if (m == 0) "$h12 ${if (h < 12) "AM" else "PM"}" else "$h12:${m.toString().padStart(2, '0')} ${if (h < 12) "AM" else "PM"}"
}

/**
 * RomM on this computer: which library it gets (read-only), who may reach it, then what will run,
 * asked before anything does. Once it answers, it is RomM's home address and pairing follows.
 */
internal fun setUpHomeFlow(app: AppState, engine: String) {
    val ops = app.store.romm
    val sources = app.store.sources.sources.value.filter { it.enabled }
    if (sources.isEmpty()) return app.toasts.show("Add a library first: RomM is given one to show", ToastKind.WARNING)
    fun reach(library: String) {
        app.choice = ChoiceSpec(
            title = "Who Can Reach It", icon = FuseIcons.Network,
            message = "RomM answers on port 8080.",
            options = listOf(
                MenuAction("home.lan", "Your Home Network", FuseIcons.Router, detail = "Your other devices use it too, as RomM's home address", onSelect = { app.choice = null; confirm(app, engine, library, thisComputerOnly = false) }),
                MenuAction("home.local", "This Computer Only", FuseIcons.Monitor, detail = "Nothing else on the network can reach it", onSelect = { app.choice = null; confirm(app, engine, library, thisComputerOnly = true) }),
            ),
        )
    }
    if (sources.size == 1) return reach(sources.single().path)
    app.choice = ChoiceSpec(
        title = "Which Library", icon = FuseIcons.FolderOpen,
        message = "RomM shows this library's games. It can read it but never change it.",
        options = sources.map { s -> MenuAction("home.lib.${s.id.value}", s.label, FuseIcons.Folder, detail = s.path, onSelect = { app.choice = null; reach(s.path) }) },
    )
}

private fun confirm(app: AppState, engine: String, library: String, thisComputerOnly: Boolean) {
    val ops = app.store.romm
    app.confirm = ConfirmSpec(
        title = "Start RomM on This Computer?",
        message = "Fuse writes a Compose file in its own folder and has $engine start RomM 5.4.0 and its database (MariaDB) in containers. " +
            "The first time, $engine downloads them first (about a gigabyte). $library is read-only to RomM. " +
            "Its passwords are made now and kept in this device's secret store. Stop it any time in Settings, Addons, RomM.",
        confirmLabel = "Start RomM",
    ) {
        app.toasts.show("Starting RomM. The first time takes a while", ToastKind.INFO, icon = FuseIcons.Server)
        app.scope.launch {
            ops.startHome(library, 8080, thisComputerOnly)
                .onSuccess { address ->
                    app.choice = ChoiceSpec(
                        title = "RomM Is Running", icon = FuseIcons.CircleCheck,
                        message = "At $address. Make your RomM account there first, then pair Fuse with it.",
                        options = listOf(
                            MenuAction("home.open", "Open RomM", FuseIcons.Globe, detail = address, onSelect = { app.choice = null; app.platform.openUrl(address) }),
                            MenuAction("home.pair", "Pair Now", FuseIcons.QrCode, detail = "Once your account is made", onSelect = { app.choice = null; app.go(Route.RommSetup(pairing = true)) }),
                        ),
                    )
                }
                .onFailure { app.toasts.show(it.message ?: "RomM didn't start", ToastKind.WARNING) }
        }
    }
}

/** Cloudflare Access: a service token added (its Client ID, then its Client Secret), changed or removed. */
private fun accessFlow(app: AppState, kept: String) {
    val ops = app.store.romm
    fun add() {
        app.textInput = TextInputSpec(
            "Client ID", "", "1a2b3c.access", capitalize = false,
            message = "From Cloudflare Zero Trust, Access, Service Auth: the service token's Client ID",
            doneLabel = "Next",
        ) { id ->
            app.textInput = TextInputSpec(
                "Client Secret", "", "", secret = true, capitalize = false,
                message = "Kept only in this device's secret store, and sent only to your outside address",
            ) { secret ->
                app.scope.launch {
                    ops.setAccess(id, secret)
                        .onSuccess { host -> app.toasts.show("Cloudflare Access is on for $host", ToastKind.SUCCESS, icon = FuseIcons.ShieldCheck) }
                        .onFailure { app.toasts.show(it.message ?: "Fuse couldn't keep the token", ToastKind.WARNING) }
                }
            }
        }
    }
    if (kept.isBlank()) return add()
    app.choice = ChoiceSpec(
        title = "Cloudflare Access",
        icon = FuseIcons.ShieldCheck,
        message = "A service token for $kept is kept on this device.",
        options = listOf(
            MenuAction("access.change", "Change the Token", FuseIcons.Key, detail = "Its Client ID and Client Secret again", onSelect = { app.choice = null; add() }),
            MenuAction("access.remove", "Remove the Token", FuseIcons.Trash, destructive = true, detail = "Requests go without it from now on", onSelect = {
                app.choice = null
                app.scope.launch { ops.removeAccess(); app.toasts.show("Cloudflare Access is off", ToastKind.SUCCESS) }
            }),
        ),
    )
}

private fun shortFolder(path: String): String = path.replace('\\', '/').trimEnd('/').split('/').takeLast(2).joinToString("/").ifBlank { path }

private fun chooseRoot(app: AppState, p: FuseRommSettings, set: ((FuseRommSettings) -> FuseRommSettings) -> Unit) {
    app.scope.launch {
        val sources = app.store.sources.sources.value.filter { it.enabled }
        app.choice = ChoiceSpec(
            title = "Where games go", message = "New systems' folders are made in this library. Systems you already have keep their folders.", icon = FuseIcons.FolderOpen,
            options = sources.map { s ->
                MenuAction("root.${s.id.value}", s.label, FuseIcons.Folder, detail = s.path, trailing = Trailing.Check(s.path == p.libraryRoot), onSelect = {
                    app.choice = null
                    set { it.copy(libraryRoot = s.path) }
                })
            } + MenuAction("root.auto", "Your first library", FuseIcons.Sparkles, trailing = Trailing.Check(p.libraryRoot.isBlank()), onSelect = {
                app.choice = null
                set { it.copy(libraryRoot = "") }
            }),
        )
    }
}

/** Finds the BIOS this device needs (or all), shows them, and queues them when the person agrees. */
private fun biosFlow(app: AppState, page: RommSettingsState, all: Boolean) {
    page.bios = true
    app.scope.launch {
        val picks = app.store.romm.biosPlan(all)
        page.bios = false
        if (picks.isEmpty()) {
            app.toasts.show(if (all) "Your server has no BIOS this device is missing" else "Nothing needed: this device has what your server could give", ToastKind.SUCCESS, icon = FuseIcons.CircleCheck)
            return@launch
        }
        app.choice = ChoiceSpec(
            title = if (all) "Download All Available BIOS" else "Download All Needed BIOS",
            message = "${picks.size} ${if (picks.size == 1) "file" else "files"} from your server. Files already here are never replaced.",
            icon = FuseIcons.Chip,
            options = listOf(MenuAction("bios.go", "Download ${picks.size}", FuseIcons.Download, onSelect = {
                app.choice = null
                app.scope.launch {
                    val n = app.store.romm.downloadBios(picks)
                    app.toasts.show("$n BIOS ${if (n == 1) "file is" else "files are"} on the way", ToastKind.SUCCESS, icon = FuseIcons.Download)
                }
            })) + picks.map { p -> MenuAction("bios.${p.firmware.id}.${p.destination}", p.firmware.fileName, FuseIcons.File, detail = "${p.why}  ·  ${shortFolder(p.destination)}", enabled = false) },
        )
    }
}

/**
 * Setting up RomM: the server's addresses (one is enough; Automatic needs no thought), then
 * pairing. Pairing shows a short code and a QR to approve in RomM, so a handheld never types a long
 * token; a pairing code made in RomM, a token or a password work too. Reading is asked for by
 * default; uploads only when the person wants them.
 */
@Composable
fun RommSetupScreen(app: AppState, pairing: Boolean) {
    val prefs by app.store.prefs.collectAsState()
    val p = prefs.romm
    val ops = app.store.romm
    var code by remember { mutableStateOf<RommDeviceCode?>(null) }
    var busy by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    var uploads by remember { mutableStateOf(false) }
    var saves by remember { mutableStateOf(true) }
    val sel = remember { LinearSelection(if (pairing && (p.localAddress.isNotBlank() || p.remoteAddress.isNotBlank())) 2 else 0) }
    var looking by remember { mutableStateOf(false) }
    // RomM on this computer, where Docker or Podman is installed here.
    var home by remember { mutableStateOf<io.github.matiyaaa.fuse.ui.shell.store.RommHomeView?>(null) }
    LaunchedEffect(Unit) { home = runCatching { ops.homeServer() }.getOrNull() }
    fun keepHome(address: String) = app.store.updatePrefs { it.copy(romm = it.romm.copy(localAddress = address)) }
    // With no home address yet, Fuse looks for RomM straight away: one server found is filled in,
    // several are offered to choose from. Typing an address is always there instead.
    LaunchedEffect(Unit) {
        if (p.localAddress.isNotBlank() || p.remoteAddress.isNotBlank() || looking) return@LaunchedEffect
        looking = true
        val found = runCatching { ops.discover() }.getOrDefault(emptyList())
        looking = false
        if (app.store.prefs.value.romm.localAddress.isNotBlank()) return@LaunchedEffect
        when (found.size) {
            0 -> Unit
            1 -> {
                keepHome(found[0].address)
                app.toasts.show("Found RomM at ${found[0].address.substringAfter("://")}", ToastKind.SUCCESS, icon = FuseIcons.Server)
            }
            else -> chooseRommHome(app, "", { looking = it }, ::keepHome)
        }
    }
    fun signedIn(name: String) {
        // One way moves this device's saves: choosing RomM's here turns Fuse One's and Syncthing's off.
        val now = io.github.matiyaaa.fuse.ui.shell.sync.SaveProviders.current(app)
        when {
            saves && !now.romm -> app.scope.launch { app.store.setSaveProvider(io.github.matiyaaa.fuse.data.settings.SaveProvider.ROMM) }
            !saves && now == io.github.matiyaaa.fuse.data.settings.SaveProvider.ROMM -> app.scope.launch { app.store.setSaveProvider(io.github.matiyaaa.fuse.data.settings.SaveProvider.OFF) }
            // RomM's save sync off with Fuse One moving the rest: Fuse One moves them all.
            !saves && now == io.github.matiyaaa.fuse.data.settings.SaveProvider.ROMM_THEN_FUSE_ONE -> app.scope.launch { app.store.setSaveProvider(io.github.matiyaaa.fuse.data.settings.SaveProvider.FUSE_ONE) }
        }
        app.toasts.show("RomM is connected as $name", ToastKind.SUCCESS, icon = FuseIcons.CircleCheck)
        app.back()
    }
    fun pair() {
        if (busy) return
        busy = true
        problem = null
        app.scope.launch {
            ops.setAddresses(p.localAddress, p.remoteAddress, runCatching { RouteMode.valueOf(p.mode) }.getOrDefault(RouteMode.AUTO))
            ops.startPairing(uploads).onSuccess { c ->
                code = c
                ops.awaitPairing(c).onSuccess(::signedIn).onFailure { problem = it.message }
                code = null
            }.onFailure { problem = it.message }
            busy = false
        }
    }
    val rows = buildList {
        add(MenuAction(
            "s.local", "Home address", FuseIcons.Home, detail = "Your RomM server on this network. Fuse looks for it, or type it",
            trailing = Trailing.Value(if (looking) "Looking" else p.localAddress.ifBlank { "Not set" }),
            onSelect = {
                if (looking) return@MenuAction
                chooseRommHome(app, p.localAddress, { looking = it }, ::keepHome)
            },
        ))
        add(MenuAction("s.remote", "Outside address (optional)", FuseIcons.Globe, detail = "For away from home, through a tunnel or VPN", trailing = Trailing.Value(p.remoteAddress.ifBlank { "Not set" }), onSelect = {
            app.textInput = TextInputSpec("Outside address", p.remoteAddress, "https://romm.example.com", capitalize = false) { v -> app.store.updatePrefs { it.copy(romm = it.romm.copy(remoteAddress = v.trim())) } }
        }))
        home?.takeIf { it.spec == null }?.let { h ->
            add(MenuAction(
                "s.home", "Set Up RomM on This Computer", FuseIcons.Server,
                detail = "No RomM yet? It runs here with the ${h.engine} you have, your library read-only to it",
                trailing = Trailing.Chevron, onSelect = { setUpHomeFlow(app, h.engine) },
            ))
        }
        val ready = p.localAddress.isNotBlank() || p.remoteAddress.isNotBlank()
        add(MenuAction("s.pair", "Pair with RomM", FuseIcons.QrCode, detail = "Shows a short code to approve in RomM. Nothing long to type", trailing = if (busy && code == null) Trailing.Value("Asking") else Trailing.Chevron, unavailableReason = if (!ready) "Add an address first" else null, onSelect = ::pair))
        add(toggleRow("s.uploads", "Allow uploads too", FuseIcons.CloudUpload, uploads, "Asks RomM for upload and scan rights. Leave off to only browse and download") { uploads = it })
        add(toggleRow("s.saves", "Move saves with RomM", FuseIcons.Save, saves, "Games from RomM keep their save in step with RomM's (RomM 5). Fuse One's and Syncthing's save sync turn off on this device: only one moves saves") { saves = it })
        add(MenuAction("s.code", "Use a pairing code from RomM", FuseIcons.Key, detail = "Made in RomM under API tokens, Pair", unavailableReason = if (!ready) "Add an address first" else null, onSelect = {
            app.textInput = TextInputSpec("Pairing code", "", "ABCD-2345", capitalize = false, doneLabel = "Pair") { v ->
                app.scope.launch {
                    ops.setAddresses(p.localAddress, p.remoteAddress, runCatching { RouteMode.valueOf(p.mode) }.getOrDefault(RouteMode.AUTO))
                    ops.usePairCode(v).onSuccess(::signedIn).onFailure { problem = it.message }
                }
            }
        }))
        add(MenuAction("s.token", "Use a token", FuseIcons.Key, detail = "A RomM client token (it is kept in this device's secret store)", unavailableReason = if (!ready) "Add an address first" else null, onSelect = {
            app.textInput = TextInputSpec("RomM token", "", "rmm_...", secret = true, capitalize = false, doneLabel = "Connect") { v ->
                app.scope.launch {
                    ops.setAddresses(p.localAddress, p.remoteAddress, runCatching { RouteMode.valueOf(p.mode) }.getOrDefault(RouteMode.AUTO))
                    ops.useToken(v).onSuccess(::signedIn).onFailure { problem = it.message }
                }
            }
        }))
        add(MenuAction("s.password", "Use a user name and password", FuseIcons.User, detail = "For older RomM servers. Prefer pairing", unavailableReason = if (!ready) "Add an address first" else null, onSelect = {
            app.textInput = TextInputSpec("RomM user name", "", "User name", capitalize = false, doneLabel = "Next") { user ->
                app.textInput = TextInputSpec("Password for ${user.trim()}", "", "Password", secret = true, capitalize = false, doneLabel = "Sign in") { pw ->
                    app.scope.launch {
                        ops.setAddresses(p.localAddress, p.remoteAddress, runCatching { RouteMode.valueOf(p.mode) }.getOrDefault(RouteMode.AUTO))
                        ops.usePassword(user, pw).onSuccess(::signedIn).onFailure { problem = it.message }
                    }
                }
            }
        }))
    }
    sel.clamp(rows.size)
    LaunchedEffect(code != null) {
        app.hero = null
        app.hints = if (code != null) listOf(Hint(HintButton.BACK, "Cancel")) else listOf(Hint(HintButton.CONFIRM, "Choose"), Hint(HintButton.BACK, "Back"))
    }
    InputLayer(enabled = app.focusZone == FocusZone.CONTENT && !app.overlayOpen) { e ->
        if (code != null) return@InputLayer if (e.action == NavAction.BACK) NavResult.IGNORED else NavResult.BLOCKED
        if (e.action == NavAction.LEFT) NavResult.BLOCKED else handleMenuAction(e, rows, sel)
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val compact = maxHeight < Size.touch * 12
        val wide = maxWidth >= 900.dp
        Column(Modifier.fillMaxSize().padding(horizontal = Space.gutter)) {
            Spacer(Modifier.height(Size.hudHeight + if (compact) Space.s else Space.l))
            Row(verticalAlignment = Alignment.CenterVertically) {
                RommMark(if (compact) Size.thumb else Size.thumbL)
                Spacer(Modifier.width(Space.l))
                Column {
                    FText("Set Up RomM", if (compact) Fuse.type.title else Fuse.type.display, maxLines = 1)
                    FText(problem ?: "Connect Fuse to your RomM server. Automatic finds it at home and from outside.", Fuse.type.body, color = if (problem != null) Fuse.colors.warning else Fuse.colors.textMuted, maxLines = 2)
                }
            }
            Spacer(Modifier.height(if (compact) Space.m else Space.l))
            Row(Modifier.weight(1f).padding(bottom = Size.hintHeight + Space.s), horizontalArrangement = Arrangement.spacedBy(Space.l)) {
                val c = code
                // Narrow, the code takes the page: beside the list it would be too thin to read.
                if (wide || c == null) {
                    Panel(Modifier.widthIn(max = Size.touch * 16).weight(1f).fillMaxHeight()) {
                        MenuList(rows, sel, modifier = Modifier.padding(Space.s), showSelection = app.focusZone == FocusZone.CONTENT && code == null)
                    }
                }
                if (c != null) PairingCard(app, c, if (wide) Modifier.width(340.dp).fillMaxHeight() else Modifier.weight(1f).fillMaxHeight())
            }
        }
    }
}

/** The code to approve in RomM, large enough to read across a room, with its QR for a phone. */
@Composable
private fun PairingCard(app: AppState, code: RommDeviceCode, modifier: Modifier) {
    val modules = remember(code.verificationUrl) { app.phoneLink?.qr(code.verificationUrl) }
    Panel(modifier) {
        Column(Modifier.fillMaxSize().padding(Space.l), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Space.m)) {
            FText("Approve in RomM", Fuse.type.bodyStrong, maxLines = 1)
            if (modules != null) io.github.matiyaaa.fuse.ui.shell.settings.QrCode(modules, 150.dp)
            Box(Modifier.clip(RoundedCornerShape(Fuse.geometry.control)).background(Fuse.colors.text.copy(alpha = 0.07f)).padding(horizontal = Space.l, vertical = Space.s)) {
                FText(code.userCode.chunked(4).joinToString("-"), Fuse.type.numericLarge, maxLines = 1)
            }
            FText("Scan the code with your phone, or open RomM's Pair device page and type this code.", Fuse.type.caption, color = Fuse.colors.textMuted, maxLines = 4)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spinner(size = 16.dp, color = Fuse.colors.textMuted)
                Spacer(Modifier.width(Space.s))
                FText("Waiting for approval", Fuse.type.caption, color = Fuse.colors.textMuted, maxLines = 1)
            }
        }
    }
}
