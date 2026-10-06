package io.github.matiyaaa.fuse.ui.shell.sync

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.relocation.BringIntoViewRequester
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
 * "Who's playing?": everyone with a profile (this device's own, or on its Fuse Sync host) as a card
 * with their avatar, and a card to add someone. Choosing one switches this device to them in place (their library, saves,
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
    // Asked at startup with nobody chosen yet, it waits for a choice; but never on a host that isn't
    // answering: Fuse is used as it is meanwhile, and the question can be asked again from the top line.
    val hostAway = status is SyncStatus.Offline || status is SyncStatus.Connecting || status is SyncStatus.NeedsAttention
    val closable = mode != WhoMode.STARTUP || active != null || hostAway
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
                app.arrivalGrand = false
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
            // Short and wide (a 4:3 handheld, a phone on its side): the PIN pad sits beside the person.
            val short = maxHeight < 440.dp && maxWidth > maxHeight
            // Centred when it fits; scrolled, with the selection kept in view, when it doesn't (a
            // small 4:3 handheld): nothing is ever out of reach below the screen.
            val scroll = rememberScrollState()
            val room = maxHeight
            val wide = maxWidth
            LaunchedEffect(step) { scroll.scrollTo(0) }
            if (step == WhoStep.Create) {
                // Its own layout: the buttons stay on screen and the rest scrolls above them.
                CreateProfile(
                    app,
                    onCreated = { p, pin ->
                        // The very first profile here arrives with the grand welcome.
                        if (profiles.none { it.id != p.id }) app.arrivalGrand = true
                        index = profiles.size
                        step = WhoStep.People
                        switchTo(p, pin)
                    },
                    onBack = { if (mode == WhoMode.ADD) close() else step = WhoStep.People },
                )
                return@BoxWithConstraints
            }
            Box(Modifier.fillMaxSize().verticalScroll(scroll)) {
            Box(Modifier.fillMaxWidth().heightIn(min = room).padding(vertical = Space.m), contentAlignment = Alignment.Center) {
            when (val s = step) {
                WhoStep.People -> People(
                    app, profiles, active, status, index, busy, compact, short, wide,
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
                    app, s.profile, busy == s.profile.id, compact, short,
                    onSubmit = { pin, wrong -> switchTo(s.profile, pin, wrong) },
                    onBack = { step = WhoStep.People },
                )
                WhoStep.Create -> Unit
            }
            }
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
    short: Boolean,
    width: Dp,
    onIndex: (Int) -> Unit,
    onChoose: (Int) -> Unit,
    onBack: () -> Unit,
    onAdd: () -> Unit,
    closable: Boolean,
) {
    val c = Fuse.colors
    val offline = status is SyncStatus.Offline || status is SyncStatus.Connecting
    val hosted = status !is SyncStatus.Off && status !is SyncStatus.NotSetUp
    val count = profiles.size + if (offline) 0 else 1
    val card = when {
        short -> 88.dp
        compact -> 120.dp
        else -> 168.dp
    }
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
        if (!short) {
            SyncMark(if (compact) 40.dp else 52.dp)
            Spacer(Modifier.height(if (compact) Space.m else Space.l))
        }
        FText("Who's playing?", if (compact) Fuse.type.title else Fuse.type.hero, align = TextAlign.Center, maxLines = 1, modifier = Modifier.semantics { heading() })
        // Offline, what that means always shows; otherwise the line goes where there is no room for it.
        if (!short || offline) {
            Spacer(Modifier.height(Space.xs))
            FText(
                when {
                    offline -> "The host isn't answering. Profiles without a PIN switch now and catch up when it's back."
                    hosted -> "Your games, saves, play time and settings follow you to every device."
                    else -> "Everyone gets their own saves, play time, favourites and theme here."
                },
                Fuse.type.body, color = c.textMuted, align = TextAlign.Center, maxLines = 2, modifier = Modifier.widthIn(max = 560.dp),
            )
        }
        Spacer(Modifier.height(if (short) Space.m else if (compact) Space.l else Space.xxl))
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
            val requesters = remember(count) { List(count) { BringIntoViewRequester() } }
            LaunchedEffect(sel, count) { requesters.getOrNull(sel)?.bringIntoView() }
            Column(verticalArrangement = Arrangement.spacedBy(Space.l), horizontalAlignment = Alignment.CenterHorizontally) {
                for (row in rows) {
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.l)) {
                        for (i in row) {
                            val p = profiles.getOrNull(i)
                            Box(Modifier.reveal(cards, i).bringIntoViewRequester(requesters[i])) {
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
            if (closable) add(Hint(HintButton.BACK, if (active == null) "Not Now" else "Back"))
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
private fun PinPad(app: AppState, p: ProfileInfo, working: Boolean, compact: Boolean, short: Boolean, onSubmit: (String, (String) -> Unit) -> Unit, onBack: () -> Unit) {
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
    // Who it is, the dots filled so far and what went wrong; then the keys.
    val person: @Composable () -> Unit = {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            ProfileAvatar(p.avatar, if (compact) 64.dp else 88.dp)
            Spacer(Modifier.height(Space.m))
            FText(p.name, Fuse.type.title, maxLines = 1)
            FText("Enter your PIN", Fuse.type.body, color = c.textMuted, maxLines = 1)
            Spacer(Modifier.height(if (short) Space.m else Space.l))
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
            FText(error ?: " ", Fuse.type.caption, color = c.danger, maxLines = 2, align = TextAlign.Center)
        }
    }
    val keys: @Composable () -> Unit = {
        val k = when {
            short -> 44.dp
            compact -> 52.dp
            else -> 64.dp
        }
        Column(verticalArrangement = Arrangement.spacedBy(if (short) Space.s else Space.m)) {
            for (r in 0 until 4) {
                Row(horizontalArrangement = Arrangement.spacedBy(if (short) Space.m else Space.l)) {
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
    }
    val hints = listOf(Hint(HintButton.CONFIRM, "Press"), Hint(HintButton.OPTIONS, "Erase"), Hint(HintButton.BACK, "Back"))
    if (short) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.xxl)) {
                Box(Modifier.widthIn(max = 220.dp)) { person() }
                keys()
            }
            Spacer(Modifier.height(Space.m))
            HintBar(hints)
        }
    } else {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            person()
            Spacer(Modifier.height(if (compact) Space.s else Space.l))
            keys()
            Spacer(Modifier.height(Space.l))
            HintBar(hints)
        }
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

/** A new profile: a name, one of Fuse's pictures and a PIN if they want one, in [ProfileEditor]. */
@Composable
private fun CreateProfile(app: AppState, onCreated: (ProfileInfo, String?) -> Unit, onBack: () -> Unit) {
    val svc = app.store.sync.service ?: return
    ProfileEditor(
        app,
        title = "New Profile",
        subtitle = "Everyone gets their own saves, play time, favourites, Home and theme.",
        submitLabel = "Create Profile",
        submitIcon = FuseIcons.UserPlus,
        onSubmit = { name, avatar, pin ->
            val digits = (pin as? PinChoice.Set)?.digits
            svc.createProfile(name, avatar, digits).fold({ onCreated(it, digits); null }, { it.message ?: "Couldn't make the profile" })
        },
        onBack = onBack,
    )
}
