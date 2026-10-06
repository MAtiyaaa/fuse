package io.github.matiyaaa.fuse.ui.shell.onboarding

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.matiyaaa.fuse.model.NavAction
import io.github.matiyaaa.fuse.ui.designsystem.components.ButtonKind
import io.github.matiyaaa.fuse.ui.designsystem.components.FText
import io.github.matiyaaa.fuse.ui.designsystem.components.FuseButton
import io.github.matiyaaa.fuse.ui.designsystem.components.Hint
import io.github.matiyaaa.fuse.ui.designsystem.effects.fuseClickable
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcon
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.designsystem.effects.drawGrain
import io.github.matiyaaa.fuse.ui.designsystem.icons.HintButton
import io.github.matiyaaa.fuse.ui.designsystem.input.InputLayer
import io.github.matiyaaa.fuse.ui.designsystem.input.NavEvent
import io.github.matiyaaa.fuse.ui.designsystem.input.NavResult
import io.github.matiyaaa.fuse.ui.designsystem.shape.PillShape
import io.github.matiyaaa.fuse.ui.designsystem.sound.SoundCue
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.Size
import io.github.matiyaaa.fuse.ui.designsystem.theme.Space
import io.github.matiyaaa.fuse.ui.fuseline.Curves
import io.github.matiyaaa.fuse.ui.fuseline.Durations
import io.github.matiyaaa.fuse.ui.fuseline.Enter
import io.github.matiyaaa.fuse.ui.fuseline.Exit
import io.github.matiyaaa.fuse.ui.fuseline.FuselineValue
import io.github.matiyaaa.fuse.ui.fuseline.RepeatMode
import io.github.matiyaaa.fuse.ui.fuseline.SizeTransform
import io.github.matiyaaa.fuse.ui.fuseline.Swap
import io.github.matiyaaa.fuse.ui.fuseline.fadeIn
import io.github.matiyaaa.fuse.ui.fuseline.fadeOut
import io.github.matiyaaa.fuse.ui.fuseline.fuselineColor
import io.github.matiyaaa.fuse.ui.fuseline.fuselineFloat
import io.github.matiyaaa.fuse.ui.fuseline.fuselineScrollTo
import io.github.matiyaaa.fuse.ui.fuseline.infiniteRepeatable
import io.github.matiyaaa.fuse.ui.fuseline.rememberLoopClock
import io.github.matiyaaa.fuse.ui.fuseline.slideInHorizontally
import io.github.matiyaaa.fuse.ui.fuseline.slideOutHorizontally
import io.github.matiyaaa.fuse.ui.fuseline.togetherWith
import io.github.matiyaaa.fuse.ui.fuseline.tween
import io.github.matiyaaa.fuse.ui.shell.app.AppState
import io.github.matiyaaa.fuse.ui.shell.app.FocusZone
import io.github.matiyaaa.fuse.ui.shell.app.FuseMark

/** A button at the bottom of a step. [note] says why it is off, under the buttons, when it is. */
data class StepAction(val label: String, val primary: Boolean = false, val enabled: Boolean = true, val note: String? = null, val run: () -> Unit)

/**
 * What a step shows. [content] draws the step's picture on its stage (steps without one show their
 * [icon], lit); [actions] are its buttons (the first primary one is selected on arrival); [onInput]
 * lets a step handle the D-pad itself (lists, controller test) before the button row does.
 * [chapter] groups steps in the rail at the side, and [footnote] is a quiet line under the buttons.
 */
class Step(
    val id: String,
    val eyebrow: String,
    val title: String,
    val body: String,
    val optional: Boolean = false,
    val actions: List<StepAction>,
    val onInput: ((NavEvent) -> NavResult)? = null,
    val icon: ImageVector = FuseIcons.Sparkles,
    val chapter: String = Chapters.START,
    val footnote: String? = null,
    val content: (@Composable BoxScope.() -> Unit)? = null,
)

/** The parts of setup, in order, as the rail lists them. */
object Chapters {
    const val START = "Start"
    const val GAMES = "Your games"
    const val CONNECT = "Connect"
    const val YOURS = "Make it yours"
    const val READY = "Ready"
}

@Stable
class OnboardingState {
    var index by mutableIntStateOf(0)
    var forward by mutableStateOf(true)
    var button by mutableIntStateOf(0)
    var total by mutableIntStateOf(1)

    /** Where each of the step's buttons sits (in the window), so Up and Down move between lines of them. */
    internal val buttonBounds = mutableMapOf<Int, androidx.compose.ui.geometry.Rect>()

    /**
     * The button above ([up]) or below the selected one, when the buttons wrap onto more than one
     * line: the nearest on the closest line in that direction. Null when there is no line there.
     */
    internal fun buttonToward(up: Boolean, count: Int): Int? {
        val from = buttonBounds[button] ?: return null
        val others = (0 until count).mapNotNull { i -> buttonBounds[i]?.let { i to it } }.filter { (i, r) ->
            i != button && if (up) r.center.y < from.top else r.center.y > from.bottom
        }
        if (others.isEmpty()) return null
        val lineY = if (up) others.maxOf { it.second.center.y } else others.minOf { it.second.center.y }
        return others.filter { kotlin.math.abs(it.second.center.y - lineY) < from.height / 2 }
            .minByOrNull { kotlin.math.abs(it.second.center.x - from.center.x) }?.first
    }

    /** Moves to the next step (steps call this from their buttons). */
    fun next() {
        if (index >= total - 1) return
        forward = true
        index++
        button = 0
    }
}

/**
 * First-run setup. It reads like a console being switched on: each step slides in with one clear
 * question on the left and a picture of what it is about on a lit stage to the right, while a rail
 * at the side shows the chapters of setup with the step you are on burning along it. A lit fuse
 * along the bottom shows how far along you are. Every online or optional step can be skipped, and
 * setup can be run again from Settings, About.
 *
 * It fills any window: on a wide screen the rail, the words and the stage share the width (the
 * whole thing centred and capped so a TV or an ultrawide never stretches it thin); on a narrow or
 * portrait screen the stage moves under the words and the page scrolls, so nothing is ever clipped.
 */
@Composable
fun OnboardingScreen(app: AppState) {
    // Setup's place outlives this page: a detour to Fuse Sync or Syncthing comes back to the same step.
    val state = app.onboarding
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

    // Again when a step's buttons change (back from setting up Fuse Sync, a profile just made), so the
    // selection is always one of them.
    LaunchedEffect(step.id, step.actions.map { it.label }) {
        app.hero = null
        app.hints = buildList {
            add(Hint(HintButton.CONFIRM, "Choose"))
            if (state.index > 0 || app.dev.rehearsing) add(Hint(HintButton.BACK, if (state.index > 0) "Back" else "Leave"))
            if (step.optional || app.dev.skipRequired) add(Hint(HintButton.MENU, "Skip"))
        }
        // The primary button is the one selected on arrival.
        state.button = step.actions.indexOfFirst { it.primary }.coerceAtLeast(0)
    }

    InputLayer(enabled = !app.overlayOpen && !app.setupOpening) { e ->
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
            // Buttons that wrap onto two lines: Up and Down move between the lines.
            NavAction.UP, NavAction.DOWN -> state.buttonToward(e.action == NavAction.UP, buttons.size)?.let { state.button = it; NavResult.MOVED } ?: NavResult.BLOCKED
            NavAction.NEXT_SECTION, NavAction.PREVIOUS_SECTION, NavAction.SEARCH, NavAction.CONTEXT -> NavResult.BLOCKED
            else -> NavResult.IGNORED
        }
    }

    // Steps move a short, fixed way whatever the screen's width (a share of a wide desktop window
    // flew them hundreds of pixels), and the old step is gone before the new one settles.
    val travel = with(androidx.compose.ui.platform.LocalDensity.current) { STEP_TRAVEL.roundToPx() }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val layout = OnboardingLayout.of(maxWidth, maxHeight)
        SetupBackdrop(Modifier.fillMaxSize())
        Row(
            Modifier
                .fillMaxSize()
                .widthIn(max = MAX_WIDTH)
                .align(Alignment.Center)
                .padding(start = layout.gutter, end = layout.gutter, top = layout.top, bottom = layout.bottom),
        ) {
            if (layout.rail) {
                StepRail(
                    steps, state.index,
                    onPick = { i -> if (i < state.index) go(i - state.index) },
                    modifier = Modifier.width(layout.railWidth).fillMaxHeight(),
                )
                Spacer(Modifier.width(layout.railGap))
            }
            Swap(
                targetState = state.index,
                // Its fade draws each step in a layer of the step's own size, so the step is given
                // the ring's room on every side (and padded back) or a selected button's ring would
                // be cut at its edge while the step fades in.
                modifier = Modifier.weight(1f).fillMaxHeight().ringRoom(),
                transitionSpec = {
                    val dir = if (state.forward) 1 else -1
                    val shift = if (motion.reduced) 0 else travel
                    (
                        slideInHorizontally(motion.tween(Durations.SLOW, Curves.Enter)) { shift * dir } +
                            fadeIn(tween(motion.ms(Durations.BASE), delayMillis = motion.ms(Durations.FAST) / 2, easing = Curves.Fade))
                        ) togetherWith
                        (slideOutHorizontally(motion.tween(Durations.FAST, Curves.Exit)) { -shift / 2 * dir } + fadeOut(motion.fade(Durations.FAST))) using
                        // Never clipped: a selected button's ring reaches past the step's edge.
                        SizeTransform(clip = false)
                },
                label = "onboarding",
            ) { index ->
                val s = steps[index.coerceIn(0, steps.lastIndex)]
                Box(Modifier.fillMaxSize().padding(RING_ROOM)) {
                    StepView(app, s, state, isCurrent = index == state.index, layout = layout)
                }
            }
        }
        FuseLine(
            progress = state.index / (steps.size - 1).coerceAtLeast(1).toFloat(),
            label = "${state.index + 1} of ${steps.size}",
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .widthIn(max = MAX_WIDTH)
                .padding(start = layout.gutter, end = layout.gutter, bottom = Size.hintHeight + Space.xs),
        )
    }
}

/** How setup lays itself out for a window of this size. */
private class OnboardingLayout(
    /** The rail of chapters and steps at the side. */
    val rail: Boolean,
    val railWidth: Dp,
    val railGap: Dp,
    /** Words above the stage, in one scrolling column (narrow and portrait windows). */
    val stacked: Boolean,
    /** A short window: smaller title, tighter spacing. */
    val short: Boolean,
    val gutter: Dp,
    val top: Dp,
    val bottom: Dp,
) {
    companion object {
        fun of(width: Dp, height: Dp): OnboardingLayout {
            val portrait = height > width * 1.05f
            val stacked = portrait || width < 720.dp
            val short = height < 600.dp
            val rail = !stacked && width >= 1080.dp && height >= 520.dp
            val gutter = when {
                width < 600.dp -> Space.gutterCompact
                width >= 1600.dp -> Space.x4
                else -> Space.gutter
            }
            return OnboardingLayout(
                rail = rail,
                railWidth = if (width >= 1500.dp) 240.dp else 208.dp,
                railGap = if (width >= 1500.dp) Space.x4 else Space.x3,
                stacked = stacked,
                short = short,
                gutter = gutter,
                top = if (short) Space.l else Space.x3,
                // Room for the fuse line and the hints under it.
                bottom = Size.hintHeight + Space.x3 + Space.s,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StepView(app: AppState, step: Step, state: OnboardingState, isCurrent: Boolean, layout: OnboardingLayout) {
    val c = Fuse.colors
    val words: @Composable (Modifier) -> Unit = { modifier ->
        Column(modifier) {
            Eyebrow(step)
            Spacer(Modifier.height(if (layout.short) Space.s else Space.m))
            FText(step.title, if (layout.short) Fuse.type.display else Fuse.type.hero, maxLines = 3)
            Spacer(Modifier.height(if (layout.short) Space.s else Space.l))
            FText(step.body, Fuse.type.body, color = c.textMuted, maxLines = 8, modifier = Modifier.widthIn(max = 560.dp))
            Spacer(Modifier.height(if (layout.short) Space.l else Space.xl))
            // Buttons wrap onto a second line rather than ever being cut, with room around them for
            // the focus ring and the lift of the selected one.
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(Space.m),
                verticalArrangement = Arrangement.spacedBy(Space.m),
            ) {
                step.actions.forEachIndexed { i, a ->
                    FuseButton(
                        a.label,
                        selected = isCurrent && i == state.button && app.focusZone == FocusZone.CONTENT,
                        onClick = { state.button = i; if (a.enabled || app.dev.skipRequired) a.run() },
                        kind = if (a.primary) ButtonKind.PRIMARY else ButtonKind.SECONDARY,
                        enabled = a.enabled || app.dev.skipRequired,
                        modifier = if (isCurrent) Modifier.onGloballyPositioned { state.buttonBounds[i] = it.boundsInRoot() } else Modifier,
                    )
                }
            }
            val note = step.actions.firstOrNull { !it.enabled && it.note != null }?.note ?: step.footnote
            if (note != null) {
                Spacer(Modifier.height(Space.s))
                Row(Modifier.padding(start = Space.xs), verticalAlignment = Alignment.CenterVertically) {
                    FuseIcon(FuseIcons.Info, size = Size.iconXS, tint = c.textFaint)
                    Spacer(Modifier.width(Space.s))
                    FText(note, Fuse.type.caption, color = c.textFaint, maxLines = 3)
                }
            }
        }
    }
    val stage: @Composable (Modifier) -> Unit = { modifier ->
        Stage(modifier, lit = isCurrent) {
            if (step.content != null) step.content.invoke(this) else StepEmblem(step.icon)
        }
    }
    if (layout.stacked) {
        Column(
            Modifier.fillMaxSize().ringRoom().verticalScroll(rememberScrollState()).padding(RING_ROOM),
            verticalArrangement = Arrangement.spacedBy(Space.xl),
        ) {
            Spacer(Modifier.height(Space.s))
            words(Modifier.fillMaxWidth())
            stage(Modifier.fillMaxWidth().heightIn(min = 220.dp, max = 420.dp))
            Spacer(Modifier.height(Space.l))
        }
    } else {
        val scroll = rememberScrollState()
        // On a short screen the buttons sit at the bottom of what may not all fit: they stay in view.
        LaunchedEffect(isCurrent, state.button, scroll.maxValue) { if (isCurrent && scroll.maxValue > 0) scroll.fuselineScrollTo(scroll.maxValue) }
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            // A short screen gives the words more of the width, so the buttons keep to one line.
            Box(Modifier.weight(if (layout.short) 1.15f else 0.92f).fillMaxHeight(), contentAlignment = Alignment.CenterStart) {
                words(Modifier.ringRoom().verticalScroll(scroll).padding(RING_ROOM))
            }
            Spacer(Modifier.width(if (layout.rail) Space.x3 else if (layout.short) Space.xl else Space.xxl))
            stage(Modifier.weight(if (layout.short) 0.85f else 1.08f).fillMaxHeight())
        }
    }
}

/** The step's chapter mark: its icon in a small lit well, the eyebrow, and "Optional" as a quiet pill. */
@Composable
private fun Eyebrow(step: Step) {
    val c = Fuse.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(28.dp).clip(PillShape).background(c.accent.copy(alpha = 0.16f)).border(1.dp, c.accent.copy(alpha = 0.3f), PillShape),
            contentAlignment = Alignment.Center,
        ) { FuseIcon(step.icon, size = Size.iconXS, tint = c.accent) }
        Spacer(Modifier.width(Space.s + Space.xxs))
        FText(step.eyebrow.uppercase(), Fuse.type.overline, color = c.textMuted, maxLines = 1)
        if (step.optional) {
            Spacer(Modifier.width(Space.s))
            FText(
                "Optional", Fuse.type.caption, color = c.textMuted, maxLines = 1,
                modifier = Modifier.clip(PillShape).background(c.text.copy(alpha = 0.07f)).padding(horizontal = Space.s, vertical = Space.xxs),
            )
        }
    }
}

/**
 * Where a step's picture stands: a soft pool of the theme's accent light with a faint floor line,
 * no hard panel, so every picture reads as lit from below in the same room. It brightens on the
 * current step.
 */
@Composable
private fun Stage(modifier: Modifier, lit: Boolean, content: @Composable BoxScope.() -> Unit) {
    val c = Fuse.colors
    val glow by fuselineFloat(if (lit) 1f else 0.4f, Fuse.motion.tween(Durations.SLOW), label = "stageGlow")
    Box(
        modifier.drawBehind {
            val center = Offset(size.width * 0.5f, size.height * 0.56f)
            val radius = maxOf(size.width, size.height) * 0.62f
            drawCircle(
                Brush.radialGradient(
                    listOf(c.accent.copy(alpha = (if (c.isDark) 0.2f else 0.14f) * glow), c.accent.copy(alpha = 0.05f * glow), Color.Transparent),
                    center = center, radius = radius,
                ),
                radius = radius, center = center,
            )
            // The floor: a hairline that fades out at both ends.
            val y = size.height * 0.9f
            drawLine(
                Brush.horizontalGradient(
                    listOf(Color.Transparent, c.text.copy(alpha = 0.12f * glow), Color.Transparent),
                    startX = size.width * 0.1f, endX = size.width * 0.9f,
                ),
                Offset(size.width * 0.1f, y), Offset(size.width * 0.9f, y), 1.dp.toPx(),
            )
        },
        contentAlignment = Alignment.Center,
    ) {
        ScaleToFit(Modifier.matchParentSize(), content)
    }
}

/**
 * Lays [content] out at its own size and, when that is more than there is room for (a phone held
 * sideways), shrinks it evenly until it fits, so a picture is never cut at the stage's edge.
 */
@Composable
private fun ScaleToFit(modifier: Modifier, content: @Composable BoxScope.() -> Unit) {
    androidx.compose.ui.layout.Layout(
        content = { Box(contentAlignment = Alignment.Center) { content() } },
        modifier = modifier,
    ) { measurables, constraints ->
        val placeable = measurables.first().measure(constraints.copy(minWidth = 0, minHeight = 0, maxHeight = androidx.compose.ui.unit.Constraints.Infinity))
        val w = constraints.maxWidth
        val h = constraints.maxHeight
        val scale = minOf(1f, h.toFloat() / placeable.height.coerceAtLeast(1), w.toFloat() / placeable.width.coerceAtLeast(1))
        layout(w, h) {
            val x = (w - placeable.width) / 2
            val y = (h - placeable.height) / 2
            placeable.placeWithLayer(x, y) {
                scaleX = scale
                scaleY = scale
            }
        }
    }
}

/** A step without a picture of its own: its icon, large, in rings of light that breathe. */
@Composable
internal fun StepEmblem(icon: ImageVector) {
    val c = Fuse.colors
    val ambient = Fuse.motion.ambient
    val breath = if (ambient) {
        rememberLoopClock(label = "emblem").animateFloat(0f, 1f, infiniteRepeatable(tween(3_200, easing = Curves.Linear), RepeatMode.Reverse), label = "breath").value
    } else {
        0.5f
    }
    Box(Modifier.size(240.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2, size.height / 2)
            val r = size.minDimension / 2
            drawCircle(
                Brush.radialGradient(listOf(c.accent.copy(alpha = 0.26f + 0.08f * breath), Color.Transparent), center = center, radius = r),
                radius = r, center = center,
            )
            for (i in 1..3) {
                val rr = r * (0.36f + 0.17f * i + 0.03f * breath)
                drawCircle(c.text.copy(alpha = 0.1f / i), radius = rr, center = center, style = Stroke(1.dp.toPx()))
            }
        }
        Box(
            Modifier.size(96.dp).clip(PillShape).background(c.surfaceRaised.copy(alpha = 0.9f)).border(1.dp, c.accent.copy(alpha = 0.35f), PillShape),
            contentAlignment = Alignment.Center,
        ) { FuseIcon(icon, size = 40.dp, tint = c.accent) }
    }
}

/**
 * The chapters of setup and their steps, down the side: done steps with a small check, the current
 * one lit with its name in full, the rest waiting. A line runs through them like a fuse, burnt up to
 * where you are. A done step can be picked to go back to it. When the window is too short for every
 * step, only the current chapter lists its steps.
 */
@Composable
private fun StepRail(steps: List<Step>, current: Int, onPick: (Int) -> Unit, modifier: Modifier) {
    val c = Fuse.colors
    BoxWithConstraints(modifier) {
        val chapters = steps.withIndex().groupBy({ it.value.chapter }, { it })
        val full = chapters.size * 34 + steps.size * 30 + 64
        val compact = maxHeight.value < full
        Column(Modifier.fillMaxHeight(), verticalArrangement = Arrangement.Center) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FuseMark(Modifier.size(Size.iconL))
                Spacer(Modifier.width(Space.s))
                FText("Setup", Fuse.type.titleSmall, maxLines = 1)
            }
            Spacer(Modifier.height(Space.xl))
            val currentChapter = steps.getOrNull(current)?.chapter
            for ((chapter, members) in chapters) {
                val done = members.all { it.index < current }
                val here = chapter == currentChapter
                Row(Modifier.padding(vertical = Space.xs), verticalAlignment = Alignment.CenterVertically) {
                    FText(
                        chapter.uppercase(), Fuse.type.overline,
                        color = when {
                            here -> c.accent
                            done -> c.textMuted
                            else -> c.textFaint
                        },
                        maxLines = 1, modifier = Modifier.weight(1f, fill = false),
                    )
                    if (compact && !here) {
                        Spacer(Modifier.width(Space.s))
                        FText(
                            if (done) "Done" else "${members.size}", Fuse.type.caption,
                            color = c.textFaint, maxLines = 1,
                        )
                    }
                }
                if (!compact || here) {
                    for ((i, s) in members) RailStep(s, i, current, last = i == steps.lastIndex, onPick = { onPick(i) })
                }
                Spacer(Modifier.height(Space.s))
            }
        }
    }
}

@Composable
private fun RailStep(step: Step, index: Int, current: Int, last: Boolean, onPick: () -> Unit) {
    val c = Fuse.colors
    val done = index < current
    val here = index == current
    val tint by fuselineColor(
        when {
            here -> c.text
            done -> c.textMuted
            else -> c.textFaint
        },
        Fuse.motion.tween(Durations.BASE), label = "railTint",
    )
    val lit by fuselineFloat(if (here) 1f else 0f, Fuse.motion.tween(Durations.BASE), label = "railLit")
    Row(
        Modifier
            .fillMaxWidth()
            .height(30.dp)
            .clip(PillShape)
            .then(if (done) Modifier.fuseClickable(shape = PillShape, scale = false, role = Role.Button, onClickLabel = step.eyebrow, onClick = onPick) else Modifier),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(Modifier.width(22.dp).fillMaxHeight()) {
            val x = size.width / 2
            val y = size.height / 2
            // The fuse through the steps: burnt (accent) up to the current one.
            if (!last) {
                drawLine(
                    if (done) c.accent.copy(alpha = 0.6f) else c.text.copy(alpha = 0.12f),
                    Offset(x, y), Offset(x, size.height + 1f), 1.5.dp.toPx(), StrokeCap.Round,
                )
            }
            if (index > 0) {
                drawLine(
                    if (done || here) c.accent.copy(alpha = 0.6f) else c.text.copy(alpha = 0.12f),
                    Offset(x, -1f), Offset(x, y), 1.5.dp.toPx(), StrokeCap.Round,
                )
            }
            when {
                here -> {
                    val r = 9.dp.toPx()
                    drawCircle(Brush.radialGradient(listOf(c.accent.copy(alpha = 0.55f * lit), Color.Transparent), center = Offset(x, y), radius = r * 1.6f), radius = r * 1.6f, center = Offset(x, y))
                    drawCircle(c.accent, radius = 4.5.dp.toPx(), center = Offset(x, y))
                    drawCircle(Color.White.copy(alpha = 0.9f), radius = 1.8.dp.toPx(), center = Offset(x, y))
                }
                done -> drawCircle(c.accent.copy(alpha = 0.8f), radius = 3.5.dp.toPx(), center = Offset(x, y))
                else -> drawCircle(c.text.copy(alpha = 0.25f), radius = 3.dp.toPx(), center = Offset(x, y), style = Stroke(1.2.dp.toPx()))
            }
        }
        Spacer(Modifier.width(Space.s))
        FText(step.eyebrow, if (here) Fuse.type.bodyStrong else Fuse.type.label, color = tint, maxLines = 1, modifier = Modifier.weight(1f))
        if (done) FuseIcon(FuseIcons.Check, size = Size.iconXS, tint = c.accent.copy(alpha = 0.8f))
    }
}

/**
 * Behind setup: the theme's room with two slow pools of accent light, one low on the left and one
 * high on the right, so the page has depth without anything competing with the words.
 */
@Composable
private fun SetupBackdrop(modifier: Modifier) {
    val c = Fuse.colors
    val ambient = Fuse.motion.ambient
    // Read while drawing, not while composing: the glow drifts without rebuilding anything.
    val driftState = if (ambient) {
        rememberLoopClock(label = "setupDrift").animateFloat(0f, 1f, infiniteRepeatable(tween(14_000, easing = Curves.Linear), RepeatMode.Reverse), label = "drift")
    } else {
        null
    }
    Canvas(modifier.graphicsLayer { alpha = if (c.isDark) 1f else 0.7f }) {
        val drift = driftState?.value ?: 0.5f
        val w = size.width
        val h = size.height
        val a = Offset(w * (0.08f + 0.06f * drift), h * (0.92f - 0.05f * drift))
        drawCircle(Brush.radialGradient(listOf(c.accent.copy(alpha = 0.16f), Color.Transparent), center = a, radius = maxOf(w, h) * 0.55f), radius = maxOf(w, h) * 0.55f, center = a)
        val b = Offset(w * (0.92f - 0.05f * drift), h * (0.1f + 0.06f * drift))
        drawCircle(Brush.radialGradient(listOf(c.accent.copy(alpha = 0.09f), Color.Transparent), center = b, radius = maxOf(w, h) * 0.45f), radius = maxOf(w, h) * 0.45f, center = b)
        // Soft accent light on a dark room bands on 8-bit screens; grain smooths it.
        drawGrain()
    }
}

/**
 * Setup progress drawn as a lit fuse: the burnt part glows in the accent colour and a spark rides
 * its end.
 */
@Composable
fun FuseLine(progress: Float, label: String, modifier: Modifier = Modifier) {
    val c = Fuse.colors
    val p by fuselineFloat(progress.coerceIn(0f, 1f), Fuse.motion.tween(Durations.DELIBERATE), label = "fuse")
    val flicker = remember { FuselineValue(0.8f) }
    val ambient = Fuse.motion.ambient
    LaunchedEffect(Unit) {
        if (!ambient) return@LaunchedEffect
        while (true) {
            flicker.animateTo(1f, tween(380))
            flicker.animateTo(0.75f, tween(420))
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
        Spacer(Modifier.width(Space.l))
        FText(label, Fuse.type.label.copy(fontFeatureSettings = "tnum"), color = c.textMuted, maxLines = 1)
    }
}

/**
 * A scrolling column clips what it holds, and a selected button's focus ring and lift reach past
 * the button. The column is widened by [RING_ROOM] on every side (and its content padded back by the
 * same), so rings are never cut while everything stays exactly where it was.
 */
private fun Modifier.ringRoom(): Modifier = this.layout { measurable, constraints ->
    val room = RING_ROOM.roundToPx()
    val wide = constraints.copy(
        maxWidth = if (constraints.hasBoundedWidth) constraints.maxWidth + room * 2 else constraints.maxWidth,
        maxHeight = if (constraints.hasBoundedHeight) constraints.maxHeight + room * 2 else constraints.maxHeight,
    )
    val placeable = measurable.measure(wide)
    val w = (placeable.width - room * 2).coerceAtLeast(0)
    val h = (placeable.height - room * 2).coerceAtLeast(0)
    layout(w, h) { placeable.place(-room, -room) }
}

private val RING_ROOM = 14.dp

/** How far a setup step slides in. */
private val STEP_TRAVEL = 56.dp

/** The widest setup gets; wider windows centre it (a TV, an ultrawide). */
private val MAX_WIDTH = 1680.dp
