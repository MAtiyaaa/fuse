package io.github.matiyaaa.fuse.ui.designsystem.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.matiyaaa.fuse.ui.designsystem.shape.PillShape
import io.github.matiyaaa.fuse.ui.designsystem.theme.Durations
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.Space

/**
 * Which of [count] pages shows: the current one is a short accent pill, the others small dots. Each
 * dot is a touch target that goes to its page ([labels] name them for screen readers).
 */
@Composable
fun PageDots(count: Int, current: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier, labels: List<String> = emptyList()) {
    val c = Fuse.colors
    val motion = Fuse.motion
    Row(
        modifier
            .clip(PillShape)
            .background(c.ink.copy(alpha = 0.45f))
            .padding(horizontal = Space.xs),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(count) { i ->
            val on = i == current
            val width by animateDpAsState(if (on) 18.dp else 6.dp, motion.tween(Durations.BASE), label = "dot width")
            val color by animateColorAsState(if (on) c.accent else c.text.copy(alpha = 0.4f), motion.tween(Durations.BASE), label = "dot color")
            Box(
                Modifier
                    .size(width = 28.dp, height = 24.dp)
                    .clickable(remember { MutableInteractionSource() }, null) { onSelect(i) }
                    .semantics {
                        selected = on
                        labels.getOrNull(i)?.let { contentDescription = it }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.width(width).height(6.dp).clip(PillShape).background(color))
            }
        }
    }
}
