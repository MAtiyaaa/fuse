package io.github.matiyaaa.fuse.ui.designsystem.input

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.utf16CodePoint
import io.github.matiyaaa.fuse.model.PadButton

val LocalInputRouter = staticCompositionLocalOf<InputRouter> { error("No InputRouter provided") }

/**
 * Registers a handler for navigation actions while this composable is on screen. The newest layer
 * of the highest [priority] receives actions first; a [modal] layer stops unhandled actions from
 * reaching the layers below it (dialogs, menus). Set [longPress] when holding confirm should become
 * [io.github.matiyaaa.fuse.model.NavAction.REORDER] here, and [repeats] for other actions that should
 * repeat while their button is held (directions always do).
 */
@Composable
fun InputLayer(
    priority: Int = LayerPriority.SCREEN,
    enabled: Boolean = true,
    modal: Boolean = false,
    longPress: Boolean = false,
    repeats: Set<io.github.matiyaaa.fuse.model.NavAction> = emptySet(),
    onAction: (NavEvent) -> NavResult,
) {
    val router = LocalInputRouter.current
    val handler = rememberUpdatedState(onAction)
    val holder = remember { arrayOfNulls<InputRouter.Registration>(1) }
    DisposableEffect(router, priority) {
        val registration = router.register(priority, modal, longPress) { handler.value(it) }
        registration.update(enabled, modal, longPress, { handler.value(it) }, repeats)
        holder[0] = registration
        onDispose {
            registration.remove()
            holder[0] = null
        }
    }
    SideEffect { holder[0]?.update(enabled, modal, longPress, { handler.value(it) }, repeats) }
}

/** Maps a Compose key to Fuse's physical button names (desktop keyboards, some Android keyboards). */
fun padButtonFor(key: Key): PadButton? = when (key) {
    Key.DirectionUp -> PadButton.KEY_UP
    Key.DirectionDown -> PadButton.KEY_DOWN
    Key.DirectionLeft -> PadButton.KEY_LEFT
    Key.DirectionRight -> PadButton.KEY_RIGHT
    Key.Enter, Key.NumPadEnter, Key.DirectionCenter -> PadButton.KEY_ENTER
    Key.Spacebar -> PadButton.KEY_SPACE
    Key.Escape -> PadButton.KEY_ESCAPE
    Key.Backspace -> PadButton.KEY_BACKSPACE
    Key.Tab -> PadButton.KEY_TAB
    Key.Slash -> PadButton.KEY_SLASH
    Key.F -> PadButton.KEY_F
    Key.Q -> PadButton.KEY_Q
    Key.E -> PadButton.KEY_E
    Key.M -> PadButton.KEY_M
    Key.PageUp -> PadButton.KEY_PAGE_UP
    Key.PageDown -> PadButton.KEY_PAGE_DOWN
    Key.MoveHome -> PadButton.KEY_HOME
    Key.ButtonA -> PadButton.A
    Key.ButtonB -> PadButton.B
    Key.ButtonX -> PadButton.X
    Key.ButtonY -> PadButton.Y
    Key.ButtonL1 -> PadButton.L1
    Key.ButtonR1 -> PadButton.R1
    Key.ButtonL2 -> PadButton.L2
    Key.ButtonR2 -> PadButton.R2
    Key.ButtonThumbLeft -> PadButton.L3
    Key.ButtonThumbRight -> PadButton.R3
    Key.ButtonStart -> PadButton.START
    Key.ButtonSelect -> PadButton.SELECT
    Key.ButtonMode -> PadButton.MODE
    else -> null
}

private val gamepadKeys = setOf(
    PadButton.A, PadButton.B, PadButton.X, PadButton.Y, PadButton.L1, PadButton.R1, PadButton.L2, PadButton.R2,
    PadButton.L3, PadButton.R3, PadButton.START, PadButton.SELECT, PadButton.MODE,
)

/**
 * Feeds a Compose key event to the router. Returns true when the key belongs to Fuse navigation, so
 * callers can stop it from reaching text fields or default focus handling.
 */
fun InputRouter.handleKeyEvent(event: KeyEvent): Boolean {
    if (typeInto(event)) return true
    val button = padButtonFor(event.key) ?: return false
    val source = if (button in gamepadKeys) InputSource.GAMEPAD else InputSource.KEYBOARD
    when (event.type) {
        KeyEventType.KeyDown -> press(button, source)
        KeyEventType.KeyUp -> release(button, source)
        else -> return false
    }
    return true
}

/**
 * Hardware typing while a text field is open: printable characters, Backspace and Enter go to
 * [InputRouter.textInput]. Arrow keys, Escape and Tab still navigate, and shortcuts with Ctrl, Alt or
 * Meta are left alone.
 */
private fun InputRouter.typeInto(event: KeyEvent): Boolean {
    val input = textInput ?: return false
    // Ctrl+V (Cmd+V) pastes; other shortcuts are left alone.
    if ((event.isCtrlPressed || event.isMetaPressed) && event.key == Key.V) {
        if (event.type == KeyEventType.KeyDown) input.paste()
        return true
    }
    if (event.isCtrlPressed || event.isAltPressed || event.isMetaPressed) return false
    val down = event.type == KeyEventType.KeyDown
    when (event.key) {
        Key.Backspace -> {
            if (down) input.backspace()
            return true
        }
        Key.Enter, Key.NumPadEnter -> {
            if (down) input.submit()
            return true
        }
        Key.Delete -> {
            if (down) input.deleteForward()
            return true
        }
        Key.MoveHome -> {
            if (down) input.home()
            return true
        }
        Key.MoveEnd -> {
            if (down) input.end()
            return true
        }
    }
    val codePoint = event.utf16CodePoint
    // Control characters and AWT's CHAR_UNDEFINED are not text.
    if (codePoint <= 0x1F || codePoint == 0x7F || codePoint == 0xFFFF) return false
    if (down) input.type(codePoint.toChar().toString())
    return true
}
