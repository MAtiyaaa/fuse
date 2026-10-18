package io.github.matiyaaa.fuse.ui.designsystem.input

import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isBackPressed
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import io.github.matiyaaa.fuse.model.NavAction

/** The router that knows whether the mouse really moved; null (hover always counts) where none is given. */
val LocalPointerRouter = staticCompositionLocalOf<InputRouter?> { null }

/**
 * The mouse pointing at this element makes it the highlighted one ([onHover]), as a controller's
 * D-pad would: then one click acts on it. Only a mouse that really moves counts, so a pointer
 * resting on the screen never takes the highlight while a controller or the keyboard moves it, and
 * content scrolling under it changes nothing. Touch never hovers, and neither does a pointer held down
 * (a drag, which is how a finger on a computer's touch screen arrives).
 */
fun Modifier.mouseHover(enabled: Boolean = true, onHover: () -> Unit): Modifier = if (!enabled) this else composed {
    val router = LocalPointerRouter.current
    val latest by rememberUpdatedState(onHover)
    pointerInput(router) {
        awaitPointerEventScope {
            var inside = false
            while (true) {
                val e = awaitPointerEvent()
                when (e.type) {
                    PointerEventType.Exit -> inside = false
                    PointerEventType.Enter, PointerEventType.Move -> {
                        // Anything that can point without touching (a mouse, a trackpad, a pointer some compositors report
                        // as Unknown) hovers; only a finger never does.
                        val mouse = e.changes.any { it.type != PointerType.Touch }
                        // A button held is a drag (and on a computer's touch screen, a finger dragging the
                        // list): what passes under it is being scrolled past, not pointed at.
                        val held = e.changes.any { it.pressed }
                        // Once per visit, and only when the person moved the mouse to get here.
                        if (mouse && !held && !inside && (router == null || router.mouseJustMoved())) {
                            inside = true
                            latest()
                        } else if (!mouse || held) {
                            inside = false
                        }
                    }
                    else -> Unit
                }
            }
        }
    }
}

/**
 * What a computer mouse's other buttons do, pressed anywhere: its back button goes back, and the
 * right one opens the options of what it points at (hovering makes that the highlighted one), as
 * Options does on a controller. Null for anything else (the main button clicks as it always does).
 * The right button does nothing while typing, where Options would type.
 */
object MouseButtons {
    fun action(buttons: PointerButtons, typing: Boolean = false): NavAction? = when {
        buttons.isBackPressed -> NavAction.BACK
        buttons.isSecondaryPressed && !buttons.isPrimaryPressed && !typing -> NavAction.CONTEXT
        else -> null
    }
}
