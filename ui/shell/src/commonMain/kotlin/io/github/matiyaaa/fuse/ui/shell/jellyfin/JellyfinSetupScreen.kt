package io.github.matiyaaa.fuse.ui.shell.jellyfin

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import io.github.matiyaaa.fuse.jellyfin.QuickConnectStart
import io.github.matiyaaa.fuse.ui.designsystem.components.Spinner
import kotlinx.coroutines.Job
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.matiyaaa.fuse.jellyfin.ConnectionMode
import io.github.matiyaaa.fuse.jellyfin.DiscoveredServer
import io.github.matiyaaa.fuse.jellyfin.JellyfinConnection
import io.github.matiyaaa.fuse.model.NavAction
import io.github.matiyaaa.fuse.ui.designsystem.components.FText
import io.github.matiyaaa.fuse.ui.designsystem.components.Hint
import io.github.matiyaaa.fuse.ui.designsystem.components.MenuAction
import io.github.matiyaaa.fuse.ui.designsystem.components.MenuList
import io.github.matiyaaa.fuse.ui.designsystem.components.Panel
import io.github.matiyaaa.fuse.ui.designsystem.components.ToastKind
import io.github.matiyaaa.fuse.ui.designsystem.components.Trailing
import io.github.matiyaaa.fuse.ui.designsystem.components.handleMenuAction
import io.github.matiyaaa.fuse.ui.designsystem.focus.LinearSelection
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.designsystem.icons.HintButton
import io.github.matiyaaa.fuse.ui.designsystem.input.InputLayer
import io.github.matiyaaa.fuse.ui.designsystem.input.NavResult
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.Size
import io.github.matiyaaa.fuse.ui.designsystem.theme.Space
import io.github.matiyaaa.fuse.ui.shell.app.AppState
import io.github.matiyaaa.fuse.ui.shell.app.FocusZone
import io.github.matiyaaa.fuse.ui.shell.app.TextInputSpec
import io.github.matiyaaa.fuse.ui.shell.onboarding.JellyfinStage
import io.github.matiyaaa.fuse.ui.shell.onboarding.looksAtHome
import io.github.matiyaaa.fuse.ui.shell.settings.infoRow
import kotlinx.coroutines.launch

/**
 * Setting up Jellyfin on a page of its own, as RomM and Fuse One have: the servers that answered on
 * this network (or typing an address), the address from outside for away from home, then signing
 * in. Every part says where it is; a problem is said under the title, never in a toast that goes.
 * Connected, Done goes back to where it was opened from (setup's Jellyfin step, or Settings).
 * The password goes to the server and nowhere else; with Quick Connect none is typed at all, only a
 * code approved in Jellyfin on a phone or computer. With [code], that is offered first.
 */
@Composable
fun JellyfinSetupScreen(app: AppState, code: Boolean = false) {
    val service = app.store.jellyfin
    val prefs by app.store.prefs.collectAsState()
    val j = prefs.jellyfin
    var found by remember { mutableStateOf<List<DiscoveredServer>>(emptyList()) }
    var looking by remember { mutableStateOf(false) }
    var round by remember { mutableStateOf(0) }
    var problem by remember { mutableStateOf<String?>(null) }
    var serverName by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    var signedInAs by remember { mutableStateOf<String?>(null) }
    val sel = remember { LinearSelection(0) }
    val address = j.localAddress.ifBlank { j.remoteAddress }
    // A Quick Connect code on show, and the wait for it: leaving the page ends the wait.
    var quick by remember { mutableStateOf<QuickConnectStart?>(null) }
    var asking by remember { mutableStateOf(false) }
    var quickJob by remember { mutableStateOf<Job?>(null) }
    val pageScope = rememberCoroutineScope()

    LaunchedEffect(round) {
        if (service == null) return@LaunchedEffect
        looking = true
        found = runCatching { service.discover() }.getOrDefault(emptyList())
        looking = false
    }
    LaunchedEffect(Unit) {
        app.hero = null
        app.hints = listOf(Hint(HintButton.CONFIRM, "Choose"), Hint(HintButton.BACK, "Back"))
    }

    fun useAddress(typed: String) {
        val svc = service ?: return
        if (testing) return
        testing = true
        problem = null
        app.scope.launch {
            val tested = svc.test(typed)
            testing = false
            val (base, info) = tested.getOrElse {
                problem = "Nothing answered at $typed. ${it.message.orEmpty()}".trim()
                return@launch
            }
            val home = looksAtHome(base)
            app.store.updatePrefs { p ->
                p.copy(jellyfin = p.jellyfin.copy(enabled = true, localAddress = if (home) base else p.jellyfin.localAddress, remoteAddress = if (home) p.jellyfin.remoteAddress else base))
            }
            val now = app.store.prefs.value.jellyfin
            svc.configure(true, JellyfinConnection(ConnectionMode.AUTO, if (home) base else now.localAddress, if (home) now.remoteAddress else base))
            serverName = info.name
            sel.index = 0
        }
    }

    fun signIn() {
        val svc = service ?: return
        val server = serverName ?: "Jellyfin"
        app.textInput = TextInputSpec("User name on $server", "", "User name", capitalize = false, doneLabel = "Next") { user ->
            val name = user.trim()
            if (name.isEmpty()) return@TextInputSpec
            app.textInput = TextInputSpec("Password for $name", "", "Password", secret = true, capitalize = false, doneLabel = "Sign In") { password ->
                app.scope.launch {
                    svc.signIn(name, password)
                        .onSuccess {
                            problem = null
                            signedInAs = it.userName ?: name
                            app.toasts.show("Signed in to ${it.serverName ?: server} as ${it.userName ?: name}", ToastKind.SUCCESS, icon = FuseIcons.CircleCheck)
                            sel.index = 0
                        }
                        .onFailure { problem = it.message ?: "That didn't sign in. Check the name and password." }
                }
            }
        }
    }

    fun signInWithCode() {
        val svc = service ?: return
        if (asking || quick != null) return
        asking = true
        problem = null
        quickJob = pageScope.launch {
            svc.startQuickConnect()
                .onSuccess { q ->
                    asking = false
                    quick = q
                    svc.awaitQuickConnect(q)
                        .onSuccess {
                            signedInAs = it.userName ?: "you"
                            app.toasts.show("Signed in to ${it.serverName ?: serverName ?: "Jellyfin"} as ${it.userName ?: "you"}", ToastKind.SUCCESS, icon = FuseIcons.CircleCheck)
                            sel.index = 0
                        }
                        .onFailure { problem = it.message }
                    quick = null
                }
                .onFailure {
                    asking = false
                    problem = it.message ?: "The server couldn't start Quick Connect."
                }
        }
    }

    fun stopCode() {
        quickJob?.cancel()
        quickJob = null
        quick = null
        asking = false
    }

    // Opened to sign in with a code: once there is an address, it starts by itself.
    LaunchedEffect(code, address.isNotBlank()) {
        if (code && address.isNotBlank() && signedInAs == null) signInWithCode()
    }

    val ready = address.isNotBlank()
    val rows = buildList {
        val who = signedInAs
        if (who != null) {
            add(MenuAction("j.done", "Done", FuseIcons.Check, detail = "Signed in as $who. Your films, shows and music are in Addons", onSelect = { app.back() }))
            add(MenuAction("j.again", "Sign In as Someone Else", FuseIcons.UserRound, onSelect = { signIn() }))
            add(MenuAction("j.again.code", "Someone Else, with a Code", FuseIcons.QrCode, onSelect = { signInWithCode() }))
        } else {
            if (ready) {
                add(MenuAction(
                    "j.signin", "Sign In", FuseIcons.UserRound,
                    detail = "To ${serverName ?: address.substringAfter("://")}, with your Jellyfin user name and password",
                    trailing = Trailing.Chevron, onSelect = { signIn() },
                ))
                add(MenuAction(
                    "j.code", "Sign In with a Code", FuseIcons.QrCode,
                    detail = "Approve a six-digit code in Jellyfin on your phone or computer (Quick Connect). Nothing to type here",
                    trailing = if (asking) Trailing.Value("Asking") else if (quick != null) Trailing.Value("Waiting") else Trailing.Chevron,
                    onSelect = { signInWithCode() },
                ))
            }
            for (s in found) {
                add(MenuAction("j.found.${s.address}", s.name, FuseIcons.Server, detail = "On this network, ${s.address.substringAfter("://")}", trailing = if (s.address == address) Trailing.Value("Chosen") else Trailing.Chevron, onSelect = { useAddress(s.address) }))
            }
            if (looking) add(infoRow("j.looking", "Looking on this network", detail = "Jellyfin servers answer in a moment", icon = FuseIcons.Search))
            add(MenuAction("j.type", "Type an Address", FuseIcons.Keyboard, detail = "Like 192.168.1.20:8096 or jellyfin.example.com", trailing = Trailing.Value(address.substringAfter("://").ifBlank { "Not set" }), onSelect = {
                app.textInput = TextInputSpec("Server address", address, "192.168.1.20:8096", capitalize = false, doneLabel = "Next") { typed ->
                    if (typed.isNotBlank()) useAddress(typed.trim())
                }
            }))
            add(MenuAction("j.remote", "Outside Address (optional)", FuseIcons.Globe, detail = "For away from home, through a tunnel or VPN", trailing = Trailing.Value(j.remoteAddress.substringAfter("://").ifBlank { "Not set" }), onSelect = {
                app.textInput = TextInputSpec("Outside address", j.remoteAddress, "https://jellyfin.example.com", capitalize = false) { v ->
                    app.store.updatePrefs { it.copy(jellyfin = it.jellyfin.copy(remoteAddress = v.trim())) }
                }
            }))
            if (!looking) add(MenuAction("j.look", "Look Again", FuseIcons.RefreshCcw, detail = "Servers on this network", onSelect = { round++ }))
        }
    }
    sel.clamp(rows.size)
    LaunchedEffect(quick != null) {
        app.hints = if (quick != null) listOf(Hint(HintButton.BACK, "Cancel")) else listOf(Hint(HintButton.CONFIRM, "Choose"), Hint(HintButton.BACK, "Back"))
    }
    InputLayer(enabled = app.focusZone == FocusZone.CONTENT && !app.overlayOpen) { e ->
        // While a code is on show, B takes it away and nothing else moves.
        if (quick != null) {
            return@InputLayer if (e.action == NavAction.BACK) {
                stopCode()
                NavResult.CONSUMED
            } else {
                NavResult.BLOCKED
            }
        }
        if (e.action == NavAction.LEFT) NavResult.BLOCKED else handleMenuAction(e, rows, sel)
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val compact = maxHeight < Size.touch * 12
        val wide = maxWidth >= 900.dp
        Column(Modifier.fillMaxSize().padding(horizontal = Space.gutter)) {
            Spacer(Modifier.height(Size.hudHeight + if (compact) Space.s else Space.l))
            FText("Set Up Jellyfin", if (compact) Fuse.type.title else Fuse.type.display, maxLines = 1)
            FText(
                problem ?: when {
                    signedInAs != null -> "Connected. Fuse Player plays your films, shows and music, with subtitles, resume and the next episode."
                    testing -> "Checking the server"
                    ready -> "Found ${serverName ?: "your server"}. Sign in with your Jellyfin account."
                    else -> "Choose your server, or type its address. Fuse remembers the home address and the one from outside, and uses whichever answers."
                },
                Fuse.type.body, color = if (problem != null) Fuse.colors.warning else Fuse.colors.textMuted, maxLines = 2,
            )
            Spacer(Modifier.height(if (compact) Space.m else Space.l))
            Row(Modifier.weight(1f).padding(bottom = Size.hintHeight + Space.s), horizontalArrangement = Arrangement.spacedBy(Space.xl), verticalAlignment = Alignment.CenterVertically) {
                val q = quick
                // Narrow, a code on show takes the page: beside the list it would be too thin to read.
                if (wide || q == null) {
                    Panel(Modifier.widthIn(max = Size.touch * 16).weight(1f).fillMaxHeight()) {
                        MenuList(rows, sel, modifier = Modifier.padding(Space.s), showSelection = app.focusZone == FocusZone.CONTENT && quick == null)
                    }
                }
                if (q != null) {
                    QuickConnectCard(app, q, if (wide) Modifier.width(340.dp).fillMaxHeight() else Modifier.weight(1f).fillMaxHeight())
                } else if (wide) {
                    Column(Modifier.width(320.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        JellyfinStage(connected = signedInAs != null, server = serverName)
                    }
                }
            }
        }
    }
}

/**
 * A Quick Connect code to approve in Jellyfin, large enough to read across a room, with a QR that
 * opens Jellyfin's Quick Connect page on a phone on the same network.
 */
@Composable
private fun QuickConnectCard(app: AppState, q: QuickConnectStart, modifier: Modifier) {
    val c = Fuse.colors
    val page = q.base.trimEnd('/') + "/web/#/quickconnect"
    val modules = remember(page) { app.phoneLink?.qr(page) }
    Panel(modifier) {
        Column(Modifier.fillMaxSize().padding(Space.l), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Space.m, Alignment.CenterVertically)) {
            FText("Approve in Jellyfin", Fuse.type.bodyStrong, maxLines = 1)
            if (modules != null) io.github.matiyaaa.fuse.ui.shell.settings.QrCode(modules, 150.dp)
            Box(Modifier.clip(RoundedCornerShape(Fuse.geometry.control)).background(c.text.copy(alpha = 0.07f)).padding(horizontal = Space.l, vertical = Space.s)) {
                FText(q.code.code.chunked(3).joinToString(" "), Fuse.type.numericLarge, maxLines = 1)
            }
            FText(
                "In Jellyfin on your phone or computer, open your profile, then Quick Connect, and enter this code. Or scan it on the same network.",
                Fuse.type.caption, color = c.textMuted, align = TextAlign.Center, maxLines = 4,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spinner(size = 16.dp, color = c.textMuted)
                Spacer(Modifier.width(Space.s))
                FText("Waiting for approval on ${q.info.name ?: "your server"}", Fuse.type.caption, color = c.textMuted, maxLines = 1)
            }
        }
    }
}
