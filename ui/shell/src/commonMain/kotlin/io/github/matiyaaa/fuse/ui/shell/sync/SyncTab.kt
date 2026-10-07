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
import androidx.compose.ui.platform.testTag
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
import io.github.matiyaaa.fuse.ui.designsystem.components.fadingEdges
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
import io.github.matiyaaa.fuse.ui.designsystem.effects.background
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
import io.github.matiyaaa.fuse.ui.fuseline.Durations
import io.github.matiyaaa.fuse.ui.fuseline.PageEffect
import io.github.matiyaaa.fuse.ui.fuseline.fuselineColor
import io.github.matiyaaa.fuse.ui.fuseline.tween
import androidx.compose.foundation.clickable
import io.github.matiyaaa.fuse.ui.shell.app.AppState
import io.github.matiyaaa.fuse.ui.shell.app.FocusZone
import io.github.matiyaaa.fuse.ui.shell.app.Route
import io.github.matiyaaa.fuse.ui.shell.components.playtimeText
import io.github.matiyaaa.fuse.ui.shell.components.subTabsRoom
import io.github.matiyaaa.fuse.ui.shell.store.impl.TimeWords
import androidx.compose.foundation.gestures.animateScrollBy
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Addons, Sync: what the host keeps for the person playing. A slim line at the top says who is
 * playing, on which host, and carries the few things to do (Sync Now, switching profile, adding
 * someone or a device, Settings); how things stand shows only when something is wrong. Below it,
 * every game the host knows, as one list that the top line and the tabs fold away from as you go
 * down, so several games are always in view; each opens into its saves through time. Devices,
 * play time by device and what happened lately sit beside the games on wide screens, and after
 * them on narrower ones.
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
    // Past the last game, on a screen with no column beside: reading devices and what happened.
    var inRest by remember { mutableStateOf(false) }
    // The column beside the games on wide screens, reached with Right.
    var inSide by remember { mutableStateOf(false) }
    // The Remote Library's card, between the actions and the games.
    var inLib by remember { mutableStateOf(false) }
    val reach by app.store.reach.state.collectAsState()
    val hasLib = reach.supported || reach.devices.isNotEmpty()
    val sideScroll = rememberScrollState()
    val list = androidx.compose.foundation.lazy.rememberLazyListState()
    io.github.matiyaaa.fuse.ui.shell.components.ReportScroll(list)
    val density = androidx.compose.ui.platform.LocalDensity.current
    val step = with(density) { 160.dp.toPx() }
    val sel = remember { LinearSelection() }
    var syncing by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(app.syncReport == null) }
    val focused = active && app.focusZone == FocusZone.CONTENT && !app.overlayOpen
    // The report comes again whenever something happened (a save kept, changes brought in).
    LaunchedEffect(profile?.id, activity.firstOrNull()?.at) {
        val r = svc.report()
        if (r != null || app.syncReport?.profile?.id != profile?.id) app.syncReport = r
        loading = false
    }
    val report = app.syncReport?.takeIf { it.profile.id == profile?.id }
    val names = devices.associate { it.id to it.name }

    val actions = buildList {
        add(SyncAction("Sync Now", FuseIcons.RefreshCcw) {
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
        // The household's games on its other devices: their own small library.
        if (hasLib) add(SyncAction("Library", FuseIcons.MonitorSmartphone) { app.go(Route.HouseholdLibrary) })
        add(SyncAction("Switch Profile", FuseIcons.Users) { app.whoAreYou = WhoMode.SWITCH })
        add(SyncAction("New Profile", FuseIcons.UserPlus) { app.whoAreYou = WhoMode.ADD })
        // Any device already in can bring in another: a code here, or saying yes when it asks.
        add(SyncAction("Add a Device", FuseIcons.Plus) { app.pairing = true })
        add(SyncAction("Settings", FuseIcons.Settings) { app.go(Route.SyncSettings) })
    }
    // Each game's cover from this library, where it has the game (by its id across devices, or its name).
    val covers = rememberSyncCovers(app)
    val games = report?.games.orEmpty()
    sel.clamp(games.size)
    fun open(i: Int) {
        val g = games.getOrNull(i) ?: return
        app.go(Route.SyncGame(g.game, g.name))
    }
    PageEffect(focused, inList, inSide, inRest, inLib, index) {
        if (focused) app.hints = when {
            inSide || inRest -> listOf(Hint(HintButton.DPAD, "Scroll"), Hint(HintButton.BACK, "Back"))
            inLib -> listOf(Hint(HintButton.CONFIRM, "Open the Remote Library"), Hint(HintButton.BACK, "Back"))
            inList -> listOf(Hint(HintButton.CONFIRM, "Open"), Hint(HintButton.BACK, "Back"))
            else -> listOf(Hint(HintButton.CONFIRM, actions.getOrNull(index)?.label ?: "Choose"), Hint(HintButton.BACK, "Back"))
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // Devices and what happened lately sit beside the games only where the games keep plenty of room.
        val side = maxWidth >= 1100.dp
        val compact = maxHeight < 560.dp
        val labels = maxWidth >= 900.dp
        // The list's items: the top line, the games' heading, the games, then (no column beside) the rest.
        val firstGame = if (hasLib) 3 else 2
        val rest = if (side) 0 else 3
        InputLayer(enabled = focused) { e ->
            when {
                inSide -> when (e.action) {
                    NavAction.UP -> if (sideScroll.value > 0) { app.scope.launch { sideScroll.animateScrollBy(-step) }; NavResult.MOVED } else NavResult.BLOCKED
                    NavAction.DOWN -> if (sideScroll.value < sideScroll.maxValue) { app.scope.launch { sideScroll.animateScrollBy(step) }; NavResult.MOVED } else NavResult.BLOCKED
                    NavAction.LEFT, NavAction.BACK -> { inSide = false; NavResult.MOVED }
                    NavAction.RIGHT, NavAction.SELECT -> NavResult.BLOCKED
                    else -> NavResult.IGNORED
                }
                inRest -> when (e.action) {
                    NavAction.DOWN -> if (list.canScrollForward) { app.scope.launch { list.animateScrollBy(step) }; NavResult.MOVED } else NavResult.BLOCKED
                    NavAction.UP, NavAction.BACK -> { inRest = false; NavResult.MOVED }
                    NavAction.LEFT, NavAction.RIGHT, NavAction.SELECT -> NavResult.BLOCKED
                    else -> NavResult.IGNORED
                }
                inLib -> when (e.action) {
                    NavAction.UP -> { inLib = false; NavResult.MOVED }
                    NavAction.DOWN -> when {
                        games.isNotEmpty() -> { inLib = false; inList = true; NavResult.MOVED }
                        rest > 0 -> { inLib = false; inRest = true; app.scope.launch { list.animateScrollBy(step) }; NavResult.MOVED }
                        else -> NavResult.BLOCKED
                    }
                    NavAction.SELECT -> { app.go(Route.HouseholdLibrary); NavResult.ACTIVATED }
                    NavAction.LEFT, NavAction.RIGHT -> NavResult.BLOCKED
                    else -> NavResult.IGNORED
                }
                inList -> when (e.action) {
                    NavAction.UP -> if (sel.index > 0) { sel.index--; NavResult.MOVED } else { inList = false; inLib = hasLib; NavResult.MOVED }
                    NavAction.DOWN -> when {
                        sel.index < games.size - 1 -> { sel.index++; NavResult.MOVED }
                        rest > 0 -> { inRest = true; app.scope.launch { list.animateScrollBy(step) }; NavResult.MOVED }
                        else -> NavResult.BLOCKED
                    }
                    NavAction.RIGHT -> if (side && sideScroll.maxValue > 0) { inSide = true; NavResult.MOVED } else NavResult.BLOCKED
                    NavAction.LEFT -> NavResult.BLOCKED
                    NavAction.SELECT -> { open(sel.index); NavResult.ACTIVATED }
                    else -> NavResult.IGNORED
                }
                else -> when (e.action) {
                    NavAction.LEFT -> if (index > 0) { index--; NavResult.MOVED } else NavResult.BLOCKED
                    NavAction.RIGHT -> if (index < actions.size - 1) { index++; NavResult.MOVED } else NavResult.BLOCKED
                    NavAction.SELECT -> { actions.getOrNull(index)?.run?.invoke(); NavResult.ACTIVATED }
                    NavAction.DOWN -> when {
                        hasLib -> { inLib = true; NavResult.MOVED }
                        games.isNotEmpty() -> { inList = true; NavResult.MOVED }
                        rest > 0 -> { inRest = true; app.scope.launch { list.animateScrollBy(step) }; NavResult.MOVED }
                        else -> NavResult.BLOCKED
                    }
                    else -> NavResult.IGNORED
                }
            }
        }
        // The game chosen stays in view with the one before it above, so moving down folds the top
        // line and the tabs away; back up at the actions, everything is in view again.
        LaunchedEffect(inList, sel.index, games.size) {
            if (!inList) {
                if (!inRest) list.animateScrollToItem(0)
                return@LaunchedEffect
            }
            // In the games, they have the whole height: the first one goes to the top, then the one
            // before the chosen one stays above it.
            list.animateScrollToItem(firstGame + maxOf(0, sel.index - 1))
        }
        // Short screens (the AYN Thor's upper one) get slimmer rows: four or more always in view.
        val rowHeight = when {
            maxHeight < 420.dp -> 48.dp
            compact -> 60.dp
            else -> 68.dp
        }
        Row(Modifier.fillMaxSize().padding(horizontal = Space.gutter), horizontalArrangement = Arrangement.spacedBy(Space.l)) {
            androidx.compose.foundation.lazy.LazyColumn(
                state = list,
                // Below the top line, never under it: what scrolls past softens away at the top edge.
                modifier = Modifier.weight(1f).fillMaxHeight().padding(top = topPadding)
                    .fadingEdges(list, top = Space.xl, bottom = Space.xl),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(top = subTabsRoom() + Space.s, bottom = Size.hintHeight + Space.m),
                verticalArrangement = Arrangement.spacedBy(Space.xs),
            ) {
                item("head") {
                    SyncHeader(
                        hostName = prefs.sync.hostName.ifBlank { "your host" },
                        host = host, words = words, profile = profile, report = report,
                        actions = actions, selected = if (focused && !inList && !inRest && !inSide && !inLib) index else -1,
                        busy = syncing || (status as? SyncStatus.Online)?.working == true,
                        labels = labels, compact = compact,
                        onAction = { i -> index = i; inList = false; inRest = false; inLib = false; actions[i].run() },
                    )
                }
                if (hasLib) item("library") {
                    io.github.matiyaaa.fuse.ui.shell.reach.HouseholdCard(
                        app, selected = focused && inLib, modifier = Modifier.padding(top = Space.m),
                        onClick = { inLib = true; inList = false; inRest = false; app.go(Route.HouseholdLibrary) },
                    )
                }
                item("games") {
                    SectionTitle(
                        "Your games on the host", FuseIcons.Gamepad,
                        trailing = report?.let { r -> "${count(r.games.size, "game")}  ·  ${sizeText(r.savesBytes)}" },
                        modifier = Modifier.padding(start = Space.xs, end = Space.xs, top = Space.m, bottom = Space.xs),
                    )
                }
                when {
                    report == null && loading -> item("wait") { Box(Modifier.fillMaxWidth().padding(Space.xxl), contentAlignment = Alignment.Center) { Spinner(size = 24.dp, color = Fuse.colors.textMuted) } }
                    report == null -> item("away") { Quiet(FuseIcons.CloudOff, "The host isn't answering. Your games show here once it does.") }
                    games.isEmpty() -> item("none") { Quiet(FuseIcons.Gamepad, "Nothing yet. Play something, and its saves and play time show up here.") }
                    else -> items(games.size, key = { games[it].game }) { i ->
                        val g = games[i]
                        GameRow(
                            g, covers.of(g.game, g.name),
                            selected = focused && inList && !inRest && sel.index == i, height = rowHeight,
                            onClick = { sel.index = i; inList = true; open(i) },
                        )
                    }
                }
                if (!side) {
                    item("devices") { DeviceCard(devices, prefs.sync.deviceId, profiles, Modifier.padding(top = Space.l)) }
                    item("play") { if (report != null && report.devicePlay.size > 1) Card("Play time by device", FuseIcons.ChartPie, Modifier.padding(top = Space.s)) { PlayBars(report.devicePlay, names) } }
                    item("lately") { ActivityCard(activity, Modifier.padding(top = Space.s)) }
                }
            }
            if (side) {
                Box(Modifier.width(340.dp).fillMaxHeight().padding(top = topPadding + subTabsRoom() + Space.s, bottom = Size.hintHeight + Space.m)) {
                    Column(Modifier.fillMaxSize().verticalScroll(sideScroll), verticalArrangement = Arrangement.spacedBy(Space.m)) {
                        DeviceCard(devices, prefs.sync.deviceId, profiles)
                        if (report != null && report.devicePlay.size > 1) Card("Play time by device", FuseIcons.ChartPie) { PlayBars(report.devicePlay, names) }
                        ActivityCard(activity)
                    }
                    // Reached with the controller: ringed like anything else in focus.
                    if (focused && inSide) {
                        Spacer(Modifier.matchParentSize().border(Size.focusStroke, Fuse.colors.focus, RoundedCornerShape(Fuse.geometry.panel)))
                    }
                }
            }
        }
    }
}

/** One of the few things to do on the Sync tab. */
private class SyncAction(val label: String, val icon: ImageVector, val run: () -> Unit)

/**
 * The top of the Sync tab, in one slim line: who is playing and on which host (with their totals
 * when there is room), and the things to do. How things stand appears only when something is
 * wrong (the host away, a PIN to type again); all being well needs no saying.
 */
@Composable
private fun SyncHeader(
    hostName: String,
    host: Boolean,
    words: SyncWords,
    profile: ProfileInfo?,
    report: ProfileReport?,
    actions: List<SyncAction>,
    selected: Int,
    busy: Boolean,
    labels: Boolean,
    compact: Boolean,
    onAction: (Int) -> Unit,
) {
    val c = Fuse.colors
    Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (profile != null) ProfileAvatar(profile.avatar, if (compact) 40.dp else 48.dp)
            else SyncMark(if (compact) 40.dp else 48.dp)
            Spacer(Modifier.width(Space.m))
            Column(Modifier.weight(1f)) {
                FText(profile?.name ?: "No one playing", if (compact) Fuse.type.bodyStrong else Fuse.type.title, maxLines = 1)
                val totals = report?.let { r ->
                    listOfNotNull(
                        "${playtimeText(r.playSeconds)} played".takeIf { r.playSeconds > 0 },
                        count(r.games.count { it.slots.isNotEmpty() }, "game") + " with saves",
                    ).joinToString("  ·  ")
                }
                FText(
                    listOfNotNull(if (host) "On $hostName, this computer" else "On $hostName", totals?.takeIf { !compact && it.isNotEmpty() }).joinToString("  ·  "),
                    Fuse.type.caption, color = c.textMuted, maxLines = 1,
                )
            }
            Spacer(Modifier.width(Space.m))
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalAlignment = Alignment.CenterVertically) {
                actions.forEachIndexed { i, a ->
                    ActionPill(a, selected = i == selected, label = labels, primary = i == 0, busy = i == 0 && busy) { onAction(i) }
                }
            }
        }
        // Only when something needs saying: the host away, a device unlinked, a PIN to type again.
        if (words.ok != true) {
            val shape = RoundedCornerShape(Fuse.geometry.control)
            Row(
                Modifier.fillMaxWidth().clip(shape).background(c.warning.copy(alpha = 0.12f)).border(1.dp, c.warning.copy(alpha = 0.35f), shape)
                    .padding(horizontal = Space.m, vertical = Space.s),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FuseIcon(FuseIcons.CloudOff, size = Size.iconS, tint = c.warning)
                Spacer(Modifier.width(Space.s))
                FText(words.title, Fuse.type.label, maxLines = 1)
                if (words.detail.isNotEmpty()) FText("  ·  ${words.detail}", Fuse.type.caption, color = c.textMuted, maxLines = 2, modifier = Modifier.weight(1f, fill = false))
            }
        }
    }
}

/** An action on the Sync tab's top line: a pill with its label on wide screens, a round icon otherwise. */
@Composable
private fun ActionPill(a: SyncAction, selected: Boolean, label: Boolean, primary: Boolean, busy: Boolean, onClick: () -> Unit) {
    val c = Fuse.colors
    val bg by fuselineColor(
        when {
            selected -> c.text
            primary -> c.accent.copy(alpha = 0.18f)
            else -> c.text.copy(alpha = 0.07f)
        },
        Fuse.motion.tween(Durations.FAST), label = "syncAction",
    )
    val fg = when {
        selected -> c.ink
        primary -> c.accent
        else -> c.text
    }
    Row(
        Modifier.height(40.dp).clip(CircleShape).background({ bg })
            .clickable(remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(horizontal = if (label) Space.m else 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (busy) Spinner(size = 18.dp, color = fg) else FuseIcon(a.icon, size = Size.iconS, tint = fg)
        if (label) {
            Spacer(Modifier.width(Space.s))
            FText(a.label, Fuse.type.label, color = fg, maxLines = 1)
        }
    }
}

/**
 * A game on the host: its cover, its name and system, how long it has been played, its saves and
 * where the newest came from, and the room it takes. Lifted and lit when chosen.
 */
@Composable
private fun GameRow(g: GameReport, card: io.github.matiyaaa.fuse.ui.shell.store.GameCard?, selected: Boolean, height: Dp, onClick: () -> Unit) {
    val c = Fuse.colors
    val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
    val newest = g.slots.flatMap { it.versions }.maxByOrNull { it.at }
    val saves = g.slots.filter { it.kind != io.github.matiyaaa.fuse.sync.SaveKind.STATE }.sumOf { it.versions.size }
    val states = g.slots.filter { it.kind == io.github.matiyaaa.fuse.sync.SaveKind.STATE }.sumOf { it.versions.size }
    val bytes = g.slots.sumOf { it.bytes }
    val title = card?.title ?: g.name
    val shape = RoundedCornerShape(Fuse.geometry.control)
    val bg by fuselineColor(if (selected) c.surfaceRaised else c.surface.copy(alpha = 0.55f), Fuse.motion.tween(Durations.FAST), label = "syncGame")
    Row(
        Modifier.fillMaxWidth().height(height).testTag("sync.game").clip(shape).background({ bg })
            .then(if (selected) Modifier.border(Size.focusStroke, c.focus, shape) else Modifier.border(1.dp, c.hairline, shape))
            .clickable(remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(horizontal = if (height < 56.dp) Space.s else Space.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SyncCover(g.name, card, height - if (height < 56.dp) Space.s else Space.m)
        Spacer(Modifier.width(Space.m))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FText(title, Fuse.type.bodyStrong, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
                val system = (card?.platformShort ?: g.platform.uppercase()).takeIf { it.isNotEmpty() }
                if (system != null) {
                    Spacer(Modifier.width(Space.s))
                    FText(system, Fuse.type.caption, color = c.textMuted, maxLines = 1, modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(c.text.copy(alpha = 0.07f)).padding(horizontal = 6.dp, vertical = 1.dp))
                }
            }
            FText(
                listOfNotNull(
                    playtimeText(g.playSeconds).takeIf { g.playSeconds > 0 },
                    listOfNotNull(
                        "$saves ${if (saves == 1) "save" else "saves"}".takeIf { saves > 0 },
                        "$states ${if (states == 1) "state" else "states"}".takeIf { states > 0 },
                    ).joinToString(", ").ifEmpty { null },
                    newest?.let { "newest from ${it.device}, ${TimeWords.relative(it.at, now, localOffsetMillis(now))}" },
                ).joinToString("  ·  ").ifEmpty { "No saves kept yet" },
                Fuse.type.caption, color = c.textMuted, maxLines = 1,
            )
        }
        Spacer(Modifier.width(Space.m))
        if (bytes > 0) FText(sizeText(bytes), Fuse.type.label, color = c.textMuted, maxLines = 1)
        else FuseIcon(FuseIcons.ChevronRight, size = Size.iconS, tint = c.textFaint)
    }
}

/** Each game's cover from this library, where it has the game: by its id across devices, or its name. */
internal class SyncCovers(private val map: Map<String, io.github.matiyaaa.fuse.ui.shell.store.GameCard>) {
    fun of(game: String, name: String) = map[game] ?: map["name:" + name.lowercase()]
}

@Composable
internal fun rememberSyncCovers(app: AppState): SyncCovers {
    val covers by androidx.compose.runtime.produceState(SyncCovers(emptyMap()), app.store) {
        // Built off the interface's thread: a big library is thousands of keys.
        app.store.library.games(io.github.matiyaaa.fuse.ui.shell.store.GameQuery()).map { cards ->
            SyncCovers(buildMap {
                for (card in cards) {
                    put(io.github.matiyaaa.fuse.sync.GameKey.of(card.platformId.value, null, null, card.title).id, card)
                    put("name:" + card.title.lowercase(), card)
                }
            })
        }.flowOn(kotlinx.coroutines.Dispatchers.Default).collect { value = it }
    }
    return covers
}

/** A game's cover at [size], square, or art made from its name when the library has none. */
@Composable
internal fun SyncCover(name: String, card: io.github.matiyaaa.fuse.ui.shell.store.GameCard?, size: Dp, corner: Dp = Fuse.geometry.control) {
    val art = Modifier.size(size).clip(RoundedCornerShape(corner))
    val generated: @Composable () -> Unit = {
        io.github.matiyaaa.fuse.ui.designsystem.media.GeneratedArt(name, androidx.compose.ui.graphics.Color(card?.accent ?: 0xFF8A93A6), art)
    }
    val model = card?.art?.square ?: card?.art?.boxart ?: card?.art?.icon
    if (model != null) io.github.matiyaaa.fuse.ui.designsystem.media.Artwork(model, art, fallback = generated) else generated()
}

/** One figure under the hero's title. */
internal class HeroFact(val icon: ImageVector, val value: String, val label: String)

/**
 * The top of an addon's tab (Sync, Syncthing): its mark, a name with a quiet tag, how things stand,
 * an optional slot at the end, and a row of small figures (left out on short screens, so the list
 * below keeps its room). A soft wash of [tint] from the left ties it to the addon's colour.
 */
@Composable
internal fun AddonHero(
    mark: @Composable (Dp) -> Unit,
    title: String,
    tag: String?,
    tagIcon: ImageVector,
    tint: androidx.compose.ui.graphics.Color,
    ok: Boolean?,
    status: String,
    detail: String,
    facts: List<HeroFact>,
    compact: Boolean,
    modifier: Modifier = Modifier,
    end: (@Composable () -> Unit)? = null,
) {
    val c = Fuse.colors
    val shape = RoundedCornerShape(Fuse.geometry.panel)
    Column(
        modifier.fillMaxWidth().clip(shape)
            .background(Brush.horizontalGradient(listOf(tint.copy(alpha = 0.16f), c.surface.copy(alpha = 0.78f), c.surface.copy(alpha = 0.78f))))
            .border(1.dp, c.hairline, shape)
            .padding(horizontal = Space.l, vertical = if (compact) Space.s else Space.m),
        verticalArrangement = Arrangement.spacedBy(Space.s),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            mark(if (compact) 44.dp else 56.dp)
            Spacer(Modifier.width(Space.l))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FText(title, if (compact) Fuse.type.title else Fuse.type.display, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
                    if (tag != null) {
                        Spacer(Modifier.width(Space.s))
                        Pill(tag, tagIcon)
                    }
                }
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // All being well needs no light: the dot shows only when something isn't.
                    if (ok != true) {
                        StatusDot(ok)
                        Spacer(Modifier.width(Space.s))
                    }
                    FText(status, Fuse.type.bodyStrong, maxLines = 1)
                    if (detail.isNotEmpty()) FText("  ·  $detail", Fuse.type.body, color = c.textMuted, maxLines = 1)
                }
            }
            if (end != null) {
                Spacer(Modifier.width(Space.l))
                end()
            }
        }
        if (facts.isNotEmpty() && !compact) {
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalAlignment = Alignment.CenterVertically) {
                for (f in facts) Fact(f.icon, f.value, f.label)
            }
        }
    }
}

/** A small figure in the hero: an icon, a value, and what it counts. */
@Composable
private fun Fact(icon: ImageVector, value: String, label: String) {
    val c = Fuse.colors
    Row(
        Modifier.clip(RoundedCornerShape(10.dp)).background(c.text.copy(alpha = 0.05f)).padding(horizontal = Space.m, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FuseIcon(icon, size = 16.dp, tint = c.textMuted)
        Spacer(Modifier.width(Space.s))
        FText(value, Fuse.type.label, maxLines = 1)
        Spacer(Modifier.width(4.dp))
        FText(label, Fuse.type.caption, color = c.textMuted, maxLines = 1)
    }
}

/** A quiet tag beside a title. */
@Composable
internal fun Pill(text: String, icon: ImageVector) {
    val c = Fuse.colors
    Row(
        Modifier.clip(CircleShape).background(c.text.copy(alpha = 0.07f)).padding(horizontal = Space.s + 2.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FuseIcon(icon, size = 14.dp, tint = c.textMuted)
        Spacer(Modifier.width(4.dp))
        FText(text, Fuse.type.caption, color = c.textMuted, maxLines = 1)
    }
}

/** A list's heading: icon, title, and a count on the right. */
@Composable
internal fun SectionTitle(title: String, icon: ImageVector, trailing: String? = null, modifier: Modifier = Modifier, tint: androidx.compose.ui.graphics.Color = Fuse.colors.accent) {
    val c = Fuse.colors
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(28.dp).clip(RoundedCornerShape(8.dp)).background(tint.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
            FuseIcon(icon, size = 16.dp, tint = tint)
        }
        Spacer(Modifier.width(Space.m))
        FText(title, Fuse.type.bodyStrong, maxLines = 1, modifier = Modifier.weight(1f))
        if (trailing != null) FText(trailing, Fuse.type.caption, color = c.textMuted, maxLines = 1)
    }
}

/** An empty or waiting list: a soft icon and one line. */
@Composable
internal fun Quiet(icon: ImageVector, text: String) {
    val c = Fuse.colors
    Column(Modifier.fillMaxWidth().padding(Space.xl), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(52.dp).clip(CircleShape).background(c.text.copy(alpha = 0.06f)), contentAlignment = Alignment.Center) {
            FuseIcon(icon, size = Size.iconM, tint = c.textMuted)
        }
        Spacer(Modifier.height(Space.m))
        FText(text, Fuse.type.body, color = c.textMuted, maxLines = 3, align = androidx.compose.ui.text.style.TextAlign.Center)
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
private fun DeviceCard(devices: List<DeviceInfo>, self: String, profiles: List<ProfileInfo>, modifier: Modifier = Modifier) {
    val c = Fuse.colors
    val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
    val offset = localOffsetMillis(now)
    val linked = devices.filterNot { it.revoked }
    Card("Devices", FuseIcons.MonitorSmartphone, modifier) {
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
private fun ActivityCard(activity: List<SyncActivity>, modifier: Modifier = Modifier) {
    val c = Fuse.colors
    val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
    val offset = localOffsetMillis(now)
    Card("Lately", FuseIcons.History, modifier) {
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
