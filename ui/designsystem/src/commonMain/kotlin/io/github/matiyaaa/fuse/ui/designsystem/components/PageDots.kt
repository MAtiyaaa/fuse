package io.github.matiyaaa.fuse.ui.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.matiyaaa.fuse.ui.designsystem.shape.PillShape
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.Space
import io.github.matiyaaa.fuse.ui.fuseline.Durations
import io.github.matiyaaa.fuse.ui.fuseline.fuselineFloat
import io.github.matiyaaa.fuse.ui.fuseline.tween

/**
 * Which of [count] pages shows: small dots, with the current page as a short accent pill that
 * glides to the next page and stretches on the way (its front leads, its back follows). Each dot is
 * a touch target that goes to its page, growing a little under a mouse pointer; [labels] name them
 * for screen readers.
 */
@Composable
fun PageDots(count: Int, current: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier, labels: List<String> = emptyList()) {
    val c = Fuse.colors
    val glide = rememberStretchGlide(current.coerceIn(0, (count - 1).coerceAtLeast(0)).toFloat(), span = 0f)
    val accent = c.accent
    Row(
        modifier
            .clip(PillShape)
            .background(c.ink.copy(alpha = 0.45f))
            .padding(horizontal = Space.xs)
            .drawWithContent {
                drawContent()
                if (count <= 0) return@drawWithContent
                // The pill is drawn over the dots: centred on the current slot, stretched between
                // the two slots it is travelling between.
                val pad = Space.xs.toPx()
                val slot = SLOT_WIDTH.toPx()
                val half = PILL_WIDTH.toPx() / 2
                val h = DOT.toPx()
                val from = pad + slot * (glide.start + 0.5f) - half
                val to = pad + slot * (glide.end + 0.5f) + half
                drawRoundRect(accent, Offset(from, (size.height - h) / 2), androidx.compose.ui.geometry.Size(to - from, h), CornerRadius(h / 2))
            },
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(count) { i ->
            val on = i == current
            val interaction = remember { MutableInteractionSource() }
            val hovered by interaction.collectIsHoveredAsState()
            val grow by fuselineFloat(if (hovered && !on) 1f else 0f, Fuse.motion.tween(Durations.FAST), label = "dot hover")
            val dot = c.text.copy(alpha = 0.4f)
            Box(
                Modifier
                    .size(width = SLOT_WIDTH, height = SLOT_HEIGHT)
                    .clickable(interaction, null, role = Role.Tab) { onSelect(i) }
                    .semantics {
                        selected = on
                        labels.getOrNull(i)?.let { contentDescription = it }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Spacer(
                    Modifier.size(DOT).drawBehind {
                        drawCircle(dot.copy(alpha = dot.alpha + 0.3f * grow), radius = size.minDimension / 2 * (1f + 0.33f * grow))
                    },
                )
            }
        }
    }
}

private val SLOT_WIDTH = 28.dp
private val SLOT_HEIGHT = 24.dp
private val PILL_WIDTH = 18.dp
private val DOT = 6.dp
