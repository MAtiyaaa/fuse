package io.github.matiyaaa.fuse.ui.shell.sync

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.matiyaaa.fuse.model.NavAction
import io.github.matiyaaa.fuse.sync.DeviceInfo
import io.github.matiyaaa.fuse.sync.GameReport
import io.github.matiyaaa.fuse.sync.ProfileInfo
import io.github.matiyaaa.fuse.sync.ProfileReport
import io.github.matiyaaa.fuse.sync.SyncActivity
import io.github.matiyaaa.fuse.sync.SyncStatus
import io.github.matiyaaa.fuse.ui.designsystem.components.ButtonKind
import io.github.matiyaaa.fuse.ui.designsystem.components.FText
import io.github.matiyaaa.fuse.ui.designsystem.components.FuseButton
import io.github.matiyaaa.fuse.ui.designsystem.components.Hint
import io.github.matiyaaa.fuse.ui.designsystem.components.MenuAction
import io.github.matiyaaa.fuse.ui.designsystem.components.MenuList
import io.github.matiyaaa.fuse.ui.designsystem.components.Panel
import io.github.matiyaaa.fuse.ui.designsystem.components.ProfileAvatar
import io.github.matiyaaa.fuse.ui.designsystem.components.Spinner
import io.github.matiyaaa.fuse.ui.designsystem.components.StatusDot
import io.github.matiyaaa.fuse.ui.designsystem.components.ToastKind
import io.github.matiyaaa.fuse.ui.designsystem.components.Trailing
import io.github.matiyaaa.fuse.ui.designsystem.components.handleMenuAction
import io.github.matiyaaa.fuse.ui.designsystem.effects.rememberReveal
import io.github.matiyaaa.fuse.ui.designsystem.effects.reveal
import io.github.matiyaaa.fuse.ui.designsystem.focus.LinearSelection
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcon
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.designsystem.icons.HintButton
import io.github.matiyaaa.fuse.ui.designsystem.input.InputLayer
import io.github.matiyaaa.fuse.ui.designsystem.input.NavResult
import io.github.matiyaaa.fuse.ui.designsystem.sound.SoundCue
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.Size
import io.github.matiyaaa.fuse.ui.designsystem.theme.Space
import io.github.matiyaaa.fuse.ui.fuseline.PageEffect
import io.github.matiyaaa.fuse.ui.shell.app.AppState
import io.github.matiyaaa.fuse.ui.shell.app.FocusZone
import io.github.matiyaaa.fuse.ui.shell.app.Route
import io.github.matiyaaa.fuse.ui.shell.components.playtimeText
import io.github.matiyaaa.fuse.ui.shell.components.subTabsRoom
import io.github.matiyaaa.fuse.ui.shell.store.impl.TimeWords
import kotlinx.coroutines.launch

/**
 * Addons, Sync: Fuse Sync's Hub in the app, everything the host keeps for the person playing. Where
 * things stand with Sync Now, Switch Profile and Add a Device; their play time in all and by
 * device; how much the host keeps; and every game it knows, each with its play time and saves (how
 * many versions, how big, which device saved the newest), opening into the game's every version
 * and file. Beside it on wide screens: who is playing, every device, and what happened lately.
 */
@Composable
internal fun SyncTab(app: AppState, active: Boolean, topPadding: Dp) {
    val svc = app.store.sync.service ?: return
    val prefs by app.store.prefs.collectAsState()
    val status by svc.status.collectAsState()
    val profile by svc.activeProfile.collectAsState()
    val profiles by svc.profiles.collectAsState()
    val devices by svc.devices.collectAsState()
    val activity by svc.activity.collectAsState()
    val host = prefs.sync.role == "HOST"
    val words = syncWords(status)
    var index by remember { mutableIntStateOf(0) }
    var inList by remember { mutableStateOf(false) }
    val sel = remember { LinearSelection() }
    var syncing by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(app.syncReport == null) }
    val focused = active && app.focusZone == FocusZone.CONTENT && !app.overlayOpen
    val reveal = rememberReveal()
    // The report comes again whenever something happened (a save kept, changes brought in).
    LaunchedEffect(profile?.id, activity.firstOrNull()?.at) {
        val r = svc.report()
        if (r != null || app.syncReport?.profile?.id != profile?.id) app.syncReport = r
        loading = false
    }
    val report = app.syncReport?.takeIf { it.profile.id == profile?.id }
    val names = devices.associate { it.id to it.name }

    val actions = buildList<Pair<String, () -> Unit>> {
        add("Sync Now" to {
            if (!syncing) {
                syncing = true
                app.scope.launch {
                    svc.syncNow().onSuccess { app.toasts.show("Up to date", ToastKind.SUCCESS, icon = FuseIcons.CloudCheck) }
                        .onFailure { app.toasts.show(it.message ?: "The host isn't answering", ToastKind.WARNING) }
                    app.syncReport = svc.report() ?: app.syncReport
                    syncing = false
                }
            }
        })
        add("Switch Profile" to { app.whoAreYou = WhoMode.SWITCH })
        add("New Profile" to { app.whoAreYou = WhoMode.ADD })
        if (host) add("Add a Device" to { app.pairing = true })
        add("Settings" to { app.go(Route.SyncSettings) })
    }
    val rows = report?.games.orEmpty().map { g -> gameRow(app, g, names) }
    sel.clamp(rows.size)
    PageEffect(focused, inList) {
        if (focused) app.hints = if (inList) listOf(Hint(HintButton.CONFIRM, "Open"), Hint(HintButton.BACK, "Back")) else listOf(Hint(HintButton.CONFIRM, "Choose"), Hint(HintButton.BACK, "Back"))
    }
    InputLayer(enabled = focused) { e ->
        if (inList) {
            when {
                e.action == NavAction.UP && sel.index == 0 -> { inList = false; NavResult.MOVED }
                e.action == NavAction.LEFT || e.action == NavAction.RIGHT -> NavResult.BLOCKED
                else -> handleMenuAction(e, rows, sel)
            }
        } else {
            when (e.action) {
                NavAction.LEFT -> if (index > 0) { index--; app.platform.sounds.play(SoundCue.MOVE); NavResult.MOVED } else NavResult.BLOCKED
                NavAction.RIGHT -> if (index < actions.size - 1) { index++; app.platform.sounds.play(SoundCue.MOVE); NavResult.MOVED } else NavResult.BLOCKED
                NavAction.SELECT -> { actions.getOrNull(index)?.second?.invoke(); NavResult.ACTIVATED }
                NavAction.DOWN -> if (rows.isNotEmpty()) { inList = true; NavResult.MOVED } else NavResult.BLOCKED
                else -> NavResult.IGNORED
            }
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 1000.dp
        val compact = maxHeight < 560.dp
        Column(
            Modifier.fillMaxSize().padding(horizontal = Space.gutter)
                .padding(top = topPadding + subTabsRoom() + Space.m, bottom = Size.hintHeight + Space.s),
            verticalArrangement = Arrangement.spacedBy(if (compact) Space.s else Space.m),
        ) {
            Row(Modifier.reveal(reveal, 0), verticalAlignment = Alignment.CenterVertically) {
                SyncMark(if (compact) Size.thumb else Size.thumbL)
                Spacer(Modifier.width(Space.l))
                Column(Modifier.weight(1f)) {
                    FText(SYNC_NAME, if (compact) Fuse.type.title else Fuse.type.display, maxLines = 1)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusDot(words.ok)
                        Spacer(Modifier.width(Space.s))
                        FText(words.title, Fuse.type.bodyStrong, maxLines = 1)
                        FText("  ·  ${words.detail}", Fuse.type.body, color = Fuse.colors.textMuted, maxLines = 1)
                    }
                }
            }
            Row(Modifier.reveal(reveal, 1), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                actions.forEachIndexed { i, (label, run) ->
                    val icon = when (label) {
                        "Sync Now" -> FuseIcons.RefreshCcw
                        "Switch Profile" -> FuseIcons.Users
                        "New Profile" -> FuseIcons.UserPlus
                        "Add a Device" -> FuseIcons.Plus
                        else -> FuseIcons.Settings
                    }
                    FuseButton(
                        label, selected = focused && !inList && index == i, onClick = { index = i; inList = false; run() }, icon = icon,
                        kind = if (i == 0) ButtonKind.PRIMARY else ButtonKind.SECONDARY,
                        loading = i == 0 && (syncing || (status as? SyncStatus.Online)?.working == true),
                        height = if (compact) 40.dp else Size.touch,
                    )
                }
            }
            if (report != null && !compact) Stats(report, Modifier.reveal(reveal, 2))
            Row(Modifier.weight(1f).reveal(reveal, 3), horizontalArrangement = Arrangement.spacedBy(Space.l)) {
                Panel(Modifier.weight(1f).fillMaxHeight()) {
                    Column {
                        Row(Modifier.padding(start = Space.l, end = Space.l, top = Space.m), verticalAlignment = Alignment.CenterVertically) {
                            FuseIcon(FuseIcons.Save, size = Size.iconS, tint = Fuse.colors.textMuted)
                            Spacer(Modifier.width(Space.s))
                            FText("EVERYTHING ON THE HOST", Fuse.type.overline, color = Fuse.colors.textMuted, maxLines = 1, modifier = Modifier.weight(1f))
                            if (report != null) FText("${report.games.size} games", Fuse.type.caption, color = Fuse.colors.textMuted, maxLines = 1)
                        }
                        when {
                            report == null && loading -> Box(Modifier.fillMaxWidth().padding(Space.xxl), contentAlignment = Alignment.Center) { Spinner(size = 24.dp, color = Fuse.colors.textMuted) }
                            report == null -> FText("The host isn't answering. Its saves show here once it does.", Fuse.type.body, color = Fuse.colors.textMuted, modifier = Modifier.padding(Space.l))
                            rows.isEmpty() -> FText("Nothing yet. Play something and its save and play time show up here.", Fuse.type.body, color = Fuse.colors.textMuted, modifier = Modifier.padding(Space.l))
                            else -> MenuList(rows, sel, modifier = Modifier.padding(Space.s), showSelection = focused && inList)
                        }
                    }
                }
                if (wide) {
                    Column(Modifier.width(340.dp).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Space.m)) {
                        PlayerCard(profile, profiles.size, report, names)
                        DeviceCard(devices, prefs.sync.deviceId, profiles)
                        ActivityCard(activity)
                    }
                }
            }
        }
    }
}

/** A game on the host, as its row reads: play time, its saves, the newest and who saved it, and the space it takes. */
private fun gameRow(app: AppState, g: GameReport, names: Map<String, String>): MenuAction {
    val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
    val newest = g.slots.flatMap { it.versions }.maxByOrNull { it.at }
    val detail = listOfNotNull(
        g.platform.uppercase().takeIf { it.isNotEmpty() },
        playtimeText(g.playSeconds).takeIf { g.playSeconds > 0 },
        g.slots.joinToString(", ") { "${it.versions.size} ${if (it.versions.size == 1) it.kind.label.lowercase() else it.kind.label.lowercase() + "s"}" }.ifEmpty { null },
        newest?.let { "newest from ${it.device}, ${TimeWords.relative(it.at, now, localOffsetMillis(now))}" },
    ).joinToString("  ·  ")
    val bytes = g.slots.sumOf { it.bytes }
    return MenuAction(
        g.game, g.name, if (g.slots.isNotEmpty()) FuseIcons.Save else FuseIcons.Clock,
        detail = detail,
        trailing = if (bytes > 0) Trailing.Value(sizeText(bytes)) else Trailing.Chevron,
        onSelect = { app.go(Route.SyncGame(g.game, g.name)) },
    )
}

/** The person's totals: play time, games, what the host keeps for them, and versions. */
@Composable
private fun Stats(r: ProfileReport, modifier: Modifier) {
    val versions = r.games.sumOf { g -> g.slots.sumOf { it.versions.size } }
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.m)) {
        Stat("Played in all", if (r.playSeconds > 0) playtimeText(r.playSeconds) else "Nothing yet", FuseIcons.Clock, Modifier.weight(1f))
        Stat("Games", "${r.games.count { it.playSeconds > 0 }} played, ${r.games.count { it.slots.isNotEmpty() }} saved", FuseIcons.Gamepad, Modifier.weight(1f))
        Stat("Kept on the host", sizeText(r.savesBytes), FuseIcons.HardDrive, Modifier.weight(1f))
        Stat("Save versions", "$versions", FuseIcons.History, Modifier.weight(1f))
    }
}

@Composable
private fun Stat(label: String, value: String, icon: ImageVector, modifier: Modifier) {
    val c = Fuse.colors
    val shape = RoundedCornerShape(Fuse.geometry.panel)
    Row(
        modifier.clip(shape).background(c.surface.copy(alpha = 0.72f)).border(1.dp, c.hairline, shape).padding(horizontal = Space.l, vertical = Space.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(c.accent.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
            FuseIcon(icon, size = Size.iconS, tint = c.accent)
        }
        Spacer(Modifier.width(Space.m))
        Column {
            FText(label, Fuse.type.caption, color = c.textMuted, maxLines = 1)
            FText(value, Fuse.type.bodyStrong, maxLines = 1)
        }
    }
}

@Composable
internal fun Card(title: String, icon: ImageVector, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val c = Fuse.colors
    val shape = RoundedCornerShape(Fuse.geometry.panel)
    Column(
        modifier.fillMaxWidth().clip(shape).background(c.surface.copy(alpha = 0.72f)).border(1.dp, c.hairline, shape).padding(Space.l),
        verticalArrangement = Arrangement.spacedBy(Space.m),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FuseIcon(icon, size = Size.iconS, tint = c.textMuted)
            Spacer(Modifier.width(Space.s))
            FText(title.uppercase(), Fuse.type.overline, color = c.textMuted, maxLines = 1)
        }
        content()
    }
}

/** Play time by device, as bars, longest first. */
@Composable
internal fun PlayBars(play: Map<String, Long>, names: Map<String, String>) {
    val c = Fuse.colors
    val max = play.values.maxOrNull()?.takeIf { it > 0 } ?: return
    Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
        for ((d, sec) in play.entries.sortedByDescending { it.value }) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row {
                    FText(names[d] ?: "A device no longer linked", Fuse.type.label, maxLines = 1, modifier = Modifier.weight(1f))
                    FText(playtimeText(sec), Fuse.type.label, color = c.textMuted, maxLines = 1)
                }
                Box(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(c.text.copy(alpha = 0.08f))) {
                    Box(
                        Modifier.fillMaxWidth((sec.toFloat() / max).coerceIn(0.02f, 1f)).height(6.dp).clip(CircleShape)
                            .background(Brush.horizontalGradient(listOf(c.accent.copy(alpha = 0.7f), c.accent))),
                    )
                }
            }
        }
    }
}

@Composable
private fun PlayerCard(profile: ProfileInfo?, count: Int, report: ProfileReport?, names: Map<String, String>) {
    Card("Playing here", FuseIcons.UserRound) {
        if (profile == null) {
            FText("No one yet", Fuse.type.title, maxLines = 1)
            FText("Choose who is playing to bring in their library and saves", Fuse.type.caption, color = Fuse.colors.textMuted, maxLines = 2)
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ProfileAvatar(profile.avatar, 52.dp)
                Spacer(Modifier.width(Space.m))
                Column {
                    FText(profile.name, Fuse.type.title, maxLines = 1)
                    FText(count(count, "profile") + " on this host", Fuse.type.caption, color = Fuse.colors.textMuted, maxLines = 1)
                }
            }
            if (report != null && report.devicePlay.isNotEmpty()) {
                FText("Play time by device", Fuse.type.caption, color = Fuse.colors.textMuted, maxLines = 1)
                PlayBars(report.devicePlay, names)
            }
        }
    }
}

@Composable
private fun DeviceCard(devices: List<DeviceInfo>, self: String, profiles: List<ProfileInfo>) {
    val c = Fuse.colors
    val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
    val offset = localOffsetMillis(now)
    val linked = devices.filterNot { it.revoked }
    Card("Devices", FuseIcons.MonitorSmartphone) {
        if (linked.isEmpty()) FText("Only this one so far", Fuse.type.body, color = c.textMuted, maxLines = 1)
        for (d in linked.take(8)) {
            val online = now - d.lastSeen < ONLINE_MS
            val who = profiles.firstOrNull { it.id == d.profile }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(c.text.copy(alpha = 0.06f)), contentAlignment = Alignment.Center) {
                    FuseIcon(if (d.platform == "ANDROID") FuseIcons.Smartphone else FuseIcons.Laptop, size = Size.iconS, tint = c.text)
                }
                Spacer(Modifier.width(Space.m))
                Column(Modifier.weight(1f)) {
                    FText(d.name + if (d.id == self) "  (this one)" else "", Fuse.type.bodyStrong, maxLines = 1)
                    FText(
                        when {
                            online -> if (d.connection == "REMOTE") "Online, from outside" else "Online"
                            d.lastSeen > 0 -> "Seen ${TimeWords.relative(d.lastSeen, now, offset)}"
                            else -> "Not seen yet"
                        },
                        Fuse.type.caption, color = c.textMuted, maxLines = 1,
                    )
                }
                if (who != null) ProfileAvatar(who.avatar, 26.dp)
                Spacer(Modifier.width(Space.s))
                Box(Modifier.size(8.dp).clip(CircleShape).background(if (online) c.success else c.textFaint))
            }
        }
    }
}

@Composable
private fun ActivityCard(activity: List<SyncActivity>) {
    val c = Fuse.colors
    val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
    val offset = localOffsetMillis(now)
    Card("Lately", FuseIcons.History) {
        if (activity.isEmpty()) FText("Nothing yet. Play something and its save shows up here.", Fuse.type.body, color = c.textMuted, maxLines = 2)
        for (a in activity.take(6)) {
            Row(verticalAlignment = Alignment.Top) {
                FuseIcon(
                    when (a.kind) {
                        "save" -> FuseIcons.Save
                        "conflict" -> FuseIcons.GitCompare
                        else -> FuseIcons.RefreshCcw
                    },
                    size = Size.iconS, tint = if (a.kind == "conflict") c.warning else c.accent, modifier = Modifier.padding(top = 2.dp),
                )
                Spacer(Modifier.width(Space.m))
                Column(Modifier.weight(1f)) {
                    FText(a.text, Fuse.type.label, maxLines = 2)
                    FText(TimeWords.relative(a.at, now, offset).replaceFirstChar { it.uppercase() }, Fuse.type.caption, color = c.textFaint, maxLines = 1)
                }
            }
        }
    }
}

/** A device heard from within this long counts as online. */
private const val ONLINE_MS = 2 * 60_000L
