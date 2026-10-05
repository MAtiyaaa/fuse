package io.github.matiyaaa.fuse.ui.shell.sync

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.matiyaaa.fuse.model.NavAction
import io.github.matiyaaa.fuse.sync.ProfileInfo
import io.github.matiyaaa.fuse.sync.SyncException
import io.github.matiyaaa.fuse.sync.SyncStatus
import io.github.matiyaaa.fuse.ui.designsystem.components.ButtonKind
import io.github.matiyaaa.fuse.ui.designsystem.components.FText
import io.github.matiyaaa.fuse.ui.designsystem.components.FuseAvatars
import io.github.matiyaaa.fuse.ui.designsystem.components.FuseButton
import io.github.matiyaaa.fuse.ui.designsystem.components.Hint
import io.github.matiyaaa.fuse.ui.designsystem.components.HintBar
import io.github.matiyaaa.fuse.ui.designsystem.components.ProfileAvatar
import io.github.matiyaaa.fuse.ui.designsystem.components.Spinner
import io.github.matiyaaa.fuse.ui.designsystem.components.ToastKind
import io.github.matiyaaa.fuse.ui.designsystem.effects.rememberReveal
import io.github.matiyaaa.fuse.ui.designsystem.effects.reveal
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcon
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.designsystem.icons.HintButton
import io.github.matiyaaa.fuse.ui.designsystem.input.InputLayer
import io.github.matiyaaa.fuse.ui.designsystem.input.LayerPriority
import io.github.matiyaaa.fuse.ui.designsystem.input.LocalInputRouter
import io.github.matiyaaa.fuse.ui.designsystem.input.NavResult
import io.github.matiyaaa.fuse.ui.designsystem.input.TextInput
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
import io.github.matiyaaa.fuse.ui.fuseline.fuselineFloat
import io.github.matiyaaa.fuse.ui.fuseline.scaleIn
import io.github.matiyaaa.fuse.ui.fuseline.scaleOut
import io.github.matiyaaa.fuse.ui.shell.app.AppState
import io.github.matiyaaa.fuse.ui.shell.app.TextInputSpec
import kotlinx.coroutines.launch

/** Why "Who's playing?" is open: switching (it can be closed), at startup, or straight to a new profile. */
enum class WhoMode { SWITCH, STARTUP, ADD }

/** What the screen shows: the people, one person's PIN, or a new profile being made. */
private sealed interface WhoStep {
    data object People : WhoStep
    data class Pin(val profile: ProfileInfo) : WhoStep
    data object Create : WhoStep
}

/**
 * "Who's playing?": everyone with a profile on this Fuse Sync host as a card with their avatar, and
 * a card to add someone. Choosing one switches this device to them in place (their library, saves,
 * theme and Home arrive at once; no restart), asking for their PIN first when they set one. Left
 * and Right (and Up and Down across rows) move, A chooses, B closes when switching.
 */
@Composable
internal fun WhoAreYouOverlay(app: AppState) {
    val mode = app.whoAreYou
    var shown by remember { mutableStateOf(mode) }
    if (mode != null) shown = mode
    val svc = app.store.sync.service
    // The room settles into place from a touch larger, as if the screen leaned in to ask.
    val motion = Fuse.motion
    Appear(
        mode != null && svc != null,
        enter = fadeIn(motion.tween(Durations.SLOW)) + scaleIn(motion.tween(Durations.DELIBERATE, Curves.Enter), initialScale = 1.06f),
        exit = fadeOut(motion.tween(Durations.BASE)) + scaleOut(motion.tween(Durations.BASE), targetScale = 0.97f),
    ) {
        val m = shown ?: return@Appear
        if (svc == null) return@Appear
        WhoAreYou(app, m)
    }
}

@Composable
private fun WhoAreYou(app: AppState, mode: WhoMode) {
    val svc = app.store.sync.service ?: return
    val profiles by svc.profiles.collectAsState()
    val active by svc.activeProfile.collectAsState()
    val status by svc.status.collectAsState()
    var step by remember(mode) { mutableStateOf<WhoStep>(if (mode == WhoMode.ADD) WhoStep.Create else WhoStep.People) }
    var index by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf<String?>(null) }
    val c = Fuse.colors
    val closable = mode != WhoMode.STARTUP || active != null
    LaunchedEffect(profiles, active) {
        val at = profiles.indexOfFirst { it.id == active?.id }
        if (at >= 0 && index == 0) index = at
    }
    LaunchedEffect(Unit) { app.platform.sounds.play(SoundCue.OPEN) }

    fun close() {
        app.whoAreYou = null
        app.platform.sounds.play(SoundCue.CLOSE)
    }

    fun switchTo(p: ProfileInfo, pin: String?, onWrongPin: (String) -> Unit = {}) {
        if (busy != null) return
        if (p.id == active?.id) {
            close()
            return
        }
        busy = p.id
        app.scope.launch {
            val r = svc.switchTo(p.id, pin)
            busy = null
            r.onSuccess {
                // The person arrives: their avatar blooms over everything, then settles in the corner.
                app.profileArrival = p
                app.whoAreYou = null
            }.onFailure { e ->
                val code = (e as? SyncException)?.code
                if (code == "wrong-pin" || code == "wait") onWrongPin(e.message ?: "That PIN isn't right.")
                else app.toasts.show(e.message ?: "Couldn't switch profiles", ToastKind.ERROR)
            }
        }
    }

    Box(
        Modifier.fillMaxSize()
            // Its own room: the page underneath stays out of sight, so nothing reads through the cards.
            .background(c.surfaceDim)
            .background(Brush.verticalGradient(listOf(c.accent.copy(alpha = 0.14f), Color.Transparent, Color.Transparent)))
            .clickable(remember { MutableInteractionSource() }, indication = null) { },
    ) {
        BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = Space.gutter), contentAlignment = Alignment.Center) {
            val compact = maxHeight < 560.dp || maxWidth < 640.dp
            when (val s = step) {
                WhoStep.People -> People(
                    app, profiles, active, status, index, busy, compact, maxWidth,
                    onIndex = { index = it },
                    onChoose = { i ->
                        val p = profiles.getOrNull(i)
                        if (p == null) {
                            step = WhoStep.Create
                        } else if (p.protected && p.id != active?.id) {
                            step = WhoStep.Pin(p)
                        } else {
                            switchTo(p, null)
                        }
                    },
                    onBack = { if (closable) close() },
                    onAdd = { step = WhoStep.Create },
                    closable = closable,
                )
                is WhoStep.Pin -> PinPad(
                    app, s.profile, busy == s.profile.id, compact,
                    onSubmit = { pin, wrong -> switchTo(s.profile, pin, wrong) },
                    onBack = { step = WhoStep.People },
                )
                WhoStep.Create -> CreateProfile(
                    app, compact,
                    onCreated = { p, pin ->
                        index = profiles.size
                        step = WhoStep.People
                        switchTo(p, pin)
                    },
                    onBack = { if (mode == WhoMode.ADD) close() else step = WhoStep.People },
                )
            }
        }
    }
}

@Composable
private fun People(
    app: AppState,
    profiles: List<ProfileInfo>,
    active: ProfileInfo?,
    status: SyncStatus,
    index: Int,
    busy: String?,
    compact: Boolean,
    width: Dp,
    onIndex: (Int) -> Unit,
    onChoose: (Int) -> Unit,
    onBack: () -> Unit,
    onAdd: () -> Unit,
    closable: Boolean,
) {
    val c = Fuse.colors
    val offline = status is SyncStatus.Offline || status is SyncStatus.Connecting
    val count = profiles.size + if (offline) 0 else 1
    val card = if (compact) 120.dp else 168.dp
    val perRow = ((width + Space.l) / (card + Space.l)).toInt().coerceIn(1, maxOf(1, count))
    val sel = index.coerceIn(0, maxOf(0, count - 1))
    InputLayer(priority = LayerPriority.DIALOG + 2, modal = true) { e ->
        when (e.action) {
            NavAction.LEFT -> if (sel > 0) { onIndex(sel - 1); NavResult.MOVED } else NavResult.BLOCKED
            NavAction.RIGHT -> if (sel < count - 1) { onIndex(sel + 1); NavResult.MOVED } else NavResult.BLOCKED
            NavAction.UP -> if (sel - perRow >= 0) { onIndex(sel - perRow); NavResult.MOVED } else NavResult.BLOCKED
            NavAction.DOWN -> if (sel + perRow < count) { onIndex(sel + perRow); NavResult.MOVED } else NavResult.BLOCKED
            NavAction.SELECT -> { if (count > 0) onChoose(sel); NavResult.ACTIVATED }
            NavAction.BACK -> { onBack(); NavResult.CONSUMED }
            // Y makes someone new from anywhere on the page, without walking to the last card.
            NavAction.SEARCH -> { if (!offline) onAdd(); NavResult.ACTIVATED }
            else -> NavResult.CONSUMED
        }
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        SyncMark(if (compact) 40.dp else 52.dp)
        Spacer(Modifier.height(if (compact) Space.m else Space.l))
        FText("Who's playing?", if (compact) Fuse.type.title else Fuse.type.hero, align = TextAlign.Center, maxLines = 1, modifier = Modifier.semantics { heading() })
        Spacer(Modifier.height(Space.xs))
        FText(
            if (offline) "The host isn't answering, so switching waits until it does. Everything here keeps working." else "Your games, saves, play time and settings follow you to every device.",
            Fuse.type.body, color = c.textMuted, align = TextAlign.Center, maxLines = 2, modifier = Modifier.widthIn(max = 560.dp),
        )
        Spacer(Modifier.height(if (compact) Space.l else Space.xxl))
        if (profiles.isEmpty() && offline) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spinner(size = 20.dp, color = c.textMuted)
                Spacer(Modifier.width(Space.s))
                FText("Reaching your host", Fuse.type.body, color = c.textMuted)
            }
        } else {
            val rows = (0 until count).chunked(perRow)
            // Each card rises into place a beat after the one before it.
            val cards = rememberReveal(count)
            Column(verticalArrangement = Arrangement.spacedBy(Space.l), horizontalAlignment = Alignment.CenterHorizontally) {
                for (row in rows) {
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.l)) {
                        for (i in row) {
                            val p = profiles.getOrNull(i)
                            Box(Modifier.reveal(cards, i)) {
                                if (p != null) {
                                    PersonCard(p, selected = i == sel, here = p.id == active?.id, working = busy == p.id, size = card) { onIndex(i); onChoose(i) }
                                } else {
                                    AddCard(selected = i == sel, size = card) { onIndex(i); onChoose(i) }
                                }
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(if (compact) Space.l else Space.xxl))
        HintBar(buildList {
            add(Hint(HintButton.CONFIRM, "Choose"))
            if (!offline) add(Hint(HintButton.SEARCH, "New Profile"))
            if (closable) add(Hint(HintButton.BACK, "Back"))
        })
    }
}

/** One person: their avatar, lifted and ringed when chosen, their name, and what to know (here now, a PIN). */
@Composable
private fun PersonCard(p: ProfileInfo, selected: Boolean, here: Boolean, working: Boolean, size: Dp, onClick: () -> Unit) {
    val c = Fuse.colors
    val lift by fuselineFloat(if (selected) 1f else 0f, Fuse.motion.focusSpring(), label = "who")
    val reduced = Fuse.motion.reduced
    val nameColor by fuselineColor(if (selected) c.text else c.textMuted, Fuse.motion.tween(Durations.FAST), label = "whoName")
    Column(
        Modifier.width(size)
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .graphicsLayer {
                val s = if (reduced) 1f else 1f + 0.07f * lift
                scaleX = s
                scaleY = s
            },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(contentAlignment = Alignment.Center) {
            ProfileAvatar(p.avatar, size * 0.78f, ring = if (selected) c.focus else null)
            if (working) {
                Box(Modifier.size(size * 0.78f).clip(CircleShape).background(Color.Black.copy(alpha = 0.35f)), contentAlignment = Alignment.Center) {
                    Spinner(size = 28.dp, color = Color.White)
                }
            }
            if (p.protected) {
                Box(
                    Modifier.align(Alignment.BottomEnd).size(26.dp).clip(CircleShape).background(c.surfaceRaised),
                    contentAlignment = Alignment.Center,
                ) { FuseIcon(FuseIcons.Lock, size = 14.dp, tint = c.text) }
            }
        }
        Spacer(Modifier.height(Space.m))
        FText(p.name, Fuse.type.bodyStrong, color = nameColor, maxLines = 1, align = TextAlign.Center)
        FText(if (here) "Playing here" else if (p.hostOnly) "This computer only" else " ", Fuse.type.caption, color = if (here) c.accent else c.textMuted, maxLines = 1, align = TextAlign.Center)
    }
}

/** "Add Profile": a dashed ring with a person and a plus, where the next card will be. */
@Composable
private fun AddCard(selected: Boolean, size: Dp, onClick: () -> Unit) {
    val c = Fuse.colors
    val lift by fuselineFloat(if (selected) 1f else 0f, Fuse.motion.focusSpring(), label = "add")
    val reduced = Fuse.motion.reduced
    val ring by fuselineColor(if (selected) c.focus else c.textFaint, Fuse.motion.tween(Durations.FAST), label = "addRing")
    Column(
        Modifier.width(size)
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .graphicsLayer {
                val s = if (reduced) 1f else 1f + 0.07f * lift
                scaleX = s
                scaleY = s
            },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(size * 0.78f), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(size * 0.78f)) {
                val r = this.size.minDimension / 2f - 2.dp.toPx()
                drawCircle(c.text.copy(alpha = if (selected) 0.08f else 0.04f), r)
                drawCircle(ring, r, style = Stroke(width = 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10.dp.toPx(), 7.dp.toPx()))))
            }
            FuseIcon(FuseIcons.UserPlus, size = size * 0.3f, tint = if (selected) c.text else c.textMuted)
        }
        Spacer(Modifier.height(Space.m))
        FText("Add Profile", Fuse.type.bodyStrong, color = if (selected) c.text else c.textMuted, maxLines = 1, align = TextAlign.Center)
        FText(" ", Fuse.type.caption, maxLines = 1)
    }
}

/** The keys of the PIN pad, in reading order: digits, then erase, zero and done. */
private val KEYS = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "erase", "0", "done")

/**
 * A person's PIN: their avatar and name, a dot for each digit typed, and a pad the D-pad walks (or
 * the number keys type into). A wrong PIN shakes the dots and says so; after three, the host asks
 * to wait a moment.
 */
@Composable
private fun PinPad(app: AppState, p: ProfileInfo, working: Boolean, compact: Boolean, onSubmit: (String, (String) -> Unit) -> Unit, onBack: () -> Unit) {
    val c = Fuse.colors
    var pin by remember(p.id) { mutableStateOf("") }
    var error by remember(p.id) { mutableStateOf<String?>(null) }
    var key by remember(p.id) { mutableIntStateOf(1) }
    var shake by remember { mutableIntStateOf(0) }
    fun press(k: String) {
        when (k) {
            "erase" -> pin = pin.dropLast(1)
            "done" -> if (pin.length >= 4 && !working) onSubmit(pin) { msg -> error = msg; pin = ""; shake++; app.platform.sounds.play(SoundCue.ERROR) }
            else -> if (pin.length < 8) {
                pin += k
                error = null
            }
        }
    }
    // A keyboard types digits straight in.
    val router = LocalInputRouter.current
    DisposableEffect(router, p.id) {
        val before = router.textInput
        router.textInput = object : TextInput {
            override fun type(text: String) = text.filter { it.isDigit() }.forEach { press(it.toString()) }
            override fun backspace() = press("erase")
            override fun submit() = press("done")
        }
        onDispose { router.textInput = before }
    }
    InputLayer(priority = LayerPriority.DIALOG + 2, modal = true) { e ->
        when (e.action) {
            NavAction.LEFT -> if (key % 3 > 0) { key--; NavResult.MOVED } else NavResult.BLOCKED
            NavAction.RIGHT -> if (key % 3 < 2) { key++; NavResult.MOVED } else NavResult.BLOCKED
            NavAction.UP -> if (key >= 3) { key -= 3; NavResult.MOVED } else NavResult.BLOCKED
            NavAction.DOWN -> if (key < 9) { key += 3; NavResult.MOVED } else NavResult.BLOCKED
            NavAction.SELECT -> { press(KEYS[key]); NavResult.ACTIVATED }
            NavAction.CONTEXT -> { press("erase"); NavResult.ACTIVATED }
            NavAction.BACK -> { onBack(); NavResult.CONSUMED }
            else -> NavResult.CONSUMED
        }
    }
    var kick by remember { mutableStateOf(0f) }
    LaunchedEffect(shake) {
        if (shake == 0) return@LaunchedEffect
        for (x in listOf(14f, -12f, 9f, -6f, 3f, 0f)) {
            kick = x
            kotlinx.coroutines.delay(45)
        }
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        ProfileAvatar(p.avatar, if (compact) 64.dp else 88.dp)
        Spacer(Modifier.height(Space.m))
        FText(p.name, Fuse.type.title, maxLines = 1)
        FText("Enter your PIN", Fuse.type.body, color = c.textMuted, maxLines = 1)
        Spacer(Modifier.height(Space.l))
        Row(
            Modifier.graphicsLayer { translationX = kick * density },
            horizontalArrangement = Arrangement.spacedBy(Space.m),
        ) {
            val shown = maxOf(4, pin.length)
            for (i in 0 until shown) {
                val filled = i < pin.length
                Box(
                    Modifier.size(14.dp).clip(CircleShape)
                        .background(if (filled) c.text else c.text.copy(alpha = 0.14f)),
                )
            }
        }
        Spacer(Modifier.height(Space.s))
        FText(error ?: " ", Fuse.type.caption, color = c.danger, maxLines = 1)
        Spacer(Modifier.height(if (compact) Space.s else Space.l))
        val k = if (compact) 52.dp else 64.dp
        Column(verticalArrangement = Arrangement.spacedBy(Space.m)) {
            for (r in 0 until 4) {
                Row(horizontalArrangement = Arrangement.spacedBy(Space.l)) {
                    for (col in 0 until 3) {
                        val i = r * 3 + col
                        PadKey(KEYS[i], selected = i == key, size = k, enabled = KEYS[i] != "done" || pin.length >= 4, working = working && KEYS[i] == "done") {
                            key = i
                            press(KEYS[i])
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(Space.l))
        HintBar(listOf(Hint(HintButton.CONFIRM, "Press"), Hint(HintButton.OPTIONS, "Erase"), Hint(HintButton.BACK, "Back")))
    }
}

@Composable
private fun PadKey(k: String, selected: Boolean, size: Dp, enabled: Boolean, working: Boolean, onClick: () -> Unit) {
    val c = Fuse.colors
    val lift by fuselineFloat(if (selected) 1f else 0f, Fuse.motion.focusSpring(), label = "key")
    val reduced = Fuse.motion.reduced
    val bg by fuselineColor(
        when {
            k == "done" && enabled -> c.accent
            selected -> c.text
            else -> c.text.copy(alpha = 0.07f)
        },
        Fuse.motion.tween(Durations.FAST), label = "keyBg",
    )
    val fg = when {
        k == "done" && enabled -> c.onAccent
        selected -> c.ink
        else -> c.text
    }
    Box(
        Modifier.size(size)
            .graphicsLayer {
                val s = if (reduced) 1f else 1f + 0.06f * lift
                scaleX = s
                scaleY = s
                alpha = if (enabled) 1f else 0.4f
            }
            .clip(CircleShape)
            .background(bg)
            .clickable(remember { MutableInteractionSource() }, indication = null, enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        when {
            working -> Spinner(size = 22.dp, color = fg)
            k == "erase" -> FuseIcon(FuseIcons.Backspace, size = Size.iconL, tint = fg)
            k == "done" -> FuseIcon(FuseIcons.Check, size = Size.iconL, tint = fg)
            else -> FText(k, Fuse.type.title, color = fg, maxLines = 1)
        }
    }
}

/** The new profile's parts the D-pad moves between: its name, the avatars, the PIN, and Create. */
private enum class CreatePart { NAME, AVATARS, PIN, CREATE }

/**
 * A new profile: a name, one of Fuse's avatars (shown large as it is chosen), and a PIN if they
 * want one. Created on the host, then this device switches to it.
 */
@Composable
private fun CreateProfile(app: AppState, compact: Boolean, onCreated: (ProfileInfo, String?) -> Unit, onBack: () -> Unit) {
    val svc = app.store.sync.service ?: return
    val c = Fuse.colors
    val avatars = FuseAvatars.all
    var name by remember { mutableStateOf("") }
    var avatar by remember { mutableIntStateOf((0 until avatars.size).random()) }
    var pin by remember { mutableStateOf<String?>(null) }
    var part by remember { mutableStateOf(CreatePart.NAME) }
    var making by remember { mutableStateOf(false) }
    val columns = if (compact) 8 else 10
    fun askName() {
        app.textInput = TextInputSpec("Profile name", name, "Name", doneLabel = "Next") { v ->
            name = v.trim().take(24)
            if (name.isNotEmpty()) part = CreatePart.AVATARS
        }
    }
    fun askPin() {
        if (pin != null) {
            pin = null
            return
        }
        app.textInput = TextInputSpec("A PIN for ${name.ifBlank { "this profile" }}", "", "4 to 8 digits", secret = true, capitalize = false, doneLabel = "Set PIN") { v ->
            val digits = v.filter { it.isDigit() }
            if (digits.length in 4..8) pin = digits else app.toasts.show("A PIN is 4 to 8 digits", ToastKind.WARNING)
        }
    }
    fun create() {
        if (making) return
        if (name.isBlank()) {
            askName()
            return
        }
        making = true
        app.scope.launch {
            val r = svc.createProfile(name, avatars[avatar].id, pin)
            making = false
            r.onSuccess { onCreated(it, pin) }.onFailure { app.toasts.show(it.message ?: "Couldn't make the profile", ToastKind.ERROR) }
        }
    }
    InputLayer(priority = LayerPriority.DIALOG + 2, modal = true, enabled = app.textInput == null) { e ->
        when (part) {
            CreatePart.AVATARS -> when (e.action) {
                NavAction.LEFT -> if (avatar % columns > 0) { avatar--; NavResult.MOVED } else NavResult.BLOCKED
                NavAction.RIGHT -> if (avatar % columns < columns - 1 && avatar < avatars.size - 1) { avatar++; NavResult.MOVED } else NavResult.BLOCKED
                NavAction.UP -> if (avatar >= columns) { avatar -= columns; NavResult.MOVED } else { part = CreatePart.NAME; NavResult.MOVED }
                NavAction.DOWN -> if (avatar + columns < avatars.size) { avatar += columns; NavResult.MOVED } else { part = CreatePart.PIN; NavResult.MOVED }
                NavAction.SELECT -> { part = CreatePart.PIN; NavResult.ACTIVATED }
                NavAction.BACK -> { onBack(); NavResult.CONSUMED }
                else -> NavResult.CONSUMED
            }
            else -> when (e.action) {
                NavAction.UP -> { part = CreatePart.entries[maxOf(0, part.ordinal - 1)]; NavResult.MOVED }
                NavAction.DOWN -> { part = CreatePart.entries[minOf(CreatePart.entries.size - 1, part.ordinal + 1)]; NavResult.MOVED }
                NavAction.SELECT -> {
                    when (part) {
                        CreatePart.NAME -> askName()
                        CreatePart.PIN -> askPin()
                        CreatePart.CREATE -> create()
                        CreatePart.AVATARS -> Unit
                    }
                    NavResult.ACTIVATED
                }
                NavAction.BACK -> { onBack(); NavResult.CONSUMED }
                else -> NavResult.CONSUMED
            }
        }
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.widthIn(max = 720.dp)) {
        FText("New Profile", if (compact) Fuse.type.title else Fuse.type.display, maxLines = 1, modifier = Modifier.semantics { heading() })
        Spacer(Modifier.height(Space.xs))
        FText("Everyone gets their own saves, play time, favourites, Home and theme.", Fuse.type.body, color = c.textMuted, maxLines = 2, align = TextAlign.Center)
        Spacer(Modifier.height(if (compact) Space.m else Space.xl))
        Row(verticalAlignment = Alignment.CenterVertically) {
            ProfileAvatar(avatars[avatar].id, if (compact) 72.dp else 104.dp)
            Spacer(Modifier.width(Space.l))
            Column(Modifier.width(if (compact) 240.dp else 320.dp)) {
                FieldRow(
                    label = "Name", value = name.ifBlank { "Tap to type a name" }, filled = name.isNotBlank(),
                    icon = FuseIcons.Pencil, selected = part == CreatePart.NAME,
                ) { part = CreatePart.NAME; askName() }
                Spacer(Modifier.height(Space.s))
                FieldRow(
                    label = "PIN", value = if (pin != null) "Set, ${pin!!.length} digits" else "None, anyone here can open it", filled = pin != null,
                    icon = if (pin != null) FuseIcons.Lock else FuseIcons.LockOpen, selected = part == CreatePart.PIN,
                ) { part = CreatePart.PIN; askPin() }
            }
        }
        Spacer(Modifier.height(if (compact) Space.m else Space.l))
        val cell = if (compact) 36.dp else 44.dp
        Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
            for (row in avatars.indices.chunked(columns)) {
                Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                    for (i in row) {
                        val chosen = i == avatar
                        Box(
                            Modifier.clip(CircleShape).clickable(remember { MutableInteractionSource() }, indication = null) {
                                avatar = i
                                part = CreatePart.AVATARS
                            },
                        ) {
                            ProfileAvatar(
                                avatars[i].id, cell,
                                ring = if (chosen && part == CreatePart.AVATARS) c.focus else if (chosen) c.text else null,
                                dim = !chosen && part == CreatePart.AVATARS,
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(if (compact) Space.m else Space.xl))
        Row(horizontalArrangement = Arrangement.spacedBy(Space.m)) {
            FuseButton("Back", selected = false, onClick = onBack, icon = FuseIcons.ArrowLeft, kind = ButtonKind.GHOST)
            FuseButton(
                "Create Profile", selected = part == CreatePart.CREATE, onClick = { part = CreatePart.CREATE; create() },
                icon = FuseIcons.UserPlus, kind = ButtonKind.PRIMARY, loading = making,
            )
        }
    }
}

@Composable
private fun FieldRow(label: String, value: String, filled: Boolean, icon: androidx.compose.ui.graphics.vector.ImageVector, selected: Boolean, onClick: () -> Unit) {
    val c = Fuse.colors
    val bg by fuselineColor(if (selected) c.text else c.text.copy(alpha = 0.06f), Fuse.motion.tween(Durations.FAST), label = "field")
    val fg = if (selected) c.ink else c.text
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(Fuse.geometry.control)).background(bg)
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(horizontal = Space.l, vertical = Space.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            FText(label, Fuse.type.caption, color = if (selected) fg.copy(alpha = 0.7f) else c.textMuted, maxLines = 1)
            FText(value, Fuse.type.bodyStrong, color = if (filled || selected) fg else c.textMuted, maxLines = 1)
        }
        FuseIcon(icon, size = Size.iconM, tint = fg.copy(alpha = 0.8f))
    }
}
