package io.github.matiyaaa.fuse.ui.shell.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.matiyaaa.fuse.model.LaunchDisplay
import io.github.matiyaaa.fuse.model.NavAction
import io.github.matiyaaa.fuse.ui.designsystem.components.FText
import io.github.matiyaaa.fuse.ui.designsystem.components.Overlay
import io.github.matiyaaa.fuse.ui.designsystem.components.OverlayEdge
import io.github.matiyaaa.fuse.ui.designsystem.components.Panel
import io.github.matiyaaa.fuse.ui.designsystem.effects.fuseClickable
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcon
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.designsystem.input.InputLayer
import io.github.matiyaaa.fuse.ui.designsystem.input.LayerPriority
import io.github.matiyaaa.fuse.ui.designsystem.input.NavResult
import io.github.matiyaaa.fuse.ui.designsystem.media.Artwork
import io.github.matiyaaa.fuse.ui.designsystem.shape.SquircleShape
import io.github.matiyaaa.fuse.ui.designsystem.sound.SoundCue
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.Radius
import io.github.matiyaaa.fuse.ui.designsystem.theme.Size
import io.github.matiyaaa.fuse.ui.designsystem.theme.Space
import io.github.matiyaaa.fuse.ui.fuseline.Durations
import io.github.matiyaaa.fuse.ui.fuseline.fuselineColor
import io.github.matiyaaa.fuse.ui.fuseline.fuselineFloat
import io.github.matiyaaa.fuse.ui.fuseline.tween

/**
 * The question asked when something starts on a device with two screens: the top screen on the
 * left, the bottom screen on the right, each drawn on a little dual-screen handheld with the game's
 * art lit on that screen. Below, two ticks keep the answer for this game or app, or for its whole
 * system (all apps); with neither ticked it is just this time.
 */
data class ScreenPromptSpec(
    /** "Play" or "Open". */
    val verb: String,
    val title: String,
    /** Art drawn on the chosen screen (the game's background or box art, the app's icon). */
    val art: Any?,
    /** ARGB colour the lit screen glows with. */
    val accent: Long,
    /** "Always for this game". */
    val itemLabel: String,
    /** "Always for PlayStation games". */
    val groupLabel: String,
    val onPick: (LaunchDisplay, ScreenMemory) -> Unit,
    /** The art is an app's icon: drawn whole, centred on the lit screen, never cropped to its shape. */
    val fitArt: Boolean = false,
    /** A game's own art for the header: its box art, as its tile shows it. */
    val cover: io.github.matiyaaa.fuse.ui.shell.store.Art? = null,
    /** The line under the ticks: how to change it later. */
    val footnote: String = "Leave both unticked for just this time. Settings, Display changes it later.",
) {
    /** The ticks offered: a blank [groupLabel] leaves only the one. */
    val memories: List<ScreenMemory> get() = if (groupLabel.isBlank()) listOf(ScreenMemory.ITEM) else listOf(ScreenMemory.ITEM, ScreenMemory.GROUP)
}

private val screens = listOf(LaunchDisplay.PRIMARY, LaunchDisplay.SECONDARY)

@Composable
internal fun ScreenPromptOverlay(app: AppState) {
    val spec = app.screenPrompt
    var shown by remember { mutableStateOf(spec) }
    if (spec != null) shown = spec
    // Row 0 is the two screens, row 1 the two ticks.
    var row by remember(spec) { mutableIntStateOf(0) }
    var screen by remember(spec) { mutableIntStateOf(0) }
    var tick by remember(spec) { mutableIntStateOf(0) }
    var memory by remember(spec) { mutableStateOf(ScreenMemory.ONCE) }
    LaunchedEffect(spec != null) { if (spec != null) app.platform.sounds.play(SoundCue.OPEN) }
    val memories = (spec ?: shown)?.memories ?: listOf(ScreenMemory.ITEM, ScreenMemory.GROUP)

    fun pick(display: LaunchDisplay) {
        val s = spec ?: return
        app.screenPrompt = null
        s.onPick(display, memory)
    }
    fun toggle(m: ScreenMemory) {
        memory = if (memory == m) ScreenMemory.ONCE else m
    }

    if (spec != null) {
        InputLayer(priority = LayerPriority.DIALOG + 1, modal = true) { e ->
            when (e.action) {
                NavAction.LEFT -> when {
                    row == 0 && screen > 0 -> { screen = 0; NavResult.MOVED }
                    row == 1 && tick > 0 -> { tick = 0; NavResult.MOVED }
                    else -> NavResult.BLOCKED
                }
                NavAction.RIGHT -> when {
                    row == 0 && screen < 1 -> { screen = 1; NavResult.MOVED }
                    row == 1 && tick < memories.size - 1 -> { tick = 1; NavResult.MOVED }
                    else -> NavResult.BLOCKED
                }
                NavAction.DOWN -> if (row == 0) { row = 1; NavResult.MOVED } else NavResult.BLOCKED
                NavAction.UP -> if (row == 1) { row = 0; NavResult.MOVED } else NavResult.BLOCKED
                NavAction.SELECT -> {
                    if (row == 0) pick(screens[screen]) else toggle(memories[tick])
                    NavResult.ACTIVATED
                }
                NavAction.BACK -> { app.screenPrompt = null; NavResult.CONSUMED }
                else -> NavResult.CONSUMED
            }
        }
    }

    Overlay(visible = spec != null, onDismiss = { app.screenPrompt = null }, edge = OverlayEdge.CENTER) {
        val s = shown ?: return@Overlay
        BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val compact = maxHeight < 560.dp
            // The handheld drawings take what height the rest leaves.
            val device = ((maxHeight - if (compact) 330.dp else 380.dp) * 0.62f).coerceIn(92.dp, 168.dp)
            val pad = if (compact) Space.l else Space.xl
            Panel(Modifier.widthIn(max = 720.dp).fillMaxWidth(0.94f)) {
                Column(Modifier.padding(pad)) {
                    Header(s, compact)
                    Spacer(Modifier.height(if (compact) Space.m else Space.l))
                    Row(horizontalArrangement = Arrangement.spacedBy(if (compact) Space.m else Space.l)) {
                        screens.forEachIndexed { i, d ->
                            ScreenCard(
                                display = d,
                                art = s.art,
                                fitArt = s.fitArt,
                                accent = Color(s.accent),
                                focused = row == 0 && screen == i,
                                marked = row == 1 && screen == i,
                                deviceWidth = device,
                                compact = compact,
                                modifier = Modifier.weight(1f),
                                onClick = { pick(d) },
                            )
                        }
                    }
                    Spacer(Modifier.height(if (compact) Space.m else Space.l))
                    Row(horizontalArrangement = Arrangement.spacedBy(if (compact) Space.m else Space.l)) {
                        memories.forEachIndexed { i, m ->
                            Tick(
                                label = if (m == ScreenMemory.ITEM) s.itemLabel else s.groupLabel,
                                checked = memory == m,
                                focused = row == 1 && tick == i,
                                modifier = Modifier.weight(1f),
                                onClick = { tick = i; row = 1; toggle(m) },
                            )
                        }
                    }
                    Spacer(Modifier.height(Space.s))
                    FText(
                        s.footnote,
                        Fuse.type.caption,
                        color = Fuse.colors.textFaint,
                        maxLines = 2,
                    )
                }
            }
        }
    }
}

/** What is asked and about what: the game's art (or a dual-screen mark), the question, and its name. */
@Composable
private fun Header(s: ScreenPromptSpec, compact: Boolean) {
    val c = Fuse.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        val well = SquircleShape.fraction(Fuse.geometry.tileCornerFraction.coerceAtLeast(0.12f) + 0.06f)
        Box(
            Modifier
                .size(if (compact) Size.thumb else Size.thumbL)
                .clip(well)
                .background(c.text.copy(alpha = if (c.isDark) 0.08f else 0.06f)),
            contentAlignment = Alignment.Center,
        ) {
            when {
                // A game shows its box art, the picture its tile has, not a crop of its background.
                s.cover != null -> io.github.matiyaaa.fuse.ui.shell.components.SquareGameArt(
                    s.cover, Modifier.fillMaxSize(),
                    fallback = { io.github.matiyaaa.fuse.ui.designsystem.media.GeneratedArt(s.title, Color(s.accent), slot = io.github.matiyaaa.fuse.ui.designsystem.media.ArtSlot.ICON) },
                )
                s.art == null -> FuseIcon(FuseIcons.DualScreen, size = Size.iconL, tint = c.text)
                // An app's icon keeps its own shape inside the well.
                s.fitArt -> Artwork(s.art, Modifier.fillMaxSize().padding(Space.s), contentScale = androidx.compose.ui.layout.ContentScale.Fit)
                else -> Artwork(s.art, Modifier.fillMaxSize())
            }
        }
        Spacer(Modifier.width(Space.m))
        Column(verticalArrangement = Arrangement.spacedBy(Space.xxs)) {
            FText("${s.verb} on which screen?", if (compact) Fuse.type.titleSmall else Fuse.type.title, maxLines = 1)
            FText(s.title, Fuse.type.body, color = c.textMuted, maxLines = 1)
        }
    }
}

/**
 * One screen to choose: the handheld with that screen lit, its name and what it is. Focused, it
 * lifts onto the raised surface inside a focus ring; the screen last chosen keeps an accent ring
 * while focus is on the ticks below.
 */
@Composable
private fun ScreenCard(
    display: LaunchDisplay,
    art: Any?,
    fitArt: Boolean,
    accent: Color,
    focused: Boolean,
    /** The screen last chosen while the focus is on the ticks below. */
    marked: Boolean,
    deviceWidth: Dp,
    compact: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val c = Fuse.colors
    val motion = Fuse.motion
    val lift by fuselineFloat(if (focused) 1f else 0f, motion.focusSpring(), label = "screenCard")
    val ring by fuselineColor(
        when {
            focused -> c.focus
            marked -> c.accent.copy(alpha = 0.6f)
            else -> c.hairline
        },
        motion.tween(Durations.FAST),
        label = "screenCardEdge",
    )
    val fill by fuselineColor(if (focused) c.surfaceRaised else c.text.copy(alpha = if (c.isDark) 0.04f else 0.03f), motion.tween(Durations.FAST), label = "screenCardFill")
    val shape = RoundedCornerShape(Fuse.geometry.control)
    Column(
        modifier
            .graphicsLayer {
                val s = 1f + (motion.focusScale - 1f) * 0.3f * lift
                scaleX = s
                scaleY = s
            }
            .clip(shape)
            .background(fill)
            .border(if (focused || marked) Size.focusStroke else Size.stroke, ring, shape)
            .fuseClickable(shape = shape, scale = false, role = Role.Button, onClickLabel = screenName(display), onClick = onClick)
            .semantics { selected = focused }
            .padding(vertical = if (compact) Space.m else Space.l, horizontal = Space.m),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Handheld(lit = display, art = art, fitArt = fitArt, accent = accent, width = deviceWidth, dim = !focused)
        Spacer(Modifier.height(if (compact) Space.s else Space.m))
        Row(verticalAlignment = Alignment.CenterVertically) {
            FuseIcon(screenIcon(display), size = Size.iconS, tint = if (focused) c.accent else c.textMuted)
            Spacer(Modifier.width(Space.s))
            FText(screenName(display), Fuse.type.titleSmall, maxLines = 1)
        }
        if (!compact) {
            FText(
                if (display == LaunchDisplay.PRIMARY) "The big screen up top" else "The second screen below",
                Fuse.type.caption,
                color = c.textMuted,
                maxLines = 1,
                modifier = Modifier.padding(top = Space.xxs),
            )
        }
    }
}

/**
 * A dual-screen handheld seen from the front, open: a lid with the top screen, a hinge, and a base
 * with the bottom screen between a D-pad and four face buttons. The [lit] screen shows [art].
 */
@Composable
private fun Handheld(lit: LaunchDisplay, art: Any?, fitArt: Boolean, accent: Color, width: Dp, dim: Boolean) {
    val c = Fuse.colors
    val shell = c.text.copy(alpha = if (dim) 0.07f else 0.1f)
    val outline = c.text.copy(alpha = if (dim) 0.16f else 0.24f)
    val corner = RoundedCornerShape(width * 0.08f)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.width(width).aspectRatio(16f / 10f).clip(corner).background(shell).border(Size.stroke, outline, corner)
                .padding(width * 0.05f),
        ) {
            Screen(on = lit == LaunchDisplay.PRIMARY, art = art, fitArt = fitArt, accent = accent, modifier = Modifier.fillMaxSize())
        }
        Row(Modifier.width(width * 0.72f).height(width * 0.035f), horizontalArrangement = Arrangement.SpaceBetween) {
            repeat(2) { Box(Modifier.width(width * 0.16f).fillMaxHeight().background(outline, RoundedCornerShape(50))) }
        }
        Box(Modifier.width(width).aspectRatio(16f / 10.5f).clip(corner).background(shell).border(Size.stroke, outline, corner)) {
            Screen(
                on = lit == LaunchDisplay.SECONDARY, art = art, fitArt = fitArt, accent = accent,
                modifier = Modifier.align(Alignment.Center).fillMaxHeight(0.78f).aspectRatio(1.05f),
            )
            DPad(outline, Modifier.align(Alignment.CenterStart).padding(start = width * 0.06f).size(width * 0.14f))
            FaceButtons(outline, Modifier.align(Alignment.CenterEnd).padding(end = width * 0.06f).size(width * 0.14f))
        }
    }
}

/**
 * One screen of the handheld: the game lit on it, or dark glass. A game's art fills the screen; an
 * app's icon ([fitArt]) sits whole in its middle on the lit glass, never cut to the screen's shape.
 */
@Composable
private fun Screen(on: Boolean, art: Any?, fitArt: Boolean, accent: Color, modifier: Modifier) {
    val c = Fuse.colors
    val glass = RoundedCornerShape(Radius.xs)
    Box(
        modifier
            .clip(glass)
            .then(
                if (on) {
                    Modifier.background(Brush.linearGradient(listOf(accent, accent.copy(alpha = 0.45f), c.surfaceDim)))
                } else {
                    Modifier.background(c.surfaceDim).border(Size.stroke, c.hairline, glass)
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (on && art != null) {
            if (fitArt) {
                Artwork(art, Modifier.fillMaxHeight(0.66f).aspectRatio(1f), contentScale = androidx.compose.ui.layout.ContentScale.Fit)
            } else {
                Artwork(art, Modifier.fillMaxSize())
            }
        }
    }
}

@Composable
private fun DPad(color: Color, modifier: Modifier) {
    Box(modifier) {
        Box(Modifier.align(Alignment.Center).fillMaxWidth().fillMaxHeight(0.32f).background(color, RoundedCornerShape(Space.xxs)))
        Box(Modifier.align(Alignment.Center).fillMaxHeight().fillMaxWidth(0.32f).background(color, RoundedCornerShape(Space.xxs)))
    }
}

@Composable
private fun FaceButtons(color: Color, modifier: Modifier) {
    Box(modifier) {
        listOf(Alignment.TopCenter, Alignment.CenterStart, Alignment.CenterEnd, Alignment.BottomCenter).forEach { a ->
            Box(Modifier.align(a).fillMaxSize(0.3f).background(color, CircleShape))
        }
    }
}

/**
 * A tick box with its label; ticking one unticks the other. The box fills with the accent and a
 * check when ticked, and is a quiet outline when not, like the choice marks in menus.
 */
@Composable
private fun Tick(label: String, checked: Boolean, focused: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = Fuse.colors
    val motion = Fuse.motion
    val shape = RoundedCornerShape(Fuse.geometry.control)
    val ring by fuselineColor(if (focused) c.focus else c.hairline, motion.tween(Durations.FAST), label = "tickEdge")
    val box by fuselineColor(if (checked) c.accent else Color.Transparent, motion.tween(Durations.FAST), label = "tickBox")
    Row(
        modifier
            .heightIn(min = Size.touch)
            .clip(shape)
            .background(if (focused) c.surfaceRaised else Color.Transparent)
            .border(if (focused) Size.focusStroke else Size.stroke, ring, shape)
            .fuseClickable(shape = shape, scale = false, role = Role.Checkbox, onClick = onClick)
            .semantics { selected = focused }
            .padding(horizontal = Space.m, vertical = Space.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val mark = RoundedCornerShape(Radius.xs)
        Box(
            Modifier
                .size(Size.iconM)
                .clip(mark)
                .background(box)
                .then(if (checked) Modifier else Modifier.border(Size.focusStroke * 0.75f, c.textMuted, mark)),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) FuseIcon(FuseIcons.Check, size = Size.iconXS, tint = c.onAccent)
        }
        Spacer(Modifier.width(Space.m))
        FText(label, Fuse.type.label, color = if (checked || focused) c.text else c.textMuted, maxLines = 1)
    }
}
