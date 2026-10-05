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
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
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
        // Any device already in can bring in another: a code here, or saying yes when it asks.
        add("Add a Device" to { app.pairing = true })
        add("Settings" to { app.go(Route.SyncSettings) })
    }
    // Each game's cover from this library, where it has the game (by its id across devices, or its name).
    val covers by androidx.compose.runtime.produceState(emptyMap<String, io.github.matiyaaa.fuse.ui.shell.store.GameCard>(), app.store) {
        // Built off the interface's thread: a big library is thousands of keys.
        app.store.library.games(io.github.matiyaaa.fuse.ui.shell.store.GameQuery()).map { cards ->
            buildMap {
                for (card in cards) {
                    put(io.github.matiyaaa.fuse.sync.GameKey.of(card.platformId.value, null, null, card.title).id, card)
                    put("name:" + card.title.lowercase(), card)
                }
            }
        }.flowOn(kotlinx.coroutines.Dispatchers.Default).collect { value = it }
    }
    val rows = report?.games.orEmpty().map { g -> gameRow(app, g, covers[g.game] ?: covers["name:" + g.name.lowercase()]) }
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
                NavAction.LEFT -> if (index > 0) { index--; NavResult.MOVED } else NavResult.BLOCKED
                NavAction.RIGHT -> if (index < actions.size - 1) { index++; NavResult.MOVED } else NavResult.BLOCKED
                NavAction.SELECT -> { actions.getOrNull(index)?.second?.invoke(); NavResult.ACTIVATED }
                NavAction.DOWN -> if (rows.isNotEmpty()) { inList = true; NavResult.MOVED } else NavResult.BLOCKED
                else -> NavResult.IGNORED
            }
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        // Devices and what happened lately sit beside the games wherever there is room for both.
        val side = maxWidth >= 820.dp
        val roomy = maxWidth >= 1200.dp
        val compact = maxHeight < 560.dp
        Column(
            Modifier.fillMaxSize().padding(horizontal = Space.gutter)
                .padding(top = topPadding + subTabsRoom() + Space.m, bottom = Size.hintHeight + Space.s),
            verticalArrangement = Arrangement.spacedBy(if (compact) Space.s else Space.m),
        ) {
            HostHero(
                hostName = prefs.sync.hostName.ifBlank { words.title },
                host = host,
                words = words,
                profile = profile,
                report = report,
                devices = devices,
                compact = compact,
                showDevices = !side,
                modifier = Modifier.reveal(reveal, 0),
            )
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
            Row(Modifier.weight(1f).reveal(reveal, 2), horizontalArrangement = Arrangement.spacedBy(Space.l)) {
                Panel(Modifier.weight(1f).fillMaxHeight()) {
                    Column {
                        SectionTitle(
                            "Your games on the host", FuseIcons.Gamepad,
                            trailing = report?.let { r -> "${r.games.size} games  ·  ${sizeText(r.savesBytes)}" },
                            modifier = Modifier.padding(start = Space.l, end = Space.l, top = Space.m, bottom = Space.xs),
                        )
                        when {
                            report == null && loading -> Box(Modifier.fillMaxWidth().padding(Space.xxl), contentAlignment = Alignment.Center) { Spinner(size = 24.dp, color = Fuse.colors.textMuted) }
                            report == null -> Quiet(FuseIcons.CloudOff, "The host isn't answering. Your games show here once it does.")
                            rows.isEmpty() -> Quiet(FuseIcons.Gamepad, "Nothing yet. Play something, and its saves and play time show up here.")
                            else -> MenuList(rows, sel, modifier = Modifier.padding(Space.s), showSelection = focused && inList)
                        }
                    }
                }
                if (side) {
                    Column(Modifier.width(if (roomy) 380.dp else if (compact) 290.dp else 320.dp).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Space.m)) {
                        DeviceCard(devices, prefs.sync.deviceId, profiles)
                        if (report != null && report.devicePlay.size > 1) {
                            Card("Play time by device", FuseIcons.ChartPie) { PlayBars(report.devicePlay, names) }
                        }
                        ActivityCard(activity)
                    }
                }
            }
        }
    }
}

/**
 * The top of the tab: the host and how things stand, who is playing here, and their totals. With no
 * room for the devices column, the devices count shows among the totals instead.
 */
@Composable
private fun HostHero(
    hostName: String,
    host: Boolean,
    words: SyncWords,
    profile: ProfileInfo?,
    report: ProfileReport?,
    devices: List<DeviceInfo>,
    compact: Boolean,
    showDevices: Boolean,
    modifier: Modifier,
) {
    val c = Fuse.colors
    val tint = if (words.ok == false) c.warning else c.accent
    val facts = buildList {
        if (report != null) {
            val versions = report.games.sumOf { g -> g.slots.sumOf { it.versions.size } }
            add(HeroFact(FuseIcons.Clock, if (report.playSeconds > 0) playtimeText(report.playSeconds) else "No play yet", "played"))
            add(HeroFact(FuseIcons.Gamepad, "${report.games.count { it.playSeconds > 0 }}", "games"))
            add(HeroFact(FuseIcons.Save, "${report.games.count { it.slots.isNotEmpty() }}", "with saves"))
            add(HeroFact(FuseIcons.History, "$versions", "versions kept"))
            if (showDevices) {
                val linked = devices.filterNot { it.revoked }
                val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
                add(HeroFact(FuseIcons.MonitorSmartphone, "${linked.count { now - it.lastSeen < ONLINE_MS }}/${linked.size}", "devices online"))
            }
        }
    }
    AddonHero(
        mark = { SyncMark(it, tint = tint) },
        title = hostName,
        tag = if (host) "This computer" else "Your host",
        tagIcon = if (host) FuseIcons.Server else FuseIcons.Cloud,
        tint = tint,
        ok = words.ok, status = words.title, detail = words.detail,
        facts = facts,
        compact = compact,
        modifier = modifier,
        end = if (profile == null) null else ({
            Row(
                Modifier.clip(CircleShape).background(c.text.copy(alpha = 0.06f)).border(1.dp, c.hairline, CircleShape)
                    .padding(start = 6.dp, end = Space.l, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ProfileAvatar(profile.avatar, if (compact) 30.dp else 38.dp)
                Spacer(Modifier.width(Space.s))
                Column {
                    FText("Playing here", Fuse.type.caption, color = c.textMuted, maxLines = 1)
                    FText(profile.name, Fuse.type.bodyStrong, maxLines = 1)
                }
            }
        }),
    )
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
                    StatusDot(ok)
                    Spacer(Modifier.width(Space.s))
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

/** A game on the host, as its row reads: its cover, play time, its saves, the newest and who saved it, and the space it takes. */
private fun gameRow(app: AppState, g: GameReport, card: io.github.matiyaaa.fuse.ui.shell.store.GameCard?): MenuAction {
    val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
    val newest = g.slots.flatMap { it.versions }.maxByOrNull { it.at }
    val saves = g.slots.filter { it.kind != io.github.matiyaaa.fuse.sync.SaveKind.STATE }.sumOf { it.versions.size }
    val states = g.slots.filter { it.kind == io.github.matiyaaa.fuse.sync.SaveKind.STATE }.sumOf { it.versions.size }
    val detail = listOfNotNull(
        (card?.platformShort ?: g.platform.uppercase()).takeIf { it.isNotEmpty() },
        playtimeText(g.playSeconds).takeIf { g.playSeconds > 0 },
        listOfNotNull(
            "$saves ${if (saves == 1) "save" else "saves"}".takeIf { saves > 0 },
            "$states ${if (states == 1) "state" else "states"}".takeIf { states > 0 },
        ).joinToString(", ").ifEmpty { null },
        newest?.let { "from ${it.device}, ${TimeWords.relative(it.at, now, localOffsetMillis(now))}" },
    ).joinToString("  ·  ")
    val bytes = g.slots.sumOf { it.bytes }
    return MenuAction(
        g.game, card?.title ?: g.name,
        detail = detail,
        art = io.github.matiyaaa.fuse.ui.designsystem.components.MenuArt(
            model = card?.art?.square ?: card?.art?.boxart ?: card?.art?.icon,
            square = true, fallbackTitle = g.name, accent = card?.accent ?: 0xFF8A93A6, wide = false,
        ),
        trailing = if (bytes > 0) Trailing.Value(sizeText(bytes)) else Trailing.Chevron,
        onSelect = { app.go(Route.SyncGame(g.game, g.name)) },
    )
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
