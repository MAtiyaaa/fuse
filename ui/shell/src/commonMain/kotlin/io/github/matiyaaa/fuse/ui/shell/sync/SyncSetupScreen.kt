package io.github.matiyaaa.fuse.ui.shell.sync

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.matiyaaa.fuse.model.NavAction
import io.github.matiyaaa.fuse.sync.NearbyHost
import io.github.matiyaaa.fuse.sync.ServiceState
import io.github.matiyaaa.fuse.ui.designsystem.components.ButtonKind
import io.github.matiyaaa.fuse.ui.designsystem.components.FText
import io.github.matiyaaa.fuse.ui.designsystem.components.FuseButton
import io.github.matiyaaa.fuse.ui.designsystem.components.Hint
import io.github.matiyaaa.fuse.ui.designsystem.components.Panel
import io.github.matiyaaa.fuse.ui.designsystem.components.Spinner
import io.github.matiyaaa.fuse.ui.designsystem.components.ToastKind
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcon
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.designsystem.icons.HintButton
import io.github.matiyaaa.fuse.ui.designsystem.input.InputLayer
import io.github.matiyaaa.fuse.ui.designsystem.input.LocalInputRouter
import io.github.matiyaaa.fuse.ui.designsystem.input.NavResult
import io.github.matiyaaa.fuse.ui.designsystem.input.TextInput
import io.github.matiyaaa.fuse.ui.designsystem.sound.SoundCue
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.Size
import io.github.matiyaaa.fuse.ui.designsystem.theme.Space
import io.github.matiyaaa.fuse.ui.fuseline.Durations
import io.github.matiyaaa.fuse.ui.fuseline.Swap
import io.github.matiyaaa.fuse.ui.fuseline.fuselineColor
import io.github.matiyaaa.fuse.ui.fuseline.fuselineFloat
import io.github.matiyaaa.fuse.ui.shell.app.AppState
import io.github.matiyaaa.fuse.ui.shell.app.FocusZone
import io.github.matiyaaa.fuse.ui.shell.app.TextInputSpec
import kotlinx.coroutines.launch

/** Where setting up stands. */
private sealed interface SetupStep {
    data object HostIntro : SetupStep
    data object HostReady : SetupStep
    data object Find : SetupStep
    /** How to join the host: ask it to let this device in, or type a code. */
    data class Way(val host: NearbyHost) : SetupStep
    data class Code(val host: NearbyHost) : SetupStep
    /** Asked: waiting for someone to let this device in, with the number to check. */
    data class Asking(val host: NearbyHost, val waiting: io.github.matiyaaa.fuse.sync.JoinWaiting) : SetupStep
    data class Connected(val hostName: String) : SetupStep
}

/** One thing the D-pad can choose on a step, drawn as a button or a row. */
private class SetupAction(val id: String, val onSelect: () -> Unit)

/** The chosen place on the step showing, how to move it, and Back: shared by every step. */
private class StepKeys(val index: () -> Int, val setIndex: (Int) -> Unit, val back: () -> Unit) {
    fun at(active: Boolean) = ActiveKeys(this, active)
}

/** [StepKeys] for one step, which answers only while it is the one showing (not while it leaves). */
private class ActiveKeys(val keys: StepKeys, val active: Boolean) {
    val index: Int get() = keys.index()
}

/** The D-pad on a step: Up and Down (Left and Right too) through its [actions], A to choose, B back. */
@Composable
private fun StepInput(app: AppState, keys: ActiveKeys, actions: List<SetupAction>) {
    InputLayer(enabled = keys.active && app.focusZone == FocusZone.CONTENT && !app.overlayOpen) { e ->
        val index = keys.index
        when (e.action) {
            NavAction.UP, NavAction.LEFT -> if (index > 0) { keys.keys.setIndex(index - 1); NavResult.MOVED } else NavResult.BLOCKED
            NavAction.DOWN, NavAction.RIGHT -> if (index < actions.size - 1) { keys.keys.setIndex(index + 1); NavResult.MOVED } else NavResult.BLOCKED
            NavAction.SELECT -> { actions.getOrNull(index)?.onSelect?.invoke(); NavResult.ACTIVATED }
            NavAction.BACK -> { keys.keys.back(); NavResult.CONSUMED }
            else -> NavResult.IGNORED
        }
    }
}

/**
 * Setting Fuse Sync up, one clear step at a time. As the host: what it means, a name, whether it
 * keeps running when Fuse is closed, then "Fuse Sync is ready" with the code other devices type.
 * Connecting: the hosts found on this network ("Fuse Sync found"), or an address typed in, then the
 * host's code, then who's playing.
 */
@Composable
internal fun SyncSetupScreen(app: AppState, host: Boolean) {
    val svc = app.store.sync.service ?: return
    var step by remember(host) { mutableStateOf<SetupStep>(if (host) SetupStep.HostIntro else SetupStep.Find) }
    var index by remember { mutableIntStateOf(0) }
    var working by remember { mutableStateOf(false) }
    // Why the last try to connect didn't, shown under the code rather than over the button.
    var connectError by remember { mutableStateOf<String?>(null) }
    // The host's address from outside, when given: used for asking and for a code alike.
    var remote by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        app.hero = null
        app.hints = listOf(Hint(HintButton.CONFIRM, "Choose"), Hint(HintButton.BACK, "Back"))
    }
    LaunchedEffect(step) { index = 0 }
    val keys = StepKeys(index = { index }, setIndex = { index = it }, back = {
        when (val s = step) {
            is SetupStep.Code -> step = SetupStep.Way(s.host)
            is SetupStep.Asking -> { svc.cancelJoin(); step = SetupStep.Way(s.host) }
            is SetupStep.Way -> { connectError = null; step = SetupStep.Find }
            else -> app.back()
        }
    })

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val compact = maxHeight < 600.dp
        val wide = maxWidth >= 980.dp
        Row(
            Modifier.fillMaxSize().padding(horizontal = Space.gutter).padding(top = Size.hudHeight + Space.l, bottom = Size.hintHeight + Space.s),
            horizontalArrangement = Arrangement.spacedBy(Space.xl),
        ) {
            if (wide) StepRail(host, step, Modifier.width(300.dp))
            Panel(Modifier.weight(1f).widthIn(max = 760.dp)) {
                Swap(step, label = "setup") { s ->
                    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(if (compact) Space.l else Space.xxl)) {
                        when (s) {
                            SetupStep.HostIntro -> HostIntro(app, compact, keys.at(s == step), working) { name, keep, folder ->
                                working = true
                                app.scope.launch {
                                    app.store.sync.configure { it.copy(hostDataDir = folder) }
                                    svc.hostHere(name, installService = keep)
                                        .onSuccess { step = SetupStep.HostReady; app.platform.sounds.play(SoundCue.SELECT) }
                                        .onFailure { app.toasts.show(it.message ?: "Couldn't start the host", ToastKind.ERROR) }
                                    working = false
                                }
                            }
                            SetupStep.HostReady -> HostReady(app, compact, keys.at(s == step))
                            SetupStep.Find -> Find(app, compact, keys.at(s == step)) { connectError = null; step = SetupStep.Way(it) }
                            is SetupStep.Way -> JoinWay(app, s.host, compact, keys.at(s == step), working, connectError, remote, onRemote = { remote = it }, onCode = { connectError = null; step = SetupStep.Code(s.host) }) {
                                working = true
                                connectError = null
                                app.scope.launch {
                                    svc.askToJoin(s.host.address, remote)
                                        .onSuccess { w -> step = SetupStep.Asking(s.host, w); app.platform.sounds.play(SoundCue.SELECT) }
                                        .onFailure { connectError = it.message ?: "The host didn't answer"; app.platform.sounds.play(SoundCue.ERROR) }
                                    working = false
                                }
                            }
                            is SetupStep.Asking -> AskingToJoin(
                                app, s.host, s.waiting, compact, keys.at(s == step),
                                onJoined = { name -> step = SetupStep.Connected(name); app.platform.sounds.play(SoundCue.SELECT) },
                                onFailed = { why -> connectError = why; step = SetupStep.Way(s.host); app.platform.sounds.play(SoundCue.ERROR) },
                                onCode = { svc.cancelJoin(); connectError = null; step = SetupStep.Code(s.host) },
                                onCancel = { svc.cancelJoin(); step = SetupStep.Way(s.host) },
                            )
                            is SetupStep.Code -> CodeEntry(app, s.host, compact, keys.at(s == step), working, connectError, remote) { code, remote ->
                                working = true
                                connectError = null
                                app.scope.launch {
                                    svc.connect(s.host.address, code, remote)
                                        .onSuccess { name -> step = SetupStep.Connected(name); app.platform.sounds.play(SoundCue.SELECT) }
                                        .onFailure { connectError = it.message ?: "Couldn't connect"; app.platform.sounds.play(SoundCue.ERROR) }
                                    working = false
                                }
                            }
                            is SetupStep.Connected -> Connected(app, s.hostName, compact, keys.at(s == step))
                        }
                    }
                }
            }
        }
    }
}

/** On wide screens: the steps, with the one you're on lit, under Fuse Sync's mark. */
@Composable
private fun StepRail(host: Boolean, step: SetupStep, modifier: Modifier) {
    val c = Fuse.colors
    val steps = if (host) listOf("What it means", "Name it", "Ready") else listOf("Find your host", "Join it", "Who's playing")
    val at = when (step) {
        SetupStep.HostIntro -> 1
        SetupStep.HostReady -> 2
        SetupStep.Find -> 0
        is SetupStep.Way, is SetupStep.Asking, is SetupStep.Code -> 1
        is SetupStep.Connected -> 2
    }
    Column(modifier.padding(top = Space.l), verticalArrangement = Arrangement.spacedBy(Space.l)) {
        SyncMark(72.dp)
        Column(verticalArrangement = Arrangement.spacedBy(Space.xxs)) {
            FText(SYNC_BYLINE, Fuse.type.title, maxLines = 1)
            FText(if (host) "Setting up the host" else "Connecting this device", Fuse.type.body, color = c.textMuted, maxLines = 1)
        }
        Spacer(Modifier.height(Space.s))
        steps.forEachIndexed { i, label ->
            val done = i < at
            val now = i == at
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(28.dp).clip(RoundedCornerShape(50))
                        .background(if (done || now) c.accent else c.text.copy(alpha = 0.08f)),
                    contentAlignment = Alignment.Center,
                ) {
                    if (done) FuseIcon(FuseIcons.Check, size = Size.iconS, tint = c.onAccent)
                    else FText("${i + 1}", Fuse.type.label, color = if (now) c.onAccent else c.textMuted, maxLines = 1)
                }
                Spacer(Modifier.width(Space.m))
                FText(label, if (now) Fuse.type.bodyStrong else Fuse.type.body, color = if (now) c.text else c.textMuted, maxLines = 1)
            }
        }
    }
}

@Composable
private fun Heading(title: String, body: String, compact: Boolean, icon: ImageVector? = null) {
    val c = Fuse.colors
    if (icon != null) {
        Box(Modifier.size(if (compact) 44.dp else 56.dp).clip(RoundedCornerShape(16.dp)).background(c.accent.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
            FuseIcon(icon, size = Size.iconL, tint = c.accent)
        }
        Spacer(Modifier.height(Space.l))
    }
    FText(title, if (compact) Fuse.type.title else Fuse.type.display, maxLines = 2, modifier = Modifier.semantics { heading() })
    Spacer(Modifier.height(Space.s))
    FText(body, Fuse.type.body, color = c.textMuted, maxLines = 5)
}

@Composable
private fun Point(icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.Top, modifier = Modifier.padding(vertical = Space.xs)) {
        Box(Modifier.size(32.dp).clip(RoundedCornerShape(10.dp)).background(Fuse.colors.text.copy(alpha = 0.06f)), contentAlignment = Alignment.Center) {
            FuseIcon(icon, size = Size.iconS, tint = Fuse.colors.text)
        }
        Spacer(Modifier.width(Space.m))
        FText(text, Fuse.type.body, maxLines = 3, modifier = Modifier.padding(top = 5.dp))
    }
}

/** A row the D-pad lands on: a label and its value, with an icon; inverted when chosen. */
@Composable
private fun ChoiceRow(label: String, value: String, icon: ImageVector, selected: Boolean, detail: String? = null, trailing: (@Composable () -> Unit)? = null, onClick: () -> Unit) {
    val c = Fuse.colors
    val bg by fuselineColor(if (selected) c.text else c.text.copy(alpha = 0.05f), Fuse.motion.tween(Durations.FAST), label = "setupRow")
    val fg = if (selected) c.ink else c.text
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(Fuse.geometry.control)).background(bg)
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(horizontal = Space.l, vertical = Space.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FuseIcon(icon, size = Size.iconM, tint = fg)
        Spacer(Modifier.width(Space.m))
        Column(Modifier.weight(1f)) {
            FText(label, Fuse.type.caption, color = if (selected) fg.copy(alpha = 0.7f) else c.textMuted, maxLines = 1)
            FText(value, Fuse.type.bodyStrong, color = fg, maxLines = 1)
            if (detail != null) FText(detail, Fuse.type.caption, color = if (selected) fg.copy(alpha = 0.7f) else c.textMuted, maxLines = 3)
        }
        trailing?.invoke()
    }
}

@Composable
private fun Switch(on: Boolean, selected: Boolean) {
    val c = Fuse.colors
    val t by fuselineFloat(if (on) 1f else 0f, Fuse.motion.focusSpring(), label = "sw")
    val track = if (on) c.accent else if (selected) c.ink.copy(alpha = 0.25f) else c.text.copy(alpha = 0.18f)
    Box(Modifier.width(44.dp).height(26.dp).clip(RoundedCornerShape(50)).background(track)) {
        Box(
            Modifier.padding(3.dp).size(20.dp).graphicsLayer { translationX = t * 18.dp.toPx() }
                .clip(RoundedCornerShape(50)).background(if (on) c.onAccent else if (selected) c.ink else c.text),
        )
    }
}

@Composable
private fun HostIntro(app: AppState, compact: Boolean, keys: ActiveKeys, working: Boolean, onGo: (String, Boolean, String) -> Unit) {
    val actions = mutableListOf<SetupAction>()
    val index = keys.index
    val svc = app.store.sync.service ?: return
    var name by remember { mutableStateOf(app.store.prefs.value.sync.deviceName.ifBlank { svc.defaultName }) }
    val lifetime: ServiceState = remember { svc.lifetimeState() }
    var keep by remember { mutableStateOf(lifetime.supported) }
    // Where everyone's saves live: Fuse's own folder, or one picked here (a big drive).
    var folder by remember { mutableStateOf(app.store.prefs.value.sync.hostDataDir) }
    fun askName() {
        app.textInput = TextInputSpec("The host's name", name, "Gaming PC") { v -> v.trim().take(32).takeIf { it.isNotEmpty() }?.let { name = it } }
    }
    fun askFolder() {
        app.scope.launch { app.platform.storage.pickFolder("Where should the host keep everyone's saves?")?.let { folder = it } }
    }
    actions += SetupAction("name") { askName() }
    if (lifetime.supported) actions += SetupAction("keep") { keep = !keep }
    actions += SetupAction("folder") { askFolder() }
    actions += SetupAction("go") { if (!working) onGo(name, keep && lifetime.supported, folder) }

    Heading(
        "Make this computer the host",
        "It keeps everyone's saves, play time and settings, and every device syncs with it: on this network by itself, and from outside when you give it an address.",
        compact, FuseIcons.Server,
    )
    Spacer(Modifier.height(Space.l))
    Point(FuseIcons.ShieldCheck, "Everything stays on this computer. Nothing goes to anyone else.")
    Point(FuseIcons.WifiOff, "Devices keep playing while it's off, and catch up when it's back.")
    Point(FuseIcons.History, "Every save keeps its earlier versions, so nothing is ever lost.")
    Spacer(Modifier.height(Space.l))
    ChoiceRow("Host name", name, FuseIcons.Pencil, selected = index == 0, detail = "How your other devices see it") { askName() }
    if (lifetime.supported) {
        Spacer(Modifier.height(Space.s))
        ChoiceRow(
            "Keep running when Fuse is closed", if (keep) "On" else "Off", FuseIcons.ServerCog, selected = index == 1,
            detail = listOfNotNull(lifetime.description, lifetime.caveat).joinToString(". "),
            trailing = { Switch(keep, index == 1) },
        ) { keep = !keep }
    }
    Spacer(Modifier.height(Space.s))
    ChoiceRow(
        "Where saves are kept", folder.ifBlank { "Fuse's own folder" }.substringAfterLast('/').ifBlank { folder }, FuseIcons.HardDrive,
        selected = index == actions.size - 2,
        detail = if (folder.isBlank()) "Every save and its earlier versions. Choose a folder on a bigger drive if you like" else folder,
    ) { askFolder() }
    Spacer(Modifier.height(Space.xl))
    FuseButton(
        "Make This the Host", selected = index == actions.size - 1, onClick = { if (!working) onGo(name, keep && lifetime.supported, folder) },
        icon = FuseIcons.Server, kind = ButtonKind.PRIMARY, loading = working,
    )
    StepInput(app, keys, actions)
}

/** The big moment: a ring that draws itself round a check, the host's name, and the code to add devices. */
@Composable
private fun HostReady(app: AppState, compact: Boolean, keys: ActiveKeys) {
    val actions = mutableListOf<SetupAction>()
    val index = keys.index
    val svc = app.store.sync.service ?: return
    val hostView by androidx.compose.runtime.produceState(svc.host.value, svc) { svc.host.collect { value = it } }
    val profiles by androidx.compose.runtime.produceState(svc.profiles.value, svc) { svc.profiles.collect { value = it } }
    val c = Fuse.colors
    actions += SetupAction("done") { app.back() }
    actions += SetupAction("profile") { app.back(); app.whoAreYou = WhoMode.ADD }
    SuccessMark(if (compact) 64.dp else 88.dp)
    Spacer(Modifier.height(Space.l))
    FText("Fuse Sync is ready", if (compact) Fuse.type.title else Fuse.type.display, maxLines = 1, modifier = Modifier.semantics { heading() })
    Spacer(Modifier.height(Space.xs))
    val admin = profiles.firstOrNull { it.hostOnly }
    FText(
        "${hostView?.name ?: "This computer"} is your host" + if (admin != null) ", and plays as ${admin.name}: a profile only this computer sees, so nobody needs one here." else ".",
        Fuse.type.body, color = c.textMuted, maxLines = 3,
    )
    Spacer(Modifier.height(Space.xs))
    FText(
        "On each of your other devices, choose Connect to a Host and ask to join: a card asks here to let it in. Or type this code:",
        Fuse.type.body, color = c.textMuted, maxLines = 3,
    )
    Spacer(Modifier.height(Space.l))
    PairingCard(app, hostView?.pairingCode, hostView?.addresses.orEmpty(), compact)
    Spacer(Modifier.height(Space.xl))
    Row(horizontalArrangement = Arrangement.spacedBy(Space.m)) {
        FuseButton("Done", selected = index == 0, onClick = { app.back() }, icon = FuseIcons.Check, kind = ButtonKind.PRIMARY)
        FuseButton("Make a Profile for Me", selected = index == 1, onClick = { app.back(); app.whoAreYou = WhoMode.ADD }, icon = FuseIcons.UserPlus)
    }
    StepInput(app, keys, actions)
}

/** A check in a ring that draws itself in once, then rests. */
@Composable
internal fun SuccessMark(size: Dp) {
    val c = Fuse.colors
    var drawn by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { drawn = true }
    val t by fuselineFloat(if (drawn) 1f else 0f, Fuse.motion.tween(Durations.SLOW), label = "success")
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val stroke = size.toPx() * 0.06f
            drawCircle(c.success.copy(alpha = 0.12f), this.size.minDimension / 2f)
            drawArc(c.success, -90f, 360f * t, useCenter = false, style = Stroke(stroke, cap = StrokeCap.Round),
                topLeft = androidx.compose.ui.geometry.Offset(stroke / 2, stroke / 2),
                size = androidx.compose.ui.geometry.Size(this.size.width - stroke, this.size.height - stroke))
        }
        FuseIcon(FuseIcons.Check, size = size * 0.42f, tint = c.success, modifier = Modifier.graphicsLayer { alpha = t; scaleX = 0.6f + 0.4f * t; scaleY = 0.6f + 0.4f * t })
    }
}

/**
 * The code another device types to join, in large, spaced letters, with how long it lasts and
 * where this host can be reached. A new code replaces the old one.
 */
@Composable
internal fun PairingCard(app: AppState, code: String?, addresses: List<String>, compact: Boolean) {
    val svc = app.store.sync.service ?: return
    val c = Fuse.colors
    var current by remember(code) { mutableStateOf(code) }
    LaunchedEffect(code) { if (current == null) current = svc.newPairingCode() }
    // A code works for ten minutes: while it is shown, a fresh one takes its place just before.
    LaunchedEffect(current) {
        if (current == null) return@LaunchedEffect
        kotlinx.coroutines.delay(9 * 60_000L + 30_000L)
        current = svc.newPairingCode()
    }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(Fuse.geometry.panel)).background(c.text.copy(alpha = 0.05f))
            .border(1.dp, c.hairline, RoundedCornerShape(Fuse.geometry.panel)).padding(if (compact) Space.l else Space.xl),
        verticalArrangement = Arrangement.spacedBy(Space.m),
    ) {
        FText("To add a device", Fuse.type.bodyStrong, maxLines = 1)
        FText("On it: Settings, Addons, Fuse Sync, Connect to a Host, choose your host, then Type a Code:", Fuse.type.caption, color = c.textMuted, maxLines = 3)
        val letters = current?.toList() ?: List(8) { ' ' }
        Row(horizontalArrangement = Arrangement.spacedBy(if (compact) Space.xs else Space.s), verticalAlignment = Alignment.CenterVertically) {
            letters.forEachIndexed { i, ch ->
                if (i == 4) Spacer(Modifier.width(if (compact) Space.s else Space.m))
                Box(
                    Modifier.size(if (compact) 38.dp else 46.dp, if (compact) 46.dp else 56.dp).clip(RoundedCornerShape(10.dp)).background(c.surfaceRaised),
                    contentAlignment = Alignment.Center,
                ) {
                    if (current == null) Spinner(size = 14.dp, color = c.textFaint) else FText(ch.toString(), Fuse.type.numericLarge, maxLines = 1, fit = true, fitMin = 0.6f)
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            FuseIcon(FuseIcons.Timer, size = Size.iconS, tint = c.textMuted)
            Spacer(Modifier.width(Space.s))
            FText("Works once, for ten minutes", Fuse.type.caption, color = c.textMuted, maxLines = 1)
            if (addresses.isNotEmpty()) {
                Spacer(Modifier.width(Space.l))
                FuseIcon(FuseIcons.Network, size = Size.iconS, tint = c.textMuted)
                Spacer(Modifier.width(Space.s))
                FText(addresses.first(), Fuse.type.caption, color = c.textMuted, maxLines = 1)
            }
        }
    }
}

/** "Fuse Sync found": the hosts that answered on this network, or typing an address. */
@Composable
private fun Find(app: AppState, compact: Boolean, keys: ActiveKeys, onPick: (NearbyHost) -> Unit) {
    val actions = mutableListOf<SetupAction>()
    val index = keys.index
    val svc = app.store.sync.service ?: return
    val c = Fuse.colors
    var looking by remember { mutableStateOf(true) }
    var found by remember { mutableStateOf<List<NearbyHost>>(emptyList()) }
    var round by remember { mutableIntStateOf(0) }
    LaunchedEffect(round) {
        looking = true
        found = runCatching { svc.discover() }.getOrDefault(emptyList())
        looking = false
    }
    fun typeAddress() {
        app.textInput = TextInputSpec("The host's address", "", "192.168.1.20 or sync.example.com", capitalize = false, doneLabel = "Next") { v ->
            val a = v.trim()
            if (a.isNotEmpty()) onPick(NearbyHost(a.substringBefore(':'), "", a))
        }
    }
    found.forEach { h -> actions += SetupAction("h.${h.address}") { onPick(h) } }
    actions += SetupAction("type") { typeAddress() }
    if (!looking) actions += SetupAction("again") { round++ }

    Heading(
        if (found.isNotEmpty()) "Fuse Sync found" else "Find your host",
        when {
            looking -> "Looking for a Fuse Sync host on this network."
            found.isEmpty() -> "Nothing answered on this network. Check the host is on and Fuse Sync is set up there, or type its address."
            found.size == 1 -> "Your host is on this network. Choose it to connect."
            else -> "These hosts are on this network. Choose yours."
        },
        compact, if (found.isNotEmpty()) FuseIcons.CircleCheck else FuseIcons.Router,
    )
    Spacer(Modifier.height(Space.l))
    if (looking) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Spinner(size = 20.dp, color = c.accent)
            Spacer(Modifier.width(Space.m))
            FText("Asking the network", Fuse.type.body, color = c.textMuted)
        }
        Spacer(Modifier.height(Space.l))
    }
    Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
        found.forEachIndexed { i, h ->
            ChoiceRow("On this network", h.name, FuseIcons.Server, selected = index == i, detail = h.address, trailing = {
                FuseIcon(FuseIcons.ChevronRight, size = Size.iconM, tint = if (index == i) c.ink else c.textMuted)
            }) { onPick(h) }
        }
        ChoiceRow("Somewhere else", "Type an Address", FuseIcons.Keyboard, selected = index == found.size, detail = "A home address, or an outside one (a VPN or https tunnel)") { typeAddress() }
        if (!looking) {
            ChoiceRow("Didn't see it?", "Look Again", FuseIcons.RefreshCcw, selected = index == found.size + 1) { round++ }
        }
    }
    StepInput(app, keys, actions)
}

/** Why the last try didn't work, under what was tried (never over a button), or nothing. */
@Composable
private fun Problem(text: String?) {
    val c = Fuse.colors
    Swap(text, label = "problem") { e ->
        if (e != null) {
            Row(
                Modifier.padding(top = Space.m).fillMaxWidth().clip(RoundedCornerShape(Fuse.geometry.control))
                    .background(c.danger.copy(alpha = 0.12f)).border(1.dp, c.danger.copy(alpha = 0.35f), RoundedCornerShape(Fuse.geometry.control))
                    .padding(horizontal = Space.m, vertical = Space.s),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FuseIcon(FuseIcons.Unplug, size = Size.iconM, tint = c.danger)
                Spacer(Modifier.width(Space.m))
                FText(e, Fuse.type.caption, color = c.text, maxLines = 4)
            }
        }
    }
}

/**
 * Joining the host: asking it to let this device in (someone says yes on the host, or on any
 * device already connected, after checking a number), or typing a code from Add a Device. The
 * address from outside, when given, is tried when home doesn't answer.
 */
@Composable
private fun JoinWay(
    app: AppState,
    host: NearbyHost,
    compact: Boolean,
    keys: ActiveKeys,
    working: Boolean,
    error: String?,
    remote: String?,
    onRemote: (String?) -> Unit,
    onCode: () -> Unit,
    onAsk: () -> Unit,
) {
    val actions = mutableListOf<SetupAction>()
    val index = keys.index
    fun typeRemote() {
        app.textInput = TextInputSpec("Outside address (optional)", remote.orEmpty(), "https://sync.example.com", capitalize = false) { v -> onRemote(v.trim().ifEmpty { null }) }
    }
    actions += SetupAction("ask") { if (!working) onAsk() }
    actions += SetupAction("code") { onCode() }
    actions += SetupAction("remote") { typeRemote() }

    Heading(
        "Join ${host.name}",
        "Ask it to let this device in, or type a code from a device that's already connected.",
        compact, FuseIcons.Link,
    )
    Spacer(Modifier.height(Space.l))
    Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
        ChoiceRow(
            "No code to type", "Ask ${host.name} to Let This Device In", FuseIcons.UserPlus, selected = index == 0,
            detail = "A card asks on ${host.name} and on every device already connected. Anyone there can say yes",
            trailing = { if (working) Spinner(size = 20.dp, color = Fuse.colors.accent) else FuseIcon(FuseIcons.ChevronRight, size = Size.iconM, tint = if (index == 0) Fuse.colors.ink else Fuse.colors.textMuted) },
        ) { if (!working) onAsk() }
        ChoiceRow(
            "Have a code?", "Type a Code", FuseIcons.Keyboard, selected = index == 1,
            detail = "From Add a Device, on the host or on any device already connected",
        ) { onCode() }
        ChoiceRow(
            "For away from home (optional)", remote ?: "Add an Outside Address", FuseIcons.Globe, selected = index == 2,
            detail = "A tunnel or VPN address (https://sync.example.com). Used whenever home doesn't answer",
        ) { typeRemote() }
    }
    Problem(error)
    StepInput(app, keys, actions)
}

/**
 * Asked, and waiting to be let in: the number this device shows, large, for whoever says yes to
 * check against theirs. Lets in on its own as soon as someone does; turned away or out of time,
 * it goes back with why.
 */
@Composable
private fun AskingToJoin(
    app: AppState,
    host: NearbyHost,
    waiting: io.github.matiyaaa.fuse.sync.JoinWaiting,
    compact: Boolean,
    keys: ActiveKeys,
    onJoined: (String) -> Unit,
    onFailed: (String) -> Unit,
    onCode: () -> Unit,
    onCancel: () -> Unit,
) {
    val svc = app.store.sync.service ?: return
    val c = Fuse.colors
    val index = keys.index
    LaunchedEffect(waiting) {
        svc.awaitJoin()
            .onSuccess { onJoined(it) }
            .onFailure { if (it !is kotlinx.coroutines.CancellationException) onFailed(it.message ?: "Nobody let this device in") }
    }
    val actions = listOf(SetupAction("code") { onCode() }, SetupAction("cancel") { onCancel() })
    Heading(
        "Waiting for ${waiting.hostName}",
        "A card is asking on ${waiting.hostName}, and on your other connected devices, to let this device in. Say yes there if it shows this same number:",
        compact, FuseIcons.Timer,
    )
    Spacer(Modifier.height(Space.l))
    MatchNumber(waiting.match, compact)
    Spacer(Modifier.height(Space.l))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Spinner(size = 18.dp, color = c.accent)
        Spacer(Modifier.width(Space.m))
        FText("Waiting to be let in. This asks for five minutes", Fuse.type.caption, color = c.textMuted, maxLines = 2)
    }
    Spacer(Modifier.height(Space.xl))
    Row(horizontalArrangement = Arrangement.spacedBy(Space.m)) {
        FuseButton("Use a Code Instead", selected = index == 0, onClick = onCode, icon = FuseIcons.Keyboard)
        FuseButton("Cancel", selected = index == 1, onClick = onCancel)
    }
    StepInput(app, keys, actions)
}

/** The host's code: eight boxes that fill as it is typed (on a keyboard, or with the on-screen one). */
@Composable
private fun CodeEntry(app: AppState, host: NearbyHost, compact: Boolean, keys: ActiveKeys, working: Boolean, error: String?, initialRemote: String?, onConnect: (String, String?) -> Unit) {
    val actions = mutableListOf<SetupAction>()
    val index = keys.index
    val c = Fuse.colors
    var code by remember { mutableStateOf("") }
    var remote by remember { mutableStateOf(initialRemote) }
    fun clean(s: String) = s.uppercase().filter { it.isLetterOrDigit() }.take(8)
    fun typeCode() {
        app.textInput = TextInputSpec("The code on ${host.name}", code, "8 letters and numbers", capitalize = false, doneLabel = "Connect") { v ->
            code = clean(v)
            if (code.length == 8) onConnect(code, remote)
        }
    }
    val router = LocalInputRouter.current
    DisposableEffect(router) {
        val before = router.textInput
        router.textInput = object : TextInput {
            override fun type(text: String) { code = clean(code + text) }
            override fun backspace() { code = code.dropLast(1) }
            override fun submit() { if (code.length == 8 && !working) onConnect(code, remote) }
        }
        onDispose { router.textInput = before }
    }
    actions += SetupAction("type") { typeCode() }
    actions += SetupAction("remote") {
        app.textInput = TextInputSpec("Outside address (optional)", remote.orEmpty(), "https://sync.example.com", capitalize = false) { v -> remote = v.trim().ifEmpty { null } }
    }
    actions += SetupAction("connect") { if (code.length == 8 && !working) onConnect(code, remote) else typeCode() }

    Heading(
        "Enter the code from ${host.name}",
        "On the host: Settings, Addons, Fuse Sync, Add a Device. It shows a code that works once.",
        compact, FuseIcons.Key,
    )
    Spacer(Modifier.height(Space.l))
    Row(
        Modifier.clip(RoundedCornerShape(Fuse.geometry.control)).clickable(remember { MutableInteractionSource() }, indication = null) { typeCode() },
        horizontalArrangement = Arrangement.spacedBy(if (compact) Space.xs else Space.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (i in 0 until 8) {
            if (i == 4) Spacer(Modifier.width(if (compact) Space.s else Space.m))
            val ch = code.getOrNull(i)
            val next = i == code.length && index == 0
            Box(
                Modifier.size(if (compact) 34.dp else 44.dp, if (compact) 44.dp else 56.dp).clip(RoundedCornerShape(10.dp))
                    .background(if (index == 0) c.text.copy(alpha = 0.1f) else c.text.copy(alpha = 0.05f))
                    .border(if (next) 2.dp else 1.dp, if (next) c.focus else c.hairline, RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center,
            ) {
                // Wide letters (W, M, Q) are set a little smaller rather than cut off.
                FText(ch?.toString() ?: "", Fuse.type.numericLarge, maxLines = 1, fit = true, fitMin = 0.6f)
            }
        }
    }
    Spacer(Modifier.height(Space.l))
    ChoiceRow("Code", if (code.isEmpty()) "Type the Code" else code.chunked(4).joinToString(" "), FuseIcons.Keyboard, selected = index == 0) { typeCode() }
    Spacer(Modifier.height(Space.s))
    ChoiceRow(
        "For away from home (optional)", remote ?: "Add an Outside Address", FuseIcons.Globe, selected = index == 1,
        detail = "Fuse uses the home address when it answers and this one otherwise, by itself",
    ) { app.textInput = TextInputSpec("Outside address (optional)", remote.orEmpty(), "https://sync.example.com", capitalize = false) { v -> remote = v.trim().ifEmpty { null } } }
    Problem(error)
    Spacer(Modifier.height(Space.xl))
    FuseButton(
        if (error != null) "Try Again" else "Connect", selected = index == 2, onClick = { if (code.length == 8 && !working) onConnect(code, remote) else typeCode() },
        icon = FuseIcons.Link, kind = ButtonKind.PRIMARY, loading = working, enabled = code.length == 8 || index == 2,
    )
    StepInput(app, keys, actions)
}

@Composable
private fun Connected(app: AppState, hostName: String, compact: Boolean, keys: ActiveKeys) {
    val actions = mutableListOf<SetupAction>()
    val index = keys.index
    val c = Fuse.colors
    val svc = app.store.sync.service
    val profiles by androidx.compose.runtime.produceState(svc?.profiles?.value.orEmpty(), svc) { svc?.profiles?.collect { value = it } }
    // Nobody here yet (the host's own profile is the host's alone): this device makes the first.
    val who = if (profiles.isEmpty()) WhoMode.ADD else WhoMode.SWITCH
    actions += SetupAction("who") { app.back(); app.whoAreYou = who }
    actions += SetupAction("later") { app.back() }
    SuccessMark(if (compact) 64.dp else 88.dp)
    Spacer(Modifier.height(Space.l))
    FText("Connected to $hostName", if (compact) Fuse.type.title else Fuse.type.display, maxLines = 2, modifier = Modifier.semantics { heading() })
    Spacer(Modifier.height(Space.xs))
    FText(
        "Next, choose who's playing on this device. What it already has joins that profile, and nothing here is replaced without asking.",
        Fuse.type.body, color = c.textMuted, maxLines = 4,
    )
    Spacer(Modifier.height(Space.xl))
    Row(horizontalArrangement = Arrangement.spacedBy(Space.m)) {
        FuseButton(if (profiles.isEmpty()) "Make Your Profile" else "Choose Who's Playing", selected = index == 0, onClick = { app.back(); app.whoAreYou = who }, icon = if (profiles.isEmpty()) FuseIcons.UserPlus else FuseIcons.Users, kind = ButtonKind.PRIMARY)
        FuseButton("Later", selected = index == 1, onClick = { app.back() })
    }
    StepInput(app, keys, actions)
}
