package io.github.matiyaaa.fuse.ui.shell.onboarding

import androidx.compose.runtime.DisposableEffect
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import io.github.matiyaaa.fuse.model.NavAction
import io.github.matiyaaa.fuse.ui.designsystem.components.ButtonKind
import io.github.matiyaaa.fuse.ui.designsystem.components.FText
import io.github.matiyaaa.fuse.ui.designsystem.components.FuseButton
import io.github.matiyaaa.fuse.ui.designsystem.components.Hint
import io.github.matiyaaa.fuse.ui.designsystem.components.SectionLabel
import io.github.matiyaaa.fuse.ui.designsystem.icons.HintButton
import io.github.matiyaaa.fuse.ui.designsystem.input.InputLayer
import io.github.matiyaaa.fuse.ui.designsystem.input.NavEvent
import io.github.matiyaaa.fuse.ui.designsystem.input.NavResult
import io.github.matiyaaa.fuse.ui.designsystem.sound.SoundCue
import io.github.matiyaaa.fuse.ui.designsystem.theme.Durations
import io.github.matiyaaa.fuse.ui.designsystem.theme.Easings
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.Space
import io.github.matiyaaa.fuse.ui.shell.app.AppState
import io.github.matiyaaa.fuse.ui.shell.app.FocusZone

/** A button at the bottom of a step. */
data class StepAction(val label: String, val primary: Boolean = false, val enabled: Boolean = true, val run: () -> Unit)

/**
 * What a step shows. [content] draws the step's body; [actions] are its buttons (the first primary
 * one is selected on arrival); [onInput] lets a step handle the D-pad itself (lists, controller test)
 * before the button row does.
 */
class Step(
    val id: String,
    val eyebrow: String,
    val title: String,
    val body: String,
    val optional: Boolean = false,
    val actions: List<StepAction>,
    val onInput: ((NavEvent) -> NavResult)? = null,
    val content: (@Composable BoxScope.() -> Unit)? = null,
)

@Stable
class OnboardingState {
    var index by mutableIntStateOf(0)
    var forward by mutableStateOf(true)
    var button by mutableIntStateOf(0)
    var total by mutableIntStateOf(1)

    /** Moves to the next step (steps call this from their buttons). */
    fun next() {
        if (index >= total - 1) return
        forward = true
        index++
        button = 0
    }
}

/**
 * First-run setup. It reads like a console being switched on: the Fuse mark lights up, then each
 * step slides in from the right with one clear question. A burning line along the bottom shows how
 * far along you are. Every online or optional step can be skipped, and setup can be run again from
 * Settings, About.
 */
@Composable
fun OnboardingScreen(app: AppState) {
    val state = remember { OnboardingState() }
    val steps = rememberSteps(app, state)
    state.total = steps.size
    val step = steps[state.index.coerceIn(0, steps.lastIndex)]
    val motion = Fuse.motion

    fun go(delta: Int) {
        val next = (state.index + delta).coerceIn(0, steps.lastIndex)
        if (next == state.index) return
        state.forward = delta > 0
        state.index = next
        state.button = 0
        app.platform.sounds.play(if (delta > 0) SoundCue.SELECT else SoundCue.BACK)
    }

    // A rehearsal left any other way (the Home button) still puts the preferences back.
    DisposableEffect(Unit) {
        onDispose {
            app.dev.rehearsalPrefs?.let { before ->
                app.dev.rehearsalPrefs = null
                app.store.updatePrefs { before }
            }
        }
    }

    LaunchedEffect(step.id) {
        app.hero = null
        app.hints = buildList {
            add(Hint(HintButton.CONFIRM, "Choose"))
            if (state.index > 0 || app.dev.rehearsing) add(Hint(HintButton.BACK, if (state.index > 0) "Back" else "Leave"))
            if (step.optional || app.dev.skipRequired) add(Hint(HintButton.MENU, "Skip"))
        }
    }

    InputLayer(enabled = !app.overlayOpen) { e ->
        step.onInput?.invoke(e)?.let { if (it != NavResult.IGNORED) return@InputLayer it }
        val buttons = step.actions
        when (e.action) {
            NavAction.LEFT -> if (state.button > 0) { state.button--; NavResult.MOVED } else NavResult.BLOCKED
            NavAction.RIGHT -> if (state.button < buttons.lastIndex) { state.button++; NavResult.MOVED } else NavResult.BLOCKED
            NavAction.SELECT -> {
                val b = buttons.getOrNull(state.button) ?: return@InputLayer NavResult.BLOCKED
                // Developer options may press a button a required step keeps off.
                if (!b.enabled && !app.dev.skipRequired) return@InputLayer NavResult.BLOCKED
                b.run()
                NavResult.ACTIVATED
            }
            NavAction.BACK -> when {
                state.index > 0 -> { go(-1); NavResult.CONSUMED }
                // A rehearsal can be left from its first step.
                app.dev.rehearsing -> { app.endRehearsal(); NavResult.CONSUMED }
                else -> NavResult.BLOCKED
            }
            NavAction.QUICK_MENU -> if (step.optional || app.dev.skipRequired) { go(1); NavResult.ACTIVATED } else NavResult.BLOCKED
            // Tabs and sections stay out of the way during setup.
            NavAction.UP, NavAction.DOWN, NavAction.NEXT_SECTION, NavAction.PREVIOUS_SECTION, NavAction.SEARCH, NavAction.CONTEXT -> NavResult.BLOCKED
            else -> NavResult.IGNORED
        }
    }

    Box(Modifier.fillMaxSize()) {
        AnimatedContent(
            targetState = state.index,
            transitionSpec = {
                val dir = if (state.forward) 1 else -1
                (slideInHorizontally(motion.tween(Durations.SLOW, Easings.Enter)) { (it * motion.slideFraction * 2f * dir).toInt() } + fadeIn(motion.fade(Durations.SLOW))) togetherWith
                    (slideOutHorizontally(motion.tween(Durations.BASE, Easings.Exit)) { (-it * motion.slideFraction * dir).toInt() } + fadeOut(motion.fade(Durations.FAST)))
            },
            label = "onboarding",
        ) { index ->
            val s = steps[index.coerceIn(0, steps.lastIndex)]
            StepView(app, s, state, isCurrent = index == state.index)
        }
        FuseLine(
            progress = state.index / (steps.size - 1).toFloat(),
            label = "${state.index + 1} of ${steps.size}",
            modifier = Modifier.align(Alignment.BottomStart).padding(horizontal = Space.gutter, vertical = Space.xl),
        )
    }
}

@Composable
private fun StepView(app: AppState, step: Step, state: OnboardingState, isCurrent: Boolean) {
    val c = Fuse.colors
    Row(Modifier.fillMaxSize().padding(horizontal = Space.gutter, vertical = Space.x3)) {
        Column(Modifier.weight(1f).widthIn(max = 560.dp), verticalArrangement = Arrangement.Center) {
            SectionLabel(step.eyebrow + if (step.optional) "  ·  Optional" else "")
            Spacer(Modifier.height(Space.m))
            FText(step.title, Fuse.type.hero, maxLines = 3)
            Spacer(Modifier.height(Space.l))
            FText(step.body, Fuse.type.body, color = c.textMuted, maxLines = 8)
            Spacer(Modifier.height(Space.xl))
            Row(horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                step.actions.forEachIndexed { i, a ->
                    FuseButton(
                        a.label,
                        selected = isCurrent && i == state.button && app.focusZone == FocusZone.CONTENT,
                        onClick = { state.button = i; if (a.enabled || app.dev.skipRequired) a.run() },
                        kind = if (a.primary) ButtonKind.PRIMARY else ButtonKind.SECONDARY,
                        enabled = a.enabled || app.dev.skipRequired,
                    )
                }
            }
        }
        if (step.content != null) {
            Spacer(Modifier.widthIn(min = Space.xxl))
            Box(Modifier.weight(1f).fillMaxSize(), contentAlignment = Alignment.CenterStart, content = step.content)
        }
    }
}

/**
 * Setup progress drawn as a lit fuse: the burnt part glows in the accent colour and a spark rides
 * its end.
 */
@Composable
fun FuseLine(progress: Float, label: String, modifier: Modifier = Modifier) {
    val c = Fuse.colors
    val p by animateFloatAsState(progress.coerceIn(0f, 1f), Fuse.motion.tween(Durations.DELIBERATE), label = "fuse")
    val flicker = remember { Animatable(0.8f) }
    val ambient = Fuse.motion.ambient
    LaunchedEffect(Unit) {
        if (!ambient) return@LaunchedEffect
        while (true) {
            flicker.animateTo(1f, androidx.compose.animation.core.tween(380))
            flicker.animateTo(0.75f, androidx.compose.animation.core.tween(420))
        }
    }
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.weight(1f).height(24.dp)) {
            val y = size.height / 2
            val stroke = 2.dp.toPx()
            drawLine(c.text.copy(alpha = 0.14f), Offset(0f, y), Offset(size.width, y), stroke, StrokeCap.Round)
            val x = size.width * p
            drawLine(
                Brush.horizontalGradient(listOf(c.accent.copy(alpha = 0.2f), c.accent), startX = 0f, endX = x.coerceAtLeast(1f)),
                Offset(0f, y), Offset(x, y), stroke * 1.4f, StrokeCap.Round,
            )
            val r = 10.dp.toPx() * flicker.value
            drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.9f), c.accent.copy(alpha = 0.6f), Color.Transparent), center = Offset(x, y), radius = r), radius = r, center = Offset(x, y))
        }
        Spacer(Modifier.widthIn(min = Space.l))
        FText(label, Fuse.type.label, color = c.textMuted)
    }
}
