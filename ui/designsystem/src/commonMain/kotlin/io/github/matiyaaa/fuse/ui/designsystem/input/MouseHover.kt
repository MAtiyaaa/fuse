package io.github.matiyaaa.fuse.ui.designsystem.input

import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput

/** The router that knows whether the mouse really moved; null (hover always counts) where none is given. */
val LocalPointerRouter = staticCompositionLocalOf<InputRouter?> { null }

/**
 * The mouse pointing at this element makes it the highlighted one ([onHover]), as a controller's
 * D-pad would: then one click acts on it. Only a mouse that really moves counts, so a pointer
 * resting on the screen never takes the highlight while a controller or the keyboard moves it, and
 * content scrolling under it changes nothing. Touch never hovers.
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
                        val mouse = e.changes.any { it.type == PointerType.Mouse }
                        // Once per visit, and only when the person moved the mouse to get here.
                        if (mouse && !inside && (router == null || router.mouseJustMoved())) {
                            inside = true
                            latest()
                        } else if (!mouse) {
                            inside = false
                        }
                    }
                    else -> Unit
                }
            }
        }
    }
}
