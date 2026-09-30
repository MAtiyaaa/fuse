package io.github.matiyaaa.fuse.ui.shell.cartridge

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.github.matiyaaa.fuse.model.CartridgeRoute
import io.github.matiyaaa.fuse.model.CartridgeStatus
import io.github.matiyaaa.fuse.model.NavAction
import io.github.matiyaaa.fuse.model.QueueState
import io.github.matiyaaa.fuse.model.ReleaseInfo
import io.github.matiyaaa.fuse.ui.designsystem.components.ButtonKind
import io.github.matiyaaa.fuse.ui.designsystem.components.FText
import io.github.matiyaaa.fuse.ui.designsystem.components.FuseButton
import io.github.matiyaaa.fuse.ui.designsystem.components.Hint
import io.github.matiyaaa.fuse.ui.designsystem.components.Panel
import io.github.matiyaaa.fuse.ui.designsystem.components.ProgressBar
import io.github.matiyaaa.fuse.ui.designsystem.components.SectionLabel
import io.github.matiyaaa.fuse.ui.designsystem.components.StatusDot
import io.github.matiyaaa.fuse.ui.designsystem.focus.FollowSelection
import io.github.matiyaaa.fuse.ui.designsystem.focus.LinearSelection
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcon
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.designsystem.icons.HintButton
import io.github.matiyaaa.fuse.ui.designsystem.input.InputLayer
import io.github.matiyaaa.fuse.ui.designsystem.input.NavResult
import io.github.matiyaaa.fuse.ui.designsystem.media.ArtSlot
import io.github.matiyaaa.fuse.ui.designsystem.media.GeneratedArt
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.Size
import io.github.matiyaaa.fuse.ui.designsystem.theme.Space
import io.github.matiyaaa.fuse.ui.designsystem.theme.toColor
import io.github.matiyaaa.fuse.ui.shell.app.AppState
import io.github.matiyaaa.fuse.ui.shell.app.ConfirmSpec
import io.github.matiyaaa.fuse.ui.shell.app.FocusZone
import io.github.matiyaaa.fuse.ui.shell.app.activateGame
import io.github.matiyaaa.fuse.ui.shell.app.play
import io.github.matiyaaa.fuse.ui.shell.components.SquareGameArt
import io.github.matiyaaa.fuse.ui.shell.components.agoText
import io.github.matiyaaa.fuse.ui.shell.home.bytesText
import io.github.matiyaaa.fuse.ui.shell.store.Art
import io.github.matiyaaa.fuse.ui.shell.store.RecentDownload
import kotlinx.coroutines.launch

private data class CartAction(val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector, val primary: Boolean = false, val run: () -> Unit)

/**
 * Fuse's own summary of Cartridge: connection, live downloads and what just arrived. Anything more
 * opens the real Cartridge (which keeps its own look); pressing Back there returns straight here and
 * Fuse picks up the new games by itself.
 */
@Composable
fun CartridgeScreen(app: AppState) {
    val store = app.store
    val status by store.cartridge.status.collectAsState()
    val recent by store.cartridge.recent.collectAsState()
    var release by remember { mutableStateOf<ReleaseInfo?>(null) }
    var installing by remember { mutableStateOf(false) }
    val actionsSel = remember { LinearSelection() }
    val recentSel = remember { LinearSelection() }
    var inRecent by remember { mutableStateOf(false) }

    LaunchedEffect(status.installed) {
        store.cartridge.refresh()
        if (!status.installed) release = store.cartridge.latestRelease()
    }

    fun open(route: CartridgeRoute) = store.cartridge.open(route)

    val actions = if (!status.installed) listOf(
        CartAction(if (installing) "Installing" else "Install Cartridge", FuseIcons.Download, primary = true) {
            val r = release
            if (r == null) {
                app.toasts.show("Couldn't reach GitHub to find Cartridge's latest release. Check your connection.")
            } else if (!installing) {
                app.confirm = ConfirmSpec(
                    title = "Install Cartridge ${r.tag.removePrefix("v")}?",
                    message = "Fuse downloads the official release from GitHub (github.com/MAtiyaaa/cartridge) and hands it to your system's installer, where you confirm it. Nothing installs silently.",
                    confirmLabel = "Download and install",
                    onConfirm = {
                        installing = true
                        app.scope.launch {
                            val result = store.cartridge.install(r)
                            installing = false
                            result.onFailure { app.toasts.show(it.message ?: "Couldn't install Cartridge") }
                        }
                    },
                )
            }
        },
        CartAction("About Cartridge", FuseIcons.Info) { app.platform.openUrl("https://github.com/MAtiyaaa/cartridge") },
    ) else listOf(
        CartAction("Open Cartridge", FuseIcons.External, primary = true) { open(CartridgeRoute.Home) },
        CartAction("Browse RomM", FuseIcons.Library) { open(CartridgeRoute.Library) },
        CartAction("Downloads", FuseIcons.Download) { open(CartridgeRoute.Downloads) },
        CartAction("Consoles", FuseIcons.Chip) { open(CartridgeRoute.Consoles) },
        CartAction("Sync library", FuseIcons.Refresh) { open(CartridgeRoute.Sync) },
    )
    actionsSel.clamp(actions.size)
    recentSel.clamp(recent.size)

    LaunchedEffect(inRecent, recentSel.index, recent) {
        app.hero = null
        app.hints = if (inRecent && recent.isNotEmpty()) {
            listOf(Hint(HintButton.CONFIRM, if (recent[recentSel.index].game != null) "Play" else "Open in Cartridge"), Hint(HintButton.OPTIONS, "Open in Cartridge"))
        } else listOf(Hint(HintButton.CONFIRM, "Choose"))
    }

    InputLayer(enabled = app.focusZone == FocusZone.CONTENT && !app.overlayOpen) { e ->
        if (inRecent) {
            when (e.action) {
                NavAction.UP -> if (recentSel.index == 0) { inRecent = false; NavResult.MOVED } else recentSel.move(e.action, recent.size, vertical = true)
                NavAction.DOWN, NavAction.PAGE_UP, NavAction.PAGE_DOWN -> recentSel.move(e.action, recent.size, vertical = true).let { if (it == NavResult.IGNORED) NavResult.BLOCKED else it }
                NavAction.SELECT -> {
                    val r = recent.getOrNull(recentSel.index) ?: return@InputLayer NavResult.BLOCKED
                    if (r.game != null) app.activateGame(r.game) else open(CartridgeRoute.Game(r.download.romId))
                    NavResult.ACTIVATED
                }
                NavAction.CONTEXT -> { recent.getOrNull(recentSel.index)?.let { open(CartridgeRoute.Game(it.download.romId)) }; NavResult.ACTIVATED }
                else -> NavResult.IGNORED
            }
        } else {
            when (e.action) {
                NavAction.LEFT, NavAction.RIGHT -> actionsSel.move(e.action, actions.size).let { if (it == NavResult.IGNORED) NavResult.BLOCKED else it }
                NavAction.DOWN -> if (recent.isNotEmpty()) { inRecent = true; NavResult.MOVED } else NavResult.BLOCKED
                NavAction.SELECT -> { actions[actionsSel.index].run(); NavResult.ACTIVATED }
                else -> NavResult.IGNORED
            }
        }
    }

    val c = Fuse.colors
    Column(Modifier.fillMaxSize().padding(horizontal = Space.gutter)) {
        Spacer(Modifier.height(Size.hudHeight + Space.xl))
        Row(verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                SectionLabel("Get games")
                Spacer(Modifier.height(Space.s))
                FText("Cartridge", Fuse.type.hero)
                Spacer(Modifier.height(Space.s))
                FText(statusLine(status), Fuse.type.body, color = c.textMuted, maxLines = 2, modifier = Modifier.widthIn(max = 640.dp))
            }
            if (status.installed) ConnectionBadge(status)
        }
        Spacer(Modifier.height(Space.xl))
        Row(horizontalArrangement = Arrangement.spacedBy(Space.m)) {
            actions.forEachIndexed { i, a ->
                FuseButton(
                    a.label,
                    selected = !inRecent && i == actionsSel.index && app.focusZone == FocusZone.CONTENT,
                    onClick = { actionsSel.index = i; inRecent = false; a.run() },
                    icon = a.icon,
                    kind = if (a.primary) ButtonKind.PRIMARY else ButtonKind.SECONDARY,
                )
            }
        }
        if (!status.installed) {
            Spacer(Modifier.height(Space.xl))
            InstallExplainer(release)
            return@Column
        }
        if (status.installed && !status.bridge) {
            Spacer(Modifier.height(Space.l))
            Panel(Modifier.widthIn(max = 720.dp)) {
                Row(Modifier.padding(Space.l), horizontalArrangement = Arrangement.spacedBy(Space.m), verticalAlignment = Alignment.CenterVertically) {
                    FuseIcon(FuseIcons.Info, tint = c.warning)
                    FText(
                        "This Cartridge (${status.version ?: "unknown version"}) opens, but it can't be opened on a page or show its downloads here. Cartridge 0.9.10 or newer can.",
                        Fuse.type.body, maxLines = 3,
                    )
                }
            }
        }
        if (status.activeDownloads > 0 || status.queuedDownloads > 0 || status.queue.isNotEmpty()) {
            Spacer(Modifier.height(Space.xl))
            DownloadsPanel(status, Modifier.widthIn(max = 720.dp))
        }
        if (recent.isNotEmpty()) {
            Spacer(Modifier.height(Space.xl))
            SectionLabel("Recently downloaded")
            Spacer(Modifier.height(Space.m))
            val list = rememberLazyListState()
            FollowSelection(list, { recentSel.index }, anchor = 0.3f)
            LazyColumn(state = list, verticalArrangement = Arrangement.spacedBy(Space.xs), modifier = Modifier.widthIn(max = 820.dp).weight(1f)) {
                itemsIndexed(recent, key = { _, r -> r.download.romId }) { i, r ->
                    RecentRow(r, selected = inRecent && i == recentSel.index && app.focusZone == FocusZone.CONTENT)
                }
                item { Spacer(Modifier.height(Size.hintHeight + Space.xl)) }
            }
        }
    }
}

/**
 * What Cartridge is downloading: the current game with its progress, then each queued game with its
 * own (bridge 2), or the queue count (older Cartridge).
 */
@Composable
private fun DownloadsPanel(status: CartridgeStatus, modifier: Modifier = Modifier) {
    val c = Fuse.colors
    val current = status.queue.firstOrNull { it.state == QueueState.DOWNLOADING }
    Panel(modifier) {
        Column(Modifier.padding(Space.l), verticalArrangement = Arrangement.spacedBy(Space.s)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionLabel("Downloading", Modifier.weight(1f))
                val pct = (current?.progress ?: status.progress)?.let { "${(it * 100).toInt()}%" }
                if (pct != null) FText(pct, Fuse.type.label, color = c.accent)
            }
            FText(current?.title ?: status.currentTitle ?: "Preparing", Fuse.type.titleSmall, maxLines = 1)
            val platform = current?.platformSlug ?: status.currentPlatform
            val sizes = current?.let { q -> q.total?.let { "${bytesText(q.received)} of ${bytesText(it)}" } }
            listOfNotNull(platform?.uppercase(), sizes).takeIf { it.isNotEmpty() }?.let {
                FText(it.joinToString("  ·  "), Fuse.type.caption, color = c.textMuted, maxLines = 1)
            }
            ProgressBar(current?.progress ?: status.progress, Modifier.fillMaxWidth(), height = 6.dp)
            val waiting = status.queue.filter { it !== current }
            if (waiting.isNotEmpty()) {
                Spacer(Modifier.height(Space.xs))
                for (q in waiting.take(5)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                        FuseIcon(
                            when (q.state) {
                                QueueState.PAUSED -> FuseIcons.Timer
                                QueueState.FAILED -> FuseIcons.Warning
                                else -> FuseIcons.Clock
                            },
                            size = 16.dp, tint = if (q.state == QueueState.FAILED) c.warning else c.textMuted,
                        )
                        FText(q.title, Fuse.type.body, maxLines = 1, modifier = Modifier.weight(1f))
                        FText(
                            when (q.state) {
                                QueueState.PAUSED -> "Paused"
                                QueueState.FAILED -> "Failed"
                                else -> q.progress?.takeIf { it > 0f }?.let { "${(it * 100).toInt()}%" } ?: "Waiting"
                            },
                            Fuse.type.caption, color = c.textMuted,
                        )
                    }
                }
                if (waiting.size > 5) FText("and ${waiting.size - 5} more", Fuse.type.caption, color = c.textMuted)
            } else if (status.queuedDownloads > 0) {
                FText("${status.queuedDownloads} more in the queue", Fuse.type.caption, color = c.textMuted)
            }
        }
    }
}



private fun statusLine(s: CartridgeStatus): String = when {
    !s.installed -> "Cartridge brings your RomM server's games to this device, each into the right system folder. Fuse shows them the moment they land."
    s.activeDownloads > 0 -> "Downloading ${s.activeDownloads + s.queuedDownloads} ${if (s.activeDownloads + s.queuedDownloads == 1) "game" else "games"}. They'll appear in Fuse on their own."
    s.connected == false -> "Cartridge isn't connected to your RomM server right now."
    else -> "Find a game in Cartridge, download it, press Back, and it's ready to play here."
}

@Composable
private fun ConnectionBadge(s: CartridgeStatus) {
    val c = Fuse.colors
    Row(
        Modifier.clip(RoundedCornerShape(Fuse.geometry.control)).background(c.text.copy(alpha = 0.08f)).padding(horizontal = Space.m, vertical = Space.s),
        horizontalArrangement = Arrangement.spacedBy(Space.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDot(s.connected)
        FText(
            when (s.connected) { true -> "Connected to RomM"; false -> "Not connected"; null -> "Status unknown" },
            Fuse.type.label,
        )
        s.version?.let { FText("v$it", Fuse.type.caption, color = c.textMuted) }
    }
}

@Composable
private fun RecentRow(r: RecentDownload, selected: Boolean) {
    val c = Fuse.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Fuse.geometry.control))
            .background(if (selected) c.text.copy(alpha = 0.1f) else Color.Transparent)
            .padding(horizontal = Space.m, vertical = Space.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(3.dp).height(24.dp).background(if (selected) c.accent else Color.Transparent, RoundedCornerShape(2.dp)))
        Spacer(Modifier.width(Space.m))
        val game = r.game
        SquareGameArt(
            game?.art ?: Art.None,
            Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)),
            fallback = { GeneratedArt(r.download.title, (game?.accent ?: 0xFF5B6475).toColor(), slot = ArtSlot.ICON) },
        )
        Spacer(Modifier.width(Space.m))
        Column(Modifier.weight(1f)) {
            FText(game?.title ?: r.download.title, Fuse.type.bodyStrong, maxLines = 1)
            FText(
                listOfNotNull(game?.platformShort ?: r.download.platformSlug.uppercase(), "Downloaded ${agoText(r.download.finishedAt)}").joinToString("  ·  "),
                Fuse.type.caption, color = c.textMuted, maxLines = 1,
            )
        }
        FText(if (game != null) "Ready to play" else "Finding it", Fuse.type.label, color = if (game != null) c.success else c.textMuted)
    }
}

@Composable
private fun InstallExplainer(release: ReleaseInfo?) {
    val c = Fuse.colors
    Panel(Modifier.widthIn(max = 720.dp)) {
        Column(Modifier.padding(Space.l), verticalArrangement = Arrangement.spacedBy(Space.s)) {
            SectionLabel("How it works")
            FText("1. Cartridge connects to your RomM server and lets you browse it with a controller.", Fuse.type.body)
            FText("2. It downloads games straight into your ROM folders, BIOS included when RomM has it.", Fuse.type.body)
            FText("3. Press Back and Fuse already has the new game, with its art.", Fuse.type.body)
            Spacer(Modifier.height(Space.xs))
            FText(
                release?.let { "Latest release: ${it.name}" } ?: "Checking for the latest release",
                Fuse.type.caption, color = c.textMuted,
            )
        }
    }
}
