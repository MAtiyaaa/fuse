package io.github.matiyaaa.fuse.ui.shell.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.matiyaaa.fuse.model.InputProfile
import io.github.matiyaaa.fuse.model.NavAction
import io.github.matiyaaa.fuse.model.PadButton
import io.github.matiyaaa.fuse.ui.designsystem.components.FText
import io.github.matiyaaa.fuse.ui.designsystem.components.Hint
import io.github.matiyaaa.fuse.ui.designsystem.components.MenuAction
import io.github.matiyaaa.fuse.ui.designsystem.components.MenuList
import io.github.matiyaaa.fuse.ui.designsystem.components.FuseButton
import io.github.matiyaaa.fuse.ui.designsystem.components.Panel
import io.github.matiyaaa.fuse.ui.designsystem.components.ProgressBar
import io.github.matiyaaa.fuse.ui.designsystem.components.SectionLabel
import io.github.matiyaaa.fuse.ui.designsystem.components.Trailing
import io.github.matiyaaa.fuse.ui.designsystem.components.handleMenuAction
import io.github.matiyaaa.fuse.ui.designsystem.focus.LinearSelection
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.designsystem.icons.HintButton
import io.github.matiyaaa.fuse.ui.designsystem.input.InputLayer
import io.github.matiyaaa.fuse.ui.designsystem.input.InputRouter
import io.github.matiyaaa.fuse.ui.designsystem.input.LocalInputRouter
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.Size
import io.github.matiyaaa.fuse.ui.designsystem.theme.Space
import io.github.matiyaaa.fuse.ui.shell.app.AppState
import io.github.matiyaaa.fuse.ui.shell.app.FocusZone
import io.github.matiyaaa.fuse.ui.shell.onboarding.ControllerTest
import kotlinx.coroutines.delay

/** Actions a button can be mapped to, in the order the screen lists them. */
private val mappable = listOf(
    NavAction.SELECT to "Confirm",
    NavAction.BACK to "Back",
    NavAction.CONTEXT to "Options",
    NavAction.SEARCH to "Search",
    NavAction.QUICK_MENU to "Quick menu",
    NavAction.PREVIOUS_SECTION to "Previous section",
    NavAction.NEXT_SECTION to "Next section",
    NavAction.PAGE_UP to "Page up",
    NavAction.PAGE_DOWN to "Page down",
    NavAction.HOME to "Home",
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
 */
@Composable
fun ControlsScreen(app: AppState) {
    val prefs by app.store.prefs.collectAsState()
    val router = LocalInputRouter.current
    val sel = remember { LinearSelection() }
    var capturing by remember { mutableStateOf<Pair<NavAction, String>?>(null) }
    var testing by remember { mutableStateOf(false) }
    var testHeld by remember { mutableStateOf<PadButton?>(null) }

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

    val nintendo = prefs.input.glyphs == io.github.matiyaaa.fuse.model.GlyphStyle.NINTENDO
    val actions = buildList {
        add(MenuAction("test", "Test buttons", FuseIcons.Joystick, detail = "Every button lights up here and does nothing else. Hold any button to stop", onSelect = { testing = true }))
        add(MenuAction("detect", "Detect my buttons", FuseIcons.ScanSearch, detail = "Sets the layout and confirm button from two presses", onSelect = { app.buttonDetect = true }))
        for ((action, label) in mappable) {
            val buttons = buttonsFor(router, action)
            add(
                MenuAction(
                    id = action.name,
                    label = label,
                    icon = FuseIcons.Gamepad,
                    trailing = Trailing.Value(buttons.joinToString(", ") { it.label(nintendo) }.ifEmpty { "Not mapped" }),
                    onSelect = { capturing = action to label },
                ),
            )
        }
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
    sel.clamp(actions.size)
    InputLayer(enabled = app.focusZone == FocusZone.CONTENT && !app.overlayOpen && capturing == null && !testing) { e -> handleMenuAction(e, actions, sel) }

    BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = Space.gutter)) {
        val wide = maxWidth > 900.dp
        Column(Modifier.fillMaxSize()) {
            Spacer(Modifier.height(Size.hudHeight + Space.l))
            FText("Controls", Fuse.type.display, maxLines = 1)
            FText("Choose an action, then press the button you want for it. The D-pad always moves.", Fuse.type.body, color = Fuse.colors.textMuted)
            Spacer(Modifier.height(Space.l))
            Row(Modifier.weight(1f).padding(bottom = Size.hintHeight + Space.s), horizontalArrangement = Arrangement.spacedBy(Space.xl)) {
                Panel(Modifier.widthIn(max = 720.dp).weight(1f, fill = false)) {
                    MenuList(actions, sel, modifier = Modifier.padding(Space.s))
                }
                if (wide) {
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        SectionLabel("Controller test")
                        Spacer(Modifier.height(Space.m))
                        ControllerTest(nintendo)
                    }
                }
            }
        }
        AnimatedVisibility(
            visible = testing,
            enter = fadeIn(Fuse.motion.fade(150)),
            exit = fadeOut(Fuse.motion.fade(150)),
            modifier = Modifier.align(Alignment.Center),
        ) {
            Panel(raised = true) {
                Column(Modifier.padding(Space.xl).widthIn(min = 360.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    FText("Button test", Fuse.type.title)
                    Spacer(Modifier.height(Space.l))
                    ControllerTest(nintendo)
                    Spacer(Modifier.height(Space.l))
                    FText("Hold any button to stop", Fuse.type.caption, color = Fuse.colors.textFaint)
                    Spacer(Modifier.height(Space.s))
                    FuseButton("Done", selected = true, onClick = { testing = false })
                }
            }
        }
        AnimatedVisibility(
            visible = capturing != null,
            enter = fadeIn(Fuse.motion.fade(150)),
            exit = fadeOut(Fuse.motion.fade(150)),
            modifier = Modifier.align(Alignment.Center),
        ) {
            Panel(raised = true) {
                Column(Modifier.padding(Space.xl).widthIn(min = 320.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    FText("Press a button for", Fuse.type.label, color = Fuse.colors.textMuted)
                    FText(capturing?.second.orEmpty(), Fuse.type.title)
                    Spacer(Modifier.height(Space.m))
                    CaptureCountdown(capturing)
                    Spacer(Modifier.height(Space.s))
                    FText("Waits ${CAPTURE_MS / 1000} seconds", Fuse.type.caption, color = Fuse.colors.textFaint)
                }
            }
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
    Box(Modifier.fillMaxWidth()) { ProgressBar(left, Modifier.fillMaxWidth(), height = 3.dp) }
}

private const val CAPTURE_MS = 5_000L
private const val TEST_EXIT_MS = 1_200L
