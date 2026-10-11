package io.github.matiyaaa.fuse.ui.shell.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as GeoSize
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import io.github.matiyaaa.fuse.model.GlyphStyle
import io.github.matiyaaa.fuse.model.InputProfile
import io.github.matiyaaa.fuse.model.NavAction
import io.github.matiyaaa.fuse.model.PadButton
import io.github.matiyaaa.fuse.ui.designsystem.components.ButtonKind
import io.github.matiyaaa.fuse.ui.designsystem.components.FText
import io.github.matiyaaa.fuse.ui.designsystem.components.FuseButton
import io.github.matiyaaa.fuse.ui.designsystem.components.Panel
import io.github.matiyaaa.fuse.ui.designsystem.components.ProgressBar
import io.github.matiyaaa.fuse.ui.designsystem.components.SectionLabel
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcon
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.designsystem.input.LocalInputRouter
import io.github.matiyaaa.fuse.ui.designsystem.input.actionFor
import io.github.matiyaaa.fuse.ui.designsystem.input.withMapping
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.Space
import io.github.matiyaaa.fuse.ui.fuseline.Durations
import io.github.matiyaaa.fuse.ui.fuseline.Swap
import io.github.matiyaaa.fuse.ui.fuseline.fadeIn
import io.github.matiyaaa.fuse.ui.fuseline.fadeOut
import io.github.matiyaaa.fuse.ui.fuseline.togetherWith
import io.github.matiyaaa.fuse.ui.shell.app.AppState
import kotlinx.coroutines.delay

/** One question guided setup asks, in the order it asks them. */
internal sealed interface GuideStep {
    /** Two or more controllers: which one moves around Fuse. */
    data object Choose : GuideStep

    /** The right face button, which tells how the pad reports its buttons. */
    data object RightButton : GuideStep

    /** The button that says yes. */
    data object Confirm : GuideStep

    /** The button for one of the remaining actions, by its place in [guidedActions]. */
    data class Map(val index: Int) : GuideStep

    /** Every button tried at the end, with nothing else happening. */
    data object Try : GuideStep
}

/** The actions guided setup asks for after the layout (Confirm and Back come from the layout itself). */
internal val guidedActions: List<Pair<NavAction, String>> = listOf(
    NavAction.CONTEXT to "Options",
    NavAction.SEARCH to "Search",
    NavAction.QUICK_MENU to "Quick menu",
    NavAction.PREVIOUS_SECTION to "Previous section",
    NavAction.NEXT_SECTION to "Next section",
    NavAction.PAGE_UP to "Page up",
    NavAction.PAGE_DOWN to "Page down",
    NavAction.HOME to "Home",
)

/** Every step in order, given how many controllers are connected. */
internal fun guideSteps(controllers: Int): List<GuideStep> = buildList {
    if (controllers > 1) add(GuideStep.Choose)
    add(GuideStep.RightButton)
    add(GuideStep.Confirm)
    guidedActions.indices.forEach { add(GuideStep.Map(it)) }
    add(GuideStep.Try)
}

private val faceButtons = setOf(PadButton.A, PadButton.B, PadButton.X, PadButton.Y)
private val directions = setOf(
    PadButton.DPAD_UP, PadButton.DPAD_DOWN, PadButton.DPAD_LEFT, PadButton.DPAD_RIGHT,
    PadButton.RSTICK_UP, PadButton.RSTICK_DOWN, PadButton.RSTICK_LEFT, PadButton.RSTICK_RIGHT,
)

/** What one press does to the profile at [step]: the new profile (or null to keep it) and a notice. */
internal data class GuideAnswer(val profile: InputProfile?, val advance: Boolean, val notice: String? = null)

/**
 * The pure part of guided setup: what a press (already let go, held for less than the time that
 * leaves) does at [step]. Directions keep an action as it is; a button only one action could use
 * stays where it is, with a word why.
 */
internal fun guideAnswer(step: GuideStep, profile: InputProfile, button: PadButton, right: PadButton?, buttons: List<PadButton>): GuideAnswer = when (step) {
    GuideStep.Choose -> GuideAnswer(null, advance = true)
    GuideStep.RightButton ->
        if (button in faceButtons) GuideAnswer(null, advance = true)
        else GuideAnswer(null, advance = false, notice = "That isn't one of the four face buttons. Press the one on the right.")
    GuideStep.Confirm ->
        if (button == PadButton.A || button == PadButton.B) GuideAnswer(detectedProfile(profile, right ?: PadButton.B, button), advance = true)
        else GuideAnswer(null, advance = false, notice = "Use the button you press to say yes, usually the bottom or right face button.")
    is GuideStep.Map -> {
        val action = guidedActions[step.index].first
        when {
            button in directions -> GuideAnswer(null, advance = true)
            button !in buttons -> GuideAnswer(null, advance = false, notice = "That one can't be changed. Try another button, or a direction to keep it as it is.")
            else -> {
                val next = profile.withMapping(button, action, buttons)
                if (next == null) GuideAnswer(null, advance = false, notice = "That button is the only one for Confirm or Back. Choose another.")
                else GuideAnswer(next, advance = true)
            }
        }
    }
    GuideStep.Try -> GuideAnswer(null, advance = false)
}

/**
 * Guided controller setup: one question at a time (which controller, the right face button, the
 * confirm button, then each action), every answer saved as it is given. While it runs every press
 * comes only here; holding any button for [LEAVE_MS] leaves, so nobody is ever stuck in it, and the
 * buttons under the pad do the same for a mouse or a finger.
 */
@Composable
internal fun GuidedControls(app: AppState, buttons: List<PadButton>, label: (PadButton, Boolean) -> String, startRequests: Int, showPad: Boolean = true, onRunning: (Boolean) -> Unit) {
    val router = LocalInputRouter.current
    val prefs by app.store.prefs.collectAsState()
    val controllers by router.controllers.collectAsState()
    val lastController by router.lastController.collectAsState()
    val sticks by router.sticks.collectAsState()
    var running by remember { mutableStateOf(false) }
    var stepIndex by remember { mutableStateOf(0) }
    var right by remember { mutableStateOf<PadButton?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var held by remember { mutableStateOf<PadButton?>(null) }
    val holdProgress = remember { mutableFloatStateOf(0f) }
    val pressed = remember { mutableStateListOf<PadButton>() }
    // Fixed when it starts, so a controller arriving halfway doesn't move the questions around.
    var steps by remember { mutableStateOf(guideSteps(controllers.size)) }
    val step = steps.getOrNull(stepIndex)
    val latestStep by rememberUpdatedState(step)
    val nintendo = prefs.input.glyphs == GlyphStyle.NINTENDO

    fun setRunning(on: Boolean) {
        running = on
        onRunning(on)
    }

    fun start() {
        steps = guideSteps(controllers.size)
        stepIndex = 0
        right = null
        notice = null
        setRunning(true)
    }

    fun stop(finished: Boolean) {
        setRunning(false)
        held = null
        app.toasts.show(if (finished) "Your controller is set up" else "Guided setup stopped. Every answer you gave is kept")
        if (finished) app.platform.haptics.confirm()
    }

    fun advance() {
        notice = null
        if (stepIndex + 1 >= steps.size) stop(finished = true) else stepIndex++
    }

    fun answer(button: PadButton) {
        val s = latestStep ?: return
        val current = app.store.prefs.value.input
        if (s == GuideStep.Choose) {
            lastController?.let { name -> app.store.updatePrefs { it.copy(input = it.input.copy(controller = name)) } }
        }
        if (s == GuideStep.RightButton && button in faceButtons) right = button
        val a = guideAnswer(s, current, button, right, buttons)
        a.profile?.let { p -> app.store.updatePrefs { it.copy(input = p) } }
        notice = a.notice
        if (a.advance) advance()
    }

    DisposableEffect(running) {
        if (running) {
            router.exclusive = { button, down ->
                if (down) {
                    if (button !in pressed) pressed.add(button)
                    if (held == null) held = button
                } else {
                    pressed.remove(button)
                    if (held == button) {
                        // A press let go before the time that leaves is an answer.
                        if (holdProgress.floatValue < 1f) answer(button)
                        held = null
                    }
                }
            }
        }
        onDispose { if (running) router.exclusive = null }
    }
    // Holding any button fills the ring; full, guided setup ends with every answer so far kept.
    LaunchedEffect(held, running) {
        holdProgress.floatValue = 0f
        if (!running || held == null) return@LaunchedEffect
        delay(HOLD_SHOWS_MS)
        val steps2 = 40
        for (i in 1..steps2) {
            holdProgress.floatValue = i / steps2.toFloat()
            delay((LEAVE_MS - HOLD_SHOWS_MS) / steps2)
        }
        held = null
        stop(finished = latestStep == GuideStep.Try)
    }
    DisposableEffect(Unit) { onDispose { if (running) onRunning(false) } }
    // Confirm on the page (or Start under it) begins.
    LaunchedEffect(startRequests) { if (startRequests > 0 && !running) start() }

    val c = Fuse.colors
    val motion = Fuse.motion
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(Space.xl)) {
        Panel(Modifier.weight(if (showPad) 0.52f else 1f).fillMaxHeight()) {
            Column(Modifier.fillMaxSize().padding(Space.xl)) {
                if (!running) {
                    SectionLabel("Guided", icon = FuseIcons.Sparkles, rule = true)
                    Spacer(Modifier.height(Space.m))
                    FText("Set up your controller one button at a time", Fuse.type.title, maxLines = 2)
                    Spacer(Modifier.height(Space.s))
                    FText(
                        buildString {
                            append("Fuse asks for each button and you press it. ")
                            if (controllers.size > 1) append("With ${controllers.size} controllers connected, the first question is which one moves around Fuse. ")
                            append("A direction keeps a button as it is. Hold any button for five seconds to stop at any time.")
                        },
                        Fuse.type.body, color = c.textMuted, maxLines = 5,
                    )
                    Spacer(Modifier.weight(1f))
                    FuseButton("Start", selected = true, onClick = ::start, kind = ButtonKind.PRIMARY, icon = FuseIcons.Play)
                } else {
                    val number = stepIndex + 1
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SectionLabel("Step $number of ${steps.size}", icon = FuseIcons.Gamepad, modifier = Modifier.weight(1f))
                        HoldRing(holdProgress.floatValue)
                    }
                    Spacer(Modifier.height(Space.s))
                    ProgressBar(number / steps.size.toFloat(), Modifier.fillMaxWidth())
                    Spacer(Modifier.height(Space.l))
                    Swap(step, transitionSpec = { fadeIn(motion.fade(Durations.FAST)) togetherWith fadeOut(motion.fade(Durations.INSTANT)) }, label = "guide") { s ->
                        Column(Modifier.fillMaxWidth()) {
                            FText(
                                when (s) {
                                    GuideStep.Choose -> "Press any button on the controller you want to use"
                                    GuideStep.RightButton -> "Press the right face button"
                                    GuideStep.Confirm -> "Press the button you confirm with"
                                    is GuideStep.Map -> "Press the button for ${guidedActions[s.index].second}"
                                    GuideStep.Try -> "Try every button"
                                    null -> ""
                                },
                                Fuse.type.title, maxLines = 2,
                            )
                            Spacer(Modifier.height(Space.s))
                            FText(
                                when (s) {
                                    GuideStep.Choose -> "Only that one moves around Fuse. Any controller does again while it is away."
                                    GuideStep.RightButton -> "Of the four buttons on the right of the pad, the one furthest right. It tells Fuse how your pad is labelled."
                                    GuideStep.Confirm -> "Usually the bottom one, or the right one on Nintendo-style pads. Back goes on the other."
                                    is GuideStep.Map -> "Or press a direction to keep it where it is."
                                    GuideStep.Try -> "Each one lights up on the pad and does nothing else. Hold any button to finish."
                                    null -> ""
                                },
                                Fuse.type.body, color = c.textMuted, maxLines = 3,
                            )
                            if (s is GuideStep.Map) {
                                Spacer(Modifier.height(Space.m))
                                val now = buttons.filter { prefs.input.actionFor(it) == guidedActions[s.index].first }
                                FText(if (now.isEmpty()) "Not on any button now" else "Now on " + now.joinToString(", ") { label(it, nintendo) }, Fuse.type.label, color = c.textFaint, maxLines = 1)
                            }
                            if (s == GuideStep.Choose) {
                                Spacer(Modifier.height(Space.m))
                                for (name in controllers) {
                                    Row(Modifier.padding(vertical = Space.xxs), verticalAlignment = Alignment.CenterVertically) {
                                        FuseIcon(if (name == lastController) FuseIcons.CircleCheck else FuseIcons.Gamepad, tint = if (name == lastController) c.accent else c.textMuted, size = GLYPH)
                                        Spacer(Modifier.width(Space.s))
                                        FText(name, Fuse.type.label, color = if (name == lastController) c.text else c.textMuted, maxLines = 1)
                                    }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    notice?.let {
                        FText(it, Fuse.type.caption, color = c.warning, maxLines = 2)
                        Spacer(Modifier.height(Space.s))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                        if (step is GuideStep.Map) FuseButton("Keep as it is", selected = false, onClick = ::advance, icon = FuseIcons.SkipForward)
                        if (step == GuideStep.Try) FuseButton("Finish", selected = true, kind = ButtonKind.PRIMARY, onClick = { stop(finished = true) }, icon = FuseIcons.Check)
                        else FuseButton("Stop", selected = false, onClick = { stop(finished = false) }, icon = FuseIcons.Close)
                    }
                }
            }
        }
        if (showPad) Panel(Modifier.weight(0.48f).fillMaxHeight()) {
            Column(Modifier.fillMaxSize().padding(Space.l), horizontalAlignment = Alignment.CenterHorizontally) {
                SectionLabel("Your controller", Modifier.align(Alignment.Start), icon = FuseIcons.Gamepad, rule = true)
                val marked = (step as? GuideStep.Map)?.let { m ->
                    buttons.filter { prefs.input.actionFor(it) == guidedActions[m.index].first }.toSet()
                }.orEmpty()
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    PadDiagram(pressed.toSet(), marked, nintendo, Modifier.fillMaxWidth(), sticks = sticks)
                }
                FText(
                    lastController?.let { "Last pressed on $it" } ?: if (nintendo) "Laid out as a Nintendo pad" else "Laid out as an Xbox pad",
                    Fuse.type.caption, color = c.textFaint, maxLines = 1,
                )
            }
        }
    }
}

/** A small ring that fills while a button is held: full, guided setup ends. */
@Composable
private fun HoldRing(progress: Float) {
    val c = Fuse.colors
    val track = c.textFaint.copy(alpha = 0.25f)
    val accent = c.accent
    Row(verticalAlignment = Alignment.CenterVertically) {
        FText(if (progress > 0f) "Keep holding to stop" else "Hold any button to stop", Fuse.type.caption, color = c.textFaint, maxLines = 1)
        Spacer(Modifier.width(Space.s))
        Box(
            Modifier.size(GLYPH).drawBehind {
                val w = 2.4f * density
                val inset = w / 2
                val arc = GeoSize(size.width - w, size.height - w)
                drawArc(track, 0f, 360f, false, Offset(inset, inset), arc, style = Stroke(w))
                if (progress > 0f) drawArc(accent, -90f, 360f * progress, false, Offset(inset, inset), arc, style = Stroke(w, cap = StrokeCap.Round))
            },
        )
    }
}

private val GLYPH = 18.dp

/** A press held this long starts to show the ring; held [LEAVE_MS], guided setup ends. */
private const val HOLD_SHOWS_MS = 600L
internal const val LEAVE_MS = 5_000L

