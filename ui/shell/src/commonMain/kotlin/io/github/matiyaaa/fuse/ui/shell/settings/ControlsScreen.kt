package io.github.matiyaaa.fuse.ui.shell.settings

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
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
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.CornerRadius
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
import io.github.matiyaaa.fuse.ui.designsystem.input.LocalInputRouter
import io.github.matiyaaa.fuse.ui.designsystem.theme.Durations
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.Size
import io.github.matiyaaa.fuse.ui.designsystem.theme.Space
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
private fun buttonsFor(router: InputRouter, action: NavAction): List<PadButton> =
    remappable.filter { router.actionFor(it) == action }

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
            if (button in remappable) {
                setProfile { it.copy(remap = it.remap + (button to target.first)) }
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
    DisposableEffect(testing) {
        if (testing) {
            router.exclusive = { button, down -> testHeld = if (down) button else if (testHeld == button) null else testHeld }
        }
        onDispose { if (testing) router.exclusive = null }
    }
    LaunchedEffect(testHeld) {
        if (testing && testHeld != null) {
            delay(TEST_EXIT_MS)
            testing = false
            testHeld = null
        }
    }
    LaunchedEffect(Unit) { app.hero = null }
    LaunchedEffect(capturing, testing) {
        app.hints = if (capturing != null || testing) emptyList() else listOf(Hint(HintButton.CONFIRM, "Change"), Hint(HintButton.BACK, "Back"))
    }

    val nintendo = prefs.input.glyphs == GlyphStyle.NINTENDO
    // The actions first, as the line above them says; the tools for checking the pad after.
    val actions = buildList {
        labelled("Actions") {
            for ((action, label, icon) in mappable) {
                val buttons = buttonsFor(router, action)
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
            add(MenuAction("detect", "Detect my buttons", FuseIcons.ScanSearch, detail = "Sets the layout and confirm button from two presses", onSelect = { app.buttonDetect = true }))
        }
        labelled("") {
            add(
                MenuAction(
                    id = "reset",
                    label = "Reset to default",
                    icon = FuseIcons.RotateCcw,
                    detail = if (prefs.input.remap.isEmpty()) "Using the standard layout" else "${prefs.input.remap.size} custom mappings",
                    enabled = prefs.input.remap.isNotEmpty(),
                    onSelect = { setProfile { it.copy(remap = emptyMap()) } },
                ),
            )
        }
    }
    sel.clamp(actions.size)
    InputLayer(enabled = app.focusZone == FocusZone.CONTENT && !app.overlayOpen && capturing == null && !testing) { e -> handleMenuAction(e, actions, sel) }

    // The action on the selected row, and the buttons it is on, for the picture of the pad.
    val selectedAction = mappable.firstOrNull { it.first.name == actions.getOrNull(sel.index)?.id }
    val marked = selectedAction?.let { buttonsFor(router, it.first).toSet() }.orEmpty()

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val narrow = maxWidth < NARROW_BELOW
        val short = maxHeight < SHORT_BELOW
        val gutter = if (narrow) Space.gutterCompact else Space.gutter
        Column(Modifier.fillMaxSize().padding(horizontal = gutter)) {
            Spacer(Modifier.height(Size.hudHeight + if (short) Space.s else Space.l))
            FText("Controls", if (short) Fuse.type.title else Fuse.type.display, maxLines = 1, modifier = Modifier.reveal(0))
            Spacer(Modifier.height(Space.xxs))
            FText(
                "Choose an action, then press the button you want for it. The D-pad always moves.",
                if (short) Fuse.type.caption else Fuse.type.body, color = Fuse.colors.textMuted, maxLines = 2,
                modifier = Modifier.reveal(1),
            )
            Spacer(Modifier.height(if (short) Space.m else Space.l))
            Row(Modifier.weight(1f).padding(bottom = Size.hintHeight + Space.s), horizontalArrangement = Arrangement.spacedBy(Space.xl)) {
                Panel(Modifier.weight(if (narrow) 1f else 0.56f).fillMaxHeight().reveal(2)) {
                    MenuList(
                        actions, sel,
                        showSelection = app.focusZone == FocusZone.CONTENT,
                        modifier = Modifier.padding(Space.s).menuEdges(actions, sel.index),
                    )
                }
                if (!narrow) {
                    Panel(Modifier.weight(0.44f).fillMaxHeight().reveal(3)) {
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
            Panel(Modifier.padding(horizontal = Space.gutterCompact).widthIn(max = CAPTURE_MAX)) {
                Column(Modifier.padding(Space.xl), horizontalAlignment = Alignment.CenterHorizontally) {
                    FText("Press a button for", Fuse.type.label, color = Fuse.colors.textMuted, maxLines = 1)
                    Spacer(Modifier.height(Space.xxs))
                    FText(target?.second.orEmpty(), Fuse.type.title, maxLines = 1)
                    Spacer(Modifier.height(Space.l))
                    // What it is on now, so the press that replaces it is a choice, not a guess.
                    val now = target?.let { buttonsFor(router, it.first) }.orEmpty()
                    GlyphLine(if (now.isEmpty()) "Not mapped yet" else "Now on", now, Alignment.CenterHorizontally)
                    Spacer(Modifier.height(Space.l))
                    CaptureCountdown(target)
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
        AnimatedContent(
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
 * The pad drawn from its own glyphs on a soft silhouette: triggers and bumpers along the top, the
 * D-pad, the centre buttons and the face buttons on the body, and the two sticks between the grips.
 * Glyphs follow the pad's style (letters or shapes), and [nintendoKeys] places A on the right as a
 * pad that sends Nintendo keycodes has it. [pressed] buttons light up solid in the accent; [marked]
 * ones take its tint.
 */
@Composable
internal fun PadDiagram(pressed: Set<PadButton>, marked: Set<PadButton>, nintendoKeys: Boolean, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val g = (maxWidth / PAD_WIDTH).coerceIn(PAD_UNIT_MIN, PAD_UNIT_MAX)
        val c = Fuse.colors
        val accent = c.accent
        val body = c.text.copy(alpha = if (c.isDark) 0.05f else 0.045f)
        val rim = c.text.copy(alpha = if (c.isDark) 0.1f else 0.12f)
        val light = Color.White.copy(alpha = if (c.isDark) 0.12f else 0.6f)

        @Composable
        fun Button(b: PadButton, cx: Float, cy: Float, size: Float) {
            val on = b in pressed
            val tint = if (on || b in marked) accent else c.text
            Box(
                Modifier.offset(g * (cx - size), g * (cy - size / 2)).size(g * size * 2, g * size),
                contentAlignment = Alignment.Center,
            ) {
                PadGlyph(b, size = g * size, color = tint, emphasized = on)
            }
        }

        Box(Modifier.size(g * PAD_WIDTH, g * PAD_HEIGHT)) {
            // The body: a rounded slab with two grips, lit along its top like every other object.
            Spacer(
                Modifier.offset(y = g * BODY_TOP).size(g * PAD_WIDTH, g * (PAD_HEIGHT - BODY_TOP)).drawWithCache {
                    val u = size.width / PAD_WIDTH
                    val slab = Path().apply { addRoundRect(RoundRect(Rect(0f, 0f, size.width, u * 4f), CornerRadius(u * 2f))) }
                    val left = Path().apply { addOval(Rect(Offset(u * 2.4f, u * 3.35f), u * 1.3f)) }
                    val right = Path().apply { addOval(Rect(Offset(size.width - u * 2.4f, u * 3.35f), u * 1.3f)) }
                    val withLeft = Path().apply { op(slab, left, PathOperation.Union) }
                    val shape = Path().apply { op(withLeft, right, PathOperation.Union) }
                    val edge = Brush.verticalGradient(0f to light, 0.25f to Color.Transparent, endY = size.height)
                    val stroke = Stroke(1.dp.toPx() * 2)
                    onDrawBehind {
                        drawPath(shape, body)
                        clipPath(shape) {
                            drawPath(shape, rim, style = stroke)
                            drawPath(shape, edge, style = stroke)
                        }
                    }
                },
            )
            // Shoulders: triggers outside, bumpers inside.
            Button(PadButton.L2, 1.3f, SHOULDER_Y, 0.8f)
            Button(PadButton.L1, 3.0f, SHOULDER_Y, 0.8f)
            Button(PadButton.R1, PAD_WIDTH - 3.0f, SHOULDER_Y, 0.8f)
            Button(PadButton.R2, PAD_WIDTH - 1.3f, SHOULDER_Y, 0.8f)
            // The D-pad lights the arm that is held.
            val arm = listOf(PadButton.DPAD_UP, PadButton.DPAD_DOWN, PadButton.DPAD_LEFT, PadButton.DPAD_RIGHT).firstOrNull { it in pressed }
            Box(
                Modifier.offset(g * (DPAD_X - 1.1f), g * (FACE_Y - 1.1f)).size(g * 2.2f),
                contentAlignment = Alignment.Center,
            ) {
                if (arm != null) PadGlyph(arm, size = g * 2f, color = accent) else ButtonGlyph(HintButton.DPAD, size = g * 2f)
            }
            // The centre buttons.
            Button(PadButton.SELECT, PAD_WIDTH / 2 - 1.05f, CENTRE_Y, 0.62f)
            Button(PadButton.MODE, PAD_WIDTH / 2, CENTRE_Y, 0.78f)
            Button(PadButton.START, PAD_WIDTH / 2 + 1.05f, CENTRE_Y, 0.62f)
            // The face buttons, where this pad has them.
            val fx = PAD_WIDTH - DPAD_X
            val top = if (nintendoKeys) PadButton.X else PadButton.Y
            val leftFace = if (nintendoKeys) PadButton.Y else PadButton.X
            val rightFace = if (nintendoKeys) PadButton.A else PadButton.B
            val bottom = if (nintendoKeys) PadButton.B else PadButton.A
            Button(top, fx, FACE_Y - 0.95f, 0.9f)
            Button(leftFace, fx - 0.95f, FACE_Y, 0.9f)
            Button(rightFace, fx + 0.95f, FACE_Y, 0.9f)
            Button(bottom, fx, FACE_Y + 0.95f, 0.9f)
            // The sticks, between the grips.
            Button(PadButton.L3, PAD_WIDTH / 2 - 1.55f, STICK_Y, 1.15f)
            Button(PadButton.R3, PAD_WIDTH / 2 + 1.55f, STICK_Y, 1.15f)
        }
    }
}

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
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        ProgressBar(left, Modifier.weight(1f))
        Spacer(Modifier.width(Space.m))
        FText("$seconds s", Fuse.type.numericSmall, color = Fuse.colors.textMuted, maxLines = 1)
    }
}

private const val CAPTURE_MS = 5_000L
private const val TEST_EXIT_MS = 1_200L

private val NARROW_BELOW = 640.dp
private val SHORT_BELOW = 560.dp

/** The pad's picture in units of its button size: overall size and where its parts sit. */
private const val PAD_WIDTH = 11f
private const val PAD_HEIGHT = 5.95f
private const val SHOULDER_Y = 0.45f
private const val BODY_TOP = 1.2f
private const val DPAD_X = 2.3f
private const val FACE_Y = BODY_TOP + 1.85f
private const val CENTRE_Y = BODY_TOP + 1.05f
private const val STICK_Y = BODY_TOP + 3.2f
private val PAD_UNIT_MIN = 22.dp
private val PAD_UNIT_MAX = 40.dp

/** The test and capture dialogs' widest; on narrow screens they keep a margin instead. */
private val TEST_MAX = 560.dp
private val CAPTURE_MAX = 420.dp
