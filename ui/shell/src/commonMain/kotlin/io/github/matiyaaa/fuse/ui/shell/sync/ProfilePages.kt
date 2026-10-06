package io.github.matiyaaa.fuse.ui.shell.sync

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.matiyaaa.fuse.model.NavAction
import io.github.matiyaaa.fuse.sync.MergeChoice
import io.github.matiyaaa.fuse.sync.ProfileChange
import io.github.matiyaaa.fuse.sync.ProfileInfo
import io.github.matiyaaa.fuse.sync.ProfileMerge
import io.github.matiyaaa.fuse.sync.SyncService
import io.github.matiyaaa.fuse.ui.designsystem.components.ButtonKind
import io.github.matiyaaa.fuse.ui.designsystem.components.FText
import io.github.matiyaaa.fuse.ui.designsystem.components.FuseButton
import io.github.matiyaaa.fuse.ui.designsystem.components.ProfileAvatar
import io.github.matiyaaa.fuse.ui.designsystem.components.ToastKind
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcon
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.designsystem.input.InputLayer
import io.github.matiyaaa.fuse.ui.designsystem.input.LayerPriority
import io.github.matiyaaa.fuse.ui.designsystem.input.NavResult
import io.github.matiyaaa.fuse.ui.designsystem.sound.SoundCue
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.Size
import io.github.matiyaaa.fuse.ui.designsystem.theme.Space
import io.github.matiyaaa.fuse.ui.fuseline.Appear
import io.github.matiyaaa.fuse.ui.fuseline.Curves
import io.github.matiyaaa.fuse.ui.fuseline.Durations
import io.github.matiyaaa.fuse.ui.fuseline.fadeIn
import io.github.matiyaaa.fuse.ui.fuseline.fadeOut
import io.github.matiyaaa.fuse.ui.fuseline.fuselineColor
import io.github.matiyaaa.fuse.ui.fuseline.scaleIn
import io.github.matiyaaa.fuse.ui.fuseline.scaleOut
import io.github.matiyaaa.fuse.ui.shell.app.AppState
import io.github.matiyaaa.fuse.ui.shell.app.ConfirmSpec
import io.github.matiyaaa.fuse.ui.shell.app.TextInputSpec
import kotlinx.coroutines.launch

/** A profile being edited, with the PIN that opened it (when it has one). */
data class ProfileEditSpec(val profile: ProfileInfo, val currentPin: String?)

/** The room profile pages sit in: their own, the page underneath out of sight, lit from above by the accent. */
@Composable
internal fun ProfileRoom(content: @Composable BoxScope.() -> Unit) {
    val c = Fuse.colors
    Box(
        Modifier.fillMaxSize()
            .background(c.surfaceDim)
            .background(Brush.verticalGradient(listOf(c.accent.copy(alpha = 0.14f), Color.Transparent, Color.Transparent)))
            .clickable(remember { MutableInteractionSource() }, indication = null) { },
        content = content,
    )
}

/** How profile pages arrive: settling into place from a touch larger, as Who's playing? does. */
@Composable
private fun ProfilePage(visible: Boolean, content: @Composable () -> Unit) {
    val motion = Fuse.motion
    Appear(
        visible,
        enter = fadeIn(motion.tween(Durations.SLOW)) + scaleIn(motion.tween(Durations.DELIBERATE, Curves.Enter), initialScale = 1.06f),
        exit = fadeOut(motion.tween(Durations.BASE)) + scaleOut(motion.tween(Durations.BASE), targetScale = 0.97f),
    ) { content() }
}

/** Editing a profile: its name, picture and PIN, in [ProfileEditor]. */
@Composable
internal fun ProfileEditOverlay(app: AppState) {
    val spec = app.profileEdit
    var shown by remember { mutableStateOf(spec) }
    if (spec != null) shown = spec
    val svc = app.store.sync.service
    ProfilePage(spec != null && svc != null) {
        val s = shown ?: return@ProfilePage
        if (svc == null) return@ProfilePage
        ProfileRoom {
            Box(Modifier.fillMaxSize().padding(horizontal = Space.gutter)) {
                key(s) {
                    ProfileEditor(
                        app,
                        title = "Edit Profile",
                        subtitle = "${s.profile.name}'s name, picture and PIN. Their saves and play time stay as they are.",
                        submitLabel = "Save Changes",
                        submitIcon = FuseIcons.Check,
                        initialName = s.profile.name,
                        initialAvatar = s.profile.avatar,
                        hasPin = s.profile.protected,
                        onSubmit = { name, avatar, pin ->
                            val change = ProfileChange(
                                name = name.takeIf { it != s.profile.name },
                                avatar = avatar.takeIf { it != s.profile.avatar },
                                pin = (pin as? PinChoice.Set)?.digits,
                                removePin = pin == PinChoice.None && s.profile.protected,
                                currentPin = s.currentPin,
                            )
                            svc.changeProfile(s.profile.id, change).fold(
                                {
                                    app.profileEdit = null
                                    app.toasts.show("${it.name} is saved", ToastKind.SUCCESS, icon = FuseIcons.UserRound)
                                    null
                                },
                                { it.message ?: "Couldn't change the profile" },
                            )
                        },
                        onBack = { app.profileEdit = null },
                    )
                }
            }
        }
    }
}

/** The choices for one profile when joining a host, in the order Left and Right go through them. */
internal object MergeOptions {
    /** [p]'s choices: the person suggested by name first, then someone new, everyone else there, leaving out. */
    fun of(p: ProfileInfo, merge: ProfileMerge): List<MergeChoice> {
        val suggested = merge.suggested[p.id]
        val same = merge.host.sortedBy { if (it.id == suggested) 0 else 1 }.map { MergeChoice.Same(it.id) }
        return if (suggested != null) same + MergeChoice.Add + MergeChoice.LeaveOut else listOf(MergeChoice.Add) + same + MergeChoice.LeaveOut
    }

    /** Where [p] starts: the same person when the names match, else someone new. */
    fun first(p: ProfileInfo, merge: ProfileMerge): MergeChoice = merge.suggested[p.id]?.let { MergeChoice.Same(it) } ?: MergeChoice.Add

    /** [current] moved [by] steps through [p]'s choices, round and round. */
    fun step(p: ProfileInfo, merge: ProfileMerge, current: MergeChoice, by: Int): MergeChoice {
        val all = of(p, merge)
        val at = all.indexOf(current).coerceAtLeast(0)
        return all[(at + by).mod(all.size)]
    }
}

/**
 * Joining a host that has people of its own while this device has profiles: "Your profiles and
 * the host's". Each profile here is the same person as someone there, someone new, or left out;
 * Left and Right (or A) change it, and the PIN of anyone joined who has one is asked once, on
 * Bring Them. Nothing changes until then; Not Now stops joining and keeps everything here.
 */
@Composable
internal fun ProfileMergeOverlay(app: AppState) {
    val svc = app.store.sync.service ?: return
    val merge by svc.merge.collectAsState()
    LaunchedEffect(merge != null) {
        app.profileMerge = merge != null
        if (merge != null) app.platform.sounds.play(SoundCue.OPEN)
    }
    var shown by remember { mutableStateOf(merge) }
    if (merge != null) shown = merge
    ProfilePage(merge != null) {
        val m = shown ?: return@ProfilePage
        ProfileRoom { key(m) { MergePage(app, svc, m) } }
    }
}

@Composable
private fun MergePage(app: AppState, svc: SyncService, m: ProfileMerge) {
    val c = Fuse.colors
    val choices = remember { mutableStateMapOf<String, MergeChoice>().apply { m.here.forEach { put(it.id, MergeOptions.first(it, m)) } } }
    val buttons = m.here.size
    var row by remember { mutableIntStateOf(0) }
    // 0 Not Now, 1 only the host's, 2 Bring Them.
    var button by remember { mutableIntStateOf(2) }
    var working by remember { mutableStateOf(false) }

    fun bring(final: Map<String, MergeChoice>) {
        if (working) return
        working = true
        app.scope.launch {
            val r = svc.bringProfiles(final)
            working = false
            r.onSuccess {
                val brought = final.values.count { it != MergeChoice.LeaveOut }
                app.toasts.show(if (brought == 0) "Using ${m.hostName}'s profiles" else "Your profiles are on ${m.hostName} now", ToastKind.SUCCESS, icon = FuseIcons.Users)
                svc.activeProfile.value?.let { app.profileArrival = it }
            }.onFailure { e ->
                app.platform.sounds.play(SoundCue.ERROR)
                app.toasts.show(e.message ?: "Couldn't bring the profiles", ToastKind.ERROR)
            }
        }
    }
    // Each person joined to someone with a PIN types that PIN, one after another, then everything goes at once.
    fun withPins(then: (Map<String, MergeChoice>) -> Unit) {
        val asks = m.here.filter { p -> (choices[p.id] as? MergeChoice.Same)?.let { s -> m.host.firstOrNull { it.id == s.hostProfile }?.protected } == true }
        fun next(i: Int, acc: Map<String, MergeChoice>) {
            val p = asks.getOrNull(i) ?: return then(acc)
            val same = acc[p.id] as MergeChoice.Same
            val target = m.host.first { it.id == same.hostProfile }
            app.textInput = TextInputSpec("${target.name}'s PIN on ${m.hostName}", "", "To join ${p.name} with ${target.name}", secret = true, capitalize = false, doneLabel = "Next") { v ->
                next(i + 1, acc + (p.id to same.copy(pin = v.filter { it.isDigit() })))
            }
        }
        next(0, m.here.associate { it.id to (choices[it.id] ?: MergeOptions.first(it, m)) })
    }
    fun bringThem() {
        val left = m.here.filter { choices[it.id] == MergeChoice.LeaveOut }
        if (left.isEmpty()) return withPins(::bring)
        val names = left.joinToString(", ") { it.name }
        app.confirm = ConfirmSpec(
            if (left.size == 1) "Leave out ${left[0].name}?" else "Leave out ${left.size} profiles?",
            "$names ${if (left.size == 1) "leaves" else "leave"} this device. Their saves are kept as plain files in Fuse Sync's kept folder.",
            "Leave Out", destructive = true,
        ) { withPins(::bring) }
    }
    fun onlyTheirs() {
        app.confirm = ConfirmSpec(
            "Use only ${m.hostName}'s profiles?",
            "${m.here.joinToString(", ") { it.name }} ${if (m.here.size == 1) "leaves" else "leave"} this device. Their saves are kept as plain files in Fuse Sync's kept folder.",
            "Use Theirs", destructive = true,
        ) { bring(m.here.associate { it.id to MergeChoice.LeaveOut }) }
    }
    fun notNow() {
        app.confirm = ConfirmSpec(
            "Stop joining ${m.hostName}?",
            "This device keeps its own profiles and isn't linked. Join again any time from Settings, Addons, Fuse Sync.",
            "Stop Joining",
        ) { app.scope.launch { svc.cancelMerge() } }
    }
    fun press(b: Int) = when (b) {
        0 -> notNow()
        1 -> onlyTheirs()
        else -> bringThem()
    }

    InputLayer(priority = LayerPriority.DIALOG + 3, enabled = app.textInput == null && app.confirm == null, modal = true) { e ->
        val p = m.here.getOrNull(row)
        when (e.action) {
            NavAction.UP -> if (row > 0) { row--; NavResult.MOVED } else NavResult.BLOCKED
            NavAction.DOWN -> if (row < buttons) { row++; NavResult.MOVED } else NavResult.BLOCKED
            NavAction.LEFT, NavAction.RIGHT -> {
                val by = if (e.action == NavAction.LEFT) -1 else 1
                if (p != null) {
                    choices[p.id] = MergeOptions.step(p, m, choices[p.id] ?: MergeOptions.first(p, m), by)
                    NavResult.MOVED
                } else {
                    val next = button + by
                    if (next in 0..2) { button = next; NavResult.MOVED } else NavResult.BLOCKED
                }
            }
            NavAction.SELECT -> {
                if (p != null) choices[p.id] = MergeOptions.step(p, m, choices[p.id] ?: MergeOptions.first(p, m), 1) else press(button)
                NavResult.ACTIVATED
            }
            NavAction.BACK -> { notNow(); NavResult.CONSUMED }
            else -> NavResult.CONSUMED
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = Space.gutter)) {
        val compact = maxHeight < 560.dp || maxWidth < 640.dp
        val short = maxHeight < 440.dp
        // A narrow screen (a handheld's lower screen): the buttons drop their icons so all three fit.
        val narrow = maxWidth < 560.dp
        val inView = remember(m.here.size) { List(m.here.size) { BringIntoViewRequester() } }
        LaunchedEffect(row) { inView.getOrNull(row)?.bringIntoView() }
        Column(Modifier.fillMaxSize().padding(vertical = if (short) Space.s else Space.m), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(Modifier.widthIn(max = 760.dp).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
                    if (!short) {
                        SyncMark(if (compact) 40.dp else 52.dp)
                        Spacer(Modifier.height(if (compact) Space.m else Space.l))
                    }
                    FText("Your profiles and ${m.hostName}'s", if (compact) Fuse.type.title else Fuse.type.display, align = TextAlign.Center, maxLines = 2, modifier = Modifier.semantics { heading() })
                    if (!short) {
                        Spacer(Modifier.height(Space.xs))
                        FText(
                            "${m.hostName} already has people. Say who's who here, and everyone's saves and play time come along.",
                            Fuse.type.body, color = c.textMuted, align = TextAlign.Center, maxLines = 2,
                        )
                    }
                    Spacer(Modifier.height(if (short) Space.m else if (compact) Space.l else Space.xl))
                    Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
                        m.here.forEachIndexed { i, p ->
                            MergeRow(
                                p, choices[p.id] ?: MergeOptions.first(p, m), m, selected = row == i, compact = compact, narrow = narrow,
                                modifier = Modifier.bringIntoViewRequester(inView[i]).testTag("merge.row.$i"),
                                onStep = { by -> row = i; choices[p.id] = MergeOptions.step(p, m, choices[p.id] ?: MergeOptions.first(p, m), by) },
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(if (short) Space.s else Space.m))
            // The buttons stay on screen whatever the size.
            Row(Modifier.testTag("merge.buttons"), horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                FuseButton("Not Now", selected = row == buttons && button == 0, onClick = { row = buttons; button = 0; notNow() }, icon = FuseIcons.ArrowLeft.takeIf { !narrow }, kind = ButtonKind.GHOST)
                FuseButton(if (compact) "Only Theirs" else "Use Only Theirs", selected = row == buttons && button == 1, onClick = { row = buttons; button = 1; onlyTheirs() }, icon = FuseIcons.Users.takeIf { !narrow })
                FuseButton("Bring Them", selected = row == buttons && button == 2, onClick = { row = buttons; button = 2; bringThem() }, icon = FuseIcons.Check.takeIf { !narrow }, kind = ButtonKind.PRIMARY, loading = working)
            }
        }
    }
}

/** One profile of this device's, and what becomes of it, with arrows either side to change that. */
@Composable
private fun MergeRow(p: ProfileInfo, choice: MergeChoice, m: ProfileMerge, selected: Boolean, compact: Boolean, narrow: Boolean, modifier: Modifier, onStep: (Int) -> Unit) {
    val c = Fuse.colors
    val bg by fuselineColor(if (selected) c.text else c.text.copy(alpha = 0.06f), Fuse.motion.tween(Durations.FAST), label = "mergeRow")
    val fg = if (selected) c.ink else c.text
    val muted = if (selected) c.ink.copy(alpha = 0.65f) else c.textMuted
    val target = (choice as? MergeChoice.Same)?.let { s -> m.host.firstOrNull { it.id == s.hostProfile } }
    val (label, detail) = when (choice) {
        is MergeChoice.Same -> "Same as ${target?.name ?: "them"}" to if (target?.protected == true) "Their PIN is asked" else "Joins their profile"
        MergeChoice.Add -> "Someone new" to "Added to ${m.hostName}"
        MergeChoice.LeaveOut -> "Leave out" to "Saves kept as files"
    }
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(Fuse.geometry.control)).background(bg)
            .clickable(remember { MutableInteractionSource() }, indication = null) { onStep(1) }
            .padding(horizontal = if (compact) Space.m else Space.l, vertical = if (compact) Space.s else Space.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ProfileAvatar(p.avatar, if (compact) 40.dp else 52.dp)
        Spacer(Modifier.width(Space.m))
        Column(Modifier.weight(1f)) {
            FText(p.name, Fuse.type.bodyStrong, color = fg, maxLines = 1)
            FText(if (p.protected) "Has a PIN" else "This device", Fuse.type.caption, color = muted, maxLines = 1)
        }
        Spacer(Modifier.width(Space.s))
        Arrow(FuseIcons.ChevronLeft, fg) { onStep(-1) }
        Row(Modifier.width(if (narrow) 168.dp else if (compact) 192.dp else 248.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
                when (choice) {
                    is MergeChoice.Same -> ProfileAvatar(target?.avatar ?: p.avatar, 32.dp)
                    MergeChoice.Add -> FuseIcon(FuseIcons.UserPlus, size = Size.iconM, tint = if (selected) c.ink else c.accent)
                    MergeChoice.LeaveOut -> FuseIcon(FuseIcons.LogOut, size = Size.iconM, tint = if (selected) c.ink else c.warning)
                }
            }
            Spacer(Modifier.width(Space.s))
            Column {
                FText(label, Fuse.type.bodyStrong, color = fg, maxLines = 1)
                FText(detail, Fuse.type.caption, color = muted, maxLines = 1)
            }
        }
        Arrow(FuseIcons.ChevronRight, fg) { onStep(1) }
    }
}

@Composable
private fun Arrow(icon: androidx.compose.ui.graphics.vector.ImageVector, tint: Color, onClick: () -> Unit) {
    Box(
        Modifier.size(Size.touch * 0.75f).clip(CircleShape).clickable(remember { MutableInteractionSource() }, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { FuseIcon(icon, size = Size.iconM, tint = tint.copy(alpha = 0.75f)) }
}
