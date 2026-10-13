package io.github.matiyaaa.fuse.ui.shell.settings

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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.layout.layout
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.matiyaaa.fuse.model.GlyphStyle
import io.github.matiyaaa.fuse.model.InputProfile
import io.github.matiyaaa.fuse.model.NavAction
import io.github.matiyaaa.fuse.model.PadButton
import io.github.matiyaaa.fuse.ui.designsystem.components.ButtonKind
import io.github.matiyaaa.fuse.ui.designsystem.components.FText
import io.github.matiyaaa.fuse.ui.designsystem.components.FuseButton
import io.github.matiyaaa.fuse.ui.designsystem.components.Hint
import io.github.matiyaaa.fuse.ui.designsystem.components.MenuAction
import io.github.matiyaaa.fuse.ui.designsystem.components.MenuHeader
import io.github.matiyaaa.fuse.ui.designsystem.components.MenuList
import io.github.matiyaaa.fuse.ui.designsystem.components.Overlay
import io.github.matiyaaa.fuse.ui.designsystem.components.OverlayEdge
import io.github.matiyaaa.fuse.ui.designsystem.components.Panel
import io.github.matiyaaa.fuse.ui.designsystem.components.ProgressBar
import io.github.matiyaaa.fuse.ui.designsystem.components.SectionLabel
import io.github.matiyaaa.fuse.ui.designsystem.components.Trailing
import io.github.matiyaaa.fuse.ui.designsystem.components.handleMenuAction
import io.github.matiyaaa.fuse.ui.designsystem.effects.reveal
import io.github.matiyaaa.fuse.ui.designsystem.focus.LinearSelection
import io.github.matiyaaa.fuse.ui.designsystem.icons.ButtonGlyph
import io.github.matiyaaa.fuse.ui.designsystem.icons.ButtonGlyphDefaults
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.designsystem.icons.HintButton
import io.github.matiyaaa.fuse.ui.designsystem.icons.PadGlyph
import io.github.matiyaaa.fuse.ui.designsystem.input.InputLayer
import io.github.matiyaaa.fuse.ui.designsystem.input.InputRouter
import io.github.matiyaaa.fuse.ui.designsystem.components.ToastKind
import io.github.matiyaaa.fuse.ui.designsystem.input.LocalInputRouter
import io.github.matiyaaa.fuse.ui.designsystem.input.actionFor
import io.github.matiyaaa.fuse.ui.designsystem.input.withMapping
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.Size
import io.github.matiyaaa.fuse.ui.designsystem.theme.Space
import io.github.matiyaaa.fuse.ui.fuseline.Durations
import io.github.matiyaaa.fuse.ui.fuseline.Swap
import io.github.matiyaaa.fuse.ui.fuseline.fadeIn
import io.github.matiyaaa.fuse.ui.fuseline.fadeOut
import io.github.matiyaaa.fuse.ui.fuseline.togetherWith
import io.github.matiyaaa.fuse.ui.shell.app.AppState
import io.github.matiyaaa.fuse.ui.shell.app.FocusZone
import kotlinx.coroutines.delay

/** Actions a button can be mapped to, in the order the screen lists them, with their icons. */
private val mappable = listOf(
    Triple(NavAction.SELECT, "Confirm", FuseIcons.CircleCheck),
    Triple(NavAction.BACK, "Back", FuseIcons.CornerUpLeft),
    Triple(NavAction.CONTEXT, "Options", FuseIcons.More),
    Triple(NavAction.SEARCH, "Search", FuseIcons.Search),
    Triple(NavAction.QUICK_MENU, "Quick menu", FuseIcons.LayoutList),
    Triple(NavAction.PREVIOUS_SECTION, "Previous section", FuseIcons.ChevronsLeft),
    Triple(NavAction.NEXT_SECTION, "Next section", FuseIcons.ChevronsRight),
    Triple(NavAction.PAGE_UP, "Page up", FuseIcons.ChevronsUp),
    Triple(NavAction.PAGE_DOWN, "Page down", FuseIcons.ChevronsDown),
    Triple(NavAction.HOME, "Home", FuseIcons.Home),
)

/** Controller buttons that can be remapped. The D-pad always navigates, so focus can never be lost. */
private val remappable = listOf(
    PadButton.A, PadButton.B, PadButton.X, PadButton.Y, PadButton.L1, PadButton.R1, PadButton.L2, PadButton.R2,
    PadButton.L3, PadButton.R3, PadButton.START, PadButton.SELECT, PadButton.MODE,
    // Some handhelds send a face button as the system Back key.
    PadButton.KEY_ESCAPE,
)

/** The buttons on the pad itself: the system Back key is left out, since not every pad has one. */
private val padButtons = remappable - PadButton.KEY_ESCAPE

/** The D-pad: it always moves, so during a wait it calls the wait off. */
private val directions = setOf(PadButton.DPAD_UP, PadButton.DPAD_DOWN, PadButton.DPAD_LEFT, PadButton.DPAD_RIGHT)

/** [nintendoKeys]: the pad sends Nintendo keycodes, so A is the right button. */
private fun PadButton.label(nintendoKeys: Boolean): String = when (this) {
    PadButton.A -> if (nintendoKeys) "A (right)" else "A (bottom)"
    PadButton.B -> if (nintendoKeys) "B (bottom)" else "B (right)"
    PadButton.X -> if (nintendoKeys) "X (top)" else "X (left)"
    PadButton.Y -> if (nintendoKeys) "Y (left)" else "Y (top)"
    PadButton.KEY_ESCAPE -> "Back key"
    PadButton.L1 -> "LB"
    PadButton.R1 -> "RB"
    PadButton.L2 -> "LT"
    PadButton.R2 -> "RT"
    PadButton.L3 -> "Left stick press"
    PadButton.R3 -> "Right stick press"
    PadButton.START -> "Start"
    PadButton.SELECT -> "Select"
    PadButton.MODE -> "Guide"
    else -> name.lowercase().replace('_', ' ')
}

/** Which controller buttons trigger [action] with the current layout and remaps. */
private fun buttonsFor(router: InputRouter, guideOff: Boolean, action: NavAction): List<PadButton> =
    remappable.filter { (!guideOff || it != PadButton.MODE) && router.actionFor(it) == action }

/**
 * Button mapping with a live controller test. Choosing an action waits for the next button press
 * and maps it; the D-pad and keyboard keep their meaning, so a bad mapping can always be undone.
 *
 * Beside the list the pad is drawn from its own glyphs, in the style of the buttons on it: every
 * press lights its button, and the buttons the selected action is on are marked, so the list and
 * the pad explain each other.
 */
@Composable
fun ControlsScreen(app: AppState) {
    val prefs by app.store.prefs.collectAsState()
    val router = LocalInputRouter.current
    // On Linux the Guide button does nothing in Fuse (Steam and the desktop answer it), so it isn't offered.
    val guideOff = app.platform.host == io.github.matiyaaa.fuse.model.Host.LINUX
    // So nothing is ever mapped onto it there: an action left only on Guide would be out of reach.
    val pad = if (guideOff) padButtons - PadButton.MODE else padButtons
    val sel = remember { LinearSelection() }
    var capturing by remember { mutableStateOf<Pair<NavAction, String>?>(null) }
    var testing by remember { mutableStateOf(false) }
    var testHeld by remember { mutableStateOf<PadButton?>(null) }
    // Every press, for the pictures of the pad (one listener for the screen and the test).
    val pressed = remember { mutableStateListOf<PadButton>() }
    var last by remember { mutableStateOf<PadButton?>(null) }
    DisposableEffect(router) {
        router.rawListener = { b, down ->
            if (down) {
                if (b !in pressed) pressed.add(b)
                last = b
            } else {
                pressed.remove(b)
            }
        }
        onDispose { router.rawListener = null }
    }

    fun setProfile(transform: (InputProfile) -> InputProfile) = app.store.updatePrefs { it.copy(input = transform(it.input)) }

    // While capturing, the next non-D-pad press is mapped. Nothing else reacts to it.
    DisposableEffect(capturing) {
        val target = capturing
        router.capture = if (target == null) null else { button ->
            // A direction never becomes a mapping: it calls the wait off.
            if (button in directions) {
                capturing = null
            } else if (button in remappable && !(guideOff && button == PadButton.MODE)) {
                val next = prefs.input.withMapping(button, target.first, pad)
                if (next == null) {
                    app.toasts.show("${button.label(prefs.input.glyphs == GlyphStyle.NINTENDO)} is the only button for ${if (prefs.input.actionFor(button) == NavAction.SELECT) "Confirm" else "Back"}. Map it to another button first", ToastKind.ERROR)
                } else {
                    setProfile { next }
                }
                capturing = null
            }
        }
        onDispose { router.capture = null }
    }
    LaunchedEffect(capturing) {
        if (capturing != null) {
            delay(CAPTURE_MS)
            capturing = null
        }
    }
    // While testing, every button only lights up the test. Holding one for a moment ends the test.
    // Taken away by what it installed: `testing` is already false when the test ends.
    DisposableEffect(testing) {
        val mine: ((PadButton, Boolean) -> Unit)? = if (!testing) null else { button, down -> testHeld = if (down) button else if (testHeld == button) null else testHeld }
        if (mine != null) router.exclusive = mine
        onDispose { if (mine != null && router.exclusive === mine) router.exclusive = null }
    }
    // Leaving the page, however it is left (Back, touch, another screen), gives the controller back.
    DisposableEffect(router) {
        onDispose {
            router.exclusive = null
            router.capture = null
        }
    }
    LaunchedEffect(testHeld) {
        if (testing && testHeld != null) {
            delay(TEST_EXIT_MS)
            testing = false
            testHeld = null
        }
    }
    // Guided first: the way most people set a controller up. Edit lists every action.
    var tab by remember { mutableStateOf(0) }
    var guiding by remember { mutableStateOf(false) }
    var startRequests by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { app.hero = null }
    LaunchedEffect(capturing, testing, guiding, tab) {
        app.hints = when {
            capturing != null || testing || guiding -> emptyList()
            tab == 0 -> listOf(Hint(HintButton.CONFIRM, "Start"), Hint(HintButton.BACK, "Back"))
            else -> listOf(Hint(HintButton.CONFIRM, "Change"), Hint(HintButton.BACK, "Back"))
        }
    }

    val nintendo = prefs.input.glyphs == GlyphStyle.NINTENDO
    // The actions first, as the line above them says; the tools for checking the pad after.
    val actions = buildList {
        labelled("Your pad") {
            add(app.choiceRow(
                "labels", "Button labels", FuseIcons.Gamepad, prefs.input.glyphs,
                listOf(GlyphStyle.XBOX to "Xbox (A at the bottom)", GlyphStyle.NINTENDO to "Nintendo (A on the right)", GlyphStyle.PLAYSTATION to "PlayStation (shapes)"),
                detail = "The letters printed on your buttons. Hints use them; what each button does stays the same",
            ) { v -> setProfile { it.copy(glyphs = v) } })
        }
        labelled("Actions") {
            for ((action, label, icon) in mappable) {
                val buttons = buttonsFor(router, guideOff, action)
                add(
                    MenuAction(
                        id = action.name,
                        label = label,
                        icon = icon,
                        trailing = Trailing.Value(buttons.joinToString(", ") { it.label(nintendo) }.ifEmpty { "Not mapped" }),
                        onSelect = { capturing = action to label },
                    ),
                )
            }
        }
        labelled("Check your pad") {
            add(MenuAction("test", "Test buttons", FuseIcons.Joystick, detail = "Every button lights up here and does nothing else. Hold any button to stop", trailing = Trailing.Chevron, onSelect = { testing = true }))
        }
        labelled("") {
            add(
                MenuAction(
                    id = "reset",
                    label = "Reset to default",
                    icon = FuseIcons.RotateCcw,
                    detail = when {
                        prefs.input.remap.isNotEmpty() -> "${prefs.input.remap.size} custom mappings. Start and Select held together do this from anywhere"
                        prefs.input.swapConfirmBack || prefs.input.swapShoulders -> "Buttons swapped. Start and Select held together do this from anywhere"
                        else -> "Using the standard layout"
                    },
                    enabled = prefs.input.remap.isNotEmpty() || prefs.input.swapConfirmBack || prefs.input.swapShoulders,
                    onSelect = {
                        setProfile { it.copy(remap = emptyMap(), swapConfirmBack = false, swapShoulders = false) }
                        app.toasts.show("Controls reset to default")
                    },
                ),
            )
        }
    }
    sel.clamp(actions.size)
    InputLayer(enabled = app.focusZone == FocusZone.CONTENT && !app.overlayOpen && capturing == null && !testing && !guiding) { e ->
        when {
            e.action == NavAction.PREVIOUS_SECTION || e.action == NavAction.NEXT_SECTION -> {
                tab = if (e.action == NavAction.NEXT_SECTION) 1 else 0
                io.github.matiyaaa.fuse.ui.designsystem.input.NavResult.MOVED
            }
            tab == 0 && e.action == NavAction.SELECT -> {
                startRequests++
                io.github.matiyaaa.fuse.ui.designsystem.input.NavResult.ACTIVATED
            }
            // The D-pad always reaches Edit (and its Reset), whatever the shoulders were mapped to.
            tab == 0 && e.action == NavAction.RIGHT -> {
                tab = 1
                io.github.matiyaaa.fuse.ui.designsystem.input.NavResult.MOVED
            }
            tab == 0 -> io.github.matiyaaa.fuse.ui.designsystem.input.NavResult.IGNORED
            else -> handleMenuAction(e, actions, sel).let { result ->
                if (result == io.github.matiyaaa.fuse.ui.designsystem.input.NavResult.IGNORED && e.action == NavAction.LEFT) {
                    tab = 0
                    io.github.matiyaaa.fuse.ui.designsystem.input.NavResult.MOVED
                } else {
                    result
                }
            }
        }
    }

    // The action on the selected row, and the buttons it is on, for the picture of the pad.
    val selectedAction = mappable.firstOrNull { it.first.name == actions.getOrNull(sel.index)?.id }
    val marked = selectedAction?.let { buttonsFor(router, guideOff, it.first).toSet() }.orEmpty()

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val narrow = maxWidth < NARROW_BELOW
        val short = maxHeight < SHORT_BELOW
        val gutter = if (narrow) Space.gutterCompact else Space.gutter
        Column(Modifier.fillMaxSize().padding(horizontal = gutter)) {
            Spacer(Modifier.height(Size.hudHeight + if (short) Space.s else Space.l))
            FText("Controls", if (short) Fuse.type.title else Fuse.type.display, maxLines = 1, modifier = Modifier.reveal(0))
            Spacer(Modifier.height(Space.xxs))
            FText(
                if (tab == 0) "Fuse asks for each button and you press it." else "Choose an action, then press the button you want for it. The D-pad always moves.",
                if (short) Fuse.type.caption else Fuse.type.body, color = Fuse.colors.textMuted, maxLines = 2,
                modifier = Modifier.reveal(1),
            )
            Spacer(Modifier.height(Space.s))
            io.github.matiyaaa.fuse.ui.shell.components.ViewTabs(
                items = listOf(
                    io.github.matiyaaa.fuse.ui.shell.components.ViewTab("Guided", icon = FuseIcons.Sparkles),
                    io.github.matiyaaa.fuse.ui.shell.components.ViewTab("Edit", icon = FuseIcons.SlidersVertical, badge = prefs.input.remap.size.takeIf { it > 0 }?.toString()),
                ),
                active = tab,
                focused = null,
                onSelect = { if (!guiding) tab = it },
                gutter = gutter,
                // The row reaches the screen's edges, so the first tab lines up with the title above.
                modifier = Modifier.reveal(2).layout { m, c ->
                    val extra = gutter.roundToPx()
                    val placed = m.measure(c.copy(minWidth = 0, maxWidth = c.maxWidth + extra * 2))
                    layout(c.maxWidth, placed.height) { placed.place(-extra, 0) }
                },
            )
            Spacer(Modifier.height(if (short) Space.s else Space.m))
            Box(Modifier.weight(1f).padding(bottom = Size.hintHeight + Space.s)) {
                if (tab == 0) {
                    GuidedControls(
                        app, pad, { b, n -> b.label(n) }, startRequests, showPad = !narrow,
                        onRunning = { guiding = it },
                    )
                } else {
                    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(Space.xl)) {
                        Panel(Modifier.weight(if (narrow) 1f else 0.56f).fillMaxHeight().reveal(3)) {
                            MenuList(
                                actions, sel,
                                showSelection = app.focusZone == FocusZone.CONTENT,
                                modifier = Modifier.padding(Space.s),
                                fadeEdges = true,
                            )
                        }
                        if (!narrow) {
                            Panel(Modifier.weight(0.44f).fillMaxHeight().reveal(4)) {
                                Column(Modifier.fillMaxSize().padding(Space.l), horizontalAlignment = Alignment.CenterHorizontally) {
                                    SectionLabel("Controller test", Modifier.align(Alignment.Start), icon = FuseIcons.Gamepad, rule = true)
                                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                                        PadDiagram(pressed.toSet(), marked, nintendo, Modifier.fillMaxWidth())
                                    }
                                    PadCaption(selectedAction?.second, marked, last, nintendo)
                                }
                            }
                        }
                    }
                }
            }
        }

        Overlay(visible = testing, onDismiss = { testing = false }, edge = OverlayEdge.CENTER) {
            Panel(Modifier.padding(horizontal = Space.gutterCompact).widthIn(max = TEST_MAX)) {
                Column(Modifier.padding(vertical = Space.l, horizontal = Space.xl), horizontalAlignment = Alignment.CenterHorizontally) {
                    MenuHeader(
                        "Button test", icon = FuseIcons.Joystick, divider = false,
                        subtitle = "Every button lights up here and does nothing else",
                    )
                    Spacer(Modifier.height(Space.l))
                    PadDiagram(pressed.toSet(), emptySet(), nintendo, Modifier.fillMaxWidth())
                    Spacer(Modifier.height(Space.l))
                    PadCaption(null, emptySet(), last, nintendo)
                    Spacer(Modifier.height(Space.l))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        FText("Hold any button to stop", Fuse.type.caption, color = Fuse.colors.textFaint, maxLines = 1)
                        FuseButton("Done", selected = true, onClick = { testing = false }, kind = ButtonKind.PRIMARY)
                    }
                }
            }
        }
        Overlay(visible = capturing != null, onDismiss = { capturing = null }, edge = OverlayEdge.CENTER) {
            val target = capturing
            val icon = mappable.firstOrNull { it.first == target?.first }?.third ?: FuseIcons.Gamepad
            // What it is on now, so the press that replaces it is a choice, not a guess.
            val now = target?.let { buttonsFor(router, guideOff, it.first) }.orEmpty()
            Panel(Modifier.padding(horizontal = Space.gutterCompact).widthIn(max = CAPTURE_MAX)) {
                Column(Modifier.padding(horizontal = Space.xl, vertical = Space.xl), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier.size(56.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(Fuse.geometry.control))
                            .background(Fuse.colors.accent.copy(alpha = 0.16f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcon(icon, size = 28.dp, tint = Fuse.colors.accent)
                    }
                    Spacer(Modifier.height(Space.m))
                    FText("Press the button for", Fuse.type.body, color = Fuse.colors.textMuted, maxLines = 1)
                    Spacer(Modifier.height(Space.xxs))
                    FText(target?.second.orEmpty(), Fuse.type.display, maxLines = 1)
                    Spacer(Modifier.height(Space.l))
                    // The pad, with where it is now marked and every press lit as it comes.
                    PadDiagram(pressed.toSet(), now.toSet(), nintendo, Modifier.widthIn(max = 360.dp).fillMaxWidth())
                    Spacer(Modifier.height(Space.m))
                    GlyphLine(if (now.isEmpty()) "Not on any button yet" else "Now on", now, Alignment.CenterHorizontally)
                    Spacer(Modifier.height(Space.l))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        CaptureCountdown(target)
                        Spacer(Modifier.width(Space.m))
                        FText("A direction cancels", Fuse.type.caption, color = Fuse.colors.textFaint, maxLines = 1, modifier = Modifier.weight(1f))
                        FuseButton("Cancel", selected = false, onClick = { capturing = null }, icon = FuseIcons.Close)
                    }
                }
            }
        }
    }
}

/** Under the pad: the buttons the selected action is on, or the last button pressed, and the layout. */
@Composable
private fun PadCaption(action: String?, marked: Set<PadButton>, last: PadButton?, nintendoKeys: Boolean) {
    val c = Fuse.colors
    val motion = Fuse.motion
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        Swap(
            action to last,
            transitionSpec = { fadeIn(motion.fade(Durations.FAST)) togetherWith fadeOut(motion.fade(Durations.INSTANT)) },
            contentKey = { (a, l) -> a ?: l?.name },
            label = "padCaption",
        ) { (a, l) ->
            when {
                a != null && marked.isEmpty() -> FText("$a is not on any button", Fuse.type.label, color = c.textMuted, maxLines = 1)
                a != null -> GlyphLine("$a is on", marked.sortedBy { it.ordinal }, Alignment.CenterHorizontally)
                l != null -> GlyphLine("Last pressed", listOf(l), Alignment.CenterHorizontally)
                else -> FText("Press any button", Fuse.type.label, color = c.textMuted, maxLines = 1)
            }
        }
        FText(
            if (nintendoKeys) "Laid out as a Nintendo pad" else "Laid out as an Xbox pad",
            Fuse.type.caption, color = c.textFaint, maxLines = 1, align = TextAlign.Center,
        )
    }
}

/** A caption followed by button glyphs, sized to sit beside caption text. */
@Composable
private fun GlyphLine(caption: String, buttons: List<PadButton>, align: Alignment.Horizontal) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s, align)) {
        FText(caption, Fuse.type.label, color = Fuse.colors.textMuted, maxLines = 1)
        for (b in buttons) PadGlyph(b, size = ButtonGlyphDefaults.Size)
    }
}

/**
 * The pad as setup draws it ([io.github.matiyaaa.fuse.ui.shell.onboarding.PadArt]), labelled in the
 * pad's own style; [nintendoKeys] places A on the right as a pad that sends Nintendo keycodes has it.
 * [pressed] buttons fill with the accent; [marked] ones take its outline.
 */
@Composable
internal fun PadDiagram(
    pressed: Set<PadButton>,
    marked: Set<PadButton>,
    nintendoKeys: Boolean,
    modifier: Modifier = Modifier,
    sticks: io.github.matiyaaa.fuse.ui.designsystem.input.StickPositions = LocalInputRouter.current.sticks.collectAsState().value,
) {
    // As wide as it may be, but never taller than its place: a short panel shrinks the pad instead of
    // letting it spill over the heading above it.
    androidx.compose.foundation.layout.BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val fit = if (constraints.hasBoundedHeight) minOf(maxWidth, maxHeight * io.github.matiyaaa.fuse.ui.shell.onboarding.PAD_ASPECT) else maxWidth
        io.github.matiyaaa.fuse.ui.shell.onboarding.PadArt(
            pressed, Fuse.glyphs.style, nintendoKeys, Modifier.widthIn(max = minOf(PAD_MAX_WIDTH, fit)).fillMaxWidth(), marked = marked, sticks = sticks,
        )
    }
}

/** The time left to press, as a ring that empties with the seconds inside it. */
@Composable
private fun CaptureCountdown(key: Any?) {
    var left by remember(key) { mutableStateOf(1f) }
    LaunchedEffect(key) {
        val steps = 50
        for (i in steps downTo 0) {
            left = i / steps.toFloat()
            delay(CAPTURE_MS / steps)
        }
    }
    val seconds = kotlin.math.ceil(left * CAPTURE_MS / 1000f).toInt()
    val c = Fuse.colors
    Box(
        Modifier.size(44.dp).drawWithCache {
            val w = 3.dp.toPx()
            val arc = androidx.compose.ui.geometry.Size(size.width - w, size.height - w)
            val at = Offset(w / 2, w / 2)
            onDrawBehind {
                drawArc(c.textFaint.copy(alpha = 0.25f), 0f, 360f, false, at, arc, style = Stroke(w))
                drawArc(c.accent, -90f, 360f * left, false, at, arc, style = Stroke(w, cap = androidx.compose.ui.graphics.StrokeCap.Round))
            }
        },
        contentAlignment = Alignment.Center,
    ) {
        FText("$seconds", Fuse.type.label, maxLines = 1)
    }
}

private const val CAPTURE_MS = 5_000L
private const val TEST_EXIT_MS = 1_200L

private val NARROW_BELOW = 640.dp
private val SHORT_BELOW = 560.dp

/** The widest the controller test's picture is drawn. */
private val PAD_MAX_WIDTH = 520.dp

/** The test and capture dialogs' widest; on narrow screens they keep a margin instead. */
private val TEST_MAX = 560.dp
private val CAPTURE_MAX = 520.dp
