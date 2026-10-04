package io.github.matiyaaa.fuse.ui.shell.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.fuseline.Durations
import io.github.matiyaaa.fuse.ui.fuseline.fuselineFloat
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * While the startup animation (or setup's opening) plays on one screen, the other stays dark, so
 * the eye goes to the animation; what it shows fades in once the animation is done.
 */
internal object OpeningVeil {
    /** True while an opening plays on the screen that has the menus. */
    val showing = MutableStateFlow(false)
}

/** Dark, and deaf to touches, while [OpeningVeil.showing]; then what is under it fades in. */
@Composable
internal fun Modifier.veiledWhileOpening(): Modifier {
    val showing by OpeningVeil.showing.collectAsState()
    val veil = fuselineFloat(if (showing) 1f else 0f, Fuse.motion.tween(Durations.DELIBERATE), label = "openingVeil")
    val drawn = drawWithContent {
        drawContent()
        val v = veil.value
        if (v > 0.002f) drawRect(Color.Black, alpha = v)
    }
    return if (!showing) this.then(drawn) else this.then(drawn).pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
        }
    }
}
