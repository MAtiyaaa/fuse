package io.github.matiyaaa.fuse.ui.designsystem.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.matiyaaa.fuse.ui.designsystem.icons.ButtonGlyph
import io.github.matiyaaa.fuse.ui.designsystem.icons.HintButton
import io.github.matiyaaa.fuse.ui.designsystem.theme.Durations
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.Size
import io.github.matiyaaa.fuse.ui.designsystem.theme.Space

@Immutable
data class Hint(val button: HintButton, val label: String)

/**
 * The quiet line of button hints at the bottom right. Changes crossfade so the line never jumps
 * while you move between items that offer different actions.
 */
@Composable
fun HintBar(hints: List<Hint>, modifier: Modifier = Modifier) {
    val c = Fuse.colors
    val motion = Fuse.motion
    AnimatedContent(
        targetState = hints,
        modifier = modifier.height(Size.hintHeight),
        transitionSpec = { fadeIn(motion.fade(Durations.FAST)) togetherWith fadeOut(motion.fade(Durations.INSTANT)) },
        contentAlignment = Alignment.CenterEnd,
        label = "hints",
    ) { list ->
        Row(
            horizontalArrangement = Arrangement.spacedBy(Space.l),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for (h in list) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(Space.s),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ButtonGlyph(h.button, size = 22.dp, color = c.text.copy(alpha = 0.9f), emphasized = h.button == HintButton.CONFIRM)
                    FText(h.label, Fuse.type.label, color = c.textMuted, maxLines = 1)
                }
            }
        }
    }
}
